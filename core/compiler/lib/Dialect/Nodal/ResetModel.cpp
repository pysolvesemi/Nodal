#include "nodal/Dialect/Nodal/ResetModel.h"

#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/ClockModel.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"

#include <map>
#include <tuple>
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
  explicit Inspector(const DomainModel &domains, bool structural = false)
      : domains(&domains), structural(structural) {}
  explicit Inspector(const HierarchyModel &hierarchy) : hierarchy(&hierarchy) {}
  FailureOr<ResetInfo> inspect(ContextualValue context, unsigned depth = 0) {
    if (hierarchy) {
      auto actual = hierarchy->resolveCapability(context);
      if (failed(actual))
        return failure();
      context = *actual;
    }
    Value value = context.value;
    Operation *use = context.use;
    unsigned occurrence = context.occurrence;
    const auto key = std::make_pair(occurrence, value);
    if (++work > 65536 || depth > 128) {
      (void)reject(use, "reset ancestry exceeds bounded producer/fact traversal");
      return failure();
    }
    Operation *op = value ? value.getDefiningOp() : nullptr;
    if (!value || !use || !capability(value.getType(), "reset") || !op ||
        op->getParentOp() != use->getParentOp() ||
        (op != use && failed(verifyDigitalValueScope(value, use)))) {
      (void)reject(use, "reset requires an actual owned typed producer");
      return failure();
    }
    if (auto found = cache.find(key); found != cache.end()) {
      if (failed(charge(use, found->second.roots.size())))
        return failure();
      return found->second;
    }
    if (!visiting.insert(key).second) {
      (void)reject(use, "reset producer cycle is not executable");
      return failure();
    }
    // Local verification may establish only structure for an exact formal.
    // No physical root, release, polarity or edge is fabricated here. Public
    // inspection never enables this path; closed-root proof substitutes first.
    if (structural && isa<RequirementResetOp>(op)) {
      if (failed(verifyRequirementProjection(op)))
        return failure();
      visiting.erase(key);
      cache.try_emplace(key, ResetInfo{});
      return ResetInfo{};
    }
    if (hierarchy && isa<PortValueOp, ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(op)) {
      auto info = rawSync(context);
      if (failed(info))
        return failure();
      visiting.erase(key);
      cache.try_emplace(key, *info);
      return *info;
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
        auto owner = projectedSyncOwner(op, occurrence);
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
          auto otherOwner = projectedSyncOwner(&other, occurrence);
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
      root.physicalOccurrence = occurrence;
      StringRef release = text(op, "release");
      if (release == "raw") {
        if (op->hasAttr("release_domain")) {
          (void)reject(op, "raw reset cannot claim a release domain");
          return failure();
        }
      } else if (release == "sync" || release == "external") {
        auto binding = resolveBinding(occurrence, op->getAttr("release_domain"));
        if (failed(binding)) {
          (void)reject(op, "release contract requires an exact owned executable clock domain");
          return failure();
        }
        if (release == "sync" && !shaped) {
          auto owner = resolveBinding(occurrence, port->port->getAttr("domain"));
          if (failed(owner) || text(owner->domain.declaration, "reset_policy") != "sync" ||
              !clocksEqual(owner->clock, binding->clock) ||
              owner->domain.declaration->getAttr("edge") !=
                  binding->domain.declaration->getAttr("edge")) {
            (void)reject(op, "synchronous assertion requires its owning Sync clock and edge");
            return failure();
          }
        }
        root.release = op;
        root.releaseOccurrence = occurrence;
        root.clock = binding->clock.value;
        root.clockUse = binding->clock.use;
        root.clockOccurrence = binding->clock.occurrence;
        root.edge = binding->domain.declaration->getAttrOfType<StringAttr>("edge");
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
      StringAttr edge;
      ContextualValue clock{occurrence, op->getOperand(1), op};
      if (structural && formalClock(op)) {
        // Exact local requirement projection, not an arbitrary unresolved
        // symbol. Its receiving edge remains unknown until occurrence proof.
        if (failed(verifyRequirementProjection(op->getOperand(1).getDefiningOp())) ||
            failed(verifyDigitalValueScope(op->getOperand(1), op)))
          return failure();
      } else {
        auto binding = resolveBinding(occurrence, op->getAttr("destination_domain"));
        if (failed(binding) || !capability(op->getOperand(1).getType(), "clock") ||
            !clocksEqual(binding->clock, clock)) {
          (void)reject(op, "reset synchronizer clock must be the exact destination binding");
          return failure();
        }
        edge = binding->domain.declaration->getAttrOfType<StringAttr>("edge");
        if (hierarchy) {
          auto actualClock = hierarchy->resolveCapability(clock);
          if (failed(actualClock))
            return failure();
          clock = *actualClock;
        }
      }
      auto input = inspect({occurrence, op->getOperand(0), op}, depth + 1);
      if (failed(input) || failed(charge(op, input->roots.size())))
        return failure();
      if (input->polarity && info.polarity != input->polarity) {
        (void)reject(op, "reset synchronizer must preserve input polarity");
        return failure();
      }
      info.roots = input->roots;
      for (auto &root : info.roots) {
        root.release = op;
        root.releaseOccurrence = occurrence;
        root.clock = clock.value;
        root.clockUse = clock.use;
        root.clockOccurrence = clock.occurrence;
        root.edge = edge;
      }
    } else if (isa<ResetCombineOp>(op)) {
      if (op->getNumOperands() == 0 || op->getNumOperands() > 32 ||
          !closed(op, {"reset_polarity", "metadata"})) {
        (void)reject(op, "reset composition requires 1..32 actual reset operands");
        return failure();
      }
      std::map<std::tuple<unsigned, Operation *, std::string>, unsigned> roots;
      for (Value operand : op->getOperands()) {
        auto input = inspect({occurrence, operand, op}, depth + 1);
        if (failed(input) || failed(charge(op, input->roots.size())))
          return failure();
        for (const auto &root : input->roots) {
          auto [at, inserted] = roots.try_emplace(
              std::make_tuple(root.physicalOccurrence, root.physical.port, root.physical.scalarKey),
              info.roots.size());
          if (inserted)
            info.roots.push_back(root);
          else {
            const auto &old = info.roots[at->second];
            if (old.polarity != root.polarity || old.release != root.release ||
                old.releaseOccurrence != root.releaseOccurrence) {
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
    visiting.erase(key);
    cache.try_emplace(key, info);
    return info;
  }

private:
  FailureOr<ContextualBinding> resolveBinding(unsigned occurrence, Attribute reference) const {
    if (hierarchy)
      return hierarchy->resolveBinding(occurrence, reference);
    auto domain = domains->resolveDomain(reference);
    auto binding = domains->resolveClockBinding(reference);
    if (failed(domain) || failed(binding))
      return failure();
    ContextualBinding result{{occurrence, *domain},
                             *binding,
                             {occurrence, (*binding)->getOperand(0), *binding},
                             std::nullopt,
                             (*binding)->getAttrOfType<StringAttr>("reset_polarity")};
    if ((*binding)->getNumOperands() == 2)
      result.reset = ContextualValue{occurrence, (*binding)->getOperand(1), *binding};
    return result;
  }
  bool clocksEqual(ContextualValue a, ContextualValue b) const {
    return hierarchy ? sameClock(a, b, *hierarchy) : sameClock(a.value, a.use, b.value, b.use);
  }
  bool formalClock(Operation *op) const {
    Operation *clock = op->getOperand(1).getDefiningOp();
    auto destination = op->getAttrOfType<FlatSymbolRefAttr>("destination_domain");
    Operation *domain =
        destination ? SymbolTable::lookupSymbolIn(op->getParentOp(), destination) : nullptr;
    return isa_and_nonnull<RequirementClockOp>(clock) &&
           clock->getParentOp() == op->getParentOp() &&
           isa_and_nonnull<DomainRequirementOp>(domain) &&
           domain->getParentOp() == op->getParentOp();
  }
  FailureOr<ResetInfo> rawSync(ContextualValue context) {
    auto physical = resolvePhysicalControl(context.value, context.use, "reset");
    if (failed(physical))
      return failure();
    auto owner = resolveBinding(context.occurrence, physical->port->getAttr("domain"));
    if (failed(owner) || text(owner->domain.declaration, "reset_policy") != "sync" ||
        !owner->reset || owner->reset->occurrence != context.occurrence ||
        !polarity(owner->resetPolarity ? owner->resetPolarity.getValue() : StringRef())) {
      (void)reject(context.use, "raw synchronous reset requires its actual owning Sync binding");
      return failure();
    }
    auto bound = resolvePhysicalControl(owner->reset->value, owner->reset->use, "reset");
    if (failed(bound) || !(*physical == *bound) || failed(resolveClock(owner->clock, *hierarchy))) {
      (void)reject(context.use,
                   "raw synchronous reset must retain its actual bound root and clock");
      return failure();
    }
    ResetInfo info;
    info.polarity = owner->resetPolarity;
    info.roots.push_back({*physical, info.polarity, owner->record, owner->clock.value,
                          owner->clock.use,
                          owner->domain.declaration->getAttrOfType<StringAttr>("edge"),
                          context.occurrence, owner->domain.occurrence, owner->clock.occurrence});
    return info;
  }
  FailureOr<SyncOwner> projectedSyncOwner(Operation *op, unsigned occurrence) {
    auto binding = resolveBinding(occurrence, op->getAttr("release_domain"));
    // Existing physical binding is the per-element declaration owner. Do not
    // infer an element's ownership from its aggregate port's first domain, nor
    // accept a release_domain label without its actual binding SSA edge.
    if (failed(binding) || text(binding->domain.declaration, "reset_policy") != "sync" ||
        !binding->reset || op->getNumResults() != 1 || binding->reset->occurrence != occurrence ||
        binding->reset->value != op->getResult(0)) {
      (void)reject(op, "projected synchronous assertion requires its actual owning Sync binding");
      return failure();
    }
    auto clock = hierarchy ? resolveClock(binding->clock, *hierarchy)
                           : resolveClock(binding->clock.value, binding->clock.use);
    if (failed(clock))
      return failure();
    return SyncOwner{*clock, binding->domain.declaration->getAttrOfType<StringAttr>("edge")};
  }
  LogicalResult charge(Operation *op, uint64_t n) {
    if (n > 65536 - work)
      return reject(op, "reset ancestry exceeds bounded producer/fact traversal");
    work += n;
    return success();
  }
  const DomainModel *domains = nullptr;
  const HierarchyModel *hierarchy = nullptr;
  bool structural = false;
  uint64_t work = 0;
  llvm::DenseMap<std::pair<unsigned, Value>, ResetInfo> cache;
  llvm::DenseSet<std::pair<unsigned, Value>> visiting;
};
} // namespace
FailureOr<ResetInfo> inspectReset(Value value, Operation *use, const DomainModel &domains) {
  return Inspector(domains).inspect({0, value, use});
}
FailureOr<ResetInfo> inspectReset(ContextualValue value, const HierarchyModel &hierarchy) {
  auto result = Inspector(hierarchy).inspect(value);
  if (failed(result))
    return failure();
  if (!result->polarity || result->roots.empty()) {
    (void)reject(value.use, "contextual reset proof cannot retain unresolved formal facts");
    return failure();
  }
  return *result;
}
LogicalResult verifyContextualResetForBinding(const ContextualBinding &binding,
                                              const HierarchyModel &hierarchy) {
  StringRef policy = text(binding.domain.declaration, "reset_policy");
  if (policy == "none")
    return binding.reset ? reject(binding.record, "resetless binding cannot retain an actual reset")
                         : success();
  if (!binding.reset)
    return reject(binding.record, "reset-bearing binding requires an actual reset capability");
  auto info = inspectReset(*binding.reset, hierarchy);
  if (failed(info))
    return failure();
  if (info->polarity != binding.resetPolarity)
    return reject(binding.record, "binding polarity must match its actual reset producer");
  for (const auto &root : info->roots) {
    if (policy != "sync" && root.release &&
        ((isa<ResetBoundaryOp>(root.release) && text(root.release, "release") == "sync") ||
         isa<LocalDomainBindingOp>(root.release)))
      return reject(binding.record,
                    "synchronous assertion contract cannot satisfy asynchronous reset policy");
    if (!root.release ||
        !sameClock({root.clockOccurrence, root.clock, root.clockUse}, binding.clock, hierarchy) ||
        root.edge != binding.domain.declaration->getAttr("edge"))
      return reject(binding.record,
                    "reset release lacks proof for the exact destination clock and edge");
  }
  return success();
}
LogicalResult verifyResetProducer(Operation *op) {
  if (op->getNumResults() != 1)
    return reject(op, "reset producer requires one result");
  Operation *use = op;
  DomainModel domains(op);
  return success(succeeded(Inspector(domains, true).inspect({0, op->getResult(0), use})));
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
