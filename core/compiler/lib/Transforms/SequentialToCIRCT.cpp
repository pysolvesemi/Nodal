#include "circt/Dialect/Comb/CombDialect.h"
#include "circt/Dialect/Comb/CombOps.h"
#include "circt/Dialect/HW/HWDialect.h"
#include "circt/Dialect/HW/HWOps.h"
#include "circt/Dialect/SV/SVDialect.h"
#include "circt/Dialect/SV/SVOps.h"
#include "circt/Dialect/Seq/SeqDialect.h"
#include "circt/Dialect/Seq/SeqOps.h"
#include "circt/Dialect/Verif/VerifDialect.h"
#include "circt/Dialect/Verif/VerifOps.h"
#include "circt/Support/BackedgeBuilder.h"
#include "mlir/IR/Builders.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Pass/PassRegistry.h"
#include "nodal/Dialect/Nodal/ClockModel.h"
#include "nodal/Dialect/Nodal/CrossingModel.h"
#include "nodal/Dialect/Nodal/DigitalInterfaceModel.h"
#include "nodal/Dialect/Nodal/DigitalMaterializationModel.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/EventModel.h"
#include "nodal/Dialect/Nodal/HierarchyBoundaryModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/PhysicalControlModel.h"
#include "nodal/Dialect/Nodal/ResetModel.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"
#include "nodal/Dialect/Nodal/StructuralIfModel.h"
#include "nodal/Dialect/Nodal/WaiverModel.h"
#include "nodal/Transforms/Passes.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/MapVector.h"
#include "llvm/ADT/ScopeExit.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringSet.h"
#include "llvm/ADT/StringSwitch.h"

#include <functional>
#include <limits>
#include <string>

using namespace mlir;
namespace nodal {
namespace {
constexpr unsigned maximumWidth = 4096;
constexpr unsigned maximumAuthoredOperations = 65536;
constexpr unsigned maximumGeneratedOperations = 262144;
constexpr StringLiteral qualityKey = "nodal.digital.quality";
constexpr StringLiteral reportKey = "nodal.digital.materialization";

LogicalResult reject(Operation *op, const Twine &message) {
  return op->emitOpError("NODAL-CIRCT-056-001: ") << message;
}
StringRef text(Operation *op, StringRef key) {
  auto value = op->getAttrOfType<StringAttr>(key);
  return value ? value.getValue() : StringRef();
}
StringRef text(DictionaryAttr attr, StringRef key) {
  auto value = attr ? attr.getAs<StringAttr>(key) : StringAttr();
  return value ? value.getValue() : StringRef();
}
StringRef sourcePath(Operation *op) {
  return text(op->getAttrOfType<DictionaryAttr>("metadata"), "semantic_path");
}
bool named(Operation *op, StringRef name) { return op->getName().getStringRef() == name; }
unsigned width(Type type) {
  if (auto t = dyn_cast<BitsType>(type))
    return t.getWidth();
  if (auto t = dyn_cast<UIntType>(type))
    return t.getWidth();
  if (auto t = dyn_cast<SIntType>(type))
    return t.getWidth();
  if (auto t = dyn_cast<EnumType>(type))
    return t.getWidth();
  if (type.isInteger(1))
    return 1;
  return 0;
}
Type targetType(Type type) {
  if (auto capability = dyn_cast<ControlCapabilityType>(type)) {
    if (capability.getKind() == "clock")
      return circt::seq::ClockType::get(type.getContext());
    if (capability.getKind() == "reset")
      return IntegerType::get(type.getContext(), 1);
  }
  unsigned bits = width(type);
  return bits && bits <= maximumWidth ? IntegerType::get(type.getContext(), bits) : Type();
}
/// Fixed physical control arrays use one bit per row-major scalar element.
/// ShapeModel remains the owner of extent proof; defaults never size an ABI.
Type targetControlShapeType(Type type, Operation *module) {
  if (!isa<nodal::ShapedType>(type) || getDigitalControlBoundaryKind(type).empty())
    return {};
  unsigned bits = 1;
  while (auto shape = dyn_cast<nodal::ShapedType>(type)) {
    SmallVector<StringRef> dimensions;
    shape.getDimensions().split(dimensions, ',');
    for (StringRef dimension : dimensions) {
      auto extent = resolveStaticShapeDimension(module, dimension);
      if (failed(extent) || extent->bounds.lower != extent->bounds.upper ||
          extent->bounds.lower <= 0 || extent->bounds.lower > maximumWidth / bits)
        return {};
      bits *= extent->bounds.lower;
    }
    type = shape.getElementType();
  }
  return IntegerType::get(type.getContext(), bits);
}
bool scalarOpcode(StringRef op) {
  return llvm::StringSwitch<bool>(op)
      .Cases({"digital_alias", "digital_select", "bool_and", "bool_or", "bool_not"}, true)
      .Cases({"digital_and", "digital_or", "digital_xor", "digital_not"}, true)
      .Cases({"digital_add", "digital_sub", "digital_mul", "digital_neg"}, true)
      .Cases({"digital_eq", "digital_ne", "digital_lt", "digital_le", "digital_gt", "digital_ge"},
             true)
      .Cases({"digital_min", "digital_max", "digital_concat", "digital_extract"}, true)
      .Cases({"digital_extend", "digital_truncate", "digital_wrap", "digital_resize_checked"}, true)
      .Cases({"digital_to_signed", "digital_to_unsigned", "digital_reinterpret_signed",
              "digital_reinterpret_unsigned"},
             true)
      .Default(false);
}
void correlate(Operation *target, Operation *source) {
  if (!sourcePath(source).empty())
    target->setAttr("nodal.source_path", StringAttr::get(source->getContext(), sourcePath(source)));
}

/// Target-only reusable module inventory. Semantic facts remain in the checked
/// occurrence model; no Nodal definitions or physical binding records are cloned.
struct DomainPortPlan {
  Attribute requirement;
  bool clock = false;
  bool reset = false;
  StringAttr edge, policy, polarity;
  std::string clockName, resetName;
};
// One target-local port inventory, shared by ordinary ports, Interface leaves,
// instances and later generated-template adapters. Keys retain exact declarations;
// the canonical semantic models still own types, roles and driver provenance.
using LogicalPortKey = std::pair<Operation *, Attribute>;
using InstanceLogicalPortKey = std::pair<Operation *, LogicalPortKey>;
struct LogicalPortPlan {
  LogicalPortKey key;
  Type originalType, targetType;
  StringAttr name;
  bool input = false;
  DictionaryAttr interfaceContract;
  SmallVector<Value> references;
  SmallVector<StringAttr> pins;
};
struct LogicalPortSignature {
  LogicalPortKey key;
  bool input;
  unsigned pins;
};
struct ModuleVariantPlan {
  nodal::ModuleOp source;
  unsigned occurrence = 0;
  std::string signature, name;
  SmallVector<DomainPortPlan> domains;
  llvm::DenseMap<Operation *, unsigned> children;
  SmallVector<LogicalPortSignature, 0> ports;
  circt::hw::HWModuleOp target;
};
struct HierarchyTargetPlan {
  std::shared_ptr<HierarchyModel> proof;
  SmallVector<ModuleVariantPlan> variants;
  SmallVector<unsigned> occurrenceVariants;
  // Borrowed endpoint facts are collected once per original definition, even
  // when material clock/reset variants emit separate physical module bodies.
  llvm::DenseMap<Operation *, std::unique_ptr<SmallVector<DigitalInterfaceEndpoint>>> interfaces;

  LogicalResult build(mlir::ModuleOp input) {
    auto checked = HierarchyModel::create(input);
    if (failed(checked) || failed((*checked)->verifyBindings()))
      return failure();
    proof = *checked;
    auto occurrences = proof->occurrences();
    occurrenceVariants.resize(occurrences.size());
    llvm::DenseMap<Operation *, llvm::StringMap<unsigned>> variantLookup;
    llvm::DenseMap<Operation *, SmallVector<unsigned>> variantsBySource;
    // Children precede parents. This inventories represented occurrences only;
    // generated families remain a later explicitly rejected profile.
    for (unsigned cursor = occurrences.size(); cursor; --cursor) {
      unsigned index = cursor - 1;
      const auto &context = occurrences[index];
      ModuleVariantPlan candidate;
      candidate.source = cast<nodal::ModuleOp>(context.module);
      candidate.occurrence = index;
      DomainModel local(context.module);
      llvm::DenseMap<Attribute, std::pair<bool, bool>> demands;
      llvm::DenseMap<std::pair<Attribute, Attribute>, Operation *> actualBindings;
      for (Operation &op : context.module->getRegion(0).front())
        if (isa<InstanceDomainBindingOp>(op))
          actualBindings.try_emplace(
              std::make_pair(op.getAttr("instance"), op.getAttr("requirement")), &op);
      auto demand = [&](Attribute domain, bool clock, bool reset) {
        if (!local.unresolvedRequirement(domain))
          return;
        auto &entry = demands[domain];
        entry.first |= clock;
        entry.second |= reset;
      };
      for (Operation &op : context.module->getRegion(0).front()) {
        if (isa<RequirementClockOp, RequirementResetOp>(op)) {
          if (llvm::any_of(op.getResult(0).getUsers(),
                           [](Operation *use) { return !isa<InstanceDomainBindingOp>(use); }))
            demand(op.getAttr("requirement"), isa<RequirementClockOp>(op),
                   isa<RequirementResetOp>(op));
        } else if (isa<ResetAssertedOp>(op)) {
          demand(op.getAttr("domain"), false, true);
        } else if (isa<SequentialUpdateOp>(op) && text(&op, "initializer_policy") == "if_reset") {
          demand(op.getAttr("domain"), true, true);
        } else if (isa<CrossingOp, AsyncFifoOp>(op)) {
          auto crossing = inspectExecutableCrossing(&op, *proof, index);
          if (failed(crossing))
            return failure();
          // Endpoint provenance remains fully checked, but only actual state
          // and monitor inputs belong to the target ABI/material signature.
          // Level chains do not sample the source clock; Gray destination
          // payload stages intentionally have no fabricated reset value.
          if (crossing->kind != CrossingKind::Level)
            demand(op.getAttr("source_domain"), true, true);
          demand(op.getAttr("destination_domain"), true, crossing->kind != CrossingKind::Gray);
        } else if (isa<InstanceOp>(op)) {
          auto child = proof->childOccurrence(index, &op);
          if (failed(child))
            return reject(&op, "target instance lacks a verified occurrence");
          unsigned variant = occurrenceVariants[*child];
          candidate.children.try_emplace(&op, variant);
          for (const auto &port : variants[variant].domains) {
            Operation *binding = actualBindings.lookup(
                {FlatSymbolRefAttr::get(input.getContext(), text(&op, "sym_name")),
                 port.requirement});
            if (!binding)
              return reject(&op, "inferred child domain port lacks its exact actual binding");
            demand(binding->getAttr("actual"), port.clock, port.reset);
          }
        }
      }
      for (Operation &op : context.module->getRegion(0).front()) {
        if (!isa<DomainRequirementOp>(op))
          continue;
        auto reference = FlatSymbolRefAttr::get(input.getContext(), text(&op, "sym_name"));
        auto found = demands.find(reference);
        if (found == demands.end())
          continue;
        auto binding = proof->resolveBinding(index, reference);
        if (failed(binding))
          return reject(&op, "inferred domain port requires actual occurrence semantics");
        DomainPortPlan port;
        port.requirement = reference;
        port.clock = found->second.first;
        port.reset = found->second.second && binding->reset.has_value();
        port.edge = binding->domain.declaration->getAttrOfType<StringAttr>("edge");
        port.policy = binding->domain.declaration->getAttrOfType<StringAttr>("reset_policy");
        port.polarity = binding->resetPolarity;
        candidate.signature += text(&op, "sym_name").str() + ":" +
                               (port.clock ? port.edge.getValue().str() : "") + ":" +
                               (port.reset ? port.policy.getValue().str() : "none") + ":" +
                               (port.reset ? port.polarity.getValue().str() : "") + ";";
        candidate.domains.push_back(std::move(port));
      }
      auto inserted =
          variantLookup[context.module].try_emplace(candidate.signature, variants.size());
      unsigned selected = inserted.first->second;
      if (inserted.second) {
        variantsBySource[context.module].push_back(selected);
        variants.push_back(std::move(candidate));
      } else if (variants[selected].children != candidate.children) {
        // The same material substitution must select the same child variants.
        // Never silently reuse a parent with a differing nested implementation.
        return reject(context.module, "materially equal parent contexts disagree on child "
                                      "variants; target profile cannot merge them");
      }
      occurrenceVariants[index] = selected;
    }
    llvm::StringSet<> moduleNames;
    for (Operation &op : input.getBody()->getOperations())
      if (auto name = op.getAttrOfType<StringAttr>("sym_name"))
        moduleNames.insert(name.getValue());
    SmallVector<unsigned> ordinals(variants.size());
    for (auto &entry : variantsBySource) {
      llvm::sort(entry.second, [&](unsigned a, unsigned b) {
        return variants[a].signature < variants[b].signature;
      });
      for (auto [ordinal, index] : llvm::enumerate(entry.second))
        ordinals[index] = ordinal;
    }
    for (auto [index, variant] : llvm::enumerate(variants)) {
      variant.name = text(variant.source, "sym_name").str();
      if (variantsBySource[variant.source].size() > 1) {
        std::string base = variant.name + "__domain_" + std::to_string(ordinals[index]);
        variant.name = base;
        for (unsigned collision = 0; !moduleNames.insert(variant.name).second;)
          variant.name = base + "_" + std::to_string(++collision);
      }
      llvm::StringSet<> names;
      for (Operation &op : variant.source.getBody().front())
        for (StringRef key : {"sym_name", "name"})
          if (auto name = op.getAttrOfType<StringAttr>(key))
            names.insert(name.getValue());
      auto reserve = [&](std::string base) {
        std::string name = base;
        for (unsigned collision = 0; !names.insert(name).second;)
          name = base + "_" + std::to_string(++collision);
        return name;
      };
      for (auto &port : variant.domains) {
        auto name = cast<FlatSymbolRefAttr>(port.requirement).getValue();
        if (port.clock)
          port.clockName = reserve("__nodal_" + name.str() + "_clock");
        if (port.reset)
          port.resetName = reserve("__nodal_" + name.str() + "_reset");
      }
    }
    return success();
  }
};

/// Only the target representation lives here. Priority, graph cuts, physical
/// driver ownership, enum ABI and materialization decisions stay with their
/// existing independently verified Nodal owners.
class ModuleLowering {
public:
  ModuleLowering(nodal::ModuleOp source, OpBuilder &builder, uint64_t &cost,
                 HierarchyTargetPlan *hierarchy = nullptr, ModuleVariantPlan *variant = nullptr,
                 bool namedBoundaryABI = false, bool preserveConstantLoops = false)
      : source(source), builder(builder), cost(cost), domains(source), hierarchy(hierarchy),
        variant(variant), namedBoundaryABI(namedBoundaryABI),
        preserveConstantLoops(preserveConstantLoops) {}

