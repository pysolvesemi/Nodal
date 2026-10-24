#include "nodal/Dialect/Nodal/HierarchyModel.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/ClockModel.h"
#include "nodal/Dialect/Nodal/CrossingModel.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ResetModel.h"
#include "nodal/Dialect/Nodal/StructuralIfModel.h"
#include "nodal/Dialect/Nodal/WaiverModel.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/StringExtras.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringSet.h"

#include <algorithm>

using namespace mlir;
namespace nodal {
namespace {
StringRef text(Operation *op, StringRef key) {
  auto attr = op ? op->getAttrOfType<StringAttr>(key) : StringAttr();
  return attr ? attr.getValue() : StringRef();
}
LogicalResult reject(Operation *op, const Twine &reason) {
  return op->emitOpError("NODAL-HIERARCHY-057-001: ") << reason;
}
bool closed(Operation *op, ArrayRef<StringRef> names) {
  return llvm::all_of(op->getAttrs(), [&](NamedAttribute attr) {
    return llvm::is_contained(names, attr.getName().getValue());
  });
}
bool capability(Type type, StringRef kind) {
  auto control = dyn_cast<ControlCapabilityType>(type);
  return control && control.getKind() == kind;
}
Operation *local(Operation *module, Attribute reference) {
  auto symbol = dyn_cast_if_present<FlatSymbolRefAttr>(reference);
  Operation *result = module && symbol ? SymbolTable::lookupSymbolIn(module, symbol) : nullptr;
  return result && result->getParentOp() == module ? result : nullptr;
}
/// Retain actual lexical ancestry. Structural arms may enclose a family, but an
/// instance itself remains directly owned by its Module or Generate body.
FailureOr<SmallVector<Operation *, 2>> generatedAncestry(Operation *instance) {
  SmallVector<Operation *, 2> result;
  Operation *module = instance ? instance->getParentOfType<ModuleOp>() : nullptr;
  Operation *scope = instance ? instance->getParentOp() : nullptr;
  if (!module || (!isa<ModuleOp>(scope) && !isa<GenerateOp>(scope)))
    return failure();
  unsigned depth = 0;
  while (scope != module) {
    if (!scope || ++depth > 128)
      return failure();
    if (isa<GenerateOp>(scope)) {
      // A digital lexical instance is never admitted merely because an analog
      // legacy Generate has matching metadata.
      if (!isDigitalRegionOwner(scope) || scope->getNumResults() != 1 ||
          failed(verifyGeneratedFamilyOperation(scope)) || failed(resolveGeneratedDomain(scope)))
        return failure();
      result.push_back(scope);
    } else if (isa<StructuralIfOp>(scope)) {
      if (scope->getNumResults() || failed(inspectStructuralIfHeader(scope)))
        return failure();
    } else {
      return failure();
    }
    scope = scope->getParentOp();
  }
  std::reverse(result.begin(), result.end());
  return result;
}
Value capturedControlIdentity(Value value) {
  for (unsigned depth = 0; value && depth != 128; ++depth) {
    auto argument = dyn_cast<BlockArgument>(value);
    if (!argument)
      return value;
    auto source = digitalRegionCaptureSource(argument);
    if (failed(source) || !*source)
      return {};
    value = *source;
  }
  return {};
}
FailureOr<InstancePort> child(Operation *op) {
  auto resolved = resolveHierarchyInstance(op, op->getAttr("instance"));
  if (failed(resolved))
    return failure();
  return InstancePort{resolved->instance, resolved->module, nullptr};
}
bool hierarchyOperation(Operation *op) {
  return isa<RequirementClockOp, RequirementResetOp, InstanceDomainBindingOp, InstancePortOp>(op);
}
bool contextualSequentialOperation(Operation *op) {
  if (!isa<ResetAssertedOp, SequentialAssertOp, SequentialUpdateOp, FlatFSMOp>(op) &&
      !(isa<DigitalStorageOp>(op) && text(op, "kind") == "register"))
    return false;
  // The consumer itself establishes an obligation. Requiring a pre-existing
  // capability would make projection inference circular and let an isolated
  // formal consumer avoid the mandatory closed-root proof.
  return DomainModel(op).unresolvedRequirement(op->getAttr("domain"));
}
} // namespace
LogicalResult verifyHierarchyInstanceOperation(Operation *instance) {
  if (!isa_and_nonnull<InstanceOp>(instance) || instance->getNumOperands() ||
      instance->getNumResults() || instance->getNumRegions() || instance->getNumSuccessors() ||
      !closed(instance, {"sym_name", "module", "parameter_bindings", "domain_bindings",
                         "generated_owner", "generated_induction", "metadata"}) ||
      failed(verifyGeneratedStructuralOwnership(instance)) || failed(generatedAncestry(instance)))
    return reject(instance, "instance requires its exact bounded Module/Generate lexical owner");
  Operation *definition = resolveInstanceModuleDefinition(instance);
  Operation *module = instance->getParentOfType<ModuleOp>();
  auto dictionary = instance->getAttrOfType<DictionaryAttr>("domain_bindings");
  if (!definition || !dictionary)
    return reject(instance,
                  "hierarchy instance requires a resolved direct definition and domain dictionary");
  for (NamedAttribute binding : dictionary) {
    auto requirement = FlatSymbolRefAttr::get(instance->getContext(), binding.getName().getValue());
    if (!isa_and_nonnull<DomainRequirementOp>(local(definition, requirement)) ||
        !isa_and_nonnull<DomainOp, DomainRequirementOp>(local(module, binding.getValue())))
      return reject(
          instance,
          "instance domain dictionary must name exact child requirements and parent actuals");
  }
  return success();
}
FailureOr<HierarchyInstance> resolveHierarchyInstance(Operation *use, Attribute reference) {
  auto *instance =
      resolveStructuralInstance(use, dyn_cast_if_present<FlatSymbolRefAttr>(reference));
  if (!instance || failed(verifyHierarchyInstanceOperation(instance)))
    return failure();
  return HierarchyInstance{instance, resolveInstanceModuleDefinition(instance)};
}
LogicalResult verifyRequirementProjection(Operation *op) {
  bool clock = isa_and_nonnull<RequirementClockOp>(op);
  if ((!clock && !isa_and_nonnull<RequirementResetOp>(op)) ||
      !isa_and_nonnull<ModuleOp>(op->getParentOp()) || op->getNumOperands() ||
      op->getNumResults() != 1 || op->getNumRegions() || op->getNumSuccessors() ||
      !closed(op, {"requirement", "metadata"}) ||
      !capability(op->getResult(0).getType(), clock ? "clock" : "reset") ||
      !isa_and_nonnull<DomainRequirementOp>(local(op->getParentOp(), op->getAttr("requirement"))))
    return reject(op,
                  "formal capability requires its exact local requirement and Clock/Reset type");
  return success();
}
LogicalResult verifyInstanceDomainBinding(Operation *op) {
  if (!isa_and_nonnull<InstanceDomainBindingOp>(op) || op->getNumOperands() < 1 ||
      op->getNumOperands() > 2 || op->getNumResults() || op->getNumRegions() ||
      op->getNumSuccessors() ||
      !closed(op, {"instance", "requirement", "actual", "origin", "metadata"}) ||
      !capability(op->getOperand(0).getType(), "clock") ||
      (op->getNumOperands() == 2 && !capability(op->getOperand(1).getType(), "reset")) ||
      !llvm::is_contained(ArrayRef<StringRef>{"default", "inherited", "named"}, text(op, "origin")))
    return reject(op,
                  "instance binding requires one Clock, optional Reset and exact binding origin");
  auto boundary = child(op);
  if (failed(boundary) ||
      !isa_and_nonnull<DomainRequirementOp>(local(boundary->module, op->getAttr("requirement"))) ||
      !isa_and_nonnull<DomainOp, DomainRequirementOp>(
          local(op->getParentOfType<ModuleOp>(), op->getAttr("actual"))))
    return reject(
        op,
        "instance binding must name one immediate child's exact requirement and a local actual");
  for (Value operand : op->getOperands())
    if (failed(verifyDigitalValueScope(operand, op)))
      return reject(op, "actual instance capabilities must retain parent-local SSA ownership");
  auto descriptor = boundary->instance->getAttrOfType<DictionaryAttr>("domain_bindings");
  auto requirement = op->getAttrOfType<FlatSymbolRefAttr>("requirement");
  if (!descriptor || descriptor.get(requirement.getValue()) != op->getAttr("actual"))
    return reject(op,
                  "retained instance domain dictionary must agree with actual binding endpoints");
  DomainModel domains(op);
  if (succeeded(domains.resolveDomain(op->getAttr("actual")))) {
    auto binding = domains.resolveBinding(op->getAttr("actual"));
    if (failed(binding) || !sameClock(op->getOperand(0), op, (*binding)->getOperand(0), *binding))
      return reject(op, "actual instance Clock operand disagrees with its concrete domain binding");
    if (op->getNumOperands() != (*binding)->getNumOperands() ||
        (op->getNumOperands() == 2 && capturedControlIdentity(op->getOperand(1)) !=
                                          capturedControlIdentity((*binding)->getOperand(1))))
      return reject(
          op, "actual instance Reset operand disagrees with its exact bound reset capability");
  }
  for (Operation &other : op->getParentOp()->getRegion(0).front())
    if (&other != op && isa<InstanceDomainBindingOp>(other) &&
        other.getAttr("instance") == op->getAttr("instance") &&
        other.getAttr("requirement") == op->getAttr("requirement"))
      return reject(op, "one authoritative binding is required per instance and requirement");
  return success();
}
FailureOr<InstancePort> inspectInstancePort(Operation *op) {
  auto fail = [&](StringRef reason) -> FailureOr<InstancePort> {
    (void)reject(op, reason);
    return failure();
  };
  if (!isa_and_nonnull<InstancePortOp>(op) ||
      (op->getNumOperands() != 0 && op->getNumOperands() != 2) || op->getNumResults() != 1 ||
      op->getNumRegions() || op->getNumSuccessors() ||
      !closed(op, {"instance", "port", "direction", "metadata", "member"}))
    return fail("instance port requires its exact parent-owned typed reference");
  FailureOr<InstancePort> boundary = failure();
  if (op->getNumOperands()) {
    auto member = op->getAttrOfType<StringAttr>("member");
    auto proof = member ? inspectGeneratedSelection(op->getOperand(0), op->getOperand(1),
                                                    member.getValue(), op)
                        : FailureOr<GeneratedSelectionProof>(failure());
    auto target = succeeded(proof) ? inspectGeneratedExportSymbol(proof->exportOperation)
                                   : FailureOr<Operation *>(failure());
    auto reference = op->getAttrOfType<FlatSymbolRefAttr>("instance");
    if (failed(target) || !isa<InstanceOp>(*target) || !reference ||
        (*target)->getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName()) !=
            reference.getAttr() ||
        failed(verifyHierarchyInstanceOperation(*target)))
      return fail("selected instance port requires its exact exported instance target");
    boundary = InstancePort{*target, resolveInstanceModuleDefinition(*target), nullptr, op};
  } else {
    if (op->hasAttr("member") || !isa<ModuleOp, GenerateOp>(op->getParentOp()))
      return fail("direct instance port forbids selection keys or non-direct ownership");
    boundary = child(op);
  }
  if (failed(boundary))
    return fail("instance port must name an exact lexical immediate-child instance");
  auto name = text(op, "port");
  boundary->port = name.empty()
                       ? nullptr
                       : local(boundary->module, FlatSymbolRefAttr::get(op->getContext(), name));
  auto type = boundary->port ? boundary->port->getAttrOfType<TypeAttr>("type") : TypeAttr();
  if (!isa_and_nonnull<PortOp>(boundary->port) || !type ||
      !(boundary->selection
            ? equivalentSelectedDigitalStorageTypes(op, boundary->instance, type.getValue(),
                                                    op->getResult(0).getType())
        : isa<GenerateOp>(op->getParentOp())
            ? equivalentGeneratedInstanceDigitalStorageTypes(boundary->instance, type.getValue(),
                                                             op->getResult(0).getType(), op)
            : equivalentInstanceDigitalStorageTypes(boundary->instance, type.getValue(),
                                                    op->getResult(0).getType())) ||
      !llvm::is_contained(ArrayRef<StringRef>{"input", "output"}, text(op, "direction")) ||
      text(op, "direction") != text(boundary->port, "direction") ||
      isDigitalControlBoundaryType(type.getValue()) ||
      failed(inferDigitalStorageMaximumBits(type.getValue(), boundary->port)))
    return fail("instance port must retain exact child data type and input/output direction");
  return *boundary;
}
bool hasLocalHierarchyObligations(Operation *module) {
  if (!isa_and_nonnull<ModuleOp>(module) || module->getNumRegions() != 1 ||
      !module->getRegion(0).hasOneBlock())
    return false;
  bool obligation = false;
  module->walk([&](Operation *op) {
    obligation |= hierarchyOperation(op) || contextualSequentialOperation(op);
  });
  return obligation;
}
struct HierarchyModel::Impl {
  HierarchyModelLimits limits;
  SmallVector<HierarchyOccurrence> occurrences;
  DenseMap<std::pair<unsigned, Operation *>, unsigned> children;
  DenseMap<std::pair<Operation *, Attribute>, Operation *> bindings;
  mutable DenseMap<std::pair<unsigned, Attribute>, ContextualDomain> domainCache;
  mutable DenseMap<std::pair<unsigned, Attribute>, ContextualBinding> bindingCache;
  mutable DenseMap<std::pair<unsigned, Value>, ContextualValue> capabilityCache;
};
HierarchyModel::HierarchyModel(std::unique_ptr<Impl> impl) : impl(std::move(impl)) {}
HierarchyModel::~HierarchyModel() = default;
ArrayRef<HierarchyOccurrence> HierarchyModel::occurrences() const { return impl->occurrences; }
FailureOr<unsigned> HierarchyModel::childOccurrence(unsigned parent, Operation *instance) const {
  auto found = impl->children.find({parent, instance});
  return found == impl->children.end() ? FailureOr<unsigned>(failure())
                                       : FailureOr<unsigned>(found->second);
}
FailureOr<std::shared_ptr<HierarchyModel>> HierarchyModel::create(Operation *outer,
                                                                  HierarchyModelLimits limits) {
  auto fail = [&](Operation *op, StringRef reason) -> FailureOr<std::shared_ptr<HierarchyModel>> {
    (void)reject(op, reason);
    return failure();
  };
  if (!isa_and_nonnull<mlir::ModuleOp>(outer))
    return failure();
  llvm::StringMap<Operation *> definitions, externalNames;
  unsigned work = 0;
  for (Operation &op : outer->getRegion(0).front()) {
    if (!isa<ModuleOp>(op))
      continue;
    if (op.getNumRegions() != 1 || !op.getRegion(0).hasOneBlock() ||
        !definitions.try_emplace(text(&op, "sym_name"), &op).second)
      return fail(&op, "hierarchy module definitions require unique exact symbols and one body");
    if (op.hasAttr("external_name")) {
      auto signature = inspectExternalModuleSignature(&op);
      if (failed(signature))
        return failure();
      if (!externalNames.try_emplace(signature->externalName.getValue(), &op).second)
        return fail(&op, "external physical symbol has more than one declaration");
    }
    auto inventory = op.walk([&](Operation *nested) {
      if (nested == &op)
        return WalkResult::advance();
      if (++work > limits.maximumOperations) {
        (void)reject(nested, "hierarchy local inventory exceeds finite operation budget");
        return WalkResult::interrupt();
      }
      if ((isa<RequirementClockOp, RequirementResetOp>(nested) &&
           failed(verifyRequirementProjection(nested))) ||
          (isa<InstanceDomainBindingOp>(nested) && failed(verifyInstanceDomainBinding(nested))) ||
          (isa<InstancePortOp>(nested) && failed(inspectInstancePort(nested))))
        return WalkResult::interrupt();
      return WalkResult::advance();
    });
    if (inventory.wasInterrupted())
      return failure();
  }
  for (const auto &external : externalNames) {
    Operation *collision = definitions.lookup(external.getKey());
    if (collision && collision != external.getValue() && !collision->hasAttr("external_name"))
      return fail(external.getValue(),
                  "external physical symbol collides with a local module definition");
  }
  // Validate every definition component, not merely selected-root reachability.
  DenseMap<Operation *, SmallVector<Operation *, 2>> children;
  DenseMap<Operation *, unsigned> incoming;
  SmallVector<Operation *> definitionOrder, pending;
  unsigned definitionEdges = 0;
  for (Operation &definition : outer->getRegion(0).front()) {
    if (!isa<ModuleOp>(definition))
      continue;
    definitionOrder.push_back(&definition);
    incoming.try_emplace(&definition, 0);
    auto walk = definition.walk([&](InstanceOp instance) {
      Operation *op = instance.getOperation();
      auto ref = op->getAttrOfType<FlatSymbolRefAttr>("module");
      Operation *target = ref ? definitions.lookup(ref.getValue()) : nullptr;
      if (!target || ++definitionEdges > limits.maximumEdges ||
          failed(verifyHierarchyInstanceOperation(op))) {
        (void)reject(
            op, "hierarchy instance requires a resolved direct definition within the edge budget");
        return WalkResult::interrupt();
      }
      children[&definition].push_back(target);
      ++incoming[target];
      return WalkResult::advance();
    });
    if (walk.wasInterrupted())
      return failure();
  }
  for (Operation *definition : definitionOrder)
    if (!incoming.lookup(definition))
      pending.push_back(definition);
  for (unsigned cursor = 0; cursor < pending.size(); ++cursor)
    for (Operation *target : children[pending[cursor]])
      if (!--incoming[target])
        pending.push_back(target);
  if (pending.size() != definitionOrder.size())
    for (Operation *definition : definitionOrder)
      if (incoming.lookup(definition))
        return fail(
            definition,
            "recursive instance definition graph is not executable, including unused components");
  auto root = outer->getAttrOfType<FlatSymbolRefAttr>("nodal.root.module");
  Operation *rootModule = root ? definitions.lookup(root.getValue()) : nullptr;
  if (!rootModule)
    return fail(outer, "executable hierarchy requires one exact outer nodal.root.module selector");
  auto data = std::make_unique<Impl>();
  data->limits = limits;
  for (auto &entry : definitions) {
    auto inventory = entry.second->walk([&](InstanceDomainBindingOp binding) {
      Operation *op = binding.getOperation();
      auto boundary = resolveHierarchyInstance(op, op->getAttr("instance"));
      if (failed(boundary))
        return WalkResult::interrupt();
      if (!data->bindings
               .try_emplace(std::make_pair(boundary->instance, op->getAttr("requirement")), op)
               .second) {
        (void)reject(op, "one authoritative binding is required per instance and requirement");
        return WalkResult::interrupt();
      }
      return WalkResult::advance();
    });
    if (inventory.wasInterrupted())
      return failure();
  }
  data->occurrences.push_back({rootModule, nullptr, 0, root.getValue().str(), {}});
  unsigned edges = 0;
  for (unsigned cursor = 0; cursor < data->occurrences.size(); ++cursor) {
    // Copy only this bounded context header: vector growth must not invalidate it.
    auto occurrence = data->occurrences[cursor];
    SmallVector<Operation *, 4> instanceOperations;
    occurrence.module->walk(
        [&](InstanceOp op) { instanceOperations.push_back(op.getOperation()); });
    for (Operation *op : instanceOperations) {
      auto ref = op->getAttrOfType<FlatSymbolRefAttr>("module");
      Operation *target = ref ? definitions.lookup(ref.getValue()) : nullptr;
      if (!target)
        return fail(op, "reachable instance must identify one actual module definition");
      unsigned depth = 0, ancestor = cursor;
      while (true) {
        if (data->occurrences[ancestor].module == target)
          return fail(op, "recursive instance hierarchy is not executable");
        if (++depth > limits.maximumDepth)
          return fail(op, "concrete hierarchy exceeds finite depth budget");
        if (!ancestor)
          break;
        ancestor = data->occurrences[ancestor].parent;
      }
      if (++edges > limits.maximumEdges || data->occurrences.size() >= limits.maximumOccurrences)
        return fail(op, "concrete hierarchy exceeds finite occurrence/edge budget");
      auto scopes = generatedAncestry(op);
      if (failed(scopes))
        return fail(op, "instance has no exact checked generated ancestry");
      auto generatedScopes = occurrence.generatedScopes;
      std::string path = occurrence.path;
      for (Operation *family : *scopes) {
        if (generatedScopes.size() >= limits.maximumDepth)
          return fail(op, "generated hierarchy exceeds finite ancestry depth budget");
        auto domain = resolveGeneratedDomain(family);
        if (failed(domain))
          return failure();
        generatedScopes.push_back({cursor, family, *domain});
        path += ".[" + text(family, "region_id").str() + "]";
      }
      path += "." + text(op, "sym_name").str();
      if (path.size() > 4096)
        return fail(op, "concrete occurrence path exceeds finite identity budget");
      data->children.try_emplace(std::make_pair(cursor, op), data->occurrences.size());
      data->occurrences.push_back(
          {target, op, cursor, std::move(path), std::move(generatedScopes)});
    }
  }
  return std::shared_ptr<HierarchyModel>(new HierarchyModel(std::move(data)));
}
FailureOr<ContextualDomain> HierarchyModel::resolveDomain(unsigned occurrence,
                                                          Attribute reference) const {
  const auto key = std::make_pair(occurrence, reference);
  if (auto found = impl->domainCache.find(key); found != impl->domainCache.end())
    return found->second;
  if (impl->domainCache.size() >= impl->limits.maximumOperations)
    return failure();
  for (unsigned work = 0; work <= impl->limits.maximumDepth; ++work) {
    if (occurrence >= impl->occurrences.size())
      return failure();
    const auto &context = impl->occurrences[occurrence];
    DomainModel domains(context.module);
    auto localDomain = domains.resolveDomain(reference);
    if (succeeded(localDomain)) {
      ContextualDomain result{occurrence, *localDomain};
      impl->domainCache.try_emplace(key, result);
      return result;
    }
    if (!isa_and_nonnull<DomainRequirementOp>(local(context.module, reference)) ||
        !context.instance)
      return failure();
    auto binding = impl->bindings.find({context.instance, reference});
    if (binding == impl->bindings.end())
      return failure();
    reference = binding->second->getAttr("actual");
    occurrence = context.parent;
  }
  return failure();
}
FailureOr<ContextualBinding> HierarchyModel::resolveBinding(unsigned occurrence,
                                                            Attribute reference) const {
  const auto key = std::make_pair(occurrence, reference);
  if (auto found = impl->bindingCache.find(key); found != impl->bindingCache.end())
    return found->second;
  if (impl->bindingCache.size() >= impl->limits.maximumOperations)
    return failure();
  auto domain = resolveDomain(occurrence, reference);
  if (failed(domain))
    return failure();
  DomainModel domains(impl->occurrences[domain->occurrence].module);
  auto actual = FlatSymbolRefAttr::get(domain->declaration->getContext(),
                                       text(domain->declaration, "sym_name"));
  // Shape/clock checks are independent of reset proof and avoid recursive
  // reset-sync -> binding -> reset-sync validation. verifyBindings discharges reset.
  auto binding = domains.resolveClockBinding(actual);
  if (failed(binding))
    return failure();
  ContextualBinding result{*domain,
                           *binding,
                           {domain->occurrence, (*binding)->getOperand(0), *binding},
                           std::nullopt,
                           (*binding)->getAttrOfType<StringAttr>("reset_polarity")};
  if ((*binding)->getNumOperands() == 2)
    result.reset = ContextualValue{domain->occurrence, (*binding)->getOperand(1), *binding};
  impl->bindingCache.try_emplace(key, result);
  return result;
}
FailureOr<ContextualValue> HierarchyModel::resolveCapability(ContextualValue value) const {
  const auto key = std::make_pair(value.occurrence, value.value);
  for (unsigned work = 0; work <= impl->limits.maximumDepth; ++work) {
    if (value.occurrence >= impl->occurrences.size() || !value.value)
      return failure();
    if (auto argument = dyn_cast<BlockArgument>(value.value)) {
      if (!value.use || failed(verifyDigitalValueScope(value.value, value.use)))
        return failure();
      auto source = digitalRegionCaptureSource(argument);
      if (failed(source) || !*source)
        return failure();
      value = {value.occurrence, *source, argument.getOwner()->getParentOp()};
      continue;
    }
    Operation *op = value.value.getDefiningOp();
    Operation *module = impl->occurrences[value.occurrence].module;
    if (!op || op->getParentOp() != module || !value.use ||
        value.use->getParentOfType<ModuleOp>() != module ||
        (op != value.use && failed(verifyDigitalValueScope(value.value, value.use))))
      return failure();
    if (!isa<RequirementClockOp, RequirementResetOp>(op)) {
      if (value.occurrence != key.first || value.value != key.second) {
        if (impl->capabilityCache.size() >= impl->limits.maximumOperations)
          return failure();
        impl->capabilityCache.try_emplace(key, value);
      }
      return value;
    }
    if (auto found = impl->capabilityCache.find(key); found != impl->capabilityCache.end())
      return found->second;
    if (failed(verifyRequirementProjection(op)))
      return failure();
    const auto &context = impl->occurrences[value.occurrence];
    if (succeeded(DomainModel(context.module).resolveDomain(op->getAttr("requirement")))) {
      if (context.instance && impl->bindings.count({context.instance, op->getAttr("requirement")}))
        return failure();
      auto binding = resolveBinding(value.occurrence, op->getAttr("requirement"));
      if (failed(binding) || (isa<RequirementResetOp>(op) && !binding->reset))
        return failure();
      value = isa<RequirementClockOp>(op) ? binding->clock : *binding->reset;
      continue;
    }
    if (!context.instance)
      return failure();
    auto found = impl->bindings.find({context.instance, op->getAttr("requirement")});
    if (found == impl->bindings.end())
      return failure();
    Operation *binding = found->second;
    unsigned operand = isa<RequirementClockOp>(op) ? 0 : 1;
    if (binding->getNumOperands() <= operand)
      return failure();
    value = {context.parent, binding->getOperand(operand), binding};
  }
  return failure();
}
bool HierarchyModel::allowsDirectSampling(ContextualDomain source,
                                          ContextualDomain destination) const {
  if (source == destination)
    return true;
  if (source.occurrence != destination.occurrence)
    return false;
  auto reference = [](Operation *op) {
    return FlatSymbolRefAttr::get(op->getContext(), text(op, "sym_name"));
  };
  return DomainModel(impl->occurrences[source.occurrence].module)
      .allowsDirectSampling(reference(source.declaration), reference(destination.declaration));
}
LogicalResult HierarchyModel::verifyBindings() const {
  for (unsigned index = 0; index < impl->occurrences.size(); ++index) {
    const auto &context = impl->occurrences[index];
    SmallVector<Block *, 4> scopes{&context.module->getRegion(0).front()};
    for (unsigned scope = 0; scope < scopes.size(); ++scope)
      for (Operation &op : *scopes[scope]) {
        if (isa<GenerateOp>(op) && op.getNumResults() == 1) {
          if (failed(verifyGeneratedFamilyOperation(&op)))
            return failure();
          scopes.push_back(&op.getRegion(0).front());
        } else if (isa<StructuralIfOp>(op)) {
          auto header = inspectStructuralIfHeader(&op);
          if (failed(header))
            return failure();
          for (Block *body : header->bodies)
            scopes.push_back(body);
        }
        if (!context.generatedScopes.empty() &&
            ((isa<CrossingOp, AsyncFifoOp>(op) && op.hasAttr("contract")) || isa<WaiverOp>(op)))
          return reject(
              &op,
              "generated occurrence crossing/waiver requires selection-qualified lane provenance");
        if (isa<ResetBoundaryOp, ResetSyncOp, ResetCombineOp>(op)) {
          if (failed(inspectReset({index, op.getResult(0), &op}, *this)))
            return failure();
        } else if (isa<CrossingOp, AsyncFifoOp>(op) && op.hasAttr("contract")) {
          if (failed(inspectExecutableCrossing(&op, *this, index)))
            return failure();
        } else if (isa<WaiverOp>(op)) {
          if (failed(inspectExecutableWaiver(&op, *this, index)))
            return failure();
        } else if (isa<LocalDomainBindingOp>(op)) {
          auto actual = resolveBinding(index, op.getAttr("domain"));
          if (failed(actual) || failed(verifyContextualResetForBinding(*actual, *this)))
            return failure();
        } else if (isa<DigitalStorageOp>(op) && text(&op, "kind") == "register") {
          auto actual = resolveBinding(index, op.getAttr("domain"));
          if (failed(actual))
            return reject(&op, "reachable state requires its actual occurrence domain binding");
          if (failed(verifyDigitalStorageOperation(&op)))
            return failure();
          if (op.getNumOperands() && text(&op, "initializer_policy") != "if_reset" &&
              !actual->reset)
            return reject(&op, "initialized child state requires a valid actual reset binding");
          if (failed(verifyContextualResetForBinding(*actual, *this)))
            return failure();
        } else if (isa<DomainRequirementOp>(op)) {
          auto ref = FlatSymbolRefAttr::get(op.getContext(), text(&op, "sym_name"));
          if (context.instance && !impl->bindings.count({context.instance, ref}))
            return reject(&op,
                          "reachable requirement lacks a unique concrete instance binding at " +
                              context.path);
          if (context.instance && impl->bindings.count({context.instance, ref}) &&
              succeeded(DomainModel(context.module).resolveDomain(ref)))
            return reject(&op,
                          "requirement cannot have both local and instance binding authorities");
          auto actual = resolveBinding(index, ref);
          if (failed(actual))
            return reject(&op,
                          "reachable requirement lacks a unique concrete clock/reset binding at " +
                              context.path);
          if (failed(verifyContextualResetForBinding(*actual, *this)))
            return failure();
        } else if (isa<RequirementClockOp, RequirementResetOp>(op)) {
          if (failed(resolveCapability({index, op.getResult(0), &op})))
            return reject(&op,
                          "required capability is absent from concrete occurrence " + context.path);
        } else if (isa<InstanceDomainBindingOp>(op)) {
          auto actual = resolveBinding(index, op.getAttr("actual"));
          if (failed(actual))
            return reject(&op, "instance actual domain lacks an executable physical binding");
          // Clock equality is physical/occurrence identity, never Same permission.
          if (!sameClock(ContextualValue{index, op.getOperand(0), &op}, actual->clock, *this))
            return reject(
                &op, "actual instance Clock operand disagrees with its concrete domain binding");
          if ((op.getNumOperands() == 2) != actual->reset.has_value())
            return reject(
                &op, "actual instance Reset presence disagrees with its concrete domain binding");
          if (actual->reset) {
            auto supplied = resolveCapability({index, op.getOperand(1), &op});
            auto expected = resolveCapability(*actual->reset);
            if (failed(supplied) || failed(expected) ||
                supplied->occurrence != expected->occurrence || supplied->value != expected->value)
              return reject(
                  &op,
                  "actual instance Reset operand disagrees with its exact bound reset capability");
          }
        }
      }
  }
  return success();
}
FailureOr<ExternalModuleSignature> inspectExternalModuleSignature(Operation *module) {
  auto fail = [&](StringRef reason) -> FailureOr<ExternalModuleSignature> {
    if (module)
      (void)reject(module, reason);
    return failure();
  };
  auto name = module ? module->getAttrOfType<StringAttr>("external_name") : StringAttr();
  if (!isa_and_nonnull<ModuleOp>(module) || !name || name.getValue().empty() ||
      name.getValue().size() > 256 ||
      !(llvm::isAlpha(name.getValue().front()) || name.getValue().front() == '_') ||
      !llvm::all_of(name.getValue().drop_front(),
                    [](char c) { return llvm::isAlnum(c) || c == '_' || c == '$'; }) ||
      !isa_and_nonnull<mlir::ModuleOp>(module->getParentOp()) || module->getNumRegions() != 1 ||
      !module->getRegion(0).hasOneBlock() || module->getRegion(0).front().getNumArguments())
    return fail("external module requires a canonical exact external symbol and declaration body");
  ExternalModuleSignature result{module, name, {}};
  llvm::StringSet<> pins;
  unsigned work = 0;
  for (Operation &operation : module->getRegion(0).front()) {
    if (++work > digitalTypeMaximumNodes)
      return fail("external module signature exceeds finite declaration budget");
    if (isa<InterfaceOp, InterfaceAbiOp, InterfaceInstanceOp>(operation) &&
        !hasDigitalInterfaceContract(&operation))
      return fail("external Interface signatures require fully typed declaration contracts");
    // These are signature declarations only. The Interface owner independently
    // checks the complete definition, selected role, ABI and exact outer axes.
    if (isa<InterfaceOp>(operation)) {
      if (operation.getNumRegions() != 1 || !operation.getRegion(0).hasOneBlock())
        return fail("external Interface requires its exact declaration block");
      bool hasLeaf = false;
      for (Operation &member : operation.getRegion(0).front()) {
        if (++work > digitalTypeMaximumNodes)
          return fail("external module signature exceeds finite declaration budget");
        if (!isa<InterfaceMemberOp>(member))
          continue;
        hasLeaf = true;
        auto attr = member.getAttrOfType<TypeAttr>("type");
        auto type = attr ? dyn_cast<ResolvedType>(attr.getValue()) : ResolvedType();
        if (text(&member, "protocol") != "resolved" || !type ||
            failed(inferDigitalBitPatternFixedBits(type.getElementType(), &operation)))
          return fail("external Interface definitions require only fixed resolved leaves");
      }
      if (!hasLeaf)
        return fail("external Interface definition requires at least one resolved pin leaf");
      continue;
    }
    if (isa<InterfaceAbiOp>(operation))
      continue;
    if (isa<InterfaceInstanceOp>(operation)) {
      if (!pins.insert(text(&operation, "sym_name")).second)
        return fail("external module repeats an original pin declaration identity");
      result.pins.push_back(&operation);
      continue;
    }
    auto meta = operation.getAttrOfType<DictionaryAttr>("metadata");
    auto placement = meta ? meta.getAs<StringAttr>("placement") : StringAttr();
    auto profile = meta ? meta.getAs<StringAttr>("profile") : StringAttr();
    auto type = operation.getNumResults() == 1
                    ? dyn_cast<ResolvedType>(operation.getResult(0).getType())
                    : ResolvedType();
    if (!isa<ResolvedNetOp>(operation) || !type ||
        !isa<BitsType, UIntType, SIntType, ParametricIntegerType>(type.getElementType()) ||
        !placement || placement.getValue() != "black_box_pin" || !profile ||
        profile.getValue() != "portable_boundary_only" ||
        failed(inferDigitalBitPatternFixedBits(type.getElementType(), module)) ||
        !pins.insert(text(&operation, "name")).second)
      return fail("external module initial profile permits only unique fixed scalar black-box "
                  "resolved pin declarations, never executable statements");
    if (result.pins.size() >= 65536)
      return fail("external module signature exceeds finite declaration budget");
    result.pins.push_back(&operation);
  }
  if (result.pins.empty() || failed(verifyDigitalInterfaceModel(module)))
    return fail("external module requires at least one independently verified resolved pin");
  return result;
}
LogicalResult verifyHierarchyModule(Operation *module) {
  if (module->hasAttr("external_name") && failed(inspectExternalModuleSignature(module)))
    return failure();
  auto outer = dyn_cast_or_null<mlir::ModuleOp>(module->getParentOp());
  if (!outer)
    return success();
  Operation *first = nullptr;
  for (Operation &candidate : outer.getBody()->getOperations())
    if (isa<ModuleOp>(candidate)) {
      first = &candidate;
      break;
    }
  if (module != first)
    return success();
  llvm::StringMap<Operation *> externalNames, definitions;
  for (Operation &definition : outer.getBody()->getOperations()) {
    if (!isa<ModuleOp>(definition))
      continue;
    definitions.try_emplace(text(&definition, "sym_name"), &definition);
    if (!definition.hasAttr("external_name"))
      continue;
    auto signature = inspectExternalModuleSignature(&definition);
    if (failed(signature))
      return failure();
    if (!externalNames.try_emplace(signature->externalName.getValue(), &definition).second)
      return reject(&definition, "external physical symbol has more than one declaration");
  }
  for (const auto &external : externalNames) {
    Operation *collision = definitions.lookup(external.getKey());
    if (collision && collision != external.getValue() && !collision->hasAttr("external_name"))
      return reject(external.getValue(),
                    "external physical symbol collides with a local module definition");
  }
  bool hierarchy = false, instances = false, executable = false;
  auto status = outer->walk([&](Operation *op) {
    instances |= isa<InstanceOp>(op);
    executable |= isa<LocalDomainBindingOp, SequentialUpdateOp, WaiverOp, EventProcessOp>(op) ||
                  (isa<CrossingOp, AsyncFifoOp>(op) && op->hasAttr("contract"));
    hierarchy |= contextualSequentialOperation(op) ||
                 isa<InterfaceBoundaryOp, ResolvedBoundaryOp>(op) ||
                 (isa<NetSplitOp>(op) && op->hasAttr("hierarchy_driver"));
    if (!hierarchyOperation(op))
      return WalkResult::advance();
    hierarchy = true;
    LogicalResult checked =
        isa<RequirementClockOp, RequirementResetOp>(op) ? verifyRequirementProjection(op)
        : isa<InstanceDomainBindingOp>(op) ? verifyInstanceDomainBinding(op)
                                           : success(succeeded(inspectInstancePort(op)));
    return failed(checked) ? WalkResult::interrupt() : WalkResult::advance();
  });
  if (status.wasInterrupted())
    return failure();
  if (!hierarchy && !(instances && executable))
    return success();
  auto root = outer->getAttrOfType<FlatSymbolRefAttr>("nodal.root.module");
  auto *definition = local(outer, root);
  if (!isa_and_nonnull<ModuleOp>(definition))
    return reject(module,
                  "executable hierarchy requires one exact outer nodal.root.module selector");
  auto hierarchyModel = HierarchyModel::create(outer);
  if (failed(hierarchyModel) || failed((*hierarchyModel)->verifyBindings()))
    return failure();
  // A context-dependent split cannot hide in an unused definition. Its empty
  // local dependencies are obligations, not domainless constants.
  DenseSet<Operation *> reached;
  for (const auto &occurrence : (*hierarchyModel)->occurrences())
    reached.insert(occurrence.module);
  auto obligations = outer->walk([&](NetSplitOp split) {
    if (!split->hasAttr("hierarchy_driver") || reached.contains(split->getParentOp()))
      return WalkResult::advance();
    (void)reject(split, "hierarchy-driver split belongs to an unreachable definition");
    return WalkResult::interrupt();
  });
  if (obligations.wasInterrupted())
    return failure();
  return verifyHierarchyDependencies(*hierarchyModel);
}
} // namespace nodal
