#include "nodal/Dialect/Nodal/ShapeModel.h"

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
Type shapeElement(Type type) {
  if (auto shaped = llvm::dyn_cast<ShapedType>(type))
    return shaped.getElementType();
  return type;
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
  return kind == StaticShapeIndexIdentity::Parameter   ? "p:"
         : kind == StaticShapeIndexIdentity::Induction ? "i:"
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
  auto shaped = llvm::dyn_cast<ShapedType>(type);
  if (!shaped)
    return std::string("scalar");
  llvm::SmallVector<llvm::StringRef> tokens;
  shaped.getDimensions().split(tokens, ',', -1, true);
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
  auto shaped = llvm::dyn_cast<ShapedType>(type);
  if (!shaped)
    return first == 0 ? std::optional<StaticProduct>(result) : std::nullopt;
  llvm::SmallVector<llvm::StringRef> dimensions;
  shaped.getDimensions().split(dimensions, ',', -1, true);
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
  auto shaped = llvm::dyn_cast<ShapedType>(type);
  if (!shaped)
    return first == 0 ? std::optional<ParameterIntegerBounds>(result) : std::nullopt;
  llvm::SmallVector<llvm::StringRef> dimensions;
  shaped.getDimensions().split(dimensions, ',', -1, true);
  if (first > dimensions.size())
    return std::nullopt;
  for (auto token : llvm::ArrayRef<llvm::StringRef>(dimensions).drop_front(first)) {
    auto dimension = resolveStaticShapeDimension(module, token.trim());
    if (failed(dimension) ||
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
  if (auto shaped = llvm::dyn_cast<ShapedType>(type)) {
    llvm::SmallVector<llvm::StringRef> tokens;
    shaped.getDimensions().split(tokens, ',', -1, true);
    for (auto token : tokens) {
      auto dimension = resolveStaticShapeDimension(module, token.trim());
      if (failed(dimension) || dimension->bounds.lower != dimension->bounds.upper)
        return std::nullopt;
      result.push_back(dimension->bounds.lower);
    }
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
  auto cardinality = inferStaticShapeMaximumCardinality(module, rootType.getDimensions());
  if (failed(cardinality))
    return;
  if (!result.rootFlatBounds)
    result.rootFlatBounds = ParameterIntegerBounds{0, *cardinality - 1};
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
  llvm::SmallVector<llvm::StringRef> dimensions;
  rootType.getDimensions().split(dimensions, ',', -1, true);
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
  llvm::SmallPtrSet<Operation *, 16> visited;
  Value current = reference;
  while (Operation *operation = current.getDefiningOp()) {
    if (!visited.insert(operation).second || visited.size() > 512 ||
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
  if (auto shaped = llvm::dyn_cast<ShapedType>(result.root.getType()))
    if (failed(inferStaticShapeMaximumCardinality(module, shaped.getDimensions())))
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
    if (auto shaped = llvm::dyn_cast<ShapedType>(type)) {
      llvm::SmallVector<llvm::StringRef> tokens;
      shaped.getDimensions().split(tokens, ',', -1, true);
      for (auto token : tokens) {
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
    if (opaque || proof->identity.kind == StaticShapeIndexIdentity::Induction)
      return makeOpaqueIntegerExpression(value, *proof->attainedBounds);
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
                          std::optional<IntegerExpression> logicalOrdinal = std::nullopt) {
  if (!query.module || !projection.root)
    return RegionStatus::Invalid;
  if (!query.spend(projection.projections.size() + 1))
    return query.failedStatus();
  Value selected = projection.projections.empty() ? projection.root
                                                  : projection.projections.back()->getResult(0);
  // Recheck the supplied chain against actual SSA rather than accepting cached
  // whole/contiguous flags or caller-authored coordinate summaries as evidence.
  auto verified = resolveStaticShapeProjection(selected, query.use);
  if (failed(verified) || verified->root != projection.root ||
      verified->projections != projection.projections)
    return RegionStatus::Invalid;
  auto selectedDimensions = query.dimensions(selected.getType());
  if (!selectedDimensions)
    return query.failedStatus();
  RegionBox currentBox = query.full(*selectedDimensions);
  std::optional<RegionAxis> currentInterval;
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
      if (!currentInterval && query.whole(currentBox, *output)) {
        currentBox = query.full(*input);
        continue;
      }
      if (!currentInterval)
        currentInterval = query.interval(currentBox, *output);
      if (!currentInterval)
        return query.failedStatus();
      if (auto mapped = query.box(*currentInterval, *input)) {
        currentBox = std::move(*mapped);
        currentInterval.reset();
      }
      continue;
    }
    if (currentInterval) {
      if (auto converted = query.box(*currentInterval, *output)) {
        currentBox = std::move(*converted);
        currentInterval.reset();
      }
    }
    if (llvm::isa<ShapeIndexOp>(operation)) {
      RegionBox prefix;
      IntegerExpression prefixOffset = query.zero;
      unsigned axis = 0;
      for (Value index : operation->getOperands().drop_front()) {
        auto position = query.selector(index, operation, true);
        auto end = position ? query.apply("add", *position, query.one) : std::nullopt;
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
  if (!physicalSelectors && !logicalOrdinal)
    region.projection = std::move(*verified);
  if (currentInterval) {
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
  if (region.kind == StaticShapeRegion::Kind::Whole)
    return query.full(dimensions);
  if (region.kind == StaticShapeRegion::Kind::Box)
    return region.axes;
  return query.box(region.interval, dimensions);
}
std::optional<RegionAxis> regionInterval(const StaticShapeRegion &region, RegionQuery &query,
                                         llvm::ArrayRef<IntegerExpression> dimensions) {
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
} // namespace

ShapeRegionProofStatus resolveStaticShapeRegion(const StaticShapeProjection &projection,
                                                Operation *use, StaticShapeRegion &result,
                                                ShapeRegionProofLimits limits) {
  RegionQuery query(use, limits);
  return deriveRegion(projection, query, result);
}

ShapeRegionProofStatus verifyStaticShapeRegionPartition(Value root,
                                                        llvm::ArrayRef<StaticShapeRegion> regions,
                                                        Operation *use,
                                                        ShapeRegionProofLimits limits) {
  RegionQuery query(use, limits);
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
  bool allBoxes = true;
  for (const auto &region : regions) {
    if (region.root != root)
      return RegionStatus::Invalid;
    auto box = regionBox(region, query, *dimensions);
    if (!box) {
      allBoxes = false;
      break;
    }
    boxes.push_back(*box);
  }
  if (!allBoxes) {
    boxes.clear();
    // A common ordinal space still handles exact nonrectangular flat images
    // without decomposing them. Preserve coordinate/decomposition fallback.
    bool allIntervals = true;
    std::vector<RegionAxis> intervals;
    for (const auto &region : regions) {
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
      for (const auto &interval : intervals)
        boxes.push_back({interval});
    } else {
      for (const auto &region : regions) {
        if (region.root != root)
          return RegionStatus::Invalid;
        if (auto box = regionBox(region, query, *dimensions)) {
          boxes.push_back(*box);
        } else {
          auto split = splitInterval(region.interval, *dimensions, query);
          if (!split)
            return query.failedStatus();
          for (auto &box : *split)
            boxes.push_back(std::move(box));
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
          !query.less(range.lower, range.upperExclusive) ||
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
        while (position < axisCuts.size() && query.less(axisCuts[position], bound))
          ++position;
        if (position < axisCuts.size() && !query.less(bound, axisCuts[position]))
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
    if (axis == dimensions->size()) {
      if (state.second.size() != 1)
        return RegionStatus::Invalid;
      continue;
    }
    const auto &axisCuts = cuts[axis];
    for (unsigned cut = 1; cut < axisCuts.size(); ++cut) {
      std::vector<unsigned> active;
      for (unsigned index : state.second) {
        const auto &range = boxes[index][axis];
        if (query.less(range.lower, axisCuts[cut - 1], true) &&
            query.less(axisCuts[cut], range.upperExclusive, true))
          active.push_back(index);
      }
      if (query.status != RegionStatus::Proved)
        return query.status;
      if (active.empty())
        return RegionStatus::Invalid;
      pending.emplace_back(axis + 1, std::move(active));
      if (pending.size() > limits.maximumStates)
        return RegionStatus::ResourceLimit;
    }
  }
  return RegionStatus::Proved;
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
  if (region.root != point.root)
    return reject(ShapeRegionMembership::Outside);
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
    auto status = deriveRegion(*region.projection, query, physical, true);
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
