#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/SmallPtrSet.h"

#include <algorithm>
#include <limits>
#include <map>
#include <set>
#include <vector>

using namespace mlir;
namespace nodal {
namespace {
// Consecutive Vec layers share one physical row-major coordinate space. Keep
// nominal/aggregate leaf types intact; the operation verifiers still enforce
// the exact authored input/result types at every projection boundary.
struct PhysicalShape {
  Type element;
  llvm::SmallVector<llvm::StringRef> dimensions;
};
std::optional<PhysicalShape> physicalShape(Type type) {
  PhysicalShape result;
  unsigned depth = 0;
  while (auto shaped = llvm::dyn_cast<ShapedType>(type)) {
    if (++depth > 512)
      return std::nullopt;
    shaped.getDimensions().split(result.dimensions, ',', -1, true);
    type = shaped.getElementType();
  }
  result.element = type;
  return result;
}
Type shapeElement(Type type) {
  auto shape = physicalShape(type);
  return shape ? shape->element : Type();
}
bool isProjection(Operation *operation) {
  return llvm::isa_and_nonnull<ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(operation);
}
bool precedesLexically(Operation *definition, Operation *use) {
  if (definition == use)
    return true;
  for (Operation *anchor = use; anchor; anchor = anchor->getParentOp())
    if (anchor->getBlock() == definition->getBlock())
      return definition->isBeforeInBlock(anchor);
  return false;
}
std::string pointerKey(Operation *op) { return std::to_string(reinterpret_cast<uintptr_t>(op)); }
FailureOr<StaticShapeIndexIdentity> indexIdentity(Operation *module, Value value,
                                                  Operation *use = nullptr) {
  if (!use)
    return resolveStaticShapeIndexIdentity(module, value);
  auto proof = resolveShapeIndexProof(value, use);
  if (failed(proof))
    return failure();
  return proof->identity;
}
FailureOr<ParameterIntegerBounds> indexBounds(Operation *module, Value value, Operation *use) {
  auto proof = resolveShapeIndexProof(value, use);
  if (failed(proof) || !proof->attainedBounds)
    return failure();
  return *proof->attainedBounds;
}
std::string identityPrefix(StaticShapeIndexIdentity::Kind kind) {
  return kind == StaticShapeIndexIdentity::Parameter         ? "p:"
         : kind == StaticShapeIndexIdentity::Induction       ? "i:"
         : kind == StaticShapeIndexIdentity::IterationAffine ? "a:"
                                                             : "e:";
}
FailureOr<std::string> indexKey(Operation *module, Value value, Operation *use) {
  auto identity = indexIdentity(module, value, use);
  if (failed(identity))
    return failure();
  if (identity->kind == StaticShapeIndexIdentity::Literal)
    return "l:" + std::to_string(identity->literal);
  return identityPrefix(identity->kind) + pointerKey(identity->root);
}
FailureOr<std::string> dimensionsKey(Operation *module, Type type) {
  auto shape = physicalShape(type);
  if (!shape)
    return failure();
  if (shape->dimensions.empty())
    return std::string("scalar");
  const auto &tokens = shape->dimensions;
  std::string result;
  for (auto token : tokens) {
    auto dimension = resolveStaticShapeDimension(module, token.trim());
    if (failed(dimension))
      return failure();
    if (!result.empty())
      result += ',';
    if (dimension->parameter)
      result += "p:" + pointerKey(dimension->parameter);
    else if (dimension->expression)
      result += "e:" + pointerKey(dimension->expression.getDefiningOp());
    else
      result += "l:" + std::to_string(dimension->literal);
  }
  return result;
}
// A deliberately small normal form for contiguous selections: sums of
// products of actual static identities. It recognizes prefix/leading-slice
// aliases without expanding noncontiguous ordinal formulas or proving algebra
// between independently captured compound-expression roots.
struct StaticProduct {
  int64_t coefficient = 1;
  std::vector<std::string> factors;
};
using StaticSum = std::map<std::vector<std::string>, int64_t>;
std::optional<StaticProduct> multiplyProduct(StaticProduct left, const StaticProduct &right) {
  if (left.coefficient &&
      right.coefficient > std::numeric_limits<int64_t>::max() / left.coefficient)
    return std::nullopt;
  left.coefficient *= right.coefficient;
  left.factors.insert(left.factors.end(), right.factors.begin(), right.factors.end());
  std::sort(left.factors.begin(), left.factors.end());
  return left;
}
std::optional<StaticProduct> dimensionProduct(Operation *module, Type type, unsigned first = 0) {
  StaticProduct result;
  auto shape = physicalShape(type);
  if (!shape)
    return std::nullopt;
  const auto &dimensions = shape->dimensions;
  if (first > dimensions.size())
    return std::nullopt;
  for (auto token : llvm::ArrayRef<llvm::StringRef>(dimensions).drop_front(first)) {
    auto dimension = resolveStaticShapeDimension(module, token.trim());
    if (failed(dimension))
      return std::nullopt;
    StaticProduct factor;
    if (dimension->parameter)
      factor.factors.push_back("p:" + pointerKey(dimension->parameter));
    else if (dimension->expression)
      factor.factors.push_back("e:" + pointerKey(dimension->expression.getDefiningOp()));
    else
      factor.coefficient = dimension->literal;
    auto multiplied = multiplyProduct(result, factor);
    if (!multiplied)
      return std::nullopt;
    result = std::move(*multiplied);
  }
  return result;
}
std::optional<ParameterIntegerBounds> dimensionProductBounds(Operation *module, Type type,
                                                             unsigned first) {
  ParameterIntegerBounds result{1, 1};
  auto shape = physicalShape(type);
  if (!shape)
    return std::nullopt;
  const auto &dimensions = shape->dimensions;
  if (first > dimensions.size())
    return std::nullopt;
  for (auto token : llvm::ArrayRef<llvm::StringRef>(dimensions).drop_front(first)) {
    auto dimension = resolveStaticShapeDimension(module, token.trim());
    if (failed(dimension) || dimension->bounds.lower <= 0 ||
        dimension->bounds.upper < dimension->bounds.lower ||
        result.upper > std::numeric_limits<int64_t>::max() / dimension->bounds.upper)
      return std::nullopt;
    result.lower *= dimension->bounds.lower;
    result.upper *= dimension->bounds.upper;
  }
  return result;
}
void addOffsetBounds(std::optional<ParameterIntegerBounds> &offset, Operation *module, Value index,
                     Type inputType, unsigned strideAxis, Operation *use) {
  if (!offset)
    return;
  auto position = indexBounds(module, index, use);
  auto stride = dimensionProductBounds(module, inputType, strideAxis);
  if (failed(position) || !stride || position->lower < 0 ||
      position->upper > (std::numeric_limits<int64_t>::max() - offset->upper) / stride->upper) {
    offset.reset();
    return;
  }
  offset->lower += position->lower * stride->lower;
  offset->upper += position->upper * stride->upper;
}
std::optional<StaticProduct> indexProduct(Operation *module, Value value, Operation *use) {
  auto identity = indexIdentity(module, value, use);
  if (failed(identity))
    return std::nullopt;
  StaticProduct result;
  if (identity->kind == StaticShapeIndexIdentity::Literal)
    result.coefficient = identity->literal;
  else
    result.factors.push_back(identityPrefix(identity->kind) + pointerKey(identity->root));
  return result;
}
bool addProduct(StaticSum &sum, const StaticProduct &term) {
  if (term.coefficient == 0)
    return true;
  auto &coefficient = sum[term.factors];
  if (term.coefficient < 0 || coefficient > std::numeric_limits<int64_t>::max() - term.coefficient)
    return false;
  coefficient += term.coefficient;
  return true;
}
std::string productKey(const StaticProduct &product) {
  std::string result = std::to_string(product.coefficient);
  for (const auto &factor : product.factors)
    result += "*" + factor;
  return result;
}
std::string linearKey(const StaticSum &offset, const StaticProduct &count) {
  std::string result = "linear[" + productKey(count) + "](";
  for (const auto &[factors, coefficient] : offset)
    result += productKey({coefficient, factors}) + ";";
  return result + ")";
}
std::optional<int64_t> product(llvm::ArrayRef<int64_t> dimensions) {
  int64_t value = 1;
  for (int64_t dimension : dimensions) {
    if (dimension <= 0 || value > std::numeric_limits<int64_t>::max() / dimension)
      return std::nullopt;
    value *= dimension;
  }
  return value;
}
std::optional<llvm::SmallVector<int64_t>> fixedDimensions(Operation *module, Type type) {
  llvm::SmallVector<int64_t> result;
  auto shape = physicalShape(type);
  if (!shape)
    return std::nullopt;
  for (auto token : shape->dimensions) {
    auto dimension = resolveStaticShapeDimension(module, token.trim());
    if (failed(dimension) || dimension->bounds.lower != dimension->bounds.upper)
      return std::nullopt;
    result.push_back(dimension->bounds.lower);
  }
  return result;
}
std::optional<int64_t> literalIndex(Operation *module, Value value, Operation *use) {
  auto identity = indexIdentity(module, value, use);
  if (failed(identity) || identity->kind != StaticShapeIndexIdentity::Literal)
    return std::nullopt;
  return identity->literal;
}
std::optional<llvm::SmallVector<int64_t>> coordinates(int64_t ordinal,
                                                      llvm::ArrayRef<int64_t> dimensions) {
  auto count = product(dimensions);
  if (!count || ordinal < 0 || ordinal >= *count)
    return std::nullopt;
  llvm::SmallVector<int64_t> result(dimensions.size());
  for (size_t i = dimensions.size(); i > 0; --i) {
    result[i - 1] = ordinal % dimensions[i - 1];
    ordinal /= dimensions[i - 1];
  }
  return result;
}
std::optional<int64_t> ordinal(llvm::ArrayRef<int64_t> coordinates,
                               llvm::ArrayRef<int64_t> dimensions) {
  if (coordinates.size() != dimensions.size())
    return std::nullopt;
  int64_t result = 0;
  for (size_t i = 0; i < dimensions.size(); ++i) {
    if (coordinates[i] < 0 || coordinates[i] >= dimensions[i] ||
        result > (std::numeric_limits<int64_t>::max() - coordinates[i]) / dimensions[i])
      return std::nullopt;
    result = result * dimensions[i] + coordinates[i];
  }
  return result;
}
// Evaluate a single logical ordinal through the verified chain. No lane
// enumeration and no symbolic expression expansion are involved.
std::optional<int64_t> mapLiteralOrdinal(Operation *module, llvm::ArrayRef<Operation *> projections,
                                         int64_t value) {
  for (Operation *projection : llvm::reverse(projections)) {
    if (llvm::isa<ShapeViewOp>(projection))
      continue; // Reshape preserves the immediate-input logical ordinal.
    auto input = fixedDimensions(module, projection->getOperand(0).getType());
    auto output = fixedDimensions(module, projection->getResult(0).getType());
    if (!input || !output)
      return std::nullopt;
    auto indices = coordinates(value, *output);
    if (!indices)
      return std::nullopt;
    if (llvm::isa<ShapeIndexOp>(projection)) {
      llvm::SmallVector<int64_t> prefix;
      for (Value index : projection->getOperands().drop_front()) {
        auto literal = literalIndex(module, index, projection);
        if (!literal)
          return std::nullopt;
        prefix.push_back(*literal);
      }
      prefix.append(indices->begin(), indices->end());
      indices = std::move(prefix);
    } else {
      auto axis = projection->getAttrOfType<IntegerAttr>("axis");
      auto lower = literalIndex(module, projection->getOperand(1), projection);
      if (!axis || axis.getInt() < 0 || static_cast<uint64_t>(axis.getInt()) >= indices->size() ||
          !lower || *lower < 0 ||
          (*indices)[axis.getInt()] > std::numeric_limits<int64_t>::max() - *lower)
        return std::nullopt;
      (*indices)[axis.getInt()] += *lower;
    }
    auto mapped = ordinal(*indices, *input);
    if (!mapped)
      return std::nullopt;
    value = *mapped;
  }
  return value;
}
// Recover exact root-coordinate sums where no nontrivial reshape changes
// their radix interpretation. Prefixes concatenate coordinates; slices add
// their actual lower atom. This is derived mapping arithmetic, not equivalence
// between independently authored constant-expression roots.
void deriveScalarCoordinates(Operation *module, Value reference, StaticShapeProjection &result) {
  auto rootType = llvm::dyn_cast<ShapedType>(result.root.getType());
  if (!rootType || llvm::isa<ShapedType>(reference.getType()))
    return;
  auto cardinality = dimensionProductBounds(module, result.root.getType(), 0);
  if (!cardinality)
    return;
  if (!result.rootFlatBounds)
    result.rootFlatBounds = ParameterIntegerBounds{0, cardinality->upper - 1};
  if (result.literalRootFlatOffset)
    result.rootFlatBounds =
        ParameterIntegerBounds{*result.literalRootFlatOffset, *result.literalRootFlatOffset};
  llvm::SmallVector<StaticSum, 4> coordinateTerms;
  llvm::SmallVector<ParameterIntegerBounds, 4> bounds;
  for (size_t cursor = result.projections.size(); cursor > 0;) {
    Operation *projection = result.projections[--cursor];
    if (llvm::isa<ShapeViewOp>(projection)) {
      Type outputType = projection->getResult(0).getType();
      auto output = dimensionsKey(module, projection->getResult(0).getType());
      while (cursor > 0 && llvm::isa<ShapeViewOp>(result.projections[cursor - 1]))
        projection = result.projections[--cursor];
      auto input = dimensionsKey(module, projection->getOperand(0).getType());
      if (failed(input) || failed(output))
        return;
      if (*input != *output) {
        // Literal coordinates can be re-radixed through fixed intermediate
        // shapes even when an earlier slice's root extent remains symbolic.
        // Never substitute singleton parameter/index bounds for opaque atoms.
        auto inputDimensions = fixedDimensions(module, projection->getOperand(0).getType());
        auto outputDimensions = fixedDimensions(module, outputType);
        if (!inputDimensions || !outputDimensions)
          return;
        llvm::SmallVector<int64_t> literalCoordinates;
        for (const StaticSum &terms : coordinateTerms) {
          if (terms.size() > 1 || (!terms.empty() && !terms.begin()->first.empty()))
            return;
          literalCoordinates.push_back(terms.empty() ? 0 : terms.begin()->second);
        }
        auto flat = ordinal(literalCoordinates, *outputDimensions);
        auto mapped = flat ? coordinates(*flat, *inputDimensions) : std::nullopt;
        if (!mapped)
          return;
        coordinateTerms.clear();
        bounds.clear();
        for (int64_t coordinate : *mapped) {
          coordinateTerms.emplace_back();
          if (!addProduct(coordinateTerms.back(), StaticProduct{coordinate, {}}))
            return;
          bounds.push_back({coordinate, coordinate});
        }
      }
      continue;
    }
    if (llvm::isa<ShapeIndexOp>(projection)) {
      llvm::SmallVector<StaticSum, 4> prefix;
      llvm::SmallVector<ParameterIntegerBounds, 4> prefixBounds;
      for (Value index : projection->getOperands().drop_front()) {
        auto term = indexProduct(module, index, projection);
        auto range = indexBounds(module, index, projection);
        if (!term || failed(range))
          return;
        prefix.emplace_back();
        if (!addProduct(prefix.back(), *term))
          return;
        prefixBounds.push_back(*range);
      }
      prefix.append(coordinateTerms.begin(), coordinateTerms.end());
      prefixBounds.append(bounds.begin(), bounds.end());
      coordinateTerms = std::move(prefix);
      bounds = std::move(prefixBounds);
      continue;
    }
    auto axis = projection->getAttrOfType<IntegerAttr>("axis");
    auto lower = indexProduct(module, projection->getOperand(1), projection);
    auto lowerBounds = indexBounds(module, projection->getOperand(1), projection);
    if (!axis || axis.getInt() < 0 || static_cast<uint64_t>(axis.getInt()) >= bounds.size() ||
        !lower || failed(lowerBounds) || !addProduct(coordinateTerms[axis.getInt()], *lower))
      return;
    auto &range = bounds[axis.getInt()];
    if (range.upper > std::numeric_limits<int64_t>::max() - lowerBounds->upper)
      return;
    range.lower += lowerBounds->lower;
    range.upper += lowerBounds->upper;
  }
  auto rootShape = physicalShape(result.root.getType());
  if (!rootShape)
    return;
  const auto &dimensions = rootShape->dimensions;
  if (coordinateTerms.size() != dimensions.size())
    return;
  StaticSum offset;
  ParameterIntegerBounds flat{0, 0};
  for (unsigned axis = 0; axis < dimensions.size(); ++axis) {
    auto stride = dimensionProduct(module, result.root.getType(), axis + 1);
    auto dimension = resolveStaticShapeDimension(module, dimensions[axis].trim());
    if (!stride || failed(dimension))
      return;
    // The verified mapping already proves bounds. Intersect coarse independent
    // intervals with the root domain, preserving direct disjoint-axis facts.
    bounds[axis].upper = std::min(bounds[axis].upper, dimension->bounds.upper - 1);
    for (const auto &[factors, coefficient] : coordinateTerms[axis]) {
      auto term = multiplyProduct({coefficient, factors}, *stride);
      if (!term || !addProduct(offset, *term))
        return;
    }
    if (flat.upper >
        (std::numeric_limits<int64_t>::max() - bounds[axis].upper) / dimension->bounds.upper)
      return;
    flat.lower = flat.lower * dimension->bounds.lower + bounds[axis].lower;
    flat.upper = flat.upper * dimension->bounds.upper + bounds[axis].upper;
  }
  result.rootCoordinateBounds = std::move(bounds);
  result.rootFlatBounds = flat;
  if (!result.literalRootFlatOffset)
    result.canonicalScalarKey = linearKey(offset, StaticProduct{});
}
} // namespace

LogicalResult verifyInstanceShapeDimension(Operation *instance, const StaticShapeDimension &formal,
                                           const StaticShapeDimension &parentActual) {
  auto parent = instance ? instance->getParentOfType<nodal::ModuleOp>() : nodal::ModuleOp();
  if (!parent)
    return failure();
  auto resolve = [&](const StaticShapeDimension &dimension,
                     bool child) -> FailureOr<InstantiatedIntegerExpression> {
    if (dimension.parameter)
      return child ? resolveInstanceIntegerParameter(instance, dimension.parameter)
                   : resolveOwnedIntegerParameter(parent, dimension.parameter);
    if (dimension.expression)
      return child ? resolveInstanceIntegerExpression(instance, dimension.expression)
                   : resolveOwnedIntegerExpression(parent, dimension.expression);
    return makeLiteralIntegerExpression(dimension.literal);
  };
  auto expected = resolve(formal, true), actual = resolve(parentActual, false);
  if (failed(expected) || failed(actual))
    return instance->emitOpError(
        "NODAL-SHAPE-043-008: finalized instance extent requires an owned all-legal Integer proof");
  if (expected->bounds.lower <= 0 || actual->bounds.lower <= 0 ||
      !equivalentInstantiatedIntegerExpressions(*expected, *actual))
    return instance->emitOpError(
        "NODAL-SHAPE-043-008: actual extent differs from the exact substituted child extent");
  return success();
}

FailureOr<StaticShapeProjection> resolveStaticShapeProjection(Value reference, Operation *use) {
  if (!reference || !use)
    return failure();
  auto module = use->getParentOfType<nodal::ModuleOp>();
  if (!module)
    return failure();
  StaticShapeProjection result;
  const Type element = shapeElement(reference.getType());
  if (!element)
    return failure();
  llvm::SmallPtrSet<Operation *, 16> visited;
  Value current = reference;
  unsigned captureDepth = 0;
  while (current) {
    if (auto argument = llvm::dyn_cast<BlockArgument>(current)) {
      if (++captureDepth > 512 || !argument.getOwner()->getParentOp()->isProperAncestor(use))
        return failure();
      auto source = isBoundedDigitalLoop(argument.getOwner()->getParentOp())
                        ? digitalLoopCaptureSource(argument)
                        : digitalCollectionCaptureSource(argument);
      if (failed(source) || !*source || source->getType() != current.getType())
        return failure();
      current = *source;
      continue;
    }
    Operation *operation = current.getDefiningOp();
    if (!operation || !visited.insert(operation).second || visited.size() > 512 ||
        operation->getParentOfType<nodal::ModuleOp>() != module ||
        operation->getNumResults() != 1 || operation->getResult(0) != current ||
        shapeElement(current.getType()) != element || !precedesLexically(operation, use))
      return failure();
    if (!isProjection(operation)) {
      result.root = current;
      break;
    }
    if (operation->getNumOperands() == 0 ||
        shapeElement(operation->getOperand(0).getType()) != element)
      return failure();
    result.projections.push_back(operation);
    current = operation->getOperand(0);
  }
  if (!result.root)
    return failure();
  std::reverse(result.projections.begin(), result.projections.end());
  Operation *previous = result.root.getDefiningOp();
  for (Operation *projection : result.projections) {
    if (!precedesLexically(previous, projection))
      return failure();
    auto valid = llvm::isa<ShapeIndexOp>(projection)  ? verifyShapeIndexMapping(projection)
                 : llvm::isa<ShapeViewOp>(projection) ? verifyShapeViewMapping(projection)
                                                      : verifyShapeSliceMapping(projection);
    if (failed(valid))
      return failure();
    previous = projection;
  }
  if (!dimensionProductBounds(module, result.root.getType(), 0))
    return failure();
  auto rootDimensions = fixedDimensions(module, result.root.getType());
  if (rootDimensions)
    result.exactFixedCardinality = product(*rootDimensions);
  result.wholeRoot = true;
  result.canonicalKey = "whole";
  result.canonicalScalarKey = "root";

  bool linear = true;
  StaticSum offset, exactOffset;
  std::optional<ParameterIntegerBounds> exactOffsetBounds = ParameterIntegerBounds{0, 0};
  llvm::SmallVector<Operation *, 4> prefix;
  for (Operation *projection : result.projections) {
    result.ancestorKeys.push_back(result.canonicalKey);
    auto inputKey = dimensionsKey(module, projection->getOperand(0).getType());
    auto outputKey = dimensionsKey(module, projection->getResult(0).getType());
    if (failed(inputKey) || failed(outputKey))
      return failure();
    std::string step;
    bool identityMap = false;
    if (llvm::isa<ShapeIndexOp>(projection)) {
      step = "/index[" + *inputKey + "](";
      bool singletonPrefix = true;
      auto fixed = fixedDimensions(module, projection->getOperand(0).getType());
      unsigned axis = 0;
      for (Value index : projection->getOperands().drop_front()) {
        auto key = indexKey(module, index, projection);
        if (failed(key))
          return failure();
        step += *key + ";";
        singletonPrefix &= fixed && (*fixed)[axis] == 1;
        ++axis;
      }
      step += ')';
      identityMap = singletonPrefix;
      result.wholeRoot &= singletonPrefix;
      if (linear) {
        unsigned axis = 0;
        for (Value index : projection->getOperands().drop_front()) {
          auto position = indexProduct(module, index, projection);
          auto stride = dimensionProduct(module, projection->getOperand(0).getType(), ++axis);
          addOffsetBounds(exactOffsetBounds, module, index, projection->getOperand(0).getType(),
                          axis, projection);
          auto term = position && stride ? multiplyProduct(*position, *stride) : std::nullopt;
          if (!term || !addProduct(offset, *term) || !addProduct(exactOffset, *term)) {
            linear = false;
            break;
          }
        }
      }
    } else if (llvm::isa<ShapeSliceOp>(projection)) {
      step = "/slice[" + *inputKey + "](" +
             std::to_string(projection->getAttrOfType<IntegerAttr>("axis").getInt());
      for (Value bound : projection->getOperands().drop_front()) {
        auto key = indexKey(module, bound, projection);
        if (failed(key))
          return failure();
        step += ";" + *key;
      }
      step += ')';
      auto lower = literalIndex(module, projection->getOperand(1), projection);
      identityMap = lower && *lower == 0 && *inputKey == *outputKey;
      result.wholeRoot &= identityMap;
      if (linear && !identityMap) {
        auto axis = projection->getAttrOfType<IntegerAttr>("axis").getInt();
        bool leading = axis == 0;
        auto fixed = fixedDimensions(module, projection->getOperand(0).getType());
        if (!leading && fixed)
          leading = llvm::all_of(llvm::ArrayRef<int64_t>(*fixed).take_front(axis),
                                 [](int64_t extent) { return extent == 1; });
        auto lowerProduct = indexProduct(module, projection->getOperand(1), projection);
        auto stride = dimensionProduct(module, projection->getOperand(0).getType(), axis + 1);
        auto term = lowerProduct && stride ? multiplyProduct(*lowerProduct, *stride) : std::nullopt;
        addOffsetBounds(exactOffsetBounds, module, projection->getOperand(1),
                        projection->getOperand(0).getType(), axis + 1, projection);
        if (!leading || !term || !addProduct(offset, *term) || !addProduct(exactOffset, *term))
          linear = false;
      }
    } else {
      // Reshape changes coordinate interpretation, not the selected ordinal
      // sequence. Later indexing/slicing records its immediate input shape.
      identityMap = true;
    }
    prefix.push_back(projection);
    const bool opaqueIndex =
        llvm::isa<ShapeIndexOp>(projection) &&
        llvm::any_of(projection->getOperands().drop_front(), [&](Value index) {
          auto identity = indexIdentity(module, index, projection);
          return failed(identity) || identity->kind != StaticShapeIndexIdentity::Literal;
        });
    if (!identityMap || opaqueIndex)
      result.canonicalScalarKey += step;
    if (!identityMap)
      result.canonicalKey += step;
    if (result.wholeRoot)
      offset.clear();
    if (linear && !result.wholeRoot) {
      auto count = dimensionProduct(module, projection->getResult(0).getType());
      if (count)
        result.canonicalKey = linearKey(offset, *count);
      else
        linear = false;
    }
    if (result.wholeRoot)
      result.canonicalKey = "whole";
    if (!llvm::isa<ShapedType>(projection->getResult(0).getType())) {
      if (auto offset = mapLiteralOrdinal(module, prefix, 0))
        result.canonicalKey = "offset:" + std::to_string(*offset);
    }
  }
  auto selectedDimensions = fixedDimensions(module, reference.getType());
  auto count = selectedDimensions ? product(*selectedDimensions) : std::nullopt;
  if (count) {
    result.literalFirstOffset = mapLiteralOrdinal(module, result.projections, 0);
    result.literalLastOffset = mapLiteralOrdinal(module, result.projections, *count - 1);
    if (result.literalFirstOffset && result.literalLastOffset) {
      result.contiguousLiteralRegion =
          *result.literalLastOffset >= *result.literalFirstOffset &&
          *result.literalLastOffset - *result.literalFirstOffset == *count - 1;
      if (result.exactFixedCardinality && *count == *result.exactFixedCardinality)
        result.wholeRoot = true;
      if (!llvm::isa<ShapedType>(reference.getType()))
        result.literalRootFlatOffset = result.literalFirstOffset;
    }
  }
  if (result.wholeRoot)
    result.canonicalKey = "whole";
  if (linear && !llvm::isa<ShapedType>(reference.getType())) {
    result.canonicalScalarKey = linearKey(exactOffset, StaticProduct{});
    result.rootFlatBounds = exactOffsetBounds;
  }
  if (result.literalRootFlatOffset)
    result.canonicalScalarKey = "offset:" + std::to_string(*result.literalRootFlatOffset);
  deriveScalarCoordinates(module, reference, result);
  return result;
}

namespace {
using RegionStatus = ShapeRegionProofStatus;
using IntegerExpression = InstantiatedIntegerExpression;
using RegionAxis = StaticShapeRegionAxis;
using RegionBox = llvm::SmallVector<RegionAxis, 4>;

// One query owns its caches and a work budget. State expansion depends on
// authored cuts, not cardinality; no algorithm below recurses with shape rank.
class RegionQuery {
public:
  RegionQuery(Operation *use, ShapeRegionProofLimits limits)
      : use(use), module(use ? use->getParentOfType<nodal::ModuleOp>() : nodal::ModuleOp()),
        limits(limits) {}
  bool spend(uint64_t amount = 1) {
    if (amount > limits.maximumWork - std::min(work, limits.maximumWork)) {
      status = RegionStatus::ResourceLimit;
      return false;
    }
    work += amount;
    return true;
  }
  bool equal(const IntegerExpression &a, const IntegerExpression &b) {
    if (!a.root || !b.root || !spend())
      return false;
    auto key = std::make_pair(a.root, b.root);
    auto found = equalities.find(key);
    if (found != equalities.end())
      return found->second;
    bool result = equivalentInstantiatedIntegerExpressions(a, b);
    equalities.emplace(key, result);
    return result;
  }
  bool less(const IntegerExpression &a, const IntegerExpression &b, bool inclusive = false) {
    if (!spend())
      return false;
    if (proveIntegerExpressionLess(a, b, inclusive))
      return true;
    if (!a.root || a.root->kind != InstantiatedIntegerNode::Kind::OpaqueValue || !a.root->identity)
      return false;
    auto induction = llvm::dyn_cast<BlockArgument>(a.root->identity);
    auto generated = induction
                         ? llvm::dyn_cast_or_null<GenerateOp>(induction.getOwner()->getParentOp())
                         : GenerateOp();
    if (!generated || !generated->isProperAncestor(use))
      return false;
    auto found = domains.find(generated.getOperation());
    if (found == domains.end()) {
      auto domain = resolveGeneratedDomain(generated);
      found =
          domains
              .emplace(generated.getOperation(),
                       succeeded(domain) ? std::optional<GeneratedDomain>(*domain) : std::nullopt)
              .first;
    }
    const auto &domain = found->second;
    return domain && domain->induction == induction && domain->attainedInductionBounds &&
           proveIntegerExpressionLess(domain->upperExclusive, b, true);
  }
  std::optional<IntegerExpression> apply(llvm::StringRef opcode, const IntegerExpression &a,
                                         const IntegerExpression &b) {
    if (!spend())
      return std::nullopt;
    auto result = composeIntegerExpression(opcode, {a, b});
    return succeeded(result) ? std::optional<IntegerExpression>(*result) : std::nullopt;
  }
  std::optional<llvm::SmallVector<IntegerExpression, 4>> dimensions(Type type) {
    auto found = dimensionCache.find(type);
    if (found != dimensionCache.end())
      return found->second;
    llvm::SmallVector<IntegerExpression, 4> result;
    auto shape = physicalShape(type);
    if (!shape)
      return std::nullopt;
    for (auto token : shape->dimensions) {
      if (!spend())
        return std::nullopt;
      auto dimension = resolveStaticShapeDimension(module, token.trim());
      if (failed(dimension))
        return std::nullopt;
      auto expression =
          dimension->parameter ? resolveOwnedIntegerParameter(module, dimension->parameter)
          : dimension->expression
              ? resolveOwnedIntegerExpression(module, dimension->expression)
              : FailureOr<IntegerExpression>(makeLiteralIntegerExpression(dimension->literal));
      if (failed(expression) || expression->bounds.lower <= 0)
        return std::nullopt;
      result.push_back(*expression);
    }
    dimensionCache.try_emplace(type, result);
    return result;
  }
  std::optional<IntegerExpression> selector(Value value, Operation *at, bool opaque) {
    if (!spend())
      return std::nullopt;
    auto proof = resolveShapeIndexProof(value, at);
    if (failed(proof) || !proof->attainedBounds)
      return std::nullopt;
    if (proof->identity.kind == StaticShapeIndexIdentity::Literal)
      return makeLiteralIntegerExpression(proof->identity.literal);
    if (proof->identity.kind == StaticShapeIndexIdentity::Parameter) {
      auto expression = resolveOwnedIntegerParameter(module, proof->identity.root);
      return succeeded(expression) ? std::optional<IntegerExpression>(*expression) : std::nullopt;
    }
    if (opaque || proof->identity.kind == StaticShapeIndexIdentity::Induction ||
        proof->identity.kind == StaticShapeIndexIdentity::IterationAffine) {
      // A checked static capture retains its actual outer selector atom. A
      // fresh block argument is not a fresh physical index identity.
      Value identity = value;
      if (proof->identity.kind == StaticShapeIndexIdentity::Expression && proof->identity.root &&
          proof->identity.root->getNumResults() == 1)
        identity = proof->identity.root->getResult(0);
      return makeOpaqueIntegerExpression(identity, *proof->attainedBounds);
    }
    auto expression = resolveOwnedIntegerExpression(module, value);
    return succeeded(expression) ? std::optional<IntegerExpression>(*expression) : std::nullopt;
  }
  std::optional<IntegerExpression> count(llvm::ArrayRef<IntegerExpression> dimensions) {
    IntegerExpression result = one;
    for (const auto &dimension : dimensions) {
      auto next = apply("mul", result, dimension);
      if (!next)
        return std::nullopt;
      result = *next;
    }
    return result;
  }
  RegionBox full(llvm::ArrayRef<IntegerExpression> dimensions) {
    RegionBox result;
    for (const auto &dimension : dimensions)
      result.push_back({zero, dimension});
    return result;
  }
  bool whole(llvm::ArrayRef<RegionAxis> box, llvm::ArrayRef<IntegerExpression> dimensions) {
    if (box.size() != dimensions.size())
      return false;
    for (unsigned i = 0; i != box.size(); ++i)
      if (!equal(box[i].lower, zero) || !equal(box[i].upperExclusive, dimensions[i]))
        return false;
    return true;
  }
  std::optional<RegionAxis> interval(llvm::ArrayRef<RegionAxis> box,
                                     llvm::ArrayRef<IntegerExpression> dimensions) {
    if (box.size() != dimensions.size())
      return std::nullopt;
    IntegerExpression offset = zero;
    std::optional<unsigned> lastPartial;
    bool varying = false;
    for (unsigned axis = 0; axis != box.size(); ++axis) {
      auto width = apply("sub", box[axis].upperExclusive, box[axis].lower);
      if (!width || (varying && (!equal(box[axis].lower, zero) ||
                                 !equal(box[axis].upperExclusive, dimensions[axis]))))
        return std::nullopt;
      varying |= !equal(*width, one);
      if (!equal(box[axis].lower, zero) || !equal(box[axis].upperExclusive, dimensions[axis]))
        lastPartial = axis;
      auto shifted = apply("mul", offset, dimensions[axis]);
      auto nextOffset = shifted ? apply("add", *shifted, box[axis].lower) : std::nullopt;
      if (!nextOffset)
        return std::nullopt;
      offset = *nextOffset;
    }
    // Retain the authored upper boundary directly instead of offset+volume:
    // e.g. [1,N)x[0,3) ends at N*3, without distributive algebra solving.
    IntegerExpression end = zero;
    if (!lastPartial) {
      auto total = count(dimensions);
      if (!total)
        return std::nullopt;
      end = *total;
    } else {
      for (unsigned axis = 0; axis != box.size(); ++axis) {
        auto shifted = apply("mul", end, dimensions[axis]);
        auto next = shifted;
        if (shifted && axis <= *lastPartial)
          next = apply("add", *shifted,
                       axis == *lastPartial ? box[axis].upperExclusive : box[axis].lower);
        if (!next)
          return std::nullopt;
        end = *next;
      }
    }
    return RegionAxis{offset, end};
  }
  std::optional<RegionBox> box(const RegionAxis &interval,
                               llvm::ArrayRef<IntegerExpression> dimensions) {
    auto cardinality = count(dimensions);
    if (cardinality && equal(interval.lower, zero) && equal(interval.upperExclusive, *cardinality))
      return full(dimensions);
    if (dimensions.size() == 1)
      return RegionBox{interval};
    auto width = apply("sub", interval.upperExclusive, interval.lower);
    if (width && equal(*width, one) && cardinality && less(zero, interval.lower, true) &&
        less(interval.lower, *cardinality)) {
      RegionBox result(dimensions.size());
      IntegerExpression remainder = interval.lower;
      for (unsigned axis = dimensions.size(); axis > 0; --axis) {
        std::optional<IntegerExpression> digit, next;
        if (less(zero, remainder, true) && less(remainder, dimensions[axis - 1])) {
          digit = remainder;
          next = zero;
        } else {
          digit = apply("mod", remainder, dimensions[axis - 1]);
          next = apply("div", remainder, dimensions[axis - 1]);
        }
        auto end = digit ? apply("add", *digit, one) : std::nullopt;
        if (!digit || !next || !end)
          return std::nullopt;
        result[axis - 1] = {*digit, *end};
        remainder = *next;
      }
      return result;
    }
    auto low = literal(interval.lower), high = literal(interval.upperExclusive);
    llvm::SmallVector<int64_t> fixed;
    for (const auto &dimension : dimensions) {
      auto value = literal(dimension);
      if (!value)
        return std::nullopt;
      fixed.push_back(*value);
    }
    if (!low || !high || *high <= *low)
      return std::nullopt;
    auto start = coordinates(*low, fixed), last = coordinates(*high - 1, fixed);
    if (!start || !last)
      return std::nullopt;
    RegionBox result;
    for (unsigned axis = 0; axis != dimensions.size(); ++axis)
      result.push_back({makeLiteralIntegerExpression((*start)[axis]),
                        makeLiteralIntegerExpression((*last)[axis] + 1)});
    auto exact = this->interval(result, dimensions);
    if (!exact || !equal(exact->lower, interval.lower) ||
        !equal(exact->upperExclusive, interval.upperExclusive))
      return std::nullopt;
    return result;
  }
  static std::optional<int64_t> literal(const IntegerExpression &expression) {
    return expression.root && expression.root->kind == InstantiatedIntegerNode::Kind::Literal
               ? std::optional<int64_t>(expression.root->literal)
               : std::nullopt;
  }
  RegionStatus failedStatus() const {
    return status == RegionStatus::ResourceLimit ? status : RegionStatus::Unsupported;
  }
  Operation *use;
  nodal::ModuleOp module;
  ShapeRegionProofLimits limits;
  RegionStatus status = RegionStatus::Proved;
  uint64_t work = 0;
  IntegerExpression zero = makeLiteralIntegerExpression(0);
  IntegerExpression one = makeLiteralIntegerExpression(1);

private:
  llvm::DenseMap<Type, llvm::SmallVector<IntegerExpression, 4>> dimensionCache;
  std::map<Operation *, std::optional<GeneratedDomain>> domains;
  std::map<std::pair<std::shared_ptr<const InstantiatedIntegerNode>,
                     std::shared_ptr<const InstantiatedIntegerNode>>,
           bool>
      equalities;
};

RegionStatus deriveRegion(const StaticShapeProjection &projection, RegionQuery &query,
                          StaticShapeRegion &result, bool physicalSelectors = false,
                          std::optional<IntegerExpression> logicalOrdinal = std::nullopt,
                          bool iterationImages = false, bool iterationStart = false) {
  if (!query.module || !projection.root)
    return RegionStatus::Invalid;
  if (!query.spend(projection.projections.size() + 1))
    return query.failedStatus();
  Value selected = projection.projections.empty() ? projection.root
                                                  : projection.projections.back()->getResult(0);
  // Recheck the supplied chain against actual SSA rather than accepting cached
  // whole/contiguous flags or caller-authored coordinate summaries as evidence.
  Operation *proofUse = query.use;
  if ((iterationImages || iterationStart) && !projection.projections.empty()) {
    Operation *last = projection.projections.back();
    for (Operation *scope = last->getParentOp(); scope; scope = scope->getParentOp())
      if (isBoundedDigitalLoop(scope)) {
        proofUse = last;
        break;
      }
  }
  auto verified = resolveStaticShapeProjection(selected, proofUse);
  if (failed(verified) || verified->root != projection.root ||
      verified->projections != projection.projections)
    return RegionStatus::Invalid;
  auto selectedDimensions = query.dimensions(selected.getType());
  if (!selectedDimensions)
    return query.failedStatus();
  RegionBox currentBox = query.full(*selectedDimensions);
  std::optional<RegionAxis> currentInterval;
  std::optional<StaticShapeIterationImage> iteration;
  if (logicalOrdinal) {
    auto cardinality = query.count(*selectedDimensions);
    auto end = query.apply("add", *logicalOrdinal, query.one);
    if (!cardinality || !end || !query.less(query.zero, *logicalOrdinal, true) ||
        !query.less(*logicalOrdinal, *cardinality))
      return query.failedStatus();
    currentInterval = RegionAxis{*logicalOrdinal, *end};
    if (auto singleton = query.box(*currentInterval, *selectedDimensions)) {
      currentBox = std::move(*singleton);
      currentInterval.reset();
    }
  }
  for (Operation *operation : llvm::reverse(projection.projections)) {
    auto input = query.dimensions(operation->getOperand(0).getType());
    auto output = query.dimensions(operation->getResult(0).getType());
    if (!input || !output || !query.spend())
      return query.failedStatus();
    if (llvm::isa<ShapeViewOp>(operation)) {
      bool sameDimensions = input->size() == output->size();
      for (unsigned axis = 0; sameDimensions && axis != input->size(); ++axis)
        sameDimensions &= query.equal((*input)[axis], (*output)[axis]);
      if (sameDimensions)
        continue;
      if (!iteration && !currentInterval && query.whole(currentBox, *output)) {
        currentBox = query.full(*input);
        continue;
      }
      if (!currentInterval)
        currentInterval = query.interval(currentBox, *output);
      if (!currentInterval)
        return query.failedStatus();
      if (iteration) {
        if (!iteration->varyingAxis)
          continue; // Already in the unchanged root-relative ordinal space.
        auto scale = query.count(
            llvm::ArrayRef<IntegerExpression>(*output).drop_front(*iteration->varyingAxis + 1));
        if (!scale || (iteration->stride != 1 && !query.equal(*scale, query.one)))
          return query.failedStatus();
        iteration->occurrenceWidth = *scale;
        // Keep the exact ordinal image through a bijective view. Do not invent
        // one varying root coordinate when re-radixing can cross row boundaries.
        iteration->varyingAxis.reset();
        continue;
      }
      if (auto mapped = query.box(*currentInterval, *input)) {
        currentBox = std::move(*mapped);
        currentInterval.reset();
      }
      continue;
    }
    if (currentInterval && !iteration) {
      if (auto converted = query.box(*currentInterval, *output)) {
        currentBox = std::move(*converted);
        currentInterval.reset();
      }
    }
    if (llvm::isa<ShapeIndexOp>(operation)) {
      RegionBox prefix;
      const bool iterationInOutput = iteration.has_value();
      IntegerExpression prefixOffset = query.zero;
      unsigned axis = 0;
      for (Value index : operation->getOperands().drop_front()) {
        std::optional<IntegerExpression> position, end;
        auto affine = (iterationImages || iterationStart)
                          ? resolveDigitalIterationIndex(index, operation)
                          : FailureOr<DigitalIterationIndex>(failure());
        if (succeeded(affine)) {
          if (iteration)
            return query.failedStatus(); // One independently varying axis only.
          auto step = RegionQuery::literal(affine->domain.step);
          if (!step || *step <= 0)
            return query.failedStatus();
          position = query.apply("add", affine->domain.lower, affine->offset);
          end = query.apply("add", affine->domain.upperExclusive, affine->offset);
          auto distance = query.apply("sub", affine->domain.upperExclusive, affine->domain.lower);
          std::optional<IntegerExpression> count;
          if (!affine->domain.attainedInductionBounds)
            count = query.zero;
          else if (*step == 1)
            count = distance;
          else {
            // ceil(distance / step), with checked intermediates. Failure is a
            // bounded unsupported proof, never truncation or maximum expansion.
            auto adjusted =
                distance ? query.apply("add", *distance, makeLiteralIntegerExpression(*step - 1))
                         : std::nullopt;
            count = adjusted ? query.apply("div", *adjusted, affine->domain.step) : std::nullopt;
          }
          if (!position || !end || !count)
            return query.failedStatus();
          if (iterationStart)
            end = query.apply("add", *position, query.one);
          else
            iteration =
                StaticShapeIterationImage{affine->loop,   affine->domain,
                                          affine->offset, *count,
                                          query.one,      axis,
                                          *step,          !affine->domain.attainedInductionBounds};
        } else {
          position = query.selector(index, operation, true);
          end = position ? query.apply("add", *position, query.one) : std::nullopt;
        }
        if (!position || !end || axis >= input->size())
          return query.failedStatus();
        prefix.push_back({*position, *end});
        auto shifted = query.apply("mul", prefixOffset, (*input)[axis++]);
        auto next = shifted ? query.apply("add", *shifted, *position) : std::nullopt;
        if (!next)
          return query.failedStatus();
        prefixOffset = *next;
      }
      if (iterationInOutput && iteration->varyingAxis)
        *iteration->varyingAxis += axis;
      if (currentInterval) {
        // Flat families can only be translated by loop-invariant prefixes.
        if (iteration && !iterationInOutput)
          return query.failedStatus();
        auto stride = query.count(llvm::ArrayRef<IntegerExpression>(*input).drop_front(axis));
        auto offset = stride ? query.apply("mul", prefixOffset, *stride) : std::nullopt;
        auto low = offset ? query.apply("add", *offset, currentInterval->lower) : std::nullopt;
        auto high =
            offset ? query.apply("add", *offset, currentInterval->upperExclusive) : std::nullopt;
        if (!low || !high)
          return query.failedStatus();
        currentInterval = RegionAxis{*low, *high};
      } else {
        prefix.append(currentBox.begin(), currentBox.end());
        currentBox = std::move(prefix);
      }
      continue;
    }
    auto axisAttr = operation->getAttrOfType<IntegerAttr>("axis");
    if (!axisAttr || axisAttr.getInt() < 0 ||
        static_cast<uint64_t>(axisAttr.getInt()) >= input->size())
      return RegionStatus::Invalid;
    unsigned axis = axisAttr.getInt();
    auto lower = query.selector(operation->getOperand(1), operation, physicalSelectors);
    if (!lower)
      return query.failedStatus();
    if (currentInterval) {
      for (unsigned prefix = 0; prefix < axis; ++prefix)
        if (!query.equal((*input)[prefix], query.one))
          return query.failedStatus();
      auto stride = query.count(llvm::ArrayRef<IntegerExpression>(*input).drop_front(axis + 1));
      auto offset = stride ? query.apply("mul", *lower, *stride) : std::nullopt;
      auto low = offset ? query.apply("add", *offset, currentInterval->lower) : std::nullopt;
      auto high =
          offset ? query.apply("add", *offset, currentInterval->upperExclusive) : std::nullopt;
      if (!low || !high)
        return query.failedStatus();
      currentInterval = RegionAxis{*low, *high};
    } else {
      if (axis >= currentBox.size())
        return RegionStatus::Invalid;
      auto low = query.apply("add", *lower, currentBox[axis].lower);
      auto high = query.apply("add", *lower, currentBox[axis].upperExclusive);
      if (!low || !high)
        return query.failedStatus();
      currentBox[axis] = {*low, *high};
    }
  }
  auto rootDimensions = query.dimensions(projection.root.getType());
  if (!rootDimensions)
    return query.failedStatus();
  StaticShapeRegion region;
  region.root = projection.root;
  if (iteration || (!physicalSelectors && !logicalOrdinal))
    region.projection = std::move(*verified);
  region.iteration = iteration;
  if (iteration && iteration->empty) {
    region.kind = StaticShapeRegion::Kind::Empty;
    region.axes = std::move(currentBox);
    if (currentInterval)
      region.interval = *currentInterval;
  } else if (currentInterval) {
    region.kind = StaticShapeRegion::Kind::Interval;
    region.interval = *currentInterval;
  } else {
    region.kind = query.whole(currentBox, *rootDimensions) ? StaticShapeRegion::Kind::Whole
                                                           : StaticShapeRegion::Kind::Box;
    region.axes = std::move(currentBox);
  }
  if (query.status != RegionStatus::Proved)
    return query.status;
  result = std::move(region);
  return RegionStatus::Proved;
}

std::optional<RegionBox> regionBox(const StaticShapeRegion &region, RegionQuery &query,
                                   llvm::ArrayRef<IntegerExpression> dimensions) {
  if (region.kind == StaticShapeRegion::Kind::Empty ||
      (region.iteration && region.iteration->stride != 1))
    return std::nullopt;
  if (region.kind == StaticShapeRegion::Kind::Whole)
    return query.full(dimensions);
  if (region.kind == StaticShapeRegion::Kind::Box)
    return region.axes;
  return query.box(region.interval, dimensions);
}
std::optional<RegionAxis> regionInterval(const StaticShapeRegion &region, RegionQuery &query,
                                         llvm::ArrayRef<IntegerExpression> dimensions) {
  if (region.kind == StaticShapeRegion::Kind::Empty ||
      (region.iteration && region.iteration->stride != 1))
    return std::nullopt;
  if (region.kind == StaticShapeRegion::Kind::Interval)
    return region.interval;
  auto box = regionBox(region, query, dimensions);
  return box ? query.interval(*box, dimensions) : std::nullopt;
}

// Split a fixed flat interval into at most 2*rank+1 exact boxes. Prefix/suffix
// boundary fragments are represented by boxes, never enumerated as lanes.
std::optional<std::vector<RegionBox>> splitInterval(const RegionAxis &interval,
                                                    llvm::ArrayRef<IntegerExpression> dimensions,
                                                    RegionQuery &query) {
  if (auto box = query.box(interval, dimensions))
    return std::vector<RegionBox>{*box};
  auto low = RegionQuery::literal(interval.lower),
       high = RegionQuery::literal(interval.upperExclusive);
  llvm::SmallVector<int64_t> fixed;
  for (const auto &dimension : dimensions) {
    auto number = RegionQuery::literal(dimension);
    if (!number)
      return std::nullopt;
    fixed.push_back(*number);
  }
  if (!low || !high || *low < 0 || *high <= *low)
    return std::nullopt;
  auto start = coordinates(*low, fixed), last = coordinates(*high - 1, fixed);
  if (!start || !last)
    return std::nullopt;
  std::vector<RegionBox> result;
  unsigned differing = 0;
  while (differing < fixed.size() && (*start)[differing] == (*last)[differing])
    ++differing;
  if (differing == fixed.size())
    return std::nullopt;
  auto make = [&](llvm::ArrayRef<int64_t> point, unsigned axis, int64_t lower, int64_t upper) {
    RegionBox box;
    for (unsigned i = 0; i != fixed.size(); ++i) {
      int64_t lo = i < axis ? point[i] : i == axis ? lower : 0;
      int64_t hi = i < axis ? point[i] + 1 : i == axis ? upper : fixed[i];
      box.push_back({makeLiteralIntegerExpression(lo), makeLiteralIntegerExpression(hi)});
    }
    result.push_back(std::move(box));
  };
  // The first and last rows at the differing axis contribute only boundary
  // suffixes; all intervening rows form one rectangular middle slab.
  if ((*start)[differing] + 1 < (*last)[differing])
    make(*start, differing, (*start)[differing] + 1, (*last)[differing]);
  for (unsigned axis = differing + 1; axis < fixed.size(); ++axis) {
    if (!query.spend(fixed.size()))
      return std::nullopt;
    if ((*start)[axis] + 1 < fixed[axis])
      make(*start, axis, (*start)[axis] + 1, fixed[axis]);
    if ((*last)[axis] > 0)
      make(*last, axis, 0, (*last)[axis]);
  }
  make(*start, fixed.size() - 1, start->back(), start->back() + 1);
  make(*last, fixed.size() - 1, last->back(), last->back() + 1);
  return result;
}
// Exact constant cancellation of retained canonical add operands. This is not a
// new affine solver: all arithmetic and identity checks stay in ParameterModel.
std::optional<int64_t> constantDifference(const IntegerExpression &a, const IntegerExpression &b,
                                          RegionQuery &query, unsigned depth = 0) {
  if (depth > 64 || !query.spend())
    return std::nullopt;
  auto difference = query.apply("sub", a, b);
  if (difference)
    if (auto literal = RegionQuery::literal(*difference))
      return literal;
  auto reverse = query.apply("sub", b, a);
  if (reverse)
    if (auto literal = RegionQuery::literal(*reverse))
      if (*literal != std::numeric_limits<int64_t>::min())
        return -*literal;
  if (!a.root || !b.root || a.root->kind != InstantiatedIntegerNode::Kind::Apply ||
      b.root->kind != InstantiatedIntegerNode::Kind::Apply || a.root->opcode != "add" ||
      b.root->opcode != "add" || a.root->operands.size() != 2 || b.root->operands.size() != 2)
    return std::nullopt;
  for (unsigned i = 0; i != 2; ++i)
    for (unsigned j = 0; j != 2; ++j) {
      auto left = a.root->operands[i], right = b.root->operands[j];
      if (!query.equal({left, left->bounds}, {right, right->bounds}))
        continue;
      left = a.root->operands[1 - i];
      right = b.root->operands[1 - j];
      return constantDifference({left, left->bounds}, {right, right->bounds}, query, depth + 1);
    }
  return std::nullopt;
}

std::optional<RegionBox> imageAxes(const StaticShapeRegion &region, RegionQuery &query) {
  if (region.iteration && !region.iteration->varyingAxis)
    return RegionBox{region.interval};
  auto dimensions = query.dimensions(region.root.getType());
  if (!dimensions)
    return std::nullopt;
  if (region.kind == StaticShapeRegion::Kind::Whole && !region.iteration)
    return query.full(*dimensions);
  if (region.kind == StaticShapeRegion::Kind::Box || region.iteration)
    return region.axes;
  return query.box(region.interval, *dimensions);
}
unsigned imageAxis(const StaticShapeRegion &region) {
  return region.iteration->varyingAxis.value_or(0);
}
bool sameInvariantAxes(const StaticShapeRegion &a, const StaticShapeRegion &b, RegionQuery &query) {
  if (!a.iteration || !b.iteration || a.root != b.root ||
      a.iteration->varyingAxis != b.iteration->varyingAxis ||
      !query.equal(a.iteration->occurrenceWidth, b.iteration->occurrenceWidth))
    return false;
  auto first = imageAxes(a, query), second = imageAxes(b, query);
  if (!first || !second || first->size() != second->size() || imageAxis(a) >= first->size())
    return false;
  for (unsigned axis = 0; axis != first->size(); ++axis)
    if (axis != imageAxis(a) &&
        (!query.equal((*first)[axis].lower, (*second)[axis].lower) ||
         !query.equal((*first)[axis].upperExclusive, (*second)[axis].upperExclusive)))
      return false;
  return true;
}
bool sameImage(const StaticShapeRegion &a, const StaticShapeRegion &b, RegionQuery &query) {
  if (!sameInvariantAxes(a, b, query) || a.iteration->stride != b.iteration->stride ||
      !query.equal(a.iteration->tripCount, b.iteration->tripCount))
    return false;
  auto first = imageAxes(a, query), second = imageAxes(b, query);
  return query.equal((*first)[imageAxis(a)].lower, (*second)[imageAxis(b)].lower);
}

// Congruence is used only on exact equal-step one-axis images. The surrounding
// interval is a disjointness bound, never evidence that stride holes are filled.
std::optional<ShapeRegionOverlap>
compareStridedImages(const StaticShapeRegion &a, const StaticShapeRegion &b, RegionQuery &query) {
  if ((!a.iteration || a.iteration->stride == 1) && (!b.iteration || b.iteration->stride == 1))
    return std::nullopt;
  auto first = imageAxes(a, query), second = imageAxes(b, query);
  if (!first || !second || first->size() != second->size())
    return ShapeRegionOverlap::Unknown;
  bool invariantIntersection = true;
  const auto &family = a.iteration && a.iteration->stride != 1 ? a : b;
  const auto &other = &family == &a ? b : a;
  unsigned varying = imageAxis(family);
  if (varying >= first->size())
    return ShapeRegionOverlap::Unknown;
  for (unsigned axis = 0; axis != first->size(); ++axis) {
    const auto &x = (*first)[axis], &y = (*second)[axis];
    if (query.less(x.upperExclusive, y.lower, true) || query.less(y.upperExclusive, x.lower, true))
      return ShapeRegionOverlap::Disjoint;
    if (axis != varying)
      invariantIntersection &=
          query.less(x.lower, y.upperExclusive) && query.less(y.lower, x.upperExclusive);
  }
  auto familyAxes = imageAxes(family, query), otherAxes = imageAxes(other, query);
  int64_t stride = family.iteration->stride;
  auto delta = constantDifference((*otherAxes)[varying].lower, (*familyAxes)[varying].lower, query);
  if (!delta)
    return ShapeRegionOverlap::Unknown;
  if (other.iteration) {
    if (other.iteration->stride != stride ||
        other.iteration->varyingAxis != family.iteration->varyingAxis)
      return ShapeRegionOverlap::Unknown;
    if (*delta % stride)
      return ShapeRegionOverlap::Disjoint;
    int64_t displacement = *delta / stride;
    const auto &aCount = family.iteration->tripCount.bounds;
    const auto &bCount = other.iteration->tripCount.bounds;
    if (static_cast<__int128>(displacement) >= aCount.upper ||
        static_cast<__int128>(displacement) + bCount.upper <= 0)
      return ShapeRegionOverlap::Disjoint;
    if (invariantIntersection && displacement < aCount.lower &&
        static_cast<__int128>(displacement) + bCount.lower > 0)
      return ShapeRegionOverlap::Overlapping;
    return ShapeRegionOverlap::Unknown;
  }
  auto width =
      query.apply("sub", (*otherAxes)[varying].upperExclusive, (*otherAxes)[varying].lower);
  if (!width || !query.equal(*width, query.one))
    return ShapeRegionOverlap::Unknown;
  if (*delta % stride || *delta < 0 || *delta / stride >= family.iteration->tripCount.bounds.upper)
    return ShapeRegionOverlap::Disjoint;
  return invariantIntersection && *delta / stride < family.iteration->tripCount.bounds.lower
             ? ShapeRegionOverlap::Overlapping
             : ShapeRegionOverlap::Unknown;
}

// Coalesce only an authored complete set of residues with identical finite
// ordinal counts. The work is bounded by supplied families, never step or lanes.
RegionStatus coalesceResidues(llvm::ArrayRef<StaticShapeRegion> input, RegionQuery &query,
                              SmallVectorImpl<StaticShapeRegion> &output) {
  std::vector<bool> consumed(input.size(), false);
  for (unsigned i = 0; i != input.size(); ++i) {
    if (consumed[i] || input[i].kind == StaticShapeRegion::Kind::Empty)
      continue;
    const auto &first = input[i];
    if (!first.iteration || first.iteration->stride == 1) {
      output.push_back(first);
      continue;
    }
    int64_t stride = first.iteration->stride;
    if (static_cast<uint64_t>(stride) > input.size())
      return query.failedStatus();
    auto firstAxes = imageAxes(first, query);
    if (!firstAxes)
      return query.failedStatus();
    unsigned axis = imageAxis(first);
    std::vector<std::pair<int64_t, unsigned>> residues;
    for (unsigned j = i; j != input.size(); ++j) {
      if (!query.spend())
        return query.failedStatus();
      const auto &next = input[j];
      if (consumed[j] || !next.iteration || next.iteration->stride != stride ||
          !sameInvariantAxes(first, next, query) ||
          !query.equal(first.iteration->tripCount, next.iteration->tripCount))
        continue;
      auto nextAxes = imageAxes(next, query);
      auto delta = constantDifference((*nextAxes)[axis].lower, (*firstAxes)[axis].lower, query);
      if (delta)
        residues.emplace_back(*delta, j);
    }
    std::sort(residues.begin(), residues.end());
    // Each complete run must start at an actually authored boundary. Refuse
    // duplicated residues and uneven tails instead of widening their hull.
    if (residues.size() != static_cast<uint64_t>(stride))
      return query.failedStatus();
    for (unsigned r = 0; r != residues.size(); ++r)
      if (static_cast<__int128>(residues[r].first) - residues.front().first != r)
        return query.failedStatus();
    auto base = imageAxes(input[residues.front().second], query);
    auto width =
        query.apply("mul", first.iteration->tripCount, makeLiteralIntegerExpression(stride));
    auto end = width ? query.apply("add", (*base)[axis].lower, *width) : std::nullopt;
    if (!end)
      return query.failedStatus();
    StaticShapeRegion merged = first;
    merged.iteration.reset();
    merged.projection.reset();
    (*base)[axis].upperExclusive = *end;
    if (first.iteration->varyingAxis) {
      merged.kind = StaticShapeRegion::Kind::Box;
      merged.axes = *base;
    } else {
      merged.kind = StaticShapeRegion::Kind::Interval;
      merged.interval = (*base)[0];
    }
    output.push_back(std::move(merged));
    for (auto [residue, index] : residues)
      consumed[index] = true;
  }
  return query.status;
}

} // namespace

ShapeRegionProofStatus resolveStaticShapeRegion(const StaticShapeProjection &projection,
                                                Operation *use, StaticShapeRegion &result,
                                                ShapeRegionProofLimits limits) {
  RegionQuery query(use, limits);
  return deriveRegion(projection, query, result, false, std::nullopt, true);
}

ShapeRegionProofStatus resolveStaticShapeIterationImage(const StaticShapeProjection &projection,
                                                        Operation *use, StaticShapeRegion &result,
                                                        ShapeRegionProofLimits limits) {
  RegionQuery query(use, limits);
  StaticShapeRegion candidate;
  auto status = deriveRegion(projection, query, candidate, true, std::nullopt, true);
  if (status != RegionStatus::Proved)
    return status;
  if (!candidate.iteration)
    return RegionStatus::Unsupported;
  result = std::move(candidate);
  return RegionStatus::Proved;
}

FailureOr<int64_t> compareStaticShapeIterationPositions(const StaticShapeProjection &read,
                                                        const StaticShapeProjection &write,
                                                        Operation *use,
                                                        ShapeRegionProofLimits limits) {
  RegionQuery query(use, limits);
  StaticShapeRegion a, b;
  if (deriveRegion(read, query, a, true, std::nullopt, true) != RegionStatus::Proved ||
      deriveRegion(write, query, b, true, std::nullopt, true) != RegionStatus::Proved ||
      !a.iteration || !b.iteration || a.iteration->loop != b.iteration->loop ||
      a.iteration->stride != b.iteration->stride || !sameInvariantAxes(a, b, query))
    return failure();
  auto first = imageAxes(a, query), second = imageAxes(b, query);
  auto displacement =
      constantDifference((*first)[imageAxis(a)].lower, (*second)[imageAxis(b)].lower, query);
  auto step = query.apply("mul", a.iteration->occurrenceWidth,
                          makeLiteralIntegerExpression(a.iteration->stride));
  auto fixedStep = step ? RegionQuery::literal(*step) : std::nullopt;
  if (!displacement || !fixedStep || *fixedStep <= 0 || *displacement % *fixedStep ||
      query.status != RegionStatus::Proved)
    return failure();
  return *displacement / *fixedStep;
}

ShapeRegionProofStatus verifyStaticShapeIterationPredecessorSeed(const StaticShapeProjection &read,
                                                                 const StaticShapeProjection &write,
                                                                 const StaticShapeProjection &seed,
                                                                 Operation *use,
                                                                 ShapeRegionProofLimits limits) {
  auto position = compareStaticShapeIterationPositions(read, write, use, limits);
  if (failed(position) || *position != -1)
    return RegionStatus::Unsupported;
  RegionQuery query(use, limits);
  StaticShapeRegion family, base, actual;
  auto status = deriveRegion(read, query, family, true, std::nullopt, true);
  if (status != RegionStatus::Proved)
    return status;
  if (!family.iteration || family.iteration->stride != 1 ||
      !query.equal(family.iteration->domain.lower, query.zero))
    return query.failedStatus();
  status = deriveRegion(read, query, base, true, std::nullopt, false, true);
  if (status != RegionStatus::Proved)
    return status;
  status = deriveRegion(seed, query, actual, true, std::nullopt, true);
  if (status != RegionStatus::Proved)
    return status;
  if (actual.iteration || actual.root != base.root)
    return RegionStatus::Invalid;
  auto dimensions = query.dimensions(base.root.getType());
  if (!dimensions)
    return query.failedStatus();
  auto first = regionBox(base, query, *dimensions), second = regionBox(actual, query, *dimensions);
  if (!first || !second) {
    auto a = regionInterval(base, query, *dimensions),
         b = regionInterval(actual, query, *dimensions);
    if (!a || !b)
      return query.failedStatus();
    first = RegionBox{*a};
    second = RegionBox{*b};
  }
  if (first->size() != second->size())
    return RegionStatus::Invalid;
  for (auto [a, b] : llvm::zip(*first, *second))
    if (!query.equal(a.lower, b.lower) || !query.equal(a.upperExclusive, b.upperExclusive))
      return query.failedStatus();
  return query.status;
}

StaticShapeOverlap compareStaticShapeProjections(const StaticShapeProjection &left,
                                                 const StaticShapeProjection &right, Operation *use,
                                                 ShapeRegionProofLimits limits) {
  RegionQuery query(use, limits);
  auto unknown = [&](RegionStatus status) {
    return StaticShapeOverlap{status, ShapeRegionOverlap::Unknown};
  };
  StaticShapeRegion a, b;
  auto status = deriveRegion(left, query, a, true, std::nullopt, true);
  if (status != RegionStatus::Proved)
    return unknown(status);
  status = deriveRegion(right, query, b, true, std::nullopt, true);
  if (status != RegionStatus::Proved)
    return unknown(status);
  if (a.root != b.root || a.kind == StaticShapeRegion::Kind::Empty ||
      b.kind == StaticShapeRegion::Kind::Empty)
    return {RegionStatus::Proved, ShapeRegionOverlap::Disjoint};
  if (auto strided = compareStridedImages(a, b, query))
    return {query.status != RegionStatus::Proved      ? query.status
            : *strided == ShapeRegionOverlap::Unknown ? RegionStatus::Unsupported
                                                      : RegionStatus::Proved,
            *strided};
  auto dimensions = query.dimensions(a.root.getType());
  if (!dimensions)
    return unknown(query.failedStatus());
  auto compareBoxes = [&](llvm::ArrayRef<RegionAxis> first,
                          llvm::ArrayRef<RegionAxis> second) -> ShapeRegionOverlap {
    if (first.size() != second.size())
      return ShapeRegionOverlap::Unknown;
    bool intersection = true;
    for (auto [x, y] : llvm::zip(first, second)) {
      if (query.less(x.upperExclusive, y.lower, true) ||
          query.less(y.upperExclusive, x.lower, true))
        return ShapeRegionOverlap::Disjoint;
      intersection &=
          query.less(x.lower, y.upperExclusive) && query.less(y.lower, x.upperExclusive);
    }
    return intersection ? ShapeRegionOverlap::Overlapping : ShapeRegionOverlap::Unknown;
  };
  auto result = [&](ShapeRegionOverlap overlap) {
    return query.status != RegionStatus::Proved
               ? unknown(query.status)
               : StaticShapeOverlap{overlap == ShapeRegionOverlap::Unknown
                                        ? RegionStatus::Unsupported
                                        : RegionStatus::Proved,
                                    overlap};
  };
  // Preserve coordinate certificates before trying an ordinal normalization.
  // A column and another column need no algebra in the other axes to be disjoint.
  auto aBox = regionBox(a, query, *dimensions), bBox = regionBox(b, query, *dimensions);
  if (aBox && bBox)
    return result(compareBoxes(*aBox, *bBox));
  auto aInterval = regionInterval(a, query, *dimensions),
       bInterval = regionInterval(b, query, *dimensions);
  if (aInterval && bInterval)
    return result(compareBoxes({*aInterval}, {*bInterval}));
  auto boxes = [&](const StaticShapeRegion &region,
                   const std::optional<RegionBox> &box) -> std::optional<std::vector<RegionBox>> {
    if (box)
      return std::vector<RegionBox>{*box};
    if (region.kind != StaticShapeRegion::Kind::Interval)
      return std::nullopt;
    return splitInterval(region.interval, *dimensions, query);
  };
  auto first = boxes(a, aBox), second = boxes(b, bBox);
  if (!first || !second)
    return unknown(query.failedStatus());
  if (first->size() > limits.maximumStates || second->size() > limits.maximumStates)
    return unknown(RegionStatus::ResourceLimit);
  bool uncertain = false;
  for (const auto &x : *first)
    for (const auto &y : *second) {
      if (!query.spend())
        return unknown(query.status);
      auto overlap = compareBoxes(x, y);
      if (overlap == ShapeRegionOverlap::Overlapping)
        return result(overlap);
      uncertain |= overlap == ShapeRegionOverlap::Unknown;
    }
  return result(uncertain ? ShapeRegionOverlap::Unknown : ShapeRegionOverlap::Disjoint);
}

static ShapeRegionProofStatus verifyRegionCoverage(Value root,
                                                   llvm::ArrayRef<StaticShapeRegion> regions,
                                                   Operation *use, ShapeRegionProofLimits limits,
                                                   bool lastIsRead = false,
                                                   uint64_t *spentWork = nullptr) {
  RegionQuery query(use, limits);
  struct ChargeWork {
    RegionQuery &query;
    uint64_t *destination;
    ~ChargeWork() {
      if (destination)
        *destination = query.work;
    }
  } charge{query, spentWork};
  if (!root || !query.module || !root.getDefiningOp() ||
      root.getDefiningOp()->getParentOfType<nodal::ModuleOp>() != query.module)
    return RegionStatus::Invalid;
  auto dimensions = query.dimensions(root.getType());
  if (!dimensions)
    return query.failedStatus();
  // Keep exact coordinate boundaries when they are already available. An
  // ordinal conversion may otherwise demand unrelated algebra such as N < 2*N
  // for two plainly adjacent rows of [2,N]. Normalization is an optimization,
  // not permission to discard the stronger original coordinate certificate.
  std::vector<RegionBox> boxes;
  std::vector<unsigned> origins;
  bool allBoxes = true;
  for (auto [regionIndex, region] : llvm::enumerate(regions)) {
    if (region.root != root)
      return RegionStatus::Invalid;
    auto box = regionBox(region, query, *dimensions);
    if (!box) {
      allBoxes = false;
      break;
    }
    boxes.push_back(*box);
    origins.push_back(regionIndex);
  }
  if (!allBoxes) {
    boxes.clear();
    origins.clear();
    // A common ordinal space still handles exact nonrectangular flat images
    // without decomposing them. Preserve coordinate/decomposition fallback.
    bool allIntervals = true;
    std::vector<RegionAxis> intervals;
    for (auto [regionIndex, region] : llvm::enumerate(regions)) {
      if (region.root != root)
        return RegionStatus::Invalid;
      auto interval = regionInterval(region, query, *dimensions);
      if (!interval) {
        allIntervals = false;
        break;
      }
      intervals.push_back(*interval);
    }
    if (allIntervals) {
      auto cardinality = query.count(*dimensions);
      if (!cardinality)
        return query.failedStatus();
      *dimensions = {*cardinality};
      for (auto [regionIndex, interval] : llvm::enumerate(intervals)) {
        boxes.push_back({interval});
        origins.push_back(regionIndex);
      }
    } else {
      for (auto [regionIndex, region] : llvm::enumerate(regions)) {
        if (region.root != root)
          return RegionStatus::Invalid;
        if (auto box = regionBox(region, query, *dimensions)) {
          boxes.push_back(*box);
          origins.push_back(regionIndex);
        } else {
          auto split = splitInterval(region.interval, *dimensions, query);
          if (!split)
            return query.failedStatus();
          for (auto &box : *split) {
            boxes.push_back(std::move(box));
            origins.push_back(regionIndex);
          }
        }
        if (boxes.size() > limits.maximumStates)
          return RegionStatus::ResourceLimit;
      }
    }
  }
  if (!query.spend(boxes.size() + dimensions->size()))
    return query.failedStatus();
  if (boxes.empty())
    return RegionStatus::Invalid;
  // Cuts are deduplicated by actual exact expression identity and must admit
  // one all-legal strict ordering. Overlapping envelopes are never defaults.
  std::vector<std::vector<IntegerExpression>> cuts(dimensions->size());
  for (unsigned axis = 0; axis != dimensions->size(); ++axis) {
    auto &axisCuts = cuts[axis];
    axisCuts = {query.zero, (*dimensions)[axis]};
    for (const auto &box : boxes) {
      if (box.size() != dimensions->size())
        return RegionStatus::Invalid;
      const auto &range = box[axis];
      if (!query.less(query.zero, range.lower, true) ||
          !query.less(range.lower, range.upperExclusive, true) ||
          !query.less(range.upperExclusive, (*dimensions)[axis], true))
        return query.failedStatus();
      for (const auto &bound : {range.lower, range.upperExclusive}) {
        bool present = false;
        for (const auto &cut : axisCuts)
          if (query.equal(bound, cut)) {
            present = true;
            break;
          }
        if (present)
          continue;
        unsigned position = 0;
        while (position < axisCuts.size() && query.less(axisCuts[position], bound, true))
          ++position;
        if (position < axisCuts.size() && !query.less(bound, axisCuts[position], true))
          return query.failedStatus();
        axisCuts.insert(axisCuts.begin() + position, bound);
      }
    }
  }
  // Memoize (axis, active boxes): different authored cells often induce the
  // same suffix problem. An iterative worklist avoids rank-proportional stack.
  using State = std::pair<unsigned, std::vector<unsigned>>;
  std::vector<State> pending;
  std::set<State> visited;
  std::vector<unsigned> all;
  for (unsigned i = 0; i != boxes.size(); ++i)
    all.push_back(i);
  pending.emplace_back(0, std::move(all));
  while (!pending.empty()) {
    State state = std::move(pending.back());
    pending.pop_back();
    if (!visited.insert(state).second)
      continue;
    if (visited.size() > limits.maximumStates || pending.size() > limits.maximumStates)
      return RegionStatus::ResourceLimit;
    if (!query.spend())
      return query.failedStatus();
    unsigned axis = state.first;
    unsigned reads = 0;
    if (lastIsRead) {
      for (unsigned index : state.second)
        reads += origins[index] == regions.size() - 1;
      if (!reads)
        continue; // Coverage outside the selected read is not required.
    }
    if (axis == dimensions->size()) {
      if (state.second.size() != 1 + reads)
        return RegionStatus::Invalid;
      continue;
    }
    const auto &axisCuts = cuts[axis];
    for (unsigned cut = 1; cut < axisCuts.size(); ++cut) {
      // Identically empty cuts carry no physical point. Potentially empty
      // strips remain checked symbolically, which is conservative for N=0.
      if (query.less(axisCuts[cut], axisCuts[cut - 1], true))
        continue;
      std::vector<unsigned> active;
      for (unsigned index : state.second) {
        const auto &range = boxes[index][axis];
        if (query.less(range.lower, axisCuts[cut - 1], true) &&
            query.less(axisCuts[cut], range.upperExclusive, true))
          active.push_back(index);
      }
      if (query.status != RegionStatus::Proved)
        return query.status;
      if (active.empty()) {
        if (lastIsRead)
          continue;
        return RegionStatus::Invalid;
      }
      pending.emplace_back(axis + 1, std::move(active));
      if (pending.size() > limits.maximumStates)
        return RegionStatus::ResourceLimit;
    }
  }
  return RegionStatus::Proved;
}

ShapeRegionProofStatus verifyStaticShapeRegionPartition(Value root,
                                                        llvm::ArrayRef<StaticShapeRegion> regions,
                                                        Operation *use,
                                                        ShapeRegionProofLimits limits) {
  RegionQuery query(use, limits);
  SmallVector<StaticShapeRegion, 4> verified, solid;
  for (const auto &region : regions) {
    if (!region.iteration) {
      verified.push_back(region);
      continue;
    }
    if (!region.projection)
      return RegionStatus::Invalid;
    StaticShapeRegion actual;
    auto status = deriveRegion(*region.projection, query, actual, true, std::nullopt, true);
    if (status != RegionStatus::Proved || actual.root != root)
      return status != RegionStatus::Proved ? status : RegionStatus::Invalid;
    verified.push_back(std::move(actual));
  }
  auto status = coalesceResidues(verified, query, solid);
  if (status != RegionStatus::Proved)
    return status;
  limits.maximumWork -= std::min(limits.maximumWork, query.work);
  return verifyRegionCoverage(root, solid, use, limits);
}

ShapeRegionProofStatus
verifyStaticShapeProjectionCoverage(const StaticShapeProjection &read,
                                    llvm::ArrayRef<StaticShapeProjection> writes, Operation *use,
                                    ShapeRegionProofLimits limits) {
  RegionQuery query(use, limits);
  SmallVector<StaticShapeRegion, 4> regions;
  for (const auto &write : writes) {
    if (write.root != read.root)
      return RegionStatus::Invalid;
    StaticShapeRegion region;
    auto status = deriveRegion(write, query, region, true, std::nullopt, true);
    if (status != RegionStatus::Proved)
      return status;
    regions.push_back(std::move(region));
  }
  StaticShapeRegion selected;
  auto status = deriveRegion(read, query, selected, true, std::nullopt, true);
  if (status != RegionStatus::Proved)
    return status;
  if (selected.kind == StaticShapeRegion::Kind::Empty)
    return RegionStatus::Proved;
  const bool stridedRead = selected.iteration && selected.iteration->stride != 1;
  if (stridedRead) {
    // An exact mapped family read can be covered without filling its holes.
    for (unsigned i = 0; i != regions.size(); ++i) {
      if (!sameImage(selected, regions[i], query))
        continue;
      bool disjoint = true;
      for (unsigned j = 0; j != writes.size(); ++j) {
        if (i == j)
          continue;
        if (!query.spend())
          return query.failedStatus();
        if (regions[j].kind == StaticShapeRegion::Kind::Empty)
          continue;
        auto relation = compareStridedImages(selected, regions[j], query);
        if (!relation || *relation != ShapeRegionOverlap::Disjoint) {
          disjoint = false;
          break;
        }
      }
      if (disjoint)
        return query.status;
    }
  }
  SmallVector<StaticShapeRegion, 4> solid;
  status = coalesceResidues(regions, query, solid);
  if (status != RegionStatus::Proved)
    return status;
  limits.maximumWork -= std::min(limits.maximumWork, query.work);
  // A complete exact root partition covers every independently checked read.
  // Prove it before introducing read-only cuts whose relative order may vary
  // (for example N and 1 in a possibly-empty [0,N) predecessor read).
  uint64_t partitionWork = 0;
  auto complete = verifyRegionCoverage(read.root, solid, use, limits, false, &partitionWork);
  limits.maximumWork -= std::min(limits.maximumWork, partitionWork);
  if (complete == RegionStatus::Proved || complete == RegionStatus::ResourceLimit)
    return complete;
  if (stridedRead)
    return RegionStatus::Unsupported;
  solid.push_back(std::move(selected));
  return verifyRegionCoverage(read.root, solid, use, limits, true);
}

namespace {
std::optional<IntegerExpression> pointFlat(const StaticShapePoint &point, RegionQuery &query,
                                           llvm::ArrayRef<IntegerExpression> dimensions) {
  if (point.flatOrdinal)
    return point.flatOrdinal;
  if (point.coordinates.size() != dimensions.size())
    return std::nullopt;
  IntegerExpression result = query.zero;
  for (unsigned axis = 0; axis != dimensions.size(); ++axis) {
    auto shifted = query.apply("mul", result, dimensions[axis]);
    auto next = shifted ? query.apply("add", *shifted, point.coordinates[axis]) : std::nullopt;
    if (!next)
      return std::nullopt;
    result = *next;
  }
  return result;
}
std::optional<llvm::SmallVector<IntegerExpression, 4>>
pointCoordinates(const StaticShapePoint &point, RegionQuery &query,
                 llvm::ArrayRef<IntegerExpression> dimensions) {
  if (point.coordinates.size() == dimensions.size())
    return point.coordinates;
  if (!point.flatOrdinal)
    return std::nullopt;
  llvm::SmallVector<IntegerExpression, 4> result(dimensions.size());
  IntegerExpression remainder = *point.flatOrdinal;
  for (unsigned axis = dimensions.size(); axis > 0; --axis) {
    if (query.less(query.zero, remainder, true) && query.less(remainder, dimensions[axis - 1])) {
      result[axis - 1] = remainder;
      remainder = query.zero;
      continue;
    }
    auto digit = query.apply("mod", remainder, dimensions[axis - 1]);
    auto next = query.apply("div", remainder, dimensions[axis - 1]);
    if (!digit || !next)
      return std::nullopt;
    result[axis - 1] = *digit;
    remainder = *next;
  }
  return result;
}
StaticShapeScalarLocation locate(const StaticShapeRegion &region, const StaticShapePoint &point,
                                 RegionQuery &query) {
  auto reject = [&](ShapeRegionMembership membership = ShapeRegionMembership::Unknown) {
    return StaticShapeScalarLocation{
        membership == ShapeRegionMembership::Unknown ? query.failedStatus() : RegionStatus::Proved,
        membership, std::nullopt};
  };
  if (!region.root || !point.root || !query.module)
    return {RegionStatus::Invalid, ShapeRegionMembership::Unknown, std::nullopt};
  if (region.root != point.root || region.kind == StaticShapeRegion::Kind::Empty)
    return reject(ShapeRegionMembership::Outside);
  if (region.iteration && region.iteration->stride != 1)
    return reject();
  auto dimensions = query.dimensions(region.root.getType());
  if (!dimensions)
    return reject();
  if (region.kind == StaticShapeRegion::Kind::Interval) {
    auto flat = pointFlat(point, query, *dimensions);
    if (!flat)
      return reject();
    if (query.less(*flat, region.interval.lower) ||
        query.less(region.interval.upperExclusive, *flat, true))
      return reject(ShapeRegionMembership::Outside);
    auto width = query.apply("sub", region.interval.upperExclusive, region.interval.lower);
    if (!width || (width->bounds.lower == 1 && width->bounds.upper == 1 &&
                   !query.equal(*flat, region.interval.lower)))
      return reject();
    if (!query.less(region.interval.lower, *flat, true) ||
        !query.less(*flat, region.interval.upperExclusive))
      return reject();
    auto ordinal = query.apply("sub", *flat, region.interval.lower);
    return ordinal ? StaticShapeScalarLocation{RegionStatus::Proved, ShapeRegionMembership::Inside,
                                               *ordinal}
                   : reject();
  }
  auto box = regionBox(region, query, *dimensions);
  auto pointCoords = pointCoordinates(point, query, *dimensions);
  if (!box || !pointCoords || box->size() != pointCoords->size())
    return reject();
  IntegerExpression ordinal = query.zero;
  bool uncertain = false;
  for (unsigned axis = 0; axis != box->size(); ++axis) {
    const auto &range = (*box)[axis];
    const auto &coordinate = (*pointCoords)[axis];
    if (query.less(coordinate, range.lower) || query.less(range.upperExclusive, coordinate, true))
      return reject(ShapeRegionMembership::Outside);
    uncertain |=
        !query.less(range.lower, coordinate, true) || !query.less(coordinate, range.upperExclusive);
    auto width = query.apply("sub", range.upperExclusive, range.lower);
    if (width && width->bounds.lower == 1 && width->bounds.upper == 1 &&
        !query.equal(coordinate, range.lower))
      uncertain = true;
    auto relative = query.apply("sub", coordinate, range.lower);
    auto shifted = width ? query.apply("mul", ordinal, *width) : std::nullopt;
    auto next = shifted && relative ? query.apply("add", *shifted, *relative) : std::nullopt;
    if (!next)
      return reject();
    ordinal = *next;
  }
  return uncertain || query.status != RegionStatus::Proved
             ? reject()
             : StaticShapeScalarLocation{RegionStatus::Proved, ShapeRegionMembership::Inside,
                                         ordinal};
}
RegionStatus pointFromRegion(const StaticShapeRegion &region, RegionQuery &query,
                             StaticShapePoint &result) {
  auto dimensions = query.dimensions(region.root.getType());
  if (!dimensions)
    return query.failedStatus();
  StaticShapePoint point;
  point.root = region.root;
  if (region.kind == StaticShapeRegion::Kind::Interval) {
    auto width = query.apply("sub", region.interval.upperExclusive, region.interval.lower);
    if (!width || !query.equal(*width, query.one))
      return query.failedStatus();
    point.flatOrdinal = region.interval.lower;
  } else {
    auto box = regionBox(region, query, *dimensions);
    if (!box)
      return query.failedStatus();
    for (const auto &range : *box) {
      auto width = query.apply("sub", range.upperExclusive, range.lower);
      if (!width || !query.equal(*width, query.one))
        return query.failedStatus();
      point.coordinates.push_back(range.lower);
    }
    point.flatOrdinal = pointFlat(point, query, *dimensions);
  }
  if (query.status != RegionStatus::Proved)
    return query.status;
  result = std::move(point);
  return RegionStatus::Proved;
}

} // namespace

ShapeRegionProofStatus resolveStaticShapePoint(const StaticShapeProjection &projection,
                                               Operation *use, StaticShapePoint &result,
                                               ShapeRegionProofLimits limits) {
  RegionQuery query(use, limits);
  Value selected = projection.projections.empty() ? projection.root
                                                  : projection.projections.back()->getResult(0);
  if (!selected || llvm::isa<ShapedType>(selected.getType()))
    return RegionStatus::Invalid;
  StaticShapeRegion region;
  auto status = deriveRegion(projection, query, region, true);
  if (status != RegionStatus::Proved)
    return status;
  return pointFromRegion(region, query, result);
}

StaticShapeScalarLocation locateStaticShapeScalar(const StaticShapeRegion &region,
                                                  const StaticShapePoint &point, Operation *use,
                                                  ShapeRegionProofLimits limits) {
  RegionQuery query(use, limits);
  if (region.projection) {
    StaticShapeRegion physical;
    auto status = deriveRegion(*region.projection, query, physical, true, std::nullopt,
                               region.iteration.has_value());
    if (status != RegionStatus::Proved)
      return {status, ShapeRegionMembership::Unknown, std::nullopt};
    return locate(physical, point, query);
  }
  return locate(region, point, query);
}
StaticShapeScalarLocation locateStaticShapeScalar(const StaticShapeProjection &regionProjection,
                                                  const StaticShapeProjection &scalarProjection,
                                                  Operation *use, ShapeRegionProofLimits limits) {
  StaticShapeRegion region;
  auto status = resolveStaticShapeRegion(regionProjection, use, region, limits);
  if (status != RegionStatus::Proved)
    return {status, ShapeRegionMembership::Unknown, std::nullopt};
  StaticShapePoint point;
  status = resolveStaticShapePoint(scalarProjection, use, point, limits);
  if (status != RegionStatus::Proved)
    return {status, ShapeRegionMembership::Unknown, std::nullopt};
  return locateStaticShapeScalar(region, point, use, limits);
}

ShapeRegionProofStatus mapStaticShapeOrdinal(const StaticShapeRegion &region,
                                             const InstantiatedIntegerExpression &ordinal,
                                             Operation *use, StaticShapePoint &result,
                                             ShapeRegionProofLimits limits) {
  RegionQuery query(use, limits);
  if (!region.root || !query.module || !ordinal.root)
    return RegionStatus::Invalid;
  if (region.kind == StaticShapeRegion::Kind::Empty || region.iteration)
    return RegionStatus::Unsupported;
  if (region.projection) {
    StaticShapeRegion singleton;
    auto status = deriveRegion(*region.projection, query, singleton, true, ordinal);
    return status == RegionStatus::Proved ? pointFromRegion(singleton, query, result) : status;
  }
  auto dimensions = query.dimensions(region.root.getType());
  if (!dimensions)
    return query.failedStatus();
  StaticShapePoint point;
  point.root = region.root;
  if (region.kind == StaticShapeRegion::Kind::Interval) {
    auto width = query.apply("sub", region.interval.upperExclusive, region.interval.lower);
    if (!width || !query.less(query.zero, ordinal, true) || !query.less(ordinal, *width))
      return query.failedStatus();
    point.flatOrdinal = query.apply("add", region.interval.lower, ordinal);
    if (!point.flatOrdinal)
      return query.failedStatus();
  } else {
    auto box = regionBox(region, query, *dimensions);
    if (!box || box->size() != dimensions->size())
      return RegionStatus::Invalid;
    llvm::SmallVector<IntegerExpression, 4> widths;
    for (const auto &range : *box) {
      auto width = query.apply("sub", range.upperExclusive, range.lower);
      if (!width || width->bounds.lower <= 0)
        return query.failedStatus();
      widths.push_back(*width);
    }
    auto count = query.count(widths);
    if (!count || !query.less(query.zero, ordinal, true) || !query.less(ordinal, *count))
      return query.failedStatus();
    // A singleton selection retains its exact opaque scalar atom. More general
    // row-major re-radixing is checked DAG arithmetic, not lane expansion.
    StaticShapePoint relative;
    relative.flatOrdinal = ordinal;
    auto coordinates = pointCoordinates(relative, query, widths);
    if (!coordinates)
      return query.failedStatus();
    for (unsigned axis = 0; axis != widths.size(); ++axis) {
      auto coordinate = query.apply("add", (*box)[axis].lower, (*coordinates)[axis]);
      if (!coordinate)
        return query.failedStatus();
      point.coordinates.push_back(*coordinate);
    }
    point.flatOrdinal = pointFlat(point, query, *dimensions);
  }
  if (query.status != RegionStatus::Proved)
    return query.status;
  result = std::move(point);
  return RegionStatus::Proved;
}
ShapeRegionProofStatus mapStaticShapeOrdinal(const StaticShapeProjection &projection,
                                             const InstantiatedIntegerExpression &ordinal,
                                             Operation *use, StaticShapePoint &result,
                                             ShapeRegionProofLimits limits) {
  StaticShapeRegion region;
  auto status = resolveStaticShapeRegion(projection, use, region, limits);
  return status == RegionStatus::Proved
             ? mapStaticShapeOrdinal(region, ordinal, use, result, limits)
             : status;
}

} // namespace nodal