  LogicalResult preflight() {
    if (failed(domains.verifyRelationships()) || failed(planControlProjections()) ||
        failed(planParameters()) || failed(planInterfaces()) || failed(planGeneratedFamilies()))
      return failure();
    SmallVector<std::pair<Value, Operation *>> seeds;
    for (Operation &op : source.getBody().front()) {
      StringRef name = op.getName().getStringRef();
      if (staticOperations.contains(&op))
        continue;
      if (op.getNumRegions() || op.getNumSuccessors())
        return reject(&op, "target profile requires flat normalized scalar operations");
      bool structural = controlProjections.contains(&op) || controlSelectors.contains(&op) ||
                        generatedMembers.count(&op);
      if (!structural) {
        bool aggregate = llvm::any_of(op.getOperandTypes(), aggregatePayload) ||
                         llvm::any_of(op.getResultTypes(), aggregatePayload);
        if (aggregate &&
            !isa<PortValueOp, InstancePortOp, MemberAccessOp, InterfaceDriveOp, InterfaceConnectOp,
                 InterfaceObserveOp, CrossingOp, AsyncFifoOp, WaiverOp, DigitalAssignOp,
                 EventProcessOp>(op) &&
            !(isa<DigitalExprOp>(op) &&
              (text(&op, "operator_name") == "digital_alias" ||
               text(&op, "operator_name") == "digital_select" ||
               (namedBoundaryABI && (text(&op, "operator_name") == "digital_struct_get" ||
                                     text(&op, "operator_name") == "digital_struct_make")))))
          return reject(
              &op,
              "fixed aggregate target profile supports only whole-value Gray/handshake/FIFO "
              "crossings, event storage and whole boundary/alias/select connections; projections "
              "require an explicit packed-offset lowering");
        for (Type type : op.getOperandTypes())
          if (!targetValueType(type))
            return reject(
                &op, "operand requires fixed scalar/packed width 1..4096 or a direct clock/reset");
        for (Type type : op.getResultTypes())
          if (!targetValueType(type) &&
              !(isa<PortValueOp>(op) && targetControlShapeType(type, source)))
            return reject(
                &op, "result requires fixed scalar/packed width 1..4096 or a fixed control input");
      }
      bool symbolic =
          llvm::any_of(op.getOperandTypes(),
                       [&](Type type) {
                         return isa_and_nonnull<circt::hw::IntType, circt::hw::StructType>(
                             targetValueType(type));
                       }) ||
          llvm::any_of(op.getResultTypes(), [&](Type type) {
            return isa_and_nonnull<circt::hw::IntType, circt::hw::StructType>(
                targetValueType(type));
          });
      if (symbolic &&
          !isa<PortValueOp, InstancePortOp, MemberAccessOp, InterfaceDriveOp, InterfaceConnectOp,
               InterfaceObserveOp, DigitalAssignOp>(op) &&
          !(isa<DigitalStorageOp>(op) && text(&op, "kind") == "wire") &&
          !(isa<DigitalExprOp>(op) &&
            llvm::is_contained(
                ArrayRef<StringRef>{"digital_alias", "digital_struct_get", "digital_struct_make"},
                text(&op, "operator_name"))))
        return reject(&op, "symbolic boundary target currently admits whole-value connections, "
                           "wires and aliases only");
      uint64_t expansion = 1;
      if (structural) {
        // Shape/index expressions are compile-time proof, not hardware values.
        expansion = controlProjections.contains(&op) ? 2 : 0;
      } else if (isa<PortOp>(op)) {
        auto type = op.getAttrOfType<TypeAttr>("type");
        Type lowered = type ? targetValueType(type.getValue()) : Type();
        if (type && !lowered)
          lowered = targetControlShapeType(type.getValue(), source);
        if (type && !lowered && !namedBoundaryABI) {
          auto view = boundaryViews.find(type.getValue());
          if (view != boundaryViews.end() &&
              llvm::any_of(view->second.leaves,
                           [](const auto &leaf) { return !leaf.path.empty(); }))
            return reject(&op, "symbolic Struct boundaries require boundary-abi=named-fields");
        }
        if (!type || !lowered ||
            (text(&op, "direction") != "input" && text(&op, "direction") != "output"))
          return reject(&op, "only supported scalar/packed input/output and fixed Clock/Reset "
                             "input ports are supported");
        if (text(&op, "direction") == "output" &&
            !getDigitalControlBoundaryKind(type.getValue()).empty())
          return reject(&op, "clock/reset output capabilities require a later boundary profile");
        ports.push_back(&op);
        portTypes.try_emplace(&op, lowered);
      } else if (isa<PortValueOp>(op)) {
        auto ref = op.getAttrOfType<FlatSymbolRefAttr>("port");
        auto *port = SymbolTable::lookupSymbolIn(source, ref);
        portValues[port].push_back(op.getResult(0));
        if (text(port, "direction") == "output")
          seeds.emplace_back(op.getResult(0), &op);
      } else if (isa<InstancePortOp>(op)) {
        auto boundary = inspectInstancePort(&op);
        if (!hierarchy || failed(boundary))
          return reject(&op, "digital instance boundary requires its checked hierarchy plan");
        instancePortValues[{boundary->instance, {boundary->port, Attribute()}}].push_back(
            op.getResult(0));
        if (auto type = dyn_cast<circt::hw::StructType>(targetValueType(op.getResult(0).getType())))
          expansion += 2 * type.getElements().size();
        if (text(&op, "direction") == "input")
          seeds.emplace_back(op.getResult(0), &op);
      } else if (isa<MemberAccessOp>(op)) {
        auto reference = op.getAttrOfType<FlatSymbolRefAttr>("instance");
        Operation *endpoint = reference ? SymbolTable::lookupSymbolIn(source, reference) : nullptr;
        auto found = interfaceEndpoints.find(endpoint);
        if (found == interfaceEndpoints.end())
          return reject(&op, "Interface access requires its complete checked endpoint");
        const auto &facts = endpoints[found->second];
        auto path = op.getAttrOfType<StringAttr>("path");
        LogicalPortKey key{facts.declaration, path};
        if (facts.instance)
          instancePortValues[{facts.instance, key}].push_back(op.getResult(0));
        else
          interfacePortValues[key].push_back(op.getResult(0));
        interfaceValues.insert(op.getResult(0));
        seeds.emplace_back(op.getResult(0), &op);
      } else if (isa<InterfaceDriveOp, InterfaceConnectOp, InterfaceObserveOp>(op)) {
        // The existing dependency graph owns sink orientation and exact drivers.
        expansion = 0;
      } else if (isa<InstanceOp>(op)) {
        if (!variant || !variant->children.count(&op))
          return reject(&op, "hierarchy target requires an exact verified child variant");
        auto actuals = instanceParameters(&op);
        if (failed(actuals))
          return reject(&op, "instance parameters require exact supported static Integer actuals");
        targetInstanceParameters.try_emplace(&op, *actuals);
        instances.push_back(&op);
      } else if (isa<InstanceDomainBindingOp>(op)) {
        if (!hierarchy)
          return reject(&op, "instance domain operands require contextual hierarchy admission");
        auto reference = op.getAttrOfType<FlatSymbolRefAttr>("instance");
        auto *instance = SymbolTable::lookupSymbolIn(source, reference);
        instanceBindings.try_emplace(std::make_pair(instance, op.getAttr("requirement")), &op);
      } else if (isa<RequirementClockOp, RequirementResetOp>(op)) {
        if (!hierarchy)
          return reject(&op, "formal domain projection requires contextual hierarchy admission");
        expansion = 0;
      } else if (isa<DomainOp>(op)) {
        // Unused descriptors remain descriptors. Executable bindings and every
        // stateful consumer are independently verified before this transaction.
      } else if (isa<DomainRequirementOp, DomainBindOp, LocalDomainBindingOp>(op)) {
        // Verified provenance only; actual values already carry the connection.
      } else if (named(&op, "nodal.clock_gate") || named(&op, "nodal.clock_mux")) {
        if (failed(inspectOwnedClock(&op, domains)))
          return failure();
        clocks.push_back(&op);
        // Closed fixed implementation: one uninitialized gate latch, or 22 mux
        // control/release bits. Output-domain release is a separately charged
        // authored reset_sync. Include monitors and correlation comments.
        expansion = named(&op, "nodal.clock_mux") ? 192 : 16;
      } else if (isa<ClockRelationshipOp>(op)) {
        if (failed(verifyClockRelationship(&op)))
          return failure();
        relationships.push_back(&op);
        expansion = 3;
      } else if (isa<ResetBoundaryOp, ResetSyncOp, ResetCombineOp>(op)) {
        if (failed(verifyResetProducer(&op)))
          return failure();
        resets.push_back(&op);
        expansion = isa<ResetSyncOp>(op) ? 3 * op.getAttrOfType<IntegerAttr>("stages").getInt() + 5
                                         : 2 * op.getNumOperands() + 2;
      } else if (isa<CrossingOp, AsyncFifoOp>(op)) {
        auto crossing = hierarchy
                            ? inspectExecutableCrossing(&op, *hierarchy->proof, variant->occurrence)
                            : inspectExecutableCrossing(&op, domains);
        if (failed(crossing))
          return failure();
        // The canonical model verifies type, endpoints, source sampling and
        // independent identity. Only the finite target expansion belongs here.
        crossings.push_back(*crossing);
        // Bounds cover state, reset logic, source-clock runtime monitors and
        // retained obligations. Payload bits were independently bounded by the
        // native model; this profile additionally requires fixed scalar/packed types.
        expansion = crossing->kind == CrossingKind::Fifo        ? 10 * crossing->depth + 192
                    : crossing->kind == CrossingKind::Handshake ? 80
                    : crossing->kind == CrossingKind::Pulse     ? 64
                    : crossing->kind == CrossingKind::Gray      ? 2 * crossing->stages + 32
                                                                : 3 * crossing->stages + 8;
      } else if (isa<WaiverOp>(op)) {
        if (failed(inspectExecutableWaiver(&op, domains)))
          return failure();
        expansion = 2; // One retained obligation and at most one identity wire; no state.
      } else if (isa<EventProcessOp>(op)) {
        auto event = inspectEventProcess(&op);
        if (failed(event))
          return failure();
        events.push_back(*event);
        // One private clock conversion, optional inversion, retained contract,
        // and one next-value backedge plus state for each canonical output.
        expansion = 3 + 2 * event->storagePorts.size();
      } else if (isa<DigitalStorageOp>(op)) {
        if (text(&op, "kind") == "register")
          registers.push_back(&op);
        else if (text(&op, "kind") == "wire")
          seeds.emplace_back(op.getResult(0), &op);
        else
          return reject(&op, "unknown scalar storage kind");
        expansion = 4;
      } else if (isa<SequentialUpdateOp>(op)) {
        if (!updates.try_emplace(op.getOperand(0), &op).second)
          return reject(&op, "register requires exactly one normalized clocked update");
        expansion = 5;
      } else if (isa<DigitalAssignOp>(op)) {
        if (text(&op, "kind") != "continuous" && text(&op, "kind") != "event_update")
          return reject(&op, "unnormalized or unbound register intent is not executable");
      } else if (isa<ConstantOp, ConstLiteralOp>(op)) {
        if (!op.getAttrOfType<IntegerAttr>("value"))
          return reject(&op, "scalar literal requires an integer bit pattern");
      } else if (isa<SequentialSelectOp>(op) || name == "nodal.reset_asserted") {
        expansion = 2;
      } else if (name == "nodal.sequential_assert") {
        expansion = 2;
      } else if (isa<DigitalExprOp>(op)) {
        if (!scalarOpcode(text(&op, "operator_name")) &&
            !llvm::is_contained(ArrayRef<StringRef>{"digital_struct_get", "digital_struct_make"},
                                text(&op, "operator_name")))
          return reject(&op,
                        "unsupported fixed-scalar operation '" + text(&op, "operator_name") + "'");
        if (text(&op, "operator_name") == "digital_alias" &&
            !source->getParentOfType<mlir::ModuleOp>()->hasAttr(qualityKey))
          return reject(&op, "authored alias/materialization intent requires explicit "
                             "nodal.digital.quality for the canonical analyzer");
        expansion = 12;
        StringRef code = text(&op, "operator_name");
        if (code == "digital_struct_get" || code == "digital_struct_make") {
          Type aggregate =
              code == "digital_struct_get" ? op.getOperand(0).getType() : op.getResult(0).getType();
          if (!isa_and_nonnull<circt::hw::StructType>(targetValueType(aggregate)))
            return reject(&op, "logical field adapters require the checked named boundary ABI");
          expansion = 2 * boundaryViews.find(aggregate)->second.leaves.size() + 2;
        }
      } else if (isa<EnumExprOp>(op)) {
        StringRef code = text(&op, "operator_name");
        if (code != "enum_literal" && code != "enum_eq" && code != "enum_as_bits" &&
            code != "enum_is_one_of" && code != "enum_is_valid" && code != "enum_decode")
          return reject(&op, "unsupported canonical enum operation '" + code + "'");
        auto reference = op.getAttrOfType<FlatSymbolRefAttr>("enum_ref");
        auto inserted = enumCodes.try_emplace(reference);
        if (inserted.second) {
          auto *definition =
              SymbolTable::lookupSymbolIn(source->getParentOfType<mlir::ModuleOp>(), reference);
          for (Operation &entry : definition->getRegion(0).front()) {
            auto value = entry.getAttrOfType<IntegerAttr>("value");
            inserted.first->second.byId.try_emplace(
                text(entry.getAttrOfType<DictionaryAttr>("metadata"), "case_id"), value);
            inserted.first->second.inDeclarationOrder.push_back(value);
          }
        }
        auto cases = op.getAttrOfType<ArrayAttr>("case_ids");
        uint64_t tests = code == "enum_is_valid" || code == "enum_decode"
                             ? inserted.first->second.inDeclarationOrder.size()
                             : cases.size();
        expansion = code == "enum_is_one_of" || code == "enum_is_valid" || code == "enum_decode"
                        ? 3 * tests + 4
                        : 3;
      } else
        return reject(&op, "operation is outside the finite scalar CIRCT profile");
      // Producer references are temporary graph operations too. Charge their
      // exact result count before creating any backedges, in addition to the
      // persistent target expansion. Alias results are conservatively included.
      if (!structural && !isa<PortValueOp>(op))
        expansion += op.getNumResults();
      if (expansion > maximumGeneratedOperations - cost)
        return reject(&op, "target expansion exceeds 262144 operations before allocation");
      cost += expansion;
    }
    for (Operation *reg : registers)
      if (!updates.count(reg->getResult(0)))
        return reject(reg, "declaration-only register lacks an executable physical binding/update");
    if (failed(planLogicalPorts()))
      return failure();
    for (const auto &port : logicalPorts)
      if (!port.input && port.references.empty())
        return reject(port.key.first, "output has no actual value/driver; no output is invented");
    for (Operation *instance : instances) {
      const auto &child = hierarchy->variants[variant->children.lookup(instance)];
      for (const auto &port : child.ports)
        if (port.input && !instancePortValues.count({instance, port.key}))
          return reject(instance, "target instance input requires an explicit typed connection");
      for (const auto &port : child.domains)
        if (!instanceBindings.count({instance, port.requirement}))
          return reject(instance, "target inferred control requires its exact actual binding");
    }
    // Values are read at the final module boundary, never at their own producer
    // operation (which would violate the shared lexical SSA-use contract).
    for (auto &seed : seeds)
      seed.second = &source.getBody().front().back();
    auto graph = analyzeDigitalAssignmentDependencies(source, seeds);
    if (failed(graph))
      return failure();
    for (const auto &update : graph->eventUpdates) {
      const auto &write = graph->nodes[update.root];
      if (write.value || write.use != update.operation || write.dependencies.size() != 1)
        return reject(update.operation, "event update requires its canonical assignment edge");
      auto ref =
          update.operation->getOperand(0).getDefiningOp()->getAttrOfType<FlatSymbolRefAttr>("port");
      Operation *port = SymbolTable::lookupSymbolIn(source, ref);
      Value rhs = graph->nodes[write.dependencies.front()].value;
      if (!rhs || !eventUpdates.try_emplace(port, rhs).second)
        return reject(update.operation, "event output requires one canonical whole-value update");
    }
    // The finite profile has only direct whole-scalar connections. Read the
    // canonical graph's unique write edge, rather than collecting another set
    // of mutable driver facts or reimplementing physical overlap/coverage.
    for (unsigned i = 0; i < seeds.size(); ++i) {
      const auto &root = graph->nodes[graph->roots[i]];
      if (auto pin = seeds[i].first.getDefiningOp<PortValueOp>())
        if (generatedOutputPorts.count(
                SymbolTable::lookupSymbolIn(source, pin->getAttrOfType<FlatSymbolRefAttr>("port"))))
          continue; // ShapeModel already proved the complete family write partition.
      if (interfaceValues.contains(seeds[i].first)) {
        if (root.dependencies.empty())
          continue; // Exact local input/child output, mapped from its physical pin below.
        if (root.dependencies.size() != 1 || !graph->nodes[root.dependencies.front()].value)
          return reject(seeds[i].first.getDefiningOp(),
                        "ordinary Interface requires one canonical value driver");
        aliases.try_emplace(seeds[i].first, graph->nodes[root.dependencies.front()].value);
        continue;
      }
      if (root.eventProcess)
        continue; // A verified event output reads persistent old state, not its writer.
      if (root.dependencies.size() != 1)
        return reject(seeds[i].second,
                      "scalar connection requires one verified whole-value writer");
      const auto &write = graph->nodes[root.dependencies.front()];
      if (write.value || !isa<DigitalAssignOp>(write.use) || write.dependencies.size() != 1)
        return reject(seeds[i].second,
                      "scalar connection requires a direct canonical assignment edge");
      Value value = graph->nodes[write.dependencies.front()].value;
      if (!value)
        return reject(seeds[i].second, "canonical assignment lacks its actual RHS value");
      aliases.try_emplace(seeds[i].first, value);
    }
    llvm::StringSet<> targetNames;
    for (const auto &port : logicalPorts)
      if (!port.interfaceContract && !targetNames.insert(port.name.getValue()).second)
        return reject(port.key.first, "authored target port names collide");
    for (auto &port : logicalPorts)
      if (port.interfaceContract) {
        std::string base = port.name.getValue().str(), name = base;
        unsigned collision = 0;
        while (!targetNames.insert(name).second)
          name = base + "_" + std::to_string(++collision);
        port.name = builder.getStringAttr(name);
      }
    for (const auto &port : logicalPorts)
      if (port.input && port.interfaceContract && !port.references.empty() &&
          aliases.count(port.references.front()))
        return reject(port.key.first, "ordinary Interface input pin cannot have a local driver");
    for (const auto &port : logicalPorts)
      if (!port.input && !aliases.count(port.references.front()) &&
          !eventUpdates.count(port.key.first) && !generatedOutputPorts.count(port.key.first))
        return reject(port.key.first, "output has no canonical driver; no output is invented");
    for (Operation *instance : instances) {
      const auto &child = hierarchy->variants[variant->children.lookup(instance)];
      for (const auto &port : child.ports)
        if (port.input &&
            !aliases.count(instancePortValues.find({instance, port.key})->second.front()))
          return reject(instance, "target instance input has no canonical driver");
    }
    if (variant)
      for (const auto &port : variant->domains) {
        if (port.clock)
          targetNames.insert(port.clockName);
        if (port.reset)
          targetNames.insert(port.resetName);
      }
    for (Operation &op : source.getBody().front())
      if (isa<DigitalStorageOp>(op) && !targetNames.insert(text(&op, "name")).second)
        return reject(&op, "target storage name collides with an existing port/storage name");
    if (source->getParentOfType<mlir::ModuleOp>()->hasAttr(qualityKey)) {
      auto report = analyzeDigitalMaterialization(source);
      if (failed(report))
        return failure();
      for (Attribute attr : report->getAs<ArrayAttr>("decisions")) {
        auto decision = cast<DictionaryAttr>(attr);
        if (text(decision, "decision") != "materialize-required")
          continue;
        auto intent = decision.getAs<DictionaryAttr>("intent");
        if (text(intent, "kind") == "named" && !targetNames.insert(text(intent, "value")).second)
          return reject(
              source,
              "authored exact target name collides with another port/storage/materialization name");
        materializations.try_emplace(text(decision, "path"), decision);
      }
    }
    for (auto &port : logicalPorts) {
      if (auto type = dyn_cast<circt::hw::StructType>(port.targetType)) {
        if (type.getElements().size() > (maximumGeneratedOperations - cost) / 4)
          return reject(port.key.first,
                        "named boundary adapters exceed finite target expansion budget");
        cost += 4 * type.getElements().size();
        for (const auto &field : type.getElements()) {
          std::string base = port.name.getValue().str() + "__" + field.name.getValue().str();
          std::string name = base;
          unsigned collision = 0;
          while (!targetNames.insert(name).second)
            name = base + "_" + std::to_string(++collision);
          port.pins.push_back(builder.getStringAttr(name));
        }
      } else
        port.pins.push_back(port.name);
    }
    if (variant)
      for (const auto &port : logicalPorts)
        variant->ports.push_back({port.key, port.input, unsigned(port.pins.size())});
    // Reserve generated names after every authored exact name is known.
    // Symbols keep even identical-source chains distinct through target CSE.
    for (const auto &event : events) {
      for (Operation *port : event.storagePorts) {
        if (!eventUpdates.count(port))
          return reject(port, "event storage lacks its verified canonical update");
        std::string base = "__nodal_event_" + std::to_string(eventNames.size()) + "_" +
                           text(port, "sym_name").str();
        std::string name = base;
        unsigned collision = 0;
        while (!targetNames.insert(name).second)
          name = base + "_" + std::to_string(++collision);
        eventNames.try_emplace(port, builder.getStringAttr(name));
      }
    }
    for (const auto &crossing : crossings) {
      SmallVector<StringAttr> names;
      SmallVector<std::string> roles;
      if (crossing.kind == CrossingKind::Pulse)
        roles = {"source_toggle",       "previous_pulse", "forward_0", "forward_1",
                 "destination_history", "return_0",       "return_1"};
      else if (crossing.kind == CrossingKind::Handshake)
        roles = {"source_payload",           "destination_payload",
                 "source_request",           "destination_request_0",
                 "destination_request_1",    "destination_acknowledgement",
                 "source_acknowledgement_0", "source_acknowledgement_1"};
      else if (crossing.kind == CrossingKind::Fifo) {
        roles = {"source_binary",
                 "source_gray",
                 "source_read_gray_0",
                 "source_read_gray_1",
                 "destination_binary",
                 "destination_gray",
                 "destination_write_gray_0",
                 "destination_write_gray_1"};
        for (unsigned entry = 0; entry < crossing.depth; ++entry)
          roles.push_back("bank_" + std::to_string(entry));
        for (StringRef role : {"source_previous_payload", "source_stalled",
                               "destination_previous_payload", "destination_stalled"})
          roles.push_back(role.str());
      } else {
        for (unsigned stage = 0; stage < crossing.stages; ++stage)
          roles.push_back("stage_" + std::to_string(stage));
        if (crossing.kind == CrossingKind::Gray) {
          roles.push_back("previous_payload");
          roles.push_back("seen_sample");
        }
      }
      StringRef kind = crossing.kind == CrossingKind::Level   ? "sync"
                       : crossing.kind == CrossingKind::Gray  ? "gray"
                       : crossing.kind == CrossingKind::Pulse ? "pulse"
                       : crossing.kind == CrossingKind::Fifo  ? "fifo"
                                                              : "handshake";
      for (const std::string &role : roles) {
        std::string base =
            "__nodal_" + kind.str() + "_" + std::to_string(crossingNames.size()) + "_" + role;
        std::string name = base;
        unsigned collision = 0;
        while (!targetNames.insert(name).second)
          name = base + "_" + std::to_string(++collision);
        names.push_back(builder.getStringAttr(name));
      }
      crossingNames.try_emplace(crossing.operation, std::move(names));
    }
    for (Operation *clock : clocks) {
      SmallVector<StringRef> roles;
      if (named(clock, "nodal.clock_gate"))
        roles = {"gate", "output"};
      else
        roles = {"select_0",
                 "select_1",
                 "controller",
                 "selected",
                 "branch0_request_0",
                 "branch0_request_1",
                 "branch0_enable",
                 "branch1_request_0",
                 "branch1_request_1",
                 "branch1_enable",
                 "branch0_ack_0",
                 "branch0_ack_1",
                 "branch1_ack_0",
                 "branch1_ack_1",
                 "controller_release_0",
                 "controller_release_1",
                 "branch0_release_0",
                 "branch0_release_1",
                 "branch1_release_0",
                 "branch1_release_1",
                 "output"};
      auto &names = clockNames[clock];
      for (StringRef role : roles) {
        std::string base =
            "__nodal_clock_" + std::to_string(clockNames.size() - 1) + "_" + role.str();
        std::string name = base;
        unsigned collision = 0;
        while (!targetNames.insert(name).second)
          name = base + "_" + std::to_string(++collision);
        names.try_emplace(role, builder.getStringAttr(name));
      }
    }
    for (Operation *reset : resets) {
      if (!isa<ResetSyncOp>(reset))
        continue;
      SmallVector<StringAttr> names;
      unsigned stages = reset->getAttrOfType<IntegerAttr>("stages").getInt();
      for (unsigned i = 0; i < stages; ++i) {
        std::string base =
            "__nodal_reset_" + std::to_string(resetNames.size()) + "_" + std::to_string(i);
        std::string name = base;
        unsigned collision = 0;
        while (!targetNames.insert(name).second)
          name = base + "_" + std::to_string(++collision);
        names.push_back(builder.getStringAttr(name));
      }
      resetNames.try_emplace(reset, std::move(names));
    }
    return success();
  }

