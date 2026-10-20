#include "nodal/Dialect/Nodal/SequentialModel.h"

#include "mlir/IR/IRMapping.h"
#include "mlir/IR/SymbolTable.h"
#include "mlir/IR/Verifier.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/DigitalValueModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/EnumSwitchModel.h"
#include "nodal/Dialect/Nodal/EnumValueModel.h"
#include "nodal/Dialect/Nodal/FlatFSMModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/MemoryModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"
#include "nodal/Dialect/Nodal/StaticPredicateModel.h"
#include "nodal/Dialect/Nodal/StructuralIfModel.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/MapVector.h"
#include "llvm/ADT/SetVector.h"
#include "llvm/ADT/StringSet.h"

#include <functional>
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
bool canonical(StringRef s) { return !s.empty() && s.size() <= 4096 && s == s.trim(); }
LogicalResult reject(Operation *op, StringRef reason) {
  return op->emitOpError("NODAL-SEQUENTIAL-056-001: ") << reason;
}
bool closed(Operation *op, ArrayRef<StringRef> names) {
  return llvm::all_of(op->getAttrs(), [&](NamedAttribute a) {
    return llvm::is_contained(names, a.getName().getValue());
  });
}
bool boolean(Type type) {
  auto b = llvm::dyn_cast<BitsType>(type);
  return b && b.getWidth() == 1;
}
bool predicate(Type type) { return boolean(type) || llvm::isa<StaticPredicateType>(type); }
FailureOr<Value> stateRoot(Value value, Operation *use) {
  auto projection = resolveStaticShapeProjection(value, use);
  if (failed(projection) || !projection->projections.empty())
    return failure();
  Operation *op = projection->root.getDefiningOp();
  if (!llvm::isa_and_nonnull<DigitalStorageOp>(op) || text(op, "kind") != "register")
    return failure();
  return projection->root;
}
LogicalResult checkPredicate(Value value, Operation *use) {
  if (!predicate(value.getType()) || failed(verifyDigitalValueScope(value, use)))
    return reject(use, "behavioral guard requires an owned Bool or checked static predicate");
  if (llvm::isa<StaticPredicateType>(value.getType()) &&
      failed(analyzeBehavioralStaticPredicate(value, use)))
    return reject(use, "behavioral static guard lacks an actual bounded parameter predicate");
  return success();
}
bool allowedPure(Operation *op) {
  StringRef n = op->getName().getStringRef();
  return n == "nodal.digital_expr" || n == "nodal.enum_expr" || n == "nodal.constant" ||
         n == "nodal.const_literal" || n == "nodal.const_expr" ||
         n == "nodal.const_parameter_ref" || n == "nodal.static_compare" ||
         n == "nodal.static_logic" || n == "nodal.behavioral_predicate" ||
         n == "nodal.shape_index" || n == "nodal.shape_slice" || n == "nodal.shape_reshape" ||
         n == "nodal.shape_flatten";
}
LogicalResult checkRegister(Operation *reg, Operation *domain) {
  if (failed(verifyDigitalStorageOperation(reg)))
    return failure();
  if (reg->getNumOperands() && text(domain, "reset_policy") == "none")
    return reject(reg, "initialized register requires a physical reset; hidden boot initialization "
                       "is unsupported");
  return success();
}
} // namespace

FailureOr<SequentialDomainControls> inspectSequentialDomainControls(Operation *use,
                                                                    Attribute reference) {
  Operation *module = isa_and_nonnull<ModuleOp>(use) ? use
                      : use                          ? use->getParentOfType<ModuleOp>()
                                                     : nullptr;
  if (!module || !reference)
    return failure();
  DomainModel domains(module);
  auto binding = domains.resolveBinding(reference);
  if (succeeded(binding)) {
    auto domain = domains.resolveDomain(reference);
    if (failed(domain))
      return failure();
    SequentialDomainControls result;
    result.module = module;
    result.declaration = *domain;
    result.binding = *binding;
    result.clock = (*binding)->getOperand(0);
    if ((*binding)->getNumOperands() == 2) {
      result.reset = (*binding)->getOperand(1);
      result.resetAvailability = SequentialResetAvailability::Present;
    }
    return result;
  }
  Operation *requirement = domains.unresolvedRequirement(reference);
  if (!requirement || !hasLocalHierarchyObligations(module))
    return failure();
  SequentialDomainControls result;
  result.module = module;
  result.declaration = requirement;
  result.resetAvailability = SequentialResetAvailability::Contextual;
  for (Operation &candidate : module->getRegion(0).front()) {
    if (!isa<RequirementClockOp, RequirementResetOp>(candidate) ||
        candidate.getAttr("requirement") != reference)
      continue;
    if (failed(verifyRequirementProjection(&candidate)))
      return failure();
    // Queries do not move declarations or manufacture a dominating SSA value.
    if (use != module) {
      Operation *anchor = use;
      while (anchor && anchor->getParentOp() != module)
        anchor = anchor->getParentOp();
      if (!anchor || anchor == &candidate || !candidate.isBeforeInBlock(anchor))
        continue;
    }
    Value &slot = isa<RequirementClockOp>(candidate) ? result.clock : result.reset;
    if (!slot)
      slot = candidate.getResult(0);
  }
  return result;
}
bool matchesSequentialDomainClock(Value clock, const SequentialDomainControls &controls) {
  if (!controls.contextual())
    return clock && clock == controls.clock;
  Operation *projection = clock ? clock.getDefiningOp() : nullptr;
  return isa_and_nonnull<RequirementClockOp>(projection) &&
         projection->getParentOp() == controls.module &&
         projection->getAttr("requirement") ==
             FlatSymbolRefAttr::get(controls.declaration->getContext(),
                                    text(controls.declaration, "sym_name")) &&
         succeeded(verifyRequirementProjection(projection));
}
FailureOr<Value> getOrCreateSequentialDomainClock(Operation *use, OpBuilder &builder,
                                                  DictionaryAttr metadata) {
  auto controls = inspectSequentialDomainControls(use, use ? use->getAttr("domain") : Attribute());
  if (failed(controls))
    return failure();
  if (controls->clock)
    return controls->clock;
  if (!controls->contextual())
    return failure();
  OpBuilder::InsertionGuard guard(builder);
  builder.setInsertionPointToStart(&controls->module->getRegion(0).front());
  OperationState projection(use->getLoc(), "nodal.requirement_clock");
  projection.addTypes(ControlCapabilityType::get(use->getContext(), "clock"));
  projection.addAttribute("requirement", use->getAttr("domain"));
  projection.addAttribute("metadata", metadata);
  return builder.create(projection)->getResult(0);
}

