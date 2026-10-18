#include "nodal/Dialect/Nodal/AnalogStorageModel.h"

#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/ADT/STLExtras.h"

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
  auto projection = resolveStaticShapeProjection(reference, use);
  if (failed(projection))
    return failure();
  AnalogStorageReference result;
  static_cast<StaticShapeProjection &>(result) = std::move(*projection);
  result.elementType = element(reference.getType());
  result.declaration = result.root.getDefiningOp();
  if (!llvm::isa_and_nonnull<AnalogVariableOp>(result.declaration) ||
      element(result.root.getType()) != result.elementType) {
    (void)reject(use, "storage reference has no matching analog variable root");
    return failure();
  }
  auto procedure = result.declaration->getParentOfType<AnalogProcedureOp>();
  if (!procedure || failed(checkLexicalOwner(result.declaration, procedure, use)))
    return failure();
  result.procedure = procedure;
  result.generatedOwner = result.declaration->getParentOfType<GenerateOp>();
  if (llvm::isa<ShapedType>(result.root.getType()) && result.elementType.getKind() != "real") {
    (void)reject(use, "shaped storage requires finite Real structural dimensions");
    return failure();
  }
  for (Operation *operation : result.projections)
    if (failed(checkLexicalOwner(operation, procedure, use)))
      return failure();
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