  LogicalResult lower() {
    for (auto &family : generatedFamilies)
      if (failed(lowerGeneratedHelper(*family)))
        return failure();
    SmallVector<circt::hw::PortInfo> targetPorts;
    unsigned inputIndex = 0, outputIndex = 0;
    appendPhysicalPorts(logicalPorts, targetPorts, inputIndex, outputIndex);
    if (variant)
      for (const auto &port : variant->domains) {
        auto add = [&](StringRef name, bool reset) {
          circt::hw::PortInfo info;
          info.name = builder.getStringAttr(name);
          info.type = reset ? Type(builder.getI1Type())
                            : Type(circt::seq::ClockType::get(builder.getContext()));
          info.dir = circt::hw::ModulePort::Input;
          info.argNum = inputIndex++;
          info.loc = source.getLoc();
          info.attrs = builder.getDictionaryAttr(
              {builder.getNamedAttr("nodal.domain_requirement", port.requirement),
               builder.getNamedAttr("nodal.inferred_control",
                                    builder.getStringAttr(reset ? "reset" : "clock"))});
          targetPorts.push_back(info);
        };
        if (port.clock)
          add(port.clockName, false);
        if (port.reset)
          add(port.resetName, true);
      }
    builder.setInsertionPoint(source);
    auto target =
        circt::hw::HWModuleOp::create(builder, source.getLoc(),
                                      variant ? builder.getStringAttr(variant->name)
                                              : source->getAttrOfType<StringAttr>("sym_name"),
                                      circt::hw::ModulePortInfo(targetPorts), targetParameters);
    if (variant) {
      variant->target = target;
      target->setAttr("nodal.definition", source->getAttr("sym_name"));
      target->setAttr("nodal.domain_variant", builder.getStringAttr(variant->signature));
    }
    SmallVector<Attribute> boundaryMap;
    for (const auto &port : logicalPorts)
      if (port.interfaceContract || isa<circt::hw::StructType>(port.targetType)) {
        SmallVector<Attribute> pins(port.pins.begin(), port.pins.end());
        NamedAttrList entry;
        entry.set("logical_port", port.name);
        entry.set("direction", builder.getStringAttr(port.input ? "input" : "output"));
        entry.set("physical_pins", builder.getArrayAttr(pins));
        if (aggregatePayload(port.originalType))
          entry.set("layout", packedLayout(port.originalType));
        if (port.interfaceContract)
          entry.set("interface", port.interfaceContract);
        boundaryMap.push_back(builder.getDictionaryAttr(entry));
      }
    if (!boundaryMap.empty())
      target->setAttr("nodal.boundary_map", builder.getArrayAttr(boundaryMap));
    correlate(target, source);
    Block *body = target.getBodyBlock();
    builder.setInsertionPoint(body->getTerminator());
    for (const auto &port : logicalPorts) {
      if (!aggregatePayload(port.originalType))
        continue;
      std::string contract;
      llvm::raw_string_ostream stream(contract);
      stream << "// NODAL packed ABI: " << port.name.getValue()
             << "; layout=recursive-row-major-fields-lsb-first; "
             << packedLayout(port.originalType);
      auto comment = circt::sv::VerbatimOp::create(builder, port.key.first->getLoc(),
                                                   builder.getStringAttr(contract), ValueRange{},
                                                   builder.getArrayAttr({}));
      correlate(comment, port.key.first);
    }
    circt::BackedgeBuilder backedges(builder, source.getLoc());
    inputIndex = 0;
    for (const auto &port : logicalPorts) {
      if (!port.input)
        continue;
      SmallVector<Value> leaves;
      for (unsigned index = 0; index < port.pins.size(); ++index)
        leaves.push_back(body->getArgument(inputIndex++));
      Value joined = joinBoundary(port.key.first, port.targetType, leaves);
      for (Value value : port.references)
        values[value] = joined;
    }
    if (variant)
      for (const auto &port : variant->domains) {
        Value clock = port.clock ? body->getArgument(inputIndex++) : Value();
        Value reset = port.reset ? body->getArgument(inputIndex++) : Value();
        domainInputs.try_emplace(port.requirement, std::make_pair(clock, reset));
      }
    // A projection is a statically proved reference into its actual input pin,
    // not a runtime array operation. Install scalar mappings before any reset
    // producers, synchronizer stages, assertions or registers consume them.
    for (const auto &[original, control] : physicalControls) {
      Value input = values.lookup(control.root);
      if (!input)
        return reject(original.getDefiningOp(), "physical control root did not map");
      Value bit = circt::comb::ExtractOp::create(builder, original.getLoc(), input,
                                                 *control.literalOffset, 1);
      correlate(bit.getDefiningOp(), original.getDefiningOp());
      if (cast<ControlCapabilityType>(original.getType()).getKind() == "clock") {
        bit = circt::seq::ToClockOp::create(builder, original.getLoc(), bit);
        correlate(bit.getDefiningOp(), original.getDefiningOp());
      }
      values[original] = bit;
    }
    // Clock/reset/state graphs interleave: a gate may consume register or CDC
    // output, and a release chain may consume that gate. Install every actual
    // result reference before consumers, then resolve the independently checked
    // dependency graph. A phase ordering must never replace a derived control
    // by a raw pin or reject a legitimate state-to-clock dependency.
    SmallVector<std::pair<Value, circt::Backedge>> producerEdges;
    for (Operation &op : source.getBody().front()) {
      if (staticOperations.contains(&op) ||
          isa<PortValueOp, RequirementClockOp, RequirementResetOp>(op) ||
          (isa<MemberAccessOp>(op) && values.count(op.getResult(0))) ||
          controlProjections.contains(&op) || controlSelectors.contains(&op))
        continue;
      for (Value original : op.getResults()) {
        if (aliases.count(original))
          continue;
        auto edge = backedges.get(targetValueType(original.getType()), op.getLoc());
        values[original] = edge;
        producerEdges.emplace_back(original, edge);
      }
    }
    // Formal projections map to this definition's inferred inputs, never to
    // parent SSA. Locally bound aliases use their actual existing producer.
    for (Operation &op : source.getBody().front())
      if (isa<RequirementClockOp, RequirementResetOp>(op)) {
        Attribute reference = op.getAttr("requirement");
        auto found = domainInputs.find(reference);
        Value actual;
        if (found != domainInputs.end())
          actual = isa<RequirementResetOp>(op) ? found->second.second : found->second.first;
        else if (auto binding = domains.resolveBinding(reference); succeeded(binding)) {
          unsigned operand = isa<RequirementResetOp>(op) ? 1 : 0;
          if ((*binding)->getNumOperands() > operand)
            actual = values.lookup((*binding)->getOperand(operand));
        }
        if (!actual && llvm::any_of(op.getResult(0).getUsers(), [](Operation *use) {
              return !isa<InstanceDomainBindingOp>(use);
            }))
          return reject(&op, "used formal capability has no target-local input");
        if (actual)
          values[op.getResult(0)] = actual;
      }
    SmallVector<std::pair<Value, circt::Backedge>> connectionEdges;
    // Install graph cuts first. No combinational value can accidentally become
    // state: all these references are resolved before the candidate verifies.
    for (Operation &op : source.getBody().front()) {
      if (!op.getNumResults())
        continue;
      Value original = op.getResult(0);
      if (!aliases.count(original))
        continue;
      auto edge = backedges.get(targetValueType(original.getType()), op.getLoc());
      values[original] = edge;
      connectionEdges.emplace_back(original, edge);
    }
    if (failed(lowerGeneratedFamilies())) {
      backedges.abandon();
      return failure();
    }
    for (Operation *instance : instances) {
      const auto &child = hierarchy->variants[variant->children.lookup(instance)];
      if (!child.target)
        return reject(instance, "target child definition was not materialized before its parent");
      SmallVector<Value> inputs;
      for (const auto &port : child.ports)
        if (port.input) {
          auto &references = instancePortValues.find({instance, port.key})->second;
          auto leaves = splitBoundary(instance, values.lookup(references.front()));
          llvm::append_range(inputs, leaves);
        }
      for (const auto &port : child.domains) {
        Operation *binding = instanceBindings.lookup({instance, port.requirement});
        if (port.clock)
          inputs.push_back(values.lookup(binding->getOperand(0)));
        if (port.reset)
          inputs.push_back(values.lookup(binding->getOperand(1)));
      }
      if (llvm::any_of(inputs, [](Value value) { return !value; }))
        return reject(instance, "target instance actual input did not map");
      auto targetInstance = circt::hw::InstanceOp::create(
          builder, instance->getLoc(), child.target,
          instance->getAttrOfType<StringAttr>("sym_name"), ArrayRef<Value>(inputs),
          targetInstanceParameters.lookup(instance),
          circt::hw::InnerSymAttr::get(instance->getAttrOfType<StringAttr>("sym_name")));
      correlate(targetInstance, instance);
      targetInstance->setAttr("nodal.domain_bindings", instance->getAttr("domain_bindings"));
      unsigned result = 0;
      for (const auto &port : child.ports)
        if (!port.input) {
          auto references = instancePortValues.find({instance, port.key});
          if (references == instancePortValues.end()) {
            result += port.pins;
            continue; // A real complete output may intentionally be unobserved.
          }
          Type actualType = targetValueType(references->second.front().getType());
          SmallVector<Value> leaves;
          for (unsigned index = 0; index < port.pins; ++index)
            leaves.push_back(targetInstance.getResult(result++));
          Value joined = joinBoundary(instance, actualType, leaves);
          for (Value reference : references->second)
            values[reference] = joined;
        }
    }
    SmallVector<std::pair<Value, circt::Backedge>> eventEdges;
    for (const auto &event : events) {
      Operation *op = event.operation;
      Value trigger = values.lookup(event.trigger);
      if (!trigger)
        return reject(op, "verified event trigger did not map to its actual root input");
      Value clock = circt::seq::ToClockOp::create(builder, op->getLoc(), trigger);
      correlate(clock.getDefiningOp(), op);
      if (event.edge == "falling") {
        clock = circt::seq::ClockInverterOp::create(builder, op->getLoc(), clock);
        correlate(clock.getDefiningOp(), op);
      }
      // Predeclare every physical output and all of its SSA aliases before
      // evaluating any RHS. The existing expression/backedge path consequently
      // observes one common pre-event state, independent of assignment order.
      for (Operation *port : event.storagePorts) {
        auto edge = backedges.get(portTypes.lookup(port), port->getLoc());
        auto state = circt::seq::CompRegOp::create(builder, port->getLoc(), Value(edge), clock,
                                                   circt::StringAttrOrRef(eventNames.lookup(port)));
        state->setAttr("inner_sym", circt::hw::InnerSymAttr::get(eventNames.lookup(port)));
        correlate(state, port);
        state->setAttr("nodal.event_process", op->getAttr("sym_name"));
        state->setAttr("nodal.event_contract", op->getAttr("contract"));
        state->setAttr("nodal.event_obligations", op->getAttr("obligations"));
        state->setAttr("nodal.event_source", op->getAttr("metadata"));
        Type original = port->getAttrOfType<TypeAttr>("type").getValue();
        if (aggregatePayload(original))
          state->setAttr("nodal.packed_abi", packedLayout(original));
        for (Value alias : portValues[port])
          values[alias] = state.getResult();
        eventEdges.emplace_back(eventUpdates.lookup(port), edge);
      }
      // Retain environment assumptions in emitted HDL without making authored
      // source identity executable syntax or claiming a physical timing proof.
      std::string detail = "// NODAL root event " + sourcePath(op).str() + ": " + event.edge.str() +
                           "; known_levels_definite_edges; external_sampling=user_setup_hold; "
                           "startup=unspecified; updates=simultaneous_pre_event; "
                           "environment assumptions, not electrical certification";
      for (char &c : detail)
        if (c == '\n' || c == '\r' || c == '{' || c == '}')
          c = ' ';
      auto comment = circt::sv::VerbatimOp::create(
          builder, op->getLoc(), builder.getStringAttr(detail + "; actual trigger {{0}}"),
          ValueRange{trigger}, builder.getArrayAttr({}));
      correlate(comment, op);
      comment->setAttr("nodal.event_process", op->getAttrDictionary());
    }
    for (Operation *op : resets) {
      if (isa<ResetBoundaryOp>(op)) {
        Value raw = values.lookup(op->getOperand(0));
        if (!raw)
          return reject(op, "physical reset boundary did not map");
        values[op->getResult(0)] = raw;
        if (text(op, "release") == "external") {
          auto binding = domains.resolveClockBinding(op->getAttr("release_domain"));
          auto domain = domains.resolveDomain(op->getAttr("release_domain"));
          Value clock = circt::seq::FromClockOp::create(builder, op->getLoc(),
                                                        values.lookup((*binding)->getOperand(0)));
          std::string comment =
              "// NODAL external reset release assumption: " + text(*domain, "sym_name").str() +
              " " + text(*domain, "edge").str() + " physical clock {{0}}; " + sourcePath(op).str();
          circt::sv::VerbatimOp::create(builder, op->getLoc(), builder.getStringAttr(comment),
                                        ValueRange{clock}, builder.getArrayAttr({}));
        }
      } else if (isa<ResetCombineOp>(op)) {
        SmallVector<Value> asserted;
        for (Value input : op->getOperands()) {
          Value raw = values.lookup(input);
          if (!raw)
            return reject(op, "reset composition input did not map");
          asserted.push_back(resetActive(raw, input.getDefiningOp()));
        }
        Value joined = asserted.size() == 1 ? asserted.front()
                                            : make(op, "comb.or", builder.getI1Type(), asserted);
        values[op->getResult(0)] = resetActive(joined, op);
      } else {
        Value raw = values.lookup(op->getOperand(0));
        Value clock = values.lookup(op->getOperand(1));
        if (!raw || !clock)
          return reject(op, "reset synchronizer input/clock did not map");
        auto domain = domains.resolveDomain(op->getAttr("destination_domain"));
        if (text(*domain, "edge") == "falling")
          clock = circt::seq::ClockInverterOp::create(builder, op->getLoc(), clock);
        Value asserted = resetActive(raw, op->getOperand(0).getDefiningOp());
        Value one = constant(op, 1, 1), previous = constant(op, 1, 0);
        for (StringAttr name : resetNames.find(op)->second) {
          auto state =
              circt::seq::FirRegOp::create(builder, op->getLoc(), previous, clock, name, asserted,
                                           one, circt::hw::InnerSymAttr::get(name), true);
          correlate(state, op);
          previous = state.getResult();
        }
        values[op->getResult(0)] = resetActive(previous, op);
      }
    }
    SmallVector<std::pair<Operation *, circt::Backedge>> stateEdges, resetValueEdges;
    for (Operation *reg : registers) {
      Operation *update = updates.lookup(reg->getResult(0));
      Value clock = values.lookup(update->getOperand(2));
      if (!clock) {
        backedges.abandon();
        return reject(update, "physical clock was not mapped to a module input");
      }
      auto edge = backedges.get(targetValueType(reg->getResult(0).getType()), reg->getLoc());
      Value reset, initial;
      if (updateSemantics(update, "reset_policy") != "none") {
        reset = updateReset(update);
        auto initialEdge =
            backedges.get(targetValueType(reg->getResult(0).getType()), reg->getLoc());
        initial = initialEdge;
        resetValueEdges.emplace_back(update, initialEdge);
      }
      Value state = emitClockedStorage(reg, update, edge, clock, reset, initial);
      if (!state) {
        backedges.abandon();
        return failure();
      }
      values[reg->getResult(0)] = state;
      stateEdges.emplace_back(update, edge);
    }
    SmallVector<std::pair<Value, circt::Backedge>> crossingEdges;
    for (const auto &crossing : crossings) {
      Operation *op = crossing.operation;
      auto destination = crossingControls(op, op->getAttr("destination_domain"),
                                          crossing.kind != CrossingKind::Gray);
      if (failed(destination)) {
        backedges.abandon();
        return reject(op, "crossing destination physical clock/reset did not map");
      }
      auto input = backedges.get(targetValueType(op->getOperand(0).getType()), op->getLoc());
      crossingEdges.emplace_back(op->getOperand(0), input);
      Value previous = input;
      if (crossing.kind == CrossingKind::Level || crossing.kind == CrossingKind::Gray) {
        for (unsigned stage = 0; stage < crossing.stages; ++stage)
          previous = crossingState(crossing, stage, previous, *destination, "forward",
                                   crossing.kind != CrossingKind::Gray);
        values[op->getResult(0)] = previous;
        if (crossing.kind == CrossingKind::Level)
          continue;
      }
      auto sourceControl = crossingControls(op, op->getAttr("source_domain"));
      if (failed(sourceControl)) {
        backedges.abandon();
        return reject(op, "crossing source physical clock/reset did not map");
      }
      if (crossing.kind == CrossingKind::Fifo) {
        auto sourceValid = backedges.get(builder.getI1Type(), op->getLoc());
        auto destinationReady = backedges.get(builder.getI1Type(), op->getLoc());
        crossingEdges.emplace_back(op->getOperand(1), sourceValid);
        crossingEdges.emplace_back(op->getOperand(2), destinationReady);
        lowerFifo(crossing, input, sourceValid, destinationReady, *sourceControl, *destination,
                  backedges);
      } else if (crossing.kind == CrossingKind::Gray) {
        // The payload history has no reset value. In particular, an enum with
        // no zero encoding must not acquire a fabricated source reset state.
        // Only the seen marker resets, and the first real sample seeds history.
        Value history = crossingState(crossing, crossing.stages, input, *sourceControl,
                                      "previous_payload", false);
        Value seen = crossingState(crossing, crossing.stages + 1, constant(op, 1, 1),
                                   *sourceControl, "seen_sample");
        unsigned bits = cast<IntegerType>(history.getType()).getWidth();
        Value delta = make(op, "comb.xor", history.getType(), {Value(input), history});
        Value minusOne = make(op, "comb.sub", history.getType(), {delta, constant(op, bits, 1)});
        Value changed = make(op, "comb.and", history.getType(), {delta, minusOne});
        Value legal = compare(op, "digital_eq", changed, constant(op, bits, 0), false);
        Value enable = sourceControl->reset ? make(op, "comb.and", builder.getI1Type(),
                                                   {seen, invert(op, sourceControl->reset)})
                                            : seen;
        crossingAssertion(crossing, *sourceControl, enable, legal,
                          "NODAL Gray transition must change at most one bit per source sample");
        crossingComment(crossing,
                        "Gray obligations: transition=one_bit_per_source_sample (checked); "
                        "skew=less_than_source_period; stability=stable_between_source_samples. "
                        "Payload stages have no invented reset value; output is unspecified during "
                        "startup and N-sample reset recovery. Physical skew and between-sample "
                        "stability are environment obligations, "
                        "not proved by the digital monitor.");
      } else if (crossing.kind == CrossingKind::Handshake) {
        // The two payload registers have no reset encoding. Only actual
        // protocol control state establishes the common reset epoch.
        auto sourceNext = backedges.get(Value(input).getType(), op->getLoc());
        auto destinationNext = backedges.get(Value(input).getType(), op->getLoc());
        auto requestNext = backedges.get(builder.getI1Type(), op->getLoc());
        auto acknowledgementNext = backedges.get(builder.getI1Type(), op->getLoc());
        Value held =
            crossingState(crossing, 0, sourceNext, *sourceControl, "source_payload", false);
        Value delivered =
            crossingState(crossing, 1, destinationNext, *destination, "destination_payload", false);
        Value request = crossingState(crossing, 2, requestNext, *sourceControl, "source_request");
        Value request0 = crossingState(crossing, 3, request, *destination, "destination_request_0");
        Value request1 =
            crossingState(crossing, 4, request0, *destination, "destination_request_1");
        Value acknowledgement = crossingState(crossing, 5, acknowledgementNext, *destination,
                                              "destination_acknowledgement");
        Value ack0 =
            crossingState(crossing, 6, acknowledgement, *sourceControl, "source_acknowledgement_0");
        Value ack1 = crossingState(crossing, 7, ack0, *sourceControl, "source_acknowledgement_1");
        Value idle = compare(op, "digital_eq", request, ack1, false);
        Value pending = compare(op, "digital_ne", request1, acknowledgement, false);
        sourceNext.setValue(mux(op, idle, input, held));
        requestNext.setValue(mux(op, idle, invert(op, request), request));
        destinationNext.setValue(mux(op, pending, held, delivered));
        // Ack changes on the same edge that captures the old held payload,
        // never merely when request arrives in either synchronizer stage.
        acknowledgementNext.setValue(mux(op, pending, request1, acknowledgement));
        values[op->getResult(0)] = delivered;
        Value sourceKnown =
            make(op, "comb.concat", builder.getIntegerType(3), {request, ack0, ack1});
        Value destinationKnown = make(op, "comb.concat", builder.getIntegerType(3),
                                      {request0, request1, acknowledgement});
        crossingAssertion(
            crossing, *sourceControl, invert(op, sourceControl->reset), sourceKnown,
            "NODAL handshake source controls require an established common reset epoch",
            "(^({{2}})) !== 1'bx");
        crossingAssertion(
            crossing, *destination, invert(op, destination->reset), destinationKnown,
            "NODAL handshake destination controls require an established common reset epoch",
            "(^({{2}})) !== 1'bx");
        crossingComment(
            crossing,
            "Handshake obligations: delivery=latest_snapshot_coalescing; "
            "bundled_data=settled_before_request_capture; reset_epoch=common_assertion. "
            "Source payload is held through returned capture acknowledgement; public "
            "payload changes while busy may coalesce. Payload state has no invented reset "
            "value and output is unspecified before first delivery/during recovery. "
            "Physical bundled-data settling is an environment timing obligation, not "
            "proved by the digital monitor. Hold common assertion long enough for both "
            "domains to observe reset; no one-sided restart. Known-state startup checks "
            "do not universally detect repeated one-sided synchronous resets.");
      } else {
        // Acknowledgement samples destination HISTORY, not the second forward
        // stage: readiness therefore means the destination has delivered the
        // event, rather than merely bringing a pending event into its domain.
        auto toggleNext = backedges.get(builder.getI1Type(), op->getLoc());
        Value toggle = crossingState(crossing, 0, toggleNext, *sourceControl, "source_toggle");
        Value priorPulse = crossingState(crossing, 1, input, *sourceControl, "previous_pulse");
        Value forward = crossingState(crossing, 2, toggle, *destination, "forward");
        forward = crossingState(crossing, 3, forward, *destination, "forward");
        Value history = crossingState(crossing, 4, forward, *destination, "destination_history");
        Value ack = crossingState(crossing, 5, history, *sourceControl, "return_acknowledgement");
        ack = crossingState(crossing, 6, ack, *sourceControl, "return_acknowledgement");
        toggleNext.setValue(make(op, "comb.xor", builder.getI1Type(), {toggle, Value(input)}));
        values[op->getResult(0)] = make(op, "comb.xor", builder.getI1Type(), {forward, history});
        Value ready = compare(op, "digital_eq", toggle, ack, false);
        Value legalPulse =
            make(op, "comb.and", builder.getI1Type(), {ready, invert(op, priorPulse)});
        Value legal = make(op, "comb.or", builder.getI1Type(), {invert(op, input), legalPulse});
        crossingAssertion(
            crossing, *sourceControl, invert(op, sourceControl->reset), legal,
            "NODAL pulse requires returned acknowledgement and single-source-cycle width");
        crossingComment(crossing,
                        "Pulse obligations: spacing=returned_acknowledgement; "
                        "width=single_source_cycle; reset_epoch=common_assertion. Hold common "
                        "assertion long enough for both domains to observe reset; no one-sided "
                        "restart. Delivery is not promised before the common reset epoch or "
                        "after a spacing/width violation.");
      }
    }
    for (Operation *op : clocks) {
      Value result = lowerClock(op, backedges);
      if (!result) {
        backedges.abandon();
        return failure();
      }
      values[op->getResult(0)] = result;
    }
    for (Operation *op : relationships) {
      SmallVector<Value, 2> actualClocks;
      for (Value operand : op->getOperands()) {
        Value mapped = values.lookup(operand);
        if (!mapped) {
          backedges.abandon();
          return reject(op, "relationship actual clock did not map");
        }
        actualClocks.push_back(circt::seq::FromClockOp::create(builder, op->getLoc(), mapped));
      }
      std::string detail;
      llvm::raw_string_ostream stream(detail);
      stream << "// NODAL clock relationship " << sourcePath(op) << ": kind=" << text(op, "kind")
             << "; source=" << op->getAttr("source")
             << "; destination=" << op->getAttr("destination") << "; proof=" << text(op, "proof")
             << "; obligations=" << op->getAttr("obligations");
      for (StringRef field : {"multiply", "divide", "phase", "phase_known"})
        if (Attribute value = op->getAttr(field))
          stream << "; " << field << "=" << value;
      stream << "; retained environment assumptions, not runtime physical certification";
      // Never let an authored identity inject a new line or a Verbatim operand.
      std::string prefix = detail;
      for (unsigned i = 0; i != prefix.size(); ++i) {
        char &c = prefix[i];
        if (c == '\n' || c == '\r')
          c = ' ';
        else if (c == '{' || c == '}')
          c = c == '{' ? '(' : ')';
      }
      auto comment = circt::sv::VerbatimOp::create(
          builder, op->getLoc(), builder.getStringAttr(prefix + "; actual clocks {{0}} -> {{1}}"),
          actualClocks, builder.getArrayAttr({}));
      correlate(comment, op);
      comment->setAttr("nodal.clock_relationship", op->getAttrDictionary());
    }
    for (Operation &op : source.getBody().front()) {
      if (!op.getNumResults() || staticOperations.contains(&op) ||
          controlProjections.contains(&op) || controlSelectors.contains(&op) ||
          named(&op, "nodal.clock_gate") || named(&op, "nodal.clock_mux") ||
          isa<GeneratedMemberOp, DigitalStorageOp, PortValueOp, InstancePortOp, MemberAccessOp,
              RequirementClockOp, RequirementResetOp, CrossingOp, AsyncFifoOp, ResetBoundaryOp,
              ResetSyncOp, ResetCombineOp>(op))
        continue;
      SmallVector<Value> inputs;
      for (Value old : op.getOperands()) {
        Value mapped = values.lookup(old);
        if (!mapped) {
          backedges.abandon();
          return reject(&op, "actual scalar operand has no target value");
        }
        inputs.push_back(mapped);
      }
      Value result = lowerValue(&op, inputs);
      if (!result) {
        backedges.abandon();
        return failure();
      }
      // An identity conversion may return a temporary graph reference. Keep an
      // SSA use so Backedge RAUW also updates it; a raw Value map is not a use.
      if (isa_and_nonnull<UnrealizedConversionCastOp>(result.getDefiningOp()))
        result = circt::hw::WireOp::create(builder, op.getLoc(), result).getResult();
      if (auto found = materializations.find(sourcePath(&op)); found != materializations.end()) {
        auto record = found->second;
        auto intent = record.getAs<DictionaryAttr>("intent");
        // The shared analyzer decides whether materialization is required. Only
        // an authored exact name is assigned here; source hints remain source
        // identity, rather than becoming a competing target naming policy.
        StringRef name = text(intent, "kind") == "named" ? text(intent, "value") : StringRef();
        auto wire = circt::hw::WireOp::create(builder, op.getLoc(), result, name);
        correlate(wire, &op);
        result = wire.getResult();
      }
      if (!llvm::is_contained(inputs, result) && result.getDefiningOp())
        correlate(result.getDefiningOp(), &op);
      values[op.getResult(0)] = result;
    }
    llvm::DenseMap<Value, circt::Backedge> connectionByValue;
    for (auto &[original, edge] : connectionEdges)
      connectionByValue.try_emplace(original, edge);
    llvm::DenseSet<Value> resolved;
    for (auto &[original, unused] : connectionEdges) {
      SmallVector<Value> pending{original};
      while (!pending.empty()) {
        Value current = pending.back();
        if (resolved.contains(current)) {
          pending.pop_back();
          continue;
        }
        Value rhs = aliases.lookup(current);
        if (aliases.count(rhs) && !resolved.contains(rhs)) {
          pending.push_back(rhs);
          continue;
        }
        Value mapped = values.lookup(rhs);
        if (!mapped) {
          backedges.abandon();
          return reject(current.getDefiningOp(), "canonical scalar driver did not lower");
        }
        if (auto *op = current.getDefiningOp(); isa<DigitalStorageOp>(op)) {
          auto wire = circt::hw::WireOp::create(builder, op->getLoc(), mapped, text(op, "name"));
          correlate(wire, op);
          mapped = wire.getResult();
        }
        connectionByValue[current].setValue(mapped);
        values[current] = mapped;
        resolved.insert(current);
        pending.pop_back();
      }
    }
    for (auto &[original, edge] : crossingEdges) {
      Value input = values.lookup(original);
      if (!input) {
        backedges.abandon();
        return reject(original.getDefiningOp(), "crossing actual sampled input did not lower");
      }
      edge.setValue(input);
    }
    for (auto &[original, edge] : eventEdges) {
      Value next = values.lookup(original);
      if (!next) {
        backedges.abandon();
        return reject(original.getDefiningOp(), "event actual update RHS did not lower");
      }
      edge.setValue(next);
    }
    for (auto &[update, edge] : resetValueEdges)
      edge.setValue(updateInitial(update));
    for (auto &[update, edge] : stateEdges) {
      Value next = values.lookup(update->getOperand(1));
      edge.setValue(next);
    }
    for (auto &[original, edge] : producerEdges) {
      Value result = values.lookup(original);
      if (!result || result == Value(edge)) {
        backedges.abandon();
        return reject(original.getDefiningOp(), "actual producer did not resolve its target value");
      }
      edge.setValue(result);
    }
    for (Operation &op : source.getBody().front()) {
      if (!named(&op, "nodal.sequential_assert"))
        continue;
      auto clock =
          circt::seq::FromClockOp::create(builder, op.getLoc(), values.lookup(op.getOperand(1)));
      auto assertion = circt::verif::ClockedAssertOp::create(
          builder, op.getLoc(), values.lookup(op.getOperand(0)),
          updateSemantics(&op, "edge") == "falling" ? circt::verif::ClockEdge::Neg
                                                    : circt::verif::ClockEdge::Pos,
          clock, Value(), op.getAttrOfType<StringAttr>("root_id"));
      correlate(assertion, &op);
    }
    SmallVector<Value> outputs;
    for (const auto &port : logicalPorts)
      if (!port.input) {
        auto leaves = splitBoundary(port.key.first, values.lookup(port.references.front()));
        llvm::append_range(outputs, leaves);
      }
    body->getTerminator()->setOperands(outputs);
    if (failed(backedges.clearOrEmitError()))
      return failure();
    return success();
  }

private:
  struct GeneratedFamilyPlan;
  struct GeneratedOutput {
    Value value;
    SmallVector<Value> destinations;
    Type laneType, busType;
    uint64_t maximumLaneBits;
    GeneratedFamilyPlan *child = nullptr;
    unsigned childOutput = 0;
  };
  struct GeneratedFamilyPlan {
    Operation *operation;
    GeneratedFamilyPlan *parent = nullptr;
    SmallVector<GeneratedFamilyPlan *> children;
    SmallVector<Operation *> selections;
    GeneratedDomain domain;
    TypedAttr lower, upper, step;
    StringAttr name, indexName, genvarName;
    SmallVector<LogicalPortPlan> ports;
    SmallVector<Value> captures;
    SmallVector<GeneratedOutput> outputs;
    SmallVector<Operation *> operations, registers;
    llvm::DenseMap<Value, SmallVector<Operation *, 2>> updates;
    llvm::DenseSet<Operation *> sinks;
    circt::hw::HWModuleOp target;
  };
  SmallVector<std::unique_ptr<GeneratedFamilyPlan>> generatedFamilies;
  SmallVector<GeneratedFamilyPlan *> generatedRoots;
  llvm::DenseMap<Operation *, GeneratedFamilyPlan *> generatedFamilyPlans;
  llvm::DenseMap<Operation *, std::pair<GeneratedFamilyPlan *, unsigned>> generatedOutputPorts;
  llvm::DenseMap<Operation *, std::pair<GeneratedFamilyPlan *, unsigned>> generatedExports;
  llvm::MapVector<Operation *, GeneratedMember> generatedMembers;
  llvm::DenseMap<Attribute, SmallVector<TypedAttr>> generatedExclusions;
  Value capturedValue(Value value) const {
    for (unsigned depth = 0; depth < digitalTypeMaximumDepth; ++depth) {
      auto argument = dyn_cast<BlockArgument>(value);
      if (!argument || isa<GenerateOp>(argument.getOwner()->getParentOp()))
        return value;
      auto source = digitalRegionCaptureSource(argument);
      if (failed(source) || !*source)
        return {};
      value = *source;
    }
    return {};
  }
  TypedAttr familyInteger(GeneratedFamilyPlan &family, Value value, Operation *use) {
    value = capturedValue(value);
    if (value == family.domain.induction)
      return circt::hw::ParamDeclRefAttr::get(family.indexName, builder.getI32Type());
    if (!value)
      return {};
    auto expression = resolveStructuralIntegerExpression(value, use);
    return succeeded(expression) && expression->expression
               ? parameterExpression(*expression->expression)
               : TypedAttr();
  }
  LogicalResult planGeneratedFamilies() {
    llvm::StringSet<> names;
    for (Operation &op : *source->getParentOfType<mlir::ModuleOp>().getBody())
      if (auto name = op.getAttrOfType<StringAttr>("sym_name"))
        names.insert(name.getValue());
    auto unique = [&](std::string base) {
      std::string name = base;
      unsigned suffix = 0;
      while (!names.insert(name).second)
        name = base + "_" + std::to_string(++suffix);
      return builder.getStringAttr(name);
    };
    std::function<LogicalResult(Operation &, GeneratedFamilyPlan *, unsigned)> planFamily;
    planFamily = [&](Operation &op, GeneratedFamilyPlan *parent, unsigned depth) -> LogicalResult {
      if (depth >= digitalTypeMaximumDepth)
        return reject(&op, "generated helper ancestry exceeds finite target depth");
      auto domain = resolveGeneratedDomain(&op);
      if (op.getNumResults() != 1 || failed(domain) || domain->lower.bounds.lower != 0 ||
          domain->lower.bounds.upper != 0 || domain->step.bounds.lower != 1 ||
          domain->step.bounds.upper != 1 || domain->upperExclusive.bounds.lower <= 0)
        return reject(&op,
                      "generated target requires a checked nonempty zero-based unit-stride family");
      if (domain->upperExclusive.bounds.lower == domain->upperExclusive.bounds.upper &&
          !preserveConstantLoops)
        return reject(&op, "concrete generated storage requires preserve-constant-loops=true");
      if (generatedFamilyPlans.size() >= 4096 || cost + 16 > maximumGeneratedOperations)
        return reject(&op, "generated helper templates exceed finite target budget");
      cost += 16;
      auto plan = std::make_unique<GeneratedFamilyPlan>();
      auto &family = *plan;
      family.operation = &op;
      family.parent = parent;
      unsigned familyIndex = generatedFamilyPlans.size();
      generatedFamilyPlans.try_emplace(&op, &family);
      if (parent)
        parent->children.push_back(&family);
      else
        generatedRoots.push_back(&family);
      family.domain = *domain;
      family.lower = parameterExpression(domain->lower);
      family.upper = parameterExpression(domain->upperExclusive);
      family.step = parameterExpression(domain->step);
      if (!family.lower || !family.upper || !family.step)
        return reject(&op, "generated bounds require checked target Integer expressions");
      family.name = unique((variant ? variant->name : text(source, "sym_name").str()) +
                           "__family_" + std::to_string(familyIndex));
      llvm::StringSet<> parameterNames;
      for (Attribute attr : targetParameters)
        parameterNames.insert(cast<circt::hw::ParamDeclAttr>(attr).getName().getValue());
      std::string index = "__nodal_index";
      while (!parameterNames.insert(index).second)
        index += "_";
      family.indexName = builder.getStringAttr(index);
      family.genvarName = builder.getStringAttr("__nodal_lane_" + std::to_string(familyIndex));
      llvm::DenseSet<Value> captures;
      auto capture = [&](Value value) -> LogicalResult {
        value = capturedValue(value);
        if (!value)
          return failure();
        Operation *producer = value.getDefiningOp();
        if (value == domain->induction || (producer && op.isProperAncestor(producer)) ||
            value.getType().isInteger(64))
          return success();
        Type type = targetValueType(value.getType());
        if (!type || isa<ResolvedType>(value.getType()))
          return reject(&op, "generated capture has no ordinary logical target value");
        if (captures.insert(value).second)
          family.captures.push_back(value);
        return success();
      };
      auto output = [&](Value value, GeneratedFamilyPlan *child = nullptr,
                        unsigned childOutput = 0) -> FailureOr<unsigned> {
        value = capturedValue(value);
        Type type = child ? child->outputs[childOutput].busType
                          : (value ? targetValueType(value.getType()) : Type());
        uint64_t maximumBits = 0;
        if (child) {
          const auto &leaf = child->outputs[childOutput];
          maximumBits = leaf.maximumLaneBits * child->domain.maximumTripCount;
        } else if (auto scalar = dyn_cast_if_present<IntegerType>(type))
          maximumBits = scalar.getWidth();
        if (!maximumBits || maximumBits > maximumWidth / domain->maximumTripCount)
          return failure();
        for (auto [index, prior] : llvm::enumerate(family.outputs))
          if (prior.value == value && prior.child == child && prior.childOutput == childOutput)
            return index;
        TypedAttr bits =
            circt::hw::ParamExprAttr::get(circt::hw::PEO::Mul, family.upper, generatedWidth(type));
        Type bus = isa<IntegerAttr>(bits)
                       ? Type(builder.getIntegerType(cast<IntegerAttr>(bits).getInt()))
                       : Type(circt::hw::IntType::get(bits));
        family.outputs.push_back({value, {}, type, bus, maximumBits, child, childOutput});
        return family.outputs.size() - 1;
      };
      auto walk = op.walk<WalkOrder::PreOrder>([&](Operation *child) {
        if (child == &op)
          return WalkResult::advance();
        if (cost + 16 > maximumGeneratedOperations) {
          (void)reject(child, "generated body expansion exceeds finite target budget");
          return WalkResult::interrupt();
        }
        cost += 16;
        if (isa<GenerateOp>(child)) {
          if (child->getParentOp() != &op || failed(planFamily(*child, &family, depth + 1))) {
            (void)reject(child, "nested target family requires exact direct lexical ownership");
            return WalkResult::interrupt();
          }
          auto *nested = generatedFamilyPlans.lookup(child);
          // A child iteration drives its exact parent row. Route that same
          // canonical sink bottom-up, never a second driver or a lane clone.
          for (auto [i, leaf] : llvm::enumerate(nested->outputs))
            for (Value destination : leaf.destinations) {
              auto row = destination.getDefiningOp<ShapeIndexOp>();
              if (!row || row->getParentOp() != &op || row->getNumOperands() != 2 ||
                  capturedValue(row->getOperand(1)) != family.domain.induction ||
                  targetValueType(destination.getType()) != leaf.busType) {
                (void)reject(child, "nested generated output lost its exact parent row");
                return WalkResult::interrupt();
              }
              auto index = output(leaf.value, nested, i);
              if (failed(index)) {
                (void)reject(child, "nested generated output exceeds finite physical bit budget");
                return WalkResult::interrupt();
              }
              auto &out = family.outputs[*index];
              if (!llvm::is_contained(out.destinations, row->getOperand(0)))
                out.destinations.push_back(row->getOperand(0));
              family.sinks.insert(row);
            }
          for (Value value : nested->captures)
            if (failed(capture(value)))
              return WalkResult::interrupt();
          return WalkResult::skip();
        }
        if (isa<ConstLiteralOp, ConstExprOp, ConstParameterRefOp>(child) &&
            child->getNumResults() == 1 && child->getResult(0).getType().isSignlessInteger(64)) {
          if (failed(verifyStructuralIntegerOperation(child))) {
            (void)reject(child, "generated static value lacks its canonical Integer proof");
            return WalkResult::interrupt();
          }
          staticOperations.insert(child);
          return WalkResult::advance();
        }
        if (isa<GeneratedMemberOp>(child)) {
          auto member = inspectGeneratedMember(child);
          if (failed(member) || child->getParentOp() != &op) {
            (void)reject(child,
                         "generated target selection requires its exact lexical family scope");
            return WalkResult::interrupt();
          }
          generatedMembers.try_emplace(child, *member);
          return WalkResult::advance();
        }
        if (isa<StructuralIfOp>(child)) {
          if (child->getParentOp() != &op || child->getNumResults()) {
            (void)reject(
                child,
                "generated target currently requires direct result-free static alternatives");
            return WalkResult::interrupt();
          }
          auto comparison = capturedValue(child->getOperand(0)).getDefiningOp<StaticCompareOp>();
          if (!comparison ||
              (text(comparison, "comparison") != "eq" && text(comparison, "comparison") != "ne") ||
              !familyInteger(family, comparison->getOperand(0), comparison) ||
              !familyInteger(family, comparison->getOperand(1), comparison)) {
            (void)reject(child,
                         "generated static alternatives require exact supported Integer equality");
            return WalkResult::interrupt();
          }
          return WalkResult::advance();
        }
        if (child->getNumRegions() || child->getNumSuccessors()) {
          (void)reject(
              child,
              "nested generated families and executable regions require later target admission");
          return WalkResult::interrupt();
        }
        if (isa<StructuralYieldOp>(child))
          return WalkResult::advance();
        if (isa<GeneratedExportOp>(child)) {
          if (text(child, "kind") == "family") {
            auto *nested = generatedFamilyPlans.lookup(child->getOperand(0).getDefiningOp());
            if (!nested || nested->parent != &family) {
              (void)reject(child, "generated family export lost its exact child template");
              return WalkResult::interrupt();
            }
            for (auto [i, leaf] : llvm::enumerate(nested->outputs))
              if (failed(output(leaf.value, nested, i))) {
                (void)reject(child, "nested generated export exceeds finite physical bit budget");
                return WalkResult::interrupt();
              }
            return WalkResult::advance();
          }
          auto index = output(child->getOperand(0));
          if (failed(index)) {
            (void)reject(child, "generated target exports require fixed scalar storage leaves");
            return WalkResult::interrupt();
          }
          generatedExports.try_emplace(child, std::make_pair(&family, *index));
          return WalkResult::advance();
        }
        if (isa<DigitalAssignOp>(child)) {
          if (child->getParentOp() != &op) {
            (void)reject(child, "generated output writes require the direct family body; static "
                                "output adapters remain unsupported");
            return WalkResult::interrupt();
          }
          if (text(child, "kind") != "continuous") {
            (void)reject(child, "generated target requires canonical normalized updates");
            return WalkResult::interrupt();
          }
          auto projection = resolveStaticShapeProjection(child->getOperand(0), child);
          StaticShapeRegion image;
          if (failed(projection) ||
              resolveStaticShapeIterationImage(*projection, child, image) !=
                  ShapeRegionProofStatus::Proved ||
              !image.iteration || image.iteration->loop != &op ||
              verifyStaticShapeRegionPartition(projection->root, {image}, child) !=
                  ShapeRegionProofStatus::Proved) {
            (void)reject(child, "generated output requires one exact complete iteration partition");
            return WalkResult::interrupt();
          }
          auto slice = child->getOperand(0).getDefiningOp<ShapeIndexOp>();
          if (!slice || slice->getNumOperands() != 2 || slice->getParentOp() != &op ||
              capturedValue(slice->getOperand(1)) != family.domain.induction) {
            (void)reject(child, "generated output requires its exact direct induction slice");
            return WalkResult::interrupt();
          }
          // The shared image proves full root coverage. Physical composition
          // peels only this actual family's axis, retaining every parent row.
          SmallVector<GeneratedFamilyPlan *> ancestry;
          for (auto *ancestor = &family; ancestor; ancestor = ancestor->parent)
            ancestry.push_back(ancestor);
          std::reverse(ancestry.begin(), ancestry.end());
          if (image.iterationCount() != ancestry.size()) {
            (void)reject(child, "generated output image must retain every exact family axis");
            return WalkResult::interrupt();
          }
          if (ancestry.size() > maximumGeneratedOperations - cost) {
            (void)reject(child, "generated output route exceeds finite target budget");
            return WalkResult::interrupt();
          }
          cost += ancestry.size();
          for (unsigned i = 0; i != ancestry.size(); ++i) {
            const auto &axis = image.iterationAt(i);
            if (axis.loop != ancestry[i]->operation || !axis.varyingAxis ||
                *axis.varyingAxis != i || axis.stride != 1 || axis.empty) {
              (void)reject(child, "generated output requires ordered complete direct family axes");
              return WalkResult::interrupt();
            }
          }
          auto index = output(child->getOperand(1));
          Value destination = slice->getOperand(0);
          if (failed(index) ||
              targetValueType(destination.getType()) != family.outputs[*index].busType) {
            (void)reject(child, "generated output requires exact parent-row lane layout");
            return WalkResult::interrupt();
          }
          family.outputs[*index].destinations.push_back(destination);
          family.sinks.insert(slice);
          return WalkResult::advance();
        }
        if (isa<SequentialUpdateOp>(child)) {
          Value state = capturedValue(child->getOperand(0));
          if (!state || !state.getDefiningOp<DigitalStorageOp>() ||
              state.getDefiningOp()->getParentOp() != &op || !child->getAttr("edge") ||
              !child->getAttr("reset_policy")) {
            (void)reject(
                child,
                "generated target requires direct storage and exact material update controls");
            return WalkResult::interrupt();
          }
          family.updates[state].push_back(child);
        } else if (isa<DigitalStorageOp>(child)) {
          if (child->getParentOp() != &op || text(child, "kind") != "register" ||
              !isa_and_nonnull<IntegerType>(targetValueType(child->getResult(0).getType()))) {
            (void)reject(
                child, "generated target currently requires direct fixed-scalar register storage");
            return WalkResult::interrupt();
          }
          family.registers.push_back(child);
        } else if (!isa<ConstantOp, ConstLiteralOp, StaticCompareOp, ShapeIndexOp,
                        SequentialSelectOp>(child) &&
                   !(isa<DigitalExprOp>(child) && scalarOpcode(text(child, "operator_name")))) {
          (void)reject(child, "operation is outside the shared generated scalar value profile");
          return WalkResult::interrupt();
        }
        if (isa<ShapeIndexOp>(child)) {
          auto shape = dyn_cast<nodal::ShapedType>(child->getOperand(0).getType());
          auto index = resolveStaticShapeIterationIndex(child->getOperand(1), child);
          if (child->getNumOperands() != 2 || !shape ||
              !generatedWidth(targetValueType(child->getResult(0).getType())) || failed(index) ||
              index->loop != &op || capturedValue(child->getOperand(1)) != domain->induction) {
            (void)reject(child,
                         "generated scalar slice requires the exact direct family induction");
            return WalkResult::interrupt();
          }
        }
        for (Value operand : child->getOperands())
          if (failed(capture(operand)))
            return WalkResult::interrupt();
        family.operations.push_back(child);
        return WalkResult::advance();
      });
      if (walk.wasInterrupted())
        return failure();
      for (Operation *reg : family.registers) {
        auto &alternatives = family.updates[reg->getResult(0)];
        if (alternatives.empty() || alternatives.size() > 2)
          return reject(reg, "generated register lacks one canonical complete update choice");
        Operation *first = alternatives.front();
        if (alternatives.size() == 2 && !areOppositeStructuralArms(first, alternatives.back()))
          return reject(reg,
                        "generated register alternatives require exact opposite structural arms");
        for (Operation *update : alternatives) {
          for (StringRef key : {"domain", "edge", "reset_policy", "reset_polarity"})
            if (update->getAttr(key) != first->getAttr(key))
              return reject(update, "generated alternatives cannot change physical state controls");
          if (update->getNumOperands() != first->getNumOperands())
            return reject(update, "generated alternatives disagree on reset contract");
          for (unsigned i = 2; i < update->getNumOperands(); ++i)
            if (capturedValue(update->getOperand(i)) != capturedValue(first->getOperand(i)))
              return reject(
                  update,
                  "generated alternatives require identical actual controls and initializer");
        }
      }
      for (auto [i, out] : llvm::enumerate(family.outputs))
        for (Value destination : out.destinations) {
          auto portValue = destination.getDefiningOp<PortValueOp>();
          if (!portValue) {
            if (!parent)
              return reject(&op, "generated root output must reach its original module pin");
            continue; // The exact enclosing family consumes this row route.
          }
          Operation *port = SymbolTable::lookupSymbolIn(
              source, portValue->getAttrOfType<FlatSymbolRefAttr>("port"));
          if (parent || !port || text(port, "direction") != "output" ||
              targetValueType(destination.getType()) != out.busType ||
              !generatedOutputPorts.try_emplace(port, std::make_pair(&family, i)).second)
            return reject(&op,
                          "generated output requires one ordinary output pin with exact layout");
        }
      // A driven output bus is a sink, not a helper input. Reject any separate
      // read of that bus rather than invent an internally driven input pin.
      llvm::DenseSet<Value> outputRoots;
      for (const auto &out : family.outputs)
        outputRoots.insert(out.destinations.begin(), out.destinations.end());
      for (Operation *child : family.operations)
        if (!family.sinks.contains(child))
          for (Value operand : child->getOperands())
            if (outputRoots.contains(capturedValue(operand)))
              return reject(child, "generated output bus reads require a separate logical adapter");
      llvm::erase_if(family.captures, [&](Value value) { return outputRoots.contains(value); });
      auto port = [&](Value value, bool input, unsigned index, Type type) {
        LogicalPortPlan pin;
        pin.key = {value.getDefiningOp(), Attribute()};
        pin.originalType = value.getType();
        pin.targetType = type;
        pin.name = builder.getStringAttr((input ? "capture_" : "result_") + std::to_string(index));
        pin.input = input;
        pin.references.push_back(value);
        if (auto structure = dyn_cast<circt::hw::StructType>(type))
          for (const auto &field : structure.getElements())
            pin.pins.push_back(builder.getStringAttr(pin.name.getValue().str() + "__" +
                                                     field.name.getValue().str()));
        else
          pin.pins.push_back(pin.name);
        family.ports.push_back(std::move(pin));
      };
      for (auto [index, value] : llvm::enumerate(family.captures))
        port(value, true, index, targetValueType(value.getType()));
      for (auto [index, out] : llvm::enumerate(family.outputs))
        port(out.value, false, index, out.laneType);
      if (cost + 8 * family.ports.size() + targetParameters.size() > maximumGeneratedOperations)
        return reject(&op, "generated logical adapters exceed target budget");
      cost += 8 * family.ports.size() + targetParameters.size();
      if (cost + 12 * targetParameters.size() > maximumGeneratedOperations)
        return reject(&op, "generated parameter guards exceed target budget");
      cost += 12 * targetParameters.size();
      staticOperations.insert(&op);
      generatedFamilies.push_back(std::move(plan));
      return success();
    };
    for (Operation &op : source.getBody().front())
      if (isa<GenerateOp>(op) && failed(planFamily(op, nullptr, 0)))
        return failure();
    if (!generatedFamilies.empty())
      for (Operation &op : source.getBody().front())
        if (isa<ParameterConstraintOp>(op) && text(&op, "constraint_kind") == "exclude") {
          auto excluded = resolveOwnedIntegerExpression(source, op.getOperand(0),
                                                        ParameterIntegerMode::MathematicalSigned64);
          TypedAttr actual = succeeded(excluded) ? parameterExpression(*excluded) : TypedAttr();
          if (!actual || cost + 4 > maximumGeneratedOperations)
            return reject(
                &op, "generated parameter exclusion requires a checked target Integer expression");
          generatedExclusions[op.getAttr("parameter")].push_back(actual);
          cost += 4;
        }
    for (Operation &op : source.getBody().front())
      if (isa<GeneratedMemberOp>(op)) {
        auto member = inspectGeneratedMember(&op);
        if (failed(member))
          return failure();
        generatedMembers.try_emplace(&op, *member);
      }
    for (auto &[operation, member] : generatedMembers) {
      if (member.familyView) {
        staticOperations.insert(operation);
        continue;
      }
      if (member.selections.empty() || !generatedExports.count(member.exportOperation))
        return reject(operation, "generated target selection requires an original scalar export");
      auto *plan = generatedFamilyPlans.lookup(member.selections.front().family);
      Operation *scope = plan && plan->parent ? plan->parent->operation : source.getOperation();
      if (!plan || operation->getParentOp() != scope)
        return reject(operation, "generated target selection prefix has a foreign lexical owner");
      plan->selections.push_back(operation);
      unsigned outputIndex = 0;
      for (; outputIndex < plan->outputs.size(); ++outputIndex)
        if (plan->outputs[outputIndex].value == member.storage)
          break;
      for (auto [i, selection] : llvm::enumerate(member.selections)) {
        if (plan->operation != selection.family || outputIndex >= plan->outputs.size() ||
            !parameterExpression(selection.coordinate))
          return reject(operation, "generated target selection must retain every exact prefix");
        const auto &out = plan->outputs[outputIndex];
        if (i + 1 == member.selections.size()) {
          if (out.child || out.value != member.storage)
            return reject(operation, "generated target selection did not reach original storage");
        } else {
          if (!out.child)
            return reject(operation, "generated target selection is missing an exported family");
          plan = out.child;
          outputIndex = out.childOutput;
        }
      }
      cost += 8 * member.selections.size();
      if (cost > maximumGeneratedOperations)
        return reject(operation, "generated prefix adapters exceed finite target budget");
    }
    return success();
  }

