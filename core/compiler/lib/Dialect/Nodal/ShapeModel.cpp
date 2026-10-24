#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/StaticPredicateModel.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/ScopeExit.h"
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
class RegionQuery;
// Geometry retains the exact typed SSA use. Types are uniqued across regions,
// but their semantic-path tokens are not an authority to choose a region.
class TypedGeometry {
public:
  // Successful context proofs for exact original SSA values, never equal types
  // or dimension tokens. The owner is private to one read-only query; the IR
  // must stay alive and unchanged until that query completes. A borrowed owner
  // is checked against this geometry's Module on every access.
  struct CheckedContexts {
    explicit CheckedContexts(Operation *module) : module(module) {}
    Operation *module;
    llvm::DenseMap<Value, Operation *> successful;
  };
  TypedGeometry(Operation *module, unsigned &remainingWork,
                CheckedContexts *sharedContexts = nullptr)
      : module(module), remainingWork(remainingWork), localContexts(module),
        checkedContexts(sharedContexts ? sharedContexts : &localContexts) {}
  bool spend() {
    if (!remainingWork)
      return false;
    --remainingWork;
    return true;
  }
  FailureOr<Operation *> context(Value original) {
    if (checkedContexts->module != module)
      return failure();
    if (auto found = checkedContexts->successful.find(original);
        found != checkedContexts->successful.end()) {
      if (!spend())
        return failure();
      return found->second;
    }
    Value value = original;
    for (unsigned depth = 0; value && depth < 128; ++depth) {
      if (!spend())
        return failure();
      if (auto argument = dyn_cast<BlockArgument>(value)) {
        auto *scope = argument.getOwner()->getParentOp();
        if (!scope || scope->getParentOfType<ModuleOp>() != module)
          return failure();
        // The canonical capture owner checks and charges the original signature.
        auto source = digitalRegionCaptureSource(argument, remainingWork);
        if (failed(source) || !*source || source->getType() != value.getType() ||
            failed(verifyDigitalValueScope(*source, scope, remainingWork)))
          return failure();
        value = *source;
        continue;
      }
      auto *producer = value.getDefiningOp();
      if (!producer)
        return failure();
      bool generated = false;
      Operation *scope = producer->getParentOp();
      for (unsigned ancestry = 0; scope && scope != module && ancestry < 128; ++ancestry) {
        if (!spend())
          return failure();
        generated |= isa<GenerateOp>(scope);
        scope = scope->getParentOp();
      }
      if (scope != module || (generated && !isGeneratedTypeUse(producer, remainingWork)))
        return failure();
      // Ordinary regions retain their previous Module-dimension policy. Their
      // original projection/storage verifiers own admission; digital headers
      // must not newly exclude valid analog/procedural storage producers.
      // Insert only after the entire original capture/header/scope chain has
      // succeeded. Partial captures and failed/resource-limited proofs stay out.
      checkedContexts->successful.try_emplace(original, producer);
      return producer;
    }
    return failure();
  }
  FailureOr<SmallVector<StaticShapeDimension, 4>> dimensions(Value value) {
    auto use = context(value);
    if (failed(use))
      return failure();
    return dimensionsInContext(value, *use);
  }
  Operation *module;
  unsigned &remainingWork;

private:
  friend class RegionQuery;

