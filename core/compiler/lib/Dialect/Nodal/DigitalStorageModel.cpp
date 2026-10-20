#include "nodal/Dialect/Nodal/DigitalStorageModel.h"

#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/EventModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
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

// A family is a retained lexical template, never one occurrence per lane.
// Only structural Generate ancestry is admitted here; behavioral/procedural
// regions cannot manufacture declaration ownership.
bool ownedFamilyScope(Operation *family) {
  unsigned depth = 0;
  for (Operation *scope = family; scope; scope = scope->getParentOp()) {
    if (llvm::isa<ModuleOp>(scope))
      return true;
    if (++depth > 128 || !llvm::isa<GenerateOp>(scope) || scope->getNumResults() != 1 ||
        !llvm::isa<GeneratedFamilyType>(scope->getResult(0).getType()))
      return false;
  }
  return false;
}

LogicalResult verifyDeclaration(Operation *operation) {
  Operation *parent = operation->getParentOp();
  bool family = llvm::isa_and_nonnull<GenerateOp>(parent) && parent->getNumResults() == 1 &&
                llvm::isa<GeneratedFamilyType>(parent->getResult(0).getType()) &&
                ownedFamilyScope(parent) && llvm::isa<DigitalStorageOp>(operation) &&
                succeeded(resolveGeneratedDomain(parent));
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(parent) && !family)
    return reject(operation,
                  "digital storage must be declared directly in its owning module or family");
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
  Operation *module = operation->getParentOfType<ModuleOp>();
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

LogicalResult verifyGeneratedExportOperation(Operation *operation) {
  Operation *family = operation->getParentOp();
  if (!llvm::isa_and_nonnull<GenerateOp>(family) || family->getNumResults() != 1 ||
      operation->getNumOperands() != 1 || operation->getNumResults() ||
      operation->getNumRegions() || operation->getNumSuccessors())
    return reject(operation,
                  "generated export requires its direct actual family and one storage value");
  for (NamedAttribute attribute : operation->getAttrs())
    if (!llvm::is_contained(ArrayRef<StringRef>{"member", "access", "kind", "metadata"},
                            attribute.getName().getValue()))
      return reject(operation, "generated export has unsupported metadata authority");
  StringRef member = text(operation, "member"), access = text(operation, "access");
  if (member.empty() || member.size() > 1024 || member != member.trim() ||
      llvm::any_of(member, [](unsigned char c) { return c <= 32 || c == 127; }))
    return reject(operation, "generated export requires an exact bounded member key");
  StringRef kind = text(operation, "kind");
  if (!kind.empty() && kind != "family")
    return reject(operation, "generated export has unknown closed member kind");
  if (kind == "family") {
    Operation *nested = operation->getOperand(0).getDefiningOp();
    if (access != "read" || !llvm::isa_and_nonnull<GenerateOp>(nested) ||
        nested->getParentOp() != family || nested->getNumResults() != 1 ||
        !llvm::isa<GeneratedFamilyType>(operation->getOperand(0).getType()) ||
        !ownedFamilyScope(nested) || failed(resolveGeneratedDomain(nested)) ||
        failed(verifyDigitalValueScope(operation->getOperand(0), operation)))
      return reject(operation,
                    "family export requires its preceding direct nested family and read access");
    return success();
  }
  Operation *storage = operation->getOperand(0).getDefiningOp();
  if (!llvm::isa_and_nonnull<DigitalStorageOp>(storage) || storage->getParentOp() != family ||
      failed(verifyDigitalValueScope(operation->getOperand(0), operation)) ||
      failed(verifyDigitalStorageOperation(storage)))
    return reject(operation, "generated export must retain preceding direct family storage");
  if (access != "read" && access != text(storage, "kind"))
    return reject(operation, "generated export access cannot expand actual storage capability");
  return success();
}

