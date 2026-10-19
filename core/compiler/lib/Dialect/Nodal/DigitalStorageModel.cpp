#include "nodal/Dialect/Nodal/DigitalStorageModel.h"

#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/ADT/StringSet.h"

#include <limits>

using namespace mlir;

namespace nodal {
namespace {
LogicalResult reject(Operation *operation, StringRef message) {
  return operation->emitOpError("NODAL-DIGITAL-054-010: ") << message;
}

StringRef text(Operation *operation, StringRef name) {
  auto attribute = operation->getAttrOfType<StringAttr>(name);
  return attribute ? attribute.getValue() : StringRef();
}

LogicalResult verifyDeclaration(Operation *operation) {
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(operation->getParentOp()))
    return reject(operation, "digital storage must be declared directly in its owning module");
  StringRef name = text(operation, llvm::isa<MemoryOp>(operation) ? SymbolTable::getSymbolAttrName()
                                                                  : StringRef("name"));
  if (name.empty() || name != name.trim() || name.size() > digitalTypeMaximumIdentifierBytes)
    return reject(operation, "digital storage requires a bounded nonempty declaration name");
  if (operation->getNumRegions() || operation->getNumSuccessors())
    return reject(operation, "digital storage declarations cannot own executable regions");
  return success();
}

LogicalResult verifyDomain(Operation *operation) {
  auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("domain");
  if (!reference)
    return reject(operation, "stateful storage requires an explicit owned clock/reset domain");
  Operation *module = operation->getParentOp();
  Operation *domain = SymbolTable::lookupSymbolIn(module, reference);
  if (!llvm::isa_and_nonnull<DomainOp, DomainRequirementOp>(domain) ||
      domain->getParentOp() != module)
    return reject(operation, "stateful storage domain must identify a local domain or requirement");
  return success();
}

bool canonicalDepth(StringRef depth) {
  if (depth.empty() || depth.size() > digitalTypeMaximumWidthTokenBytes)
    return false;
  int64_t literal = 0;
  if (depth.front() >= '0' && depth.front() <= '9')
    return depth.front() != '0' && !depth.getAsInteger(10, literal) && literal > 0;
  return isCanonicalDigitalWidthToken(depth);
}
} // namespace

LogicalResult verifyDigitalStorageModel(Operation *module) {
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module))
    return failure();
  llvm::StringSet<> names;
  for (Operation &operation : module->getRegion(0).front())
    if (llvm::isa<DigitalStorageOp>(operation) && !names.insert(text(&operation, "name")).second)
      return reject(&operation, "digital storage declaration names must be unique in their module");
  return verifyDigitalAssignmentModel(module);
}

LogicalResult verifyDigitalStorageOperation(Operation *operation) {
  if (failed(verifyDeclaration(operation)))
    return failure();
  if (operation->getNumResults() != 1 || operation->getNumOperands() > 1)
    return reject(operation, "wire/register requires one typed value and at most one initializer");
  Type type = operation->getResult(0).getType();
  if (failed(inferDigitalStorageMaximumBits(type, operation)))
    return reject(operation, "wire/register requires an owned finite directionless storage type");
  StringRef kind = text(operation, "kind");
  if (kind == "wire") {
    if (operation->getNumOperands() || operation->hasAttr("domain"))
      return reject(operation, "a structural wire has neither a state initializer nor a domain");
  } else if (kind == "register") {
    if (failed(verifyDomain(operation)))
      return failure();
    if (operation->getNumOperands()) {
      Value initial = operation->getOperand(0);
      Operation *definition = initial.getDefiningOp();
      if (!equivalentDigitalStorageTypes(initial.getType(), type, operation->getParentOp()))
        return reject(operation,
                      "register initializer must have exactly the declared recursive type");
      if (!definition || definition->getParentOp() != operation->getParentOp() ||
          definition == operation || !definition->isBeforeInBlock(operation))
        return reject(operation, "register initializer must be a value owned by the same module");
    }
  } else {
    return reject(operation, "digital storage kind must be wire or register");
  }
  return success();
}

LogicalResult verifyDigitalMemoryOperation(Operation *operation) {
  if (failed(verifyDeclaration(operation)))
    return failure();
  if (operation->getNumResults() || operation->getNumOperands())
    return reject(operation, "memory declaration has no value result or access operands");
  auto element = operation->getAttrOfType<TypeAttr>("element_type");
  if (!element)
    return reject(operation, "memory requires an exact element type");
  auto bits = inferDigitalStorageMaximumBits(element.getValue(), operation);
  if (failed(bits))
    return reject(operation, "memory element requires an owned finite directionless storage type");
  StringRef depth = text(operation, "depth");
  auto extent = canonicalDepth(depth) ? resolveStaticShapeDimension(operation->getParentOp(), depth)
                                      : FailureOr<StaticShapeDimension>(failure());
  if (failed(extent) || extent->bounds.lower <= 0 || extent->bounds.upper <= 0)
    return reject(operation, "memory depth requires a canonical owned positive static shape proof");
  if (*bits > std::numeric_limits<int64_t>::max() / extent->bounds.upper)
    return reject(operation, "memory depth times element footprint exceeds signed-i64 metadata");
  auto latency = operation->getAttrOfType<IntegerAttr>("read_latency");
  if (!latency || !latency.getType().isSignlessInteger(64) || latency.getInt() < 0)
    return reject(operation, "memory read latency must be a nonnegative signed-i64 descriptor");
  StringRef collision = text(operation, "read_under_write");
  if (collision != "old_data" && collision != "new_data" && collision != "no_change" &&
      collision != "undefined")
    return reject(operation, "memory read-under-write policy is not one of the explicit contracts");
  StringRef ordering = text(operation, "ordering");
  if (ordering != "ordered" && ordering != "independent_ports")
    return reject(operation, "memory ordering must be ordered or independent_ports");
  return verifyDomain(operation);
}