  // Only callers inside this owner, or RegionQuery immediately after context(),
  // may reuse the checked context for this exact original SSA value. Do not
  // accept a context inferred from an equal type or from another value.
  FailureOr<SmallVector<StaticShapeDimension, 4>>
  dimensionsInContext(Value value, Operation *use) {
    auto key = std::make_pair(value, use);
    if (auto found = cache.find(key); found != cache.end())
      return found->second;
    auto shape = physicalShape(value.getType());
    if (!shape)
      return failure();
    SmallVector<StaticShapeDimension, 4> result;
    for (auto token : shape->dimensions) {
      if (!spend())
        return failure();
      auto proof = !use->getParentOfType<GenerateOp>()
                       ? resolveStaticShapeDimension(module, token.trim(), "shape", remainingWork)
                       : resolveGeneratedTypeDimension(use, token.trim(), "shape", remainingWork);
      if (failed(proof))
        return failure();
      result.push_back(*proof);
    }
    cache.try_emplace(key, result);
    return result;
  }
  FailureOr<SmallVector<InstantiatedIntegerExpression, 4>>
  expressionsInContext(Value value, Operation *use) {
    auto proven = dimensionsInContext(value, use);
    if (failed(proven))
      return failure();
    SmallVector<InstantiatedIntegerExpression, 4> result;
    for (const auto &dimension : *proven) {
      if (!spend())
        return failure();
      FailureOr<InstantiatedIntegerExpression> expression =
          makeLiteralIntegerExpression(dimension.literal);
      if (dimension.parameter)
        expression = resolveOwnedIntegerParameter(module, dimension.parameter, remainingWork);
      else if (dimension.expression) {
        if (!use->getParentOfType<GenerateOp>())
          expression = resolveOwnedIntegerExpression(module, dimension.expression, remainingWork);
        else {
          auto facts =
              resolveStructuralIntegerExpression(dimension.expression, use, remainingWork);
          if (failed(facts) || !facts->expression)
            return failure();
          expression = *facts->expression;
        }
      }
      if (failed(expression) || expression->bounds.lower <= 0)
        return failure();
      result.push_back(*expression);
    }
    return result;
  }
  CheckedContexts localContexts;
  CheckedContexts *checkedContexts;
  llvm::DenseMap<std::pair<Value, Operation *>, SmallVector<StaticShapeDimension, 4>> cache;
};
std::string dimensionExpressionKey(Value value) {
  if (auto *op = value.getDefiningOp())
    return "e:" + pointerKey(op); // Preserve ordinary canonical output.
  return "v:" + std::to_string(reinterpret_cast<uintptr_t>(value.getAsOpaquePointer()));
}
FailureOr<std::string> dimensionsKey(TypedGeometry &geometry, Value value) {
  auto dimensions = geometry.dimensions(value);
  if (failed(dimensions))
    return failure();
  if (dimensions->empty())
    return std::string("scalar");
  std::string result;
  for (const auto &dimension : *dimensions) {
    if (!result.empty())
      result += ',';
    if (dimension.parameter)
      result += "p:" + pointerKey(dimension.parameter);
    else if (dimension.expression)
      result += dimensionExpressionKey(dimension.expression);
    else
      result += "l:" + std::to_string(dimension.literal);
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
std::optional<StaticProduct> dimensionProduct(TypedGeometry &geometry, Value value,
                                              unsigned first = 0) {
  StaticProduct result;
  auto dimensions = geometry.dimensions(value);
  if (failed(dimensions) || first > dimensions->size())
    return std::nullopt;
  for (const auto &dimension :
       llvm::ArrayRef<StaticShapeDimension>(*dimensions).drop_front(first)) {
    StaticProduct factor;
    if (dimension.parameter)
      factor.factors.push_back("p:" + pointerKey(dimension.parameter));
    else if (dimension.expression)
      factor.factors.push_back(dimensionExpressionKey(dimension.expression));
    else
      factor.coefficient = dimension.literal;
    auto multiplied = multiplyProduct(result, factor);
    if (!multiplied)
      return std::nullopt;
    result = std::move(*multiplied);
  }
  return result;
}
std::optional<ParameterIntegerBounds> dimensionProductBounds(TypedGeometry &geometry, Value value,
                                                             unsigned first) {
  ParameterIntegerBounds result{1, 1};
  auto dimensions = geometry.dimensions(value);
  if (failed(dimensions) || first > dimensions->size())
    return std::nullopt;
  for (const auto &dimension :
       llvm::ArrayRef<StaticShapeDimension>(*dimensions).drop_front(first)) {
    if (dimension.bounds.lower <= 0 || dimension.bounds.upper < dimension.bounds.lower ||
        result.upper > std::numeric_limits<int64_t>::max() / dimension.bounds.upper)
      return std::nullopt;
    result.lower *= dimension.bounds.lower;
    result.upper *= dimension.bounds.upper;
  }
  return result;
}
void addOffsetBounds(std::optional<ParameterIntegerBounds> &offset, Operation *module, Value index,
                     Value input, unsigned strideAxis, Operation *use, TypedGeometry &geometry) {
  if (!offset)
    return;
  auto position = indexBounds(module, index, use);
  auto stride = dimensionProductBounds(geometry, input, strideAxis);
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
std::optional<llvm::SmallVector<int64_t>> fixedDimensions(TypedGeometry &geometry, Value value) {
  llvm::SmallVector<int64_t> result;
  auto dimensions = geometry.dimensions(value);
  if (failed(dimensions))
    return std::nullopt;
  for (const auto &dimension : *dimensions) {
    if (dimension.bounds.lower != dimension.bounds.upper)
      return std::nullopt;
    result.push_back(dimension.bounds.lower);
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
                                         int64_t value, TypedGeometry &geometry) {
  for (Operation *projection : llvm::reverse(projections)) {
    if (llvm::isa<ShapeViewOp>(projection))
      continue; // Reshape preserves the immediate-input logical ordinal.
    auto input = fixedDimensions(geometry, projection->getOperand(0));
    auto output = fixedDimensions(geometry, projection->getResult(0));
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
void deriveScalarCoordinates(Operation *module, Value reference, StaticShapeProjection &result,
                             TypedGeometry &geometry) {
  auto rootType = llvm::dyn_cast<ShapedType>(result.root.getType());
  if (!rootType || llvm::isa<ShapedType>(reference.getType()))
    return;
  auto cardinality = dimensionProductBounds(geometry, result.root, 0);
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
      Value outputValue = projection->getResult(0);
      auto output = dimensionsKey(geometry, projection->getResult(0));
      while (cursor > 0 && llvm::isa<ShapeViewOp>(result.projections[cursor - 1]))
        projection = result.projections[--cursor];
      auto input = dimensionsKey(geometry, projection->getOperand(0));
      if (failed(input) || failed(output))
        return;
      if (*input != *output) {
        // Literal coordinates can be re-radixed through fixed intermediate
        // shapes even when an earlier slice's root extent remains symbolic.
        // Never substitute singleton parameter/index bounds for opaque atoms.
        auto inputDimensions = fixedDimensions(geometry, projection->getOperand(0));
        auto outputDimensions = fixedDimensions(geometry, outputValue);
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
    auto stride = dimensionProduct(geometry, result.root, axis + 1);
    auto rootDimensions = geometry.dimensions(result.root);
    if (failed(rootDimensions))
      return;
    auto dimension = FailureOr<StaticShapeDimension>((*rootDimensions)[axis]);
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

namespace {
FailureOr<InstantiatedIntegerExpression>
resolveGeneratedOrdinalImpl(Operation *family, Value ordinal, Operation *use,
                            ArrayRef<GeneratedSelection> prefix, unsigned *remainingWork);
} // namespace

FailureOr<InstantiatedIntegerExpression> resolveGeneratedOrdinal(Operation *family, Value ordinal,
                                                                 Operation *use) {
  return resolveGeneratedOrdinal(family, ordinal, use, {});
}

FailureOr<InstantiatedIntegerExpression>
resolveGeneratedOrdinal(Operation *family, Value ordinal, Operation *use,
                        llvm::ArrayRef<GeneratedSelection> prefix) {
  return resolveGeneratedOrdinalImpl(family, ordinal, use, prefix, nullptr);
}

namespace {
FailureOr<InstantiatedIntegerExpression>
generatedOrdinalCoordinate(const InstantiatedIntegerExpression &index,
                           const InstantiatedIntegerExpression &lower,
                           const InstantiatedIntegerExpression &step) {
  auto displacement = composeIntegerExpression("mul", {index, step});
  return succeeded(displacement) ? composeIntegerExpression("add", {lower, *displacement})
                                 : FailureOr<InstantiatedIntegerExpression>(failure());
}
LogicalResult chargeCoordinate(const InstantiatedIntegerExpression &expression, unsigned &work) {
  llvm::DenseSet<const InstantiatedIntegerNode *> visited;
  SmallVector<std::pair<const InstantiatedIntegerNode *, unsigned>, 8> pending{
      {expression.root.get(), 0}};
  while (!pending.empty()) {
    auto [node, depth] = pending.pop_back_val();
    if (!work || !node || depth >= 128)
      return failure();
    --work;
    if (!visited.insert(node).second)
      continue;
    for (const auto &operand : node->operands)
      pending.emplace_back(operand.get(), depth + 1);
  }
  return success();
}
} // namespace

FailureOr<InstantiatedIntegerExpression>
resolveGeneratedOrdinal(Operation *family, Value ordinal, Operation *use,
                        llvm::ArrayRef<GeneratedSelection> prefix, unsigned &work) {
  return resolveGeneratedOrdinalImpl(family, ordinal, use, prefix, &work);
}

namespace {
FailureOr<InstantiatedIntegerExpression>
resolveGeneratedOrdinalImpl(Operation *family, Value ordinal, Operation *use,
                            ArrayRef<GeneratedSelection> prefix, unsigned *work) {
  if (!family || !ordinal || !use || prefix.size() > 128 || (work && !*work))
    return failure();
  if (work)
    --*work;
  auto domain = work ? resolveGeneratedDomain(family, *work) : resolveGeneratedDomain(family);
  if (failed(domain) || !ordinal.getType().isSignlessInteger(64) ||
      family->getParentOfType<ModuleOp>() != use->getParentOfType<ModuleOp>() ||
      failed(verifyDigitalValueScope(ordinal, use)))
    return failure();
  // Prefix facts come only from the recursively inspected actual family/export
  // chain. Substitute its exact induction identities in this query, never in the
  // declaration's retained domain or maximum trip count. A captured family view
  // need not expose its parent's ordinal as a fresh SSA use in the current arm.
  llvm::SmallVector<std::pair<Value, InstantiatedIntegerExpression>, 2> substitutions;
  Operation *previous = nullptr;
  for (const auto &selection : prefix) {
    Operation *ancestor = selection.family;
    if (!llvm::isa_and_nonnull<GenerateOp>(ancestor) || !ancestor->isProperAncestor(family) ||
        (previous && !previous->isProperAncestor(ancestor)) || ancestor->getNumRegions() != 1 ||
        !llvm::hasSingleElement(ancestor->getRegion(0)) ||
        ancestor->getRegion(0).front().getNumArguments() == 0 || !selection.coordinate.root)
      return failure();
    if (work && failed(chargeCoordinate(selection.coordinate, *work)))
      return failure();
    substitutions.emplace_back(ancestor->getRegion(0).front().getArgument(0), selection.coordinate);
    previous = ancestor;
  }
  if (!prefix.empty()) {
    auto lower = substituteIntegerExpression(domain->lower, substitutions);
    auto upper = substituteIntegerExpression(domain->upperExclusive, substitutions);
    auto step = substituteIntegerExpression(domain->step, substitutions);
    if (failed(lower) || failed(upper) || failed(step) || step->bounds.lower <= 0)
      return failure();
    domain->lower = *lower;
    domain->upperExclusive = *upper;
    domain->step = *step;
  }
  // Selecting a family member is a structural/topology use, not a width or
  // array-extent effect. Preserve the actual static DAG and bounded ownership.
  auto structural = work ? resolveStructuralIntegerExpression(ordinal, use, *work)
                         : resolveStructuralIntegerExpression(ordinal, use);
  if (failed(structural) || !structural->expression)
    return failure();
  // Reuse the canonical lexical structural proof above. Re-resolving through
  // a module-only query would discard valid enclosing Generate ownership.
  const auto &index = *structural->expression;
  auto nonnegative =
      work ? proveStructuralIntegerLessAtUse(makeLiteralIntegerExpression(0), index, use, true,
                                             *work)
           : proveStructuralIntegerLessAtUse(makeLiteralIntegerExpression(0), index, use, true);
  if (failed(nonnegative) || !*nonnegative)
    return failure();
  auto coordinate = generatedOrdinalCoordinate(index, domain->lower, domain->step);
  if (failed(coordinate))
    return failure();
  auto exists =
      work ? proveStructuralIntegerLessAtUse(*coordinate, domain->upperExclusive, use, false, *work)
           : proveStructuralIntegerLessAtUse(*coordinate, domain->upperExclusive, use);
  if (failed(exists) || !*exists)
    return failure();
  return *coordinate;
}

} // namespace

static ShapeRegionProofStatus resolveGeneratedInductionOrdinalImpl(
    Operation *family, Value induction, Operation *use,
    InstantiatedIntegerExpression &result, unsigned &work,
    const GeneratedDomain *checkedDomain) {
  using Status = ShapeRegionProofStatus;
  auto unsupported = [&]() { return work ? Status::Unsupported : Status::ResourceLimit; };
  if (!work)
    return Status::ResourceLimit;
  --work;
  if (!isa_and_nonnull<GenerateOp>(family) || !use ||
      use->getParentOp() != family ||
      family->getNumRegions() != 1 || !llvm::hasSingleElement(family->getRegion(0)) ||
      family->getRegion(0).front().getNumArguments() != 1 ||
      induction != family->getRegion(0).front().getArgument(0))
    return Status::Invalid;
  if (!isa<ModuleOp>(family->getParentOp()))
    return Status::Unsupported;
  FailureOr<GeneratedDomain> domain = checkedDomain
      ? FailureOr<GeneratedDomain>(*checkedDomain) : resolveGeneratedDomain(family, work);
  if (failed(domain))
    return work ? Status::Invalid : Status::ResourceLimit;
  if (domain->induction != induction || !domain->attainedInductionBounds ||
      !domain->maximumTripCount || domain->maximumTripCount > uint64_t(INT64_MAX))
    return unsupported();
  auto literal = [](const InstantiatedIntegerExpression &e) {
    return e.root && e.root->kind == InstantiatedIntegerNode::Kind::Literal;
  };
  auto facts = resolveStructuralIntegerExpression(induction, use, work);
  if (failed(facts) || !facts->expression)
    return unsupported();
  // Identity rank only: preserve the original opaque SSA atom, never replace a
  // variable trip count by its maximum or synthesize a new induction identity.
  if (!literal(domain->upperExclusive)) {
    Operation *upper = family->getOperand(1).getDefiningOp();
    auto reference =
        upper ? upper->getAttrOfType<FlatSymbolRefAttr>("parameter") : FlatSymbolRefAttr();
    const auto &bound = domain->upperExclusive;
    const auto &actual = *facts->expression;
    if (!literal(domain->lower) || domain->lower.bounds.lower != 0 || !literal(domain->step) ||
        domain->step.bounds.lower != 1 ||
        !isa_and_nonnull<ConstLiteralOp>(family->getOperand(0).getDefiningOp()) ||
        !isa_and_nonnull<ConstLiteralOp>(family->getOperand(2).getDefiningOp()) ||
        !isa_and_nonnull<ConstParameterRefOp>(upper) ||
        upper->getParentOp() != family->getParentOp() || !reference || !bound.root ||
        bound.root->kind != InstantiatedIntegerNode::Kind::ParentParameter ||
        !bound.root->parameter || bound.root->parameter->getParentOp() != family->getParentOp() ||
        SymbolTable::lookupSymbolIn(family->getParentOp(), reference) != bound.root->parameter ||
        bound.bounds.lower <= 0 || bound.bounds.upper != int64_t(domain->maximumTripCount) ||
        domain->attainedInductionBounds->lower != 0 ||
        domain->attainedInductionBounds->upper != int64_t(domain->maximumTripCount - 1) ||
        !actual.root || actual.root->kind != InstantiatedIntegerNode::Kind::OpaqueValue ||
        actual.root->identity != induction || actual.bounds.lower != 0 ||
        actual.bounds.upper != domain->attainedInductionBounds->upper ||
        failed(chargeCoordinate(actual, work)))
      return unsupported();
    result = actual;
    return Status::Proved;
  }
  if (!literal(domain->lower) || !literal(domain->step) || domain->step.bounds.lower <= 0)
    return unsupported();
  // The certificate is the actual original Generate progression, not the atom's
  // interval. Its kth occurrence is lower+k*step for every 0 <= k < count.
  // Check the retained first/last witnesses against that same domain, then the
  // positive-step progression guarantees exact divisibility at every occurrence.
  auto lastRank = makeLiteralIntegerExpression(int64_t(domain->maximumTripCount - 1));
  auto last = generatedOrdinalCoordinate(lastRank, domain->lower, domain->step);
  if (failed(last) || !literal(*last) ||
      domain->attainedInductionBounds->lower != domain->lower.bounds.lower ||
      domain->attainedInductionBounds->upper != last->bounds.lower ||
      facts->expression->bounds.lower != domain->attainedInductionBounds->lower ||
      facts->expression->bounds.upper != domain->attainedInductionBounds->upper)
    return unsupported();
  auto distance = composeIntegerExpression("sub", {*facts->expression, domain->lower});
  auto rank = succeeded(distance)
                  ? composeIntegerExpression("div", {*distance, domain->step})
                  : FailureOr<InstantiatedIntegerExpression>(failure());
  if (failed(rank) || rank->bounds.lower != 0 ||
      rank->bounds.upper != int64_t(domain->maximumTripCount - 1) ||
      failed(chargeCoordinate(*rank, work)))
    return unsupported();
  // Check the inverse arithmetic at the retained canonical endpoint as well.
  // No lane enumeration or invented normalized induction atom is involved.
  auto lastDistance = composeIntegerExpression("sub", {*last, domain->lower});
  auto residue = succeeded(lastDistance)
                     ? composeIntegerExpression("mod", {*lastDistance, domain->step})
                     : FailureOr<InstantiatedIntegerExpression>(failure());
  auto recovered = succeeded(lastDistance)
                       ? composeIntegerExpression("div", {*lastDistance, domain->step})
                       : FailureOr<InstantiatedIntegerExpression>(failure());
  if (failed(residue) || failed(recovered) || !literal(*residue) ||
      residue->bounds.lower != 0 ||
      !equivalentInstantiatedIntegerExpressions(*recovered, lastRank))
    return unsupported();
  result = *rank;
  return Status::Proved;
}

ShapeRegionProofStatus resolveGeneratedInductionOrdinal(
    Operation *family, Value induction, Operation *use,
    InstantiatedIntegerExpression &result, unsigned &work) {
  return resolveGeneratedInductionOrdinalImpl(family, induction, use, result, work, nullptr);
}


FailureOr<InstantiatedIntegerExpression> resolveSelectedInstancePathGeneratedOrdinal(
    Operation *rootModule, ArrayRef<SelectedIntegerInstanceStep> path, Operation *family,
    Value ordinal, Operation *use, ArrayRef<GeneratedSelection> originalPrefix,
    ArrayRef<GeneratedSelection> evaluatedPrefix,
    ArrayRef<std::pair<Value, InstantiatedIntegerExpression>> lexicalCoordinates, unsigned &work) {
  // Reuse the actual selection inspector, not caller-chosen scope or copied
  // proof labels. It independently proves all-lane existence before specializing.
  if (!llvm::isa_and_nonnull<GeneratedMemberOp, InstancePortOp, InterfaceBoundaryOp>(use) ||
      use->getNumOperands() != 2 || use->getOperand(1) != ordinal)
    return failure();
  auto member = use->getAttrOfType<StringAttr>("member");
  SmallVector<Operation *, 2> uses;
  auto original = member ? inspectGeneratedSelection(use->getOperand(0), ordinal, member.getValue(),
                                                     use, uses, work)
                         : FailureOr<GeneratedSelectionProof>(failure());
  if (failed(original) || original->family != family || uses.empty() || uses.back() != use ||
      original->selections.size() != originalPrefix.size() + 1 ||
      originalPrefix.size() != evaluatedPrefix.size() || family->getNumOperands() != 3)
    return failure();
  for (auto [expected, supplied] :
       llvm::zip(ArrayRef<GeneratedSelection>(original->selections).drop_back(), originalPrefix)) {
    if (expected.family != supplied.family || expected.ordinal != supplied.ordinal ||
        failed(chargeCoordinate(expected.coordinate, work)) ||
        failed(chargeCoordinate(supplied.coordinate, work)) ||
        !equivalentInstantiatedIntegerExpressions(expected.coordinate, supplied.coordinate))
      return failure();
  }
  for (const auto &edge : path)
    if (failed(verifySelectedStructuralCoordinates(edge.coordinates, edge.instance, work)))
      return failure();
  SmallVector<std::pair<Value, InstantiatedIntegerExpression>, 2> substitutions;
  for (auto [before, after] : llvm::zip(originalPrefix, evaluatedPrefix)) {
    if (before.family != after.family || before.ordinal != after.ordinal ||
        failed(chargeCoordinate(after.coordinate, work)))
      return failure();
    substitutions.emplace_back(before.family->getRegion(0).front().getArgument(0),
                               after.coordinate);
  }
  if (failed(verifySelectedStructuralCoordinates(substitutions, family, work)))
    return failure();
  if (failed(verifySelectedStructuralCoordinates(lexicalCoordinates, use, work)))
    return failure();
  for (const auto &coordinate : lexicalCoordinates) {
    if (1 + substitutions.size() > work)
      return failure();
    work -= 1 + substitutions.size();
    auto previous = llvm::find_if(
        substitutions, [&](const auto &entry) { return entry.first == coordinate.first; });
    if (previous == substitutions.end()) {
      substitutions.push_back(coordinate);
    } else if (failed(chargeCoordinate(previous->second, work)) ||
               failed(chargeCoordinate(coordinate.second, work)) ||
               !equivalentInstantiatedIntegerExpressions(previous->second, coordinate.second)) {
      return failure();
    }
  }
  auto resolve = [&](Value value,
                     Operation *actualUse) -> FailureOr<InstantiatedIntegerExpression> {
    auto facts = path.empty() ? resolveStructuralIntegerExpression(value, actualUse, work)
                              : resolveSelectedInstancePathStructuralIntegerExpression(
                                    rootModule, path, value, actualUse, work);
    if (failed(facts) || !facts->expression || failed(chargeCoordinate(*facts->expression, work)))
      return failure();
    return substituteIntegerExpression(*facts->expression, substitutions);
  };
  auto lower = resolve(family->getOperand(0), family);
  auto upper = resolve(family->getOperand(1), family);
  auto step = resolve(family->getOperand(2), family);
  auto index = resolve(ordinal, use);
  if (failed(lower) || failed(upper) || failed(step) || failed(index) || step->bounds.lower <= 0)
    return failure();
  // Existence was proved independently above. Preserve its original actual-use
  // guards instead of inventing a contextual representative-lane guard proof.
  return generatedOrdinalCoordinate(*index, *lower, *step);
}

static FailureOr<StaticShapeProjection>
resolveStaticShapeProjectionImpl(Value reference, Operation *use, unsigned &remainingWork,
                                 TypedGeometry::CheckedContexts *checkedContexts = nullptr) {
  if (!reference || !use)
    return failure();
  auto module = use->getParentOfType<nodal::ModuleOp>();
  if (!module)
    return failure();
  TypedGeometry geometry(module, remainingWork, checkedContexts);
  if (!geometry.spend())
    return failure();
  StaticShapeProjection result;
  const Type element = shapeElement(reference.getType());
  if (!element)
    return failure();
  llvm::SmallPtrSet<Operation *, 16> visited;
  Value current = reference;
  unsigned captureDepth = 0;
  while (current) {
    if (!geometry.spend())
      return failure();
    if (auto argument = llvm::dyn_cast<BlockArgument>(current)) {
      if (++captureDepth > 512 || !argument.getOwner()->getParentOp()->isProperAncestor(use))
        return failure();
      auto source = digitalRegionCaptureSource(argument);
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
    if (!geometry.spend())
      return failure();
    if (!precedesLexically(previous, projection))
      return failure();
    auto valid = llvm::isa<ShapeIndexOp>(projection)  ? verifyShapeIndexMapping(projection)
                 : llvm::isa<ShapeViewOp>(projection) ? verifyShapeViewMapping(projection)
                                                      : verifyShapeSliceMapping(projection);
    if (failed(valid))
      return failure();
    previous = projection;
  }
  if (!dimensionProductBounds(geometry, result.root, 0))
    return failure();
  auto rootDimensions = fixedDimensions(geometry, result.root);
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
    auto inputKey = dimensionsKey(geometry, projection->getOperand(0));
    auto outputKey = dimensionsKey(geometry, projection->getResult(0));
    if (failed(inputKey) || failed(outputKey))
      return failure();
    std::string step;
    bool identityMap = false;
    if (llvm::isa<ShapeIndexOp>(projection)) {
      step = "/index[" + *inputKey + "](";
      bool singletonPrefix = true;
      auto fixed = fixedDimensions(geometry, projection->getOperand(0));
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
          auto stride = dimensionProduct(geometry, projection->getOperand(0), ++axis);
          addOffsetBounds(exactOffsetBounds, module, index, projection->getOperand(0), axis,
                          projection, geometry);
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
        auto fixed = fixedDimensions(geometry, projection->getOperand(0));
        if (!leading && fixed)
          leading = llvm::all_of(llvm::ArrayRef<int64_t>(*fixed).take_front(axis),
                                 [](int64_t extent) { return extent == 1; });
        auto lowerProduct = indexProduct(module, projection->getOperand(1), projection);
        auto stride = dimensionProduct(geometry, projection->getOperand(0), axis + 1);
        auto term = lowerProduct && stride ? multiplyProduct(*lowerProduct, *stride) : std::nullopt;
        addOffsetBounds(exactOffsetBounds, module, projection->getOperand(1),
                        projection->getOperand(0), axis + 1, projection, geometry);
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
      auto count = dimensionProduct(geometry, projection->getResult(0));
      if (count)
        result.canonicalKey = linearKey(offset, *count);
      else
        linear = false;
    }
    if (result.wholeRoot)
      result.canonicalKey = "whole";
    if (!llvm::isa<ShapedType>(projection->getResult(0).getType())) {
      if (auto offset = mapLiteralOrdinal(module, prefix, 0, geometry))
        result.canonicalKey = "offset:" + std::to_string(*offset);
    }
  }
  auto selectedDimensions = fixedDimensions(geometry, reference);
  auto count = selectedDimensions ? product(*selectedDimensions) : std::nullopt;
  if (count) {
    result.literalFirstOffset = mapLiteralOrdinal(module, result.projections, 0, geometry);
    result.literalLastOffset = mapLiteralOrdinal(module, result.projections, *count - 1, geometry);
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
  deriveScalarCoordinates(module, reference, result, geometry);
  if (!remainingWork)
    return failure();
  return result;
}

FailureOr<StaticShapeProjection> resolveStaticShapeProjection(Value reference, Operation *use) {
  unsigned remainingWork = 65536;
  return resolveStaticShapeProjectionImpl(reference, use, remainingWork);
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
        limits(limits), checkedContexts(module) {}
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
  bool invariant(const IntegerExpression &expression) {
    SmallVector<std::shared_ptr<const InstantiatedIntegerNode>, 8> pending{expression.root};
    llvm::SmallPtrSet<const InstantiatedIntegerNode *, 16> visited;
    while (!pending.empty()) {
      auto node = pending.pop_back_val();
      if (!node || !spend())
        return false;
      if (!visited.insert(node.get()).second)
        continue;
      if (node->kind == InstantiatedIntegerNode::Kind::OpaqueValue)
        return false;
      pending.append(node->operands.begin(), node->operands.end());
    }
    return true;
  }
  unsigned remainingTypeWork() const {
    return static_cast<unsigned>(
        std::min<uint64_t>(65536, limits.maximumWork - std::min(work, limits.maximumWork)));
  }
  std::optional<GeneratedDomain> generatedDomain(Operation *family) {
    if (!spend())
      return std::nullopt;
    if (auto found = domains.find(family); found != domains.end())
      return found->second;
    unsigned remaining = remainingTypeWork(), before = remaining;
    auto domain = resolveGeneratedDomain(family, remaining);
    spend(before - remaining);
    if (failed(domain) && !remaining)
      status = RegionStatus::ResourceLimit;
    auto result = succeeded(domain) ? std::optional<GeneratedDomain>(*domain) : std::nullopt;
    domains.emplace(family, result);
    return result;
  }
  FailureOr<StaticShapeProjection> projection(Value selected, Operation *proofUse) {
    unsigned remaining = remainingTypeWork(), before = remaining;
    auto result = resolveStaticShapeProjectionImpl(selected, proofUse, remaining, &checkedContexts);
    spend(before - remaining);
    if (failed(result) && !remaining)
      status = RegionStatus::ResourceLimit;
    return result;
  }
  std::optional<llvm::SmallVector<IntegerExpression, 4>> dimensions(Value value) {
    unsigned remaining = remainingTypeWork(), before = remaining;
    auto release = llvm::scope_exit([&] { spend(before - remaining); });
    TypedGeometry geometry(module, remaining, &checkedContexts);
    auto context = geometry.context(value);
    if (failed(context)) {
      if (!remaining)
        status = RegionStatus::ResourceLimit;
      return std::nullopt;
    }
    auto key = std::make_pair(value, *context);
    if (auto found = dimensionCache.find(key); found != dimensionCache.end())
      return found->second;
    auto result = geometry.expressionsInContext(value, *context);
    if (failed(result)) {
      if (!remaining)
        status = RegionStatus::ResourceLimit;
      return std::nullopt;
    }
    dimensionCache.try_emplace(key, *result);
    return *result;
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
  // Shared only by this query's bounded projection and dimension owners. Never
  // seeded from the mapper's separate, unbudgeted actual-use authentication.
  TypedGeometry::CheckedContexts checkedContexts;
  llvm::DenseMap<std::pair<Value, Operation *>, llvm::SmallVector<IntegerExpression, 4>>
      dimensionCache;
  std::map<Operation *, std::optional<GeneratedDomain>> domains;
  std::map<std::pair<std::shared_ptr<const InstantiatedIntegerNode>,
                     std::shared_ptr<const InstantiatedIntegerNode>>,
           bool>
      equalities;
};

/// Physical declaration identity and an independently inspected selected prefix.
/// The aliases remain original SSA values; no synthetic root or lane is allocated.
struct PhysicalRoot {
  Operation *owner = nullptr;
  Operation *member = nullptr;
  StringAttr path;
  SmallVector<GeneratedSelection, 2> selections;
};
FailureOr<PhysicalRoot> physicalRoot(Value root, RegionQuery &query) {
  Operation *operation = root ? root.getDefiningOp() : nullptr;
  if (!operation || !query.spend())
    return failure();
  PhysicalRoot result{operation};
  Operation *selection = nullptr;
  if (isa<InstancePortOp>(operation)) {
    auto boundary = inspectInstancePort(operation);
    if (failed(boundary))
      return failure();
    result.owner = boundary->instance;
    result.member = boundary->port;
    selection = boundary->selection;
  } else if (isa<MemberAccessOp>(operation) && hasDigitalInterfaceContract(operation)) {
    auto member = inspectDigitalInterfaceMember(operation);
    if (failed(member))
      return failure();
    result.owner = member->instance ? member->instance : member->endpoint;
    result.member = member->endpoint;
    result.path = member->path;
    selection = member->selection;
  } else if (isa<PortValueOp>(operation)) {
    auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("port");
    auto module = operation->getParentOfType<nodal::ModuleOp>();
    result.owner = reference && module ? SymbolTable::lookupSymbolIn(module, reference) : nullptr;
    if (!isa_and_nonnull<PortOp>(result.owner))
      return failure();
  } else if (isa<GeneratedMemberOp>(operation)) {
    auto member = inspectGeneratedMember(operation);
    if (failed(member) || member->familyView)
      return failure();
    result.owner = member->storage.getDefiningOp();
    result.selections = std::move(member->selections);
  }
  if (selection) {
    auto key = selection->getAttrOfType<StringAttr>("member");
    auto proof = key && selection->getNumOperands() == 2
                     ? inspectGeneratedSelection(selection->getOperand(0), selection->getOperand(1),
                                                 key.getValue(), selection)
                     : FailureOr<GeneratedSelectionProof>(failure());
    if (failed(proof))
      return failure();
    result.selections = std::move(proof->selections);
  }
  return result;
}
/// Missing selected coordinates denote the authored template's full family,
/// not a distinct domainless root. Existence is already checked by the selector.
std::optional<ShapeRegionOverlap>
comparePhysicalRoots(const PhysicalRoot &left, const PhysicalRoot &right, RegionQuery &query) {
  if (left.owner != right.owner || left.member != right.member || left.path != right.path)
    return ShapeRegionOverlap::Disjoint;
  if (left.selections.empty() || right.selections.empty())
    return ShapeRegionOverlap::Overlapping;
  if (left.selections.size() != right.selections.size())
    return std::nullopt;
  bool equal = true;
  for (auto [a, b] : llvm::zip(left.selections, right.selections)) {
    if (!query.spend() || a.family != b.family)
      return std::nullopt;
    if (query.less(a.coordinate, b.coordinate) || query.less(b.coordinate, a.coordinate))
      return ShapeRegionOverlap::Disjoint;
    equal &= query.equal(a.coordinate, b.coordinate);
  }
  return equal ? std::optional<ShapeRegionOverlap>(ShapeRegionOverlap::Overlapping) : std::nullopt;
}

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
      if (isBoundedDigitalLoop(scope) || llvm::isa<GenerateOp>(scope) ||
          scope->getName().getStringRef() == "nodal.structural_if") {
        proofUse = last;
        break;
      }
  }
  // A relation may compare a read with a later writer or another lexical
  // alias. Recheck each original SSA chain at its actual definition instead of
  // granting visibility at the relation's diagnostic use. Access/capture rights
  // remain the caller's independently checked operation contract.
  if (iterationImages) {
    Operation *definition = selected.getDefiningOp();
    if (definition && !precedesLexically(definition, proofUse))
      proofUse = definition;
  }
  auto verified = query.projection(selected, proofUse);
  if (failed(verified) || verified->root != projection.root ||
      verified->projections != projection.projections)
    return query.status == RegionStatus::ResourceLimit ? query.status : RegionStatus::Invalid;
  auto selectedDimensions = query.dimensions(selected);
  if (!selectedDimensions)
    return query.failedStatus();
  RegionBox currentBox = query.full(*selectedDimensions);
  std::optional<RegionAxis> currentInterval;
  std::optional<StaticShapeIterationImage> iteration;
  SmallVector<StaticShapeIterationImage, 2> outerIterations;
  auto addIteration = [&](StaticShapeIterationImage next) {
    if (!query.spend(outerIterations.size() + 1))
      return false;
    if (!iteration) {
      iteration = std::move(next);
      return true;
    }
    if (outerIterations.size() + 2 > query.limits.maximumStates) {
      query.status = RegionStatus::ResourceLimit;
      return false;
    }
    // Only independent Generate coordinates compose into a Cartesian image.
    // Ordered-loop predecessor semantics remain deliberately single-axis.
    if (!isa<GenerateOp>(next.loop) || !isa<GenerateOp>(iteration->loop) || !next.varyingAxis ||
        !iteration->varyingAxis || next.loop == iteration->loop)
      return false;
    for (const auto &outer : outerIterations)
      if (outer.loop == next.loop ||
          (!outer.loop->isProperAncestor(next.loop) && !next.loop->isProperAncestor(outer.loop)))
        return false;
    if (iteration->loop->isProperAncestor(next.loop)) {
      outerIterations.push_back(*iteration);
      iteration = std::move(next);
    } else if (next.loop->isProperAncestor(iteration->loop)) {
      auto at = llvm::find_if(outerIterations, [&](const auto &outer) {
        return next.loop->isProperAncestor(outer.loop);
      });
      outerIterations.insert(at, std::move(next));
    } else {
      return false;
    }
    return true;
  };
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
    auto input = query.dimensions(operation->getOperand(0));
    auto output = query.dimensions(operation->getResult(0));
    if (!input || !output || !query.spend())
      return query.failedStatus();
    if (llvm::isa<ShapeViewOp>(operation)) {
      bool sameDimensions = input->size() == output->size();
      for (unsigned axis = 0; sameDimensions && axis != input->size(); ++axis)
        sameDimensions &= query.equal((*input)[axis], (*output)[axis]);
      if (sameDimensions)
        continue;
      // Re-radixing more than one occurrence axis needs a separate exact
      // mixed-radix proof; a bounding flat interval is not that proof.
      if (!outerIterations.empty())
        return query.failedStatus();
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
      const unsigned prefixRank = operation->getNumOperands() - 1;
      if (!query.spend(outerIterations.size() + 1))
        return query.failedStatus();
      if (iteration && iteration->varyingAxis)
        *iteration->varyingAxis += prefixRank;
      for (auto &outer : outerIterations)
        *outer.varyingAxis += prefixRank;
      bool addedIteration = false;
      IntegerExpression prefixOffset = query.zero;
      unsigned axis = 0;
      for (Value index : operation->getOperands().drop_front()) {
        std::optional<IntegerExpression> position, end;
        auto affine = (iterationImages || iterationStart)
                          ? resolveStaticShapeIterationIndex(index, operation)
                          : FailureOr<DigitalIterationIndex>(failure());
        if (succeeded(affine)) {
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
          else {
            if (!addIteration(StaticShapeIterationImage{
                    affine->loop, affine->domain, affine->offset, *count, query.one, axis, *step,
                    !affine->domain.attainedInductionBounds}))
              return query.failedStatus();
            addedIteration = true;
          }
        } else {
          // Recognize only the checked canonical rank of this original family.
          // Its physical stride is one; its original domain and induction are
          // not rebased into a fictitious zero-based Generate.
          Operation *family = operation->getParentOp();
          Operation *definition = index.getDefiningOp();
          if ((iterationImages || iterationStart) && isa<GenerateOp>(family) &&
              isa<ModuleOp>(family->getParentOp()) &&
              isa_and_nonnull<ConstExprOp>(definition) && definition->getParentOp() == family) {
            auto domain = query.generatedDomain(family);
            if (!domain)
              return query.status == RegionStatus::ResourceLimit
                         ? RegionStatus::ResourceLimit : RegionStatus::Invalid;
            unsigned available = unsigned(std::min<uint64_t>(
                UINT_MAX, query.limits.maximumWork - std::min(query.work, query.limits.maximumWork)));
            unsigned remaining = available;
            IntegerExpression ordinal;
            auto proof = resolveGeneratedInductionOrdinalImpl(
                family, family->getRegion(0).front().getArgument(0), operation, ordinal, remaining,
                &*domain);
            auto actual = resolveStructuralIntegerExpression(index, operation, remaining);
            if (!query.spend(available - remaining) || !remaining)
              return RegionStatus::ResourceLimit;
            if (proof == RegionStatus::Invalid || failed(actual))
              return RegionStatus::Invalid;
            if (proof == RegionStatus::Proved && actual->expression &&
                query.equal(*actual->expression, ordinal)) {
              position = query.zero;
              end = makeLiteralIntegerExpression(int64_t(domain->maximumTripCount));
              auto offset = query.apply("sub", query.zero, domain->lower);
              if (!offset)
                return query.failedStatus();
              if (iterationStart)
                end = query.one;
              else {
                if (!addIteration(StaticShapeIterationImage{
                        family, *domain, *offset,
                        makeLiteralIntegerExpression(int64_t(domain->maximumTripCount)),
                        query.one, axis, 1, false}))
                  return query.failedStatus();
                addedIteration = true;
              }
            }
          }
          if (!position) {
            position = query.selector(index, operation, true);
            end = position ? query.apply("add", *position, query.one) : std::nullopt;
          }
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
      if (currentInterval) {
        // Flat families can only be translated by loop-invariant prefixes.
        if (addedIteration || (iteration && !iterationInOutput))
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
  auto rootDimensions = query.dimensions(projection.root);
  if (!rootDimensions)
    return query.failedStatus();
  if (!outerIterations.empty()) {
    // Every independent coordinate and occurrence boundary must be invariant
    // over the complete ancestry. Triangular domains, diagonal repetitions and
    // opaque selector relationships are not rectangular Cartesian products.
    if (currentInterval || !iteration || !iteration->varyingAxis)
      return query.failedStatus();
    llvm::SmallDenseSet<unsigned, 4> axes;
    auto exact = [&](const StaticShapeIterationImage &map) {
      return query.spend() && map.varyingAxis && axes.insert(*map.varyingAxis).second &&
             query.invariant(map.domain.lower) && query.invariant(map.domain.upperExclusive) &&
             query.invariant(map.offset);
    };
    if (!exact(*iteration))
      return query.failedStatus();
    for (const auto &outer : outerIterations)
      if (!exact(outer))
        return query.failedStatus();
    for (const auto &axis : currentBox)
      if (!query.invariant(axis.lower) || !query.invariant(axis.upperExclusive))
        return query.failedStatus();
  }
  StaticShapeRegion region;
  region.root = projection.root;
  if (iteration || (!physicalSelectors && !logicalOrdinal))
    region.projection = std::move(*verified);
  region.iteration = iteration;
  region.outerIterations = std::move(outerIterations);
  if ((iteration && iteration->empty) ||
      llvm::any_of(region.outerIterations, [](const auto &outer) { return outer.empty; })) {
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

bool stridedImage(const StaticShapeRegion &region) {
  return (region.iteration && region.iteration->stride != 1) ||
         llvm::any_of(region.outerIterations, [](const auto &outer) { return outer.stride != 1; });
}

std::optional<RegionBox> regionBox(const StaticShapeRegion &region, RegionQuery &query,
                                   llvm::ArrayRef<IntegerExpression> dimensions) {
  if (region.kind == StaticShapeRegion::Kind::Empty || stridedImage(region))
    return std::nullopt;
  if (region.kind == StaticShapeRegion::Kind::Whole)
    return query.full(dimensions);
  if (region.kind == StaticShapeRegion::Kind::Box)
    return region.axes;
  return query.box(region.interval, dimensions);
}
std::optional<RegionAxis> regionInterval(const StaticShapeRegion &region, RegionQuery &query,
                                         llvm::ArrayRef<IntegerExpression> dimensions) {
  if (region.kind == StaticShapeRegion::Kind::Empty || stridedImage(region))
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

// Shared exact scalar progression membership. Bounds are the already checked
// all-lane trip-count envelope; holes are never filled by the enclosing interval.
std::pair<ShapeRegionMembership, std::optional<int64_t>>
locateScalarProgression(int64_t delta, int64_t stride, ParameterIntegerBounds trips) {
  if (stride <= 0)
    return {ShapeRegionMembership::Unknown, std::nullopt};
  if (delta < 0 || delta % stride || delta / stride >= trips.upper)
    return {ShapeRegionMembership::Outside, std::nullopt};
  int64_t ordinal = delta / stride;
  return {ordinal < trips.lower ? ShapeRegionMembership::Inside : ShapeRegionMembership::Unknown,
          ordinal};
}

std::optional<RegionBox> imageAxes(const StaticShapeRegion &region, RegionQuery &query) {
  if (region.iteration && !region.iteration->varyingAxis)
    return RegionBox{region.interval};
  auto dimensions = query.dimensions(region.root);
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
  if (!a.outerIterations.empty() || !b.outerIterations.empty() || !a.iteration || !b.iteration ||
      a.root != b.root || a.iteration->varyingAxis != b.iteration->varyingAxis ||
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
  if (!a.outerIterations.empty() || !b.outerIterations.empty()) {
    if (a.root != b.root || a.iterationCount() != b.iterationCount() ||
        !query.spend(a.iterationCount()))
      return false;
    for (unsigned i = 0; i != a.iterationCount(); ++i) {
      const auto &x = a.iterationAt(i), &y = b.iterationAt(i);
      if (x.varyingAxis != y.varyingAxis || x.stride != y.stride ||
          !query.equal(x.occurrenceWidth, y.occurrenceWidth) ||
          !query.equal(x.tripCount, y.tripCount))
        return false;
    }
    auto first = imageAxes(a, query), second = imageAxes(b, query);
    if (!first || !second || first->size() != second->size())
      return false;
    for (auto [x, y] : llvm::zip(*first, *second))
      if (!query.equal(x.lower, y.lower) || !query.equal(x.upperExclusive, y.upperExclusive))
        return false;
    return true;
  }
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
  if (!stridedImage(a) && !stridedImage(b))
    return std::nullopt;
  if (!a.outerIterations.empty() || !b.outerIterations.empty()) {
    auto first = imageAxes(a, query), second = imageAxes(b, query);
    if (!first || !second || first->size() != second->size())
      return ShapeRegionOverlap::Unknown;
    bool intersection = true;
    auto coordinate = [&](const StaticShapeRegion &region, unsigned axis, RegionAxis bounds) {
      StaticShapeRegion selected;
      selected.root = region.root;
      selected.kind = StaticShapeRegion::Kind::Box;
      selected.axes = {bounds};
      selected.interval = bounds;
      for (unsigned i = 0; i != region.iterationCount(); ++i) {
        const auto &map = region.iterationAt(i);
        if (map.varyingAxis == axis) {
          selected.iteration = map;
          selected.iteration->varyingAxis.reset();
          break;
        }
      }
      return selected;
    };
    if (!query.spend(first->size() * (a.iterationCount() + b.iterationCount() + 1)))
      return ShapeRegionOverlap::Unknown;
    for (unsigned axis = 0; axis != first->size(); ++axis) {
      const auto &x = (*first)[axis], &y = (*second)[axis];
      if (query.less(x.upperExclusive, y.lower, true) ||
          query.less(y.upperExclusive, x.lower, true))
        return ShapeRegionOverlap::Disjoint;
      auto left = coordinate(a, axis, x), right = coordinate(b, axis, y);
      // Reuse the one-coordinate progression proof, retaining ordinary axes
      // as ordinary intervals rather than inventing an induction or count.
      auto relation = compareStridedImages(left, right, query);
      if (relation && *relation == ShapeRegionOverlap::Disjoint)
        return *relation;
      intersection &=
          relation ? *relation == ShapeRegionOverlap::Overlapping
                   : query.less(x.lower, y.upperExclusive) && query.less(y.lower, x.upperExclusive);
    }
    return intersection ? ShapeRegionOverlap::Overlapping : ShapeRegionOverlap::Unknown;
  }
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
  auto location = locateScalarProgression(*delta, stride, family.iteration->tripCount.bounds);
  if (location.first == ShapeRegionMembership::Outside)
    return ShapeRegionOverlap::Disjoint;
  return invariantIntersection && location.first == ShapeRegionMembership::Inside
             ? ShapeRegionOverlap::Overlapping
             : ShapeRegionOverlap::Unknown;
}

// A universally single-occurrence image has no stride holes. Tighten only the
// private coverage geometry, retaining the original maps for every other API.
// A proof miss leaves the image unchanged; exhaustion is never a proof miss.
RegionStatus normalizeSingletonCoverage(StaticShapeRegion &region, RegionQuery &query) {
  if (query.status != RegionStatus::Proved || !query.spend())
    return query.status;
  if (region.kind == StaticShapeRegion::Kind::Empty || !region.iteration ||
      !region.outerIterations.empty() || region.iteration->empty || region.iteration->stride <= 1)
    return query.status;
  const auto &map = *region.iteration;
  if (!query.less(query.one, map.tripCount, true) ||
      !query.less(map.tripCount, query.one, true) ||
      !query.less(query.zero, map.occurrenceWidth) || !query.invariant(map.domain.lower) ||
      !query.invariant(map.domain.upperExclusive) || !query.invariant(map.offset) ||
      !query.invariant(map.occurrenceWidth))
    return query.status;
  if (map.varyingAxis) {
    if (*map.varyingAxis >= region.axes.size())
      return query.status;
    for (const auto &axis : region.axes)
      if (!query.invariant(axis.lower) || !query.invariant(axis.upperExclusive))
        return query.status;
  } else if (!query.invariant(region.interval.lower) ||
             !query.invariant(region.interval.upperExclusive)) {
    return query.status;
  }
  // The authored hull may be Whole although only its first occurrence exists.
  // Its mapped lower boundary already retains offsets, slices and views.
  auto &axis = map.varyingAxis ? region.axes[*map.varyingAxis] : region.interval;
  auto end = query.apply("add", axis.lower, map.occurrenceWidth);
  if (!end)
    return query.status;
  axis.upperExclusive = *end;
  region.kind = map.varyingAxis ? StaticShapeRegion::Kind::Box : StaticShapeRegion::Kind::Interval;
  region.iteration.reset();
  region.projection.reset();
  return query.status;
}

// Normalize the complete private writer list before grouping residues, so a
// singleton cannot join or consume another family. Duplicate boxes stay separate.
// Coalesce only an authored complete set with identical finite ordinal counts;
// work is bounded by supplied families, never step or lanes.
RegionStatus coalesceResidues(llvm::MutableArrayRef<StaticShapeRegion> input, RegionQuery &query,
                              SmallVectorImpl<StaticShapeRegion> &output) {
  for (auto &region : input) {
    auto status = normalizeSingletonCoverage(region, query);
    if (status != RegionStatus::Proved)
      return status;
  }
  std::vector<bool> consumed(input.size(), false);
  for (unsigned i = 0; i != input.size(); ++i) {
    if (consumed[i] || input[i].kind == StaticShapeRegion::Kind::Empty)
      continue;
    const auto &first = input[i];
    if (!stridedImage(first)) {
      output.push_back(first);
      continue;
    }
    // Nested residue completion is not yet admitted. Preserve holes rather
    // than treating a per-coordinate hull as complete Cartesian coverage.
    if (!first.outerIterations.empty())
      return query.failedStatus();
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
      if (consumed[j] || !next.outerIterations.empty() || !next.iteration ||
          next.iteration->stride != stride || !sameInvariantAxes(first, next, query) ||
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

namespace {
/// The arithmetic proof remains in ParameterModel. This small recognizer only
/// extracts the shared one-varying-axis map from that already-checked SSA DAG.
class IterationMapQuery {
public:
  FailureOr<DigitalIterationIndex> resolve(Value value, Operation *use, unsigned depth = 0) {
    if (++work > 65536 || depth > 128 || !value || !use ||
        failed(verifyDigitalValueScope(value, use)))
      return failure();
    if (auto found = memo.find(value); found != memo.end())
      return found->second;
    auto result = compute(value, use, depth);
    if (succeeded(result))
      memo.try_emplace(value, *result);
    return result;
  }

private:
  bool invariant(const InstantiatedIntegerExpression &expression) {
    SmallVector<std::shared_ptr<const InstantiatedIntegerNode>, 8> pending{expression.root};
    llvm::SmallPtrSet<const InstantiatedIntegerNode *, 16> visited;
    while (!pending.empty()) {
      auto node = pending.pop_back_val();
      if (!node || ++work > 65536)
        return false;
      if (!visited.insert(node.get()).second)
        continue;
      if (node->kind == InstantiatedIntegerNode::Kind::OpaqueValue)
        return false;
      pending.append(node->operands.begin(), node->operands.end());
    }
    return true;
  }
  FailureOr<DigitalIterationIndex> compute(Value value, Operation *use, unsigned depth) {
    if (auto argument = llvm::dyn_cast<BlockArgument>(value)) {
      Operation *owner = argument.getOwner()->getParentOp();
      if (llvm::isa<GenerateOp>(owner)) {
        auto domain = resolveGeneratedDomain(owner);
        if (failed(domain) || domain->induction != value)
          return failure();
        return DigitalIterationIndex{owner, *domain, makeLiteralIntegerExpression(0),
                                     domain->attainedInductionBounds};
      }
      auto source = digitalRegionCaptureSource(argument);
      if (failed(source) || !*source)
        return failure();
      return resolve(*source, owner, depth + 1);
    }
    Operation *operation = value.getDefiningOp();
    if (!llvm::isa_and_nonnull<ConstExprOp>(operation) || operation->getNumOperands() != 2)
      return failure();
    auto attr = operation->getAttrOfType<StringAttr>("operator_name");
    StringRef opcode = attr ? attr.getValue() : StringRef();
    if (opcode != "add" && opcode != "sub")
      return failure();
    auto affine = resolve(operation->getOperand(0), operation, depth + 1);
    auto offset = resolveStructuralIntegerExpression(operation->getOperand(1), operation);
    if (failed(affine) || failed(offset) || !offset->expression ||
        !invariant(*offset->expression)) {
      if (opcode != "add")
        return failure();
      affine = resolve(operation->getOperand(1), operation, depth + 1);
      offset = resolveStructuralIntegerExpression(operation->getOperand(0), operation);
    }
    if (failed(affine) || failed(offset) || !offset->expression || !invariant(*offset->expression))
      return failure();
    auto composed = composeIntegerExpression(opcode, {affine->offset, *offset->expression});
    if (failed(composed))
      return failure();
    auto lower = composeIntegerExpression("add", {affine->domain.lower, *composed});
    auto upper = composeIntegerExpression("add", {affine->domain.upperExclusive, *composed});
    if (failed(lower) || failed(upper))
      return failure();
    affine->offset = *composed;
    auto checked = resolveStructuralIntegerExpression(value, use);
    if (failed(checked))
      return failure();
    affine->attainedBounds =
        checked->expression ? std::optional<ParameterIntegerBounds>(checked->expression->bounds)
                            : std::nullopt;
    return *affine;
  }
  unsigned work = 0;
  llvm::DenseMap<Value, DigitalIterationIndex> memo;
};
} // namespace

FailureOr<DigitalIterationIndex> resolveStaticShapeIterationIndex(Value value, Operation *use) {
  if (auto loop = resolveDigitalIterationIndex(value, use); succeeded(loop))
    return loop;
  // Validate the complete raw Integer DAG before extracting its affine map.
  if (failed(resolveStructuralIntegerExpression(value, use)))
    return failure();
  return IterationMapQuery().resolve(value, use);
}

ShapeRegionProofStatus resolveStaticShapeRegion(const StaticShapeProjection &projection,
                                                Operation *use, StaticShapeRegion &result,
                                                ShapeRegionProofLimits limits) {
  RegionQuery query(use, limits);
  return deriveRegion(projection, query, result, false, std::nullopt, true);
}

namespace {
// Private certificate for a successful same-query deriveRegion(..., true,
// std::nullopt, true) result. It is not an admission path for supplied images.
RegionStatus checkIterationImageCompleteness(const StaticShapeRegion &candidate,
                                             RegionQuery &query) {
  if (!candidate.iteration)
    return RegionStatus::Unsupported;
  // Injectivity is over every repeated occurrence between this actual use and
  // the root's declaration, not merely each selector considered separately.
  // A root declared in a family is already fresh in its enclosing occurrences.
  Operation *rootScope = candidate.root.getDefiningOp()->getParentOp();
  for (Operation *scope = query.use->getParentOp(); scope && scope != rootScope;
       scope = scope->getParentOp()) {
    if (!query.spend(candidate.iterationCount() + 1))
      return query.failedStatus();
    if (!isa<GenerateOp>(scope) && !isBoundedDigitalLoop(scope))
      continue;
    auto domain = isa<GenerateOp>(scope) ? resolveGeneratedDomain(scope)
                                         : resolveBoundedIterationDomain(scope);
    if (failed(domain))
      return RegionStatus::Invalid;
    bool represented = false;
    for (unsigned i = 0; i != candidate.iterationCount(); ++i)
      represented |= candidate.iterationAt(i).loop == scope;
    if (domain->maximumTripCount > 1 && !represented)
      return query.failedStatus();
  }
  return RegionStatus::Proved;
}
RegionStatus deriveCheckedIterationImage(const StaticShapeProjection &projection,
                                         RegionQuery &query, StaticShapeRegion &result) {
  StaticShapeRegion candidate;
  auto status = deriveRegion(projection, query, candidate, true, std::nullopt, true);
  if (status != RegionStatus::Proved)
    return status;
  status = checkIterationImageCompleteness(candidate, query);
  if (status != RegionStatus::Proved)
    return status;
  result = std::move(candidate);
  return RegionStatus::Proved;
}

} // namespace
ShapeRegionProofStatus resolveStaticShapeIterationImage(const StaticShapeProjection &projection,
                                                        Operation *use, StaticShapeRegion &result,
                                                        ShapeRegionProofLimits limits) {
  RegionQuery query(use, limits);
  return deriveCheckedIterationImage(projection, query, result);
}

ShapeRegionProofStatus proveStaticShapeSameOccurrence(const StaticShapeProjection &left,
                                                      const StaticShapeProjection &right,
                                                      Operation *use,
                                                      ShapeRegionProofLimits limits) {
  // Direct template storage is intrinsically local to one actual occurrence.
  // This proof does not apply to module storage or selected external aliases.
  Operation *root = left.root ? left.root.getDefiningOp() : nullptr;
  Operation *family = resolveGeneratedLocalRootFamily(root);
  if (family && left.root == right.root && family->isProperAncestor(use) &&
      left.projections.empty() && right.projections.empty() &&
      succeeded(resolveStaticShapeProjection(left.root, use)))
    return ShapeRegionProofStatus::Proved;
  RegionQuery query(use, limits);
  StaticShapeRegion a, b;
  auto first = deriveRegion(left, query, a, true, std::nullopt, true);
  if (first != RegionStatus::Proved)
    return first;
  auto second = deriveRegion(right, query, b, true, std::nullopt, true);
  if (second != RegionStatus::Proved)
    return second;
  if (!a.iteration || !b.iteration || !sameImage(a, b, query) || !query.spend(a.iterationCount()))
    return query.failedStatus();
  for (unsigned i = 0; i != a.iterationCount(); ++i)
    if (a.iterationAt(i).loop != b.iterationAt(i).loop ||
        !query.equal(a.iterationAt(i).offset, b.iterationAt(i).offset))
      return query.failedStatus();
  return query.status;
}

FailureOr<int64_t> compareStaticShapeIterationPositions(const StaticShapeProjection &read,
                                                        const StaticShapeProjection &write,
                                                        Operation *use,
                                                        ShapeRegionProofLimits limits) {
  RegionQuery query(use, limits);
  StaticShapeRegion a, b;
  if (deriveRegion(read, query, a, true, std::nullopt, true) != RegionStatus::Proved ||
      deriveRegion(write, query, b, true, std::nullopt, true) != RegionStatus::Proved ||
      !a.iteration || !b.iteration || !isBoundedDigitalLoop(a.iteration->loop) ||
      a.iteration->loop != b.iteration->loop || a.iteration->stride != b.iteration->stride ||
      !sameInvariantAxes(a, b, query))
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
  auto dimensions = query.dimensions(base.root);
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

namespace {
RegionStatus specializeRegion(StaticShapeRegion &region, ArrayRef<GeneratedSelection> selections,
                              RegionQuery &query) {
  if (selections.empty())
    return RegionStatus::Proved;
  SmallVector<std::pair<Value, IntegerExpression>, 4> substitutions;
  for (const auto &selection : selections) {
    if (!query.spend() || !isa_and_nonnull<GenerateOp>(selection.family) ||
        selection.family->getNumRegions() != 1 || !selection.family->getRegion(0).hasOneBlock() ||
        !selection.coordinate.root)
      return query.status == RegionStatus::ResourceLimit ? query.status : RegionStatus::Invalid;
    substitutions.emplace_back(selection.family->getRegion(0).front().getArgument(0),
                               selection.coordinate);
  }
  auto substitute = [&](IntegerExpression &expression) {
    if (!expression.root)
      return true;
    if (!query.spend())
      return false;
    auto result = substituteIntegerExpression(expression, substitutions);
    if (failed(result))
      return false;
    expression = *result;
    return true;
  };
  SmallVector<StaticShapeIterationImage, 2> retained;
  bool selectedMap = false;
  for (unsigned i = 0; i != region.iterationCount(); ++i) {
    auto map = region.iterationAt(i);
    auto selection =
        llvm::find_if(selections, [&](const auto &entry) { return entry.family == map.loop; });
    if (selection == selections.end()) {
      retained.push_back(std::move(map));
      continue;
    }
    RegionAxis *axis = map.varyingAxis ? &region.axes[*map.varyingAxis] : &region.interval;
    auto displacement = query.apply("sub", selection->coordinate, map.domain.lower);
    // A normalized physical image retains the original nonunit progression.
    // Recover its occurrence rank with exact residue and round-trip proof,
    // then advance by physical stride. Ordinary raw images retain their path.
    if (displacement && !query.equal(map.domain.step, makeLiteralIntegerExpression(map.stride))) {
      auto step = RegionQuery::literal(map.domain.step);
      if (!step || *step <= 0 || map.stride <= 0)
        return query.failedStatus();
      auto rank = query.apply("div", *displacement, map.domain.step);
      auto residue = query.apply("mod", *displacement, map.domain.step);
      auto recoveredDelta = rank ? query.apply("mul", *rank, map.domain.step) : std::nullopt;
      auto recovered = recoveredDelta
                           ? query.apply("add", map.domain.lower, *recoveredDelta)
                           : std::nullopt;
      if (!rank || !residue || !recovered || !query.equal(*residue, query.zero) ||
          !query.equal(*recovered, selection->coordinate) ||
          !query.less(query.zero, *rank, true) || !query.less(*rank, map.tripCount))
        return query.failedStatus();
      displacement = query.apply("mul", *rank, makeLiteralIntegerExpression(map.stride));
    }
    auto scaled =
        displacement ? query.apply("mul", *displacement, map.occurrenceWidth) : std::nullopt;
    auto lower = scaled ? query.apply("add", axis->lower, *scaled) : std::nullopt;
    auto upper = lower ? query.apply("add", *lower, map.occurrenceWidth) : std::nullopt;
    if (!lower || !upper)
      return query.failedStatus();
    *axis = {*lower, *upper};
    selectedMap = true;
  }
  region.iteration.reset();
  region.outerIterations.clear();
  if (!retained.empty()) {
    region.iteration = retained.pop_back_val();
    region.outerIterations = std::move(retained);
  }
  for (auto &axis : region.axes)
    if (!substitute(axis.lower) || !substitute(axis.upperExclusive))
      return query.failedStatus();
  if (!substitute(region.interval.lower) || !substitute(region.interval.upperExclusive))
    return query.failedStatus();
  if (selectedMap && region.kind == StaticShapeRegion::Kind::Whole)
    region.kind = region.interval.lower.root ? StaticShapeRegion::Kind::Interval
                                             : StaticShapeRegion::Kind::Box;
  return query.status;
}
RegionStatus specializePhysicalRoot(PhysicalRoot &root, ArrayRef<GeneratedSelection> selections,
                                    RegionQuery &query) {
  for (const auto &selection : selections) {
    if (!query.spend())
      return query.status;
    if (!selection.family || !root.owner || !selection.family->isProperAncestor(root.owner))
      continue;
    auto known = llvm::find_if(root.selections,
                               [&](const auto &entry) { return entry.family == selection.family; });
    if (known == root.selections.end())
      root.selections.push_back(selection);
    else
      *known = selection;
  }
  // Inspector prefixes and query contexts both retain actual ancestry order.
  llvm::sort(root.selections, [](const auto &a, const auto &b) {
    return a.family != b.family && a.family->isProperAncestor(b.family);
  });
  return query.status;
}
} // namespace

StaticShapeOverlap compareStaticShapeContextualProjections(
    const StaticShapeProjection &left, ArrayRef<GeneratedSelection> leftContext,
    const StaticShapeProjection &right, ArrayRef<GeneratedSelection> rightContext, Operation *use,
    ShapeRegionProofLimits limits) {
  RegionQuery query(use, limits);
  auto unknown = [&](RegionStatus status) {
    return StaticShapeOverlap{status, ShapeRegionOverlap::Unknown};
  };
  auto leftRoot = physicalRoot(left.root, query), rightRoot = physicalRoot(right.root, query);
  if (failed(leftRoot) || failed(rightRoot))
    return unknown(query.status == RegionStatus::ResourceLimit ? query.status
                                                               : RegionStatus::Invalid);
  StaticShapeRegion a, b;
  auto status = deriveRegion(left, query, a, true, std::nullopt, true);
  if (status != RegionStatus::Proved)
    return unknown(status);
  status = deriveRegion(right, query, b, true, std::nullopt, true);
  if (status != RegionStatus::Proved)
    return unknown(status);
  if (a.kind == StaticShapeRegion::Kind::Empty || b.kind == StaticShapeRegion::Kind::Empty)
    return {RegionStatus::Proved, ShapeRegionOverlap::Disjoint};
  status = specializeRegion(a, leftContext, query);
  if (status != RegionStatus::Proved)
    return unknown(status);
  status = specializeRegion(b, rightContext, query);
  if (status != RegionStatus::Proved)
    return unknown(status);
  if (specializePhysicalRoot(*leftRoot, leftContext, query) != RegionStatus::Proved ||
      specializePhysicalRoot(*rightRoot, rightContext, query) != RegionStatus::Proved)
    return unknown(query.failedStatus());
  auto physical = comparePhysicalRoots(*leftRoot, *rightRoot, query);
  if (physical == ShapeRegionOverlap::Disjoint)
    return {query.status, *physical};
  if (!physical)
    return unknown(query.failedStatus());
  if (a.kind == StaticShapeRegion::Kind::Whole && b.kind == StaticShapeRegion::Kind::Whole)
    return {query.status, ShapeRegionOverlap::Overlapping};
  if (a.root != b.root && a.root.getType() != b.root.getType())
    return unknown(RegionStatus::Unsupported);
  if (auto strided = compareStridedImages(a, b, query))
    return {query.status != RegionStatus::Proved      ? query.status
            : *strided == ShapeRegionOverlap::Unknown ? RegionStatus::Unsupported
                                                      : RegionStatus::Proved,
            *strided};
  auto dimensions = query.dimensions(a.root);
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

StaticShapeOverlap compareStaticShapeProjections(const StaticShapeProjection &left,
                                                 const StaticShapeProjection &right, Operation *use,
                                                 ShapeRegionProofLimits limits) {
  return compareStaticShapeContextualProjections(left, {}, right, {}, use, limits);
}

ShapeRegionProofStatus proveStaticShapeProjectionIdentity(const StaticShapeProjection &left,
                                                          const StaticShapeProjection &right,
                                                          Operation *use,
                                                          ShapeRegionProofLimits limits) {
  RegionQuery query(use, limits);
  auto aRoot = physicalRoot(left.root, query), bRoot = physicalRoot(right.root, query);
  if (failed(aRoot) || failed(bRoot))
    return query.status == RegionStatus::ResourceLimit ? query.status : RegionStatus::Invalid;
  StaticShapeRegion a, b;
  auto status = deriveRegion(left, query, a, true, std::nullopt, true);
  if (status != RegionStatus::Proved)
    return status;
  status = deriveRegion(right, query, b, true, std::nullopt, true);
  if (status != RegionStatus::Proved)
    return status;
  if (aRoot->owner != bRoot->owner || aRoot->member != bRoot->member ||
      aRoot->path != bRoot->path || aRoot->selections.size() != bRoot->selections.size() ||
      a.root.getType() != b.root.getType())
    return RegionStatus::Unsupported;
  for (auto [x, y] : llvm::zip(aRoot->selections, bRoot->selections))
    if (x.family != y.family || !query.equal(x.coordinate, y.coordinate))
      return query.failedStatus();
  // Rebase only these query-local regions after proving exact physical identity.
  // Their original SSA chains above remain the sole verification evidence.
  b.root = a.root;
  if (a.iteration || b.iteration)
    return sameImage(a, b, query) ? query.status : query.failedStatus();
  auto dimensions = query.dimensions(a.root);
  if (!dimensions)
    return query.failedStatus();
  auto first = regionBox(a, query, *dimensions), second = regionBox(b, query, *dimensions);
  if (first && second) {
    if (first->size() != second->size())
      return RegionStatus::Unsupported;
    for (auto [x, y] : llvm::zip(*first, *second))
      if (!query.equal(x.lower, y.lower) || !query.equal(x.upperExclusive, y.upperExclusive))
        return query.failedStatus();
    return query.status;
  }
  auto x = regionInterval(a, query, *dimensions), y = regionInterval(b, query, *dimensions);
  return x && y && query.equal(x->lower, y->lower) &&
                 query.equal(x->upperExclusive, y->upperExclusive)
             ? query.status
             : query.failedStatus();
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
  auto dimensions = query.dimensions(root);
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
  auto readRoot = physicalRoot(read.root, query);
  if (failed(readRoot))
    return query.status == RegionStatus::ResourceLimit ? query.status : RegionStatus::Invalid;
  StaticShapeRegion selected;
  auto status = deriveRegion(read, query, selected, true, std::nullopt, true);
  if (status != RegionStatus::Proved)
    return status;
  SmallVector<StaticShapeRegion, 4> regions;
  for (const auto &write : writes) {
    auto writeRoot = physicalRoot(write.root, query);
    if (failed(writeRoot))
      return query.status == RegionStatus::ResourceLimit ? query.status : RegionStatus::Invalid;
    StaticShapeRegion region;
    status = deriveRegion(write, query, region, true, std::nullopt, true);
    if (status != RegionStatus::Proved)
      return status;
    auto relation = comparePhysicalRoots(*readRoot, *writeRoot, query);
    if (relation == ShapeRegionOverlap::Disjoint)
      continue;
    if (!relation || (readRoot->selections.empty() && !writeRoot->selections.empty()))
      return query.failedStatus();
    if (region.root.getType() != read.root.getType() &&
        region.kind != StaticShapeRegion::Kind::Whole)
      return RegionStatus::Unsupported;
    // Exact equal selected contexts, or an authored whole family covering the
    // checked selected occurrence. Never widen a selected writer to its template.
    region.root = read.root;
    regions.push_back(std::move(region));
  }
  if (selected.kind == StaticShapeRegion::Kind::Empty)
    return RegionStatus::Proved;
  if (stridedImage(selected)) {
    // An exact mapped family read can be covered without filling its holes.
    for (unsigned i = 0; i != regions.size(); ++i) {
      if (!sameImage(selected, regions[i], query))
        continue;
      bool disjoint = true;
      for (unsigned j = 0; j != regions.size(); ++j) {
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
  status = normalizeSingletonCoverage(selected, query);
  if (status != RegionStatus::Proved)
    return status;
  const bool stridedRead = stridedImage(selected);
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
// Narrow inverse for a checked scalar one-loop image, not a general region
// ordinal conversion. The caller retains the exact original relation context.
StaticShapeScalarLocation locateStridedScalarImage(const StaticShapeRegion &region,
                                                   const StaticShapePoint &point,
                                                   RegionQuery &query) {
  auto unknown = [&]() {
    return StaticShapeScalarLocation{query.failedStatus(), ShapeRegionMembership::Unknown,
                                     std::nullopt};
  };
  if (!query.spend() || region.root != point.root || region.iterationCount() != 1 ||
      !query.equal(region.iteration->occurrenceWidth, query.one))
    return unknown();
  auto dimensions = query.dimensions(region.root);
  auto axes = imageAxes(region, query);
  if (!dimensions || !axes || imageAxis(region) >= axes->size())
    return unknown();
  llvm::SmallVector<IntegerExpression, 4> coordinates;
  if (!region.iteration->varyingAxis) {
    auto flat = pointFlat(point, query, *dimensions);
    if (!flat)
      return unknown();
    coordinates.push_back(*flat);
  } else {
    auto values = pointCoordinates(point, query, *dimensions);
    if (!values)
      return unknown();
    coordinates = std::move(*values);
  }
  if (coordinates.size() != axes->size())
    return unknown();
  for (unsigned axis = 0; axis != axes->size(); ++axis) {
    if (!query.spend())
      return unknown();
    if (axis == imageAxis(region))
      continue;
    auto width = query.apply("sub", (*axes)[axis].upperExclusive, (*axes)[axis].lower);
    if (!width || !query.equal(*width, query.one) ||
        !query.equal(coordinates[axis], (*axes)[axis].lower))
      return unknown();
  }
  auto delta =
      constantDifference(coordinates[imageAxis(region)], (*axes)[imageAxis(region)].lower, query);
  if (!delta)
    return unknown();
  auto location =
      locateScalarProgression(*delta, region.iteration->stride, region.iteration->tripCount.bounds);
  if (location.first == ShapeRegionMembership::Unknown)
    return unknown();
  return {RegionStatus::Proved, location.first,
          location.second
              ? std::optional<IntegerExpression>(makeLiteralIntegerExpression(*location.second))
              : std::nullopt};
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
  if (stridedImage(region))
    return reject();
  auto dimensions = query.dimensions(region.root);
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
  auto dimensions = query.dimensions(region.root);
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

// This additional occurrence profile is deliberately coordinate-preserving.
// General Cartesian images remain owned by deriveCheckedIterationImage; this
// consumer only admits the two original Module-backed rectangular axes.
static ShapeRegionProofStatus checkTwoAxisOccurrenceImage(const StaticShapeRegion &region,
                                                          RegionQuery &query) {
  if (!query.spend())
    return query.status;
  Operation *root = region.root ? region.root.getDefiningOp() : nullptr;
  if (!root || root->getParentOp() != query.module || region.iterationCount() != 2 ||
      region.axes.size() != 2)
    return query.failedStatus();
  auto dimensions = query.dimensions(region.root);
  auto shape = physicalShape(region.root.getType());
  if (!dimensions || dimensions->size() != 2 || !shape || shape->dimensions.size() != 2)
    return query.failedStatus();
  Operation *outer = region.iterationAt(0).loop, *inner = region.iterationAt(1).loop;
  if (!isa_and_nonnull<GenerateOp>(outer) || !isa_and_nonnull<GenerateOp>(inner) ||
      outer->getParentOp() != query.module || inner->getParentOp() != outer ||
      query.use->getParentOp() != inner)
    return query.failedStatus();
  auto literalOperand = [](Value value) -> std::optional<int64_t> {
    auto op = value.getDefiningOp<ConstLiteralOp>();
    auto attr = op ? op->getAttrOfType<IntegerAttr>("value") : IntegerAttr();
    if (!attr || !value.getType().isInteger(64) || !attr.getValue().isSignedIntN(64))
      return std::nullopt;
    return attr.getValue().getSExtValue();
  };
  for (unsigned axis = 0; axis != 2; ++axis) {
    if (!query.spend())
      return query.status;
    const auto &map = region.iterationAt(axis);
    Operation *family = map.loop;
    if (family->getNumOperands() != 3 || family->getNumRegions() != 1 ||
        !family->getRegion(0).hasOneBlock() ||
        family->getRegion(0).front().getNumArguments() != 1 ||
        map.domain.module != query.module ||
        map.domain.induction != family->getRegion(0).front().getArgument(0) ||
        literalOperand(family->getOperand(0)) != 0 ||
        literalOperand(family->getOperand(2)) != 1 || map.varyingAxis != axis ||
        map.stride != 1 || map.empty || !map.domain.attainedInductionBounds ||
        !query.equal(map.domain.lower, query.zero) ||
        !query.equal(map.domain.step, query.one) || !query.equal(map.offset, query.zero) ||
        !query.equal(map.occurrenceWidth, query.one) ||
        !query.equal(map.tripCount, map.domain.upperExclusive) ||
        !query.equal(region.axes[axis].lower, query.zero) ||
        !query.equal(region.axes[axis].upperExclusive, map.domain.upperExclusive) ||
        !query.equal((*dimensions)[axis], map.domain.upperExclusive))
      return query.failedStatus();
    Operation *upper = family->getOperand(1).getDefiningOp();
    const auto &bound = map.domain.upperExclusive;
    if (!upper || !precedesLexically(upper, family) || bound.bounds.lower <= 0)
      return query.failedStatus();
    if (auto literal = literalOperand(family->getOperand(1))) {
      if (RegionQuery::literal(bound) != literal ||
          shape->dimensions[axis] != std::to_string(*literal))
        return query.failedStatus();
    } else {
      auto reference = upper->getAttrOfType<FlatSymbolRefAttr>("parameter");
      if (!isa<ConstParameterRefOp>(upper) || upper->getParentOp() != query.module ||
          !reference || !bound.root ||
          bound.root->kind != InstantiatedIntegerNode::Kind::ParentParameter ||
          !isa_and_nonnull<ParameterOp>(bound.root->parameter) ||
          bound.root->parameter->getParentOp() != query.module ||
          shape->dimensions[axis] != reference.getValue() ||
          SymbolTable::lookupSymbolIn(query.module, reference) != bound.root->parameter)
        return query.failedStatus();
    }
  }
  return query.status;
}

static ShapeRegionProofStatus mapTwoAxisOccurrencePoint(
    const StaticShapePoint &point, StaticShapeRegion &source, StaticShapeRegion &destination,
    bool invariantDestination, RegionQuery &query, StaticShapePoint &result) {
  if (point.root != source.root)
    return RegionStatus::Invalid;
  auto status = checkTwoAxisOccurrenceImage(source, query);
  if (status != RegionStatus::Proved)
    return status;
  if (point.coordinates.size() != 2 ||
      llvm::any_of(point.coordinates, [](const auto &coordinate) {
        return !RegionQuery::literal(coordinate);
      }))
    return query.failedStatus();
  auto dimensions = query.dimensions(source.root);
  StaticShapePoint coordinatesOnly = point;
  coordinatesOnly.flatOrdinal.reset();
  auto original = dimensions ? pointFlat(coordinatesOnly, query, *dimensions) : std::nullopt;
  if (!original)
    return query.failedStatus();
  // The public point can carry both representations. Neither takes precedence
  // over a contradictory other representation at this inverse trust boundary.
  if (point.flatOrdinal && !query.equal(*point.flatOrdinal, *original))
    return query.status == RegionStatus::ResourceLimit ? query.status : RegionStatus::Invalid;
  SmallVector<GeneratedSelection, 2> occurrences;
  for (unsigned axis = 0; axis != 2; ++axis) {
    const auto &map = source.iterationAt(axis);
    const auto &coordinate = point.coordinates[axis];
    if (!query.less(query.zero, coordinate, true) ||
        !query.less(coordinate, map.domain.upperExclusive))
      return query.failedStatus();
    occurrences.push_back({map.loop, Value(), coordinate});
  }
  if (invariantDestination) {
    Operation *root = destination.root ? destination.root.getDefiningOp() : nullptr;
    if (!root || root->getParentOp() != query.module || destination.iterationCount() ||
        isa<ShapedType>(destination.root.getType()))
      return query.failedStatus();
  } else {
    status = checkTwoAxisOccurrenceImage(destination, query);
    if (status != RegionStatus::Proved)
      return status;
    for (unsigned axis = 0; axis != 2; ++axis)
      if (!query.spend() || source.iterationAt(axis).loop != destination.iterationAt(axis).loop)
        return query.failedStatus();
  }
  status = specializeRegion(source, occurrences, query);
  if (status != RegionStatus::Proved)
    return status;
  StaticShapePoint roundTrip;
  status = pointFromRegion(source, query, roundTrip);
  if (status != RegionStatus::Proved)
    return status;
  if (roundTrip.coordinates.size() != 2 || !roundTrip.flatOrdinal ||
      !query.equal(*original, *roundTrip.flatOrdinal))
    return query.failedStatus();
  for (unsigned axis = 0; axis != 2; ++axis)
    if (!query.equal(point.coordinates[axis], roundTrip.coordinates[axis]))
      return query.failedStatus();
  if (!invariantDestination) {
    status = specializeRegion(destination, occurrences, query);
    if (status != RegionStatus::Proved)
      return status;
  }
  StaticShapePoint mapped;
  status = pointFromRegion(destination, query, mapped);
  if (status != RegionStatus::Proved)
    return status;
  if (invariantDestination &&
      (!mapped.flatOrdinal || !query.invariant(*mapped.flatOrdinal) ||
       llvm::any_of(mapped.coordinates, [&](const auto &value) { return !query.invariant(value); })))
    return query.failedStatus();
  result = std::move(mapped);
  return RegionStatus::Proved;
}

static ShapeRegionProofStatus
mapSameOccurrencePoint(const StaticShapeProjection &source, const StaticShapePoint &point,
                       const StaticShapeProjection &destination, Operation *actualRelationUse,
                       StaticShapePoint &result, ShapeRegionProofLimits limits,
                       bool allowInvariantDestination) {
  if (!actualRelationUse || !source.root || !destination.root || !point.root)
    return RegionStatus::Invalid;
  auto selected = [](const StaticShapeProjection &projection) {
    return projection.projections.empty() ? projection.root
                                          : projection.projections.back()->getResult(0);
  };
  Value from = selected(source), to = selected(destination);
  if (!llvm::is_contained(actualRelationUse->getOperands(), from) ||
      !llvm::is_contained(actualRelationUse->getOperands(), to))
    return RegionStatus::Invalid;
  if (isa<ShapedType>(from.getType()) || isa<ShapedType>(to.getType()))
    return RegionStatus::Unsupported;
  auto checkedSource = resolveStaticShapeProjection(from, actualRelationUse);
  auto checkedDestination = resolveStaticShapeProjection(to, actualRelationUse);
  if (failed(checkedSource) || failed(checkedDestination) || checkedSource->root != source.root ||
      checkedSource->projections != source.projections ||
      checkedDestination->root != destination.root ||
      checkedDestination->projections != destination.projections)
    return RegionStatus::Invalid;
  RegionQuery query(actualRelationUse, limits);
  StaticShapeRegion a, b;
  auto status = deriveCheckedIterationImage(source, query, a);
  if (status != RegionStatus::Proved)
    return status;
  // The optional one-way case has a module-owned, occurrence-invariant
  // scalar destination. Never attempt to invert it into a generated lane.
  bool invariantDestination = false;
  if (allowInvariantDestination) {
    status = deriveRegion(destination, query, b, true, std::nullopt, true);
    if (status != RegionStatus::Proved)
      return status;
    invariantDestination = b.iterationCount() == 0;
  }
  if (a.iterationCount() == 2) {
    if (!invariantDestination) {
      // The enhanced route already derived this exact candidate with the same
      // query and flags. Reuse only that derivation, not its completeness proof.
      status = allowInvariantDestination ? checkIterationImageCompleteness(b, query)
                                         : deriveCheckedIterationImage(destination, query, b);
      if (status != RegionStatus::Proved)
        return status;
    }
    return mapTwoAxisOccurrencePoint(point, a, b, invariantDestination, query, result);
  }
  if (invariantDestination) {
    Operation *definition = destination.root.getDefiningOp();
    Operation *scope = actualRelationUse->getParentOp();
    if (!definition || !scope || !isa<GenerateOp>(scope) || !isa<ModuleOp>(scope->getParentOp()) ||
        definition->getParentOp() != scope->getParentOp() || a.iterationCount() != 1 ||
        a.iteration->loop != scope || a.iteration->stride <= 0 ||
        !query.equal(a.iteration->occurrenceWidth, query.one))
      return query.failedStatus();
    StaticShapePoint anchor;
    status = pointFromRegion(b, query, anchor);
    if (status != RegionStatus::Proved)
      return status;
    for (const auto &coordinate : anchor.coordinates)
      if (!query.invariant(coordinate))
        return query.failedStatus();
    if (!anchor.flatOrdinal || !query.invariant(*anchor.flatOrdinal))
      return query.failedStatus();
  } else {
    status = deriveCheckedIterationImage(destination, query, b);
    if (status != RegionStatus::Proved)
      return status;
    if (a.iterationCount() != 1 || b.iterationCount() != 1 ||
        a.iteration->loop != b.iteration->loop ||
        a.iteration->loop != actualRelationUse->getParentOp() ||
        !isa<GenerateOp>(a.iteration->loop) || !isa<ModuleOp>(a.iteration->loop->getParentOp()) ||
        a.iteration->stride <= 0 || a.iteration->stride != b.iteration->stride ||
        a.iteration->varyingAxis != b.iteration->varyingAxis ||
        !query.equal(a.iteration->domain.step, b.iteration->domain.step) ||
        !query.equal(a.iteration->occurrenceWidth, query.one) ||
        !query.equal(b.iteration->occurrenceWidth, query.one) ||
        !query.equal(a.iteration->offset, b.iteration->offset))
      return query.failedStatus();
  }
  auto location =
      stridedImage(a) ? locateStridedScalarImage(a, point, query) : locate(a, point, query);
  if (location.status != RegionStatus::Proved)
    return location.status;
  if (location.membership != ShapeRegionMembership::Inside || !location.ordinal)
    return RegionStatus::Unsupported;
  // The checked inverse supplies the normalized occurrence rank, not a guessed
  // induction token. Canonical arithmetic reconstructs the original coordinate.
  auto displacement = query.apply("mul", *location.ordinal, a.iteration->domain.step);
  auto coordinate =
      displacement ? query.apply("add", a.iteration->domain.lower, *displacement) : std::nullopt;
  if (!coordinate || !query.less(a.iteration->domain.lower, *coordinate, true) ||
      !query.less(*coordinate, a.iteration->domain.upperExclusive))
    return query.failedStatus();
  GeneratedSelection occurrence{a.iteration->loop, Value(), *coordinate};
  status = specializeRegion(a, {occurrence}, query);
  if (status != RegionStatus::Proved)
    return status;
  StaticShapePoint roundTrip;
  status = pointFromRegion(a, query, roundTrip);
  if (status != RegionStatus::Proved)
    return status;
  auto dimensions = query.dimensions(source.root);
  auto original = dimensions ? pointFlat(point, query, *dimensions) : std::nullopt;
  auto recovered = dimensions ? pointFlat(roundTrip, query, *dimensions) : std::nullopt;
  if (!original || !recovered || !query.equal(*original, *recovered))
    return query.failedStatus();
  if (!invariantDestination) {
    status = specializeRegion(b, {occurrence}, query);
    if (status != RegionStatus::Proved)
      return status;
  }
  StaticShapePoint mapped;
  status = pointFromRegion(b, query, mapped);
  if (status == RegionStatus::Proved)
    result = std::move(mapped);
  return status;
}

ShapeRegionProofStatus mapStaticShapeSameOccurrencePoint(const StaticShapeProjection &source,
                                                         const StaticShapePoint &point,
                                                         const StaticShapeProjection &destination,
                                                         Operation *actualRelationUse,
                                                         StaticShapePoint &result,
                                                         ShapeRegionProofLimits limits) {
  return mapSameOccurrencePoint(source, point, destination, actualRelationUse, result, limits,
                                false);
}

ShapeRegionProofStatus mapStaticShapeSameOccurrenceOrInvariantPoint(
    const StaticShapeProjection &source, const StaticShapePoint &point,
    const StaticShapeProjection &destination, Operation *actualRelationUse,
    StaticShapePoint &result, ShapeRegionProofLimits limits) {
  return mapSameOccurrencePoint(source, point, destination, actualRelationUse, result, limits,
                                true);
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
  auto dimensions = query.dimensions(region.root);
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
