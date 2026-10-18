#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/SmallPtrSet.h"

#include <algorithm>
#include <limits>
#include <map>
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
FailureOr<std::string> indexKey(Operation *module, Value value) {
  auto identity = resolveStaticShapeIndexIdentity(module, value);
  if (failed(identity))
    return failure();
  if (identity->kind == StaticShapeIndexIdentity::Literal)
    return "l:" + std::to_string(identity->literal);
  return (identity->kind == StaticShapeIndexIdentity::Parameter ? "p:" : "e:") +
         pointerKey(identity->root);
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
                     Type inputType, unsigned strideAxis) {
  if (!offset)
    return;
  auto position = inferStaticShapeIndexBounds(module, index);
  auto stride = dimensionProductBounds(module, inputType, strideAxis);
  if (failed(position) || !stride || position->lower < 0 ||
      position->upper > (std::numeric_limits<int64_t>::max() - offset->upper) / stride->upper) {
    offset.reset();
    return;
  }
  offset->lower += position->lower * stride->lower;
  offset->upper += position->upper * stride->upper;
}
std::optional<StaticProduct> indexProduct(Operation *module, Value value) {
  auto identity = resolveStaticShapeIndexIdentity(module, value);
  if (failed(identity))
    return std::nullopt;
  StaticProduct result;
  if (identity->kind == StaticShapeIndexIdentity::Literal)
    result.coefficient = identity->literal;
  else
    result.factors.push_back((identity->kind == StaticShapeIndexIdentity::Parameter ? "p:" : "e:") +
                             pointerKey(identity->root));
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
std::optional<int64_t> literalIndex(Operation *module, Value value) {
  auto identity = resolveStaticShapeIndexIdentity(module, value);
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
        auto literal = literalIndex(module, index);
        if (!literal)
          return std::nullopt;
        prefix.push_back(*literal);
      }
      prefix.append(indices->begin(), indices->end());
      indices = std::move(prefix);
    } else {
      auto axis = projection->getAttrOfType<IntegerAttr>("axis");
      auto lower = literalIndex(module, projection->getOperand(1));
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
        auto term = indexProduct(module, index);
        auto range = inferStaticShapeIndexBounds(module, index);
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
    auto lower = indexProduct(module, projection->getOperand(1));
    auto lowerBounds = inferStaticShapeIndexBounds(module, projection->getOperand(1));
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
        auto key = indexKey(module, index);
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
          auto position = indexProduct(module, index);
          auto stride = dimensionProduct(module, projection->getOperand(0).getType(), ++axis);
          addOffsetBounds(exactOffsetBounds, module, index, projection->getOperand(0).getType(),
                          axis);
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
        auto key = indexKey(module, bound);
        if (failed(key))
          return failure();
        step += ";" + *key;
      }
      step += ')';
      auto lower = literalIndex(module, projection->getOperand(1));
      identityMap = lower && *lower == 0 && *inputKey == *outputKey;
      result.wholeRoot &= identityMap;
      if (linear && !identityMap) {
        auto axis = projection->getAttrOfType<IntegerAttr>("axis").getInt();
        bool leading = axis == 0;
        auto fixed = fixedDimensions(module, projection->getOperand(0).getType());
        if (!leading && fixed)
          leading = llvm::all_of(llvm::ArrayRef<int64_t>(*fixed).take_front(axis),
                                 [](int64_t extent) { return extent == 1; });
        auto lowerProduct = indexProduct(module, projection->getOperand(1));
        auto stride = dimensionProduct(module, projection->getOperand(0).getType(), axis + 1);
        auto term = lowerProduct && stride ? multiplyProduct(*lowerProduct, *stride) : std::nullopt;
        addOffsetBounds(exactOffsetBounds, module, projection->getOperand(1),
                        projection->getOperand(0).getType(), axis + 1);
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
          auto identity = resolveStaticShapeIndexIdentity(module, index);
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

} // namespace nodal