FailureOr<BehavioralIfHeader> inspectBehavioralIfHeader(Operation *op) {
  if (!llvm::isa_and_nonnull<BehavioralIfOp>(op) || op->getNumRegions() != 2 ||
      op->getNumResults() || op->getNumSuccessors() || !op->getNumOperands() ||
      op->getNumOperands() > 8193 || !predicate(op->getOperand(0).getType()) ||
      text(op, "contract_version") != "behavioral-if-v1" || !canonical(text(op, "region_id")) ||
      path(op) != text(op, "region_id") ||
      !closed(op, {"operandSegmentSizes", "contract_version", "region_id", "metadata"}))
    return failure();
  Operation *m = op->getParentOfType<nodal::ModuleOp>();
  if (!m || !llvm::isa<ModuleOp, BehavioralIfOp, FlatFSMOp, EnumSwitchOp>(op->getParentOp()))
    return failure();
  StringRef modulePath = path(m).empty() ? text(m, "sym_name") : path(m);
  if (!text(op, "region_id").starts_with((modulePath + ".").str()))
    return failure();
  unsigned depth = 0;
  for (Operation *at = op; at != m; at = at->getParentOp())
    if (!at || ++depth > 128)
      return failure();
  auto sizes = op->getAttrOfType<DenseI32ArrayAttr>("operandSegmentSizes");
  if (!sizes || sizes.size() != 3 || sizes[0] != 1 || sizes[1] < 0 || sizes[2] < 0 ||
      uint64_t(1) + sizes[1] + sizes[2] != op->getNumOperands())
    return failure();
  BehavioralIfHeader h;
  h.operation = op;
  h.module = m;
  h.captureOffsets[0] = 1;
  h.captureOffsets[1] = 1 + sizes[1];
  for (unsigned arm = 0; arm < 2; ++arm) {
    if (!op->getRegion(arm).hasOneBlock())
      return failure();
    h.bodies[arm] = &op->getRegion(arm).front();
    h.captureCounts[arm] = sizes[arm + 1];
    if (h.bodies[arm]->getNumArguments() != h.captureCounts[arm])
      return failure();
    for (unsigned i = 0; i < h.captureCounts[arm]; ++i)
      if (h.bodies[arm]->getArgument(i).getType() !=
          op->getOperand(h.captureOffsets[arm] + i).getType())
        return failure();
  }
  return h;
}
FailureOr<Value> behavioralCaptureSource(BlockArgument arg) {
  auto h = inspectBehavioralIfHeader(arg.getOwner()->getParentOp());
  if (failed(h))
    return failure();
  unsigned arm = arg.getOwner() == h->bodies[0] ? 0 : 1;
  if (arg.getOwner() != h->bodies[arm] || arg.getArgNumber() >= h->captureCounts[arm])
    return failure();
  return h->operation->getOperand(h->captureOffsets[arm] + arg.getArgNumber());
}
namespace detail {
class BehavioralIntegerEnvironmentAdmission {
public:
  static FailureOr<ParameterIntegerEnvironment> create(const BehavioralIfHeader &h, unsigned arm,
                                                       Operation *use,
                                                       const ParameterIntegerEnvironment *outer) {
    SmallVector<ParameterIntegerBinding, 8> bindings;
    for (BlockArgument arg : h.bodies[arm]->getArguments()) {
      if (!arg.getType().isSignlessInteger(64))
        continue;
      auto source = behavioralCaptureSource(arg);
      if (failed(source) || failed(verifyDigitalValueScope(*source, h.operation)))
        return failure();
      auto facts = probeBehavioralIntegerCapture(*source, h.operation);
      // Runtime Integer captures are storage values, never static proof bindings.
      if (failed(facts) || !facts->expression)
        continue;
      bindings.push_back({arg, *facts->expression, *source});
    }
    return ParameterIntegerEnvironment::create(
        h.module, h.bodies[arm], use, outer ? outer->instance() : nullptr, bindings, {}, outer);
  }
};
} // namespace detail
FailureOr<SmallVector<ParameterIntegerEnvironment, 4>>
behavioralIntegerContextsAtUse(Operation *use) {
  auto h = use ? inspectBehavioralIfHeader(use->getParentOp())
               : FailureOr<BehavioralIfHeader>(failure());
  if (failed(h))
    return failure();
  unsigned arm = use->getBlock() == h->bodies[0] ? 0 : 1;
  SmallVector<ParameterIntegerEnvironment, 4> result;
  if (isDigitalRegionOwner(h->operation->getParentOp())) {
    auto outer = digitalIntegerContextsAtUse(h->operation);
    if (failed(outer) || outer->size() != 1)
      return failure();
    auto context =
        detail::BehavioralIntegerEnvironmentAdmission::create(*h, arm, use, &outer->front());
    if (failed(context))
      return failure();
    result.push_back(*context);
  } else {
    auto context = detail::BehavioralIntegerEnvironmentAdmission::create(*h, arm, use, nullptr);
    if (failed(context))
      return failure();
    result.push_back(*context);
  }
  return result;
}
LogicalResult verifyBehavioralPredicate(Operation *op) {
  if (!llvm::isa_and_nonnull<BehavioralPredicateOp>(op) || op->getNumOperands() != 1 ||
      op->getNumResults() != 1 || !boolean(op->getResult(0).getType()) || op->getNumRegions() ||
      op->getNumSuccessors() || !closed(op, {"metadata"}) ||
      !llvm::isa<StaticPredicateType>(op->getOperand(0).getType()))
    return reject(
        op, "static behavioral conversion requires one actual predicate and exact Bool result");
  return checkPredicate(op->getOperand(0), op);
}
LogicalResult verifyBehavioralIfOperation(Operation *op) {
  auto h = inspectBehavioralIfHeader(op);
  if (failed(h))
    return reject(op, "behavioral choice requires the exact owned capture/region contract");
  if (failed(checkPredicate(op->getOperand(0), op)))
    return failure();
  for (Value capture : op->getOperands().drop_front())
    if (failed(verifyDigitalValueScope(capture, op)))
      return reject(op, "behavioral captures must retain actual lexical ownership");
  for (Block *body : h->bodies)
    if (failed(verifySequentialBody(body)))
      return failure();
  return success();
}
LogicalResult verifySequentialBody(Block *body) {
  for (Operation &nested : *body) {
    if (llvm::isa<BehavioralIfOp, EnumSwitchOp>(nested))
      continue;
    if (llvm::isa<StructuralIfOp, CollectionOp>(nested)) {
      // Existing value-region owners retain all type/shape/proof obligations.
      // Behavioral use admits only a pure result, never structural writes.
      bool pure = nested.getNumResults() == 1;
      nested.walk([&](Operation *child) {
        if (llvm::isa<DigitalAssignOp, MemoryWriteOp, MemoryWriteEffectOp, MemoryReadOp,
                      BehavioralIfOp, DigitalStorageOp, LocalDomainBindingOp>(child))
          pure = false;
      });
      if (!pure ||
          failed(llvm::isa<StructuralIfOp>(nested) ? verifyStructuralIfOperation(&nested)
                                                   : verifyDigitalCollectionOperation(&nested)))
        return reject(&nested, "behavioral value region must retain a verified read-only result");
      continue;
    }
    if (llvm::isa<MemoryWriteOp>(nested)) {
      if (failed(verifyMemoryPort(&nested)))
        return failure();
    } else if (llvm::isa<DigitalAssignOp>(nested)) {
      if (text(&nested, "kind") != "register_next")
        return reject(&nested, "behavioral arms update only domain-owned registers");
    } else if (!allowedPure(&nested) || nested.getNumRegions() || nested.getNumSuccessors() ||
               nested.getNumResults() != 1)
      return reject(&nested, "behavioral arms cannot create structure or untracked effects");
  }
  return success();
}
LogicalResult verifyResetAsserted(Operation *op) {
  if (!llvm::isa_and_nonnull<ResetAssertedOp>(op) || !llvm::isa<ModuleOp>(op->getParentOp()) ||
      op->getNumOperands() > 1 || op->getNumResults() != 1 ||
      !boolean(op->getResult(0).getType()) || op->getNumRegions() || op->getNumSuccessors() ||
      !closed(op, {"domain", "reset_polarity", "metadata"}) || !canonical(path(op)))
    return reject(op, "reset assertion requires exact actual Reset and Bool result");
  auto controls = inspectSequentialDomainControls(op, op->getAttr("domain"));
  if (failed(controls))
    return reject(op, "reset assertion requires a verified local binding or exact requirement");
  if (controls->contextual())
    return op->getNumOperands() == 0 && !op->hasAttr("reset_polarity")
               ? success()
               : reject(op, "formal reset assertion retains only its exact requirement identity");
  if (controls->resetAvailability != SequentialResetAvailability::Present ||
      op->getNumOperands() != 1 || op->getOperand(0) != controls->reset ||
      op->getAttr("reset_polarity") != controls->binding->getAttr("reset_polarity") ||
      failed(verifyDigitalValueScope(op->getOperand(0), op)))
    return reject(op, "reset assertion must retain its exact physical binding and polarity");
  return success();
}
LogicalResult verifySequentialAssertion(Operation *op) {
  if (!llvm::isa_and_nonnull<SequentialAssertOp>(op) || !llvm::isa<ModuleOp>(op->getParentOp()) ||
      op->getNumOperands() != 2 || op->getNumResults() || op->getNumRegions() ||
      op->getNumSuccessors() || !boolean(op->getOperand(0).getType()) ||
      !canonical(text(op, "root_id")) || !closed(op, {"domain", "edge", "root_id", "metadata"}) ||
      !canonical(path(op)))
    return reject(op, "sampled assertion requires actual Bool, Clock and source identity");
  auto controls = inspectSequentialDomainControls(op, op->getAttr("domain"));
  if (failed(controls) || !matchesSequentialDomainClock(op->getOperand(1), *controls) ||
      (controls->contextual() ? op->hasAttr("edge")
                              : op->getAttr("edge") != controls->declaration->getAttr("edge")))
    return reject(op, "sampled assertion requires its exact physical domain clock and edge");
  for (Value operand : op->getOperands())
    if (failed(verifyDigitalValueScope(operand, op)))
      return reject(op, "sampled assertion operands must retain actual lexical ownership");
  return success();
}

