#include "nodal/Dialect/Nodal/DomainModel.h"

#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ResetModel.h"

#include "llvm/ADT/DenseSet.h"

using namespace mlir;
namespace nodal {
namespace {
StringRef text(Operation *op, StringRef key) {
  auto a = op ? op->getAttrOfType<StringAttr>(key) : StringAttr();
  return a ? a.getValue() : StringRef();
}
LogicalResult reject(Operation *op, StringRef reason) {
  return op->emitOpError("NODAL-SEQUENTIAL-056-001: ") << reason;
}
bool closed(Operation *op, ArrayRef<StringRef> names) {
  return llvm::all_of(op->getAttrs(), [&](NamedAttribute a) {
    return llvm::is_contained(names, a.getName().getValue());
  });
}
bool capability(Type type, StringRef kind) {
  auto c = llvm::dyn_cast<ControlCapabilityType>(type);
  return c && c.getKind() == kind;
}
LogicalResult checkPhysical(Value value, Operation *binding, StringRef kind) {
  Operation *producer = value.getDefiningOp();
  Operation *module = binding->getParentOp();
  if (!capability(value.getType(), kind) || !llvm::isa_and_nonnull<PortValueOp>(producer) ||
      producer->getParentOp() != module || failed(verifyDigitalValueScope(value, binding)))
    return reject(binding, "local binding requires actual owned typed input boundary signals");
  auto ref = producer->getAttrOfType<FlatSymbolRefAttr>("port");
  Operation *port = ref ? SymbolTable::lookupSymbolIn(module, ref) : nullptr;
  auto type = port ? port->getAttrOfType<TypeAttr>("type") : TypeAttr();
  bool ownedDomain = port && (port->getAttr("domain") == binding->getAttr("domain") ||
                              (kind == "clock" && !port->getAttr("domain")));
  if (!ownedDomain && kind == "clock" && port) {
    auto reference = binding->getAttrOfType<FlatSymbolRefAttr>("domain");
    Operation *current = reference ? SymbolTable::lookupSymbolIn(module, reference) : nullptr;
    llvm::DenseSet<Operation *> seen;
    for (unsigned depth = 0; current && depth < 128 && seen.insert(current).second; ++depth) {
      auto parent = current->getAttrOfType<FlatSymbolRefAttr>("parent");
      if (!parent)
        break;
      if (parent == port->getAttr("domain")) {
        ownedDomain = true;
        break;
      }
      current = SymbolTable::lookupSymbolIn(module, parent);
    }
  }
  if (!llvm::isa_and_nonnull<PortOp>(port) || text(port, "direction") != "input" || !type ||
      type.getValue() != value.getType() || !ownedDomain)
    return reject(binding, "clock/reset producer must match an actual local input port and domain");
  return success();
}
} // namespace

DomainModel::DomainModel(Operation *use) {
  module = llvm::isa_and_nonnull<ModuleOp>(use) ? use
           : use                                ? use->getParentOfType<ModuleOp>()
                                                : nullptr;
  if (!module || module->getNumRegions() != 1 || !module->getRegion(0).hasOneBlock()) {
    module = nullptr;
    return;
  }
  for (Operation &op : module->getRegion(0).front()) {
    auto record = [&](auto &index, Attribute reference) {
      auto inserted = index.try_emplace(reference, Binding{&op, false});
      if (!inserted.second)
        inserted.first->second.duplicate = true;
    };
    if (llvm::isa<DomainBindOp>(op)) {
      if (auto reference = op.getAttrOfType<FlatSymbolRefAttr>("requirement"))
        record(requirements, reference);
    } else if (llvm::isa<LocalDomainBindingOp>(op)) {
      record(physicalBindings, op.getAttr("domain"));
    }
  }
}
FailureOr<Operation *> DomainModel::resolveDomain(Attribute reference) const {
  auto symbol = llvm::dyn_cast_if_present<FlatSymbolRefAttr>(reference);
  Operation *domain = module && symbol ? SymbolTable::lookupSymbolIn(module, symbol) : nullptr;
  if (!domain || domain->getParentOp() != module)
    return failure();
  if (llvm::isa<DomainOp>(domain))
    return domain;
  if (!llvm::isa<DomainRequirementOp>(domain))
    return failure();
  auto found = requirements.find(reference);
  if (found == requirements.end() || found->second.duplicate)
    return failure();
  Operation *binding = found->second.first;
  if (binding->getNumOperands() || binding->getNumResults() || binding->getNumRegions() ||
      binding->getNumSuccessors())
    return failure();
  auto actual = binding->getAttrOfType<FlatSymbolRefAttr>("actual");
  domain = actual ? SymbolTable::lookupSymbolIn(module, actual) : nullptr;
  return llvm::isa_and_nonnull<DomainOp>(domain) && domain->getParentOp() == module
             ? FailureOr<Operation *>(domain)
             : FailureOr<Operation *>(failure());
}
FailureOr<Operation *> DomainModel::resolveBinding(Attribute reference) const {
  auto domain = resolveDomain(reference);
  if (failed(domain))
    return failure();
  auto actual = FlatSymbolRefAttr::get(module->getContext(), text(*domain, "sym_name"));
  auto found = physicalBindings.find(actual);
  if (found == physicalBindings.end())
    return failure();
  // Validate the first record before rejecting ambiguity, preserving F56's
  // diagnostic order when the first physical binding itself is malformed.
  Operation *binding = found->second.first;
  if (failed(verifyLocalDomainBinding(binding)) || found->second.duplicate)
    return failure();
  return binding;
}
FailureOr<Operation *> DomainModel::resolveClockBinding(Attribute reference) const {
  auto domain = resolveDomain(reference);
  if (failed(domain))
    return failure();
  auto actual = FlatSymbolRefAttr::get(module->getContext(), text(*domain, "sym_name"));
  auto found = physicalBindings.find(actual);
  if (found == physicalBindings.end() || found->second.duplicate ||
      failed(verifyLocalDomainClock(found->second.first)))
    return failure();
  return found->second.first;
}
bool DomainModel::matchesActualDomain(Attribute reference, Attribute expected) const {
  auto domain = resolveDomain(reference);
  return succeeded(domain) &&
         FlatSymbolRefAttr::get(module->getContext(), text(*domain, "sym_name")) == expected;
}
Operation *DomainModel::findLocalBinding(Attribute reference) const {
  auto found = physicalBindings.find(reference);
  return found == physicalBindings.end() ? nullptr : found->second.first;
}

LogicalResult verifyLocalDomainClock(Operation *op) {
  if (!llvm::isa_and_nonnull<LocalDomainBindingOp>(op) || !llvm::isa<ModuleOp>(op->getParentOp()) ||
      op->getNumResults() || op->getNumRegions() || op->getNumSuccessors() ||
      op->getNumOperands() < 1 || op->getNumOperands() > 2 ||
      !closed(op, {"domain", "reset_polarity", "metadata"}))
    return reject(op, "local domain binding requires one clock and at most one reset");
  auto reference = op->getAttrOfType<FlatSymbolRefAttr>("domain");
  Operation *domain =
      reference ? SymbolTable::lookupSymbolIn(op->getParentOp(), reference) : nullptr;
  if (!llvm::isa_and_nonnull<DomainOp>(domain) || domain->getParentOp() != op->getParentOp() ||
      (text(domain, "edge") != "rising" && text(domain, "edge") != "falling"))
    return reject(op, "executable local domain requires explicit rising/falling edge");
  StringRef policy = text(domain, "reset_policy");
  if (policy != "none" && policy != "sync" && policy != "async" &&
      policy != "async_assert_sync_release")
    return reject(
        op,
        "reset synchronization requires the explicit F57 implementation; no implicit synchronizer");
  if ((policy == "none") != (op->getNumOperands() == 1) ||
      (text(op, "reset_polarity") != "active_high" && text(op, "reset_polarity") != "active_low"))
    return reject(op, "physical reset presence and explicit polarity must match domain policy");
  if (failed(checkPhysical(op->getOperand(0), op, "clock")))
    return failure();
  // Validate ancestry even for resetless/no-state consumers. Equal frequency or
  // a self-only test is insufficient to establish a valid derived-domain DAG.
  llvm::DenseSet<Operation *> seen;
  Operation *current = domain;
  unsigned count = 0;
  while (current) {
    if (++count > 128 || !seen.insert(current).second)
      return reject(op, "generated domain parent cycle or depth bound exceeded");
    auto parent = current->getAttrOfType<FlatSymbolRefAttr>("parent");
    if (!parent)
      break;
    Operation *next = SymbolTable::lookupSymbolIn(op->getParentOp(), parent);
    if (!isa_and_nonnull<DomainOp>(next) || next->getParentOp() != op->getParentOp() ||
        text(next, "edge") != text(current, "edge"))
      return reject(op, "generated domain requires an exact owned parent and inherited edge");
    current = next;
  }
  return success();
}
LogicalResult verifyLocalDomainBinding(Operation *op) {
  if (failed(verifyLocalDomainClock(op)))
    return failure();
  auto reference = op->getAttrOfType<FlatSymbolRefAttr>("domain");
  auto *domain = SymbolTable::lookupSymbolIn(op->getParentOp(), reference);
  DomainModel domains(op);
  return verifyResetForBinding(op, domain, domains);
}
} // namespace nodal