LogicalResult verifyGeneratedFamilyOperation(Operation *operation) {
  if (operation->getNumResults() == 0) {
    for (Operation &child : operation->getRegion(0).front())
      if (child.getName().getStringRef() == "nodal.generated_export")
        return reject(&child, "export requires an actual family handle");
    return success();
  }
  if (operation->getNumResults() != 1 ||
      !llvm::isa<GeneratedFamilyType>(operation->getResult(0).getType()) ||
      !ownedFamilyScope(operation) || operation->getNumOperands() != 3 ||
      failed(resolveGeneratedDomain(operation)))
    return reject(operation, "family requires one typed handle and a direct owned SSA domain");
  llvm::StringSet<> members, storageNames;
  unsigned count = 0;
  for (Operation &child : operation->getRegion(0).front()) {
    if (++count > 65536)
      return reject(operation, "family template exceeds finite authored operation budget");
    if (llvm::isa<DigitalStorageOp>(child) && !storageNames.insert(text(&child, "name")).second)
      return reject(&child, "family storage names must be unique in their actual scope");
    if (child.getName().getStringRef() != "nodal.generated_export")
      continue;
    if (!members.insert(text(&child, "member")).second ||
        failed(verifyGeneratedExportOperation(&child)))
      return reject(&child, "family exports must have unique verified member keys");
  }
  for (Operation *user : operation->getResult(0).getUsers()) {
    if (llvm::isa<GeneratedExportOp>(user) && text(user, "kind") == "family" &&
        succeeded(verifyGeneratedExportOperation(user)))
      continue;
    if (!llvm::isa<GeneratedMemberOp>(user) || user->getNumOperands() != 2 ||
        user->getOperand(0) != operation->getResult(0))
      return reject(user, "family handle is not a runtime payload or arbitrary capability");
  }
  return success();
}

namespace {
FailureOr<GeneratedMember> inspectMember(Operation *operation, unsigned depth, unsigned &work) {
  auto rejectMember = [&](StringRef reason) -> FailureOr<GeneratedMember> {
    (void)reject(operation, reason);
    return failure();
  };
  if (depth > 128 || !work--)
    return rejectMember("generated selection chain exceeds finite depth or work budget");
  if (!llvm::isa<GeneratedMemberOp>(operation) || operation->getNumOperands() != 2 ||
      operation->getNumResults() != 1 || operation->getNumRegions() ||
      operation->getNumSuccessors())
    return rejectMember("generated member requires one exact family and ordinal");
  for (NamedAttribute attribute : operation->getAttrs())
    if (!llvm::is_contained(ArrayRef<StringRef>{"member", "access", "kind", "metadata"},
                            attribute.getName().getValue()))
      return rejectMember("generated member has unsupported proof metadata");
  Value handle = operation->getOperand(0);
  if (!llvm::isa<GeneratedFamilyType>(handle.getType()) ||
      failed(verifyDigitalValueScope(handle, operation)))
    return rejectMember("generated member must retain its actual dominating family");
  Operation *family = handle.getDefiningOp();
  llvm::SmallVector<GeneratedSelection, 2> selections;
  if (llvm::isa_and_nonnull<GeneratedMemberOp>(family)) {
    auto parent = inspectMember(family, depth + 1, work);
    if (failed(parent) || !parent->familyView)
      return rejectMember("nested family selection requires a checked immutable family view");
    selections = std::move(parent->selections);
    family = parent->storage.getDefiningOp();
  }
  if (!llvm::isa_and_nonnull<GenerateOp>(family) || family->getNumRegions() != 1 ||
      !llvm::hasSingleElement(family->getRegion(0)))
    return rejectMember("generated member must retain an actual family declaration");
  for (Operation &child : family->getRegion(0).front()) {
    (void)child;
    if (!work--)
      return rejectMember("generated selection inventory exceeds finite work budget");
  }
  if (failed(verifyGeneratedFamilyOperation(family)))
    return failure();
  Operation *exported = nullptr;
  for (Operation &child : family->getRegion(0).front())
    if (llvm::isa<GeneratedExportOp>(child) && text(&child, "member") == text(operation, "member"))
      exported = &child;
  if (!exported)
    return rejectMember("generated member key is absent from the actual closed export inventory");
  Value storage = exported->getOperand(0);
  StringRef access = text(operation, "access"), kind = text(operation, "kind");
  bool familyView = kind == "family";
  if ((!kind.empty() && !familyView) || kind != text(exported, "kind") ||
      (familyView && access != "read") ||
      (access != "read" && access != text(exported, "access")) ||
      (access != "read" && access != "wire" && access != "register") ||
      operation->getResult(0).getType() != storage.getType())
    return rejectMember(
        "generated member must retain exact kind, type and monotonic export access");
  auto coordinate = resolveGeneratedOrdinal(family, operation->getOperand(1), operation);
  if (failed(coordinate))
    return rejectMember("generated ordinal lacks an all-legal existence proof");
  selections.push_back({family, operation->getOperand(1), *coordinate});
  if (familyView)
    for (Operation *user : operation->getResult(0).getUsers())
      if (!llvm::isa<GeneratedMemberOp>(user) || user->getNumOperands() != 2 ||
          user->getOperand(0) != operation->getResult(0))
        return rejectMember("family view is immutable structure, not a runtime payload or export");
  return GeneratedMember{family,
                         exported,
                         storage,
                         *coordinate,
                         std::move(selections),
                         familyView,
                         access == "wire"       ? GeneratedMember::Access::Wire
                         : access == "register" ? GeneratedMember::Access::Register
                                                : GeneratedMember::Access::Read};
}
} // namespace

