#include "nodal/Dialect/Nodal/AnalogStorageModel.h"

#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/SmallPtrSet.h"

#include <algorithm>
#include <limits>
#include <map>

using namespace mlir;
namespace nodal {
namespace {
LogicalResult reject(Operation *op, llvm::StringRef message) {
  return op->emitOpError("NODAL-ANALOG-033-019: ") << message;
}
llvm::StringRef text(Operation *op, llvm::StringRef key) {
  auto value = op->getAttrOfType<StringAttr>(key);
  return value ? value.getValue() : llvm::StringRef();
}
bool isProjection(Operation *op) {
  return llvm::isa_and_nonnull<ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(op);
}
VariableType element(Type type) {
  if (auto shaped = llvm::dyn_cast<ShapedType>(type))
    return llvm::dyn_cast<VariableType>(shaped.getElementType());
  return llvm::dyn_cast<VariableType>(type);
}
// All admitted procedural regions are lexical, single-block regions. Walking
// the use's enclosing operations checks both visibility and declaration order
// without building a fresh whole-module dominance graph for each access.
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
LogicalResult checkLexicalOwner(Operation *operation, Operation *procedure, Operation *use) {
  if (operation->getParentOfType<AnalogProcedureOp>().getOperation() != procedure ||
      use->getParentOfType<AnalogProcedureOp>().getOperation() != procedure ||
      text(operation, "owner") != text(procedure, "owner") ||
      text(use, "owner") != text(procedure, "owner") || !precedesLexically(operation, use))
    return reject(use,
                  "storage reference escapes its procedure, lexical scope or declaration order");
  if (failed(verifyGeneratedAnalogOwnership(operation)) ||
      failed(verifyGeneratedAnalogOwnership(use)))
    return failure();
  return success();
}
} // namespace

FailureOr<AnalogStorageReference> resolveAnalogStorageReference(Value reference, Operation *use) {
  if (!reference || !use || !element(reference.getType()))
    return failure();
  AnalogStorageReference result;
  result.elementType = element(reference.getType());
  llvm::SmallPtrSet<Operation *, 16> visited;
  Value current = reference;
  while (Operation *operation = current.getDefiningOp()) {
    if (!visited.insert(operation).second) {
      (void)reject(use, "cyclic storage projection");
      return failure();
    }
    if (llvm::isa<AnalogVariableOp>(operation)) {
      result.declaration = operation;
      result.root = current;
      break;
    }
    if (!isProjection(operation) || operation->getNumOperands() == 0 ||
        operation->getNumResults() != 1 || operation->getResult(0) != current ||
        element(operation->getOperand(0).getType()) != result.elementType) {
      (void)reject(use, "storage capability requires actual variable-rooted projections");
      return failure();
    }
    result.projections.push_back(operation);
    current = operation->getOperand(0);
  }
  if (!result.declaration || result.declaration->getNumResults() != 1 ||
      result.declaration->getResult(0) != result.root ||
      element(result.root.getType()) != result.elementType) {
    (void)reject(use, "storage reference has no matching analog variable root");
    return failure();
  }
  auto procedure = result.declaration->getParentOfType<AnalogProcedureOp>();
  auto module = result.declaration->getParentOfType<nodal::ModuleOp>();
  if (!procedure || !module || failed(checkLexicalOwner(result.declaration, procedure, use)))
    return failure();
  result.procedure = procedure;
  result.generatedOwner = result.declaration->getParentOfType<GenerateOp>();
  std::reverse(result.projections.begin(), result.projections.end());
  if (auto rootType = llvm::dyn_cast<ShapedType>(result.root.getType())) {
    if (result.elementType.getKind() != "real" ||
        failed(inferStaticShapeMaximumCardinality(module, rootType.getDimensions()))) {
      (void)reject(use, "shaped storage requires finite Real structural dimensions");
      return failure();
    }
  }
  auto rootDimensions = fixedDimensions(module, result.root.getType());
  if (rootDimensions)
    result.exactFixedCardinality = product(*rootDimensions);
  result.wholeRoot = true;
  result.canonicalKey = "whole";
  Operation *previous = result.declaration;
  bool linear = true;
  StaticSum offset;
  llvm::SmallVector<Operation *, 4> prefix;
  for (Operation *projection : result.projections) {
    if (failed(checkLexicalOwner(projection, procedure, use)) ||
        !precedesLexically(previous, projection))
      return failure();
    auto kind = llvm::isa<ShapeIndexOp>(projection)  ? verifyShapeIndexMapping(projection)
                : llvm::isa<ShapeViewOp>(projection) ? verifyShapeViewMapping(projection)
                                                     : verifyShapeSliceMapping(projection);
    if (failed(kind))
      return failure();
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
          auto term = position && stride ? multiplyProduct(*position, *stride) : std::nullopt;
          if (!term || !addProduct(offset, *term)) {
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
        if (!leading || !term || !addProduct(offset, *term))
          linear = false;
      }
    } else {
      // Reshape changes coordinate interpretation, not the selected ordinal
      // sequence. Later indexing/slicing records its immediate input shape.
      identityMap = true;
    }
    prefix.push_back(projection);
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
    previous = projection;
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
  return result;
}

FailureOr<Type> getAnalogStorageSnapshotType(Value reference, Operation *use) {
  auto storage = resolveAnalogStorageReference(reference, use);
  if (failed(storage))
    return failure();
  auto type = storage->elementType;
  Type valueType =
      type.getKind() == "boolean"
          ? Type(IntegerType::get(reference.getContext(), 1))
          : Type(QuantityType::get(reference.getContext(), type.getKind(), type.getDimension()));
  if (auto shaped = llvm::dyn_cast<ShapedType>(reference.getType()))
    return Type(ShapedType::get(reference.getContext(), shaped.getDimensions(), valueType));
  return valueType;
}
LogicalResult verifyAnalogStorageProjection(Operation *projection) {
  if (!isProjection(projection) || projection->getNumResults() != 1)
    return failure();
  return success(succeeded(resolveAnalogStorageReference(projection->getResult(0), projection)));
}
bool analogStorageReferenceCovers(const AnalogStorageReference &initialized,
                                  const AnalogStorageReference &query) {
  if (initialized.declaration != query.declaration || initialized.procedure != query.procedure ||
      initialized.generatedOwner != query.generatedOwner)
    return false;
  if (initialized.wholeRoot || initialized.canonicalKey == query.canonicalKey ||
      llvm::is_contained(query.ancestorKeys, initialized.canonicalKey))
    return true;
  return initialized.contiguousLiteralRegion && initialized.literalFirstOffset &&
         initialized.literalLastOffset && query.literalFirstOffset && query.literalLastOffset &&
         *initialized.literalFirstOffset <= *query.literalFirstOffset &&
         *query.literalLastOffset <= *initialized.literalLastOffset;
}
} // namespace nodal