  // Shared clocked storage emitter. Both ordinary modules and represented
  // family helpers supply the canonical next value and exact physical controls.
  Value emitClockedStorage(Operation *reg, Operation *update, Value next, Value clock, Value reset,
                           Value initial) {
    StringRef policy = updateSemantics(update, "reset_policy");
    if (!next || !clock || (policy != "none" && (!reset || !initial))) {
      (void)reject(update, "clocked state requires mapped next value and exact reset controls");
      return {};
    }
    if (updateSemantics(update, "edge") == "falling")
      clock = circt::seq::ClockInverterOp::create(builder, update->getLoc(), clock);
    Operation *state;
    if (policy == "async" || policy == "async_assert_sync_release")
      state = circt::seq::FirRegOp::create(
          builder, reg->getLoc(), next, clock, reg->getAttrOfType<StringAttr>("name"),
          resetActive(reset, update), initial, circt::hw::InnerSymAttr(), true);
    else {
      if (policy == "sync")
        next = mux(update, resetActive(reset, update), initial, next);
      state = circt::seq::CompRegOp::create(builder, reg->getLoc(), next, clock);
      state->setAttr("name", reg->getAttr("name"));
    }
    correlate(state, reg);
    return state->getResult(0);
  }
  Operation *regionOperation(Operation *at, StringRef name, ArrayRef<NamedAttribute> attrs,
                             unsigned regions = 1, ValueRange operands = {}) {
    OperationState state(at->getLoc(), name);
    state.addAttributes(attrs);
    state.addOperands(operands);
    for (unsigned i = 0; i != regions; ++i)
      state.addRegion()->push_back(new Block());
    Operation *result = builder.create(state);
    correlate(result, at);
    return result;
  }
  void assignGenerated(Operation *at, Value destination, Value value) {
    OperationState state(at->getLoc(), "sv.assign");
    state.addOperands({destination, value});
    correlate(builder.create(state), at);
  }
  TypedAttr generatedWidth(Type type) {
    if (auto scalar = dyn_cast_if_present<IntegerType>(type))
      return builder.getI32IntegerAttr(scalar.getWidth());
    if (auto symbolic = dyn_cast_if_present<circt::hw::IntType>(type))
      return symbolic.getWidth();
    return {};
  }
  Value generatedSlice(Operation *at, Value input, Value index, Type result,
                       bool bitOffset = false) {
    Type element = result;
    if (auto inout = dyn_cast<circt::hw::InOutType>(element))
      element = inout.getElementType();
    TypedAttr width = generatedWidth(element);
    if (!width)
      return {};
    // A parameter value may be outlined as a runtime wire. A localparam keeps
    // the slice width an elaboration constant through the actual SV consumer.
    Value stride;
    {
      // These operand-free declarations refer only to this helper's admitted
      // parameters. Place them before every use, including child loop captures:
      // the exporter otherwise turns a forward localparam use into a wire,
      // which is not a legal constant part-select width.
      OpBuilder::InsertionGuard insertion(builder);
      Operation *owner = builder.getInsertionBlock()->getParentOp();
      auto module = dyn_cast<circt::hw::HWModuleOp>(owner);
      if (!module)
        module = owner->getParentOfType<circt::hw::HWModuleOp>();
      assert(module && "generated slice requires an actual target module");
      builder.setInsertionPointToStart(module.getBodyBlock());
      stride = make(at, "sv.localparam", builder.getI32Type(), {},
                    {builder.getNamedAttr("value", width),
                     builder.getNamedAttr("name", builder.getStringAttr(""))});
    }
    return make(
        at, "sv.verbatim.expr", result, {input, index, stride},
        {builder.getNamedAttr("format_string",
                              builder.getStringAttr(bitOffset ? "{{0}}[{{1}} +: {{2}}]"
                                                              : "{{0}}[{{1}} * {{2}} +: {{2}}]")),
         builder.getNamedAttr("symbols", builder.getArrayAttr({}))});
  }
  LogicalResult lowerGeneratedHelper(GeneratedFamilyPlan &family) {
    OpBuilder::InsertionGuard insertion(builder);
    auto parentValues = std::move(values);
    values.clear();
    auto restoreValues = llvm::scope_exit([&] { values = std::move(parentValues); });
    SmallVector<circt::hw::PortInfo> ports;
    unsigned inputIndex = 0, outputIndex = 0;
    appendPhysicalPorts(family.ports, ports, inputIndex, outputIndex);
    SmallVector<Attribute> parameters(targetParameters.begin(), targetParameters.end());
    parameters.push_back(
        circt::hw::ParamDeclAttr::get(family.indexName, builder.getI32IntegerAttr(0)));
    builder.setInsertionPoint(source);
    family.target = circt::hw::HWModuleOp::create(builder, family.operation->getLoc(), family.name,
                                                  circt::hw::ModulePortInfo(ports),
                                                  builder.getArrayAttr(parameters));
    correlate(family.target, family.operation);
    family.target->setAttr("nodal.generated_template", family.operation->getAttr("region_id"));
    family.target->setAttr("nodal.generated_definition", source->getAttr("sym_name"));
    Block *body = family.target.getBodyBlock();
    builder.setInsertionPoint(body->getTerminator());
    inputIndex = 0;
    for (const auto &port : family.ports)
      if (port.input) {
        SmallVector<Value> leaves;
        for (unsigned i = 0; i != port.pins.size(); ++i)
          leaves.push_back(body->getArgument(inputIndex++));
        values[port.references.front()] = joinBoundary(port.key.first, port.targetType, leaves);
      }
    values[family.domain.induction] = circt::hw::ParamValueOp::create(
        builder, family.operation->getLoc(), builder.getI32Type(),
        circt::hw::ParamDeclRefAttr::get(family.indexName, builder.getI32Type()));
    circt::BackedgeBuilder backedges(builder, family.operation->getLoc());
    SmallVector<std::pair<Value, circt::Backedge>> edges;
    for (Operation *op : family.operations) {
      if (isa<StaticCompareOp>(op) || family.sinks.contains(op))
        continue;
      for (Value value : op->getResults()) {
        auto edge = backedges.get(targetValueType(value.getType()), op->getLoc());
        values[value] = edge;
        edges.emplace_back(value, edge);
      }
    }
    auto mapped = [&](Value value) { return values.lookup(capturedValue(value)); };
    llvm::DenseMap<Operation *, Value> nextWires;
    for (Operation *reg : family.registers) {
      Type type = targetValueType(reg->getResult(0).getType());
      Value wire = make(reg, "sv.wire", circt::hw::InOutType::get(type), {},
                        {builder.getNamedAttr("name", builder.getStringAttr(""))});
      nextWires.try_emplace(reg, wire);
      Value next = make(reg, "sv.read_inout", type, {wire});
      Operation *update = family.updates.find(reg->getResult(0))->second.front();
      Value reset, initial;
      if (updateSemantics(update, "reset_policy") != "none") {
        reset = mapped(update->getOperand(3));
        initial = mapped(update->getOperand(4));
      }
      Value state =
          emitClockedStorage(reg, update, next, mapped(update->getOperand(2)), reset, initial);
      if (!state) {
        backedges.abandon();
        return failure();
      }
      values[reg->getResult(0)] = state;
    }
    if (failed(lowerGeneratedScope(&family))) {
      backedges.abandon();
      return failure();
    }
    for (Operation *op : family.operations) {
      if (!op->getNumResults() || isa<DigitalStorageOp, StaticCompareOp>(op) ||
          family.sinks.contains(op))
        continue;
      Value result;
      if (isa<ShapeIndexOp>(op))
        result =
            generatedSlice(op, mapped(op->getOperand(0)), values.lookup(family.domain.induction),
                           targetValueType(op->getResult(0).getType()));
      else {
        SmallVector<Value> inputs;
        for (Value operand : op->getOperands())
          inputs.push_back(mapped(operand));
        if (llvm::any_of(inputs, [](Value value) { return !value; })) {
          backedges.abandon();
          return reject(op, "generated scalar operand lacks its exact logical capture");
        }
        result = lowerValue(op, inputs);
      }
      if (!result) {
        backedges.abandon();
        return failure();
      }
      if (isa_and_nonnull<UnrealizedConversionCastOp>(result.getDefiningOp()))
        result = circt::hw::WireOp::create(builder, op->getLoc(), result).getResult();
      values[op->getResult(0)] = result;
    }
    // Each alternative already contains priority, enable and old-state hold in
    // its normalized RHS. Only exact elaboration choice remains here.
    for (Operation *reg : family.registers) {
      const auto &updates = family.updates.find(reg->getResult(0))->second;
      if (updates.size() == 1) {
        if (updates.front()->getParentOp() != family.operation) {
          backedges.abandon();
          return reject(reg, "generated state requires a complete canonical structural choice");
        }
        assignGenerated(updates.front(), nextWires.lookup(reg),
                        mapped(updates.front()->getOperand(1)));
        continue;
      }
      Operation *choice = updates.front()->getParentOp();
      if (!isa<StructuralIfOp>(choice) || choice != updates.back()->getParentOp()) {
        backedges.abandon();
        return reject(reg, "generated state alternatives must share one actual static owner");
      }
      Operation *comparison = capturedValue(choice->getOperand(0)).getDefiningOp();
      TypedAttr cond = familyInteger(family, comparison->getOperand(0), comparison);
      TypedAttr pattern = familyInteger(family, comparison->getOperand(1), comparison);
      auto *wrapper = regionOperation(
          choice, "sv.generate",
          {builder.getNamedAttr("sym_name",
                                builder.getStringAttr("choice_" + text(reg, "name").str()))});
      builder.setInsertionPointToEnd(&wrapper->getRegion(0).front());
      auto *branch = regionOperation(
          choice, "sv.generate.case",
          {builder.getNamedAttr("cond", cond),
           builder.getNamedAttr("casePatterns",
                                builder.getArrayAttr({pattern, builder.getUnitAttr()})),
           builder.getNamedAttr("caseNames", builder.getStrArrayAttr({"equal", "other"}))},
          2);
      for (Operation *update : updates) {
        bool thenArm = update->getParentRegion() == &choice->getRegion(0);
        bool equalArm = thenArm == (text(comparison, "comparison") == "eq");
        builder.setInsertionPointToEnd(&branch->getRegion(equalArm ? 0 : 1).front());
        assignGenerated(update, nextWires.lookup(reg), mapped(update->getOperand(1)));
      }
      builder.setInsertionPoint(body->getTerminator());
    }
    SmallVector<Value> outputs;
    for (const auto &port : family.ports)
      if (!port.input)
        llvm::append_range(outputs, splitBoundary(port.key.first, mapped(port.references.front())));
    body->getTerminator()->setOperands(outputs);
    for (auto &[original, edge] : edges) {
      Value result = values.lookup(original);
      if (!result || result == Value(edge)) {
        backedges.abandon();
        return reject(original.getDefiningOp(), "generated template producer did not map");
      }
      edge.setValue(result);
    }
    return backedges.clearOrEmitError();
  }
  LogicalResult lowerGeneratedFamilies() {
    if (!generatedFamilies.empty())
      for (Operation &parameter : source.getBody().front()) {
        if (!symbolicParameters.contains(&parameter))
          continue;
        auto envelope = resolveOwnedIntegerParameter(source, &parameter,
                                                     ParameterIntegerMode::MathematicalSigned64);
        if (failed(envelope))
          return failure();
        Value value = circt::hw::ParamValueOp::create(
            builder, parameter.getLoc(), builder.getI32Type(),
            circt::hw::ParamDeclRefAttr::get(parameter.getAttrOfType<StringAttr>("sym_name"),
                                             builder.getI32Type()));
        Value lo = compare(&parameter, "digital_ge", value,
                           constant(&parameter, 32, uint64_t(envelope->bounds.lower)), true);
        Value hi = compare(&parameter, "digital_le", value,
                           constant(&parameter, 32, uint64_t(envelope->bounds.upper)), true);
        SmallVector<Value> conditions{lo, hi};
        auto reference = FlatSymbolRefAttr::get(builder.getContext(), text(&parameter, "sym_name"));
        for (TypedAttr exclusion : generatedExclusions[reference]) {
          Value excluded = circt::hw::ParamValueOp::create(builder, parameter.getLoc(),
                                                           builder.getI32Type(), exclusion);
          conditions.push_back(compare(&parameter, "digital_ne", value, excluded, true));
        }
        Value invalid =
            invert(&parameter, make(&parameter, "comb.and", builder.getI1Type(), conditions));
        OpBuilder::InsertionGuard insertion(builder);
        Operation *initial = regionOperation(&parameter, "sv.initial", {});
        builder.setInsertionPointToEnd(&initial->getRegion(0).front());
        circt::sv::IfOp::create(builder, parameter.getLoc(), invalid, [&] {
          circt::sv::FatalProceduralOp::create(
              builder, parameter.getLoc(), 1,
              builder.getStringAttr("NODAL generated family parameter outside verified range"));
        });
      }
    return lowerGeneratedScope(nullptr);
  }
  LogicalResult lowerGeneratedScope(GeneratedFamilyPlan *parent) {
    for (auto *owned : parent ? parent->children : generatedRoots) {
      auto &family = *owned;
      SmallVector<Value> wires, buses;
      for (const auto &out : family.outputs) {
        Value wire = make(family.operation, "sv.wire", circt::hw::InOutType::get(out.busType), {},
                          {builder.getNamedAttr("name", builder.getStringAttr(""))});
        wires.push_back(wire);
        buses.push_back(make(family.operation, "sv.read_inout", out.busType, {wire}));
      }
      {
        OpBuilder::InsertionGuard insertion(builder);
        auto *wrapper = regionOperation(
            family.operation, "sv.generate",
            {builder.getNamedAttr(
                "sym_name",
                builder.getStringAttr("family_" + family.genvarName.getValue().str()))});
        builder.setInsertionPointToEnd(&wrapper->getRegion(0).front());
        auto *loop =
            regionOperation(family.operation, "sv.generate.for",
                            {builder.getNamedAttr("lowerBound", family.lower),
                             builder.getNamedAttr("upperBound", family.upper),
                             builder.getNamedAttr("step", family.step),
                             builder.getNamedAttr("genBlockName", builder.getStringAttr("lanes")),
                             builder.getNamedAttr("inductionVarName", family.genvarName)});
        Block &body = loop->getRegion(0).front();
        Value index = body.addArgument(builder.getI32Type(), family.operation->getLoc());
        builder.setInsertionPointToEnd(&body);
        SmallVector<Value> inputs;
        for (const auto &port : family.ports)
          if (port.input) {
            Value input = values.lookup(port.references.front());
            if (!input)
              return reject(family.operation, "generated template capture did not map");
            llvm::append_range(inputs, splitBoundary(port.key.first, input));
          }
        SmallVector<Attribute> parameters;
        for (Attribute attr : targetParameters) {
          auto declaration = cast<circt::hw::ParamDeclAttr>(attr);
          parameters.push_back(circt::hw::ParamDeclAttr::get(
              declaration.getName(),
              circt::hw::ParamDeclRefAttr::get(declaration.getName(), builder.getI32Type())));
        }
        parameters.push_back(circt::hw::ParamDeclAttr::get(
            family.indexName, circt::hw::ParamVerbatimAttr::get(
                                  builder.getContext(), family.genvarName, builder.getI32Type())));
        auto instance = circt::hw::InstanceOp::create(
            builder, family.operation->getLoc(), family.target, builder.getStringAttr("lane"),
            ArrayRef<Value>(inputs), builder.getArrayAttr(parameters));
        correlate(instance, family.operation);
        for (auto [i, out] : llvm::enumerate(family.outputs)) {
          Value destination = generatedSlice(family.operation, wires[i], index,
                                             circt::hw::InOutType::get(out.laneType));
          assignGenerated(family.operation, destination, instance.getResult(i));
        }
      }
      for (auto [i, out] : llvm::enumerate(family.outputs)) {
        if (parent)
          values[out.value] = buses[i];
        for (Value destination : out.destinations) {
          auto pin = destination.getDefiningOp<PortValueOp>();
          if (pin) {
            Operation *port =
                SymbolTable::lookupSymbolIn(source, pin->getAttrOfType<FlatSymbolRefAttr>("port"));
            for (Value reference : portValues[port])
              values[reference] = buses[i];
          } else {
            values[destination] = buses[i];
          }
        }
      }
      for (Operation *operation : family.selections) {
        const auto &member = generatedMembers.find(operation)->second;
        auto *plan = &family;
        unsigned selected = 0;
        while (plan->outputs[selected].value != member.storage)
          ++selected; // Fully bounded and validated by the prefix preflight.
        Value bus = buses[selected];
        TypedAttr offset = builder.getI32IntegerAttr(0);
        SmallVector<Attribute> prefixes;
        Type selectedType;
        for (const auto &selection : member.selections) {
          const auto &out = plan->outputs[selected];
          TypedAttr coordinate = parameterExpression(selection.coordinate);
          TypedAttr stride = generatedWidth(out.laneType);
          offset = circt::hw::ParamExprAttr::get(
              circt::hw::PEO::Add, offset,
              circt::hw::ParamExprAttr::get(circt::hw::PEO::Mul, coordinate, stride));
          prefixes.push_back(builder.getDictionaryAttr(
              {builder.getNamedAttr("family", selection.family->getAttr("region_id")),
               builder.getNamedAttr("coordinate", coordinate),
               builder.getNamedAttr("stride_bits", stride)}));
          selectedType = out.laneType;
          plan = out.child;
          selected = out.childOutput;
        }
        // Compose every verified prefix stride, not just the final coordinate.
        // A single range select avoids invalid SV select chaining even when
        // downstream optimization removes intermediate adapter wires.
        Value index = circt::sv::LocalParamOp::create(
            builder, operation->getLoc(), builder.getI32Type(), offset, builder.getStringAttr(""));
        Value value = generatedSlice(operation, bus, index, selectedType, true);
        value.getDefiningOp()->setAttr("nodal.generated_selection", builder.getArrayAttr(prefixes));
        values[operation->getResult(0)] = value;
      }
    }
    return success();
  }