LogicalResult verifySequentialSelect(Operation *op) {
  if (!llvm::isa_and_nonnull<SequentialSelectOp>(op) || op->getNumOperands() != 3 ||
      op->getNumResults() != 1 || op->getNumRegions() || op->getNumSuccessors() ||
      !llvm::isa<ModuleOp>(op->getParentOp()) || !closed(op, {"metadata"}))
    return reject(op, "normalized priority select requires exact owned SSA operands");
  if (failed(checkPredicate(op->getOperand(0), op)))
    return failure();
  Type type = op->getResult(0).getType();
  if (failed(inferDigitalStorageMaximumBits(type, op)))
    return failure();
  for (Value value : op->getOperands().drop_front())
    if (!equivalentDigitalStorageTypes(value.getType(), type, op->getParentOp()) ||
        failed(verifyDigitalValueScope(value, op)))
      return reject(op, "priority alternatives require exact owned recursive state type");
  return success();
}
LogicalResult verifySequentialUpdate(Operation *op) {
  if (!llvm::isa_and_nonnull<SequentialUpdateOp>(op) || op->getNumResults() ||
      op->getNumRegions() || op->getNumSuccessors() ||
      (op->getNumOperands() != 3 && op->getNumOperands() != 4 && op->getNumOperands() != 5) ||
      !llvm::isa<ModuleOp>(op->getParentOp()) || !canonical(text(op, "root_id")) ||
      !closed(op, {"domain", "edge", "reset_policy", "reset_polarity", "initializer_policy",
                   "root_id", "metadata"}))
    return reject(op,
                  "clocked update requires explicit typed clock/reset and stable root identity");
  auto root = stateRoot(op->getOperand(0), op);
  if (failed(root) || *root != op->getOperand(0))
    return reject(op, "clocked update requires its exact local state declaration");
  const DomainModel domains(op);
  Operation *reg = root->getDefiningOp();
  Operation *binding = domains.findLocalBinding(reg->getAttr("domain"));
  bool initialized = reg->getNumOperands() == 1;
  bool conditional = text(reg, "initializer_policy") == "if_reset";
  if (op->getAttr("domain") != reg->getAttr("domain") ||
      op->getAttr("initializer_policy") != reg->getAttr("initializer_policy") ||
      op->getNumOperands() != (conditional   ? 4u
                               : initialized ? 5u
                                             : 3u) ||
      (initialized && op->getOperand(conditional ? 3 : 4) != reg->getOperand(0)))
    return reject(
        op, "clock/reset/reset-value fields must exactly match their owned domain and register");
  if (binding) {
    if (conditional || failed(verifyLocalDomainBinding(binding)) ||
        failed(checkRegister(reg, *domains.resolveDomain(binding->getAttr("domain")))))
      return reject(op, "clocked update requires a verified physical local binding");
    Operation *domain = *domains.resolveDomain(binding->getAttr("domain"));
    if (text(op, "edge") != text(domain, "edge") ||
        text(op, "reset_polarity") != text(binding, "reset_polarity") ||
        text(op, "reset_policy") !=
            (initialized ? text(domain, "reset_policy") : StringRef("none")) ||
        op->getOperand(2) != binding->getOperand(0) ||
        (initialized && op->getOperand(3) != binding->getOperand(1)))
      return reject(
          op, "clock/reset/reset-value fields must exactly match their owned domain and register");
  } else {
    // A reusable definition retains local formal operands. Material semantics
    // are deliberately absent and must be discharged for every root occurrence.
    if (!domains.unresolvedRequirement(reg->getAttr("domain")) ||
        !hasLocalHierarchyObligations(op->getParentOp()) || op->hasAttr("edge") ||
        op->hasAttr("reset_policy") || op->hasAttr("reset_polarity") ||
        failed(verifyDigitalStorageOperation(reg)))
      return reject(op, "formal clocked update requires an unresolved local requirement and no "
                        "guessed material clock/reset fields");
    auto projection = [&](Value value, bool reset) {
      Operation *producer = value.getDefiningOp();
      return producer &&
             (reset ? isa<RequirementResetOp>(producer) : isa<RequirementClockOp>(producer)) &&
             producer->getAttr("requirement") == reg->getAttr("domain") &&
             producer->getParentOp() == op->getParentOp() &&
             succeeded(verifyRequirementProjection(producer));
    };
    if (!projection(op->getOperand(2), false) ||
        (initialized && !conditional && !projection(op->getOperand(3), true)))
      return reject(op, "formal clocked update must use its exact local requirement projections");
  }
  if (!equivalentDigitalStorageTypes(root->getType(), op->getOperand(1).getType(),
                                     op->getParentOp()))
    return reject(op, "clocked next state must retain the exact recursive state type");
  for (Value operand : op->getOperands())
    if (failed(verifyDigitalValueScope(operand, op)))
      return reject(op, "clocked update cannot use foreign or escaped values");
  return success();
}
namespace {
struct SampleSource {
  Attribute domain;
  bool boundary = false;
  Operation *identity = nullptr;
  bool permitsUnannotated = false;
};
/// One source classification for sampling and direct reconvergence. The physical
/// graph already owns dependency edges, captures, projections and actual writers.
SampleSource sampleSource(Operation *module, const DigitalDependencyNode &node) {
  if (node.hierarchyBoundary)
    return {};
  if (node.sourceDomain)
    return {node.sourceDomain, true,
            !node.waiver && node.value ? node.value.getDefiningOp() : nullptr};
  if (!node.value)
    return {};
  if (auto *reset = node.value.getDefiningOp(); llvm::isa_and_nonnull<ResetAssertedOp>(reset))
    return {reset->getAttr("domain"), true, reset};
  auto access = resolveStaticShapeProjection(node.value, node.use);
  Operation *source = succeeded(access) ? access->root.getDefiningOp() : nullptr;
  if ((llvm::isa_and_nonnull<DigitalStorageOp>(source) && text(source, "kind") == "register") ||
      llvm::isa_and_nonnull<MemoryReadOp>(source))
    return {source->getAttr("domain"), true, source};
  if (llvm::isa_and_nonnull<PortValueOp>(source)) {
    auto ref = source->getAttrOfType<FlatSymbolRefAttr>("port");
    Operation *owner = source->getParentOfType<ModuleOp>();
    Operation *port = ref ? SymbolTable::lookupSymbolIn(owner, ref) : nullptr;
    // Outputs are observations of actual writers, never independent attributed roots.
    if (port && text(port, "direction") == "output")
      return {};
    return {port ? port->getAttr("domain") : Attribute(), true, port, true};
  }
  // A source-less Interface boundary is not attributed merely by having a type.
  if (node.dependencies.empty() &&
      llvm::isa_and_nonnull<MemberAccessOp, NetReadOp>(node.value.getDefiningOp()))
    return {{}, true, node.value.getDefiningOp(), true};
  return {};
}
LogicalResult verifyDirectCrossingReconvergence(Operation *module,
                                                const DigitalDependencyGraph &graph,
                                                ArrayRef<SequentialSample> samples,
                                                ArrayRef<unsigned> roots, unsigned &work,
                                                unsigned maximumWork) {
  if (graph.crossings.size() < 2)
    return success();
  struct Group {
    Operation *use;
    SmallVector<unsigned, 4> roots;
  };
  SmallVector<Group, 8> groups;
  llvm::DenseMap<std::pair<std::pair<unsigned, Operation *>, Attribute>, unsigned> ownersByDomain;
  llvm::DenseSet<std::pair<unsigned, Attribute>> standaloneRoots;
  for (unsigned i = 0; i < samples.size(); ++i) {
    auto sample = samples[i];
    if (sample.group) {
      auto entry = ownersByDomain.try_emplace({{sample.occurrence, sample.group}, sample.domain},
                                              groups.size());
      if (entry.second)
        groups.push_back({sample.use, {}});
      groups[entry.first->second].roots.push_back(roots[i]);
    } else if (standaloneRoots.insert({roots[i], sample.domain}).second) {
      groups.push_back({sample.use, {roots[i]}});
    }
  }
  for (const Group &group : groups) {
    llvm::DenseSet<unsigned> visited;
    llvm::DenseSet<unsigned> boundaries;
    SmallVector<unsigned, 8> pending(group.roots.begin(), group.roots.end());
    while (!pending.empty()) {
      unsigned id = pending.pop_back_val();
      if (!visited.insert(id).second)
        continue;
      if (++work > maximumWork)
        return reject(group.use, "crossing reconvergence proof exceeds finite budget");
      const auto &node = graph.nodes[id];
      if (node.crossing)
        boundaries.insert(node.crossingIndex);
      else
        pending.append(node.dependencies.begin(), node.dependencies.end());
    }
    if (boundaries.size() < 2)
      continue;
    // An intersection of attributed input cones is a causal reconvergence, not
    // a coincidence of constants or equal clock frequencies. Store one owner
    // per actual leaf; never copy transitive provenance sets into value nodes.
    llvm::DenseMap<std::pair<unsigned, Operation *>, unsigned> owners;
    for (unsigned index : boundaries) {
      visited.clear();
      for (const auto &sample : graph.crossings[index].samples)
        pending.push_back(sample.root);
      while (!pending.empty()) {
        unsigned id = pending.pop_back_val();
        if (!visited.insert(id).second)
          continue;
        if (++work > maximumWork)
          return reject(group.use, "crossing reconvergence proof exceeds finite budget");
        const auto &node = graph.nodes[id];
        auto source = sampleSource(module, node);
        if (source.boundary && source.domain && source.identity) {
          auto inserted =
              owners.try_emplace(std::make_pair(node.occurrence, source.identity), index);
          if (!inserted.second && inserted.first->second != index)
            return group.use->emitOpError(
                "NODAL-CDC-057-002: independent synchronizers of a shared actual source "
                "reconverge in one sample");
        }
        pending.append(node.dependencies.begin(), node.dependencies.end());
        // Stateful samples are provenance edges, never combinational edges.
        // Retain original roots through upstream chains (and stateless aliases)
        // without changing their domain-permission or physical cycle cuts.
        if (node.crossing)
          for (const auto &sample : graph.crossings[node.crossingIndex].samples)
            pending.push_back(sample.root);
      }
    }
  }
  return success();
}
} // namespace
LogicalResult verifyClockControlSamples(Operation *module, ArrayRef<ClockControlSample> samples,
                                        SequentialModelLimits limits) {
  SmallVector<std::pair<Value, Operation *>, 2> seeds;
  for (auto sample : samples)
    seeds.emplace_back(sample.value, sample.use);
  DigitalAssignmentProofLimits graphLimits;
  graphLimits.maximumNodes = limits.maximumOperations;
  auto graph = analyzeDigitalAssignmentDependencies(module, seeds, graphLimits);
  if (failed(graph))
    return failure();
  DomainModel domains(module);
  unsigned work = 0;
  for (unsigned i = 0; i != samples.size(); ++i) {
    auto sample = samples[i];
    auto kind = dyn_cast<StringAttr>(sample.source);
    bool constant = kind && kind.getValue() == "constant";
    bool external = kind && kind.getValue() == "external";
    bool attributed = false;
    llvm::DenseSet<Operation *> externalSources;
    llvm::DenseSet<unsigned> visited;
    SmallVector<unsigned> pending{graph->roots[i]};
    while (!pending.empty()) {
      unsigned id = pending.pop_back_val();
      if (!visited.insert(id).second)
        continue;
      if (++work > limits.maximumOperations)
        return reject(sample.use, "clock control sampling exceeds finite graph budget");
      const auto &node = graph->nodes[id];
      if (node.eventProcess || node.eventTrigger)
        return sample.use->emitOpError(
            "NODAL-EVENT-057-001: event state or trigger cannot become ordinary clock control");
      if (node.crossing && text(node.crossing, "kind") == "pulse")
        return sample.use->emitOpError(
            "NODAL-CDC-003: Pulse crossing cannot masquerade as clock control");
      auto source = sampleSource(module, node);
      if (source.boundary) {
        auto ref = dyn_cast_if_present<FlatSymbolRefAttr>(source.domain);
        bool unannotated =
            source.permitsUnannotated &&
            (!ref || (ref.getValue() == "none" && !SymbolTable::lookupSymbolIn(module, ref)));
        if (constant || (external && (!unannotated || !isa_and_nonnull<PortOp>(source.identity))) ||
            (!external &&
             (unannotated || !domains.matchesActualDomain(source.domain, sample.source))))
          return sample.use->emitOpError(
              "NODAL-CDC-001: clock control has mixed, foreign or forged provenance");
        if (external) {
          auto type = source.identity->getAttrOfType<TypeAttr>("type");
          if (!type || !boolean(type.getValue()) || text(source.identity, "direction") != "input")
            return sample.use->emitOpError(
                "NODAL-CDC-001: external clock control requires an owned Bool input boundary");
          externalSources.insert(source.identity);
        } else {
          attributed = true;
        }
      }
      pending.append(node.dependencies.begin(), node.dependencies.end());
    }
    if ((external && externalSources.size() != 1) || (!external && !constant && !attributed))
      return sample.use->emitOpError(
          "NODAL-CDC-001: clock control contract lacks actual source evidence");
  }
  return success();
}
LogicalResult
verifySequentialSampleDependencies(Operation *module, ArrayRef<SequentialSample> samples,
                                   const DigitalDependencyGraph &graph, ArrayRef<unsigned> roots,
                                   SequentialModelLimits limits, unsigned alreadyCharged) {
  if (samples.size() != roots.size())
    return reject(module, "sampling roots must exactly match their physical dependency graph");
  unsigned work = alreadyCharged;
  bool hasEventState =
      llvm::any_of(graph.nodes, [](const auto &node) { return node.eventProcess != nullptr; });
  const DomainModel domains(module);
  using SampleKey = std::pair<unsigned, std::pair<unsigned, Attribute>>;
  llvm::DenseSet<SampleKey> visited;
  llvm::DenseSet<SampleKey> strictChecked[2];
  llvm::DenseMap<std::pair<std::pair<unsigned, Operation *>, Attribute>, bool> attributedGroups;
  for (unsigned i = 0; i < samples.size(); ++i) {
    auto sample = samples[i];
    if (roots[i] >= graph.nodes.size())
      return reject(sample.use, "sampling root is outside the physical dependency graph");
    if (sample.requireAttributedSource && !sample.allowDomainlessSource &&
        !sample.requireAttributedGroup &&
        !strictChecked[sample.rejectPulseSources]
             .insert({roots[i], {sample.occurrence, sample.domain}})
             .second)
      continue;
    // Permission and classification are distinct traversals of the same graph.
    // A stateless waiver changes one boundary permission, never Pulse lineage.
    if (sample.rejectPulseSources || (!sample.observationOnly && hasEventState)) {
      llvm::DenseSet<unsigned> provenanceVisited;
      SmallVector<unsigned, 8> provenance{roots[i]};
      while (!provenance.empty()) {
        unsigned id = provenance.pop_back_val();
        if (!provenanceVisited.insert(id).second)
          continue;
        if (id >= graph.nodes.size() || ++work > limits.maximumOperations)
          return reject(sample.use, "crossing classification proof exceeds finite graph budget");
        const auto &node = graph.nodes[id];
        if (node.eventProcess)
          return sample.use->emitOpError("NODAL-EVENT-057-001: event-owned state requires a "
                                         "separately supported sampling boundary");
        if (sample.rejectPulseSources && node.crossing && text(node.crossing, "kind") == "pulse")
          return sample.use->emitOpError(
              "NODAL-CDC-003: Pulse-classified crossing result cannot be "
              "relabeled as a non-pulse crossing");
        provenance.append(node.dependencies.begin(), node.dependencies.end());
      }
    }
    llvm::DenseSet<unsigned> strictVisited;
    bool attributed = false, unresolved = false;
    SmallVector<unsigned, 8> pending{roots[i]};
    while (!pending.empty()) {
      unsigned id = pending.pop_back_val();
      if (id >= graph.nodes.size())
        return reject(sample.use, "sampling dependency is outside its physical graph");
      if (sample.requireAttributedSource
              ? !strictVisited.insert(id).second
              : !visited.insert({id, {sample.occurrence, sample.domain}}).second)
        continue;
      if (++work > limits.maximumOperations)
        return reject(sample.use, "sequential dependency proof exceeds finite operation budget");
      const auto &node = graph.nodes[id];
      if (!graph.hierarchy && node.hierarchyBoundary) {
        unresolved = true;
        continue; // Exact instance output is an explicit closed-root obligation.
      }
      auto source = sampleSource(module, node);
      if (source.boundary) {
        auto reference = llvm::dyn_cast_if_present<FlatSymbolRefAttr>(source.domain);
        bool unannotated =
            source.permitsUnannotated &&
            (!reference ||
             (reference.getValue() == "none" &&
              !SymbolTable::lookupSymbolIn(
                  graph.hierarchy ? graph.hierarchy->occurrences()[node.occurrence].module : module,
                  reference)));
        if (sample.requireAttributedSource && unannotated)
          return sample.use->emitOpError(
              "NODAL-CDC-001: crossing input contains an unannotated source boundary");
        bool permitted = false;
        bool formal = !graph.hierarchy && hasLocalHierarchyObligations(module) &&
                      (domains.unresolvedRequirement(source.domain) ||
                       domains.unresolvedRequirement(sample.domain));
        unresolved |= formal;
        if (graph.hierarchy) {
          auto origin = graph.hierarchy->resolveDomain(node.occurrence, source.domain);
          auto receiver = graph.hierarchy->resolveDomain(sample.occurrence, sample.domain);
          permitted = succeeded(origin) && succeeded(receiver) &&
                      (sample.requireAttributedSource
                           ? *origin == *receiver
                           : graph.hierarchy->allowsDirectSampling(*origin, *receiver));
        } else {
          permitted = sample.requireAttributedSource
                          ? domains.matchesActualDomain(source.domain, sample.domain)
                          : domains.allowsDirectSampling(source.domain, sample.domain);
        }
        if (!sample.observationOnly && !formal && !unannotated && !permitted) {
          // Keep established diagnostic distinctions while sharing the same
          // actual-source classification and domain proof for every consumer.
          if (llvm::isa_and_nonnull<PortOp>(source.identity))
            return sample.use->emitOpError("NODAL-CDC-001: foreign or unresolved input domain is "
                                           "sampled without an explicit crossing");
          if (llvm::isa_and_nonnull<DigitalStorageOp, MemoryReadOp>(source.identity))
            return sample.use->emitOpError(
                "NODAL-CDC-001: foreign-domain state is sampled without an explicit crossing");
          if (node.sourceDomain && !llvm::isa_and_nonnull<CrossingOp, AsyncFifoOp>(source.identity))
            return sample.use->emitOpError(
                "NODAL-CDC-001: foreign or unresolved Interface domain is "
                "sampled without an explicit crossing");
          return sample.use->emitOpError("NODAL-CDC-001: foreign or unresolved source domain is "
                                         "sampled without an explicit crossing");
        }
        if (sample.requireAttributedSource && node.crossing &&
            node.crossingRole == "source_ready") {
          // Ready is owned by this FIFO's source domain, but that declaration
          // cannot independently establish its own sampled source. Reuse the
          // retained payload/valid roots for attribution only: ordinary reads
          // still terminate at the stateful boundary, and strictVisited stops
          // self/mutual ready feedback without manufacturing an anchor.
          for (const auto &input : graph.crossings[node.crossingIndex].samples)
            if (input.role == "source_payload" || input.role == "source_valid")
              pending.push_back(input.root);
        } else {
          attributed |= !unannotated;
        }
      }
      // Waivers cut only ordinary domain permission. The physical graph retains
      // the operand edge for classification, reconvergence, cycles and drivers.
      if (!node.waiver)
        pending.append(node.dependencies.begin(), node.dependencies.end());
    }
    if (sample.requireAttributedGroup) {
      if (!sample.group || !sample.requireAttributedSource)
        return reject(sample.use, "attributed sample group requires an actual strict owner");
      attributedGroups[{{sample.occurrence, sample.group}, sample.domain}] |=
          attributed || unresolved;
    }
    if (sample.requireAttributedSource && !sample.allowDomainlessSource && !attributed &&
        !unresolved)
      return sample.use->emitOpError(
          "NODAL-CDC-001: crossing source requires actual attributed dependency evidence");
  }
  for (auto entry : attributedGroups)
    if (!entry.second)
      return entry.first.first.second->emitOpError(
          "NODAL-CDC-001: crossing source group requires actual attributed dependency evidence");
  return verifyDirectCrossingReconvergence(module, graph, samples, roots, work,
                                           limits.maximumOperations);
}
LogicalResult verifySequentialSamples(Operation *module, ArrayRef<SequentialSample> samples,
                                      SequentialModelLimits limits, unsigned alreadyCharged) {
  SmallVector<std::pair<Value, Operation *>, 8> seeds;
  for (auto sample : samples)
    seeds.push_back({sample.value, sample.use});
  DigitalAssignmentProofLimits graphLimits;
  graphLimits.maximumNodes = limits.maximumOperations;
  auto graph = analyzeDigitalAssignmentDependencies(module, seeds, graphLimits);
  if (failed(graph))
    return failure();
  SmallVector<SequentialSample, 8> allSamples(samples.begin(), samples.end());
  SmallVector<unsigned> roots(graph->roots.begin(), graph->roots.end());
  for (const auto &observation : graph->observations)
    for (unsigned root : observation.roots) {
      SequentialSample sample;
      sample.use = observation.use;
      sample.group = observation.use;
      sample.observationOnly = true;
      allSamples.push_back(sample);
      roots.push_back(root);
    }
  return verifySequentialSampleDependencies(module, allSamples, *graph, roots, limits,
                                            alreadyCharged);
}

