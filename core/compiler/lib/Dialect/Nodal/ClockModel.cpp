#include "nodal/Dialect/Nodal/ClockModel.h"

#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ResetModel.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/StringSet.h"

#include <map>

using namespace mlir;
namespace nodal {
namespace {
StringRef text(Operation *op, StringRef key) {
  auto a = op ? op->getAttrOfType<StringAttr>(key) : StringAttr();
  return a ? a.getValue() : StringRef();
}
StringRef path(Operation *op) {
  auto m = op ? op->getAttrOfType<DictionaryAttr>("metadata") : DictionaryAttr();
  auto a = m ? m.getAs<StringAttr>("semantic_path") : StringAttr();
  return a ? a.getValue() : StringRef();
}
LogicalResult reject(Operation *op, StringRef reason) {
  return op->emitOpError("NODAL-CLOCK-057-001: ") << reason;
}
bool capability(Type t, StringRef kind) {
  auto c = dyn_cast<ControlCapabilityType>(t);
  return c && c.getKind() == kind;
}
bool boolean(Type t) {
  auto b = dyn_cast<BitsType>(t);
  return b && b.getWidth() == 1;
}
bool obligation(DictionaryAttr attrs, StringRef key, StringRef expected) {
  auto value = attrs ? attrs.getAs<StringAttr>(key) : StringAttr();
  return value && value.getValue() == expected;
}
FailureOr<OwnedClock> header(Operation *op, const DomainModel &domains) {
  auto bad = [&](StringRef message) -> FailureOr<OwnedClock> {
    (void)reject(op, message);
    return failure();
  };
  const bool mux = isa_and_nonnull<ClockMuxOp>(op);
  if (!isa_and_nonnull<ClockGateOp, ClockMuxOp>(op) ||
      !isa_and_nonnull<ModuleOp>(op->getParentOp()) || op->getNumResults() != 1 ||
      op->getNumOperands() != (mux ? 4u : 3u) || op->getNumRegions() || op->getNumSuccessors() ||
      !capability(op->getResult(0).getType(), "clock"))
    return bad("clock producer requires its exact flat owned operands and Clock result");
  for (NamedAttribute a : op->getAttrs())
    if (!llvm::is_contained(ArrayRef<StringRef>{"parent_domains", "domain", "edge", "contract",
                                                "control_domains", "obligations", "metadata"},
                            a.getName().getValue()))
      return bad("clock producer contains an unsupported field");
  if (text(op, "contract") != (mux ? "glitchless_mux_v1" : "gate_v1") ||
      (text(op, "edge") != "rising" && text(op, "edge") != "falling") || path(op).empty() ||
      path(op).trim() != path(op))
    return bad("clock producer requires closed contract, edge and semantic identity");
  StringRef ownerPath = path(op->getParentOp());
  if (ownerPath.empty())
    ownerPath = text(op->getParentOp(), "sym_name");
  if (path(op).size() > 4096 || !path(op).starts_with((ownerPath + ".").str()) ||
      path(op).size() <= ownerPath.size() + 1)
    return bad("clock producer requires its canonical owned semantic identity");
  auto refs = op->getAttrOfType<ArrayAttr>("parent_domains");
  auto controls = op->getAttrOfType<ArrayAttr>("control_domains");
  if (!refs || refs.size() != (mux ? 2u : 1u) || !controls || controls.size() != (mux ? 1u : 2u))
    return bad("clock parent/control references must match exact operand roles");
  auto ownRef = op->getAttrOfType<FlatSymbolRefAttr>("domain");
  auto domain = domains.resolveDomain(ownRef);
  if (failed(domain) ||
      (*domain)->getAttr("sym_name") != StringAttr::get(op->getContext(), ownRef.getValue()) ||
      (*domain)->getAttr("edge") != op->getAttr("edge"))
    return bad("clock producer requires an exact actual receiving domain and inherited edge");
  OwnedClock result{op, *domain, {nullptr, nullptr}, {nullptr, nullptr}, mux, mux ? 22u : 1u};
  for (unsigned i = 0; i != refs.size(); ++i) {
    auto ref = dyn_cast<FlatSymbolRefAttr>(refs[i]);
    auto parent = domains.resolveDomain(ref);
    Operation *binding = domains.findUniqueLocalBinding(ref);
    if (!ref || failed(parent) || !binding || binding->getNumOperands() < 1 ||
        (*parent)->getAttr("sym_name") != StringAttr::get(op->getContext(), ref.getValue()) ||
        (*parent)->getAttr("edge") != op->getAttr("edge") ||
        (*domain)->getAttr(i ? "second_parent" : "parent") != ref ||
        !capability(op->getOperand(i).getType(), "clock"))
      return bad("clock parent operands require exact actual bindings and inherited edges");
    result.parents[i] = *parent;
    result.parentBindings[i] = binding;
  }
  if ((!mux && (*domain)->hasAttr("second_parent")) ||
      (mux && (refs[0] == refs[1] || !capability(op->getOperand(3).getType(), "reset"))))
    return bad("mux requires two distinct actual parents and a common assertion Reset");
  Operation *binding = domains.findUniqueLocalBinding(ownRef);
  if (!binding || binding->getNumOperands() < 1 || binding->getOperand(0) != op->getResult(0))
    return bad("receiving domain must bind this exact owned clock result");
  for (unsigned i = 0; i != controls.size(); ++i) {
    if (!boolean(op->getOperand(mux ? 2 : i + 1).getType()))
      return bad("clock controls require exact Bool operands, never Pulse or Clock casts");
    if (auto ref = dyn_cast<FlatSymbolRefAttr>(controls[i])) {
      auto control = domains.resolveDomain(ref);
      if (failed(control) ||
          (*control)->getAttr("sym_name") != StringAttr::get(op->getContext(), ref.getValue()))
        return bad("gate controls must belong to their actual parent; mux controls to one domain");
    } else {
      auto kind = dyn_cast<StringAttr>(controls[i]);
      if (!kind || (kind.getValue() != "constant" && kind.getValue() != "external"))
        return bad("clock controls require a source domain, constant or external contract");
    }
  }
  auto obligations = op->getAttrOfType<DictionaryAttr>("obligations");
  if (!obligations || obligations.size() != (mux ? 4u : 2u) ||
      (mux ? !(obligation(obligations, "control_sampling", "level_coalescing") &&
               obligation(obligations, "switching", "serialized_break_before_make") &&
               obligation(obligations, "reset_epoch", "common_assertion") &&
               obligation(obligations, "pulse_integrity", "except_reset_assertion"))
           : !(obligation(obligations, "control_timing", "inactive_phase_aperture") &&
               obligation(obligations, "startup", "known_after_inactive_phase"))))
    return bad("clock obligations must retain the exact closed timing and reset contract");
  for (Value operand : op->getOperands())
    if (failed(verifyDigitalValueScope(operand, op)))
      return bad("clock producer cannot capture foreign or escaped values");
  return result;
}
class ClockResolver {
public:
  explicit ClockResolver(Operation *use) : domains(use) {}
  FailureOr<ClockIdentity> resolve(Value value, Operation *use, unsigned depth = 0) {
    if (++work > 65536 || depth > 128) {
      (void)reject(use, "clock producer graph exceeds finite traversal/depth budget");
      return failure();
    }
    auto *op = value ? value.getDefiningOp() : nullptr;
    if (!value || !use || !capability(value.getType(), "clock") ||
        (op != use && failed(verifyDigitalValueScope(value, use))))
      return failure();
    if (auto argument = dyn_cast<BlockArgument>(value)) {
      auto source = digitalRegionCaptureSource(argument);
      if (failed(source) || !*source)
        return failure();
      // Each hop keeps the real capture operation as its use, so isolated arms
      // cannot acquire a raw outer control or a forged representative alias.
      return resolve(*source, argument.getOwner()->getParentOp(), depth + 1);
    }
    if (!op || op->getParentOp() != use->getParentOfType<ModuleOp>())
      return failure();
    if (auto found = cache.find(value); found != cache.end())
      return found->second;
    if (!visiting.insert(value).second) {
      (void)reject(use, "clock producer/binding cycle is not executable");
      return failure();
    }
    ClockIdentity identity;
    if (isa<ClockGateOp, ClockMuxOp>(op)) {
      auto h = header(op, domains);
      if (failed(h))
        return failure();
      ClockIdentity first;
      for (unsigned i = 0; i != (h->mux ? 2u : 1u); ++i) {
        auto actual = resolve(op->getOperand(i), op, depth + 1);
        auto bound = resolve(h->parentBindings[i]->getOperand(0), h->parentBindings[i], depth + 1);
        if (failed(actual) || failed(bound) || !(*actual == *bound)) {
          (void)reject(op, "clock operand must retain its exact actual parent binding identity");
          return failure();
        }
        if (i == 0)
          first = *actual;
        else if (*actual == first) {
          (void)reject(op, "mux parents must be distinct actual clock identities");
          return failure();
        }
      }
      identity.producer = op;
    } else {
      auto physical = resolvePhysicalControl(value, use, "clock");
      if (failed(physical))
        return failure();
      identity.physical = *physical;
    }
    visiting.erase(value);
    cache.try_emplace(value, identity);
    return identity;
  }

private:
  DomainModel domains;
  unsigned work = 0;
  DenseMap<Value, ClockIdentity> cache;
  DenseSet<Value> visiting;
};
bool sameRoots(const ResetInfo &a, const ResetInfo &b) {
  if (a.roots.empty() || a.roots.size() != b.roots.size())
    return false;
  // Root sets can be composed deeply. Compare canonical keys in O(R log R),
  // without copying transitive provenance onto every clock producer.
  std::map<std::pair<Operation *, std::string>, StringAttr> roots;
  for (const auto &root : a.roots)
    roots.emplace(std::make_pair(root.physical.port, root.physical.scalarKey), root.polarity);
  for (const auto &root : b.roots) {
    auto found = roots.find({root.physical.port, root.physical.scalarKey});
    if (found == roots.end() || found->second != root.polarity)
      return false;
  }
  return true;
}
FailureOr<ResetInfo> bindingRoots(Operation *binding, const DomainModel &domains) {
  Value value = binding->getOperand(1);
  if (isa_and_nonnull<PortValueOp, ShapeIndexOp, ShapeSliceOp, ShapeViewOp>(
          value.getDefiningOp())) {
    // Existing Sync bindings may use the physical reset pin directly. Their
    // assertion/release safety was checked by verifyLocalDomainBinding; retain
    // only its exact root/polarity for the derived independent release chain.
    auto root = resolvePhysicalControl(value, binding, "reset");
    auto polarity = binding->getAttrOfType<StringAttr>("reset_polarity");
    if (failed(root) || !polarity)
      return failure();
    ResetInfo result;
    result.polarity = polarity;
    result.roots.push_back({*root, polarity, nullptr, {}, nullptr, {}});
    return result;
  }
  return inspectReset(value, binding, domains);
}
LogicalResult checkOutputReset(Operation *op, const OwnedClock &h, const ResetInfo &roots,
                               const DomainModel &domains) {
  auto *binding = domains.findUniqueLocalBinding(op->getAttr("domain"));
  if (!binding || binding->getNumOperands() != 2 ||
      (text(h.domain, "reset_policy") != "async_assert_sync_release" &&
       text(h.domain, "reset_policy") != "async"))
    return reject(op, "derived clock requires its own output-clock asynchronous release chain");
  Operation *sync = binding->getOperand(1).getDefiningOp();
  if (!isa_and_nonnull<ResetSyncOp>(sync) || sync->getNumOperands() != 2 ||
      sync->getAttr("destination_domain") != op->getAttr("domain") ||
      (!sync->getAttrOfType<IntegerAttr>("stages") ||
       sync->getAttrOfType<IntegerAttr>("stages").getInt() != 2) ||
      !sameClock(sync->getOperand(1), sync, op->getResult(0), binding))
    return reject(op, "derived reset must have a distinct two-stage output-clock release");
  auto raw = inspectReset(sync->getOperand(0), sync, domains);
  auto output = inspectReset(binding->getOperand(1), binding, domains);
  if (failed(raw) || failed(output) || !sameRoots(roots, *raw) || !sameRoots(roots, *output) ||
      llvm::any_of(raw->roots, [](const ResetRoot &r) { return r.release != nullptr; }))
    return reject(op, "derived output release must originate at the actual common raw roots");
  return verifyResetForBinding(binding, h.domain, domains);
}
} // namespace
FailureOr<ClockIdentity> resolveClock(Value value, Operation *use) {
  return ClockResolver(use).resolve(value, use);
}
FailureOr<ClockIdentity> resolveClock(ContextualValue value, const HierarchyModel &hierarchy) {
  auto actual = hierarchy.resolveCapability(value);
  if (failed(actual))
    return failure();
  auto identity = resolveClock(actual->value, actual->use);
  if (failed(identity))
    return failure();
  identity->occurrence = actual->occurrence;
  return *identity;
}
bool sameClock(ContextualValue left, ContextualValue right, const HierarchyModel &hierarchy) {
  auto a = resolveClock(left, hierarchy);
  auto b = resolveClock(right, hierarchy);
  return succeeded(a) && succeeded(b) && *a == *b;
}
bool sameClock(Value left, Operation *leftUse, Value right, Operation *rightUse) {
  ClockResolver resolver(leftUse);
  auto a = resolver.resolve(left, leftUse);
  auto b = resolver.resolve(right, rightUse);
  return succeeded(a) && succeeded(b) && *a == *b;
}
FailureOr<OwnedClock> inspectOwnedClock(Operation *op, const DomainModel &domains) {
  auto h = header(op, domains);
  if (failed(h) || failed(resolveClock(op->getResult(0), op)))
    return failure();
  // Sampling permission depends on relationships, while physical clock
  // resolution must remain clock-only. Keep this out of header/ClockResolver
  // so a relationship may inspect gate identity without recursively proving
  // that same relationship through the gate's data controls.
  if (!h->mux) {
    auto parents = op->getAttrOfType<ArrayAttr>("parent_domains");
    for (Attribute control : op->getAttrOfType<ArrayAttr>("control_domains"))
      if (isa<FlatSymbolRefAttr>(control) && !domains.allowsDirectSampling(control, parents[0])) {
        (void)reject(op,
                     "gate controls must belong to their actual parent or verified Same domain");
        return failure();
      }
  }
  for (unsigned i = 0; i != (h->mux ? 2u : 1u); ++i)
    if (failed(verifyLocalDomainBinding(h->parentBindings[i])))
      return failure();
  if (!h->mux && text(h->parents[0], "reset_policy") == "none") {
    auto *binding = domains.findUniqueLocalBinding(op->getAttr("domain"));
    if (text(h->domain, "reset_policy") != "none" || binding->getNumOperands() != 1) {
      (void)reject(op, "resetless gate must retain a resetless derived domain");
      return failure();
    }
  } else {
    if (h->parentBindings[0]->getNumOperands() != 2 ||
        (h->mux && h->parentBindings[1]->getNumOperands() != 2)) {
      (void)reject(op, "mux requires nonempty common reset roots; resetless mux is unsupported");
      return failure();
    }
    auto roots = bindingRoots(h->parentBindings[0], domains);
    if (failed(roots))
      return failure();
    if (h->mux) {
      auto other = bindingRoots(h->parentBindings[1], domains);
      auto raw = inspectReset(op->getOperand(3), op, domains);
      if (failed(other) || failed(raw) || !sameRoots(*roots, *other) || !sameRoots(*roots, *raw) ||
          llvm::any_of(raw->roots, [](const ResetRoot &r) { return r.release != nullptr; })) {
        (void)reject(op, "mux common assertion must match both parents' actual raw reset roots");
        return failure();
      }
    }
    if (failed(checkOutputReset(op, *h, *roots, domains)))
      return failure();
  }
  SmallVector<ClockControlSample, 2> controls;
  auto refs = op->getAttrOfType<ArrayAttr>("control_domains");
  for (unsigned i = 0; i != refs.size(); ++i)
    controls.push_back({op->getOperand(h->mux ? 2 : i + 1), refs[i], op});
  if (failed(verifyClockControlSamples(op->getParentOp(), controls)))
    return failure();
  return h;
}
LogicalResult verifyClockProducer(Operation *op) {
  return success(succeeded(inspectOwnedClock(op, DomainModel(op))));
}
LogicalResult verifyClockModel(Operation *module, SequentialModelLimits limits) {
  unsigned work = 0;
  uint64_t state = 0;
  StringSet<> identities;
  for (Operation &op : module->getRegion(0).front()) {
    if (++work > limits.maximumOperations)
      return reject(&op, "clock graph operation budget exceeded");
    if (isa<ResetSyncOp>(op)) {
      auto stages = op.getAttrOfType<IntegerAttr>("stages");
      if (!stages || stages.getInt() < 2 || stages.getInt() > 32)
        return reject(&op, "reset stages are outside the clock state budget contract");
      state += stages.getInt();
    } else if (isa<ClockGateOp, ClockMuxOp>(op)) {
      auto h = inspectOwnedClock(&op, DomainModel(module));
      if (failed(h))
        return failure();
      if (!identities.insert(path(&op)).second)
        return reject(&op, "independent clock producers require unique semantic identities");
      state += h->stateBits;
    }
    if (state > limits.maximumNormalizedOperations)
      return reject(&op, "clock controller/release state budget exceeded before allocation");
  }
  return success();
}
LogicalResult verifyClockBindingReset(Operation *binding, Operation *domain,
                                      const DomainModel &domains) {
  SmallVector<Value> pending{binding->getOperand(0)};
  DenseSet<Value> seen;
  unsigned work = 0;
  while (!pending.empty()) {
    Value value = pending.pop_back_val();
    if (!seen.insert(value).second)
      continue;
    if (++work > 65536)
      return reject(binding, "mux consumer reset proof exceeds graph budget");
    Operation *producer = value.getDefiningOp();
    if (isa_and_nonnull<ClockMuxOp>(producer)) {
      if (binding->getNumOperands() != 2 || text(domain, "reset_policy") == "none" ||
          text(domain, "reset_policy") == "sync")
        return reject(binding, "every mux-clock consumer requires common asynchronous reset");
      auto roots = inspectReset(producer->getOperand(3), producer, domains);
      auto reset = inspectReset(binding->getOperand(1), binding, domains);
      if (failed(roots) || failed(reset) || !sameRoots(*roots, *reset))
        return reject(binding, "mux-clock consumer must retain the exact common assertion roots");
      for (const auto &root : reset->roots)
        if (!root.release || !isa<ResetSyncOp>(root.release) ||
            !sameClock(root.clock, root.clockUse, binding->getOperand(0), binding) ||
            root.edge != domain->getAttr("edge"))
          return reject(binding, "mux-clock consumer requires safe actual output-clock release");
    }
    if (isa_and_nonnull<ClockGateOp, ClockMuxOp>(producer))
      for (unsigned i = 0; i != (isa<ClockMuxOp>(producer) ? 2u : 1u); ++i)
        pending.push_back(producer->getOperand(i));
  }
  return success();
}
} // namespace nodal