FailureOr<GeneratedMember> inspectGeneratedMember(Operation *operation) {
  unsigned work = 65536;
  return inspectMember(operation, 0, work);
}

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
  if (operation->hasAttr("initializer_policy") &&
      (text(operation, "initializer_policy") != "if_reset" || kind != "register" ||
       operation->getNumOperands() != 1 ||
       !DomainModel(operation).unresolvedRequirement(operation->getAttr("domain")) ||
       !hasLocalHierarchyObligations(operation->getParentOfType<ModuleOp>())))
    return reject(operation, "if_reset requires initialized register storage in an exact "
                             "unresolved local requirement");
  if (kind == "wire") {
    if (operation->getNumOperands() || operation->hasAttr("domain"))
      return reject(operation, "a structural wire has neither a state initializer nor a domain");
  } else if (kind == "register") {
    if (failed(verifyDomain(operation)))
      return failure();
    if (operation->getNumOperands()) {
      Value initial = operation->getOperand(0);
      Operation *definition = initial.getDefiningOp();
      if (!equivalentDigitalStorageTypes(initial.getType(), type,
                                         operation->getParentOfType<ModuleOp>()))
        return reject(operation,
                      "register initializer must have exactly the declared recursive type");
      if (!definition || failed(verifyDigitalValueScope(initial, operation)))
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
  if (!root || root->getParentOfType<ModuleOp>() != module ||
      (root->getParentOp() != module &&
       (!llvm::isa<DigitalStorageOp>(root) || failed(verifyDigitalStorageOperation(root)))))
    return reject(operation, "digital assignment target root belongs to another module");
  const bool staticStateScope = (llvm::isa<StructuralIfOp>(parent) ||
                                 (llvm::isa<GenerateOp>(parent) && parent->getNumResults() == 1)) &&
                                !operation->getParentOfType<CollectionOp>() &&
                                !operation->getParentOfType<HardwareLoopOp>();
  if (scoped && !llvm::isa<BehavioralIfOp, FlatFSMOp, EnumSwitchOp>(parent) &&
      (control || (text(operation, "kind") != "continuous" &&
                   !(staticStateScope && text(operation, "kind") == "register_next"))))
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
  if (text(operation, "kind") == "event_update")
    return verifyEventUpdate(operation);
  if (operation->hasAttr("event_process"))
    return reject(operation, "only event_update may carry an event process identity");
  StringRef requiredKind;
  if (llvm::isa<DigitalStorageOp>(root)) {
    if (failed(verifyDigitalStorageOperation(root)))
      return failure();
    if (text(root, "kind") == "register" && !projection->projections.empty())
      return reject(operation,
                    "projected register updates are unsupported; use the owned root state update");
    requiredKind = text(root, "kind") == "register" ? "register_next" : "continuous";
  } else if (llvm::isa<InstancePortOp>(root)) {
    auto boundary = inspectInstancePort(root);
    if (failed(boundary) || text(root, "direction") != "input")
      return reject(operation, "instance input is writable; child output is read-only");
    requiredKind = "continuous";
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