namespace {
struct Model {
  Model(Operation *module, SequentialModelLimits limits)
      : module(module), limits(limits), domains(module) {}
  Operation *module;
  SequentialModelLimits limits;
  const DomainModel domains;
  llvm::DenseMap<Value, Operation *> roots;
  SmallVector<Operation *, 8> registers;
  unsigned work = 0;
  SmallVector<SequentialSample, 8> samples;
  LogicalResult sampled(Value value, Attribute domain, Operation *use, Operation *group = nullptr) {
    if (++work > limits.maximumOperations)
      return reject(use, "sequential sampling roots exceed finite operation budget");
    samples.push_back({value, domain, use, false, group});
    return success();
  }
  llvm::DenseMap<Block *, SmallVector<Value, 4>> actionControls;
  LogicalResult sampledControls(Operation *op, Attribute domain, Operation *group) {
    Block *body = op->getBlock();
    for (Operation *ancestor = op->getParentOp(); ancestor != module;
         body = ancestor->getBlock(), ancestor = ancestor->getParentOp()) {
      if (llvm::isa<BehavioralIfOp, EnumSwitchOp>(ancestor)) {
        if (failed(sampled(ancestor->getOperand(0), domain, ancestor, group)))
          return failure();
      } else if (llvm::isa<FlatFSMOp>(ancestor)) {
        auto controls = actionControls.find(body);
        if (controls == actionControls.end())
          return reject(ancestor, "FSM action lacks its actual phase control dependencies");
        for (Value value : controls->second)
          if (failed(sampled(value, domain, ancestor, group)))
            return failure();
      }
    }
    return success();
  }
  LogicalResult verifySamples() { return verifySequentialSamples(module, samples, limits, work); }
  LogicalResult verify() {
    if (!llvm::isa_and_nonnull<ModuleOp>(module) || module->getNumRegions() != 1 ||
        !module->getRegion(0).hasOneBlock())
      return failure();
    llvm::DenseSet<Attribute> bindings;
    for (Operation &op : module->getRegion(0).front()) {
      if (++work > limits.maximumOperations)
        return reject(&op, "sequential model operation budget exceeded");
      if (llvm::isa<LocalDomainBindingOp>(op)) {
        if (failed(verifyLocalDomainBinding(&op)))
          return failure();
        if (!bindings.insert(op.getAttr("domain")).second)
          return reject(&op, "one physical local binding is allowed per domain");
      }
    }
    for (Operation &op : module->getRegion(0).front())
      if (llvm::isa<DigitalStorageOp>(op) && text(&op, "kind") == "register") {
        if (Operation *binding = domains.findLocalBinding(op.getAttr("domain"))) {
          if (failed(checkRegister(&op, *domains.resolveDomain(binding->getAttr("domain")))))
            return failure();
          if (op.getNumOperands() &&
              failed(sampled(op.getOperand(0), op.getAttr("domain"), &op, &op)))
            return failure();
          registers.push_back(&op);
        } else if (domains.unresolvedRequirement(op.getAttr("domain")) &&
                   hasLocalHierarchyObligations(module)) {
          if (failed(verifyDigitalStorageOperation(&op)))
            return failure();
          if (op.getNumOperands() &&
              failed(sampled(op.getOperand(0), op.getAttr("domain"), &op, &op)))
            return failure();
          registers.push_back(&op);
        }
      }
    struct Scope {
      Block *body;
      Operation *root;
      unsigned depth;
    };
    SmallVector<Scope, 8> scopes{{&module->getRegion(0).front(), nullptr, 0}};
    llvm::StringSet<> identities;
    for (unsigned i = 0; i < scopes.size(); ++i) {
      Scope scope = scopes[i];
      for (Operation &op : *scope.body) {
        if (++work > limits.maximumOperations)
          return reject(&op, "sequential model operation budget exceeded");
        if (llvm::isa<FlatFSMOp>(op)) {
          auto h = inspectFlatFSMHeader(&op);
          if (failed(h) || failed(verifyFlatFSMOperation(&op)) ||
              scope.depth >= limits.maximumDepth)
            return failure();
          if (!identities.insert(text(&op, "machine_id")).second)
            return reject(&op, "FSM identities must be unique within their module");
          for (Block *body : h->bodies)
            if (!identities.insert(digitalRegionSemanticPath(body)).second)
              return reject(&op, "FSM action identities must be unique within their module");
          Value state = op.getOperand(0);
          for (unsigned index = 0; index < h->privateStateCount(); ++index) {
            auto inserted = roots.try_emplace(op.getOperand(index), &op);
            if (!inserted.second && inserted.first->second != &op)
              return reject(&op, "private FSM state has an unrelated writer");
          }
          for (unsigned index = h->privateStateCount(); index < h->guardOffset + h->guards; ++index)
            if (failed(sampled(op.getOperand(index), op.getAttr("domain"), &op,
                               state.getDefiningOp())))
              return failure();
          SmallVector<SmallVector<Value, 4>, 4> controls;
          if (failed(
                  flatFSMActionControlDependencies(&op, controls, work, limits.maximumOperations)))
            return failure();
          for (unsigned action = 0; action < h->bodies.size(); ++action) {
            actionControls.try_emplace(h->bodies[action], std::move(controls[action]));
            scopes.push_back({h->bodies[action], &op, scope.depth + 1});
          }
        } else if (llvm::isa<EnumSwitchOp>(op)) {
          auto h = inspectEnumSwitchHeader(&op);
          if (failed(h) || failed(verifyEnumSwitchOperation(&op)) ||
              scope.depth >= limits.maximumDepth)
            return failure();
          if (!identities.insert(text(&op, "region_id")).second)
            return reject(&op, "switch identities must be unique within their module");
          for (Block *body : h->bodies) {
            if (!identities.insert(digitalRegionSemanticPath(body)).second)
              return reject(&op, "switch arm identities must be unique within their module");
            scopes.push_back({body, scope.root ? scope.root : &op, scope.depth + 1});
          }
        } else if (llvm::isa<SequentialAssertOp>(op)) {
          if (failed(verifySequentialAssertion(&op)) ||
              failed(sampled(op.getOperand(0), op.getAttr("domain"), &op)))
            return failure();
        } else if (llvm::isa<BehavioralIfOp>(op)) {
          if (scope.depth >= limits.maximumDepth ||
              op.getNumOperands() - 1 > limits.maximumCaptures)
            return reject(&op, "behavioral region exceeds its finite depth/capture budget");
          if (failed(verifyBehavioralIfOperation(&op)))
            return failure();
          if (!identities.insert(text(&op, "region_id")).second)
            return reject(&op, "behavioral region identities must be unique within their module");
          auto h = inspectBehavioralIfHeader(&op);
          for (Block *body : h->bodies)
            scopes.push_back({body, scope.root ? scope.root : &op, scope.depth + 1});
        } else if (llvm::isa<MemoryReadOp, MemoryWriteOp, MemoryWriteEffectOp>(op)) {
          auto port = inspectMemoryPort(&op);
          if (failed(port))
            return failure();
          if (scope.root && llvm::isa<FlatFSMOp>(scope.root) &&
              op.getAttr("domain") != scope.root->getAttr("domain"))
            return reject(&op, "FSM memory effects must retain their machine's actual domain");
          unsigned values = llvm::isa<MemoryReadOp>(op)          ? 1
                            : llvm::isa<MemoryWriteEffectOp>(op) ? 4
                                                                 : 3;
          for (unsigned operand = 0; operand < values; ++operand)
            if (failed(sampled(op.getOperand(operand), op.getAttr("domain"), &op, &op)))
              return failure();
          if (failed(sampledControls(&op, op.getAttr("domain"), &op)))
            return failure();
        } else if ((llvm::isa<DigitalAssignOp>(op) && text(&op, "kind") == "register_next") ||
                   llvm::isa<SequentialUpdateOp>(op)) {
          if ((llvm::isa<DigitalAssignOp>(op) && op.getNumOperands() != 2) ||
              (llvm::isa<SequentialUpdateOp>(op) && op.getNumOperands() != 3 &&
               op.getNumOperands() != 4 && op.getNumOperands() != 5))
            return reject(&op, "sequential update operand arity is invalid");
          auto target = stateRoot(op.getOperand(0), &op);
          if (failed(target))
            return reject(&op, "sequential update must target one whole owned register");
          Operation *reg = target->getDefiningOp();
          if (scope.root && llvm::isa<FlatFSMOp>(scope.root)) {
            auto header = inspectFlatFSMHeader(scope.root);
            if (failed(header))
              return failure();
            for (unsigned index = 0; index < header->privateStateCount(); ++index)
              if (*target == scope.root->getOperand(index))
                return reject(&op, "an action cannot directly write private FSM storage");
          }
          if (scope.root && llvm::isa<FlatFSMOp>(scope.root) &&
              reg->getAttr("domain") != scope.root->getAttr("domain"))
            return reject(&op, "FSM actions must update registers in their actual owning domain");
          if (!domains.findLocalBinding(reg->getAttr("domain")) &&
              !(hasLocalHierarchyObligations(module) &&
                domains.unresolvedRequirement(reg->getAttr("domain")))) {
            if (scope.root || llvm::isa<SequentialUpdateOp>(op))
              return reject(
                  &op, "behavioral register update requires an actual local clock/reset binding");
            continue; // Existing F54 declaration/intent is not executable.
          }
          if (llvm::isa<SequentialUpdateOp>(op)) {
            if (failed(verifySequentialUpdate(&op)))
              return failure();
          } else if (failed(verifyDigitalAssignmentOperation(&op)))
            return failure();
          if (failed(sampled(op.getOperand(1), reg->getAttr("domain"), &op, reg)))
            return failure();
          if (failed(sampledControls(&op, reg->getAttr("domain"), reg)))
            return failure();
          Operation *root = scope.root ? scope.root : &op;
          auto inserted = roots.try_emplace(*target, root);
          if (!inserted.second && inserted.first->second != root)
            return op.emitOpError("NODAL-STATE-001: one state value has unrelated update roots");
        }
      }
    }
    return verifySamples();
  }
};
using StateMap = llvm::MapVector<Value, Value>;
class Normalizer {
public:
  Normalizer(Model &model) : model(model), builder(model.module->getContext()) {}
  LogicalResult run() {
    SmallVector<Operation *, 8> roots;
    llvm::DenseSet<Operation *> seen;
    // Authored block order, never DenseMap iteration, determines emitted order.
    for (Operation &op : model.module->getRegion(0).front())
      if (llvm::isa<BehavioralIfOp, MemoryWriteOp>(op) ||
          (llvm::isa<DigitalAssignOp>(op) && text(&op, "kind") == "register_next" &&
           (model.domains.findLocalBinding(op.getOperand(0).getDefiningOp()->getAttr("domain")) ||
            model.domains.unresolvedRequirement(
                op.getOperand(0).getDefiningOp()->getAttr("domain")))))
        roots.push_back(&op);
    llvm::DenseSet<Value> emitted;
    struct Pending {
      Value state;
      Value next;
      Operation *root;
    };
    SmallVector<Pending, 8> updates;
    for (Operation *root : roots) {
      builder.setInsertionPoint(root);
      IRMapping mapping;
      StateMap next;
      if (failed(operation(root, mapping, next, 0, Value(), root)))
        return failure();
      for (auto [state, value] : next) {
        updates.push_back({state, value, root});
        emitted.insert(state);
      }
    }
    builder.setInsertionPointToEnd(&model.module->getRegion(0).front());
    for (const auto &pending : updates)
      if (failed(update(pending.state, pending.next, pending.root)))
        return failure();
    for (Operation *root : roots)
      root->erase();
    for (Operation *reg : model.registers) {
      Value state = reg->getResult(0);
      auto found = model.roots.find(state);
      if (!emitted.contains(state) &&
          (found == model.roots.end() || !llvm::isa<SequentialUpdateOp>(found->second)))
        if (failed(update(state, state, nullptr)))
          return failure();
    }
    // A binding may materialize its actual clock after an authored write root.
    // Commit owned memory effects only after all value producers and register
    // updates, retaining their actual source-root/body sequence. Moving only
    // these result-free effects neither clones clocks nor reorders other owners.
    Block &body = model.module->getRegion(0).front();
    SmallVector<Operation *, 8> memoryEffects;
    for (Operation &op : body)
      if (llvm::isa<MemoryWriteEffectOp>(op))
        memoryEffects.push_back(&op);
    llvm::DenseMap<Attribute, int64_t> memoryOrder;
    for (Operation *op : memoryEffects) {
      op->setAttr("order", builder.getI64IntegerAttr(memoryOrder[op->getAttr("memory")]++));
      op->moveBefore(&body, body.end());
    }
    return success();
  }

private:
  Model &model;
  OpBuilder builder;
  unsigned generated = 0;
  LogicalResult charge(Operation *at) {
    if (++generated > model.limits.maximumNormalizedOperations)
      return reject(at, "normalized sequential SSA exceeds its finite expansion budget");
    return success();
  }
  DictionaryAttr metadata(Operation *source, StringRef identity) {
    NamedAttrList attrs(source ? source->getAttrOfType<DictionaryAttr>("metadata")
                               : DictionaryAttr());
    attrs.set("semantic_path", builder.getStringAttr(identity));
    return attrs.getDictionary(builder.getContext());
  }
  LogicalResult update(Value state, Value next, Operation *root) {
    Operation *reg = state.getDefiningOp();
    Operation *binding = model.domains.findLocalBinding(reg->getAttr("domain"));
    if (failed(charge(reg)))
      return failure();
    OperationState op(root ? root->getLoc() : reg->getLoc(), "nodal.sequential_update");
    op.addAttribute("domain", reg->getAttr("domain"));
    if (binding) {
      Operation *domain = *model.domains.resolveDomain(binding->getAttr("domain"));
      op.addOperands({state, next, binding->getOperand(0)});
      if (reg->getNumOperands())
        op.addOperands({binding->getOperand(1), reg->getOperand(0)});
      op.addAttribute("edge", domain->getAttr("edge"));
      op.addAttribute("reset_policy",
                      builder.getStringAttr(reg->getNumOperands() ? text(domain, "reset_policy")
                                                                  : StringRef("none")));
      op.addAttribute("reset_polarity", binding->getAttr("reset_polarity"));
    } else {
      Value clock = formalCapability(reg, false);
      bool conditional = text(reg, "initializer_policy") == "if_reset";
      Value reset = reg->getNumOperands() && !conditional ? formalCapability(reg, true) : Value();
      if (!clock || (reg->getNumOperands() && !conditional && !reset))
        return failure();
      op.addOperands({state, next, clock});
      if (conditional) {
        op.addOperands(reg->getOperand(0));
        op.addAttribute("initializer_policy", reg->getAttr("initializer_policy"));
      } else if (reg->getNumOperands())
        op.addOperands({reset, reg->getOperand(0)});
    }
    std::string identity =
        root && canonical(path(root))
            ? path(root).str()
            : (text(model.module, "sym_name") + "." + text(reg, "name") + ".update").str();
    op.addAttribute("root_id", builder.getStringAttr(identity));
    op.addAttribute(
        "metadata",
        metadata(reg,
                 (text(model.module, "sym_name") + "." + text(reg, "name") + ".clocked").str()));
    builder.create(op);
    return success();
  }
  Value formalCapability(Operation *reg, bool reset) {
    auto requirement = reg->getAttrOfType<FlatSymbolRefAttr>("domain");
    if (!reset) {
      if (failed(charge(reg)))
        return {};
      auto clock = getOrCreateSequentialDomainClock(
          reg, builder,
          metadata(
              reg,
              (text(model.module, "sym_name") + "." + requirement.getValue() + ".clock").str()));
      return succeeded(clock) ? *clock : Value();
    }
    for (Operation &candidate : model.module->getRegion(0).front())
      if (isa<RequirementResetOp>(candidate) && candidate.getAttr("requirement") == requirement)
        return candidate.getResult(0);
    if (!model.domains.unresolvedRequirement(requirement) || failed(charge(reg)))
      return {};
    OpBuilder::InsertionGuard guard(builder);
    builder.setInsertionPointToStart(&model.module->getRegion(0).front());
    OperationState projection(reg->getLoc(), "nodal.requirement_reset");
    projection.addTypes(ControlCapabilityType::get(builder.getContext(), "reset"));
    projection.addAttribute("requirement", requirement);
    projection.addAttribute(
        "metadata",
        metadata(reg,
                 (text(model.module, "sym_name") + "." + requirement.getValue() + ".reset").str()));
    return builder.create(projection)->getResult(0);
  }
  Value guardLiteral(Operation *op) {
    if (failed(charge(op)))
      return {};
    OperationState literal(op->getLoc(), "nodal.const_literal");
    literal.addTypes(BitsType::get(builder.getContext(), 1));
    literal.addAttribute("value",
                         builder.getIntegerAttr(
                             IntegerType::get(builder.getContext(), 1, IntegerType::Unsigned), 1));
    literal.addAttribute("spelling", builder.getStringAttr("1"));
    literal.addAttribute("metadata", metadata(op, (path(op) + ".memory.enabled").str()));
    return builder.create(literal)->getResult(0);
  }
  Value guardExpression(Operation *op, StringRef opcode, ArrayRef<Value> inputs) {
    if (failed(charge(op)))
      return {};
    OperationState expression(op->getLoc(), "nodal.digital_expr");
    expression.addOperands(inputs);
    expression.addTypes(BitsType::get(builder.getContext(), 1));
    expression.addAttribute("operator_name", builder.getStringAttr(opcode));
    expression.addAttribute("metadata",
                            metadata(op, (path(op) + ".memory.guard" + Twine(generated)).str()));
    return builder.create(expression)->getResult(0);
  }
  Value booleanGuard(Operation *op, Value predicate) {
    if (boolean(predicate.getType()))
      return predicate;
    if (failed(charge(op)))
      return {};
    OperationState conversion(op->getLoc(), "nodal.behavioral_predicate");
    conversion.addOperands(predicate);
    conversion.addTypes(BitsType::get(builder.getContext(), 1));
    conversion.addAttribute("metadata", metadata(op, (path(op) + ".memory.predicate").str()));
    return builder.create(conversion)->getResult(0);
  }
  LogicalResult operation(Operation *op, IRMapping &mapping, StateMap &next, unsigned depth,
                          Value guard, Operation *sourceRoot) {
    if (depth > model.limits.maximumDepth)
      return reject(op, "normalization depth budget exceeded");
    if (llvm::isa<MemoryWriteOp>(op)) {
      auto port = inspectMemoryPort(op);
      if (failed(port))
        return failure();
      if (!guard)
        guard = guardLiteral(op);
      if (!guard || failed(charge(op)))
        return failure();
      OperationState effect(op->getLoc(), "nodal.memory_write_effect");
      for (Value value : op->getOperands())
        effect.addOperands(mapping.lookupOrDefault(value));
      effect.addOperands({guard, port->binding->getOperand(0)});
      effect.addAttribute("memory", op->getAttr("memory"));
      effect.addAttribute("domain", op->getAttr("domain"));
      effect.addAttribute("edge", port->domain->getAttr("edge"));
      effect.addAttribute("order", builder.getI64IntegerAttr(0));
      effect.addAttribute("root_id", builder.getStringAttr(path(sourceRoot)));
      effect.addAttribute("metadata", op->getAttr("metadata"));
      builder.create(effect);
      return success();
    }
    if (llvm::isa<DigitalAssignOp>(op)) {
      Value target = mapping.lookupOrDefault(op->getOperand(0));
      auto root = stateRoot(target, op);
      // Remapped captures already refer to module-owned state; source use may
      // still live in an isolated arm, so resolve its verified original root.
      if (failed(root))
        root = stateRoot(op->getOperand(0), op);
      if (failed(root))
        return failure();
      next[*root] = mapping.lookupOrDefault(op->getOperand(1));
      return success();
    }
    if (!llvm::isa<BehavioralIfOp>(op)) {
      if (failed(charge(op)))
        return failure();
      builder.clone(*op, mapping);
      return success();
    }
    auto h = inspectBehavioralIfHeader(op);
    if (failed(h))
      return failure();
    StateMap choices[2];
    if (next.size() > (model.limits.maximumNormalizedOperations - generated) / 2)
      return reject(op, "behavioral state accumulator exceeds finite expansion budget");
    generated += 2 * next.size();
    choices[0] = next;
    choices[1] = next;
    bool memoryWrites = false;
    op->walk([&](MemoryWriteOp) { memoryWrites = true; });
    Value condition;
    if (memoryWrites) {
      condition = booleanGuard(op, mapping.lookupOrDefault(op->getOperand(0)));
      if (!condition)
        return failure();
    }
    for (unsigned arm = 0; arm < 2; ++arm) {
      Value armGuard = guard;
      if (memoryWrites) {
        armGuard = arm == 0 ? condition : guardExpression(op, "bool_not", {condition});
        if (!armGuard)
          return failure();
        if (guard)
          armGuard = guardExpression(op, "bool_and", {guard, armGuard});
        if (!armGuard)
          return failure();
      }
      IRMapping local = mapping;
      for (unsigned i = 0; i < h->captureCounts[arm]; ++i)
        local.map(h->bodies[arm]->getArgument(i),
                  mapping.lookupOrDefault(op->getOperand(h->captureOffsets[arm] + i)));
      // Copy the already selected state so a nested missing update holds the
      // earlier write in this root, rather than discarding it for old state.
      for (Operation &nested : *h->bodies[arm])
        if (failed(operation(&nested, local, choices[arm], depth + 1, armGuard, sourceRoot)))
          return failure();
    }
    llvm::SetVector<Value> states;
    for (auto &choice : choices)
      for (auto entry : choice)
        states.insert(entry.first);
    for (Value state : states) {
      Value previous = next.count(state) ? next[state] : state;
      Value a = choices[0].count(state) ? choices[0][state] : previous;
      Value b = choices[1].count(state) ? choices[1][state] : previous;
      if (a == b) {
        next[state] = a;
        continue;
      }
      if (failed(charge(op)))
        return failure();
      OperationState select(op->getLoc(), "nodal.sequential_select");
      select.addOperands({mapping.lookupOrDefault(op->getOperand(0)), a, b});
      select.addTypes(state.getType());
      select.addAttribute(
          "metadata",
          metadata(
              op,
              (text(op, "region_id") + "." + text(state.getDefiningOp(), "name") + ".next").str()));
      next[state] = builder.create(select)->getResult(0);
    }
    return success();
  }
};
} // namespace
LogicalResult verifySequentialModel(Operation *module, SequentialModelLimits limits) {
  return Model{module, limits}.verify();
}
FailureOr<SmallVector<SequentialSample, 8>>
collectHierarchySequentialSamples(Operation *module, SequentialModelLimits limits) {
  Model model{module, limits};
  if (failed(model.verify()))
    return failure();
  return std::move(model.samples);
}
LogicalResult normalizeSequentialCandidate(Operation *module, SequentialModelLimits limits) {
  // Generic updates retain formal operands and omit material semantics. The
  // mandatory whole-root verifier still proves every actual occurrence below.
  if (failed(verifySequentialModel(module, limits)))
    return failure();
  MemoryModelLimits memoryLimits;
  memoryLimits.maximumOperations = limits.maximumOperations;
  if (failed(preflightMemoryNormalization(module, memoryLimits)))
    return failure(); // Never repair forged order or expand over-budget raw ports.
  unsigned generated = 0;
  if (failed(expandFlatFSMModel(module, generated, limits.maximumNormalizedOperations)) ||
      failed(expandEnumSwitchModel(module, generated, limits.maximumNormalizedOperations)))
    return failure();
  limits.maximumNormalizedOperations -= generated;
  Model model{module, limits};
  return success(succeeded(model.verify()) && succeeded(Normalizer(model).run()));
}
LogicalResult normalizeSequentialModel(Operation *module, SequentialModelLimits limits) {
  Operation *root = module;
  while (root->getParentOp())
    root = root->getParentOp();
  IRMapping mapping;
  Operation *copy = root->clone(mapping);
  Operation *candidate = mapping.lookupOrDefault(module);
  bool valid =
      succeeded(normalizeSequentialCandidate(candidate, limits)) && succeeded(verify(copy));
  if (valid)
    module->getRegion(0).takeBody(candidate->getRegion(0));
  copy->destroy();
  return success(valid);
}
} // namespace nodal
