#include "nodal/Dialect/Nodal/PhysicalControlModel.h"

#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

using namespace mlir;
namespace nodal {
FailureOr<PhysicalControl> resolvePhysicalControl(Value value, Operation *use, StringRef kind) {
  auto capability =
      value ? dyn_cast<ControlCapabilityType>(value.getType()) : ControlCapabilityType();
  Operation *module = use ? use->getParentOp() : nullptr;
  if (!capability || capability.getKind() != kind || !isa_and_nonnull<ModuleOp>(module) ||
      failed(verifyDigitalValueScope(value, use)))
    return failure();
  auto projection = resolveStaticShapeProjection(value, use);
  if (failed(projection) || !projection->exactFixedCardinality ||
      !projection->literalRootFlatOffset || projection->canonicalScalarKey.empty() ||
      *projection->literalRootFlatOffset < 0 ||
      *projection->literalRootFlatOffset >= *projection->exactFixedCardinality)
    return failure();
  Operation *producer = projection->root.getDefiningOp();
  if (!isa_and_nonnull<PortValueOp>(producer) || producer->getParentOp() != module ||
      producer->getNumOperands() || producer->getNumResults() != 1 || producer->getNumRegions() ||
      producer->getNumSuccessors() || llvm::any_of(projection->projections, [&](Operation *step) {
        return step->getParentOp() != module;
      }))
    return failure();
  auto ref = producer->getAttrOfType<FlatSymbolRefAttr>("port");
  Operation *port = ref ? SymbolTable::lookupSymbolIn(module, ref) : nullptr;
  auto type = port ? port->getAttrOfType<TypeAttr>("type") : TypeAttr();
  auto direction = port ? port->getAttrOfType<StringAttr>("direction") : StringAttr();
  if (!isa_and_nonnull<PortOp>(port) || port->getParentOp() != module || !direction ||
      direction.getValue() != "input" || !type || type.getValue() != projection->root.getType())
    return failure();
  return PhysicalControl{port, projection->root, projection->canonicalScalarKey,
                         projection->literalRootFlatOffset};
}
bool samePhysicalControl(Value left, Value right, Operation *use, StringRef kind) {
  auto lhs = resolvePhysicalControl(left, use, kind);
  auto rhs = resolvePhysicalControl(right, use, kind);
  return succeeded(lhs) && succeeded(rhs) && *lhs == *rhs;
}
} // namespace nodal
