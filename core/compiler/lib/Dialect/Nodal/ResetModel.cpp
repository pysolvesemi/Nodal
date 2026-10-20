#include "nodal/Dialect/Nodal/ResetModel.h"

#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/ClockModel.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"

#include <map>
using namespace mlir;
namespace nodal {
namespace {
StringRef text(Operation *op, StringRef key) {
  auto a = op ? op->getAttrOfType<StringAttr>(key) : StringAttr();
  return a ? a.getValue() : StringRef();
}
LogicalResult reject(Operation *op, const Twine &reason) {
  return op->emitOpError("NODAL-RESET-057-001: ") << reason;
}
bool capability(Type t, StringRef kind) {
  auto c = llvm::dyn_cast<ControlCapabilityType>(t);
  return c && c.getKind() == kind;
}
bool polarity(StringRef p) { return p == "active_high" || p == "active_low"; }
bool closed(Operation *op, ArrayRef<StringRef> names) {
  return llvm::all_of(op->getAttrs(), [&](NamedAttribute a) {
    return llvm::is_contained(names, a.getName().getValue());
  });
}
class Inspector {
  struct SyncOwner {
    ClockIdentity clock;
    StringAttr edge;
  };

public:
  explicit Inspector(const DomainModel &domains) : domains(domains) {}
  FailureOr<ResetInfo> inspect(Value value, Operation *use, unsigned depth = 0) {
    if (++work > 65536 || depth > 128) {
      (void)reject(use, "reset ancestry exceeds bounded producer/fact traversal");
      return failure();
    }
    Operation *op = value.getDefiningOp();
    if (!capability(value.getType(), "reset") || !op || op->getParentOp() != use->getParentOp() ||
        (op != use && failed(verifyDigitalValueScope(value, use)))) {
      (void)reject(use, "reset requires an actual owned typed producer");
      return failure();
    }
    if (auto found = cache.find(value); found != cache.end()) {
      if (failed(charge(use, found->second.roots.size())))
        return failure();
      return found->second;
    }
    if (!visiting.insert(value).second) {
      (void)reject(use, "reset producer cycle is not executable");
      return failure();
    }
    if (!isa<ModuleOp>(op->getParentOp()) || op->getNumResults() != 1 || op->getNumRegions() ||
        op->getNumSuccessors() || !polarity(text(op, "reset_polarity"))) {
      (void)reject(op, "reset producer requires one result, flat ownership and explicit polarity");
      return failure();
    }
    ResetInfo info;
    info.polarity = op->getAttrOfType<StringAttr>("reset_polarity");
    if (isa<ResetBoundaryOp>(op)) {
      if (op->getNumOperands() != 1 ||
          !closed(op, {"reset_polarity", "release", "release_domain", "metadata"})) {
        (void)reject(op, "reset boundary has invalid operands or attributes");
        return failure();
      }
      auto port = resolvePhysicalControl(op->getOperand(0), op, "reset");
      if (failed(port)) {
        (void)reject(op, "reset boundary must retain its exact physical input port");
        return failure();
      }
      const bool shaped = isa<nodal::ShapedType>(port->root.getType());
      std::optional<SyncOwner> syncOwner;
      if (shaped && text(op, "release") == "sync") {
        auto owner = projectedSyncOwner(op);
        if (failed(owner))
          return failure();
        syncOwner = *owner;
      }
      // Aliased port_value/view producers still designate one physical element.
      // One element cannot acquire contradictory polarity or Sync timing facts.
      for (Operation &other : op->getParentOp()->getRegion(0).front()) {
        if (failed(charge(op, 1)))
          return failure();
        if (!isa<ResetBoundaryOp>(other) || other.getNumOperands() != 1)
          continue;
        auto otherPort = resolvePhysicalControl(other.getOperand(0), &other, "reset");
        if (failed(otherPort) || !(*otherPort == *port))
          continue;
        if (text(&other, "reset_polarity") != text(op, "reset_polarity")) {
          (void)reject(op, "one physical reset root cannot have contradictory polarities");
          return failure();
        }
        if (syncOwner && text(&other, "release") == "sync") {
          auto otherOwner = projectedSyncOwner(&other);
          if (failed(otherOwner))
            return failure();
          if (!(syncOwner->clock == otherOwner->clock) || syncOwner->edge != otherOwner->edge) {
            (void)reject(op,
                         "one physical reset root cannot have incompatible Sync clocks or edges");
            return failure();
          }
        }
      }
      ResetRoot root{*port, info.polarity, nullptr, {}, nullptr, {}};
      StringRef release = text(op, "release");
      if (release == "raw") {
        if (op->hasAttr("release_domain")) {
          (void)reject(op, "raw reset cannot claim a release domain");
          return failure();
        }
      } else if (release == "sync" || release == "external") {
        auto binding = domains.resolveClockBinding(op->getAttr("release_domain"));
        auto domain = domains.resolveDomain(op->getAttr("release_domain"));
        if (failed(binding) || failed(domain)) {
          (void)reject(op, "release contract requires an exact owned executable clock domain");
          return failure();
        }
        if (release == "sync" && !shaped) {
          auto owner = domains.resolveDomain(port->port->getAttr("domain"));
          auto ownerBinding = domains.resolveClockBinding(port->port->getAttr("domain"));
          if (failed(owner) || failed(ownerBinding) || text(*owner, "reset_policy") != "sync" ||
              !sameClock((*ownerBinding)->getOperand(0), *ownerBinding, (*binding)->getOperand(0),
                         *binding) ||
              (*owner)->getAttr("edge") != (*domain)->getAttr("edge")) {
            (void)reject(op, "synchronous assertion requires its owning Sync clock and edge");
            return failure();
          }
        }
        root.release = op;
        root.clock = (*binding)->getOperand(0);
        root.clockUse = *binding;
        root.edge = (*domain)->getAttrOfType<StringAttr>("edge");
      } else {
        (void)reject(op, "unsupported reset boundary release contract");
        return failure();
      }
      info.roots.push_back(root);
    } else if (isa<ResetSyncOp>(op)) {
      auto stages = op->getAttrOfType<IntegerAttr>("stages");
      if (op->getNumOperands() != 2 ||
          !closed(op, {"destination_domain", "reset_polarity", "stages", "metadata"}) || !stages ||
          stages.getInt() < 2 || stages.getInt() > 32) {
        (void)reject(
            op, "reset synchronizer requires two operands, closed attributes and 2..32 stages");
        return failure();
      }
      auto binding = domains.resolveClockBinding(op->getAttr("destination_domain"));
      auto domain = domains.resolveDomain(op->getAttr("destination_domain"));
      if (failed(binding) || failed(domain) ||
          !sameClock((*binding)->getOperand(0), *binding, op->getOperand(1), op) ||
          !capability(op->getOperand(1).getType(), "clock")) {
        (void)reject(op, "reset synchronizer clock must be the exact destination binding");
        return failure();
      }
      auto input = inspect(op->getOperand(0), op, depth + 1);
      if (failed(input) || failed(charge(op, input->roots.size())))
        return failure();
      if (info.polarity != input->polarity) {
        (void)reject(op, "reset synchronizer must preserve input polarity");
        return failure();
      }
      info.roots = input->roots;
      for (auto &root : info.roots) {
        root.release = op;
        root.clock = op->getOperand(1);
        root.clockUse = op;
        root.edge = (*domain)->getAttrOfType<StringAttr>("edge");
      }
    } else if (isa<ResetCombineOp>(op)) {
      if (op->getNumOperands() == 0 || op->getNumOperands() > 32 ||
          !closed(op, {"reset_polarity", "metadata"})) {
        (void)reject(op, "reset composition requires 1..32 actual reset operands");
        return failure();
      }
      std::map<std::pair<Operation *, std::string>, unsigned> roots;
      for (Value operand : op->getOperands()) {
        auto input = inspect(operand, op, depth + 1);
        if (failed(input) || failed(charge(op, input->roots.size())))
          return failure();
        for (const auto &root : input->roots) {
          auto [at, inserted] = roots.try_emplace(
              std::make_pair(root.physical.port, root.physical.scalarKey), info.roots.size());
          if (inserted)
            info.roots.push_back(root);
          else {
            const auto &old = info.roots[at->second];
            if (old.polarity != root.polarity || old.release != root.release) {
              (void)reject(op, "independent release paths of one reset root cannot reconverge");
              return failure();
            }
          }
        }
      }
    } else {
      (void)reject(op,
                   "reset safety requires a typed boundary, synchronizer or composition producer");
      return failure();
    }
    visiting.erase(value);
    cache.try_emplace(value, info);
    return info;
  }

private:
  FailureOr<SyncOwner> projectedSyncOwner(Operation *op) {
    auto domain = domains.resolveDomain(op->getAttr("release_domain"));
    auto binding = domains.resolveClockBinding(op->getAttr("release_domain"));
    // Existing physical binding is the per-element declaration owner. Do not
    // infer an element's ownership from its aggregate port's first domain, nor
    // accept a release_domain label without its actual binding SSA edge.
    if (failed(domain) || failed(binding) || text(*domain, "reset_policy") != "sync" ||
        (*binding)->getNumOperands() != 2 || op->getNumResults() != 1 ||
        (*binding)->getOperand(1) != op->getResult(0)) {
      (void)reject(op, "projected synchronous assertion requires its actual owning Sync binding");
      return failure();
    }
    auto clock = resolveClock((*binding)->getOperand(0), *binding);
    if (failed(clock))
      return failure();
    return SyncOwner{*clock, (*domain)->getAttrOfType<StringAttr>("edge")};
  }
  LogicalResult charge(Operation *op, uint64_t n) {
    if (n > 65536 - work)
      return reject(op, "reset ancestry exceeds bounded producer/fact traversal");
    work += n;
    return success();
  }
  const DomainModel &domains;
  uint64_t work = 0;
  llvm::DenseMap<Value, ResetInfo> cache;
  llvm::DenseSet<Value> visiting;
};
} // namespace
FailureOr<ResetInfo> inspectReset(Value value, Operation *use, const DomainModel &domains) {
  return Inspector(domains).inspect(value, use);
}
LogicalResult verifyResetProducer(Operation *op) {
  if (op->getNumResults() != 1)
    return reject(op, "reset producer requires one result");
  Operation *use = op;
  DomainModel domains(op);
  return success(succeeded(inspectReset(op->getResult(0), use, domains)));
}
LogicalResult verifyResetForBinding(Operation *binding, Operation *domain,
                                    const DomainModel &domains) {
  StringRef policy = text(domain, "reset_policy");
  if (policy == "none")
    return success();
  Value reset = binding->getOperand(1);
  if (policy == "sync" && isa_and_nonnull<PortValueOp, ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(
                              reset.getDefiningOp())) {
    auto root = resolvePhysicalControl(reset, binding, "reset");
    if (succeeded(root) && root->port->getAttr("domain") == binding->getAttr("domain"))
      return success();
    return reject(binding, "raw synchronous reset must belong to its actual sampling domain");
  }
  auto info = inspectReset(reset, binding, domains);
  if (failed(info))
    return failure();
  if (info->polarity != binding->getAttr("reset_polarity"))
    return reject(binding, "binding polarity must match its actual reset producer");
  for (const auto &root : info->roots) {
    if (policy != "sync" && root.release && isa<ResetBoundaryOp>(root.release) &&
        text(root.release, "release") == "sync")
      return reject(binding,
                    "synchronous assertion contract cannot satisfy asynchronous reset policy");
    if (!root.release || !sameClock(root.clock, root.clockUse, binding->getOperand(0), binding) ||
        root.edge != domain->getAttr("edge"))
      return reject(binding, "reset release lacks proof for the exact destination clock and edge");
  }
  return success();
}
} // namespace nodal
