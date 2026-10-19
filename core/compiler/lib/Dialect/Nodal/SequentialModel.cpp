#include "nodal/Dialect/Nodal/SequentialModel.h"

#include "mlir/IR/IRMapping.h"
#include "mlir/IR/SymbolTable.h"
#include "mlir/IR/Verifier.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/DigitalValueModel.h"
#include "nodal/Dialect/Nodal/EnumValueModel.h"
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
bool capability(Type type, StringRef kind) {
  auto c = llvm::dyn_cast<ControlCapabilityType>(type);
  return c && c.getKind() == kind;
}
Operation *moduleOf(Operation *op) {
  return llvm::isa_and_nonnull<nodal::ModuleOp>(op) ? op : op->getParentOfType<nodal::ModuleOp>();
}
Operation *domainOf(Operation *op) {
  auto ref = op->getAttrOfType<FlatSymbolRefAttr>("domain");
  Operation *m = moduleOf(op);
  return ref && m ? SymbolTable::lookupSymbolIn(m, ref) : nullptr;
}
Operation *bindingOf(Operation *reg) {
  Operation *m = moduleOf(reg);
  for (Operation &op : m->getRegion(0).front())
    if (llvm::isa<LocalDomainBindingOp>(op) && op.getAttr("domain") == reg->getAttr("domain"))
      return &op;
  return nullptr;
}
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
LogicalResult checkPhysical(Value value, Operation *binding, StringRef kind) {
  Operation *producer = value.getDefiningOp();
  Operation *module = binding->getParentOp();
  if (!capability(value.getType(), kind) || !llvm::isa_and_nonnull<PortValueOp>(producer) ||
      producer->getParentOp() != module || failed(verifyDigitalValueScope(value, binding)))
    return reject(binding, "local binding requires actual owned typed input boundary signals");
  auto ref = producer->getAttrOfType<FlatSymbolRefAttr>("port");
  Operation *port = ref ? SymbolTable::lookupSymbolIn(module, ref) : nullptr;
  auto type = port ? port->getAttrOfType<TypeAttr>("type") : TypeAttr();
  if (!llvm::isa_and_nonnull<PortOp>(port) || text(port, "direction") != "input" || !type ||
      type.getValue() != value.getType() || port->getAttr("domain") != binding->getAttr("domain"))
    return reject(binding, "clock/reset producer must match an actual local input port and domain");
  return success();
}
LogicalResult checkRegister(Operation *reg, Operation *binding) {
  if (failed(verifyDigitalStorageOperation(reg)))
    return failure();
  if (reg->getNumOperands() && text(domainOf(binding), "reset_policy") == "none")
    return reject(reg, "initialized register requires a physical reset; hidden boot initialization "
                       "is unsupported");
  return success();
}
} // namespace

