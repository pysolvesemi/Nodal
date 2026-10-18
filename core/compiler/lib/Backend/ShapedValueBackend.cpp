#include "nodal/Backend/ShapedValueBackend.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/ADT/DenseSet.h"

#include <algorithm>
#include <limits>

using namespace mlir;
namespace nodal {
// Shape semantics and proofs stay in the native shape owner. The target owns
// only deterministic compact row-major arithmetic, never per-lane expansion.
FailureOr<std::string> staticTargetArithmetic(llvm::StringRef left, char operation,
                                              llvm::StringRef right) {
  int64_t lhs = 0, rhs = 0, result = 0;
  const bool constantLeft = !left.getAsInteger(10, lhs);
  const bool constantRight = !right.getAsInteger(10, rhs);
  if (constantLeft && constantRight) {
    const bool overflow = operation == '*' ? __builtin_mul_overflow(lhs, rhs, &result)
                                           : __builtin_add_overflow(lhs, rhs, &result);
    if (overflow)
      return failure();
    return std::to_string(result);
  }
  if (operation == '*' && ((constantLeft && lhs == 0) || (constantRight && rhs == 0)))
    return std::string("0");
  if (constantLeft && lhs == (operation == '*' ? 1 : 0))
    return right.str();
  if (constantRight && rhs == (operation == '*' ? 1 : 0))
    return left.str();
  return (llvm::Twine("(") + left + " " + (operation == '*' ? "*" : "+") + " " + right + ")").str();
}

namespace {
constexpr int64_t maximumIndex = std::numeric_limits<int32_t>::max();
bool portable(ParameterIntegerBounds bounds) {
  return bounds.lower >= std::numeric_limits<int32_t>::min() && bounds.upper <= maximumIndex &&
         bounds.lower <= bounds.upper;
}
// The source prover establishes each mathematical intermediate. Checking only
// its final result would miss expressions such as (huge * huge) % small. Follow
// retained parameter initializers too: their emitted arithmetic has the same
// target integer width even when the declared parameter range itself fits.
bool portableStaticDAG(Value value, llvm::DenseSet<Value> &active,
                       llvm::DenseSet<Operation *> &parameters);
bool portableParameterInitializer(Operation *parameter, llvm::DenseSet<Value> &active,
                                  llvm::DenseSet<Operation *> &parameters) {
  if (!parameters.insert(parameter).second)
    return false;
  bool valid = false;
  auto symbol = parameter->getAttrOfType<StringAttr>("sym_name");
  Operation *owner = parameter->getParentOp();
  for (Operation &candidate : owner->getRegion(0).front()) {
    auto reference = candidate.getAttrOfType<FlatSymbolRefAttr>("parameter");
    if (llvm::isa<ParameterValueOp>(candidate) && symbol && reference &&
        reference.getValue() == symbol.getValue() && candidate.getNumOperands() == 1) {
      valid = portableStaticDAG(candidate.getOperand(0), active, parameters);
      parameters.erase(parameter);
      return valid;
    }
  }
  if (auto literal = parameter->getAttrOfType<IntegerAttr>("default_value"))
    valid = literal.getValue().isSignedIntN(32);
  parameters.erase(parameter);
  return valid;
}
bool portableStaticDAG(Value value, llvm::DenseSet<Value> &active,
                       llvm::DenseSet<Operation *> &parameters) {
  if (!value || !active.insert(value).second)
    return false;
  auto bounds = inferParameterIntegerBounds(value);
  Operation *definition = value.getDefiningOp();
  bool valid = definition && succeeded(bounds) && portable(*bounds);
  if (valid && llvm::isa<ConstExprOp>(definition))
    for (Value operand : definition->getOperands())
      if (!portableStaticDAG(operand, active, parameters)) {
        valid = false;
        break;
      }
  if (valid && llvm::isa<ConstParameterRefOp>(definition)) {
    // Width checking serves shape and generated-domain consumers. The caller's
    // native proof owns effect capability; requiring a shape envelope here
    // would incorrectly reject topology-only parameters in generated bounds.
    Operation *module = definition->getParentOp();
    auto reference = definition->getAttrOfType<FlatSymbolRefAttr>("parameter");
    Operation *parameter = llvm::isa<ModuleOp>(module) && reference
                               ? SymbolTable::lookupSymbolIn(module, reference.getValue())
                               : nullptr;
    valid = llvm::isa_and_nonnull<ParameterOp>(parameter) && parameter->getParentOp() == module &&
            portableParameterInitializer(parameter, active, parameters);
  }
  active.erase(value);
  return valid;
}
FailureOr<TargetShapeExpression> targetDimension(Operation *module, llvm::StringRef token) {
  auto dimension = resolveStaticShapeDimension(module, token);
  if (failed(dimension) || !portable(dimension->bounds))
    return failure();
  FailureOr<std::string> rendered = std::to_string(dimension->literal);
  if (dimension->parameter) {
    auto name = dimension->parameter->getAttrOfType<StringAttr>("sym_name");
    if (!name)
      return failure();
    llvm::DenseSet<Value> active;
    llvm::DenseSet<Operation *> parameters;
    if (!portableParameterInitializer(dimension->parameter, active, parameters))
      return failure();
    rendered = name.getValue().str();
  } else if (dimension->expression) {
    llvm::DenseSet<Value> active;
    llvm::DenseSet<Operation *> parameters;
    if (!portableStaticDAG(dimension->expression, active, parameters))
      return failure();
    rendered = renderParameterConstantExpression(dimension->expression);
  }
  if (failed(rendered))
    return failure();
  return TargetShapeExpression{*rendered, dimension->bounds};
}
FailureOr<TargetShapeExpression> product(const TargetShapeExpression &left,
                                         const TargetShapeExpression &right) {
  int64_t minimum = 0, maximum = 0;
  if (left.bounds.lower < 0 || right.bounds.lower < 0 ||
      __builtin_mul_overflow(left.bounds.lower, right.bounds.lower, &minimum) ||
      __builtin_mul_overflow(left.bounds.upper, right.bounds.upper, &maximum) ||
      maximum > maximumIndex)
    return failure();
  auto text = staticTargetArithmetic(left.text, '*', right.text);
  if (failed(text))
    return failure();
  return TargetShapeExpression{*text, {minimum, maximum}};
}
} // namespace

LogicalResult
verifyTargetStructuralIntegerExpression(const InstantiatedIntegerExpression &expression) {
  if (!expression.root || !portable(expression.bounds))
    return failure();
  llvm::SmallVector<const InstantiatedIntegerNode *> work{expression.root.get()};
  llvm::DenseSet<const InstantiatedIntegerNode *> visited;
  llvm::DenseSet<Value> active;
  llvm::DenseSet<Operation *> parameters;
  while (!work.empty()) {
    const auto *node = work.pop_back_val();
    if (!visited.insert(node).second)
      continue;
    if (!portable(node->bounds))
      return failure();
    if (node->parameter && !portableParameterInitializer(node->parameter, active, parameters))
      return failure();
    if (node->origin && node->origin->getNumResults() == 1 &&
        llvm::isa<ConstExprOp, ConstLiteralOp, ConstParameterRefOp>(node->origin) &&
        !portableStaticDAG(node->origin->getResult(0), active, parameters))
      return failure();
    for (const auto &operand : node->operands)
      work.push_back(operand.get());
  }
  return success();
}

FailureOr<TargetShapedLayout> resolveTargetShapedLayout(Operation *module,
                                                        llvm::StringRef dimensions) {
  auto maximum = inferStaticShapeMaximumCardinality(module, dimensions);
  if (failed(maximum) || *maximum > maximumIndex)
    return failure();
  TargetShapedLayout result;
  result.count = {"1", {1, 1}};
  llvm::SmallVector<llvm::StringRef> tokens;
  dimensions.split(tokens, ',', -1, true);
  for (llvm::StringRef token : tokens) {
    auto dimension = targetDimension(module, token);
    if (failed(dimension))
      return failure();
    auto count = product(result.count, *dimension);
    if (failed(count))
      return failure();
    result.dimensions.push_back(*dimension);
    result.count = *count;
  }
  return result;
}

FailureOr<TargetShapeExpression> renderShapeStaticIndex(Operation *module, Value value) {
  auto bounds = inferStaticShapeIndexBounds(module, value);
  if (failed(bounds) || !portable(*bounds))
    return failure();
  Operation *definition = value.getDefiningOp();
  if (definition && definition->getName().getStringRef() == "nodal.constant")
    return TargetShapeExpression{std::to_string(bounds->lower), *bounds};
  llvm::DenseSet<Value> active;
  llvm::DenseSet<Operation *> parameters;
  if (!portableStaticDAG(value, active, parameters))
    return failure();
  auto rendered = renderParameterConstantExpression(value);
  if (failed(rendered))
    return failure();
  return TargetShapeExpression{*rendered, *bounds};
}

namespace {
FailureOr<TargetShapeExpression> sum(const TargetShapeExpression &left,
                                     const TargetShapeExpression &right) {
  int64_t minimum = 0, maximum = 0;
  if (__builtin_add_overflow(left.bounds.lower, right.bounds.lower, &minimum) ||
      __builtin_add_overflow(left.bounds.upper, right.bounds.upper, &maximum) ||
      !portable({minimum, maximum}))
    return failure();
  auto text = staticTargetArithmetic(left.text, '+', right.text);
  if (failed(text))
    return failure();
  return TargetShapeExpression{*text, {minimum, maximum}};
}
FailureOr<TargetShapeExpression> flatten(llvm::ArrayRef<TargetShapeExpression> coordinates,
                                         const TargetShapedLayout &shape) {
  if (coordinates.size() != shape.dimensions.size())
    return failure();
  TargetShapeExpression result{"0", {0, 0}};
  for (auto [coordinate, dimension] : llvm::zip(coordinates, shape.dimensions)) {
    auto term = product(result, dimension);
    if (failed(term))
      return failure();
    auto added = sum(*term, coordinate);
    if (failed(added))
      return failure();
    result = *added;
  }
  return result;
}
FailureOr<llvm::SmallVector<TargetShapeExpression>> unflatten(const TargetShapeExpression &ordinal,
                                                              const TargetShapedLayout &shape) {
  llvm::SmallVector<TargetShapeExpression> result;
  for (size_t axis = 0; axis < shape.dimensions.size(); ++axis) {
    TargetShapeExpression stride{"1", {1, 1}};
    for (const auto &dimension : llvm::drop_begin(shape.dimensions, axis + 1)) {
      auto multiplied = product(stride, dimension);
      if (failed(multiplied))
        return failure();
      stride = *multiplied;
    }
    std::string text = ordinal.text;
    if (stride.text != "1")
      text = "(" + text + " / " + stride.text + ")";
    if (axis != 0)
      text = "(" + text + " % " + shape.dimensions[axis].text + ")";
    // The verified row-major map is applied only over this shape's logical
    // domain. Its coordinate envelope comes from that semantic domain, not a
    // parameter default or an algebraic cancellation in the target spelling.
    result.push_back({text, {0, shape.dimensions[axis].bounds.upper - 1}});
  }
  return result;
}
} // namespace

FailureOr<ShapedProjectionProgram> renderShapedProjectionProgram(
    const StaticShapeProjection &reference, llvm::StringRef ordinal,
    llvm::function_ref<std::string(unsigned)> stageName,
    llvm::function_ref<FailureOr<TargetShapeExpression>(Value, Operation *)> scopedIndex) {
  Operation *operation = reference.root.getDefiningOp();
  auto module = operation ? operation->getParentOfType<ModuleOp>() : ModuleOp();
  if (!module)
    return failure();
  ShapedProjectionProgram program;
  program.result = ordinal.str();
  llvm::DenseSet<Value> visited;
  Value cursor =
      reference.projections.empty() ? reference.root : reference.projections.back()->getResult(0);
  for (Operation *projection : llvm::reverse(reference.projections)) {
    if (!llvm::isa<ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(projection) ||
        projection->getNumResults() != 1 || projection->getResult(0) != cursor ||
        !visited.insert(cursor).second || projection->getNumOperands() < 1)
      return failure();
    auto input = llvm::dyn_cast<ShapedType>(projection->getOperand(0).getType());
    auto output = llvm::dyn_cast<ShapedType>(cursor.getType());
    if (!input)
      return failure();
    auto inputShape = resolveTargetShapedLayout(module, input.getDimensions());
    if (failed(inputShape))
      return failure();
    llvm::SmallVector<TargetShapeExpression> coordinates;
    if (output) {
      auto outputShape = resolveTargetShapedLayout(module, output.getDimensions());
      if (failed(outputShape))
        return failure();
      if (!llvm::isa<ShapeViewOp>(projection)) {
        auto expanded =
            unflatten({program.result, {0, outputShape->count.bounds.upper - 1}}, *outputShape);
        if (failed(expanded))
          return failure();
        coordinates = *expanded;
      }
    }
    if (llvm::isa<ShapeViewOp>(projection)) {
      cursor = projection->getOperand(0);
      continue;
    }
    if (llvm::isa<ShapeIndexOp>(projection)) {
      llvm::SmallVector<TargetShapeExpression> prefix;
      for (Value index : projection->getOperands().drop_front()) {
        auto rendered =
            scopedIndex ? scopedIndex(index, projection) : renderShapeStaticIndex(module, index);
        if (failed(rendered))
          return failure();
        prefix.push_back(*rendered);
      }
      prefix.append(coordinates.begin(), coordinates.end());
      coordinates = std::move(prefix);
    } else {
      auto axis = projection->getAttrOfType<IntegerAttr>("axis");
      if (!axis || axis.getInt() < 0 || uint64_t(axis.getInt()) >= coordinates.size() ||
          projection->getNumOperands() != 4)
        return failure();
      auto renderBound = [&](Value value) {
        return scopedIndex ? scopedIndex(value, projection) : renderShapeStaticIndex(module, value);
      };
      auto lower = renderBound(projection->getOperand(1));
      auto upper = renderBound(projection->getOperand(2));
      auto length = renderBound(projection->getOperand(3));
      if (failed(lower) || failed(upper) || failed(length))
        return failure();
      auto shifted = sum(coordinates[axis.getInt()], *lower);
      if (failed(shifted))
        return failure();
      shifted->bounds.upper =
          std::min(shifted->bounds.upper, inputShape->dimensions[axis.getInt()].bounds.upper - 1);
      coordinates[axis.getInt()] = *shifted;
    }
    auto flattened = flatten(coordinates, *inputShape);
    std::string temporary = stageName(program.steps.size());
    if (failed(flattened) || temporary.empty())
      return failure();
    program.steps.push_back({projection, flattened->text, temporary});
    program.result = std::move(temporary);
    cursor = projection->getOperand(0);
  }
  auto rootShape = llvm::dyn_cast<ShapedType>(cursor.getType());
  if (cursor != reference.root || !rootShape ||
      failed(resolveTargetShapedLayout(module, rootShape.getDimensions())))
    return failure();
  program.root = cursor;
  return program;
}

FailureOr<ShapedProjectionProgram>
renderShapedProjectionProgram(Value value, llvm::StringRef ordinal, llvm::StringRef temporary) {
  auto reference = resolveStaticShapeProjection(value, value.getDefiningOp());
  if (failed(reference) ||
      !llvm::isa_and_nonnull<AnalogVariableOp, AnalogVariableReadOp, AnalogArrayFillOp>(
          reference->root.getDefiningOp()))
    return failure();
  return renderShapedProjectionProgram(*reference, ordinal,
                                       [&](unsigned) { return temporary.str(); });
}
} // namespace nodal