  void appendPhysicalPorts(ArrayRef<LogicalPortPlan> ports,
                           SmallVectorImpl<circt::hw::PortInfo> &targetPorts, unsigned &inputIndex,
                           unsigned &outputIndex) {
    for (const auto &port : ports) {
      SmallVector<Type> types;
      if (auto structure = dyn_cast<circt::hw::StructType>(port.targetType))
        for (const auto &field : structure.getElements())
          types.push_back(field.type);
      else
        types.push_back(port.targetType);
      for (auto [index, type] : llvm::enumerate(types)) {
        circt::hw::PortInfo info;
        info.name = port.pins[index];
        info.type = type;
        info.dir = port.input ? circt::hw::ModulePort::Input : circt::hw::ModulePort::Output;
        info.argNum = port.input ? inputIndex++ : outputIndex++;
        info.loc = port.key.first->getLoc();
        NamedAttrList attributes;
        if (!sourcePath(port.key.first).empty())
          attributes.set("nodal.source_path", builder.getStringAttr(sourcePath(port.key.first)));
        if (aggregatePayload(port.originalType) && !isa<circt::hw::StructType>(port.targetType))
          attributes.set("nodal.packed_abi", packedLayout(port.originalType));
        if (port.interfaceContract || isa<circt::hw::StructType>(port.targetType)) {
          attributes.set("nodal.logical_port", port.name);
          attributes.set("nodal.logical_leaf", builder.getI32IntegerAttr(index));
        }
        info.attrs = builder.getDictionaryAttr(attributes);
        targetPorts.push_back(info);
      }
    }
  }