FailureOr<BehavioralIfHeader> inspectBehavioralIfHeader(Operation *op) {
  if (!llvm::isa_and_nonnull<BehavioralIfOp>(op) || op->getNumRegions() != 2 ||
      op->getNumResults() || op->getNumSuccessors() || !op->getNumOperands() ||
      op->getNumOperands() > 8193 || !predicate(op->getOperand(0).getType()) ||
      text(op, "contract_version") != "behavioral-if-v1" || !canonical(text(op, "region_id")) ||
      path(op) != text(op, "region_id") ||
      !closed(op, {"operandSegmentSizes", "contract_version", "region_id", "metadata"}))
    return failure();
  Operation *m = op->getParentOfType<nodal::ModuleOp>();
  if (!m || !llvm::isa<ModuleOp, BehavioralIfOp>(op->getParentOp()))
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
      auto facts = resolveBehavioralIntegerExpression(*source, h.operation);
      if (failed(facts) || !facts->expression)
        return failure();
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
  if (llvm::isa<BehavioralIfOp>(h->operation->getParentOp())) {
    auto outer = behavioralIntegerContextsAtUse(h->operation);
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
LogicalResult verifyLocalDomainBinding(Operation *op) {
  if (!llvm::isa_and_nonnull<LocalDomainBindingOp>(op) || !llvm::isa<ModuleOp>(op->getParentOp()) ||
      op->getNumResults() || op->getNumRegions() || op->getNumSuccessors() ||
      op->getNumOperands() < 1 || op->getNumOperands() > 2 ||
      !closed(op, {"domain", "reset_polarity", "metadata"}))
    return reject(op, "local domain binding requires one clock and at most one reset");
  Operation *domain = domainOf(op);
  if (!llvm::isa_and_nonnull<DomainOp>(domain) || domain->getParentOp() != op->getParentOp() ||
      (text(domain, "edge") != "rising" && text(domain, "edge") != "falling"))
    return reject(op, "executable local domain requires explicit rising/falling edge");
  StringRef policy = text(domain, "reset_policy");
  if (policy != "none" && policy != "sync" && policy != "async")
    return reject(
        op,
        "reset synchronization requires the explicit F57 implementation; no implicit synchronizer");
  if ((policy == "none") != (op->getNumOperands() == 1) ||
      (text(op, "reset_polarity") != "active_high" && text(op, "reset_polarity") != "active_low"))
    return reject(op, "physical reset presence and explicit polarity must match domain policy");
  if (failed(checkPhysical(op->getOperand(0), op, "clock")))
    return failure();
  return op->getNumOperands() == 2 ? checkPhysical(op->getOperand(1), op, "reset") : success();
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
    for (Operation &nested : *body) {
      if (llvm::isa<BehavioralIfOp>(nested))
        continue;
      if (llvm::isa<StructuralIfOp, CollectionOp>(nested)) {
        // Existing value-region owners retain all type/shape/proof obligations.
        // Behavioral use admits only a pure result, never structural writes.
        bool pure = nested.getNumResults() == 1;
        nested.walk([&](Operation *child) {
          if (llvm::isa<DigitalAssignOp, BehavioralIfOp, DigitalStorageOp, LocalDomainBindingOp>(
                  child))
            pure = false;
        });
        if (!pure ||
            failed(llvm::isa<StructuralIfOp>(nested) ? verifyStructuralIfOperation(&nested)
                                                     : verifyDigitalCollectionOperation(&nested)))
          return reject(&nested, "behavioral value region must retain a verified read-only result");
        continue;
      }
      if (llvm::isa<DigitalAssignOp>(nested)) {
        if (text(&nested, "kind") != "register_next")
          return reject(&nested, "behavioral arms update only domain-owned registers");
      } else if (!allowedPure(&nested) || nested.getNumRegions() || nested.getNumSuccessors() ||
                 nested.getNumResults() != 1)
        return reject(&nested, "behavioral arms cannot create structure or untracked effects");
    }
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
      (op->getNumOperands() != 3 && op->getNumOperands() != 5) ||
      !llvm::isa<ModuleOp>(op->getParentOp()) || !canonical(text(op, "root_id")) ||
      !closed(op, {"domain", "edge", "reset_policy", "reset_polarity", "root_id", "metadata"}))
    return reject(op,
                  "clocked update requires explicit typed clock/reset and stable root identity");
  auto root = stateRoot(op->getOperand(0), op);
  if (failed(root) || *root != op->getOperand(0))
    return reject(op, "clocked update requires its exact local state declaration");
  Operation *reg = root->getDefiningOp(), *binding = bindingOf(reg);
  if (!binding || failed(verifyLocalDomainBinding(binding)) || failed(checkRegister(reg, binding)))
    return reject(op, "clocked update requires a verified physical local binding");
  Operation *domain = domainOf(binding);
  bool initialized = reg->getNumOperands() == 1;
  if (op->getAttr("domain") != reg->getAttr("domain") || text(op, "edge") != text(domain, "edge") ||
      text(op, "reset_polarity") != text(binding, "reset_polarity") ||
      text(op, "reset_policy") !=
          (initialized ? text(domain, "reset_policy") : StringRef("none")) ||
      op->getOperand(2) != binding->getOperand(0) ||
      op->getNumOperands() != (initialized ? 5u : 3u) ||
      (initialized &&
       (op->getOperand(3) != binding->getOperand(1) || op->getOperand(4) != reg->getOperand(0))))
    return reject(
        op, "clock/reset/reset-value fields must exactly match their owned domain and register");
  if (!equivalentDigitalStorageTypes(root->getType(), op->getOperand(1).getType(),
                                     op->getParentOp()))
    return reject(op, "clocked next state must retain the exact recursive state type");
  for (Value operand : op->getOperands())
    if (failed(verifyDigitalValueScope(operand, op)))
      return reject(op, "clocked update cannot use foreign or escaped values");
  return success();
}
namespace {
struct Model {
  Model(Operation *module, SequentialModelLimits limits) : module(module), limits(limits) {}
  Operation *module;
  SequentialModelLimits limits;
  llvm::DenseMap<Value, Operation *> roots;
  SmallVector<Operation *, 8> registers;
  unsigned work = 0;
  struct Sample {
    Value value;
    Attribute domain;
    Operation *use;
  };
  SmallVector<Sample, 8> samples;
  LogicalResult sampled(Value value, Attribute domain, Operation *use) {
    samples.push_back({value, domain, use});
    return success();
  }
  LogicalResult verifySamples() {
    if (samples.empty())
      return success();
    SmallVector<std::pair<Value, Operation *>, 8> seeds;
    for (auto sample : samples)
      seeds.push_back({sample.value, sample.use});
    DigitalAssignmentProofLimits graphLimits;
    graphLimits.maximumNodes = limits.maximumOperations;
    auto graph = analyzeDigitalAssignmentDependencies(module, seeds, graphLimits);
    if (failed(graph))
      return failure();
    // Explicit requirements are constraints, never domainless boundaries. The
    // finite local profile can prove a unique direct requirement-to-domain bind;
    // hierarchy/chained or unresolved bindings remain unsupported until F57/F58.
    llvm::DenseMap<Attribute, Operation *> requirementBindings;
    llvm::DenseSet<Attribute> ambiguousBindings;
    for (Operation &op : module->getRegion(0).front()) {
      if (!llvm::isa<DomainBindOp>(op))
        continue;
      auto requirement = op.getAttrOfType<FlatSymbolRefAttr>("requirement");
      if (requirement && !requirementBindings.try_emplace(requirement, &op).second)
        ambiguousBindings.insert(requirement);
    }
    auto sameDomain = [&](Attribute reference, Attribute expected) {
      auto symbol = llvm::dyn_cast_if_present<FlatSymbolRefAttr>(reference);
      Operation *declaration = symbol ? SymbolTable::lookupSymbolIn(module, symbol) : nullptr;
      if (!declaration || declaration->getParentOp() != module)
        return false;
      if (llvm::isa<DomainOp>(declaration))
        return reference == expected;
      if (!llvm::isa<DomainRequirementOp>(declaration) || ambiguousBindings.contains(reference))
        return false;
      Operation *binding = requirementBindings.lookup(reference);
      if (!binding || binding->getNumOperands() || binding->getNumResults() ||
          binding->getNumRegions() || binding->getNumSuccessors())
        return false;
      auto actual = binding->getAttrOfType<FlatSymbolRefAttr>("actual");
      Operation *target = actual ? SymbolTable::lookupSymbolIn(module, actual) : nullptr;
      return llvm::isa_and_nonnull<DomainOp>(target) && target->getParentOp() == module &&
             actual == expected;
    };
    llvm::DenseSet<std::pair<unsigned, Attribute>> visited;
    for (unsigned i = 0; i < samples.size(); ++i) {
      auto sample = samples[i];
      SmallVector<unsigned, 8> pending{graph->roots[i]};
      while (!pending.empty()) {
        unsigned id = pending.pop_back_val();
        if (!visited.insert({id, sample.domain}).second)
          continue;
        if (++work > limits.maximumOperations)
          return reject(sample.use, "sequential dependency proof exceeds finite operation budget");
        const auto &node = graph->nodes[id];
        if (node.sourceDomain && !sameDomain(node.sourceDomain, sample.domain))
          return sample.use->emitOpError("NODAL-CDC-001: foreign or unresolved Interface domain is "
                                         "sampled without an explicit crossing");
        if (node.value) {
          auto access = resolveStaticShapeProjection(node.value, node.use);
          Operation *source = succeeded(access) ? access->root.getDefiningOp() : nullptr;
          if (llvm::isa_and_nonnull<DigitalStorageOp>(source) &&
              text(source, "kind") == "register" &&
              !sameDomain(source->getAttr("domain"), sample.domain))
            return sample.use->emitOpError(
                "NODAL-CDC-001: foreign-domain state is sampled without an explicit crossing");
          if (llvm::isa_and_nonnull<PortValueOp>(source)) {
            auto ref = source->getAttrOfType<FlatSymbolRefAttr>("port");
            Operation *port = ref ? SymbolTable::lookupSymbolIn(module, ref) : nullptr;
            auto domain =
                port ? port->getAttrOfType<FlatSymbolRefAttr>("domain") : FlatSymbolRefAttr();
            bool unannotated = domain && domain.getValue() == "none" &&
                               !SymbolTable::lookupSymbolIn(module, domain);
            if (domain && !unannotated && !sameDomain(domain, sample.domain))
              return sample.use->emitOpError("NODAL-CDC-001: foreign or unresolved input domain is "
                                             "sampled without an explicit crossing");
          }
        }
        pending.append(node.dependencies.begin(), node.dependencies.end());
      }
    }
    return success();
  }
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
        if (Operation *binding = bindingOf(&op)) {
          if (failed(checkRegister(&op, binding)))
            return failure();
          if (op.getNumOperands() && failed(sampled(op.getOperand(0), op.getAttr("domain"), &op)))
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
        if (llvm::isa<BehavioralIfOp>(op)) {
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
        } else if ((llvm::isa<DigitalAssignOp>(op) && text(&op, "kind") == "register_next") ||
                   llvm::isa<SequentialUpdateOp>(op)) {
          if ((llvm::isa<DigitalAssignOp>(op) && op.getNumOperands() != 2) ||
              (llvm::isa<SequentialUpdateOp>(op) && op.getNumOperands() != 3 &&
               op.getNumOperands() != 5))
            return reject(&op, "sequential update operand arity is invalid");
          auto target = stateRoot(op.getOperand(0), &op);
          if (failed(target))
            return reject(&op, "sequential update must target one whole owned register");
          Operation *reg = target->getDefiningOp();
          if (!bindingOf(reg)) {
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
          if (failed(sampled(op.getOperand(1), reg->getAttr("domain"), &op)))
            return failure();
          for (Operation *ancestor = op.getParentOp(); ancestor != module;
               ancestor = ancestor->getParentOp())
            if (llvm::isa<BehavioralIfOp>(ancestor) &&
                failed(sampled(ancestor->getOperand(0), reg->getAttr("domain"), ancestor)))
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
      if (llvm::isa<BehavioralIfOp>(op) ||
          (llvm::isa<DigitalAssignOp>(op) && text(&op, "kind") == "register_next" &&
           bindingOf(op.getOperand(0).getDefiningOp())))
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
      if (failed(operation(root, mapping, next, 0)))
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
    Operation *reg = state.getDefiningOp(), *binding = bindingOf(reg), *domain = domainOf(binding);
    if (failed(charge(reg)))
      return failure();
    OperationState op(root ? root->getLoc() : reg->getLoc(), "nodal.sequential_update");
    op.addOperands({state, next, binding->getOperand(0)});
    if (reg->getNumOperands())
      op.addOperands({binding->getOperand(1), reg->getOperand(0)});
    op.addAttribute("domain", reg->getAttr("domain"));
    op.addAttribute("edge", domain->getAttr("edge"));
    op.addAttribute("reset_policy",
                    builder.getStringAttr(reg->getNumOperands() ? text(domain, "reset_policy")
                                                                : StringRef("none")));
    op.addAttribute("reset_polarity", binding->getAttr("reset_polarity"));
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
  LogicalResult operation(Operation *op, IRMapping &mapping, StateMap &next, unsigned depth) {
    if (depth > model.limits.maximumDepth)
      return reject(op, "normalization depth budget exceeded");
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
    for (unsigned arm = 0; arm < 2; ++arm) {
      IRMapping local = mapping;
      for (unsigned i = 0; i < h->captureCounts[arm]; ++i)
        local.map(h->bodies[arm]->getArgument(i),
                  mapping.lookupOrDefault(op->getOperand(h->captureOffsets[arm] + i)));
      // Copy the already selected state so a nested missing update holds the
      // earlier write in this root, rather than discarding it for old state.
      for (Operation &nested : *h->bodies[arm])
        if (failed(operation(&nested, local, choices[arm], depth + 1)))
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
LogicalResult normalizeSequentialModel(Operation *module, SequentialModelLimits limits) {
  if (failed(verifySequentialModel(module, limits)))
    return failure();
  Operation *root = module;
  while (root->getParentOp())
    root = root->getParentOp();
  IRMapping mapping;
  Operation *copy = root->clone(mapping);
  Operation *candidate = mapping.lookupOrDefault(module);
  Model model{candidate, limits};
  bool valid =
      succeeded(model.verify()) && succeeded(Normalizer(model).run()) && succeeded(verify(copy));
  if (valid)
    module->getRegion(0).takeBody(candidate->getRegion(0));
  copy->destroy();
  return success(valid);
}
} // namespace nodal