LogicalResult verifyDigitalAssignmentOperation(Operation *operation) {
  Operation *parent = operation->getParentOp();
  const bool loop = isBoundedDigitalLoop(parent);
  const bool scoped = isDigitalRegionOwner(parent);
  Operation *module = scoped ? operation->getParentOfType<nodal::ModuleOp>() : parent;
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module) || operation->getNumResults() ||
      operation->getNumOperands() != 2 || operation->getNumRegions() ||
      (loop && failed(inspectDigitalLoopHeader(parent))))
    return reject(operation,
                  "digital assignment requires two values in its owned module or bounded loop");
  Value target = operation->getOperand(0), value = operation->getOperand(1);
  const bool control = isDigitalControlBoundaryType(target.getType());
  std::optional<ParameterIntegerEnvironment> context;
  if (scoped) {
    auto contexts = digitalIntegerContextsAtUse(operation);
    if (failed(contexts) || contexts->size() != 1)
      return failure();
    context.emplace(contexts->front());
  }
  if (control ? target.getType() != value.getType()
              : !equivalentDigitalStorageTypes(target.getType(), value.getType(), module,
                                               context ? &*context : nullptr))
    return reject(operation,
                  "digital assignment requires exactly matching recursive storage types");
  for (Value operand : operation->getOperands()) {
    if (failed(verifyCollectionValueScope(operand, operation)))
      return reject(operation,
                    loop ? "digital assignment operands must retain exact lexical/module ownership"
                         : "digital assignment operands must belong to the same module");
  }
  auto projection = resolveStaticShapeProjection(target, operation);
  if (failed(projection))
    return reject(operation, "digital assignment target has no verified structural root");
  Operation *root = projection->root.getDefiningOp();
  if (!root || root->getParentOp() != module)
    return reject(operation, "digital assignment target root belongs to another module");
  if (scoped && (control || text(operation, "kind") != "continuous"))
    return reject(operation, "bounded loop writes require continuous ordinary digital storage");
  if (control) {
    auto source = resolveStaticShapeProjection(value, operation);
    if (failed(source) || text(operation, "kind") != "continuous")
      return reject(operation, "Clock/Reset wiring requires continuous exact boundary projections");
    auto boundaryPort = [&](Value rootValue, StringRef direction) -> Operation * {
      Operation *producer = rootValue.getDefiningOp();
      if (!llvm::isa_and_nonnull<PortValueOp>(producer) || producer->getParentOp() != module ||
          producer->getNumOperands() || producer->getNumResults() != 1)
        return nullptr;
      auto reference = producer->getAttrOfType<FlatSymbolRefAttr>("port");
      Operation *port = reference ? SymbolTable::lookupSymbolIn(module, reference) : nullptr;
      auto type = port ? port->getAttrOfType<TypeAttr>("type") : TypeAttr();
      if (!llvm::isa_and_nonnull<PortOp>(port) || port->getParentOp() != module ||
          text(port, "direction") != direction || !type || type.getValue() != rootValue.getType())
        return nullptr;
      return port;
    };
    Operation *destinationPort = boundaryPort(projection->root, "output");
    Operation *sourcePort = boundaryPort(source->root, "input");
    if (!destinationPort || !sourcePort)
      return reject(operation,
                    "Clock/Reset wiring requires actual output and input port provenance");
    auto destinationDomain = destinationPort->getAttrOfType<FlatSymbolRefAttr>("domain");
    auto sourceDomain = sourcePort->getAttrOfType<FlatSymbolRefAttr>("domain");
    if (!destinationDomain || !sourceDomain || destinationDomain != sourceDomain)
      return reject(operation, "Clock/Reset wiring requires exact boundary domain identity");
    return success();
  }
  StringRef requiredKind;
  if (llvm::isa<DigitalStorageOp>(root)) {
    if (failed(verifyDigitalStorageOperation(root)))
      return failure();
    if (text(root, "kind") == "register" && target != projection->root)
      return reject(operation,
                    "projected register updates are unsupported; use the owned root state update");
    requiredKind = text(root, "kind") == "register" ? "register_next" : "continuous";
  } else if (llvm::isa<PortValueOp>(root)) {
    auto reference = root->getAttrOfType<FlatSymbolRefAttr>("port");
    Operation *port = reference ? SymbolTable::lookupSymbolIn(module, reference) : nullptr;
    auto type = port ? port->getAttrOfType<TypeAttr>("type") : TypeAttr();
    if (!llvm::isa_and_nonnull<PortOp>(port) || port->getParentOp() != module ||
        text(port, "direction") != "output" || !type ||
        type.getValue() != projection->root.getType())
      return reject(operation, "digital assignment port target must be an exact writable output");
    requiredKind = "continuous";
  } else {
    return reject(operation, "digital assignment target must be a wire, output or register");
  }
  if (text(operation, "kind") != requiredKind)
    return reject(operation, "assignment intent does not match the target storage kind");
  return success();
}
} // namespace nodal