  LogicalResult planInterfaces() {
    bool hasEndpoints = false;
    for (Operation &op : source.getBody().front()) {
      if (isa<InterfaceOp, InterfaceInstanceOp, InterfaceBoundaryOp, InterfaceAbiOp>(op))
        staticOperations.insert(&op);
      if (isa<InterfaceInstanceOp, InterfaceBoundaryOp>(op)) {
        if (!hasDigitalInterfaceContract(&op))
          return reject(&op, "target requires fully typed Interface endpoint contracts");
        hasEndpoints = true;
      }
    }
    if (!hasEndpoints)
      return success();
    if (hierarchy) {
      auto found = hierarchy->interfaces.find(source);
      if (found == hierarchy->interfaces.end()) {
        auto collected = std::make_unique<SmallVector<DigitalInterfaceEndpoint>>();
        if (failed(collectDigitalInterfaceEndpoints(source, *collected)))
          return failure();
        found = hierarchy->interfaces.try_emplace(source, std::move(collected)).first;
      }
      endpoints = *found->second;
    } else {
      if (failed(collectDigitalInterfaceEndpoints(source, localEndpoints)))
        return failure();
      endpoints = localEndpoints;
    }
    for (auto [index, endpoint] : llvm::enumerate(endpoints)) {
      interfaceEndpoints.try_emplace(endpoint.operation, index);
      for (const auto &leaf : endpoint.leaves) {
        if (!llvm::is_contained(ArrayRef<StringRef>{"in", "out"}, leaf.access.getValue()) ||
            !llvm::is_contained(ArrayRef<StringRef>{"plain", "valid", "stream"},
                                leaf.protocol.getValue()) ||
            isa<ResolvedType>(leaf.type) || isDigitalControlBoundaryType(leaf.type) ||
            (leaf.analogMetadata && !leaf.analogMetadata.empty()) ||
            (leaf.analogReferences && !leaf.analogReferences.empty()))
          return reject(endpoint.operation,
                        "Interface target requires ordinary digital in/out leaves; resolved, "
                        "analog, control and monitor endpoints need an explicit physical profile");
        if (cost >= maximumGeneratedOperations)
          return reject(endpoint.operation, "complete Interface inventory exceeds target budget");
        ++cost;
      }
    }
    for (Operation &op : source.getBody().front())
      if (isa<InterfaceInstanceOp, InterfaceBoundaryOp>(op) && !interfaceEndpoints.count(&op))
        return reject(&op,
                      "typed Interface endpoint is absent from its complete checked inventory");
    return success();
  }
  LogicalResult planLogicalPorts() {
    for (Operation &op : source.getBody().front()) {
      if (isa<PortOp>(op)) {
        LogicalPortPlan port;
        port.key = {&op, Attribute()};
        port.originalType = op.getAttrOfType<TypeAttr>("type").getValue();
        port.targetType = portTypes.lookup(&op);
        port.name = op.getAttrOfType<StringAttr>("sym_name");
        port.input = text(&op, "direction") == "input";
        port.references = portValues[&op];
        logicalPorts.push_back(std::move(port));
        continue;
      }
      if (!isa<InterfaceInstanceOp>(op))
        continue;
      const auto &endpoint = endpoints[interfaceEndpoints.lookup(&op)];
      std::string dimensions;
      for (Attribute attr : endpoint.dimensions) {
        if (!dimensions.empty())
          dimensions += ',';
        dimensions += cast<StringAttr>(attr).getValue();
      }
      for (const auto &leaf : endpoint.leaves) {
        LogicalPortPlan port;
        port.key = {endpoint.declaration, leaf.path};
        port.originalType =
            dimensions.empty()
                ? leaf.type
                : Type(nodal::ShapedType::get(source.getContext(), dimensions, leaf.type));
        port.targetType = targetValueType(port.originalType);
        if (!port.targetType)
          return reject(&op, "Interface payload requires supported bounded digital boundary type; "
                             "symbolic Struct requires boundary-abi=named-fields");
        port.input = leaf.access.getValue() == "in";
        port.references = interfacePortValues[port.key];
        std::string name = text(&op, "sym_name").str() + "__m";
        SmallVector<StringRef> segments;
        leaf.path.getValue().split(segments, '.');
        for (StringRef segment : segments)
          name += "_" + std::to_string(segment.size()) + "_" + segment.str();
        port.name = builder.getStringAttr(name);
        std::string originalType;
        llvm::raw_string_ostream stream(originalType);
        stream << leaf.type;
        NamedAttrList contract;
        contract.set("endpoint", op.getAttr("sym_name"));
        contract.set("definition", endpoint.semanticIdentity);
        contract.set("selected_role", endpoint.selectedRole->getAttr("sym_name"));
        contract.set("member", leaf.path);
        contract.set("protocol", leaf.protocol);
        contract.set("role", leaf.role);
        contract.set("access", leaf.access);
        contract.set("nested_roles", leaf.nestedRoles);
        contract.set("nested_interfaces", leaf.nestedInterfaces);
        contract.set("dimensions", endpoint.dimensions);
        contract.set("payload_type", builder.getStringAttr(originalType));
        port.interfaceContract = builder.getDictionaryAttr(contract);
        logicalPorts.push_back(std::move(port));
      }
    }
    return success();
  }
  TypedAttr parameterNode(const std::shared_ptr<const InstantiatedIntegerNode> &node,
                          unsigned depth = 0) {
    if (!node || depth > digitalTypeMaximumDepth)
      return {};
    if (node->bounds.lower < std::numeric_limits<int32_t>::min() ||
        node->bounds.upper > std::numeric_limits<int32_t>::max()) {
      source.emitOpError("NODAL-CIRCT-056-001: static Integer expression bounds must fit signed "
                         "32-bit arithmetic without truncation");
      return {};
    }
    if (auto found = parameterExpressions.find(node.get()); found != parameterExpressions.end())
      return found->second;
    if (parameterExpressions.size() >= maximumAuthoredOperations ||
        cost >= maximumGeneratedOperations)
      return {};
    ++cost; // Parameter DAG attributes share the transaction's expansion budget.
    TypedAttr value;
    using Kind = InstantiatedIntegerNode::Kind;
    if (node->kind == Kind::Literal)
      value = builder.getI32IntegerAttr(node->literal);
    else if (node->kind == Kind::ParentParameter) {
      if (!symbolicParameters.contains(node->parameter))
        return {};
      value = circt::hw::ParamDeclRefAttr::get(
          node->parameter->getAttrOfType<StringAttr>("sym_name"), builder.getI32Type());
    } else if (node->kind == Kind::Apply) {
      SmallVector<TypedAttr> operands;
      for (const auto &operand : node->operands) {
        auto actual = parameterNode(operand, depth + 1);
        if (!actual)
          return {};
        operands.push_back(actual);
      }
      if (node->opcode == "add" && operands.size() == 2)
        value = circt::hw::ParamExprAttr::get(circt::hw::PEO::Add, operands);
      else if (node->opcode == "mul" && operands.size() == 2)
        value = circt::hw::ParamExprAttr::get(circt::hw::PEO::Mul, operands);
      else if ((node->opcode == "sub" && operands.size() == 2) ||
               (node->opcode == "neg" && operands.size() == 1)) {
        // CIRCT expresses subtraction through multiplication by -1. Preserve
        // the source mathematical contract for that new signed-i32 intermediate.
        if (node->operands.back()->bounds.lower == std::numeric_limits<int32_t>::min() ||
            maximumGeneratedOperations - cost < 2)
          return {};
        cost += 2;
        auto negative = circt::hw::ParamExprAttr::get(circt::hw::PEO::Mul, operands.back(),
                                                      builder.getI32IntegerAttr(-1));
        value = operands.size() == 1 ? negative
                                     : circt::hw::ParamExprAttr::get(circt::hw::PEO::Add,
                                                                     operands.front(), negative);
      } else
        return {}; // No unchecked verbatim fallback or evaluated default.
    }
    if (value)
      parameterExpressions.try_emplace(node.get(), value);
    return value;
  }
  TypedAttr parameterExpression(const InstantiatedIntegerExpression &expression) {
    // The memo keys refer to immutable query-local proof nodes. Retain their
    // owning roots for this target transaction, never pointers into dead queries.
    if (parameterExpressionRoots.size() >= maximumAuthoredOperations)
      return {};
    parameterExpressionRoots.push_back(expression.root);
    return parameterNode(expression.root);
  }
  LogicalResult planParameters() {
    llvm::DenseMap<Attribute, Value> defaults;
    for (Operation &op : source.getBody().front()) {
      if (isa<ParameterOp>(op)) {
        auto type = op.getAttrOfType<TypeAttr>("type");
        if (!type || !type.getValue().isSignlessInteger(64) || getParameterKind(&op) != "integer" ||
            op.hasAttr("unit"))
          return reject(&op, "symbolic target parameters require unitless i64 Integer");
        if (text(&op, "variability") == "symbolic")
          symbolicParameters.insert(&op);
        staticOperations.insert(&op);
      } else if (isa<ParameterValueOp>(op)) {
        defaults.try_emplace(op.getAttr("parameter"), op.getOperand(0));
        staticOperations.insert(&op);
      } else if (isa<ParameterConstraintOp, ParameterEnvelopeOp, ParameterOverrideOp>(op))
        staticOperations.insert(&op);
      else if (isa<ConstParameterRefOp, ConstExprOp, ConstLiteralOp>(op) &&
               op.getNumResults() == 1 && op.getResult(0).getType().isSignlessInteger(64))
        staticOperations.insert(&op);
    }
    for (Operation *op : staticOperations)
      for (Value result : op->getResults())
        for (Operation *user : result.getUsers())
          if (!staticOperations.contains(user) && !controlSelectors.contains(user) &&
              !controlProjections.contains(user) && !isa<GenerateOp, GeneratedMemberOp>(user) &&
              !user->getParentOfType<GenerateOp>())
            return reject(user, "static Integer target parameters cannot become runtime values");
    SmallVector<Attribute> declarations;
    for (Operation &op : source.getBody().front()) {
      if (!symbolicParameters.contains(&op))
        continue;
      // HW IntType's textual width contract uses i32 parameter expressions.
      // Reject wider legal envelopes instead of truncating native i64 semantics.
      auto envelope =
          resolveOwnedIntegerParameter(source, &op, ParameterIntegerMode::MathematicalSigned64);
      if (failed(envelope) || !parameterExpression(*envelope))
        return reject(&op, "target Integer parameter envelope must fit signed 32-bit arithmetic");
      TypedAttr value;
      auto reference = FlatSymbolRefAttr::get(builder.getContext(), text(&op, "sym_name"));
      if (Value expression = defaults.lookup(reference)) {
        auto proof = resolveOwnedIntegerExpression(source, expression,
                                                   ParameterIntegerMode::MathematicalSigned64);
        if (succeeded(proof))
          value = parameterExpression(*proof);
      } else {
        auto literal = dyn_cast_or_null<IntegerAttr>(op.getAttr("default_value"));
        if (literal && literal.getType().isSignlessInteger(64) &&
            literal.getInt() >= std::numeric_limits<int32_t>::min() &&
            literal.getInt() <= std::numeric_limits<int32_t>::max())
          value = builder.getI32IntegerAttr(literal.getInt());
      }
      if (!value || value.getType() != builder.getI32Type())
        return reject(&op,
                      "parameter default requires a supported exact static Integer expression");
      declarations.push_back(
          circt::hw::ParamDeclAttr::get(op.getAttrOfType<StringAttr>("sym_name"), value));
    }
    targetParameters = builder.getArrayAttr(declarations);
    return success();
  }
  FailureOr<ArrayAttr> instanceParameters(Operation *instance) {
    Operation *child = resolveInstanceModuleDefinition(instance);
    if (!child)
      return failure();
    SmallVector<Attribute> actuals;
    for (Operation &op : child->getRegion(0).front()) {
      if (!isa<ParameterOp>(op) || text(&op, "variability") != "symbolic")
        continue;
      auto proof = resolveInstanceIntegerParameter(instance, &op,
                                                   ParameterIntegerMode::MathematicalSigned64);
      TypedAttr value = succeeded(proof) ? parameterExpression(*proof) : TypedAttr();
      if (!value || cost >= maximumGeneratedOperations)
        return failure();
      ++cost;
      actuals.push_back(
          circt::hw::ParamDeclAttr::get(op.getAttrOfType<StringAttr>("sym_name"), value));
    }
    return builder.getArrayAttr(actuals);
  }
  static bool aggregatePayload(Type type) {
    return isa<nodal::ShapedType, StructType>(type) && getDigitalControlBoundaryKind(type).empty();
  }
  std::string leafName(const HierarchyBoundaryLeaf &leaf, unsigned skip = 0) {
    std::string result = "f";
    for (const auto &field : ArrayRef(leaf.path).drop_front(skip))
      result +=
          "_" + std::to_string(field.name.getValue().size()) + "_" + field.name.getValue().str();
    return result;
  }
  Type leafVectorType(const HierarchyBoundaryLeaf &leaf) {
    TypedAttr packed = parameterExpression(leaf.width);
    for (const auto &group : leaf.dimensions)
      for (const auto &extent : group.extents) {
        TypedAttr factor = parameterExpression(extent);
        if (!packed || !factor || cost >= maximumGeneratedOperations)
          return {};
        ++cost;
        packed = circt::hw::ParamExprAttr::get(circt::hw::PEO::Mul, packed, factor);
      }
    return packed ? circt::hw::IntType::get(packed) : Type();
  }
  SmallVector<Value> splitBoundary(Operation *at, Value value) {
    SmallVector<Value> result;
    if (auto type = dyn_cast<circt::hw::StructType>(value.getType())) {
      for (auto [index, field] : llvm::enumerate(type.getElements()))
        result.push_back(
            make(at, "hw.struct_extract", field.type, {value},
                 {builder.getNamedAttr("fieldIndex", builder.getI32IntegerAttr(index))}));
    } else
      result.push_back(value);
    return result;
  }
  Value joinBoundary(Operation *at, Type type, ValueRange leaves) {
    return isa<circt::hw::StructType>(type) ? make(at, "hw.struct_create", type, leaves)
                                            : leaves.front();
  }
  Type targetValueType(Type type) {
    if (Type scalar = targetType(type))
      return scalar;
    if (!aggregatePayload(type) && !isa<ParametricIntegerType>(type))
      return {};
    auto [entry, inserted] = packedTypes.try_emplace(type, Type());
    if (inserted) {
      auto view = inspectHierarchyBoundaryType(type, source);
      auto maximum = inferDigitalBitPatternMaximumBits(type, source);
      if (succeeded(view) && succeeded(maximum) && *maximum <= maximumWidth) {
        boundaryViews.try_emplace(type, *view);
        auto fixed = inferDigitalBitPatternFixedBits(type, source);
        if (view->leaves.size() == 1 && view->leaves[0].path.empty())
          entry->second = leafVectorType(view->leaves.front());
        else if (!namedBoundaryABI) {
          if (succeeded(fixed))
            entry->second = builder.getIntegerType(*fixed); // Established packed CDC ABI.
        } else {
          SmallVector<circt::hw::StructType::FieldInfo> fields;
          for (const auto &leaf : view->leaves) {
            Type vector = leafVectorType(leaf);
            if (!vector || cost >= maximumGeneratedOperations)
              return {};
            ++cost;
            fields.push_back({builder.getStringAttr(leafName(leaf)), vector});
          }
          entry->second = circt::hw::StructType::get(builder.getContext(), fields);
        }
      }
    }
    return entry->second;
  }
  DictionaryAttr packedLayout(Type type) {
    auto found = packedLayouts.find(type);
    if (found != packedLayouts.end())
      return found->second;
    if (isa_and_nonnull<circt::hw::StructType>(packedTypes.lookup(type))) {
      const auto &view = boundaryViews.find(type)->second;
      SmallVector<Attribute> leaves;
      for (const auto &leaf : view.leaves) {
        SmallVector<Attribute> path, groups;
        for (const auto &field : leaf.path)
          path.push_back(
              builder.getDictionaryAttr({builder.getNamedAttr("structure", field.structure),
                                         builder.getNamedAttr("field", field.name)}));
        for (const auto &group : leaf.dimensions) {
          SmallVector<Attribute> extents;
          for (const auto &extent : group.extents)
            extents.push_back(parameterExpression(extent));
          groups.push_back(builder.getDictionaryAttr(
              {builder.getNamedAttr("field_depth", builder.getI32IntegerAttr(group.fieldDepth)),
               builder.getNamedAttr("extents", builder.getArrayAttr(extents))}));
        }
        std::string elementType;
        llvm::raw_string_ostream elementStream(elementType);
        leaf.type.print(elementStream);
        leaves.push_back(builder.getDictionaryAttr(
            {builder.getNamedAttr("field", builder.getStringAttr(leafName(leaf))),
             builder.getNamedAttr("logical_path", builder.getArrayAttr(path)),
             builder.getNamedAttr("dimension_groups", builder.getArrayAttr(groups)),
             builder.getNamedAttr("element_type", builder.getStringAttr(elementType)),
             builder.getNamedAttr("element_width", parameterExpression(leaf.width))}));
      }
      auto result = builder.getDictionaryAttr(
          {builder.getNamedAttr("kind", builder.getStringAttr("named-leaf-vectors")),
           builder.getNamedAttr("logical_order",
                                builder.getStringAttr("recursive-row-major-fields-lsb-first")),
           builder.getNamedAttr("element_order",
                                builder.getStringAttr("row-major-zero-element-lsb")),
           builder.getNamedAttr("leaves", builder.getArrayAttr(leaves))});
      packedLayouts.try_emplace(type, result);
      return result;
    }
    // This is target representation metadata, not a second type validator.
    // Exact width/shape facts were established by the canonical owners above.
    // Record structural strides rather than expanding physical vector lanes.
    auto bits = inferDigitalBitPatternFixedBits(type, source);
    if (failed(bits) || isa_and_nonnull<circt::hw::IntType>(packedTypes.lookup(type))) {
      auto view = inspectHierarchyBoundaryType(type, source);
      assert(succeeded(view) && view->leaves.size() == 1 && view->leaves[0].path.empty());
      const auto &leaf = view->leaves.front();
      NamedAttrList layout;
      layout.set("kind", builder.getStringAttr("scalar-element-vec"));
      layout.set("order", builder.getStringAttr("recursive-row-major-fields-lsb-first"));
      layout.set("element_width", parameterExpression(leaf.width));
      SmallVector<Attribute> groups;
      for (const auto &group : leaf.dimensions) {
        SmallVector<Attribute> extents;
        for (const auto &extent : group.extents)
          extents.push_back(parameterExpression(extent));
        groups.push_back(builder.getArrayAttr(extents));
      }
      layout.set("dimension_groups", builder.getArrayAttr(groups));
      auto result = builder.getDictionaryAttr(layout);
      packedLayouts.try_emplace(type, result);
      return result;
    }
    assert(*bits <= maximumWidth);
    NamedAttrList layout;
    std::string identity;
    llvm::raw_string_ostream stream(identity);
    type.print(stream);
    layout.set("source_type", builder.getStringAttr(identity));
    layout.set("width", builder.getI64IntegerAttr(*bits));
    layout.set("order", builder.getStringAttr("recursive-row-major-fields-lsb-first"));
    if (auto shape = dyn_cast<nodal::ShapedType>(type)) {
      layout.set("kind", builder.getStringAttr("vec"));
      DictionaryAttr element = packedLayout(shape.getElementType());
      layout.set("element", element);
      SmallVector<StringRef> dimensions;
      shape.getDimensions().split(dimensions, ',');
      SmallVector<Attribute> extents, strides(dimensions.size());
      int64_t stride = element.getAs<IntegerAttr>("width").getInt();
      for (StringRef dimension : dimensions) {
        auto extent = resolveStaticShapeDimension(source, dimension);
        assert(succeeded(extent) && extent->bounds.lower == extent->bounds.upper);
        extents.push_back(builder.getI64IntegerAttr(extent->bounds.lower));
      }
      for (unsigned i = dimensions.size(); i; --i) {
        strides[i - 1] = builder.getI64IntegerAttr(stride);
        stride *= cast<IntegerAttr>(extents[i - 1]).getInt();
      }
      layout.set("dimensions", builder.getArrayAttr(extents));
      layout.set("bit_strides", builder.getArrayAttr(strides));
    } else if (auto structure = dyn_cast<StructType>(type)) {
      layout.set("kind", builder.getStringAttr("struct"));
      SmallVector<Attribute> fields;
      int64_t offset = 0;
      for (Attribute attribute : structure.getFields()) {
        auto field = cast<DictionaryAttr>(attribute);
        DictionaryAttr child = packedLayout(field.getAs<TypeAttr>("type").getValue());
        fields.push_back(builder.getDictionaryAttr(
            {builder.getNamedAttr("name", field.getAs<StringAttr>("name")),
             builder.getNamedAttr("bit_offset", builder.getI64IntegerAttr(offset)),
             builder.getNamedAttr("layout", child)}));
        offset += child.getAs<IntegerAttr>("width").getInt();
      }
      layout.set("fields", builder.getArrayAttr(fields));
    } else
      layout.set("kind", builder.getStringAttr("scalar"));
    auto result = builder.getDictionaryAttr(layout);
    packedLayouts.try_emplace(type, result);
    return result;
  }
  void correlateClock(Operation *target, Operation *op, StringRef role) {
    correlate(target, op);
    target->setAttr("nodal.clock", op->getAttr("metadata"));
    target->setAttr("nodal.clock_contract", op->getAttr("contract"));
    target->setAttr("nodal.clock_domain", op->getAttr("domain"));
    target->setAttr("nodal.clock_parents", op->getAttr("parent_domains"));
    target->setAttr("nodal.clock_obligations", op->getAttr("obligations"));
    target->setAttr("nodal.clock_role", builder.getStringAttr(role));
  }
  std::string clockSourceComment(Operation *op) {
    std::string path = sourcePath(op).str();
    for (char &character : path)
      if (character == '\n' || character == '\r')
        character = ' ';
      else if (character == '{')
        character = '(';
      else if (character == '}')
        character = ')';
    return "// NODAL owned clock " + path + ": ";
  }
  void clockComment(Operation *op, StringRef contract) {
    std::string detail;
    llvm::raw_string_ostream stream(detail);
    stream << clockSourceComment(op) << contract << "; domain=" << op->getAttr("domain")
           << "; parents=" << op->getAttr("parent_domains") << "; edge=" << text(op, "edge")
           << "; obligations=" << op->getAttr("obligations");
    auto comment =
        circt::sv::VerbatimOp::create(builder, op->getLoc(), builder.getStringAttr(detail),
                                      ValueRange{}, builder.getArrayAttr({}));
    correlateClock(comment, op, "contract");
  }
  void clockAssertion(Operation *op, Value clock, Value reset, Value known, StringRef role,
                      bool falling = false) {
    // Immediate, four-state assertions also execute with Icarus. Unknown reset
    // never hides an unknown control: only a known asserted local reset skips.
    std::string check =
        clockSourceComment(op) + role.str() + " known-state check\n" + "always @(" +
        (falling ? "negedge" : "posedge") + " {{0}}) begin\n" + "  if (({{1}}) !== 1'b1) begin\n" +
        "    assert ((^({{2}})) !== 1'bx) else $fatal(1, \"NODAL-CLOCK-057: " + role.str() +
        " requires known controls and an established reset epoch\");\n  end\nend";
    auto assertion =
        circt::sv::VerbatimOp::create(builder, op->getLoc(), builder.getStringAttr(check),
                                      ValueRange{clock, reset, known}, builder.getArrayAttr({}));
    correlateClock(assertion, op, role);
    assertion->setAttr("nodal.clock_check", op->getAttr("contract"));
  }
  Value clockState(Operation *op, StringRef role, Value input, Value clock, Value reset,
                   uint64_t resetValue = 0) {
    StringAttr name = clockNames.find(op)->second.lookup(role);
    auto state = circt::seq::FirRegOp::create(
        builder, op->getLoc(), input, clock, name, reset,
        constant(op, cast<IntegerType>(input.getType()).getWidth(), resetValue),
        circt::hw::InnerSymAttr::get(name), true);
    correlateClock(state, op, role);
    return state.getResult();
  }
  Value lowerClock(Operation *op, circt::BackedgeBuilder &backedges) {
    bool falling = text(op, "edge") == "falling";
    Value first = values.lookup(op->getOperand(0));
    Value firstBit = circt::seq::FromClockOp::create(builder, op->getLoc(), first);
    if (falling)
      first = circt::seq::ClockInverterOp::create(builder, op->getLoc(), first);
    if (named(op, "nodal.clock_gate")) {
      auto gate = circt::seq::ClockGateOp::create(
          builder, op->getLoc(), first, values.lookup(op->getOperand(1)),
          values.lookup(op->getOperand(2)),
          circt::hw::InnerSymAttr::get(clockNames.find(op)->second.lookup("gate")));
      correlateClock(gate, op, "gate");
      Value result = gate.getResult();
      if (falling) {
        result = circt::seq::ClockInverterOp::create(builder, op->getLoc(), result);
        correlateClock(result.getDefiningOp(), op, "physical_output");
      }
      Value reset = constant(op, 1, 0);
      auto binding = domains.resolveClockBinding(op->getAttrOfType<ArrayAttr>("parent_domains")[0]);
      if (failed(binding))
        return {};
      if ((*binding)->getNumOperands() == 2)
        reset = resetActive(values.lookup((*binding)->getOperand(1)), *binding);
      Value controls = make(op, "comb.concat", builder.getIntegerType(2),
                            {values.lookup(op->getOperand(1)), values.lookup(op->getOperand(2))});
      clockAssertion(op, firstBit, reset, controls, "gate enable/testEnable", falling);
      clockComment(op,
                   "gate_v1: transparent during the inactive parent phase; enable OR testEnable; "
                   "no invented latch initialization; startup known after a complete inactive "
                   "phase with known controls; inactive_phase_aperture is an environment "
                   "setup/hold obligation, not a metastability proof");
      Value bit = circt::seq::FromClockOp::create(builder, op->getLoc(), result);
      StringAttr outputName = clockNames.find(op)->second.lookup("output");
      auto wire =
          circt::hw::WireOp::create(builder, op->getLoc(), bit, circt::StringAttrOrRef(outputName),
                                    circt::hw::InnerSymAttr::get(outputName));
      correlateClock(wire, op, "observable_output");
      return circt::seq::ToClockOp::create(builder, op->getLoc(), wire.getResult());
    }
    Value second = values.lookup(op->getOperand(1));
    if (falling)
      second = circt::seq::ClockInverterOp::create(builder, op->getLoc(), second);
    Value active0 = circt::seq::FromClockOp::create(builder, op->getLoc(), first);
    Value active1 = circt::seq::FromClockOp::create(builder, op->getLoc(), second);
    Value inactive0 = circt::seq::ClockInverterOp::create(builder, op->getLoc(), first);
    Value inactive1 = circt::seq::ClockInverterOp::create(builder, op->getLoc(), second);
    Value assertion =
        resetActive(values.lookup(op->getOperand(3)), op->getOperand(3).getDefiningOp());
    auto release = [&](StringRef prefix, Value clock) {
      Value stage0 =
          clockState(op, prefix.str() + "_release_0", constant(op, 1, 0), clock, assertion, 1);
      Value stage1 = clockState(op, prefix.str() + "_release_1", stage0, clock, assertion, 1);
      return std::make_pair(stage0, stage1);
    };
    auto controllerRelease = release("controller", first);
    auto branch0Release = release("branch0", inactive0);
    auto branch1Release = release("branch1", inactive1);
    Value controllerReset = controllerRelease.second;
    Value selected0 =
        clockState(op, "select_0", values.lookup(op->getOperand(2)), first, controllerReset);
    Value selected1 = clockState(op, "select_1", selected0, first, controllerReset);
    auto controllerNext = backedges.get(builder.getIntegerType(3), op->getLoc());
    auto selectedNext = backedges.get(builder.getI1Type(), op->getLoc());
    Value controller = clockState(op, "controller", controllerNext, first, controllerReset);
    Value selected = clockState(op, "selected", selectedNext, first, controllerReset);
    // Exact five-state encoding. After two initial samples, WaitOff consumes
    // the second stage only on the following edge (old-state semantics).
    enum : uint64_t { Init0, Init1, WaitOff, WaitOn, Run };
    auto inState = [&](uint64_t state) {
      return compare(op, "digital_eq", controller, constant(op, 3, state), false);
    };
    Value initializing0 = inState(Init0), initializing1 = inState(Init1);
    Value waitingOff = inState(WaitOff), waitingOn = inState(WaitOn), running = inState(Run);
    Value requesting = make(op, "comb.or", builder.getI1Type(), {waitingOn, running});
    Value request0 = make(op, "comb.and", builder.getI1Type(), {requesting, invert(op, selected)});
    Value request1 = make(op, "comb.and", builder.getI1Type(), {requesting, selected});
    auto branch = [&](StringRef prefix, Value request, Value clock,
                      std::pair<Value, Value> localRelease, Value active) {
      Value reset = localRelease.second;
      Value sync0 = clockState(op, prefix.str() + "_request_0", request, clock, reset);
      Value sync1 = clockState(op, prefix.str() + "_request_1", sync0, clock, reset);
      Value enabled = clockState(op, prefix.str() + "_enable", sync1, clock, reset);
      Value known = make(op, "comb.concat", builder.getIntegerType(6),
                         {localRelease.first, reset, request, sync0, sync1, enabled});
      clockAssertion(op, active, reset, known, prefix.str() + " inactive-edge controls", true);
      return enabled;
    };
    Value enabled0 = branch("branch0", request0, inactive0, branch0Release, active0);
    Value enabled1 = branch("branch1", request1, inactive1, branch1Release, active1);
    Value ack00 = clockState(op, "branch0_ack_0", enabled0, first, controllerReset);
    Value ack01 = clockState(op, "branch0_ack_1", ack00, first, controllerReset);
    Value ack10 = clockState(op, "branch1_ack_0", enabled1, first, controllerReset);
    Value ack11 = clockState(op, "branch1_ack_1", ack10, first, controllerReset);
    Value bothOff = invert(op, make(op, "comb.or", builder.getI1Type(), {ack01, ack11}));
    Value actualOn = mux(op, selected, ack11, ack01);
    Value changed = compare(op, "digital_ne", selected1, selected, false);
    Value start = make(op, "comb.and", builder.getI1Type(), {waitingOff, bothOff});
    selectedNext.setValue(mux(op, start, selected1, selected));
    Value next = mux(op, changed, constant(op, 3, WaitOff), controller);
    next = mux(op, running, next, controller);
    next = mux(op, waitingOn, mux(op, actualOn, constant(op, 3, Run), controller), next);
    next = mux(op, waitingOff, mux(op, bothOff, constant(op, 3, WaitOn), controller), next);
    next = mux(op, initializing1, constant(op, 3, WaitOff), next);
    next = mux(op, initializing0, constant(op, 3, Init1), next);
    controllerNext.setValue(next);
    Value known = make(op, "comb.concat", builder.getIntegerType(13),
                       {controllerRelease.first, controllerReset, values.lookup(op->getOperand(2)),
                        selected0, selected1, controller, selected, ack00, ack01, ack10, ack11});
    clockAssertion(op, active0, controllerReset, known, "mux controller active-edge controls");
    Value output0 = make(op, "comb.and", builder.getI1Type(), {active0, enabled0});
    Value output1 = make(op, "comb.and", builder.getI1Type(), {active1, enabled1});
    Value output = make(op, "comb.or", builder.getI1Type(), {output0, output1});
    if (falling)
      output = invert(op, output);
    StringAttr outputName = clockNames.find(op)->second.lookup("output");
    auto wire =
        circt::hw::WireOp::create(builder, op->getLoc(), output, circt::StringAttrOrRef(outputName),
                                  circt::hw::InnerSymAttr::get(outputName));
    correlateClock(wire, op, "observable_output");
    Value result = circt::seq::ToClockOp::create(builder, op->getLoc(), wire.getResult());
    correlateClock(result.getDefiningOp(), op, "physical_output");
    clockComment(op, "glitchless_mux_v1: serialized_break_before_make; selector level_coalescing; "
                     "first parent ungated active-edge controller; two selector samples before "
                     "first request; each branch request sync2 then actual-enable inactive-edge "
                     "register; returned actual-enable ack2; WAIT_ON cannot cancel before on "
                     "acknowledgement; WAIT_OFF waits for both off acknowledgements; 22 internal "
                     "state bits, three independent two-stage local releases, no invented "
                     "initialization; output reset_sync remains independent; pulse_integrity="
                     "except_reset_assertion because common assertion may truncate a pulse while "
                     "all consumers are held reset; normal operation preserves complete pulses; "
                     "liveness requires controller and relevant branch inactive edges; physical "
                     "control timing and common assertion duration remain environment obligations");
    return result;
  }
  struct CrossingControls {
    Value clock;
    Value assertionClock;
    Value reset;
    bool asynchronous = false;
    bool falling = false;
  };
  FailureOr<ContextualBinding> bindingFacts(Attribute reference) const {
    if (hierarchy)
      return hierarchy->proof->resolveBinding(variant->occurrence, reference);
    auto binding = domains.resolveBinding(reference);
    auto domain = domains.resolveDomain(reference);
    if (failed(binding) || failed(domain))
      return failure();
    ContextualBinding result{{0, *domain},
                             *binding,
                             {0, (*binding)->getOperand(0), *binding},
                             std::nullopt,
                             (*binding)->getAttrOfType<StringAttr>("reset_polarity")};
    if ((*binding)->getNumOperands() == 2)
      result.reset = ContextualValue{0, (*binding)->getOperand(1), *binding};
    return result;
  }
  StringRef updateSemantics(Operation *update, StringRef key) const {
    if (auto authored = update->getAttrOfType<StringAttr>(key))
      return authored.getValue();
    if (key == "reset_policy" && update->getNumOperands() == 3)
      return "none";
    auto facts = bindingFacts(update->getAttr("domain"));
    assert(succeeded(facts) && "mandatory occurrence proof established update binding");
    if (key == "reset_policy" && text(update, "initializer_policy") == "if_reset" && !facts->reset)
      return "none";
    return key == "reset_polarity" ? facts->resetPolarity.getValue()
                                   : text(facts->domain.declaration, key);
  }
  Value updateReset(Operation *update) const {
    if (text(update, "initializer_policy") == "if_reset")
      return domainInputs.lookup(update->getAttr("domain")).second;
    return values.lookup(update->getOperand(3));
  }
  Value updateInitial(Operation *update) const {
    return values.lookup(
        update->getOperand(text(update, "initializer_policy") == "if_reset" ? 3 : 4));
  }
  FailureOr<CrossingControls> crossingControls(Operation *at, Attribute reference,
                                               bool resetNeeded = true) {
    auto facts = bindingFacts(reference);
    if (failed(facts))
      return failure();
    Operation *domain = facts->domain.declaration;
    CrossingControls result;
    auto inferred = domainInputs.find(reference);
    if (inferred != domainInputs.end()) {
      result.clock = inferred->second.first;
      result.reset = inferred->second.second;
    } else {
      auto binding = domains.resolveBinding(reference);
      if (failed(binding))
        return failure();
      result.clock = values.lookup((*binding)->getOperand(0));
      if ((*binding)->getNumOperands() == 2)
        result.reset = values.lookup((*binding)->getOperand(1));
    }
    if (!result.clock)
      return failure();
    result.assertionClock = circt::seq::FromClockOp::create(builder, at->getLoc(), result.clock);
    result.falling = text(domain, "edge") == "falling";
    if (result.falling)
      result.clock = circt::seq::ClockInverterOp::create(builder, at->getLoc(), result.clock);
    StringRef policy = text(domain, "reset_policy");
    result.asynchronous =
        resetNeeded && (policy == "async" || policy == "async_assert_sync_release");
    if (!resetNeeded)
      result.reset = {};
    else if (policy != "none") {
      if (!result.reset)
        return failure();
      if (facts->resetPolarity.getValue() == "active_low")
        result.reset = invert(at, result.reset);
    }
    return result;
  }
  Value crossingState(const ExecutableCrossing &crossing, unsigned index, Value input,
                      const CrossingControls &control, StringRef role, bool resetState = true) {
    Operation *op = crossing.operation;
    StringAttr name = crossingNames.find(op)->second[index];
    Value reset = resetState ? control.reset : Value();
    Value zero = reset ? constant(op, cast<IntegerType>(input.getType()).getWidth(), 0) : Value();
    Value next = reset && !control.asynchronous ? mux(op, reset, zero, input) : input;
    Operation *state;
    if (reset && control.asynchronous)
      state = circt::seq::FirRegOp::create(builder, op->getLoc(), next, control.clock, name, reset,
                                           zero, circt::hw::InnerSymAttr::get(name), true);
    else
      state = circt::seq::CompRegOp::create(builder, op->getLoc(), next, control.clock,
                                            circt::StringAttrOrRef(name));
    correlate(state, op);
    // Symbols preserve independent chains through CSE. Metadata retains the
    // canonical source contract, every state role and the original source path.
    state->setAttr("nodal.crossing", op->getAttr("metadata"));
    state->setAttr("nodal.crossing_contract", op->getAttr("contract"));
    if (Attribute stages = op->getAttr("stages"))
      state->setAttr("nodal.crossing_stages", stages);
    if (Attribute depth = op->getAttr("depth"))
      state->setAttr("nodal.crossing_depth", depth);
    state->setAttr("nodal.crossing_source_domain", op->getAttr("source_domain"));
    state->setAttr("nodal.crossing_destination_domain", op->getAttr("destination_domain"));
    state->setAttr("nodal.crossing_stage", builder.getI64IntegerAttr(index));
    state->setAttr("nodal.crossing_role", builder.getStringAttr(role));
    if (Attribute obligations = op->getAttr("obligations"))
      state->setAttr("nodal.crossing_obligations", obligations);
    if (aggregatePayload(op->getOperand(0).getType()))
      state->setAttr("nodal.packed_abi", packedLayout(op->getOperand(0).getType()));
    return state->getResult(0);
  }
  void lowerFifo(const ExecutableCrossing &crossing, Value payload, Value sourceValid,
                 Value destinationReady, const CrossingControls &sourceControl,
                 const CrossingControls &destination, circt::BackedgeBuilder &backedges) {
    Operation *op = crossing.operation;
    unsigned pointerBits = crossing.pointerBits, addressBits = pointerBits - 1;
    Type pointerType = builder.getIntegerType(pointerBits);
    auto writeBinaryNext = backedges.get(pointerType, op->getLoc());
    auto writeGrayNext = backedges.get(pointerType, op->getLoc());
    auto readBinaryNext = backedges.get(pointerType, op->getLoc());
    auto readGrayNext = backedges.get(pointerType, op->getLoc());
    // All eight pointer-width controls have real local reset. In particular,
    // registered Gray values, not combinational binary bits, cross the clocks.
    Value writeBinary = crossingState(crossing, 0, writeBinaryNext, sourceControl, "source_binary");
    Value writeGray = crossingState(crossing, 1, writeGrayNext, sourceControl, "source_gray");
    auto readGraySource = backedges.get(pointerType, op->getLoc());
    Value read0 = crossingState(crossing, 2, readGraySource, sourceControl, "source_read_gray_0");
    Value read1 = crossingState(crossing, 3, read0, sourceControl, "source_read_gray_1");
    Value readBinary =
        crossingState(crossing, 4, readBinaryNext, destination, "destination_binary");
    Value readGray = crossingState(crossing, 5, readGrayNext, destination, "destination_gray");
    Value write0 = crossingState(crossing, 6, writeGray, destination, "destination_write_gray_0");
    Value write1 = crossingState(crossing, 7, write0, destination, "destination_write_gray_1");
    readGraySource.setValue(readGray);
    Value fullReadGray = make(op, "comb.xor", pointerType,
                              {read1, constant(op, pointerBits, uint64_t(3) << (pointerBits - 2))});
    Value sourceReady = make(op, "comb.and", builder.getI1Type(),
                             {invert(op, sourceControl.reset),
                              compare(op, "digital_ne", writeGray, fullReadGray, false)});
    Value destinationValid =
        make(op, "comb.and", builder.getI1Type(),
             {invert(op, destination.reset), compare(op, "digital_ne", readGray, write1, false)});
    Value writeAccepted = make(op, "comb.and", builder.getI1Type(), {sourceValid, sourceReady});
    Value readAccepted =
        make(op, "comb.and", builder.getI1Type(), {destinationValid, destinationReady});
    auto advance = [&](Value current, Value accepted) {
      return mux(op, accepted,
                 make(op, "comb.add", pointerType, {current, constant(op, pointerBits, 1)}),
                 current);
    };
    auto gray = [&](Value binary) {
      Value high = circt::comb::ExtractOp::create(builder, op->getLoc(), binary, 1, addressBits);
      Value shifted = make(op, "comb.concat", pointerType, {constant(op, 1, 0), high});
      return make(op, "comb.xor", pointerType, {binary, shifted});
    };
    Value nextWrite = advance(writeBinary, writeAccepted),
          nextRead = advance(readBinary, readAccepted);
    writeBinaryNext.setValue(nextWrite);
    writeGrayNext.setValue(gray(nextWrite));
    readBinaryNext.setValue(nextRead);
    readGrayNext.setValue(gray(nextRead));
    Value writeAddress =
        circt::comb::ExtractOp::create(builder, op->getLoc(), writeBinary, 0, addressBits);
    Value readAddress =
        circt::comb::ExtractOp::create(builder, op->getLoc(), readBinary, 0, addressBits);
    SmallVector<Value> bank;
    for (unsigned entry = 0; entry < crossing.depth; ++entry) {
      auto next = backedges.get(payload.getType(), op->getLoc());
      Value state = crossingState(crossing, 8 + entry, next, sourceControl,
                                  "bank_" + std::to_string(entry), false);
      Value selected =
          compare(op, "digital_eq", writeAddress, constant(op, addressBits, entry), false);
      Value enable = make(op, "comb.and", builder.getI1Type(), {writeAccepted, selected});
      next.setValue(mux(op, enable, payload, state));
      bank.push_back(state);
    }
    // An honest source-clock register bank and balanced combinational read mux,
    // not a pretend dual-clock memory. Whole payloads stay packed; no per-bit
    // expansion or fabricated empty/bank initialization is introduced.
    for (unsigned bit = 0; bank.size() > 1; ++bit) {
      Value select = circt::comb::ExtractOp::create(builder, op->getLoc(), readAddress, bit, 1);
      for (unsigned entry = 0; entry < bank.size() / 2; ++entry)
        bank[entry] = mux(op, select, bank[entry * 2 + 1], bank[entry * 2]);
      bank.resize(bank.size() / 2);
    }
    Value output = bank.front();
    values[op->getResult(0)] = output;
    values[op->getResult(1)] = destinationValid;
    values[op->getResult(2)] = sourceReady;
    unsigned monitorBase = 8 + crossing.depth;
    auto monitor = [&](Value sampledPayload, Value valid, Value ready,
                       const CrossingControls &control, unsigned index, StringRef endpoint) {
      Value previous = crossingState(crossing, index, sampledPayload, control,
                                     endpoint.str() + "_previous_payload", false);
      Value stalled = make(op, "comb.and", builder.getI1Type(), {valid, invert(op, ready)});
      Value previousStalled =
          crossingState(crossing, index + 1, stalled, control, endpoint.str() + "_stalled");
      Value same = compare(op, "digital_eq", sampledPayload, previous, false);
      Value held = make(op, "comb.and", builder.getI1Type(), {valid, same});
      Value legal = make(op, "comb.or", builder.getI1Type(), {invert(op, previousStalled), held});
      crossingAssertion(crossing, control, invert(op, control.reset), legal,
                        "NODAL FIFO " + endpoint.str() +
                            " must hold payload and valid while stalled");
      return previousStalled;
    };
    Value sourceStalled =
        monitor(payload, sourceValid, sourceReady, sourceControl, monitorBase, "source");
    Value destinationStalled = monitor(output, destinationValid, destinationReady, destination,
                                       monitorBase + 2, "destination");
    Value sourceKnown = make(op, "comb.concat", builder.getIntegerType(4 * pointerBits + 1),
                             {writeBinary, writeGray, read0, read1, sourceStalled});
    Value destinationKnown = make(op, "comb.concat", builder.getIntegerType(4 * pointerBits + 1),
                                  {readBinary, readGray, write0, write1, destinationStalled});
    crossingAssertion(crossing, sourceControl, invert(op, sourceControl.reset), sourceKnown,
                      "NODAL FIFO source controls require an established common reset epoch",
                      "(^({{2}})) !== 1'bx");
    crossingAssertion(crossing, destination, invert(op, destination.reset), destinationKnown,
                      "NODAL FIFO destination controls require an established common reset epoch",
                      "(^({{2}})) !== 1'bx");
    crossingComment(
        crossing, "FIFO obligations: transfer=valid_and_ready; stall=hold_payload_and_valid; "
                  "reset_epoch=common_assertion; pointer_skew=less_than_launch_period; "
                  "bundled_data=settled_before_read_visibility. Exactly depth packed source-clock "
                  "bank entries; binary/registered-Gray pointers and two-stage opposite-pointer "
                  "synchronizers. No combinational cross-clock ready/valid path. Payload bank and "
                  "empty output are unspecified. Physical pointer skew and payload settling are "
                  "environment timing obligations, not proved by digital monitors. Hold common "
                  "assertion long enough for both domains to observe reset; no one-sided restart. "
                  "Known-state startup checks do not universally detect repeated one-sided "
                  "synchronous resets. Liveness requires both clocks continuing and the receiver "
                  "eventually accepting.");
  }
  std::string crossingSourceComment(const ExecutableCrossing &crossing) {
    std::string path = sourcePath(crossing.operation).str();
    for (char &character : path)
      if (character == '\n' || character == '\r')
        character = ' ';
      else if (character == '{')
        character = '(';
      else if (character == '}')
        character = ')';
    return "// NODAL crossing " + path + ": ";
  }
  void crossingComment(const ExecutableCrossing &crossing, StringRef comment) {
    auto emitted = circt::sv::VerbatimOp::create(
        builder, crossing.operation->getLoc(),
        builder.getStringAttr(crossingSourceComment(crossing) + comment.str()), ValueRange{},
        builder.getArrayAttr({}));
    correlate(emitted, crossing.operation);
  }
  void crossingAssertion(const ExecutableCrossing &crossing, const CrossingControls &control,
                         Value enable, Value property, StringRef message,
                         StringRef predicate = "({{2}}) === 1'b1") {
    // Pinned firtool emits verif.clocked_assert as concurrent SVA, unsupported
    // by Icarus. A clocked immediate assertion preserves the runtime contract
    // there too. Gray skips unknown seen-sample state deterministically. Pulse,
    // handshake and FIFO skip only a known asserted reset: an unknown release chain
    // cannot hide startup checks. No initial block invents checker state.
    std::string assertion =
        crossingSourceComment(crossing) + message.str() + "\n" + "always @(" +
        (control.falling ? "negedge" : "posedge") + " {{0}}) begin\n" +
        (crossing.kind != CrossingKind::Gray ? "  if (({{1}}) !== 1'b0) begin\n"
                                             : "  if (({{1}}) === 1'b1) begin\n") +
        "    assert (" + predicate.str() +
        ") else $fatal(1, \"NODAL-CROSSING-057: " + message.str() + "\");\n  end\nend";
    auto emitted = circt::sv::VerbatimOp::create(
        builder, crossing.operation->getLoc(), builder.getStringAttr(assertion),
        ValueRange{control.assertionClock, enable, property}, builder.getArrayAttr({}));
    correlate(emitted, crossing.operation);
    emitted->setAttr("nodal.crossing_check", crossing.operation->getAttr("contract"));
  }
  LogicalResult planControlProjections() {
    if (source.getBody().front().empty())
      return success();
    Operation *use = &source.getBody().front().back();
    SmallVector<Value> selectors;
    for (Operation &op : source.getBody().front()) {
      if (!isa<ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(op) ||
          getDigitalControlBoundaryKind(op.getResult(0).getType()).empty())
        continue;
      auto projection = resolveStaticShapeProjection(op.getResult(0), use);
      if (failed(projection) || !projection->exactFixedCardinality ||
          !targetControlShapeType(projection->root.getType(), source))
        return reject(&op,
                      "physical control projection requires a fixed input shape of 1..4096 bits");
      auto root = projection->root.getDefiningOp<PortValueOp>();
      auto ref = root ? root->getAttrOfType<FlatSymbolRefAttr>("port") : FlatSymbolRefAttr();
      Operation *port = ref ? SymbolTable::lookupSymbolIn(source, ref) : nullptr;
      if (!port || text(port, "direction") != "input")
        return reject(&op, "physical control projection requires an owned input port");
      if (auto scalar = dyn_cast<ControlCapabilityType>(op.getResult(0).getType())) {
        if (!projection->literalRootFlatOffset)
          return reject(&op, "physical control projection requires a proved literal input offset");
        // A dead last projection has no later use at which to establish scope.
        // Its shape/root proof is still checked, but it needs no target value.
        if (!op.getResult(0).use_empty()) {
          auto control = resolvePhysicalControl(op.getResult(0), use, scalar.getKind());
          if (failed(control) || !control->literalOffset)
            return reject(&op,
                          "physical control projection requires a proved literal input offset");
          physicalControls.emplace_back(op.getResult(0), *control);
        }
      }
      controlProjections.insert(&op);
      llvm::append_range(selectors, op.getOperands().drop_front());
    }
    while (!selectors.empty()) {
      Value value = selectors.pop_back_val();
      Operation *op = value.getDefiningOp();
      if (!op || !isa<ConstantOp, ConstLiteralOp, ConstExprOp>(op) ||
          (!value.getType().isIndex() && !value.getType().isInteger(64)))
        return reject(source, "physical control selectors require fixed compile-time expressions");
      if (!controlSelectors.insert(op).second)
        continue;
      llvm::append_range(selectors, op->getOperands());
    }
    for (Operation *op : controlSelectors)
      for (Operation *user : op->getUsers())
        if (!controlSelectors.contains(user) && !controlProjections.contains(user))
          return reject(op, "physical control selector escapes its compile-time projection");
    return success();
  }
  Value constant(Operation *at, unsigned bits, const llvm::APInt &number) {
    return circt::hw::ConstantOp::create(
        builder, at->getLoc(),
        builder.getIntegerAttr(builder.getIntegerType(bits), number.zextOrTrunc(bits)));
  }
  Value constant(Operation *at, unsigned bits, uint64_t number) {
    return constant(at, bits, llvm::APInt(bits, number));
  }
  Value make(Operation *at, StringRef name, Type type, ValueRange operands,
             ArrayRef<NamedAttribute> attrs = {}) {
    // The pinned exporter truncates attributed variadic bitwise operations to
    // their first two operands. Retain provenance with a balanced binary tree,
    // rather than dropping source correlation or relying on optimization.
    // Enum and reset fan-in preflight already charge at least n-1 nodes.
    if (operands.size() > 2 && (name == "comb.or" || name == "comb.and" || name == "comb.xor")) {
      SmallVector<Value> level(operands.begin(), operands.end());
      while (level.size() > 1) {
        SmallVector<Value> next;
        next.reserve((level.size() + 1) / 2);
        for (unsigned index = 0; index < level.size(); index += 2)
          next.push_back(index + 1 == level.size()
                             ? level[index]
                             : make(at, name, type, {level[index], level[index + 1]}, attrs));
        level = std::move(next);
      }
      return level.front();
    }
    OperationState state(at->getLoc(), name);
    state.addOperands(operands);
    state.addTypes(type);
    state.addAttributes(attrs);
    Operation *op = builder.create(state);
    correlate(op, at);
    return op->getResult(0);
  }
  Value mux(Operation *at, Value cond, Value a, Value b) {
    return make(at, "comb.mux", a.getType(), {cond, a, b});
  }
  Value invert(Operation *at, Value value) {
    unsigned bits = cast<IntegerType>(value.getType()).getWidth();
    return make(at, "comb.xor", value.getType(),
                {value, constant(at, bits, llvm::APInt::getAllOnes(bits))});
  }
  Value resetActive(Value value, Operation *at) {
    StringRef polarity = isa<SequentialUpdateOp, ResetAssertedOp>(at)
                             ? updateSemantics(at, "reset_polarity")
                             : text(at, "reset_polarity");
    return polarity == "active_low" ? invert(at, value) : value;
  }
  Value resize(Operation *at, Value value, unsigned bits, bool sign) {
    unsigned old = cast<IntegerType>(value.getType()).getWidth();
    if (old == bits)
      return value;
    if (old > bits)
      return circt::comb::ExtractOp::create(builder, at->getLoc(), value, 0, bits);
    Value prefix;
    if (sign) {
      Value high = circt::comb::ExtractOp::create(builder, at->getLoc(), value, old - 1, 1);
      prefix = circt::comb::ReplicateOp::create(builder, at->getLoc(), high, bits - old);
    } else
      prefix = constant(at, bits - old, 0);
    return circt::comb::ConcatOp::create(builder, at->getLoc(), prefix, value);
  }
  Value compare(Operation *at, StringRef comparison, Value a, Value b, bool sign) {
    using P = circt::comb::ICmpPredicate;
    P p = llvm::StringSwitch<P>(comparison)
              .Cases({"digital_eq", "enum_eq"}, P::eq)
              .Case("digital_ne", P::ne)
              .Case("digital_lt", sign ? P::slt : P::ult)
              .Case("digital_le", sign ? P::sle : P::ule)
              .Case("digital_gt", sign ? P::sgt : P::ugt)
              .Case("digital_ge", sign ? P::sge : P::uge)
              .Default(P::eq);
    return make(at, "comb.icmp", builder.getI1Type(), {a, b},
                {builder.getNamedAttr(
                    "predicate", circt::comb::ICmpPredicateAttr::get(builder.getContext(), p))});
  }
  IntegerAttr enumCode(Operation *op, StringRef id) {
    return enumCodes.find(op->getAttr("enum_ref"))->second.byId.lookup(id);
  }
  Value lowerValue(Operation *op, ArrayRef<Value> inputs) {
    if (isa<WaiverOp>(op)) {
      // Escape the serialized authored record byte-for-byte into one ASCII line.
      // No newline, Unicode separator, comment terminator or Verbatim placeholder
      // can become target syntax. The original typed record remains attached.
      std::string authored;
      llvm::raw_string_ostream stream(authored);
      stream << op->getAttrDictionary();
      std::string escaped;
      constexpr char hex[] = "0123456789ABCDEF";
      for (unsigned char c : authored) {
        if (c < 32 || c >= 127 || c == '{' || c == '}' || c == '\\' || c == '/') {
          escaped += "\\x";
          escaped += hex[c >> 4];
          escaped += hex[c & 15];
        } else
          escaped += c;
      }
      auto comment = circt::sv::VerbatimOp::create(
          builder, op->getLoc(),
          builder.getStringAttr("// NODAL stateless waiver: " + escaped +
                                "; user physical responsibility; no synchronization, delivery or "
                                "physical certification"),
          ValueRange{}, builder.getArrayAttr({}));
      correlate(comment, op);
      comment->setAttr("nodal.waiver", op->getAttrDictionary());
      StringRef occurrence = sourcePath(source);
      if (occurrence.empty())
        occurrence = text(source, "sym_name");
      comment->setAttr(
          "nodal.waiver_identity",
          builder.getDictionaryAttr(
              {builder.getNamedAttr("occurrence", builder.getStringAttr(occurrence)),
               builder.getNamedAttr("semantic_path", builder.getStringAttr(sourcePath(op))),
               builder.getNamedAttr("id", op->getAttr("id"))}));
      return inputs[0];
    }
    StringRef operationCode = text(op, "operator_name");
    if (operationCode == "digital_struct_get" || operationCode == "digital_struct_make") {
      Type resultType = targetValueType(op->getResult(0).getType());
      Type aggregateType = operationCode == "digital_struct_get" ? op->getOperand(0).getType()
                                                                 : op->getResult(0).getType();
      auto view = boundaryViews.find(aggregateType);
      if (view == boundaryViews.end())
        return {};
      SmallVector<Value> leaves;
      auto extract = [&](Value value, StringRef name) -> Value {
        auto type = dyn_cast<circt::hw::StructType>(value.getType());
        if (!type)
          return {};
        auto [found, inserted] = targetFieldIndices.try_emplace(type);
        if (inserted)
          for (auto [index, field] : llvm::enumerate(type.getElements()))
            found->second.try_emplace(field.name.getValue(), index);
        auto field = found->second.find(name);
        if (field == found->second.end())
          return {};
        unsigned index = field->second;
        return make(op, "hw.struct_extract", type.getElements()[index].type, {value},
                    {builder.getNamedAttr("fieldIndex", builder.getI32IntegerAttr(index))});
      };
      if (operationCode == "digital_struct_get") {
        StringRef field = text(op, "field_name");
        for (const auto &leaf : view->second.leaves)
          if (!leaf.path.empty() && leaf.path.front().name.getValue() == field) {
            Value value = extract(inputs.front(), leafName(leaf));
            if (!value)
              return {};
            leaves.push_back(value);
          }
      } else {
        auto structure = cast<StructType>(aggregateType);
        llvm::DenseMap<StringAttr, unsigned> operands;
        for (auto [index, raw] : llvm::enumerate(structure.getFields()))
          operands.try_emplace(cast<DictionaryAttr>(raw).getAs<StringAttr>("name"), index);
        for (const auto &leaf : view->second.leaves) {
          auto field = operands.find(leaf.path.front().name);
          if (field == operands.end())
            return {};
          Value value = inputs[field->second];
          if (leaf.path.size() > 1)
            value = extract(value, leafName(leaf, 1));
          if (!value)
            return {};
          leaves.push_back(value);
        }
      }
      if (leaves.empty() || (!isa<circt::hw::StructType>(resultType) && leaves.size() != 1))
        return {};
      return joinBoundary(op, resultType, leaves);
    }
    auto scalarResult = dyn_cast_or_null<IntegerType>(targetValueType(op->getResult(0).getType()));
    unsigned bits = scalarResult ? scalarResult.getWidth() : 0;
    if (isa<ConstantOp, ConstLiteralOp>(op)) {
      auto attr = op->getAttrOfType<IntegerAttr>("value");
      bool negative =
          !cast<IntegerType>(attr.getType()).isUnsigned() && attr.getValue().isNegative();
      return constant(op, bits,
                      negative ? attr.getValue().sextOrTrunc(bits)
                               : attr.getValue().zextOrTrunc(bits));
    }
    if (isa<SequentialSelectOp>(op))
      return mux(op, inputs[0], inputs[1], inputs[2]);
    if (named(op, "nodal.reset_asserted")) {
      if (!inputs.empty())
        return resetActive(inputs[0], op);
      auto facts = bindingFacts(op->getAttr("domain"));
      if (failed(facts))
        return {};
      if (!facts->reset)
        return constant(op, 1, 0);
      Value reset = domainInputs.lookup(op->getAttr("domain")).second;
      return reset ? resetActive(reset, op) : Value();
    }
    StringRef code = text(op, "operator_name");
    if (isa<EnumExprOp>(op)) {
      auto ids = op->getAttrOfType<ArrayAttr>("case_ids");
      if (code == "enum_literal")
        return constant(op, bits, enumCode(op, cast<StringAttr>(ids[0]).getValue()).getValue());
      if (code == "enum_as_bits")
        return inputs[0];
      if (code == "enum_eq")
        return compare(op, code, inputs[0], inputs[1], false);
      SmallVector<IntegerAttr> codes;
      if (code == "enum_is_valid" || code == "enum_decode")
        codes = enumCodes.find(op->getAttr("enum_ref"))->second.inDeclarationOrder;
      else
        for (Attribute id : ids)
          codes.push_back(enumCode(op, cast<StringAttr>(id).getValue()));
      SmallVector<Value> tests;
      for (IntegerAttr value : codes) {
        Value number = constant(op, width(op->getOperand(0).getType()), value.getValue());
        tests.push_back(compare(op, "enum_eq", inputs[0], number, false));
      }
      if (tests.empty())
        return constant(op, 1, 0);
      Value valid =
          tests.size() == 1 ? tests.front() : make(op, "comb.or", builder.getI1Type(), tests);
      if (code == "enum_decode") {
        Value fallback =
            constant(op, bits, enumCode(op, cast<StringAttr>(ids[0]).getValue()).getValue());
        return mux(op, valid, inputs[0], fallback);
      }
      return valid;
    }
    if (code == "digital_extract") {
      auto bound = capturedValue(op->getOperand(1)).getDefiningOp();
      auto lower = bound ? bound->getAttrOfType<IntegerAttr>("value") : IntegerAttr();
      if (!lower || lower.getInt() < 0 || lower.getInt() > maximumWidth) {
        (void)reject(op, "target bit extraction requires an exact literal lower bound");
        return {};
      }
      return circt::comb::ExtractOp::create(builder, op->getLoc(), inputs[0], lower.getInt(), bits);
    }
    if (code == "digital_alias")
      return inputs[0];
    if (code == "digital_select")
      return mux(op, inputs[0], inputs[1], inputs[2]);
    auto parametric = dyn_cast<ParametricIntegerType>(op->getOperand(0).getType());
    bool sign = isa<SIntType>(op->getOperand(0).getType()) ||
                (parametric && parametric.getKind() == "sint");
    if (code == "digital_not" || code == "bool_not")
      return invert(op, inputs[0]);
    if (code == "digital_concat")
      return circt::comb::ConcatOp::create(builder, op->getLoc(), inputs[0], inputs[1]);
    if (inputs.size() == 1) {
      Value value = resize(op, inputs[0], bits, sign);
      if (code == "digital_neg")
        return make(op, "comb.sub", value.getType(), {constant(op, bits, 0), value});
      return value;
    }
    bool comparison = code == "digital_eq" || code == "digital_ne" || code == "digital_lt" ||
                      code == "digital_le" || code == "digital_gt" || code == "digital_ge";
    unsigned common = comparison ? std::max(cast<IntegerType>(inputs[0].getType()).getWidth(),
                                            cast<IntegerType>(inputs[1].getType()).getWidth())
                                 : bits;
    Value a = resize(op, inputs[0], common, sign), b = resize(op, inputs[1], common, sign);
    if (comparison)
      return compare(op, code, a, b, sign);
    if (code == "digital_min" || code == "digital_max")
      return mux(op, compare(op, code == "digital_min" ? "digital_lt" : "digital_gt", a, b, sign),
                 a, b);
    StringRef target = llvm::StringSwitch<StringRef>(code)
                           .Cases({"digital_and", "bool_and"}, "comb.and")
                           .Cases({"digital_or", "bool_or"}, "comb.or")
                           .Case("digital_xor", "comb.xor")
                           .Case("digital_add", "comb.add")
                           .Case("digital_sub", "comb.sub")
                           .Case("digital_mul", "comb.mul")
                           .Default("");
    return make(op, target, builder.getIntegerType(bits), {a, b});
  }
  nodal::ModuleOp source;
  OpBuilder &builder;
  uint64_t &cost;
  DomainModel domains;
  HierarchyTargetPlan *hierarchy = nullptr;
  ModuleVariantPlan *variant = nullptr;
  ArrayAttr targetParameters;
  bool namedBoundaryABI = false;
  bool preserveConstantLoops = false;
  llvm::DenseSet<Operation *> staticOperations;
  llvm::DenseSet<Operation *> symbolicParameters;
  llvm::DenseMap<const InstantiatedIntegerNode *, TypedAttr> parameterExpressions;
  SmallVector<std::shared_ptr<const InstantiatedIntegerNode>> parameterExpressionRoots;
  llvm::DenseMap<Operation *, ArrayAttr> targetInstanceParameters;
  llvm::DenseMap<Attribute, std::pair<Value, Value>> domainInputs;
  SmallVector<Operation *> instances;
  llvm::DenseMap<InstanceLogicalPortKey, SmallVector<Value>> instancePortValues;
  SmallVector<LogicalPortPlan> logicalPorts;
  SmallVector<DigitalInterfaceEndpoint> localEndpoints;
  ArrayRef<DigitalInterfaceEndpoint> endpoints;
  llvm::DenseMap<Operation *, unsigned> interfaceEndpoints;
  llvm::DenseMap<LogicalPortKey, SmallVector<Value>> interfacePortValues;
  llvm::DenseSet<Value> interfaceValues;
  llvm::DenseMap<std::pair<Operation *, Attribute>, Operation *> instanceBindings;
  SmallVector<ExecutableCrossing, 0> crossings;
  SmallVector<ExecutableEventProcess, 0> events;
  llvm::DenseMap<Operation *, Value> eventUpdates;
  llvm::DenseMap<Operation *, StringAttr> eventNames;
  SmallVector<Operation *> resets, clocks, relationships;
  llvm::DenseMap<Operation *, llvm::StringMap<StringAttr>> clockNames;
  llvm::DenseMap<Operation *, SmallVector<StringAttr>> resetNames;
  llvm::DenseMap<Operation *, SmallVector<StringAttr>> crossingNames;
  SmallVector<Operation *> ports, registers;
  llvm::DenseMap<Operation *, Type> portTypes;
  llvm::DenseMap<Type, Type> packedTypes;
  llvm::DenseMap<Type, HierarchyBoundaryType> boundaryViews;
  llvm::DenseMap<Type, llvm::StringMap<unsigned>> targetFieldIndices;
  llvm::DenseMap<Type, DictionaryAttr> packedLayouts;
  llvm::DenseSet<Operation *> controlProjections, controlSelectors;
  SmallVector<std::pair<Value, PhysicalControl>> physicalControls;
  llvm::DenseMap<Operation *, SmallVector<Value>> portValues;
  llvm::DenseMap<Value, Operation *> updates;
  llvm::DenseMap<Value, Value> aliases, values;
  llvm::StringMap<DictionaryAttr> materializations;
  struct EnumCodes {
    llvm::StringMap<IntegerAttr> byId;
    SmallVector<IntegerAttr> inDeclarationOrder;
  };
  llvm::DenseMap<Attribute, EnumCodes> enumCodes;
};

class SequentialToCIRCTPass final
    : public PassWrapper<SequentialToCIRCTPass, OperationPass<mlir::ModuleOp>> {
public:
  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(SequentialToCIRCTPass)
  SequentialToCIRCTPass() = default;
  SequentialToCIRCTPass(const SequentialToCIRCTPass &other) : PassWrapper(other) {}
  Option<std::string> boundaryABI{
      *this, "boundary-abi", llvm::cl::desc("External data boundary ABI: legacy or named-fields"),
      llvm::cl::init("legacy")};
  Option<bool> preserveConstantLoops{
      *this, "preserve-constant-loops",
      llvm::cl::desc("Preserve eligible concrete generated families as actual HDL loops"),
      llvm::cl::init(false)};
  StringRef getArgument() const final { return "nodal-sequential-to-circt"; }
  StringRef getDescription() const final {
    return "Transactionally lower verified finite scalar register/FSM/CDC dataflow to "
           "HW/Comb/Seq/Verif";
  }
  void getDependentDialects(DialectRegistry &registry) const final {
    registry.insert<circt::hw::HWDialect, circt::comb::CombDialect, circt::seq::SeqDialect,
                    circt::verif::VerifDialect, circt::sv::SVDialect>();
  }
  void runOnOperation() final {
    auto input = getOperation();
    if (boundaryABI != "legacy" && boundaryABI != "named-fields") {
      (void)reject(input, "boundary-abi must be legacy or named-fields");
      signalPassFailure();
      return;
    }
    unsigned count = 0;
    auto walk = input.walk([&](Operation *op) {
      if (++count > maximumAuthoredOperations) {
        (void)reject(op, "authored whole-module operation budget exceeds 65536");
        return WalkResult::interrupt();
      }
      return WalkResult::advance();
    });
    if (walk.wasInterrupted() || failed(verify(input)) || failed(verifyParameterModel(input))) {
      signalPassFailure();
      return;
    }
    bool hierarchy = false;
    input.walk([&](Operation *op) {
      hierarchy |=
          isa<InstanceOp>(op) || (isa<nodal::ModuleOp>(op) && hasLocalHierarchyObligations(op));
    });
    OwningOpRef<mlir::ModuleOp> candidate = cast<mlir::ModuleOp>(input->clone());
    SmallVector<nodal::ModuleOp> owners;
    SmallVector<Operation *> enums;
    for (Operation &op : *candidate->getBody()) {
      if (auto owner = dyn_cast<nodal::ModuleOp>(op))
        owners.push_back(owner);
      else if (isa<EnumOp>(op))
        enums.push_back(&op);
      else {
        (void)reject(
            &op,
            "whole-module profile admits only Nodal modules and checked enum ABI declarations");
        signalPassFailure();
        return;
      }
    }
    for (auto owner : owners) {
      // The original verifier checked any attached receipt. Normalization changes
      // actual SSA uses, so recompute canonical target requirements on the private
      // candidate instead of accepting the old report as a semantic proof.
      owner->removeAttr(reportKey);
      if (failed(normalizeSequentialCandidate(owner))) {
        signalPassFailure();
        return;
      }
    }
    if (failed(verify(*candidate))) {
      signalPassFailure();
      return;
    }
    OpBuilder builder(input.getContext());
    SmallVector<std::unique_ptr<ModuleLowering>> lowerings;
    std::optional<HierarchyTargetPlan> hierarchyPlan;
    if (hierarchy) {
      hierarchyPlan.emplace();
      if (failed(hierarchyPlan->build(*candidate))) {
        signalPassFailure();
        return;
      }
    }
    uint64_t cost = 2 * (hierarchyPlan ? hierarchyPlan->variants.size() : owners.size());
    auto preflight = [&](nodal::ModuleOp owner, ModuleVariantPlan *variant) {
      auto lowering = std::make_unique<ModuleLowering>(
          owner, builder, cost, hierarchyPlan ? &*hierarchyPlan : nullptr, variant,
          boundaryABI == "named-fields", preserveConstantLoops);
      if (failed(lowering->preflight())) {
        return failure();
      }
      lowerings.push_back(std::move(lowering));
      return success();
    };
    if (hierarchyPlan) {
      for (auto &variant : hierarchyPlan->variants)
        if (failed(preflight(variant.source, &variant))) {
          signalPassFailure();
          return;
        }
    } else {
      for (auto owner : owners)
        if (failed(preflight(owner, nullptr))) {
          signalPassFailure();
          return;
        }
    }
    SmallVector<Attribute> abi;
    for (Operation *definition : enums) {
      auto underlying = definition->getAttrOfType<TypeAttr>("underlying_type");
      if (!underlying || !targetType(underlying.getValue())) {
        (void)reject(definition, "enum ABI exceeds the fixed scalar width profile");
        signalPassFailure();
        return;
      }
      auto metadata = definition->getAttrOfType<DictionaryAttr>("metadata");
      if (!metadata || !metadata.getAs<IntegerAttr>("abi_version")) {
        (void)reject(definition, "legacy enum descriptors are not a checked executable ABI");
        signalPassFailure();
        return;
      }
      NamedAttrList record;
      record.set("symbol", definition->getAttr("sym_name"));
      record.set("metadata", metadata);
      SmallVector<Attribute> cases;
      for (Operation &entry : definition->getRegion(0).front()) {
        NamedAttrList item;
        item.set("name", entry.getAttr("sym_name"));
        item.set("code", entry.getAttr("value"));
        item.set("metadata", entry.getAttr("metadata"));
        cases.push_back(builder.getDictionaryAttr(item));
      }
      record.set("cases", builder.getArrayAttr(cases));
      abi.push_back(builder.getDictionaryAttr(record));
    }
    for (auto &lowering : lowerings)
      if (failed(lowering->lower())) {
        signalPassFailure();
        return;
      }
    // All target users now reference emitted HW definitions. Unselected
    // well-formed templates were verified but are not executable root evidence.
    for (auto owner : owners)
      owner.erase();
    for (Operation *definition : enums)
      definition->erase();
    if (!abi.empty())
      candidate->getOperation()->setAttr("nodal.enum_abi", builder.getArrayAttr(abi));
    if (failed(verify(*candidate))) {
      signalPassFailure();
      return;
    }
    candidate->getOperation()->setAttr("nodal.boundary_abi", builder.getStringAttr(boundaryABI));
    candidate->getOperation()->setAttr("nodal.preserve_constant_loops",
                                       builder.getBoolAttr(preserveConstantLoops));
    input->setAttrs(candidate->getOperation()->getAttrs());
    input.getBodyRegion().takeBody(candidate->getBodyRegion());
  }
};
} // namespace
std::unique_ptr<Pass> createSequentialToCIRCTPass() {
  return std::make_unique<SequentialToCIRCTPass>();
}
void registerSequentialToCIRCTPass() {
  static PassRegistration<SequentialToCIRCTPass> registration;
}
} // namespace nodal
