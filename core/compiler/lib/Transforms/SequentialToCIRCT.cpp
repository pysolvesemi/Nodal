#include "circt/Dialect/Comb/CombDialect.h"
#include "circt/Dialect/Comb/CombOps.h"
#include "circt/Dialect/HW/HWAttributes.h"
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
#include "nodal/Dialect/Nodal/DigitalValueModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/EventModel.h"
#include "nodal/Dialect/Nodal/HierarchyBoundaryModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/PhysicalControlModel.h"
#include "nodal/Dialect/Nodal/ResetModel.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"
#include "nodal/Dialect/Nodal/StaticPredicateModel.h"
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
bool resolvedScalar(Type type) {
  if (isa<BitsType, UIntType, SIntType>(type))
    return true;
  auto parametric = dyn_cast<ParametricIntegerType>(type);
  return parametric &&
         llvm::is_contained(ArrayRef<StringRef>{"bits", "uint", "sint"}, parametric.getKind());
}
// One physical carrier is a vector of independent electrical lanes. Geometry
// never replaces endpoint/component/occurrence identity. Re-prove exact local
// axis provenance before mapping its complete data and enable value types.
struct ResolvedPhysicalGeometry {
  Type carrier, payload, data, enable;
};
Type resolvedPointwiseType(Type carrier, Type element) {
  if (auto shape = dyn_cast<nodal::ShapedType>(carrier))
    return nodal::ShapedType::get(carrier.getContext(), shape.getDimensions(), element);
  return element;
}
ResolvedType resolvedCarrierLeaf(Type type) {
  if (auto shape = dyn_cast<nodal::ShapedType>(type))
    type = shape.getElementType();
  return dyn_cast<ResolvedType>(type);
}
FailureOr<ResolvedPhysicalGeometry>
inspectResolvedPhysicalGeometry(const DigitalResolvedEndpoint &endpoint, Operation *module) {
  const auto &shape = endpoint.shape;
  auto resolved = dyn_cast_or_null<ResolvedType>(endpoint.type);
  if (!resolved || !shape.owner || shape.owner != endpoint.operation ||
      shape.owner->getParentOp() != module || !shape.dimensions ||
      shape.axes.size() != shape.dimensions.size() || shape.axes.size() > digitalTypeMaximumDepth ||
      !isa<InterfaceInstanceOp, InterfaceBoundaryOp, ResolvedNetOp, ResolvedBoundaryOp>(
          shape.owner) ||
      (!shape.axes.empty() && (!isa<InterfaceInstanceOp, InterfaceBoundaryOp>(shape.owner) ||
                               module->hasAttr("external_name"))))
    return failure();
  auto metadata = shape.owner->getAttrOfType<DictionaryAttr>("metadata");
  auto original = metadata ? metadata.getAs<ArrayAttr>("dimensions") : ArrayAttr();
  if ((original && original != shape.dimensions) || (!original && !shape.axes.empty()))
    return failure();
  std::string dimensions;
  for (auto [index, axis] : llvm::enumerate(shape.axes)) {
    auto token = dyn_cast<StringAttr>(shape.dimensions[index]);
    auto checked = token ? resolveStaticShapeDimension(module, token.getValue())
                         : FailureOr<StaticShapeDimension>(failure());
    if (failed(checked) || checked->parameter != axis.parameter ||
        checked->expression != axis.expression || checked->literal != axis.literal ||
        checked->bounds.lower != axis.bounds.lower || checked->bounds.upper != axis.bounds.upper)
      return failure();
    if (!dimensions.empty())
      dimensions += ',';
    dimensions += token.getValue();
  }
  Type carrier = resolved;
  if (!dimensions.empty())
    carrier = nodal::ShapedType::get(module->getContext(), dimensions, resolved);
  return ResolvedPhysicalGeometry{
      carrier, resolved.getElementType(), resolvedPointwiseType(carrier, resolved.getElementType()),
      resolvedPointwiseType(carrier, BitsType::get(module->getContext(), 1))};
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
  bool inout = false;
  DictionaryAttr interfaceContract;
  DictionaryAttr resolvedContract;
  DictionaryAttr generatedLayout;
  std::optional<unsigned> splitRoute;
  bool splitEnable = false;
  SmallVector<Value> references;
  SmallVector<StringAttr> pins;
};
struct LogicalPortSignature {
  LogicalPortKey key;
  bool input;
  unsigned pins;
  bool inout = false;
  Type resolvedElement;
  Type resolvedOriginal;
  std::optional<unsigned> splitRoute;
  bool splitEnable = false;
};
struct ModuleVariantPlan {
  nodal::ModuleOp source;
  unsigned occurrence = 0;
  std::string signature, name;
  SmallVector<DomainPortPlan> domains;
  llvm::DenseMap<Operation *, unsigned> children;
  SmallVector<LogicalPortSignature, 0> ports;
  Operation *target = nullptr;
};
struct InterfaceTargetFacts {
  SmallVector<DigitalInterfaceEndpoint> endpoints;
  SmallVector<DigitalInterfaceAccess> accesses;
  // Original-template query facts are independent of physical domain variants.
  bool generatedTemplatesReady = false;
  SmallVector<DigitalInterfaceBoundaryType, 4> generatedTypes;
  SmallVector<DigitalInterfaceTemplateDriver, 4> generatedDrivers;
};
struct HierarchyTargetPlan {
  std::shared_ptr<HierarchyModel> proof;
  SmallVector<ModuleVariantPlan> variants;
  SmallVector<unsigned> occurrenceVariants;
  SmallVector<DigitalResolvedHierarchyComponent> resolvedComponents;
  SmallVector<DigitalResolvedSplitBinding> resolvedSplits;
  SmallVector<SmallVector<const DigitalResolvedEndpoint *, 0>> resolvedEndpoints;
  // Borrowed endpoint facts are collected once per original definition, even
  // when material clock/reset variants emit separate physical module bodies.
  llvm::DenseMap<Operation *, std::unique_ptr<InterfaceTargetFacts>> interfaces;

  // Derived physical routes only. The successful semantic query owns unique
  // driver/component/domain proof, and the existing occurrence tree owns paths.
  struct SplitRoute {
    const DigitalResolvedSplitBinding *binding = nullptr;
    unsigned bits = 0, canonical = 0;
    Type originalType, enableType;
    bool incoming = false, outgoing = false;
    Operation *sourceChild = nullptr;
    unsigned sourceChildOccurrence = 0, sourceChildRoute = 0;
    SmallVector<std::pair<unsigned, unsigned>, 2> destinations;
    SmallVector<Operation *, 2> splits;
    std::string signature;
  };
  struct SplitOccurrencePlan {
    SmallVector<SplitRoute, 2> routes;
    SmallVector<unsigned, 2> ordered;
    llvm::DenseMap<std::pair<unsigned, Operation *>, unsigned> driverRoutes;
    llvm::DenseMap<Operation *, unsigned> splitRoutes;
    std::string signature;
  };
  SmallVector<SplitOccurrencePlan, 0> splitPlans;

  LogicalResult planSplitRoutes(mlir::ModuleOp input) {
    auto occurrences = proof->occurrences();
    splitPlans.resize(occurrences.size());
    if (resolvedSplits.empty())
      return success();
    llvm::DenseMap<Operation *, unsigned> ordinals;
    llvm::DenseSet<Operation *> indexedDefinitions;
    uint64_t work = 0;
    for (const auto &occurrence : occurrences) {
      if (!indexedDefinitions.insert(occurrence.module).second)
        continue;
      unsigned index = 0;
      for (Operation &op : occurrence.module->getRegion(0).front()) {
        if (++work > maximumGeneratedOperations)
          return reject(input, "split route operation inventory exceeds finite target budget");
        ordinals.try_emplace(&op, index++);
      }
    }
    // Index only the successful semantic component inventory. Each route uses
    // the payload declared in that exact local module, never a foreign token.
    using DriverKey = std::pair<unsigned, Operation *>;
    llvm::DenseMap<DriverKey, const DigitalResolvedHierarchyComponent *> driverComponents;
    llvm::DenseMap<std::pair<const DigitalResolvedHierarchyComponent *, unsigned>, Type>
        componentTypes;
    for (const auto &component : resolvedComponents) {
      for (const auto &driver : component.drivers) {
        if (++work > maximumGeneratedOperations ||
            !driverComponents
                 .try_emplace(DriverKey{driver.occurrence, driver.operation}, &component)
                 .second)
          return reject(input, "split component inventory exceeds exact bounded driver identity");
      }
      for (const auto &endpoint : component.endpoints) {
        auto resolved = dyn_cast<ResolvedType>(endpoint.endpoint.type);
        if (++work > maximumGeneratedOperations || !resolved ||
            endpoint.occurrence >= occurrences.size() || !resolvedScalar(resolved.getElementType()))
          return reject(input, "split component requires an exact local scalar payload");
        auto geometry = inspectResolvedPhysicalGeometry(endpoint.endpoint,
                                                        occurrences[endpoint.occurrence].module);
        if (failed(geometry))
          return reject(endpoint.endpoint.operation, "split family lost exact local geometry");
        Type type = geometry->data;
        auto [found, inserted] =
            componentTypes.try_emplace(std::make_pair(&component, endpoint.occurrence), type);
        if (!inserted && !equivalentDigitalStorageTypes(found->second, type,
                                                        occurrences[endpoint.occurrence].module))
          return reject(endpoint.endpoint.operation,
                        "split component local payload types disagree");
      }
    }
    auto route = [&](unsigned occurrence,
                     const DigitalResolvedSplitBinding &binding) -> FailureOr<unsigned> {
      if (occurrence >= occurrences.size() || !occurrences[occurrence].generatedScopes.empty() ||
          ++work > maximumGeneratedOperations)
        return failure();
      auto component = driverComponents.lookup({binding.driverOccurrence, binding.driver});
      Type localType = componentTypes.lookup({component, occurrence});
      if (!localType)
        return failure();
      auto maximum = inferDigitalBitPatternMaximumBits(localType, occurrences[occurrence].module);
      if (failed(maximum) || *maximum <= 0 || *maximum > maximumWidth)
        return failure();
      unsigned bits = *maximum;
      auto &plan = splitPlans[occurrence];
      auto found = plan.driverRoutes.try_emplace(
          std::make_pair(binding.driverOccurrence, binding.driver), plan.routes.size());
      unsigned index = found.first->second;
      if (found.second) {
        SplitRoute node;
        node.binding = &binding;
        node.bits = bits;
        node.originalType = localType;
        node.enableType = resolvedPointwiseType(localType, BitsType::get(input.getContext(), 1));
        plan.routes.push_back(std::move(node));
      } else if (plan.routes[index].bits != bits)
        return failure();
      return index;
    };
    for (const auto &binding : resolvedSplits) {
      if (binding.driverOccurrence >= occurrences.size() ||
          binding.splitOccurrence >= occurrences.size() || !binding.driver || !binding.split ||
          binding.driver->getParentOp() != occurrences[binding.driverOccurrence].module ||
          binding.split->getParentOp() != occurrences[binding.splitOccurrence].module)
        return reject(input, "split physical route requires exact direct owned operations");
      Type driverCarrier = binding.driver->getOperand(0).getType();
      auto driverNet = resolvedCarrierLeaf(driverCarrier);
      Type driverData =
          driverNet ? resolvedPointwiseType(driverCarrier, driverNet.getElementType()) : Type();
      Type driverEnable =
          resolvedPointwiseType(driverCarrier, BitsType::get(input.getContext(), 1));
      if (!driverNet || !resolvedScalar(driverNet.getElementType()) ||
          (binding.data && binding.data.getType() != driverData) || !binding.enable ||
          binding.enable.getType() != driverEnable ||
          (!binding.data &&
           (!binding.level ||
            !llvm::is_contained(ArrayRef<StringRef>{"low", "high"}, binding.level.getValue()))))
        return reject(binding.split,
                      "split physical route requires exact scalar data/enable in its actual owner");
      SmallVector<unsigned> ancestors;
      for (unsigned at = binding.driverOccurrence;; at = occurrences[at].parent) {
        if (ancestors.size() >= 128)
          return reject(binding.split, "split route ancestry exceeds target depth");
        ancestors.push_back(at);
        if (at == 0)
          break;
      }
      SmallVector<unsigned> descending;
      unsigned lca = binding.splitOccurrence;
      while (!llvm::is_contained(ancestors, lca)) {
        if (descending.size() >= 128 || lca == 0)
          return reject(binding.split, "split route has no exact shared occurrence ancestor");
        descending.push_back(lca);
        lca = occurrences[lca].parent;
      }
      auto current = route(binding.driverOccurrence, binding);
      if (failed(current))
        return reject(binding.split, "split physical routes exceed their checked bounded profile");
      unsigned at = binding.driverOccurrence;
      while (at != lca) {
        unsigned parent = occurrences[at].parent;
        auto next = route(parent, binding);
        if (failed(next))
          return reject(binding.split,
                        "split physical routes exceed their checked bounded profile");
        splitPlans[at].routes[*current].outgoing = true;
        auto &up = splitPlans[parent].routes[*next];
        Operation *instance = occurrences[at].instance;
        if (!instance || instance->getParentOp() != occurrences[parent].module || up.incoming ||
            (up.sourceChild && (up.sourceChild != instance || up.sourceChildRoute != *current)))
          return reject(binding.split, "split upward route lost its exact child edge");
        up.sourceChild = instance;
        up.sourceChildOccurrence = at;
        up.sourceChildRoute = *current;
        current = next;
        at = parent;
      }
      for (unsigned child : llvm::reverse(descending)) {
        auto next = route(child, binding);
        if (failed(next))
          return reject(binding.split,
                        "split physical routes exceed their checked bounded profile");
        auto &down = splitPlans[child].routes[*next];
        if (down.sourceChild || down.outgoing || child == binding.driverOccurrence ||
            !occurrences[child].instance ||
            occurrences[child].instance->getParentOp() != occurrences[at].module)
          return reject(binding.split, "split downward route lost its exact parent edge");
        down.incoming = true;
        auto edge = std::make_pair(child, *next);
        auto &destinations = splitPlans[at].routes[*current].destinations;
        if (!llvm::is_contained(destinations, edge))
          destinations.push_back(edge);
        at = child;
        current = next;
      }
      auto &local = splitPlans[at];
      if (!local.splitRoutes.try_emplace(binding.split, *current).second)
        return reject(binding.split, "split route has duplicate successful binding facts");
      local.routes[*current].splits.push_back(binding.split);
    }
    // Canonicalize bottom-up from local operation identity and child route ABI.
    // Absolute occurrence/driver IDs never become specialization keys or names.
    uint64_t signatureBytes = 0;
    for (unsigned cursor = occurrences.size(); cursor; --cursor) {
      unsigned occurrence = cursor - 1;
      auto &plan = splitPlans[occurrence];
      for (auto [index, node] : llvm::enumerate(plan.routes)) {
        llvm::sort(node.splits, [&](Operation *a, Operation *b) {
          return ordinals.lookup(a) < ordinals.lookup(b);
        });
        llvm::sort(node.destinations, [&](auto a, auto b) {
          auto left = std::make_pair(ordinals.lookup(occurrences[a.first].instance),
                                     splitPlans[a.first].routes[a.second].canonical);
          auto right = std::make_pair(ordinals.lookup(occurrences[b.first].instance),
                                      splitPlans[b.first].routes[b.second].canonical);
          return left < right;
        });
        llvm::raw_string_ostream out(node.signature);
        out << node.originalType << ':' << node.enableType << ':';
        if (node.incoming)
          out << 'I';
        else if (node.sourceChild)
          out << 'C' << ordinals.lookup(node.sourceChild) << '.'
              << splitPlans[node.sourceChildOccurrence].routes[node.sourceChildRoute].canonical;
        else {
          if (node.binding->driverOccurrence != occurrence)
            return reject(input, "split route would capture a foreign driver value");
          out << 'L' << ordinals.lookup(node.binding->driver);
        }
        out << (node.outgoing ? 'O' : '-') << '[';
        for (Operation *split : node.splits)
          out << 'S' << ordinals.lookup(split) << ',';
        for (auto edge : node.destinations)
          out << 'C' << ordinals.lookup(occurrences[edge.first].instance) << '.'
              << splitPlans[edge.first].routes[edge.second].canonical << ',';
        out << ']';
        plan.ordered.push_back(index);
      }
      llvm::sort(plan.ordered, [&](unsigned a, unsigned b) {
        return plan.routes[a].signature < plan.routes[b].signature;
      });
      for (auto [index, raw] : llvm::enumerate(plan.ordered)) {
        auto &node = plan.routes[raw];
        if (index && node.signature == plan.routes[plan.ordered[index - 1]].signature)
          return reject(input, "different split drivers have indistinguishable relative routes");
        node.canonical = index;
        if (node.signature.size() + 1 > maximumGeneratedOperations - signatureBytes)
          return reject(input, "split route signature exceeds finite target budget");
        signatureBytes += node.signature.size() + 1;
        plan.signature += node.signature + ';';
      }
    }
    return success();
  }

  LogicalResult build(mlir::ModuleOp input) {
    auto checked = HierarchyModel::create(input);
    if (failed(checked) || failed((*checked)->verifyBindings()))
      return failure();
    proof = *checked;
    auto occurrences = proof->occurrences();
    if (!occurrences.empty() && occurrences.front().module->hasAttr("external_name"))
      return reject(occurrences.front().module,
                    "external signature cannot be the physical executable root");
    bool hasResolved = false;
    input.walk([&](Operation *operation) {
      hasResolved |= isa<ResolvedNetOp, ResolvedBoundaryOp>(operation);
      if (isa<InterfaceMemberOp>(operation))
        if (auto type = operation->getAttrOfType<TypeAttr>("type"))
          hasResolved |= isa<ResolvedType>(type.getValue());
    });
    if (hasResolved &&
        failed(verifyHierarchyDependencies(proof, {}, &resolvedSplits, &resolvedComponents)))
      return failure();
    unsigned resolvedEndpointCount = 0;
    resolvedEndpoints.resize(occurrences.size());
    for (const auto &component : resolvedComponents)
      for (const auto &qualified : component.endpoints) {
        if (qualified.occurrence >= occurrences.size())
          return reject(input, "resolved endpoint lost its exact occurrence");
        if (!occurrences[qualified.occurrence].generatedScopes.empty())
          return reject(
              qualified.endpoint.operation,
              "generated resolved endpoint requires exact selected-lane physical transport");
        if (++resolvedEndpointCount > maximumGeneratedOperations)
          return reject(input, "resolved physical endpoint plan exceeds finite target budget");
        resolvedEndpoints[qualified.occurrence].push_back(&qualified.endpoint);
      }
    if (failed(planSplitRoutes(input)))
      return failure();
    occurrenceVariants.resize(occurrences.size());
    llvm::DenseMap<Operation *, llvm::StringMap<unsigned>> variantLookup;
    llvm::DenseMap<Operation *, SmallVector<unsigned>> variantsBySource;
    // Index the existing occurrence inventory once. Generated entries name the
    // original authored template, never an enumerated lane or cloned definition.
    SmallVector<SmallVector<unsigned>> children(occurrences.size());
    for (unsigned child = 1; child < occurrences.size(); ++child)
      children[occurrences[child].parent].push_back(child);
    using ActualBindings = llvm::DenseMap<std::pair<Operation *, Attribute>, Operation *>;
    llvm::DenseMap<Operation *, ActualBindings> definitionBindings;
    uint64_t bindingWork = 0;
    for (const auto &context : occurrences) {
      auto entry = definitionBindings.try_emplace(context.module);
      if (!entry.second)
        continue;
      auto inventory = context.module->walk<WalkOrder::PreOrder>([&](Operation *operation) {
        if (operation != context.module && isa<nodal::ModuleOp>(operation))
          return WalkResult::skip();
        if (++bindingWork > maximumGeneratedOperations) {
          (void)reject(operation, "child binding inventory exceeds finite target budget");
          return WalkResult::interrupt();
        }
        if (isa<InstanceDomainBindingOp>(operation)) {
          auto instance = resolveHierarchyInstance(operation, operation->getAttr("instance"));
          if (failed(instance))
            return WalkResult::interrupt();
          entry.first->second.try_emplace(
              std::make_pair(instance->instance, operation->getAttr("requirement")), operation);
        }
        return WalkResult::advance();
      });
      if (inventory.wasInterrupted())
        return failure();
    }
    // Children precede parents, including Generate-owned original instances.
    for (unsigned cursor = occurrences.size(); cursor; --cursor) {
      unsigned index = cursor - 1;
      const auto &context = occurrences[index];
      ModuleVariantPlan candidate;
      candidate.source = cast<nodal::ModuleOp>(context.module);
      candidate.occurrence = index;
      DomainModel local(context.module);
      llvm::DenseMap<Attribute, std::pair<bool, bool>> demands;
      const auto &actualBindings = definitionBindings.find(context.module)->second;
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
        }
      }
      for (unsigned child : children[index]) {
        Operation *instance = occurrences[child].instance;
        unsigned childVariant = occurrenceVariants[child];
        candidate.children.try_emplace(instance, childVariant);
        for (const auto &port : variants[childVariant].domains) {
          Operation *binding = actualBindings.lookup({instance, port.requirement});
          if (!binding)
            return reject(instance, "inferred child domain port lacks its exact actual binding");
          demand(binding->getAttr("actual"), port.clock, port.reset);
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
      if (!resolvedSplits.empty()) {
        candidate.signature += "|split:" + splitPlans[index].signature;
        // A parent implementation includes the actual relative child ABI.
        // Do not merge equal local domains with differing sideband children.
        for (Operation &op : context.module->getRegion(0).front())
          if (auto child = candidate.children.find(&op); child != candidate.children.end())
            candidate.signature +=
                "|child:" + text(&op, "sym_name").str() + ":" + std::to_string(child->second);
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
    for (Operation &op : input.getBody()->getOperations())
      if (auto external = op.getAttrOfType<StringAttr>("external_name"))
        moduleNames.insert(external.getValue());
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
        failed(planFixedShapeSelections()) || failed(planParameters()) || failed(planResolved()) ||
        failed(planInterfaces()) || failed(planGeneratedFamilies()))
      return failure();
    SmallVector<std::pair<Value, Operation *>> seeds;
    for (Operation &op : source.getBody().front()) {
      StringRef name = op.getName().getStringRef();
      if (staticOperations.contains(&op))
        continue;
      if (op.getNumRegions() || op.getNumSuccessors())
        return reject(&op, "target profile requires flat normalized scalar operations");
      bool structural = controlProjections.contains(&op) || controlSelectors.contains(&op) ||
                        generatedMembers.count(&op) || selectedInstanceReads.count(&op) ||
                        fixedShapeSelections.count(&op) || resolvedOperations.contains(&op);
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
          !resolvedOperations.contains(&op) &&
          (llvm::any_of(op.getOperandTypes(),
                        [&](Type type) {
                          return isa_and_nonnull<circt::hw::IntType, circt::hw::StructType>(
                              targetValueType(type));
                        }) ||
           llvm::any_of(op.getResultTypes(), [&](Type type) {
             return isa_and_nonnull<circt::hw::IntType, circt::hw::StructType>(
                 targetValueType(type));
           }));
      bool fixedNamedCrossing = false;
      if (namedBoundaryABI && isa<CrossingOp, AsyncFifoOp>(op) &&
          isa_and_nonnull<circt::hw::StructType>(targetValueType(op.getOperand(0).getType()))) {
        if (failed(planFixedBoundaryAdapter(op.getOperand(0).getType(), &op)))
          return failure();
        fixedNamedCrossing = true;
      }
      if (symbolic && !structural && !fixedNamedCrossing && !resolvedOperations.contains(&op) &&
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
      if (resolvedOperations.contains(&op)) {
        expansion = 4;
        if (isa<NetSplitOp>(op) &&
            (!hierarchy || !variant ||
             !hierarchy->splitPlans[variant->occurrence].splitRoutes.count(&op)))
          return reject(&op, "resolved split lost its exact successful driver-sideband proof");
        if (isa<NetDriverOp>(op))
          staticOperations.insert(&op); // Capability identity, never a runtime value.
        if (isa<NetDriveOp, NetDriveLevelOp, NetReleaseOp>(op) ||
            (isa<InterfaceDriveOp>(op) && isa<ResolvedType>(op.getOperand(0).getType())))
          resolvedEffects.push_back(&op);
      } else if (structural) {
        // Shape/index expressions are compile-time proof, not hardware values.
        expansion = controlProjections.contains(&op) ? 2 : fixedShapeSelections.count(&op) ? 1 : 0;
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
        if (boundary->selection) {
          if (!selectedInstanceReads.count(&op))
            return reject(&op, "selected generated instance port lacks its exact carrier plan");
          continue; // A structural read, not another physical child port.
        }
        instancePortValues[{boundary->instance, {boundary->port, Attribute()}}].push_back(
            op.getResult(0));
        if (auto type = dyn_cast<circt::hw::StructType>(targetValueType(op.getResult(0).getType())))
          expansion += 2 * type.getElements().size();
        if (text(&op, "direction") == "input")
          seeds.emplace_back(op.getResult(0), &op);
      } else if (isa<MemberAccessOp>(op)) {
        if (selectedInstanceReads.count(&op))
          continue; // A selected child member is a structural read, never a new physical pin.
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
        if (fixedNamedCrossing) {
          const auto &adapter = fixedBoundaryAdapters.find(op.getOperand(0).getType())->second;
          // Pack/unpack scalar extracts, binary concatenations, struct leaves
          // and correlated identity, charged per actual crossing consumer.
          expansion += 4 * adapter.segments.size() + 2 * adapter.leafBits.size() + 2;
        }
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
    if (failed(planLogicalPorts()) || failed(planSplitPorts()))
      return failure();
    for (const auto &port : logicalPorts)
      if (!port.input && !port.splitRoute && port.references.empty())
        return reject(port.key.first, "output has no actual value/driver; no output is invented");
    for (Operation *instance : instances) {
      const auto &child = hierarchy->variants[variant->children.lookup(instance)];
      for (const auto &port : child.ports)
        if (port.input && !port.inout && !port.splitRoute &&
            !instancePortValues.count({instance, port.key}))
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
      if (auto pin = seeds[i].first.getDefiningOp<PortValueOp>()) {
        Operation *port =
            SymbolTable::lookupSymbolIn(source, pin->getAttrOfType<FlatSymbolRefAttr>("port"));
        auto guarded = generatedGuardOutputs.find(port);
        if (guarded != generatedGuardOutputs.end()) {
          // Coverage/exclusivity belong to this independently verified graph.
          // The target only checks its physical plan names every exact edge.
          if (root.dependencies.size() != guarded->second.size())
            return reject(port, "generated guarded output lost its canonical write partition");
          for (unsigned index : root.dependencies) {
            const auto &write = graph->nodes[index];
            if (write.value || !llvm::is_contained(guarded->second, write.use) ||
                write.dependencies.size() != 1 ||
                capturedValue(graph->nodes[write.dependencies.front()].value) !=
                    capturedValue(write.use->getOperand(1)))
              return reject(port,
                            "generated guarded output requires exact whole-value write edges");
          }
          continue;
        }
      }
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
      if (!port.interfaceContract && !port.splitRoute &&
          !targetNames.insert(port.name.getValue()).second)
        return reject(port.key.first, "authored target port names collide");
    for (auto &port : logicalPorts)
      if (port.interfaceContract || port.splitRoute) {
        std::string base = port.name.getValue().str(), name = base;
        unsigned collision = 0;
        while (!targetNames.insert(name).second)
          name = base + "_" + std::to_string(++collision);
        port.name = builder.getStringAttr(name);
      }
    for (const auto &port : logicalPorts)
      if (port.input && !port.inout && port.interfaceContract && !port.references.empty() &&
          aliases.count(port.references.front()))
        return reject(port.key.first, "ordinary Interface input pin cannot have a local driver");
    for (const auto &port : logicalPorts)
      if (!port.input && !port.splitRoute && !aliases.count(port.references.front()) &&
          !eventUpdates.count(port.key.first) && !generatedOutputPorts.count(port.key.first) &&
          !generatedGuardOutputs.count(port.key.first))
        return reject(port.key.first, "output has no canonical driver; no output is invented");
    for (Operation *instance : instances) {
      const auto &child = hierarchy->variants[variant->children.lookup(instance)];
      for (const auto &port : child.ports)
        if (port.input && !port.inout && !port.splitRoute &&
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
        variant->ports.push_back(
            {port.key, port.input, unsigned(port.pins.size()), port.inout,
             port.inout ? cast<circt::hw::InOutType>(port.targetType).getElementType() : Type(),
             port.inout
                 ? resolvedPointwiseType(port.originalType,
                                         resolvedCarrierLeaf(port.originalType).getElementType())
                 : Type(),
             port.splitRoute ? std::optional<unsigned>(hierarchy->splitPlans[variant->occurrence]
                                                           .routes[*port.splitRoute]
                                                           .canonical)
                             : std::nullopt,
             port.splitEnable});
    auto reserveResolvedName = [&](std::string base) {
      std::string name = base;
      for (unsigned n = 0; !targetNames.insert(name).second;)
        name = base + "_" + std::to_string(++n);
      return builder.getStringAttr(name);
    };
    for (auto [index, component] : llvm::enumerate(resolvedLocalComponents)) {
      std::string base = "__nodal_resolved_" + std::to_string(index);
      component.physicalName = reserveResolvedName(base);
      for (unsigned alias = 1; alias < component.localPins.size(); ++alias)
        component.aliasNames.push_back(
            reserveResolvedName(base + "_alias_" + std::to_string(alias)));
    }
    for (Operation *instance : instances) {
      const auto &child = hierarchy->variants[variant->children.lookup(instance)];
      for (const auto &port : child.ports) {
        if (!port.inout)
          continue;
        InstanceLogicalPortKey key{instance, port.key};
        if (projectedResolvedPorts.contains(key))
          continue;
        auto view = inspectHierarchyBoundaryType(port.resolvedOriginal, child.source, instance);
        if (failed(view) || view->leaves.size() != 1 || !view->leaves.front().path.empty() ||
            !view->leaves.front().dimensions.empty() ||
            view->leaves.front().width.bounds.lower <= 0 ||
            view->leaves.front().width.bounds.upper > maximumWidth)
          return reject(instance, "unconnected resolved child pin lost exact instance width proof");
        auto &leaf = view->leaves.front();
        Type type = leafVectorType(leaf);
        if (!type || uint64_t(leaf.width.bounds.upper) + 3 > maximumGeneratedOperations - cost)
          return reject(instance,
                        "unconnected resolved child pin requires exact bounded scalar context");
        cost += leaf.width.bounds.upper + 3;
        auto name = reserveResolvedName("__nodal_unconnected_resolved_" +
                                        std::to_string(unconnectedResolvedPorts.size()));
        unconnectedResolvedPorts.push_back({key, type, name});
      }
    }
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
    for (auto &scope : generatedGuardScopes)
      if (failed(lowerGeneratedGuardHelper(*scope)))
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
    if (source->hasAttr("external_name")) {
      auto signature = inspectExternalModuleSignature(source);
      if (failed(signature) || !variant)
        return reject(source, "external target requires exact checked declaration and occurrence");
      auto external = circt::hw::HWModuleExternOp::create(
          builder, source.getLoc(), builder.getStringAttr(variant->name),
          circt::hw::ModulePortInfo(targetPorts), signature->externalName.getValue(),
          targetParameters);
      correlate(external, source);
      external->setAttr("nodal.definition", source->getAttr("sym_name"));
      variant->target = external;
      return success();
    }
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
      if (port.interfaceContract || port.resolvedContract ||
          isa<circt::hw::StructType>(port.targetType)) {
        SmallVector<Attribute> pins(port.pins.begin(), port.pins.end());
        NamedAttrList entry;
        entry.set("logical_port", port.name);
        entry.set("direction", builder.getStringAttr(port.inout   ? "inout"
                                                     : port.input ? "input"
                                                                  : "output"));
        entry.set("physical_pins", builder.getArrayAttr(pins));
        if (!port.inout && aggregatePayload(port.originalType))
          entry.set("layout", packedLayout(port.originalType));
        if (port.interfaceContract)
          entry.set("interface", port.interfaceContract);
        if (port.resolvedContract)
          entry.set("resolved", port.resolvedContract);
        boundaryMap.push_back(builder.getDictionaryAttr(entry));
      }
    if (!boundaryMap.empty())
      target->setAttr("nodal.boundary_map", builder.getArrayAttr(boundaryMap));
    correlate(target, source);
    Block *body = target.getBodyBlock();
    builder.setInsertionPoint(body->getTerminator());
    for (const auto &port : logicalPorts) {
      if (port.inout || !aggregatePayload(port.originalType))
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
    SmallVector<std::pair<unsigned, std::pair<circt::Backedge, circt::Backedge>>> splitEdges;
    if (hierarchy && variant) {
      const auto &plan = hierarchy->splitPlans[variant->occurrence];
      splitValues.resize(plan.routes.size());
      for (auto [index, route] : llvm::enumerate(plan.routes)) {
        if (route.incoming)
          continue;
        auto data = backedges.get(splitTypes[index], source.getLoc());
        auto enable = backedges.get(splitEnableTypes[index], source.getLoc());
        splitValues[index] = {data, enable};
        splitEdges.push_back({unsigned(index), {data, enable}});
      }
    }
    inputIndex = 0;
    for (const auto &port : logicalPorts) {
      if (!port.input)
        continue;
      SmallVector<Value> leaves;
      for (unsigned index = 0; index < port.pins.size(); ++index)
        leaves.push_back(body->getArgument(inputIndex++));
      Value joined = joinBoundary(port.key.first, port.targetType, leaves);
      if (port.splitRoute) {
        auto &pair = splitValues[*port.splitRoute];
        (port.splitEnable ? pair.second : pair.first) = joined;
      }
      if (port.inout)
        resolvedPortCarriers.try_emplace(port.key, joined);
      for (Value value : port.references)
        values[value] = joined;
    }
    if (variant)
      for (const auto &port : variant->domains) {
        Value clock = port.clock ? body->getArgument(inputIndex++) : Value();
        Value reset = port.reset ? body->getArgument(inputIndex++) : Value();
        domainInputs.try_emplace(port.requirement, std::make_pair(clock, reset));
      }
    if (failed(lowerResolvedCarriers()))
      return failure();
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
          isa<PortValueOp, RequirementClockOp, RequirementResetOp, ResolvedNetOp,
              ResolvedBoundaryOp>(op) ||
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
    for (Operation *instance : instances)
      if (failed(lowerInstance(instance)))
        return failure();
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
    struct CrossingEdge {
      Value original;
      circt::Backedge edge;
      Operation *consumer;
    };
    SmallVector<CrossingEdge> crossingEdges;
    for (const auto &crossing : crossings) {
      Operation *op = crossing.operation;
      auto destination = crossingControls(op, op->getAttr("destination_domain"),
                                          crossing.kind != CrossingKind::Gray);
      if (failed(destination)) {
        backedges.abandon();
        return reject(op, "crossing destination physical clock/reset did not map");
      }
      Type inputType = targetValueType(op->getOperand(0).getType());
      auto adapter = fixedBoundaryAdapters.find(op->getOperand(0).getType());
      if (adapter != fixedBoundaryAdapters.end())
        inputType = builder.getIntegerType(adapter->second.bits);
      auto input = backedges.get(inputType, op->getLoc());
      crossingEdges.push_back({op->getOperand(0), input, op});
      Value previous = input;
      if (crossing.kind == CrossingKind::Level || crossing.kind == CrossingKind::Gray) {
        for (unsigned stage = 0; stage < crossing.stages; ++stage)
          previous = crossingState(crossing, stage, previous, *destination, "forward",
                                   crossing.kind != CrossingKind::Gray);
        values[op->getResult(0)] = previous;
        if (crossing.kind == CrossingKind::Level) {
          if (failed(finishCrossingPayload(crossing))) {
            backedges.abandon();
            return failure();
          }
          continue;
        }
      }
      auto sourceControl = crossingControls(op, op->getAttr("source_domain"));
      if (failed(sourceControl)) {
        backedges.abandon();
        return reject(op, "crossing source physical clock/reset did not map");
      }
      if (crossing.kind == CrossingKind::Fifo) {
        auto sourceValid = backedges.get(builder.getI1Type(), op->getLoc());
        auto destinationReady = backedges.get(builder.getI1Type(), op->getLoc());
        crossingEdges.push_back({op->getOperand(1), sourceValid, op});
        crossingEdges.push_back({op->getOperand(2), destinationReady, op});
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
      if (failed(finishCrossingPayload(crossing))) {
        backedges.abandon();
        return failure();
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
      if (isa<NetSplitOp>(op)) {
        auto route = hierarchy->splitPlans[variant->occurrence].splitRoutes.find(&op);
        if (route == hierarchy->splitPlans[variant->occurrence].splitRoutes.end())
          return reject(&op, "split result lost its exact route identity");
        auto pair = splitValues[route->second];
        if (!pair.first || !pair.second)
          return reject(&op, "split data/enable have no target-local route");
        // Keep real SSA uses across later route-backedge replacement.
        values[op.getResult(0)] = circt::hw::WireOp::create(builder, op.getLoc(), pair.first);
        values[op.getResult(1)] = circt::hw::WireOp::create(builder, op.getLoc(), pair.second);
        continue;
      }
      if (isa<NetReadOp>(op)) {
        Value carrier = values.lookup(op.getOperand(0));
        auto expected = targetValueType(op.getResult(0).getType());
        if (!carrier || carrier.getType() != circt::hw::InOutType::get(expected))
          return reject(&op, "resolved read requires its exact typed physical carrier");
        values[op.getResult(0)] = make(&op, "sv.read_inout", expected, {carrier});
        continue;
      }
      if (isa<ResolvedNetOp, ResolvedBoundaryOp>(op))
        continue;
      if (fixedShapeSelections.count(&op)) {
        Value result = lowerFixedShapeSelection(&op);
        if (!result) {
          backedges.abandon();
          return reject(&op, "fixed named shape selection did not match its actual value layout");
        }
        values[op.getResult(0)] = result;
        continue;
      }
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
    for (auto &[index, edges] : splitEdges) {
      const auto &route = hierarchy->splitPlans[variant->occurrence].routes[index];
      auto actual = splitValues[index];
      if (!route.sourceChild) {
        const auto &binding = *route.binding;
        actual.first = binding.data ? values.lookup(binding.data)
                                    : typedFill(binding.driver, splitTypes[index],
                                                binding.level.getValue() == "high" ? '1' : '0');
        actual.second = values.lookup(binding.enable);
      }
      if (!actual.first || !actual.second || actual.first == Value(edges.first) ||
          actual.second == Value(edges.second) || actual.first.getType() != splitTypes[index] ||
          actual.second.getType() != splitEnableTypes[index]) {
        backedges.abandon();
        return reject(source, "split route did not resolve exact driver data/enable");
      }
      edges.first.setValue(actual.first);
      edges.second.setValue(actual.second);
      splitValues[index] = actual;
    }
    if (failed(lowerResolvedEffects())) {
      backedges.abandon();
      return failure();
    }
    for (auto &[original, edge, consumer] : crossingEdges) {
      Value input = values.lookup(original);
      if (!input) {
        backedges.abandon();
        return reject(original.getDefiningOp(), "crossing actual sampled input did not lower");
      }
      input = packFixedBoundary(consumer, original.getType(), input);
      if (!input || input.getType() != Value(edge).getType()) {
        backedges.abandon();
        return reject(consumer, "crossing named input disagrees with its exact packed layout");
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
        if (port.splitRoute) {
          const auto &pair = splitValues[*port.splitRoute];
          outputs.push_back(port.splitEnable ? pair.second : pair.first);
          continue;
        }
        auto leaves = splitBoundary(port.key.first, values.lookup(port.references.front()));
        llvm::append_range(outputs, leaves);
      }
    body->getTerminator()->setOperands(outputs);
    if (failed(backedges.clearOrEmitError()))
      return failure();
    return success();
  }

private:
  LogicalResult lowerInstance(Operation *instance) {
    const auto &child = hierarchy->variants[variant->children.lookup(instance)];
    if (!child.target)
      return reject(instance, "target child definition was not materialized before its parent");
    SmallVector<Value> inputs;
    for (const auto &port : child.ports)
      if (port.input) {
        if (port.splitRoute) {
          auto found = splitChildInputs.find({instance, *port.splitRoute});
          if (found == splitChildInputs.end())
            return reject(instance, "split child input lost its exact parent route");
          const auto &pair = splitValues[found->second];
          inputs.push_back(port.splitEnable ? pair.second : pair.first);
          continue;
        }
        if (port.inout) {
          auto found = resolvedChildCarriers.find({instance, port.key});
          if (found == resolvedChildCarriers.end())
            return reject(instance, "resolved instance port lacks its exact physical carrier");
          inputs.push_back(found->second);
          continue;
        }
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
        builder, instance->getLoc(), child.target, instance->getAttrOfType<StringAttr>("sym_name"),
        ArrayRef<Value>(inputs), targetInstanceParameters.lookup(instance),
        circt::hw::InnerSymAttr::get(instance->getAttrOfType<StringAttr>("sym_name")));
    correlate(targetInstance, instance);
    targetInstance->setAttr("nodal.domain_bindings", instance->getAttr("domain_bindings"));
    unsigned result = 0;
    for (const auto &port : child.ports)
      if (!port.input) {
        if (port.splitRoute) {
          auto found = splitChildOutputs.find({instance, *port.splitRoute});
          if (found == splitChildOutputs.end())
            return reject(instance, "split child output lost its exact parent route");
          auto &pair = splitValues[found->second];
          (port.splitEnable ? pair.second : pair.first) = targetInstance.getResult(result++);
          continue;
        }
        InstanceLogicalPortKey key{instance, port.key};
        auto references = instancePortValues.find(key);
        auto demanded = generatedInstanceOutputs.find(key);
        if (references == instancePortValues.end() && demanded == generatedInstanceOutputs.end()) {
          result += port.pins;
          continue; // Every unobserved authored output retains its physical slot.
        }
        Type actualType = demanded != generatedInstanceOutputs.end()
                              ? demanded->second.type
                              : targetValueType(references->second.front().getType());
        SmallVector<Value> leaves;
        for (unsigned index = 0; index < port.pins; ++index)
          leaves.push_back(targetInstance.getResult(result++));
        Value joined = joinBoundary(instance, actualType, leaves);
        if (demanded != generatedInstanceOutputs.end())
          generatedInstanceValues[key] = joined;
        if (references != instancePortValues.end())
          for (Value reference : references->second)
            values[reference] = joined;
      }
    return success();
  }
  struct GeneratedFamilyPlan;
  struct GeneratedOutput {
    Value value;
    SmallVector<Value> destinations;
    Type laneType, busType;
    uint64_t maximumLaneBits;
    GeneratedFamilyPlan *child = nullptr;
    unsigned childOutput = 0;
    SmallVector<Operation *, 1> writes;
    InstanceLogicalPortKey instancePort = {};
  };
  struct GeneratedInstanceOutput {
    HierarchyBoundaryType boundary;
    Type type;
    uint64_t maximumBits;
    DictionaryAttr layout;
  };
  struct SelectedInstanceRead {
    InstanceLogicalPortKey key;
    GeneratedSelectionProof proof;
  };
  // Original Instance + logical declaration, or a direct original endpoint with
  // no Instance. A selected SSA view is never logical output identity.
  llvm::DenseMap<LogicalPortKey, DigitalInterfaceBoundaryType> generatedInterfaceTypes;
  llvm::DenseMap<Value, DigitalInterfaceTemplateDriver> generatedInterfaceDrivers;
  llvm::DenseMap<LogicalPortKey, Value> generatedInterfaceRoots;
  llvm::DenseSet<Operation *> generatedInterfaceEffects;
  llvm::MapVector<InstanceLogicalPortKey, GeneratedInstanceOutput> generatedInstanceOutputs;
  llvm::DenseMap<Operation *, SmallVector<InstanceLogicalPortKey>> generatedInstanceDemandKeys;
  llvm::MapVector<Operation *, SelectedInstanceRead> selectedInstanceReads;
  llvm::DenseMap<InstanceLogicalPortKey, Value> generatedInstanceValues;
  struct GeneratedFamilyPlan {
    Operation *operation;
    GeneratedFamilyPlan *parent = nullptr;
    SmallVector<Operation *> selections;
    SmallVector<Value> buses;
    GeneratedDomain domain;
    TypedAttr lower, upper, step, capacity;
    bool ordinalTransport = false;
    StringAttr name, indexName, genvarName;
    SmallVector<LogicalPortPlan> ports;
    SmallVector<Value> captures;
    SmallVector<GeneratedOutput> outputs;
    SmallVector<Operation *> operations, registers, instances;
    SmallVector<std::pair<Value, Operation *>> connectionSeeds;
    llvm::DenseMap<Value, Value> aliases;
    // One exact flattened row-major read route per authored projection. These
    // are physical adapters over checked ShapeIndex operations, not selections.
    struct Slice {
      Value root;
      SmallVector<TypedAttr> offsets;
    };
    llvm::DenseMap<Operation *, Slice> slices;
    llvm::DenseMap<Value, SmallVector<Operation *, 2>> updates;
    llvm::DenseSet<Operation *> sinks;
    circt::hw::HWModuleOp target;
  };
  struct GeneratedGuardScope {
    Block *source;
    StringAttr name;
    SmallVector<LogicalPortPlan> ports;
    // A carrier is the exact already-proven family output, never a family token.
    SmallVector<std::pair<GeneratedFamilyPlan *, unsigned>> carriers;
    SmallVector<GeneratedFamilyPlan *> busFamilies;
    SmallVector<Operation *> destinations;
    circt::hw::HWModuleOp target;
  };
  SmallVector<std::unique_ptr<GeneratedGuardScope>> generatedGuardScopes;
  struct GeneratedChoicePlan {
    TypedAttr condition, pattern;
    bool matchIsThen = true;
    StringAttr name;
    GeneratedGuardScope *arms[2] = {nullptr, nullptr};
  };
  llvm::MapVector<Operation *, GeneratedChoicePlan> generatedChoices;
  llvm::MapVector<Operation *, SmallVector<Operation *>> generatedGuardOutputs;
  llvm::DenseMap<Operation *, Value> generatedGuardWires;
  llvm::DenseSet<Operation *> generatedGuardOperations;
  SmallVector<std::unique_ptr<GeneratedFamilyPlan>> generatedFamilies;
  llvm::DenseMap<Operation *, GeneratedFamilyPlan *> generatedFamilyPlans;
  llvm::DenseMap<Operation *, std::pair<GeneratedFamilyPlan *, unsigned>> generatedOutputPorts;
  llvm::DenseMap<Operation *, std::pair<GeneratedFamilyPlan *, unsigned>> generatedExports;
  llvm::MapVector<Operation *, GeneratedMember> generatedMembers;
  // The prefix preflight owns the root carrier route. Consumers never rediscover
  // it from a leaf storage identity or assume an unbounded search will succeed.
  llvm::DenseMap<Operation *, std::pair<GeneratedFamilyPlan *, unsigned>> generatedSelectionRoots;
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
  // Proof cache only: the literal owner independently checks original spelling,
  // signedness, actual-use provenance and every legal width before fill emission.
  llvm::DenseMap<Operation *, char> literalFills;
  char symbolicLiteralFill(Operation *op) {
    auto found = literalFills.find(op);
    if (found != literalFills.end())
      return found->second;
    if (!isa<ConstantOp, ConstLiteralOp>(op) || op->getNumResults() != 1 ||
        !isDigitalScalarType(op->getResult(0).getType()))
      return 0;
    auto value = op->getAttrOfType<IntegerAttr>("value");
    if (!value)
      return 0;
    Type type = op->getResult(0).getType();
    auto parametric = dyn_cast<ParametricIntegerType>(type);
    bool sign = isa<SIntType>(type) || (parametric && parametric.getKind() == "sint");
    char fill =
        value.getValue().isZero() ? '0'
        : sign && !cast<IntegerType>(value.getType()).isUnsigned() && value.getValue().isAllOnes()
            ? '1'
            : 0;
    if (!fill || cost >= maximumGeneratedOperations || failed(verifyDigitalLiteralOperation(op)))
      return 0;
    ++cost;
    literalFills.try_emplace(op, fill);
    return fill;
  }
  bool generatedSymbolicValue(Operation *op) {
    if (op->getNumResults() != 1)
      return false;
    Type original = op->getResult(0).getType();
    Type result = targetValueType(original);
    if (!isa_and_nonnull<circt::hw::IntType>(result))
      return false;
    if (symbolicLiteralFill(op))
      return true;
    StringRef code = text(op, "operator_name");
    if (isa<SequentialSelectOp>(op) || code == "digital_select")
      return op->getNumOperands() == 3 &&
             targetValueType(op->getOperand(0).getType()).isInteger(1) &&
             op->getOperand(1).getType() == original && op->getOperand(2).getType() == original;
    if (code == "digital_alias")
      return op->getNumOperands() == 1 && op->getOperand(0).getType() == original;
    return llvm::is_contained(ArrayRef<StringRef>{"digital_and", "digital_or", "digital_xor"},
                              code) &&
           op->getNumOperands() == 2 && op->getOperand(0).getType() == original &&
           op->getOperand(1).getType() == original;
  }
  TypedAttr familyInteger(GeneratedFamilyPlan &family, Value value, Operation *use) {
    value = capturedValue(value);
    if (value == family.domain.induction)
      return circt::hw::ParamDeclRefAttr::get(family.indexName, builder.getI32Type());
    if (!value)
      return {};
    auto expression = resolveStructuralIntegerExpression(value, use);
    return succeeded(expression) && expression->expression
               ? parameterExpression(*expression->expression, &family)
               : TypedAttr();
  }
  void appendGeneratedPort(SmallVectorImpl<LogicalPortPlan> &ports, Value value, bool input,
                           unsigned index, Type type, Operation *anchor = nullptr,
                           Type originalType = {}, DictionaryAttr layout = {}) {
    LogicalPortPlan pin;
    pin.key = {value ? value.getDefiningOp() : anchor, Attribute()};
    pin.originalType = value ? value.getType() : originalType;
    pin.generatedLayout = layout;
    pin.targetType = type;
    pin.name = builder.getStringAttr((input ? "capture_" : "result_") + std::to_string(index));
    pin.input = input;
    if (value)
      pin.references.push_back(value);
    if (auto structure = dyn_cast<circt::hw::StructType>(type))
      for (const auto &field : structure.getElements())
        pin.pins.push_back(
            builder.getStringAttr(pin.name.getValue().str() + "__" + field.name.getValue().str()));
    else
      pin.pins.push_back(pin.name);
    ports.push_back(std::move(pin));
  }
  void appendGeneratedOutputPort(SmallVectorImpl<LogicalPortPlan> &ports,
                                 const GeneratedOutput &output, bool input, unsigned index,
                                 Type type) {
    if (!output.instancePort.second.first)
      return appendGeneratedPort(ports, output.value, input, index, type);
    const auto &logical = generatedInstanceOutputs.find(output.instancePort)->second;
    appendGeneratedPort(ports, {}, input, index, type,
                        output.instancePort.first ? output.instancePort.first
                                                  : output.instancePort.second.first,
                        logical.boundary.type, logical.layout);
  }
  Value generatedOutputValue(const GeneratedOutput &output) {
    if (output.instancePort.second.first) {
      if (Value value = generatedInstanceValues.lookup(output.instancePort))
        return value;
      if (!output.instancePort.first)
        return values.lookup(generatedInterfaceRoots.lookup(output.instancePort.second));
      return {};
    }
    return values.lookup(capturedValue(output.value));
  }
  LogicalResult planSelectedInstanceOutput(Operation *op, InstanceLogicalPortKey key,
                                           Type originalType, Operation *declarationOwner,
                                           const GeneratedSelectionProof &proof,
                                           const HierarchyBoundaryType *originalView = nullptr) {
    if (!generatedInstanceOutputs.count(key)) {
      auto view = originalView
                      ? FailureOr<HierarchyBoundaryType>(*originalView)
                      : inspectHierarchyBoundaryType(originalType, declarationOwner, key.first);
      if (failed(view))
        return failure();
      uint64_t maximumBits = 0;
      SmallVector<circt::hw::StructType::FieldInfo> fields;
      for (const auto &leaf : view->leaves) {
        Type type = leafVectorType(leaf); // No scope: induction atoms cannot become slot widths.
        uint64_t bits = leaf.width.bounds.upper;
        if (!type || leaf.width.bounds.lower <= 0 || bits > maximumWidth) {
          (void)reject(op, "selected instance carrier requires invariant all-lane payload widths");
          return failure();
        }
        for (const auto &group : leaf.dimensions)
          for (const auto &extent : group.extents) {
            if (extent.bounds.lower <= 0 || extent.bounds.upper > maximumWidth / bits) {
              (void)reject(op, "selected instance carrier exceeds bounded all-lane shape");
              return failure();
            }
            bits *= extent.bounds.upper;
          }
        if (bits > maximumWidth - maximumBits || cost >= maximumGeneratedOperations) {
          (void)reject(op, "selected instance logical output exceeds target budget");
          return failure();
        }
        ++cost;
        maximumBits += bits;
        fields.push_back({builder.getStringAttr(leafName(leaf)), type});
      }
      if (fields.empty() ||
          (!namedBoundaryABI && (fields.size() != 1 || !view->leaves.front().path.empty()))) {
        (void)reject(op, "selected aggregate output requires exact named leaf transport");
        return failure();
      }
      Type type = fields.size() == 1 && view->leaves.front().path.empty()
                      ? fields.front().type
                      : Type(circt::hw::StructType::get(builder.getContext(), fields));
      DictionaryAttr layout;
      if (fields.size() == 1 && view->leaves.front().path.empty() &&
          !view->leaves.front().dimensions.empty()) {
        const auto &leaf = view->leaves.front();
        SmallVector<Attribute> groups;
        for (const auto &group : leaf.dimensions) {
          SmallVector<Attribute> extents;
          for (const auto &extent : group.extents)
            extents.push_back(parameterExpression(extent));
          groups.push_back(builder.getArrayAttr(extents));
        }
        layout = builder.getDictionaryAttr(
            {builder.getNamedAttr("kind", builder.getStringAttr("scalar-element-vec")),
             builder.getNamedAttr("order",
                                  builder.getStringAttr("recursive-row-major-fields-lsb-first")),
             builder.getNamedAttr("element_width", parameterExpression(leaf.width)),
             builder.getNamedAttr("dimension_groups", builder.getArrayAttr(groups))});
      }
      generatedInstanceOutputs.try_emplace(
          key, GeneratedInstanceOutput{*view, type, maximumBits, layout});
      generatedInstanceDemandKeys[key.first ? key.first : key.second.first].push_back(key);
    }
    if (generatedInstanceOutputs.find(key)->second.type !=
        targetValueType(op->getResult(0).getType())) {
      (void)reject(op, "selected view must exactly match the invariant original logical payload");
      return failure();
    }
    if (cost + proof.selections.size() + 1 > maximumGeneratedOperations) {
      (void)reject(op, "selected instance prefix storage exceeds target budget");
      return failure();
    }
    cost += proof.selections.size() + 1;
    selectedInstanceReads.try_emplace(op, SelectedInstanceRead{key, proof});
    return success();
  }
  LogicalResult planSelectedInstanceOutputs() {
    unsigned visited = 0;
    auto walked = source->walk<WalkOrder::PreOrder>([&](Operation *op) {
      if (op != source.getOperation() && isa<ModuleOp>(op))
        return WalkResult::skip();
      if (++visited > maximumAuthoredOperations) {
        (void)reject(op, "selected output demand inventory exceeds authored-work budget");
        return WalkResult::interrupt();
      }
      if (!isa<InstancePortOp>(op) || op->getNumOperands() == 0)
        return WalkResult::advance();
      auto boundary = inspectInstancePort(op);
      if (failed(boundary) || !boundary->selection || text(op, "direction") != "output" ||
          !isa<GenerateOp>(boundary->instance->getParentOp())) {
        (void)reject(op,
                     "selected instance target currently admits original generated outputs only");
        return WalkResult::interrupt();
      }
      auto proof =
          inspectGeneratedSelection(op->getOperand(0), op->getOperand(1), text(op, "member"), op);
      if (failed(proof) || proof->selections.empty())
        return WalkResult::interrupt();
      InstanceLogicalPortKey key{boundary->instance, {boundary->port, Attribute()}};
      if (failed(planSelectedInstanceOutput(
              op, key, boundary->port->getAttrOfType<TypeAttr>("type").getValue(), boundary->module,
              *proof)))
        return WalkResult::interrupt();
      return WalkResult::advance();
    });
    if (walked.wasInterrupted())
      return failure();
    for (const auto &access : interfaceAccesses) {
      if (cost >= maximumGeneratedOperations)
        return reject(access.value.getDefiningOp(),
                      "Interface access inventory exceeds target budget");
      ++cost;
      if (!access.identity.selection)
        continue;
      Operation *op = access.value.getDefiningOp();
      auto endpointIndex = interfaceEndpoints.find(access.projection);
      if (endpointIndex == interfaceEndpoints.end())
        return reject(op, "selected Interface member lost its complete original endpoint");
      const auto &endpoint = endpoints[endpointIndex->second];
      if (access.identity.access.getValue() != "out")
        return reject(op, "selected Interface target requires an original ordinary output");
      Operation *selection = access.identity.selection;
      auto proof = inspectGeneratedSelection(selection->getOperand(0), selection->getOperand(1),
                                             text(selection, "member"), selection);
      if (failed(proof) || proof->selections.empty())
        return failure();
      InstanceLogicalPortKey key{endpoint.instance, {endpoint.declaration, access.identity.path}};
      const HierarchyBoundaryType *originalView = nullptr;
      if (!endpoint.instance) {
        auto found = generatedInterfaceTypes.find(key.second);
        if (found == generatedInterfaceTypes.end() || !generatedInterfaceRoots.count(key.second))
          return reject(op, "selected direct Interface lost its original authored payload driver");
        originalView = &found->second.payload;
      }
      if (failed(planSelectedInstanceOutput(op, key, access.leaf.type, endpoint.declarationOwner,
                                            *proof, originalView)))
        return failure();
    }
    return success();
  }
  bool generatedScopeContains(Block *scope, Block *block) {
    for (; block; block = block->getParentOp() ? block->getParentOp()->getBlock() : nullptr)
      if (scope == block)
        return true;
    return false;
  }
  LogicalResult planGeneratedFamilies() {
    if (failed(planSelectedInstanceOutputs()))
      return failure();
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
      if (op.getNumResults() != 1 || failed(domain) || domain->step.bounds.lower <= 0)
        return reject(&op, "generated target requires its checked positive-step family domain");
      bool concreteDomain = llvm::all_of(
          ArrayRef<InstantiatedIntegerExpression>{domain->lower, domain->upperExclusive,
                                                  domain->step},
          [](const auto &expression) {
            return expression.root->kind == InstantiatedIntegerNode::Kind::Literal;
          });
      if (concreteDomain && !preserveConstantLoops)
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
      family.domain = *domain;
      family.name = unique((variant ? variant->name : text(source, "sym_name").str()) +
                           "__family_" + std::to_string(familyIndex));
      llvm::StringSet<> parameterNames;
      for (Attribute attr : targetParameters)
        parameterNames.insert(cast<circt::hw::ParamDeclAttr>(attr).getName().getValue());
      for (auto *ancestor = parent; ancestor; ancestor = ancestor->parent)
        parameterNames.insert(ancestor->indexName.getValue());
      std::string index = "__nodal_index";
      while (!parameterNames.insert(index).second)
        index += "_";
      family.indexName = builder.getStringAttr(index);
      family.genvarName = builder.getStringAttr("__nodal_lane_" + std::to_string(familyIndex));
      family.lower = parameterExpression(domain->lower, parent);
      family.upper = parameterExpression(domain->upperExclusive, parent);
      family.step = parameterExpression(domain->step, parent);
      family.ordinalTransport = domain->lower.bounds.lower != 0 ||
                                domain->lower.bounds.upper != 0 || domain->step.bounds.lower != 1 ||
                                domain->step.bounds.upper != 1 ||
                                domain->upperExclusive.bounds.lower <= 0;
      bool dependentDomain = false;
      llvm::DenseSet<const InstantiatedIntegerNode *> seenBounds;
      SmallVector<const InstantiatedIntegerNode *> boundNodes{
          domain->lower.root.get(), domain->upperExclusive.root.get(), domain->step.root.get()};
      while (!boundNodes.empty()) {
        const auto *node = boundNodes.pop_back_val();
        if (!seenBounds.insert(node).second)
          continue;
        if (cost >= maximumGeneratedOperations)
          return reject(&op, "generated bound transport exceeds finite target DAG budget");
        ++cost;
        dependentDomain |= node->kind == InstantiatedIntegerNode::Kind::OpaqueValue;
        for (const auto &operand : node->operands)
          boundNodes.push_back(operand.get());
      }
      family.ordinalTransport |= dependentDomain;
      // Preserve the established compact ABI for complete zero-based families.
      // Independent nonempty domains use their exact cardinality below. Only
      // dependent or maybe-empty domains retain a bounded internal ordinal
      // carrier. Holes never acquire drivers or bypass existence proofs.
      family.capacity = family.ordinalTransport
                            ? TypedAttr(builder.getI32IntegerAttr(domain->maximumTripCount))
                            : family.upper;
      if (family.ordinalTransport && !dependentDomain &&
          proveIntegerExpressionLess(domain->lower, domain->upperExclusive)) {
        // Derive the exact positive cardinality with the canonical checked
        // arithmetic owner. No target default, sampled bound or lane expansion.
        auto distance = composeIntegerExpression("sub", {domain->upperExclusive, domain->lower});
        auto adjustment =
            composeIntegerExpression("sub", {domain->step, makeLiteralIntegerExpression(1)});
        auto numerator = succeeded(distance) && succeeded(adjustment)
                             ? composeIntegerExpression("add", {*distance, *adjustment})
                             : FailureOr<InstantiatedIntegerExpression>(failure());
        auto fitsPositiveI32 = [](const FailureOr<InstantiatedIntegerExpression> &expression) {
          return succeeded(expression) && expression->bounds.lower >= 0 &&
                 expression->bounds.upper <= std::numeric_limits<int32_t>::max();
        };
        // Check each derived intermediate even when the canonical owner folds
        // a literal result, rather than silently widening only concrete cases.
        if (!fitsPositiveI32(distance) || !fitsPositiveI32(adjustment) ||
            !fitsPositiveI32(numerator))
          return reject(&op,
                        "generated cardinality requires checked positive signed-i32 arithmetic");
        auto count = succeeded(numerator)
                         ? composeIntegerExpression("div", {*numerator, domain->step})
                         : FailureOr<InstantiatedIntegerExpression>(failure());
        if (failed(count) || count->bounds.lower <= 0 ||
            !(family.capacity = parameterExpression(*count)))
          return reject(&op,
                        "generated cardinality requires checked positive signed-i32 arithmetic");
      }
      if (!family.lower || !family.upper || !family.step)
        return reject(&op, "generated bounds require checked target Integer expressions");
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
      auto output = [&](Value value, GeneratedFamilyPlan *child = nullptr, unsigned childOutput = 0,
                        InstanceLogicalPortKey key = {}) -> FailureOr<unsigned> {
        if (domain->maximumTripCount == 0)
          return failure(); // No physical slot, not even a zero-width placeholder.
        if (value)
          value = capturedValue(value);
        if (child)
          key = child->outputs[childOutput].instancePort;
        auto logical = generatedInstanceOutputs.find(key);
        Type type = child              ? child->outputs[childOutput].busType
                    : key.second.first ? logical->second.type
                                       : (value ? targetValueType(value.getType()) : Type());
        uint64_t maximumBits = key.second.first ? logical->second.maximumBits : 0;
        if (child) {
          const auto &leaf = child->outputs[childOutput];
          maximumBits = leaf.maximumLaneBits * child->domain.maximumTripCount;
        } else if (key.second.first) {
          // The original all-lane boundary query already established this capacity.
        } else if (auto scalar = dyn_cast_if_present<IntegerType>(type))
          maximumBits = scalar.getWidth();
        else if (isa_and_nonnull<circt::hw::IntType, circt::hw::StructType>(type)) {
          auto maximum = inferDigitalBitPatternMaximumBits(value.getType(), source);
          if (succeeded(maximum) && *maximum > 0)
            maximumBits = *maximum;
        }
        if (!maximumBits || maximumBits > maximumWidth / domain->maximumTripCount)
          return failure();
        for (auto [index, prior] : llvm::enumerate(family.outputs)) {
          if (cost >= maximumGeneratedOperations)
            return failure();
          ++cost; // Repeated exports cannot create uncharged quadratic dedup work.
          if (prior.value == value && prior.instancePort == key && prior.child == child &&
              prior.childOutput == childOutput)
            return index;
        }
        Type bus = generatedBusType(type, family.capacity);
        if (!bus)
          return failure();
        family.outputs.push_back({value, {}, type, bus, maximumBits, child, childOutput, {}, key});
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
              if (!row ||
                  (row->getParentOp() != &op && !nested->operation->isProperAncestor(row)) ||
                  row->getNumOperands() != 2 ||
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
              llvm::append_range(out.writes, leaf.writes);
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
        if (isa<InterfaceOp>(child)) {
          staticOperations.insert(child);
          return WalkResult::skip(); // Definition payload was checked at its original anchor.
        }
        if (isa<InterfaceInstanceOp, InterfaceAbiOp>(child)) {
          if (child->getParentOp() != &op ||
              (isa<InterfaceInstanceOp>(child) && !interfaceEndpoints.count(child))) {
            (void)reject(child, "generated Interface declaration lost its direct checked family");
            return WalkResult::interrupt();
          }
          staticOperations.insert(child);
          return WalkResult::advance();
        }
        if (isa<MemberAccessOp>(child)) {
          if (selectedInstanceReads.count(child))
            return WalkResult::advance();
          auto found = generatedInterfaceDrivers.find(child->getResult(0));
          if (found == generatedInterfaceDrivers.end() || found->second.family != &op ||
              child->getParentOp() != &op || !targetValueType(child->getResult(0).getType()) ||
              failed(capture(found->second.source))) {
            (void)reject(child, "generated Interface member requires its original whole driver");
            return WalkResult::interrupt();
          }
          family.aliases.try_emplace(found->second.root, capturedValue(found->second.source));
          family.operations.push_back(child);
          return WalkResult::advance();
        }
        if (generatedInterfaceEffects.contains(child))
          return WalkResult::advance(); // Exact original effect owned by the authored batch.
        if (isa<InterfaceObserveOp>(child)) {
          if (!llvm::all_of(child->getOperands(), [&](Value value) {
                return generatedInterfaceDrivers.count(value) ||
                       selectedInstanceReads.count(value.getDefiningOp());
              })) {
            (void)reject(child, "generated Interface observation lacks its exact ordinary route");
            return WalkResult::interrupt();
          }
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
        if (isa<InstanceOp>(child)) {
          if (child->getParentOp() != &op || !variant || !variant->children.count(child)) {
            (void)reject(child, "generated child requires its exact checked occurrence template");
            return WalkResult::interrupt();
          }
          auto actuals = instanceParameters(child, &family);
          if (failed(actuals)) {
            (void)reject(child, "generated child parameter lacks its exact bounded actual DAG");
            return WalkResult::interrupt();
          }
          targetInstanceParameters.try_emplace(child, *actuals);
          family.instances.push_back(child);
          return WalkResult::advance();
        }
        if (isa<ParameterOverrideOp>(child)) {
          staticOperations.insert(child);
          return WalkResult::advance();
        }
        if (isa<InstanceDomainBindingOp>(child)) {
          auto target = resolveHierarchyInstance(child, child->getAttr("instance"));
          if (failed(target) || target->instance->getParentOp() != &op) {
            (void)reject(child, "generated binding requires its exact family-owned child");
            return WalkResult::interrupt();
          }
          instanceBindings.try_emplace(
              std::make_pair(target->instance, child->getAttr("requirement")), child);
          for (Value operand : child->getOperands())
            if (failed(capture(operand)))
              return WalkResult::interrupt();
          return WalkResult::advance();
        }
        if (isa<InstancePortOp>(child)) {
          if (selectedInstanceReads.count(child)) {
            if (child->getParentOp() != &op) {
              (void)reject(child,
                           "selected instance view requires its exact admitted lexical scope");
              return WalkResult::interrupt();
            }
            return WalkResult::advance();
          }
          auto boundary = inspectInstancePort(child);
          if (failed(boundary) || boundary->selection || boundary->instance->getParentOp() != &op ||
              child->getParentOp() != &op || !targetValueType(child->getResult(0).getType())) {
            (void)reject(child, "generated port requires its exact body-local ordinary child");
            return WalkResult::interrupt();
          }
          Value value = child->getResult(0);
          instancePortValues[{boundary->instance, {boundary->port, Attribute()}}].push_back(value);
          if (text(child, "direction") == "input")
            family.connectionSeeds.emplace_back(value, &op.getRegion(0).front().back());
          family.operations.push_back(child);
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
          if (text(child, "kind") == "instance" || text(child, "kind") == "interface") {
            auto symbol = inspectGeneratedExportSymbol(child);
            if (failed(symbol))
              return WalkResult::interrupt();
            for (const auto &key : generatedInstanceDemandKeys.lookup(*symbol))
              if (failed(output({}, nullptr, 0, key))) {
                (void)reject(child,
                             "selected original instance output exceeds family carrier budget");
                return WalkResult::interrupt();
              }
            return WalkResult::advance();
          }
          if (child->getNumOperands() != 1 ||
              (text(child, "kind") != "" && text(child, "kind") != "family")) {
            (void)reject(
                child,
                "generated structural symbol exports require occurrence-aware target routing");
            return WalkResult::interrupt();
          }
          // An empty template has no attained slot and must not manufacture an
          // i0 bus or a driver. Its authored helper and zero-trip loop remain.
          if (domain->maximumTripCount == 0)
            return WalkResult::advance();
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
          Value assigned = capturedValue(child->getOperand(0));
          Operation *producer = assigned ? assigned.getDefiningOp() : nullptr;
          if (producer && producer->getParentOp() == &op &&
              (isa<InstancePortOp>(producer) ||
               (isa<DigitalStorageOp>(producer) && text(producer, "kind") == "wire"))) {
            if (failed(capture(child->getOperand(1))))
              return WalkResult::interrupt();
            return WalkResult::advance(); // The canonical authored-template query owns this alias.
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
          Value destination = capturedValue(slice->getOperand(0));
          if (failed(index) ||
              targetValueType(destination.getType()) != family.outputs[*index].busType) {
            (void)reject(child, "generated output requires exact parent-row lane layout");
            return WalkResult::interrupt();
          }
          family.outputs[*index].destinations.push_back(destination);
          family.outputs[*index].writes.push_back(child);
          // Source may spell outer row selections inside the innermost body.
          // Keep every proven output projection as a sink-only logical route.
          for (Value target = child->getOperand(0); target;) {
            auto projection = target.getDefiningOp<ShapeIndexOp>();
            if (!projection || !op.isProperAncestor(projection))
              break;
            family.sinks.insert(projection);
            target = projection->getOperand(0);
          }
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
          Type stateType = targetValueType(child->getResult(0).getType());
          if (child->getParentOp() == &op && text(child, "kind") == "wire" && stateType) {
            family.connectionSeeds.emplace_back(child->getResult(0),
                                                &op.getRegion(0).front().back());
            family.operations.push_back(child);
            return WalkResult::advance();
          }
          if (child->getParentOp() != &op || text(child, "kind") != "register" ||
              !isa_and_nonnull<IntegerType, circt::hw::IntType>(stateType) ||
              (isa<circt::hw::IntType>(stateType) && child->getNumOperands())) {
            (void)reject(child,
                         "generated symbolic state requires direct uninitialized scalar storage");
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
          bool exactAxis = false;
          if (succeeded(index))
            for (auto *ancestor = &family; ancestor; ancestor = ancestor->parent)
              exactAxis |= index->loop == ancestor->operation &&
                           capturedValue(child->getOperand(1)) == ancestor->domain.induction;
          if (child->getNumOperands() != 2 || !shape ||
              !generatedBusType(targetValueType(child->getResult(0).getType()),
                                builder.getI32IntegerAttr(1)) ||
              failed(index) || !exactAxis) {
            (void)reject(child,
                         "generated scalar slice requires the exact direct family induction");
            return WalkResult::interrupt();
          }
        }
        if (isa<ShapeIndexOp>(child)) {
          auto resultStructure =
              dyn_cast<circt::hw::StructType>(targetValueType(child->getResult(0).getType()));
          unsigned leafCount = resultStructure ? resultStructure.getElements().size() : 1;
          GeneratedFamilyPlan::Slice route;
          for (unsigned leaf = 0; leaf != leafCount; ++leaf) {
            Value root = child->getResult(0);
            TypedAttr offset = builder.getI32IntegerAttr(0);
            while (auto projection = root.getDefiningOp<ShapeIndexOp>()) {
              if (projection->getParentOp() != &op)
                break;
              if (cost >= maximumGeneratedOperations) {
                (void)reject(child, "generated row-major read exceeds finite target budget");
                return WalkResult::interrupt();
              }
              ++cost;
              TypedAttr index = familyInteger(family, projection->getOperand(1), projection);
              Type type = targetValueType(root.getType());
              if (resultStructure) {
                auto structure = dyn_cast<circt::hw::StructType>(type);
                if (!structure || leaf >= structure.getElements().size() ||
                    structure.getElements()[leaf].name !=
                        resultStructure.getElements()[leaf].name) {
                  (void)reject(child, "generated row-major read lost its exact logical field");
                  return WalkResult::interrupt();
                }
                type = structure.getElements()[leaf].type;
              }
              TypedAttr stride = generatedWidth(type);
              if (!index || !stride) {
                (void)reject(child, "generated row-major read lost its exact axis proof");
                return WalkResult::interrupt();
              }
              offset = circt::hw::ParamExprAttr::get(
                  circt::hw::PEO::Add, offset,
                  circt::hw::ParamExprAttr::get(circt::hw::PEO::Mul, index, stride));
              root = capturedValue(projection->getOperand(0));
            }
            route.root = root;
            route.offsets.push_back(offset);
          }
          family.slices.try_emplace(child, std::move(route));
        }
        if (child->getNumResults() == 1 &&
            isa_and_nonnull<circt::hw::IntType>(targetValueType(child->getResult(0).getType())) &&
            !isa<DigitalStorageOp, ShapeIndexOp>(child) && !generatedSymbolicValue(child)) {
          (void)reject(child,
                       "generated symbolic scalar requires exact-type bitwise/select transport");
          return WalkResult::interrupt();
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
        if (isa_and_nonnull<circt::hw::IntType>(targetValueType(reg->getResult(0).getType())) &&
            updateSemantics(first, "reset_policy") != "none")
          return reject(reg,
                        "generated symbolic state currently requires resetless exact-type storage");
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
              targetValueType(destination.getType()) != out.busType)
            return reject(&op,
                          "generated output requires one ordinary output pin with exact layout");
          if (isa<StructuralIfOp>(op.getParentOp())) {
            if (out.writes.empty())
              return reject(&op, "guarded generated output lost its canonical authored writes");
            llvm::append_range(generatedGuardOutputs[port], out.writes);
          } else if (!generatedOutputPorts.try_emplace(port, std::make_pair(&family, i)).second)
            return reject(&op, "generated output requires one exact family driver");
        }
      // A driven output bus is a sink, not a helper input. Reject any separate
      // read of that bus rather than invent an internally driven input pin.
      llvm::DenseSet<Value> outputRoots;
      for (const auto &out : family.outputs)
        for (Value destination : out.destinations) {
          outputRoots.insert(destination);
          while (auto projection = destination.getDefiningOp<ShapeIndexOp>()) {
            if (!family.sinks.contains(projection))
              break;
            destination = projection->getOperand(0);
            outputRoots.insert(destination);
          }
        }
      for (Operation *child : family.operations)
        if (!family.sinks.contains(child))
          for (Value operand : child->getOperands())
            if (outputRoots.contains(capturedValue(operand)))
              return reject(child, "generated output bus reads require a separate logical adapter");
      llvm::erase_if(family.captures, [&](Value value) { return outputRoots.contains(value); });
      for (auto [index, value] : llvm::enumerate(family.captures))
        appendGeneratedPort(family.ports, value, true, index, targetValueType(value.getType()));
      for (auto [index, out] : llvm::enumerate(family.outputs))
        appendGeneratedOutputPort(family.ports, out, false, index, out.laneType);
      uint64_t physicalPins = 0;
      for (const auto &port : family.ports)
        physicalPins += port.pins.size();
      if (cost + 8 * physicalPins + targetParameters.size() > maximumGeneratedOperations)
        return reject(&op, "generated logical adapters exceed target budget");
      cost += 8 * physicalPins + targetParameters.size();
      if (cost + 12 * targetParameters.size() > maximumGeneratedOperations)
        return reject(&op, "generated parameter guards exceed target budget");
      cost += 12 * targetParameters.size();
      staticOperations.insert(&op);
      generatedFamilies.push_back(std::move(plan));
      return success();
    };
    unsigned inventoryWork = 0;
    auto chargeInventory = [&](Operation *operation) -> LogicalResult {
      if (inventoryWork >= maximumAuthoredOperations)
        return reject(operation,
                      "generated recursive inventory exceeds finite authored-work budget");
      ++inventoryWork;
      return success();
    };
    std::function<LogicalResult(Block &, unsigned)> planScope;
    planScope = [&](Block &block, unsigned depth) -> LogicalResult {
      if (depth >= digitalTypeMaximumDepth)
        return reject(block.getParentOp(), "generated guard ancestry exceeds finite target depth");
      for (Operation &op : block) {
        if (failed(chargeInventory(&op)))
          return failure();
        if (isa<GenerateOp>(op)) {
          if (failed(planFamily(op, nullptr, 0)))
            return failure();
        } else if (isa<StructuralIfOp>(op)) {
          bool hasGeneratedUse = false;
          bool exhausted = false;
          op.walk<WalkOrder::PreOrder>([&](Operation *nested) {
            if (failed(chargeInventory(nested))) {
              exhausted = true;
              return WalkResult::interrupt();
            }
            hasGeneratedUse |= isa<GenerateOp, GeneratedMemberOp>(nested);
            return hasGeneratedUse ? WalkResult::interrupt() : WalkResult::advance();
          });
          if (exhausted)
            return failure();
          if (!depth && !hasGeneratedUse)
            return reject(&op,
                          "this structural target profile requires an actual generated family use");
          auto header = inspectStructuralIfHeader(&op);
          auto predicate = capturedValue(op.getOperand(0));
          auto comparison =
              predicate ? predicate.getDefiningOp<StaticCompareOp>() : StaticCompareOp();
          if (op.getNumResults() || failed(header) || !comparison ||
              failed(analyzeStaticPredicate(op.getOperand(0), &op)))
            return reject(&op,
                          "generated guard requires its checked result-free static comparison");
          auto left = resolveStructuralIntegerExpression(comparison->getOperand(0), comparison);
          auto right = resolveStructuralIntegerExpression(comparison->getOperand(1), comparison);
          if (failed(left) || failed(right) || !left->expression || !right->expression)
            return reject(&op, "generated guard requires exact owned Integer expressions");
          StringRef code = text(comparison, "comparison");
          GeneratedChoicePlan choice;
          if (code == "eq" || code == "ne") {
            choice.condition = parameterExpression(*left->expression);
            choice.pattern = parameterExpression(*right->expression);
            choice.matchIsThen = code == "eq";
          } else if (code == "lt" || code == "le" || code == "gt" || code == "ge") {
            bool reverse = code == "gt" || code == "le";
            auto difference = composeIntegerExpression(
                "sub", reverse ? ArrayRef<InstantiatedIntegerExpression>{*right->expression,
                                                                         *left->expression}
                               : ArrayRef<InstantiatedIntegerExpression>{*left->expression,
                                                                         *right->expression});
            auto actual = succeeded(difference) ? parameterExpression(*difference) : TypedAttr();
            if (actual)
              choice.condition = circt::hw::ParamExprAttr::get(circt::hw::PEO::ShrU, actual,
                                                               builder.getI32IntegerAttr(31));
            choice.pattern = builder.getI32IntegerAttr(code == "lt" || code == "gt" ? 1 : 0);
          }
          if (!choice.condition || !choice.pattern)
            return reject(
                &op, "generated guard comparison exceeds checked signed-i32 target arithmetic");
          choice.name =
              builder.getStringAttr("generated_guard_" + std::to_string(generatedChoices.size()));
          generatedChoices.try_emplace(&op, choice);
          staticOperations.insert(&op);
          staticOperations.insert(comparison);
          for (Region &region : op.getRegions())
            if (failed(planScope(region.front(), depth + 1)))
              return failure();
        } else if (selectedInstanceReads.count(&op)) {
          // The demanded original output and actual prefix were checked before family planning.
        } else if (isa<GeneratedMemberOp>(op)) {
          auto member = inspectGeneratedMember(&op);
          if (failed(member))
            return failure();
          generatedMembers.try_emplace(&op, *member);
        } else if (depth && isa<StaticCompareOp>(op)) {
          if (!llvm::all_of(op.getUsers(), [&](Operation *user) {
                return isa<StructuralIfOp>(user) && user->getOperand(0) == op.getResult(0);
              }))
            return reject(&op, "generated static predicate must feed its actual structural choice");
          staticOperations.insert(&op);
        } else if (depth) {
          if (op.getNumRegions() || op.getNumSuccessors())
            return reject(&op, "generated guard body requires an admitted scalar operation");
          if (isa<StructuralYieldOp>(op))
            continue;
          if (isa<ConstLiteralOp, ConstExprOp, ConstParameterRefOp>(op) &&
              op.getNumResults() == 1 && op.getResult(0).getType().isInteger(64)) {
            if (failed(verifyStructuralIntegerOperation(&op)))
              return failure();
            staticOperations.insert(&op);
            continue;
          }
          if (isa<DigitalAssignOp>(op)) {
            Value root = capturedValue(op.getOperand(0));
            auto pin = root ? root.getDefiningOp<PortValueOp>() : PortValueOp();
            Operation *port = pin ? SymbolTable::lookupSymbolIn(
                                        source, pin->getAttrOfType<FlatSymbolRefAttr>("port"))
                                  : nullptr;
            if (text(&op, "kind") != "continuous" || !port || text(port, "direction") != "output" ||
                !isa_and_nonnull<IntegerType, circt::hw::IntType>(targetValueType(root.getType())))
              return reject(&op,
                            "generated guarded write requires an exact ordinary scalar output");
            generatedGuardOutputs[port].push_back(&op);
          } else if (!isa<ConstantOp, ConstLiteralOp, SequentialSelectOp>(op) &&
                     !(isa<DigitalExprOp>(op) && scalarOpcode(text(&op, "operator_name"))))
            return reject(&op, "operation is outside the existing generated guard scalar profile");
          for (Type type : op.getResultTypes())
            if (!isa_and_nonnull<IntegerType>(targetValueType(type)) &&
                !generatedSymbolicValue(&op))
              return reject(&op,
                            "generated guard scalar requires exact fixed or bitwise/select type");
          generatedGuardOperations.insert(&op);
        }
        if (depth || isa<GenerateOp, StructuralIfOp, GeneratedMemberOp>(op)) {
          if (cost + 16 > maximumGeneratedOperations)
            return reject(&op, "generated guard adapters exceed finite target budget");
          cost += 16;
        }
      }
      return success();
    };
    if (failed(planScope(source.getBody().front(), 0)))
      return failure();
    // Inventory all authored families before asking the canonical assignment
    // owner once for original-template routing. Each root retains its actual lexical use, so
    // sibling and nested families are not merged semantically. There is no per-port or per-family
    // whole-module writer scan in this target adapter.
    SmallVector<std::pair<Value, Operation *>> connectionSeeds;
    SmallVector<GeneratedFamilyPlan *> connectionOwners;
    for (const auto &family : generatedFamilies) {
      if (family->connectionSeeds.size() > (maximumGeneratedOperations - cost) / 2)
        return reject(family->operation, "generated alias roots exceed finite target budget");
      cost += 2 * family->connectionSeeds.size();
      llvm::append_range(connectionSeeds, family->connectionSeeds);
      connectionOwners.append(family->connectionSeeds.size(), family.get());
    }
    if (!connectionSeeds.empty()) {
      auto assignments = inspectGeneratedTemplateAssignments(source, connectionSeeds);
      if (failed(assignments))
        return failure();
      for (auto [index, fact] : llvm::enumerate(*assignments)) {
        // These are verified authored-template aliases, not executable edges.
        // In particular an exact-empty family still has an authored helper
        // body, but no attained slot, driver or selected value is invented.
        auto *family = connectionOwners[index];
        Value value = capturedValue(fact.source);
        if (fact.root != connectionSeeds[index].first || fact.family != family->operation || !value)
          return reject(connectionSeeds[index].second,
                        "generated template alias lost its original root/family identity");
        family->aliases.try_emplace(fact.root, value);
      }
    }
    for (const auto &ownedFamily : generatedFamilies) {
      const auto &family = *ownedFamily;
      for (Operation *instance : family.instances) {
        const auto &child = hierarchy->variants[variant->children.lookup(instance)];
        for (const auto &port : child.ports) {
          if (port.inout || port.splitRoute)
            return reject(instance,
                          "generated resolved child requires exact physical lane routing");
          if (port.input) {
            auto references = instancePortValues.find({instance, port.key});
            if (references == instancePortValues.end() ||
                !family.aliases.count(references->second.front()))
              return reject(instance, "generated child input requires its canonical actual driver");
          }
        }
        for (const auto &port : child.domains)
          if (!instanceBindings.count({instance, port.requirement}))
            return reject(instance, "generated child control requires its exact actual binding");
      }
    }
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
    auto planRead = [&](Operation *operation, ArrayRef<GeneratedSelection> selections,
                        Value storage, InstanceLogicalPortKey key,
                        Operation *exportOperation) -> LogicalResult {
      if (selections.empty() || (!key.second.first && !generatedExports.count(exportOperation)))
        return reject(operation, "generated target selection requires an original scalar export");
      auto *plan = generatedFamilyPlans.lookup(selections.front().family);
      Operation *scope = plan && plan->parent ? plan->parent->operation : source.getOperation();
      Operation *owner = operation->getParentOp();
      while (isa<StructuralIfOp>(owner))
        owner = owner->getParentOp();
      if (!plan || owner != scope)
        return reject(operation, "generated target selection prefix has a foreign lexical owner");
      plan->selections.push_back(operation);
      unsigned outputIndex = 0;
      for (; outputIndex < plan->outputs.size(); ++outputIndex)
        if (plan->outputs[outputIndex].value == storage &&
            plan->outputs[outputIndex].instancePort == key)
          break;
      auto root = std::make_pair(plan, outputIndex);
      for (auto [i, selection] : llvm::enumerate(selections)) {
        if (plan->operation != selection.family || outputIndex >= plan->outputs.size() ||
            !parameterExpression(selection.coordinate))
          return reject(operation, "generated target selection must retain every exact prefix");
        const auto &out = plan->outputs[outputIndex];
        if (i + 1 == selections.size()) {
          if (out.child || out.value != storage || out.instancePort != key ||
              out.laneType != targetValueType(operation->getResult(0).getType()))
            return reject(operation, "generated target selection did not reach original storage");
        } else {
          if (!out.child)
            return reject(operation, "generated target selection is missing an exported family");
          plan = out.child;
          outputIndex = out.childOutput;
        }
      }
      generatedSelectionRoots.try_emplace(operation, root);
      auto structure = dyn_cast_if_present<circt::hw::StructType>(
          targetValueType(operation->getResult(0).getType()));
      uint64_t fields = structure ? structure.getElements().size() : 1;
      cost += 8 * fields * selections.size();
      if (cost > maximumGeneratedOperations)
        return reject(operation, "generated prefix adapters exceed finite target budget");
      return success();
    };
    for (auto &[operation, member] : generatedMembers) {
      if (member.familyView) {
        staticOperations.insert(operation);
        continue;
      }
      if (failed(
              planRead(operation, member.selections, member.storage, {}, member.exportOperation)))
        return failure();
    }
    for (auto &[operation, port] : selectedInstanceReads)
      if (failed(
              planRead(operation, port.proof.selections, {}, port.key, port.proof.exportOperation)))
        return failure();
    // Outline only authored arms that would otherwise require nested explicit
    // sv.generate wrappers. The pinned exporter cannot emit those legally.
    // Reverse preorder is postorder: every nested scope target exists first.
    for (auto &[operation, choice] : llvm::reverse(generatedChoices))
      for (unsigned arm = 0; arm != 2; ++arm) {
        Block *block = &operation->getRegion(arm).front();
        bool needsHelper = false;
        for (Operation &nested : *block) {
          if (failed(chargeInventory(&nested)))
            return failure();
          needsHelper |= isa<GenerateOp, StructuralIfOp>(nested);
        }
        if (!needsHelper)
          continue;
        auto plan = std::make_unique<GeneratedGuardScope>();
        plan->source = block;
        std::string base = text(source, "sym_name").str() + "__" + choice.name.getValue().str() +
                           "_arm_" + std::to_string(arm);
        plan->name = unique(base);
        llvm::SetVector<Value> captures;
        llvm::SetVector<Operation *> destinations;
        llvm::SetVector<std::pair<GeneratedFamilyPlan *, unsigned>> carriers;
        bool invalid = false;
        for (Operation &nested : *block) {
          auto walked = nested.walk<WalkOrder::PreOrder>([&](Operation *use) {
            if (failed(chargeInventory(use)))
              return WalkResult::interrupt();
            if (auto route = generatedSelectionRoots.find(use);
                route != generatedSelectionRoots.end()) {
              auto [family, index] = route->second;
              if (!generatedScopeContains(block, family->operation->getBlock()))
                carriers.insert({family, index});
            }
            if (isa<DigitalAssignOp>(use)) {
              auto projection = resolveStaticShapeProjection(use->getOperand(0), use);
              auto pin = succeeded(projection)
                             ? capturedValue(projection->root).getDefiningOp<PortValueOp>()
                             : PortValueOp();
              Operation *port = pin ? SymbolTable::lookupSymbolIn(
                                          source, pin->getAttrOfType<FlatSymbolRefAttr>("port"))
                                    : nullptr;
              auto writes = generatedGuardOutputs.find(port);
              if (writes != generatedGuardOutputs.end() && llvm::is_contained(writes->second, use))
                destinations.insert(port);
            }
            for (auto [index, operand] : llvm::enumerate(use->getOperands())) {
              if (isa<StructuralIfOp>(use))
                continue; // Walk actual arm uses, not sink-only capture operands.
                          // Static predicates are transported as parameters.
              if (isa<DigitalAssignOp>(use) && index == 0 && generatedGuardOperations.contains(use))
                continue;
              Value value = capturedValue(operand);
              if (!value) {
                invalid = true;
                continue;
              }
              if (generatedScopeContains(block, value.getParentBlock()) ||
                  isa<GeneratedFamilyType>(value.getType()) || value.getType().isInteger(64))
                continue;
              if (!targetValueType(value.getType()) || !value.getDefiningOp()) {
                invalid = true;
                continue;
              }
              captures.insert(value);
            }
            return WalkResult::advance();
          });
          if (walked.wasInterrupted())
            return failure();
        }
        if (invalid)
          return reject(operation, "generated guard scope lacks an exact material lexical capture");
        llvm::SetVector<GeneratedFamilyPlan *> busFamilies;
        for (Operation &nested : *block) {
          if (failed(chargeInventory(&nested)))
            return failure();
          if (auto *family = generatedFamilyPlans.lookup(&nested))
            busFamilies.insert(family);
        }
        for (auto [family, index] : carriers)
          busFamilies.insert(family);
        llvm::append_range(plan->busFamilies, busFamilies);
        for (Value value : captures) {
          if (auto pin = value.getDefiningOp<PortValueOp>())
            if (destinations.contains(SymbolTable::lookupSymbolIn(
                    source, pin->getAttrOfType<FlatSymbolRefAttr>("port"))))
              continue; // Exact destination buses are helper outputs, never captures.
          appendGeneratedPort(plan->ports, value, true, plan->ports.size(),
                              targetValueType(value.getType()));
          plan->carriers.emplace_back(nullptr, 0);
        }
        for (auto [family, index] : carriers) {
          const auto &out = family->outputs[index];
          appendGeneratedOutputPort(plan->ports, out, true, plan->ports.size(), out.busType);
          plan->carriers.emplace_back(family, index);
        }
        for (Operation *destination : destinations) {
          Operation *write = generatedGuardOutputs[destination].front();
          auto projection = resolveStaticShapeProjection(write->getOperand(0), write);
          if (failed(projection))
            return reject(write, "generated guard output lost its exact logical root");
          Value reference = capturedValue(projection->root);
          appendGeneratedPort(plan->ports, reference, false, plan->destinations.size(),
                              targetValueType(reference.getType()));
          plan->destinations.push_back(destination);
        }
        if (cost + 8 * plan->ports.size() + targetParameters.size() > maximumGeneratedOperations)
          return reject(operation, "generated guard scope adapters exceed target budget");
        cost += 8 * plan->ports.size() + targetParameters.size();
        choice.arms[arm] = plan.get();
        generatedGuardScopes.push_back(std::move(plan));
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
  Type generatedBusType(Type lane, TypedAttr capacity) {
    if (auto structure = dyn_cast_if_present<circt::hw::StructType>(lane)) {
      SmallVector<circt::hw::StructType::FieldInfo> fields;
      for (const auto &field : structure.getElements()) {
        Type bus = generatedBusType(field.type, capacity);
        if (!bus)
          return {};
        fields.push_back({field.name, bus});
      }
      return circt::hw::StructType::get(builder.getContext(), fields);
    }
    TypedAttr width = generatedWidth(lane);
    if (!width)
      return {};
    TypedAttr bits = circt::hw::ParamExprAttr::get(circt::hw::PEO::Mul, capacity, width);
    return isa<IntegerAttr>(bits) ? Type(builder.getIntegerType(cast<IntegerAttr>(bits).getInt()))
                                  : Type(circt::hw::IntType::get(bits));
  }
  Value generatedSlice(Operation *at, Value input, Value index, Type result, bool bitOffset = false,
                       TypedAttr lower = {}, TypedAttr step = {}) {
    if (auto structure = dyn_cast<circt::hw::StructType>(result)) {
      auto inputType = dyn_cast<circt::hw::StructType>(input.getType());
      if (!inputType || inputType.getElements().size() != structure.getElements().size() ||
          bitOffset)
        return {}; // A selected nested route computes a separate exact offset per leaf.
      auto leaves = splitBoundary(at, input);
      SmallVector<Value> selected;
      for (auto [i, field] : llvm::enumerate(structure.getElements())) {
        if (inputType.getElements()[i].name != field.name)
          return {};
        Value value = generatedSlice(at, leaves[i], index, field.type, false, lower, step);
        if (!value)
          return {};
        selected.push_back(value);
      }
      return joinBoundary(at, result, selected);
    }
    Type element = result;
    if (auto inout = dyn_cast<circt::hw::InOutType>(element))
      element = inout.getElementType();
    TypedAttr width = generatedWidth(element);
    if (!width)
      return {};
    // A parameter value may be outlined as a runtime wire. A localparam keeps
    // the slice width an elaboration constant through the actual SV consumer.
    Value stride, lowerValue, stepValue;
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
      if (lower && step) {
        lowerValue = make(at, "sv.localparam", builder.getI32Type(), {},
                          {builder.getNamedAttr("value", lower),
                           builder.getNamedAttr("name", builder.getStringAttr(""))});
        stepValue = make(at, "sv.localparam", builder.getI32Type(), {},
                         {builder.getNamedAttr("value", step),
                          builder.getNamedAttr("name", builder.getStringAttr(""))});
      }
    }
    SmallVector<Value> operands{input, index, stride};
    if (lower && step)
      llvm::append_range(operands, ArrayRef<Value>{lowerValue, stepValue});
    // Keep the actual genvar in the constant part-select. A comb.div/sub value
    // would be outlined as a runtime wire and lose elaboration-constant status.
    StringRef format = lower && step ? "{{0}}[(({{1}} - {{3}}) / {{4}}) * {{2}} +: {{2}}]"
                       : bitOffset   ? "{{0}}[{{1}} +: {{2}}]"
                                     : "{{0}}[{{1}} * {{2}} +: {{2}}]";
    return make(at, "sv.verbatim.expr", result, operands,
                {builder.getNamedAttr("format_string", builder.getStringAttr(format)),
                 builder.getNamedAttr("symbols", builder.getArrayAttr({}))});
  }
  LogicalResult lowerGeneratedHelper(GeneratedFamilyPlan &family) {
    OpBuilder::InsertionGuard insertion(builder);
    auto parentValues = std::move(values);
    auto parentInstanceValues = std::move(generatedInstanceValues);
    values.clear();
    generatedInstanceValues.clear();
    auto restoreValues = llvm::scope_exit([&] {
      values = std::move(parentValues);
      generatedInstanceValues = std::move(parentInstanceValues);
    });
    SmallVector<circt::hw::PortInfo> ports;
    unsigned inputIndex = 0, outputIndex = 0;
    appendPhysicalPorts(family.ports, ports, inputIndex, outputIndex);
    SmallVector<Attribute> parameters(targetParameters.begin(), targetParameters.end());
    for (auto *ancestor = family.parent; ancestor; ancestor = ancestor->parent)
      parameters.push_back(
          circt::hw::ParamDeclAttr::get(ancestor->indexName, builder.getI32IntegerAttr(0)));
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
    for (auto *ancestor = &family; ancestor; ancestor = ancestor->parent)
      values[ancestor->domain.induction] = circt::hw::ParamValueOp::create(
          builder, family.operation->getLoc(), builder.getI32Type(),
          circt::hw::ParamDeclRefAttr::get(ancestor->indexName, builder.getI32Type()));
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
    for (auto [original, alias] : family.aliases) {
      Value actual = values.lookup(alias);
      if (!actual) {
        backedges.abandon();
        return reject(original.getDefiningOp(), "generated canonical RHS did not map");
      }
      values[original] = actual;
    }
    for (Operation *instance : family.instances)
      if (failed(lowerInstance(instance))) {
        backedges.abandon();
        return failure();
      }
    if (failed(lowerGeneratedScope(&family))) {
      backedges.abandon();
      return failure();
    }
    for (Operation *op : family.operations) {
      if (!op->getNumResults() ||
          isa<DigitalStorageOp, StaticCompareOp, InstancePortOp, MemberAccessOp>(op) ||
          family.sinks.contains(op))
        continue;
      Value result;
      if (isa<ShapeIndexOp>(op)) {
        const auto &route = family.slices.find(op)->second;
        auto carriers = splitBoundary(op, mapped(route.root));
        Type resultType = targetValueType(op->getResult(0).getType());
        auto structure = dyn_cast<circt::hw::StructType>(resultType);
        SmallVector<Value> leaves;
        for (auto [leaf, carrier] : llvm::enumerate(carriers)) {
          Type type = structure ? structure.getElements()[leaf].type : resultType;
          Value offset =
              circt::sv::LocalParamOp::create(builder, op->getLoc(), builder.getI32Type(),
                                              route.offsets[leaf], builder.getStringAttr(""));
          leaves.push_back(generatedSlice(op, carrier, offset, type, true));
        }
        result = joinBoundary(op, resultType, leaves);
      } else {
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
    for (const auto &out : family.outputs) {
      Value value = generatedOutputValue(out);
      if (!value || value.getType() != out.laneType) {
        backedges.abandon();
        return reject(family.operation,
                      "generated logical output did not map its original payload");
      }
      llvm::append_range(outputs, splitBoundary(family.operation, value));
    }
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
  LogicalResult lowerGeneratedGuardHelper(GeneratedGuardScope &scope) {
    OpBuilder::InsertionGuard insertion(builder);
    auto parentValues = std::move(values);
    auto parentWires = std::move(generatedGuardWires);
    SmallVector<SmallVector<Value>> parentBuses;
    for (auto *family : scope.busFamilies)
      parentBuses.push_back(std::move(family->buses));
    values.clear();
    generatedGuardWires.clear();
    auto restore = llvm::scope_exit([&] {
      values = std::move(parentValues);
      generatedGuardWires = std::move(parentWires);
      for (auto [family, buses] : llvm::zip(scope.busFamilies, parentBuses))
        family->buses = std::move(buses);
    });
    SmallVector<circt::hw::PortInfo> ports;
    unsigned inputIndex = 0, outputIndex = 0;
    appendPhysicalPorts(scope.ports, ports, inputIndex, outputIndex);
    Operation *owner = scope.source->getParentOp();
    builder.setInsertionPoint(source);
    scope.target = circt::hw::HWModuleOp::create(
        builder, owner->getLoc(), scope.name, circt::hw::ModulePortInfo(ports), targetParameters);
    correlate(scope.target, owner);
    scope.target->setAttr("nodal.generated_guard_scope", owner->getAttr("region_id"));
    scope.target->setAttr(
        "nodal.generated_guard_arm",
        builder.getI32IntegerAttr(scope.source == &owner->getRegion(0).front() ? 0 : 1));
    Block *body = scope.target.getBodyBlock();
    builder.setInsertionPoint(body->getTerminator());
    inputIndex = 0;
    unsigned logicalInput = 0;
    for (const auto &port : scope.ports)
      if (port.input) {
        SmallVector<Value> leaves;
        for (unsigned i = 0; i != port.pins.size(); ++i)
          leaves.push_back(body->getArgument(inputIndex++));
        Value value = joinBoundary(port.key.first, port.targetType, leaves);
        auto [family, index] = scope.carriers[logicalInput++];
        if (family) {
          family->buses.resize(family->outputs.size());
          family->buses[index] = value;
        } else
          values[port.references.front()] = value;
      }
    SmallVector<Value> outputs;
    for (Operation *destination : scope.destinations) {
      Type type = targetValueType(destination->getAttrOfType<TypeAttr>("type").getValue());
      Value wire = make(destination, "sv.wire", circt::hw::InOutType::get(type), {},
                        {builder.getNamedAttr("name", builder.getStringAttr(""))});
      generatedGuardWires.try_emplace(destination, wire);
      llvm::append_range(
          outputs, splitBoundary(destination, make(destination, "sv.read_inout", type, {wire})));
    }
    if (failed(lowerGeneratedScope(nullptr, scope.source)))
      return failure();
    body->getTerminator()->setOperands(outputs);
    return success();
  }
  LogicalResult lowerGeneratedGuardInstance(GeneratedGuardScope &scope) {
    Operation *owner = scope.source->getParentOp();
    SmallVector<Value> inputs;
    unsigned logicalInput = 0;
    for (const auto &port : scope.ports)
      if (port.input) {
        auto [family, index] = scope.carriers[logicalInput++];
        Value value = family ? family->buses[index] : values.lookup(port.references.front());
        if (!value)
          return reject(owner, "generated guard scope capture did not map");
        llvm::append_range(inputs, splitBoundary(port.key.first, value));
      }
    SmallVector<Attribute> parameters;
    for (Attribute attr : targetParameters) {
      auto declaration = cast<circt::hw::ParamDeclAttr>(attr);
      parameters.push_back(circt::hw::ParamDeclAttr::get(
          declaration.getName(),
          circt::hw::ParamDeclRefAttr::get(declaration.getName(), builder.getI32Type())));
    }
    auto instance = circt::hw::InstanceOp::create(
        builder, owner->getLoc(), scope.target, builder.getStringAttr("scope"),
        ArrayRef<Value>(inputs), builder.getArrayAttr(parameters));
    correlate(instance, owner);
    for (auto [index, destination] : llvm::enumerate(scope.destinations))
      assignGenerated(owner, generatedGuardWires.lookup(destination), instance.getResult(index));
    return success();
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
    for (const auto &[port, writes] : generatedGuardOutputs) {
      Type type = targetValueType(port->getAttrOfType<TypeAttr>("type").getValue());
      Value wire = make(port, "sv.wire", circt::hw::InOutType::get(type), {},
                        {builder.getNamedAttr("name", builder.getStringAttr(""))});
      generatedGuardWires.try_emplace(port, wire);
      Value read = make(port, "sv.read_inout", type, {wire});
      for (Value reference : portValues[port])
        values[reference] = read;
    }
    return lowerGeneratedScope(nullptr);
  }
  LogicalResult lowerGeneratedScope(GeneratedFamilyPlan *parent, Block *scope = nullptr) {
    if (!scope)
      scope = parent ? &parent->operation->getRegion(0).front() : &source.getBody().front();
    circt::BackedgeBuilder guardEdges(builder, scope->getParentOp()->getLoc());
    auto abandonEdges = llvm::scope_exit([&] { guardEdges.abandon(); });
    SmallVector<std::pair<Value, circt::Backedge>> guardProducers;
    if (!parent)
      for (Operation &op : *scope)
        if (generatedGuardOperations.contains(&op))
          for (Value value : op.getResults()) {
            auto edge = guardEdges.get(targetValueType(value.getType()), op.getLoc());
            values[value] = edge;
            guardProducers.emplace_back(value, edge);
          }
    for (Operation &operation : *scope) {
      auto *owned = generatedFamilyPlans.lookup(&operation);
      if (!owned)
        continue;
      auto &family = *owned;
      if (family.parent != parent)
        return reject(&operation, "generated scope lost its exact family plan owner");
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
        for (auto *ancestor = family.parent; ancestor; ancestor = ancestor->parent)
          parameters.push_back(circt::hw::ParamDeclAttr::get(
              ancestor->indexName,
              circt::hw::ParamDeclRefAttr::get(ancestor->indexName, builder.getI32Type())));
        parameters.push_back(circt::hw::ParamDeclAttr::get(
            family.indexName, circt::hw::ParamVerbatimAttr::get(
                                  builder.getContext(), family.genvarName, builder.getI32Type())));
        // Materialize the actual lvalues before their producer instance. This
        // preserves direct output-port connectivity even without optimization;
        // no dependency, ordering token or extra instance is manufactured.
        SmallVector<Value> outputDestinations;
        for (auto [i, out] : llvm::enumerate(family.outputs)) {
          SmallVector<std::pair<Value, Type>> destinations;
          if (auto structure = dyn_cast<circt::hw::StructType>(out.laneType)) {
            auto bus = cast<circt::hw::StructType>(out.busType);
            for (auto [fieldIndex, field] : llvm::enumerate(structure.getElements())) {
              Value wire = make(family.operation, "sv.struct_field_inout",
                                circt::hw::InOutType::get(bus.getElements()[fieldIndex].type),
                                {wires[i]}, {builder.getNamedAttr("field", field.name)});
              destinations.emplace_back(wire, field.type);
            }
          } else
            destinations.emplace_back(wires[i], out.laneType);
          for (auto [wire, laneType] : destinations) {
            Value destination =
                generatedSlice(family.operation, wire, index, circt::hw::InOutType::get(laneType),
                               false, family.ordinalTransport ? family.lower : TypedAttr(),
                               family.ordinalTransport ? family.step : TypedAttr());
            outputDestinations.push_back(destination);
          }
        }
        auto instance = circt::hw::InstanceOp::create(
            builder, family.operation->getLoc(), family.target, builder.getStringAttr("lane"),
            ArrayRef<Value>(inputs), builder.getArrayAttr(parameters));
        correlate(instance, family.operation);
        if (outputDestinations.size() != instance.getNumResults())
          return reject(family.operation, "generated helper lost its exact physical output count");
        for (auto [result, destination] : llvm::enumerate(outputDestinations))
          assignGenerated(family.operation, destination, instance.getResult(result));
      }
      for (auto [i, out] : llvm::enumerate(family.outputs)) {
        if (parent) {
          if (out.instancePort.second.first)
            generatedInstanceValues[out.instancePort] = buses[i];
          else
            values[out.value] = buses[i];
        }
        for (Value destination : out.destinations) {
          auto pin = destination.getDefiningOp<PortValueOp>();
          if (pin) {
            Operation *port =
                SymbolTable::lookupSymbolIn(source, pin->getAttrOfType<FlatSymbolRefAttr>("port"));
            if (Value wire = generatedGuardWires.lookup(port))
              assignGenerated(family.operation, wire, buses[i]);
            else
              for (Value reference : portValues[port])
                values[reference] = buses[i];
          } else {
            values[destination] = buses[i];
          }
        }
      }
      family.buses = std::move(buses);
    }
    for (Operation &selectedOperation : *scope) {
      auto found = generatedMembers.find(&selectedOperation);
      auto instance = selectedInstanceReads.find(&selectedOperation);
      if ((found != generatedMembers.end() && !found->second.familyView) ||
          instance != selectedInstanceReads.end()) {
        Operation *operation = &selectedOperation;
        ArrayRef<GeneratedSelection> selections = instance != selectedInstanceReads.end()
                                                      ? ArrayRef(instance->second.proof.selections)
                                                      : ArrayRef(found->second.selections);
        auto route = generatedSelectionRoots.find(operation);
        if (route == generatedSelectionRoots.end())
          return reject(operation, "generated selection lost its preflight carrier route");
        auto [plan, selected] = route->second;
        if (selected >= plan->buses.size() || !plan->buses[selected])
          return reject(operation, "generated selection carrier did not map in its exact scope");
        Value bus = plan->buses[selected];
        auto rootStructure = dyn_cast<circt::hw::StructType>(bus.getType());
        auto carriers = splitBoundary(operation, bus);
        SmallVector<Value> leaves;
        for (auto [fieldIndex, carrier] : llvm::enumerate(carriers)) {
          auto *current = plan;
          unsigned output = selected;
          TypedAttr offset = builder.getI32IntegerAttr(0);
          SmallVector<Attribute> prefixes;
          Type selectedType;
          for (const auto &selection : selections) {
            const auto &out = current->outputs[output];
            TypedAttr coordinate = parameterExpression(selection.coordinate);
            auto ordinal = resolveStructuralIntegerExpression(selection.ordinal, operation);
            TypedAttr position = coordinate;
            if (current->ordinalTransport) {
              if (failed(ordinal) || !ordinal->expression)
                return reject(operation,
                              "generated transport ordinal lost its exact Integer proof");
              position = parameterExpression(*ordinal->expression);
            }
            if (!position)
              return reject(operation, "generated transport ordinal lost its exact Integer proof");
            selectedType = out.laneType;
            if (rootStructure) {
              auto structure = dyn_cast<circt::hw::StructType>(selectedType);
              if (!structure || fieldIndex >= structure.getElements().size() ||
                  structure.getElements()[fieldIndex].name !=
                      rootStructure.getElements()[fieldIndex].name)
                return reject(operation, "generated selection lost its exact logical field route");
              selectedType = structure.getElements()[fieldIndex].type;
            }
            TypedAttr stride = generatedWidth(selectedType);
            if (!stride)
              return reject(operation, "generated selected leaf lacks its exact physical stride");
            offset = circt::hw::ParamExprAttr::get(
                circt::hw::PEO::Add, offset,
                circt::hw::ParamExprAttr::get(circt::hw::PEO::Mul, position, stride));
            prefixes.push_back(builder.getDictionaryAttr(
                {builder.getNamedAttr("family", selection.family->getAttr("region_id")),
                 builder.getNamedAttr("coordinate", coordinate),
                 builder.getNamedAttr("ordinal", position),
                 builder.getNamedAttr("stride_bits", stride)}));
            current = out.child;
            output = out.childOutput;
          }
          Value index =
              circt::sv::LocalParamOp::create(builder, operation->getLoc(), builder.getI32Type(),
                                              offset, builder.getStringAttr(""));
          Value value = generatedSlice(operation, carrier, index, selectedType, true);
          value.getDefiningOp()->setAttr("nodal.generated_selection",
                                         builder.getArrayAttr(prefixes));
          leaves.push_back(value);
        }
        values[operation->getResult(0)] =
            joinBoundary(operation, targetValueType(operation->getResult(0).getType()), leaves);
      }
    }
    if (!parent)
      for (Operation &op : *scope) {
        auto choice = generatedChoices.find(&op);
        if (choice != generatedChoices.end()) {
          OpBuilder::InsertionGuard insertion(builder);
          auto *wrapper = regionOperation(&op, "sv.generate",
                                          {builder.getNamedAttr("sym_name", choice->second.name)});
          builder.setInsertionPointToEnd(&wrapper->getRegion(0).front());
          auto *branch = regionOperation(
              &op, "sv.generate.case",
              {builder.getNamedAttr("cond", choice->second.condition),
               builder.getNamedAttr("casePatterns", builder.getArrayAttr({choice->second.pattern,
                                                                          builder.getUnitAttr()})),
               builder.getNamedAttr("caseNames", builder.getStrArrayAttr({"matched", "other"}))},
              2);
          for (unsigned arm = 0; arm != 2; ++arm) {
            unsigned targetArm = (arm == 0) == choice->second.matchIsThen ? 0 : 1;
            builder.setInsertionPointToEnd(&branch->getRegion(targetArm).front());
            if (auto *scopePlan = choice->second.arms[arm]) {
              if (failed(lowerGeneratedGuardInstance(*scopePlan)))
                return failure();
            } else if (failed(lowerGeneratedScope(nullptr, &op.getRegion(arm).front())))
              return failure();
          }
        } else if (generatedGuardOperations.contains(&op)) {
          if (isa<DigitalAssignOp>(op)) {
            auto pin = capturedValue(op.getOperand(0)).getDefiningOp<PortValueOp>();
            Operation *port =
                SymbolTable::lookupSymbolIn(source, pin->getAttrOfType<FlatSymbolRefAttr>("port"));
            Value value = values.lookup(capturedValue(op.getOperand(1)));
            if (!value)
              return reject(&op, "generated guarded write lost its actual RHS mapping");
            assignGenerated(&op, generatedGuardWires.lookup(port), value);
          } else {
            SmallVector<Value> inputs;
            for (Value operand : op.getOperands())
              inputs.push_back(values.lookup(capturedValue(operand)));
            if (llvm::any_of(inputs, [](Value value) { return !value; }))
              return reject(&op, "generated guarded scalar operand lost its lexical capture");
            Value value = lowerValue(&op, inputs);
            if (!value)
              return failure();
            values[op.getResult(0)] = value;
          }
        }
      }
    for (auto &[original, edge] : guardProducers) {
      Value result = values.lookup(original);
      if (!result || result == Value(edge))
        return reject(original.getDefiningOp(), "generated guarded producer did not map");
      edge.setValue(result);
    }
    return guardEdges.clearOrEmitError();
  }

  SmallVector<std::pair<Value, Value>> splitValues;
  SmallVector<Type> splitTypes, splitEnableTypes;
  llvm::DenseMap<std::pair<Operation *, unsigned>, unsigned> splitChildInputs, splitChildOutputs;
  LogicalResult planSplitPorts() {
    if (!hierarchy || !variant)
      return success();
    const auto &plan = hierarchy->splitPlans[variant->occurrence];
    auto occurrences = hierarchy->proof->occurrences();
    splitTypes.resize(plan.routes.size());
    splitEnableTypes.resize(plan.routes.size());
    for (unsigned raw : plan.ordered) {
      const auto &route = plan.routes[raw];
      Type mapped = targetValueType(route.originalType);
      if (!isa_and_nonnull<IntegerType, circt::hw::IntType>(mapped))
        return reject(source, "split route lost its exact local scalar type mapping");
      Type enableType = targetValueType(route.enableType);
      if (!isa_and_nonnull<IntegerType, circt::hw::IntType>(enableType))
        return reject(source, "split route lost exact local pointwise enable mapping");
      splitTypes[raw] = mapped;
      splitEnableTypes[raw] = enableType;
      if (8 > maximumGeneratedOperations - cost)
        return reject(source, "split route transport exceeds target operation budget");
      cost += 8;
      if (route.sourceChild) {
        unsigned canonical = hierarchy->splitPlans[route.sourceChildOccurrence]
                                 .routes[route.sourceChildRoute]
                                 .canonical;
        if (!splitChildOutputs.try_emplace(std::make_pair(route.sourceChild, canonical), raw)
                 .second)
          return reject(source, "split child output has conflicting routes");
      }
      for (auto edge : route.destinations) {
        unsigned canonical = hierarchy->splitPlans[edge.first].routes[edge.second].canonical;
        if (!splitChildInputs
                 .try_emplace(std::make_pair(occurrences[edge.first].instance, canonical), raw)
                 .second)
          return reject(source, "split child input has conflicting routes");
      }
      if (!route.incoming && !route.outgoing)
        continue;
      for (bool enable : {false, true}) {
        LogicalPortPlan port;
        std::string name =
            "__nodal_split_" + std::to_string(route.canonical) + (enable ? "_enable" : "_data");
        port.key = {source.getOperation(), builder.getStringAttr(name)};
        port.name = builder.getStringAttr(name);
        port.originalType = enable ? route.enableType : route.originalType;
        port.targetType = enable ? enableType : mapped;
        port.input = route.incoming;
        port.splitRoute = raw;
        port.splitEnable = enable;
        logicalPorts.push_back(std::move(port));
      }
    }
    return success();
  }
  LogicalResult planResolved() {
    if (!hierarchy || !variant)
      return success();
    bool checkedParametricInOut = false;
    llvm::DenseMap<Value, unsigned> byRepresentative;
    for (const auto *endpoint : hierarchy->resolvedEndpoints[variant->occurrence]) {
      auto geometry = inspectResolvedPhysicalGeometry(*endpoint, source);
      if (failed(geometry))
        return reject(endpoint->operation,
                      "resolved physical target requires exact ordinary endpoint geometry");
      auto resolved = dyn_cast_or_null<ResolvedType>(endpoint->type);
      Type type = targetValueType(geometry->data);
      Type laneType = targetValueType(geometry->payload);
      Type enableType = targetValueType(geometry->enable);
      auto maximum = inferDigitalBitPatternMaximumBits(geometry->data, source);
      if (!resolved || !resolvedScalar(resolved.getElementType()) ||
          !isa_and_nonnull<IntegerType, circt::hw::IntType>(type) ||
          !isa_and_nonnull<IntegerType, circt::hw::IntType>(laneType) ||
          !isa_and_nonnull<IntegerType, circt::hw::IntType>(enableType) || failed(maximum) ||
          *maximum <= 0 || *maximum > maximumWidth || !endpoint->mode ||
          endpoint->mode.getValue() != resolved.getDriveMode() || !endpoint->profile ||
          endpoint->profile.getValue() != "portable_boundary_only" ||
          (endpoint->placement && endpoint->placement.getValue() != "top_level_pin" &&
           endpoint->placement.getValue() != "black_box_pin"))
        return reject(
            endpoint->operation,
            "resolved physical target requires exact bounded scalar portable boundary pins");
      if (isa<circt::hw::IntType>(type) && !checkedParametricInOut) {
        // Probe the linked consumer, not a path or manifest. An overridden
        // runtime library must not silently invalidate a managed build's ABI.
        auto formal =
            circt::hw::ParamDeclRefAttr::get(builder.getStringAttr("formal"), builder.getI32Type());
        auto actual =
            circt::hw::ParamDeclRefAttr::get(builder.getStringAttr("actual"), builder.getI32Type());
        auto wrapper = circt::hw::InOutType::get(circt::hw::IntType::get(formal));
        auto bindings = builder.getArrayAttr(
            {circt::hw::ParamDeclAttr::get(builder.getStringAttr("formal"), actual)});
        auto substituted = circt::hw::evaluateParametricType(endpoint->operation->getLoc(),
                                                             bindings, wrapper, false);
        if (!circt::hw::isParametricType(wrapper) || failed(substituted) ||
            *substituted != circt::hw::InOutType::get(circt::hw::IntType::get(actual)))
          return reject(endpoint->operation,
                        "symbolic resolved transport requires recursive CIRCT InOut parameter "
                        "substitution; bootstrap --overlay circt-inout-parameters-v1 and "
                        "rebuild/select that managed toolchain prefix");
        checkedParametricInOut = true;
      }
      Attribute path = endpoint->path && !endpoint->path.getValue().empty()
                           ? Attribute(endpoint->path)
                           : Attribute();
      LogicalPortKey key{endpoint->operation, path};
      unsigned index = resolvedLocalComponents.size();
      if (endpoint->component) {
        auto found = byRepresentative.try_emplace(endpoint->component, index);
        index = found.first->second;
      }
      if (index == resolvedLocalComponents.size()) {
        ResolvedLocalComponent component;
        component.representative = endpoint->component;
        component.type = type;
        component.laneType = laneType;
        component.enableType = enableType;
        component.geometry = endpoint;
        component.originalData = geometry->data;
        component.mode = endpoint->mode;
        component.profile = endpoint->profile;
        resolvedLocalComponents.push_back(std::move(component));
      }
      auto &component = resolvedLocalComponents[index];
      if (component.type != type || component.laneType != laneType ||
          component.enableType != enableType ||
          !equivalentDigitalStorageTypesAt(component.originalData, component.geometry->operation,
                                           geometry->data, endpoint->operation) ||
          component.mode != endpoint->mode || component.profile != endpoint->profile ||
          !resolvedEndpointComponents.try_emplace(key, index).second)
        return reject(endpoint->operation,
                      "resolved local endpoint identity or exact type disagrees");
      resolvedEndpointFacts.try_emplace(key, endpoint);
      LogicalPortKey declaration{endpoint->declaration, path};
      if (endpoint->instance) {
        InstanceLogicalPortKey child{endpoint->instance, declaration};
        if (!projectedResolvedPorts.insert(child).second)
          return reject(endpoint->operation,
                        "resolved child endpoint must retain one canonical projection");
        component.childPins.push_back(child);
      } else
        component.localPins.push_back(declaration);
      if (isa<ResolvedNetOp, ResolvedBoundaryOp>(endpoint->operation)) {
        component.values.push_back(endpoint->operation->getResult(0));
        resolvedValueComponents.try_emplace(endpoint->operation->getResult(0), index);
      }
      if (uint64_t(*maximum) + 5 > maximumGeneratedOperations - cost)
        return reject(endpoint->operation,
                      "resolved physical carrier exceeds target operation budget");
      cost += *maximum + 5;
    }
    for (Operation &op : source.getBody().front()) {
      bool resolved =
          llvm::any_of(op.getOperandTypes(), [](Type t) { return bool(resolvedCarrierLeaf(t)); }) ||
          llvm::any_of(op.getResultTypes(), [](Type t) { return bool(resolvedCarrierLeaf(t)); });
      if (!resolved)
        continue;
      if (!isa<ResolvedNetOp, ResolvedBoundaryOp, MemberAccessOp, NetDriverOp, NetReadOp,
               NetDriveOp, NetDriveLevelOp, NetReleaseOp, NetPassThroughOp, NetSplitOp,
               InterfaceDriveOp, InterfaceConnectOp, InterfaceObserveOp>(op))
        return reject(&op, "resolved target requires an exact supported capability consumer");
      resolvedOperations.insert(&op);
      if (!isa<MemberAccessOp>(op))
        continue;
      auto reference = op.getAttrOfType<FlatSymbolRefAttr>("instance");
      Operation *endpoint = reference ? SymbolTable::lookupSymbolIn(source, reference) : nullptr;
      auto path = op.getAttrOfType<StringAttr>("path");
      auto found = resolvedEndpointComponents.find({endpoint, path});
      auto facts = resolvedEndpointFacts.find({endpoint, path});
      auto geometry = facts == resolvedEndpointFacts.end()
                          ? FailureOr<ResolvedPhysicalGeometry>(failure())
                          : inspectResolvedPhysicalGeometry(*facts->second, source);
      if (found == resolvedEndpointComponents.end() || failed(geometry) ||
          op.getResult(0).getType() != geometry->carrier)
        return reject(&op, "resolved member view lost its exact checked endpoint type");
      resolvedLocalComponents[found->second].values.push_back(op.getResult(0));
      resolvedValueComponents.try_emplace(op.getResult(0), found->second);
      LogicalPortKey key{facts->second->declaration, path};
      if (facts->second->instance)
        instancePortValues[{facts->second->instance, key}].push_back(op.getResult(0));
      else
        interfacePortValues[key].push_back(op.getResult(0));
    }
    return success();
  }
  DictionaryAttr resolvedContract(Operation *operation, Attribute path, unsigned index) {
    const auto *facts = resolvedEndpointFacts.find({operation, path})->second;
    const auto &component = resolvedLocalComponents[index];
    NamedAttrList record;
    record.set("mode", facts->mode);
    record.set("profile", facts->profile);
    if (facts->placement)
      record.set("placement", facts->placement);
    if (facts->domain)
      record.set("domain", facts->domain);
    record.set("width", generatedWidth(component.type));
    record.set("outer_dimensions", facts->shape.dimensions);
    record.set("lane_width", generatedWidth(component.laneType));
    record.set("lane_count", generatedWidth(component.enableType));
    record.set("layout", builder.getStringAttr("row-major-lanes-lsb-first"));
    record.set("member", facts->path);
    record.set("carrier", builder.getStringAttr("true-inout"));
    return builder.getDictionaryAttr(record);
  }
  LogicalResult joinResolvedPins(Operation *sourceOp, Value left, Value right, StringAttr name) {
    auto type = dyn_cast<circt::hw::InOutType>(left.getType());
    Type element = type ? type.getElementType() : Type();
    auto bits = dyn_cast_or_null<IntegerType>(element);
    if (!isa_and_nonnull<IntegerType, circt::hw::IntType>(element) ||
        left.getType() != right.getType() ||
        (bits && (!bits.getWidth() || bits.getWidth() > maximumWidth)))
      return reject(sourceOp, "electrical alias requires two exact equal-width InOut operands");
    // sv.alias is the native semantic equivalent, but pinned Icarus cannot parse
    // that construct. Only this fixed standard primitive is rendered as text.
    // Both nets remain typed SSA operands; no authored name or text is interpolated.
    std::string primitive = "tran";
    SmallVector<Value, 3> operands{left, right};
    if (!bits) {
      TypedAttr last = circt::hw::ParamExprAttr::get(circt::hw::PEO::Add, generatedWidth(element),
                                                     builder.getI32IntegerAttr(-1));
      operands.push_back(circt::sv::LocalParamOp::create(
          builder, sourceOp->getLoc(), builder.getI32Type(), last, builder.getStringAttr("")));
      primitive += " " + name.getValue().str() + "[{{2}}:0]";
    } else if (bits.getWidth() != 1)
      primitive += " " + name.getValue().str() + "[" + std::to_string(bits.getWidth() - 1) + ":0]";
    primitive += " ({{0}}, {{1}});";
    auto alias =
        circt::sv::VerbatimOp::create(builder, sourceOp->getLoc(), builder.getStringAttr(primitive),
                                      operands, builder.getArrayAttr({}));
    correlate(alias, sourceOp);
    alias->setAttr("nodal.resolved_alias", builder.getStringAttr("checked-local-component"));
    return success();
  }
  LogicalResult lowerResolvedCarriers() {
    for (auto &component : resolvedLocalComponents) {
      Value carrier;
      unsigned ordinal = 0;
      for (const auto &key : component.localPins) {
        auto found = resolvedPortCarriers.find(key);
        if (found == resolvedPortCarriers.end())
          return reject(key.first, "complete resolved local pin was not physically materialized");
        if (!carrier)
          carrier = found->second;
        else if (ordinal >= component.aliasNames.size() ||
                 failed(joinResolvedPins(key.first, carrier, found->second,
                                         component.aliasNames[ordinal++])))
          return failure();
      }
      if (!carrier) {
        Operation *origin = component.representative ? component.representative.getDefiningOp()
                                                     : source.getOperation();
        carrier = make(origin, "sv.wire", circt::hw::InOutType::get(component.type), {},
                       {builder.getNamedAttr("name", component.physicalName)});
        carrier.getDefiningOp()->setAttr("nodal.resolved_component",
                                         builder.getStringAttr("existing-checked-local-component"));
      }
      for (Value value : component.values)
        values[value] = carrier;
      for (const auto &key : component.childPins)
        if (!resolvedChildCarriers.try_emplace(key, carrier).second)
          return reject(key.first, "resolved child port has duplicate physical assignments");
    }
    // Unprojected child pins remain complete physical pins. An unconnected wire
    // realizes that exact actual declaration; it is not a new semantic endpoint.
    for (const auto &port : unconnectedResolvedPorts) {
      Value carrier = make(port.key.first, "sv.wire", circt::hw::InOutType::get(port.type), {},
                           {builder.getNamedAttr("name", port.name)});
      auto *original = port.key.second.first;
      NamedAttrList identity;
      identity.set("instance", port.key.first->getAttr("sym_name"));
      identity.set("declaration_path", builder.getStringAttr(sourcePath(original)));
      if (port.key.second.second)
        identity.set("member", port.key.second.second);
      carrier.getDefiningOp()->setAttr("nodal.resolved_declaration",
                                       builder.getDictionaryAttr(identity));
      resolvedChildCarriers.try_emplace(port.key, carrier);
    }
    return success();
  }
  Value typedFill(Operation *at, Type type, char fill,
                  StringRef correlation = "nodal.resolved_fill") {
    assert((fill == '0' || fill == '1' || fill == 'z') && "closed typed fill token");
    if (auto integer = dyn_cast<IntegerType>(type)) {
      if (fill == 'z')
        return make(at, "sv.constantZ", type, {});
      unsigned bits = integer.getWidth();
      return constant(at, bits, fill == '1' ? APInt::getAllOnes(bits) : APInt(bits, 0));
    }
    if (!isa<circt::hw::IntType>(type))
      return {};
    // Pinned constantZ does not export HWInt and replicate uses a fixed-width
    // accessor. Only these closed self-sized literal tokens are admitted here.
    auto value = circt::sv::VerbatimExprOp::create(builder, at->getLoc(), type,
                                                   std::string("'") + fill, ValueRange{});
    correlate(value, at);
    value->setAttr(correlation, builder.getStringAttr(std::string(1, fill)));
    return value.getResult();
  }
  LogicalResult lowerResolvedEffects() {
    for (Operation *op : resolvedEffects) {
      Value carrier = values.lookup(op->getOperand(0));
      auto inout =
          carrier ? dyn_cast<circt::hw::InOutType>(carrier.getType()) : circt::hw::InOutType();
      if (!inout || !isa<IntegerType, circt::hw::IntType>(inout.getElementType()))
        return reject(op, "resolved driver lost its exact typed physical component");
      Type type = inout.getElementType();
      auto componentIndex = resolvedValueComponents.find(op->getOperand(0));
      if (componentIndex == resolvedValueComponents.end())
        return reject(op, "resolved effect lost its exact planned component");
      const auto &component = resolvedLocalComponents[componentIndex->second];
      if (!component.geometry->shape.axes.empty() && !isa<NetReleaseOp>(op)) {
        if (!isa<NetDriveOp, NetDriveLevelOp>(op))
          return reject(op, "resolved family requires a checked pointwise conditional driver");
        Value enable = values.lookup(op->getOperand(isa<NetDriveOp>(op) ? 3 : 2));
        Value data = isa<NetDriveOp>(op) ? values.lookup(op->getOperand(2)) : Value();
        if (!enable || enable.getType() != component.enableType ||
            (isa<NetDriveOp>(op) && (!data || data.getType() != component.type)))
          return reject(op, "resolved family data/enable lost exact whole pointwise mapping");
        OpBuilder::InsertionGuard insertion(builder);
        unsigned ordinal = resolvedDriverOrdinal++;
        auto *wrapper = regionOperation(
            op, "sv.generate", {builder.getNamedAttr("sym_name", builder.getStringAttr(""))});
        builder.setInsertionPointToEnd(&wrapper->getRegion(0).front());
        auto *loop = regionOperation(
            op, "sv.generate.for",
            {builder.getNamedAttr("lowerBound", builder.getI32IntegerAttr(0)),
             builder.getNamedAttr("upperBound", generatedWidth(component.enableType)),
             builder.getNamedAttr("step", builder.getI32IntegerAttr(1)),
             builder.getNamedAttr("genBlockName", builder.getStringAttr("resolved_lanes_" +
                                                                        std::to_string(ordinal))),
             builder.getNamedAttr("inductionVarName", builder.getStringAttr("resolved_lane"))});
        Block &body = loop->getRegion(0).front();
        Value index = body.addArgument(builder.getI32Type(), op->getLoc());
        builder.setInsertionPointToEnd(&body);
        Value destination =
            generatedSlice(op, carrier, index, circt::hw::InOutType::get(component.laneType));
        Value laneEnable = generatedSlice(op, enable, index, builder.getI1Type());
        Value laneData =
            data ? generatedSlice(op, data, index, component.laneType)
                 : typedFill(op, component.laneType, text(op, "level") == "high" ? '1' : '0');
        Value released = typedFill(op, component.laneType, 'z');
        if (!destination || !laneEnable || !laneData || !released)
          return reject(op, "resolved family lane transport lost its checked slice");
        Value drive = make(op, "comb.mux", component.laneType, {laneEnable, laneData, released});
        assignGenerated(op, destination, drive);
        continue;
      }
      Value drive;
      if (isa<NetReleaseOp>(op))
        drive = typedFill(op, type, 'z');
      else if (isa<InterfaceDriveOp>(op))
        drive = values.lookup(op->getOperand(1));
      else {
        Value enable = values.lookup(op->getOperand(isa<NetDriveOp>(op) ? 3 : 2));
        Value data;
        if (isa<NetDriveOp>(op))
          data = values.lookup(op->getOperand(2));
        else {
          data = typedFill(op, type, text(op, "level") == "high" ? '1' : '0');
        }
        if (!enable || !enable.getType().isInteger(1) || !data || data.getType() != type)
          return reject(op,
                        "resolved conditional driver values disagree with actual checked types");
        Value released = typedFill(op, type, 'z');
        drive = make(op, "comb.mux", type, {enable, data, released});
      }
      if (!drive || drive.getType() != type)
        return reject(op, "resolved driver payload did not lower exactly");
      auto assign = circt::sv::AssignOp::create(builder, op->getLoc(), carrier, drive);
      correlate(assign, op);
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
        info.dir = port.inout   ? circt::hw::ModulePort::InOut
                   : port.input ? circt::hw::ModulePort::Input
                                : circt::hw::ModulePort::Output;
        info.argNum = port.input ? inputIndex++ : outputIndex++;
        info.loc = port.key.first->getLoc();
        NamedAttrList attributes;
        if (!sourcePath(port.key.first).empty())
          attributes.set("nodal.source_path", builder.getStringAttr(sourcePath(port.key.first)));
        if (port.generatedLayout)
          attributes.set("nodal.packed_abi", port.generatedLayout);
        else if (!port.inout && aggregatePayload(port.originalType) &&
                 !isa<circt::hw::StructType>(port.targetType))
          attributes.set("nodal.packed_abi", packedLayout(port.originalType));
        if (port.resolvedContract)
          attributes.set("nodal.resolved", port.resolvedContract);
        if (port.splitRoute) {
          const auto &route = hierarchy->splitPlans[variant->occurrence].routes[*port.splitRoute];
          attributes.set(
              "nodal.split_sideband",
              builder.getDictionaryAttr(
                  {builder.getNamedAttr("route", builder.getI32IntegerAttr(route.canonical)),
                   builder.getNamedAttr(
                       "role", builder.getStringAttr(port.splitEnable ? "enable" : "data")),
                   builder.getNamedAttr("relative_route",
                                        builder.getStringAttr(route.signature))}));
        }
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
    unsigned inventoryWork = 0;
    auto inventory = source->walk<WalkOrder::PreOrder>([&](Operation *op) {
      if (op != source.getOperation() && isa<ModuleOp>(op))
        return WalkResult::skip();
      if (++inventoryWork > maximumAuthoredOperations) {
        (void)reject(op, "Interface target inventory exceeds authored-work budget");
        return WalkResult::interrupt();
      }
      if (isa<InterfaceOp, InterfaceInstanceOp, InterfaceBoundaryOp, InterfaceAbiOp>(op) &&
          op->getParentOp() == source)
        staticOperations.insert(op);
      if (isa<InterfaceInstanceOp, InterfaceBoundaryOp>(op)) {
        if (!hasDigitalInterfaceContract(op)) {
          (void)reject(op, "target requires fully typed Interface endpoint contracts");
          return WalkResult::interrupt();
        }
        hasEndpoints = true;
      }
      return WalkResult::advance();
    });
    if (inventory.wasInterrupted())
      return failure();
    if (!hasEndpoints)
      return success();
    InterfaceTargetFacts localTemplateFacts;
    InterfaceTargetFacts *templateFacts = &localTemplateFacts;
    if (hierarchy) {
      auto found = hierarchy->interfaces.find(source);
      if (found == hierarchy->interfaces.end()) {
        auto collected = std::make_unique<InterfaceTargetFacts>();
        if (failed(collectDigitalInterfaceEndpoints(source, collected->endpoints,
                                                    &collected->accesses)))
          return failure();
        found = hierarchy->interfaces.try_emplace(source, std::move(collected)).first;
      }
      templateFacts = found->second.get();
      endpoints = templateFacts->endpoints;
      interfaceAccesses = templateFacts->accesses;
    } else {
      if (failed(collectDigitalInterfaceEndpoints(source, localEndpoints, &localInterfaceAccesses)))
        return failure();
      endpoints = localEndpoints;
      interfaceAccesses = localInterfaceAccesses;
    }
    SmallVector<std::pair<Operation *, Operation *>> typeSeeds;
    SmallVector<LogicalPortKey> typeKeys;
    llvm::DenseSet<LogicalPortKey> seenTypes;
    for (auto [index, endpoint] : llvm::enumerate(endpoints)) {
      bool directGenerated =
          !endpoint.instance && isa<GenerateOp>(endpoint.declaration->getParentOp());
      bool selectedChild = endpoint.selection && endpoint.instance &&
                           isa<GenerateOp>(endpoint.instance->getParentOp());
      if ((!directGenerated && endpoint.selection && !selectedChild) ||
          (!directGenerated && !isa<ModuleOp>(endpoint.operation->getParentOp())) ||
          (directGenerated && !isa<ModuleOp, GenerateOp>(endpoint.operation->getParentOp())))
        return reject(endpoint.operation,
                      "generated Interface target requires selected occurrence routing");
      if (selectedChild && (!isa<ModuleOp>(endpoint.declarationOwner) ||
                            endpoint.definition->getParentOp() != endpoint.declarationOwner ||
                            endpoint.declaration->getParentOp() != endpoint.declarationOwner ||
                            !endpoint.dimensions.empty()))
        return reject(
            endpoint.operation,
            "selected child Interface requires original module-owned payload and scalar endpoint");
      interfaceEndpoints.try_emplace(endpoint.operation, index);
      for (const auto &leaf : endpoint.leaves) {
        if (directGenerated) {
          if (leaf.access.getValue() != "out" || !endpoint.dimensions.empty())
            return reject(
                endpoint.operation,
                "generated direct Interface requires ordinary outputs and scalar endpoint");
          LogicalPortKey key{endpoint.declaration, leaf.path};
          if (seenTypes.insert(key).second) {
            typeSeeds.emplace_back(endpoint.declaration, leaf.declaration);
            typeKeys.push_back(key);
          }
        }
        if (selectedChild && failed(inspectHierarchyBoundaryType(
                                 leaf.type, endpoint.declarationOwner, endpoint.instance)))
          return reject(endpoint.operation,
                        "selected child Interface leaf lacks original all-lane type proof");
        if (isa<ResolvedType>(leaf.type) &&
            resolvedEndpointComponents.count({endpoint.operation, leaf.path}))
          continue;
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
    if (!typeSeeds.empty()) {
      if (!templateFacts->generatedTemplatesReady) {
        auto types = inspectDigitalInterfaceBoundaryTypes(source, typeSeeds);
        if (failed(types))
          return failure();
        templateFacts->generatedTypes = std::move(*types);
      }
      if (templateFacts->generatedTypes.size() != typeKeys.size())
        return reject(source, "cached Interface template inventory lost original authored order");
      for (auto [index, type] : llvm::enumerate(templateFacts->generatedTypes)) {
        if (type.endpoint != typeKeys[index].first || !type.outerDimensions.empty())
          return reject(type.endpoint,
                        "generated Interface lost its original scalar endpoint geometry");
        // Full original payload validation includes unobserved logical leaves.
        for (const auto &leaf : type.payload.leaves) {
          uint64_t work = 1 + leaf.path.size();
          for (const auto &group : leaf.dimensions)
            work += 1 + group.extents.size();
          if (work > maximumGeneratedOperations - cost || !leafVectorType(leaf))
            return reject(type.endpoint,
                          "generated Interface requires bounded invariant original payload layout");
          cost += work;
        }
        generatedInterfaceTypes.try_emplace(typeKeys[index], std::move(type));
      }
    }
    SmallVector<std::pair<Value, Operation *>> driverSeeds;
    SmallVector<LogicalPortKey> driverKeys;
    for (const auto &access : interfaceAccesses) {
      if (access.identity.selection || access.identity.instance ||
          !generatedInterfaceTypes.count({access.identity.endpoint, access.identity.path}))
        continue;
      Operation *root = access.value.getDefiningOp();
      Operation *family = access.identity.endpoint->getParentOp();
      if (root->getParentOp() != family || cost + 2 > maximumGeneratedOperations)
        return reject(root,
                      "generated Interface driver requires bounded direct original family use");
      cost += 2;
      driverSeeds.emplace_back(access.value, &family->getRegion(0).front().back());
      driverKeys.emplace_back(access.identity.endpoint, access.identity.path);
    }
    if (!driverSeeds.empty()) {
      if (!templateFacts->generatedTemplatesReady) {
        auto drivers = inspectGeneratedInterfaceTemplateDrivers(source, driverSeeds);
        if (failed(drivers))
          return failure();
        templateFacts->generatedDrivers = std::move(*drivers);
      }
      if (templateFacts->generatedDrivers.size() != driverSeeds.size())
        return reject(source, "cached Interface driver inventory lost original authored order");
      for (auto [index, driver] : llvm::enumerate(templateFacts->generatedDrivers)) {
        if (driver.root != driverSeeds[index].first ||
            driver.family != driverKeys[index].first->getParentOp() ||
            driver.effect->getParentOp() != driver.family)
          return reject(driver.effect,
                        "generated Interface driver lost exact original family identity");
        generatedInterfaceDrivers.try_emplace(driver.root, driver);
        generatedInterfaceRoots.try_emplace(driverKeys[index], driver.root);
        generatedInterfaceEffects.insert(driver.effect);
      }
    }
    templateFacts->generatedTemplatesReady = true;
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
      if (isa<ResolvedNetOp>(op)) {
        auto component = resolvedEndpointComponents.find({&op, Attribute()});
        if (component == resolvedEndpointComponents.end())
          return reject(&op, "resolved declaration lost its checked logical endpoint");
        LogicalPortPlan port;
        port.key = {&op, Attribute()};
        port.originalType = op.getResult(0).getType();
        port.targetType =
            circt::hw::InOutType::get(resolvedLocalComponents[component->second].type);
        port.name = op.getAttrOfType<StringAttr>("name");
        port.input = port.inout = true;
        port.references.push_back(op.getResult(0));
        port.resolvedContract = resolvedContract(&op, Attribute(), component->second);
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
        port.inout = isa<ResolvedType>(leaf.type);
        port.targetType =
            port.inout
                ? Type(circt::hw::InOutType::get(
                      resolvedLocalComponents[resolvedEndpointComponents.lookup({&op, leaf.path})]
                          .type))
                : targetValueType(port.originalType);
        if (!port.targetType)
          return reject(&op, "Interface payload requires supported bounded digital boundary type; "
                             "symbolic Struct requires boundary-abi=named-fields");
        port.input = port.inout || leaf.access.getValue() == "in";
        if (port.inout)
          port.resolvedContract =
              resolvedContract(&op, leaf.path, resolvedEndpointComponents.lookup({&op, leaf.path}));
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
                          GeneratedFamilyPlan *scope = nullptr, unsigned depth = 0) {
    if (!node || depth > digitalTypeMaximumDepth)
      return {};
    if (node->bounds.lower < std::numeric_limits<int32_t>::min() ||
        node->bounds.upper > std::numeric_limits<int32_t>::max()) {
      source.emitOpError("NODAL-CIRCT-056-001: static Integer expression bounds must fit signed "
                         "32-bit arithmetic without truncation");
      return {};
    }
    auto key = std::make_pair(node.get(), scope);
    if (auto found = parameterExpressions.find(key); found != parameterExpressions.end())
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
    } else if (node->kind == Kind::OpaqueValue) {
      // A proof atom is not a general target parameter. Only this helper's
      // exact admitted induction ancestry has declarations in its environment.
      for (auto *ancestor = scope; ancestor; ancestor = ancestor->parent)
        if (node->identity == ancestor->domain.induction) {
          value = circt::hw::ParamDeclRefAttr::get(ancestor->indexName, builder.getI32Type());
          break;
        }
    } else if (node->kind == Kind::Apply) {
      SmallVector<TypedAttr> operands;
      for (const auto &operand : node->operands) {
        auto actual = parameterNode(operand, scope, depth + 1);
        if (!actual)
          return {};
        operands.push_back(actual);
      }
      if (node->opcode == "add" && operands.size() == 2)
        value = circt::hw::ParamExprAttr::get(circt::hw::PEO::Add, operands);
      else if (node->opcode == "mul" && operands.size() == 2)
        value = circt::hw::ParamExprAttr::get(circt::hw::PEO::Mul, operands);
      else if (node->opcode == "div" && operands.size() == 2 &&
               node->operands[0]->bounds.lower >= 0 && node->operands[1]->bounds.lower > 0)
        value = circt::hw::ParamExprAttr::get(circt::hw::PEO::DivS, operands);
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
      parameterExpressions.try_emplace(key, value);
    return value;
  }
  TypedAttr parameterExpression(const InstantiatedIntegerExpression &expression,
                                GeneratedFamilyPlan *scope = nullptr) {
    // The memo keys refer to immutable query-local proof nodes. Retain their
    // owning roots for this target transaction, never pointers into dead queries.
    if (parameterExpressionRoots.size() >= maximumAuthoredOperations)
      return {};
    parameterExpressionRoots.push_back(expression.root);
    return parameterNode(expression.root, scope);
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
              !fixedShapeSelections.count(user) && !controlProjections.contains(user) &&
              !isa<GenerateOp, GeneratedMemberOp, StaticCompareOp, StructuralIfOp>(user) &&
              !(isa<InstancePortOp, InterfaceBoundaryOp>(user) && user->getNumOperands() == 2) &&
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
  FailureOr<ArrayAttr> instanceParameters(Operation *instance,
                                          GeneratedFamilyPlan *scope = nullptr) {
    Operation *child = resolveInstanceModuleDefinition(instance);
    if (!child)
      return failure();
    SmallVector<Attribute> actuals;
    for (Operation &op : child->getRegion(0).front()) {
      if (!isa<ParameterOp>(op) || text(&op, "variability") != "symbolic")
        continue;
      auto proof = scope ? resolveGeneratedInstanceIntegerParameter(
                               instance, &op, ParameterIntegerMode::MathematicalSigned64)
                         : resolveInstanceIntegerParameter(
                               instance, &op, ParameterIntegerMode::MathematicalSigned64);
      TypedAttr value = succeeded(proof) ? parameterExpression(*proof, scope) : TypedAttr();
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
  // This is one immutable physical permutation, not another type/shape owner.
  // Each segment maps one canonical scalar's logical packed offset to its
  // named leaf vector's offset. Both inventories are independently checked.
  struct FixedBoundarySegment {
    unsigned leaf, leafOffset, logicalOffset, bits;
  };
  struct FixedBoundaryAdapter {
    unsigned bits = 0;
    Type targetType;
    SmallVector<unsigned> leafBits;
    SmallVector<FixedBoundarySegment> segments;
    DictionaryAttr layout;
  };
  LogicalResult planFixedBoundaryAdapter(Type type, Operation *at) {
    if (fixedBoundaryAdapters.count(type))
      return success();
    auto target = dyn_cast_or_null<circt::hw::StructType>(targetValueType(type));
    auto view = boundaryViews.find(type);
    auto bits = inferDigitalBitPatternFixedBits(type, source);
    if (!target || view == boundaryViews.end() || failed(bits) || !*bits || *bits > maximumWidth ||
        target.getElements().size() != view->second.leaves.size())
      return reject(at, "named-field CDC requires an exact fixed logical packed layout");
    FixedBoundaryAdapter plan;
    plan.bits = *bits;
    plan.targetType = target;
    llvm::StringMap<unsigned> leafIndices;
    for (auto [index, leaf] : llvm::enumerate(view->second.leaves)) {
      auto physical = dyn_cast<IntegerType>(target.getElements()[index].type);
      if (!physical || !physical.getWidth() ||
          !leafIndices.try_emplace(leafName(leaf), index).second)
        return reject(at, "named-field CDC leaf vectors require unique fixed widths");
      plan.leafBits.push_back(physical.getWidth());
    }
    SmallVector<unsigned> leafOffsets(plan.leafBits.size(), 0);
    HierarchyBoundaryLeaf path;
    unsigned logicalOffset = 0;
    llvm::DenseMap<Type, unsigned> fixedWidths;
    std::function<LogicalResult(Type, unsigned)> visit = [&](Type current,
                                                             unsigned depth) -> LogicalResult {
      if (depth > 128 || cost >= maximumGeneratedOperations)
        return failure();
      ++cost;
      if (auto shape = dyn_cast<nodal::ShapedType>(current)) {
        SmallVector<StringRef> dimensions;
        shape.getDimensions().split(dimensions, ',');
        unsigned count = 1;
        for (StringRef token : dimensions) {
          auto extent = resolveStaticShapeDimension(source, token);
          if (failed(extent) || extent->bounds.lower != extent->bounds.upper ||
              extent->bounds.lower <= 0 || extent->bounds.lower > maximumWidth / count)
            return failure();
          count *= extent->bounds.lower;
        }
        for (unsigned index = 0; index != count; ++index)
          if (failed(visit(shape.getElementType(), depth + 1)))
            return failure();
        return success();
      }
      if (auto structure = dyn_cast<StructType>(current)) {
        for (Attribute attribute : structure.getFields()) {
          auto field = cast<DictionaryAttr>(attribute);
          path.path.push_back(
              {builder.getStringAttr(structure.getName()), field.getAs<StringAttr>("name")});
          auto result = visit(field.getAs<TypeAttr>("type").getValue(), depth + 1);
          path.path.pop_back();
          if (failed(result))
            return failure();
        }
        return success();
      }
      auto found = leafIndices.find(leafName(path));
      if (found == leafIndices.end())
        return failure();
      unsigned index = found->second;
      const auto &leaf = view->second.leaves[index];
      if (leaf.type != current || leaf.path.size() != path.path.size())
        return failure();
      for (auto [actual, expected] : llvm::zip(path.path, leaf.path))
        if (actual.structure != expected.structure || actual.name != expected.name)
          return failure();
      auto [width, inserted] = fixedWidths.try_emplace(current, 0);
      if (inserted) {
        auto proof = inferDigitalBitPatternFixedBits(current, source);
        if (failed(proof) || !*proof || *proof > maximumWidth)
          return failure();
        width->second = *proof;
      }
      unsigned count = width->second;
      if (count > plan.bits - logicalOffset || count > plan.leafBits[index] - leafOffsets[index])
        return failure();
      plan.segments.push_back({index, leafOffsets[index], logicalOffset, count});
      leafOffsets[index] += count;
      logicalOffset += count;
      return success();
    };
    if (failed(visit(type, 0)) || logicalOffset != plan.bits || leafOffsets != plan.leafBits)
      return reject(at,
                    "named-field CDC logical/physical layout must be an exact bounded bijection");
    SmallVector<Attribute> segments;
    for (const auto &segment : plan.segments)
      segments.push_back(builder.getDictionaryAttr(
          {builder.getNamedAttr("leaf", builder.getI32IntegerAttr(segment.leaf)),
           builder.getNamedAttr("leaf_offset", builder.getI32IntegerAttr(segment.leafOffset)),
           builder.getNamedAttr("logical_offset", builder.getI32IntegerAttr(segment.logicalOffset)),
           builder.getNamedAttr("width", builder.getI32IntegerAttr(segment.bits))}));
    plan.layout = builder.getDictionaryAttr(
        {builder.getNamedAttr("kind", builder.getStringAttr("fixed-named-cdc")),
         builder.getNamedAttr("width", builder.getI64IntegerAttr(plan.bits)),
         builder.getNamedAttr("order",
                              builder.getStringAttr("recursive-row-major-fields-lsb-first")),
         builder.getNamedAttr("boundary", packedLayout(type)),
         builder.getNamedAttr("segments", builder.getArrayAttr(segments))});
    fixedBoundaryAdapters.try_emplace(type, std::move(plan));
    return success();
  }
  // Low-order segments first, with binary balanced concatenation. Keeping this
  // bounded also avoids depending on attributed variadic backend reductions.
  Value concatenateBoundary(Operation *at, SmallVector<Value> parts) {
    if (parts.empty())
      return {};
    while (parts.size() > 1) {
      SmallVector<Value> next;
      for (unsigned i = 0; i < parts.size(); i += 2) {
        if (i + 1 == parts.size()) {
          next.push_back(parts[i]);
          continue;
        }
        auto low = cast<IntegerType>(parts[i].getType()).getWidth();
        auto high = cast<IntegerType>(parts[i + 1].getType()).getWidth();
        next.push_back(
            make(at, "comb.concat", builder.getIntegerType(low + high), {parts[i + 1], parts[i]}));
      }
      parts = std::move(next);
    }
    return parts.front();
  }
  Value boundarySegment(Operation *at, Value value, unsigned offset, unsigned bits) {
    auto type = dyn_cast<IntegerType>(value.getType());
    if (!type || offset > type.getWidth() || bits > type.getWidth() - offset)
      return {};
    return !offset && bits == type.getWidth()
               ? value
               : make(at, "comb.extract", builder.getIntegerType(bits), {value},
                      {builder.getNamedAttr("lowBit", builder.getI32IntegerAttr(offset))});
  }
  Value packFixedBoundary(Operation *at, Type original, Value value) {
    auto found = fixedBoundaryAdapters.find(original);
    if (found == fixedBoundaryAdapters.end())
      return value;
    const auto &plan = found->second;
    if (value.getType() != plan.targetType)
      return {};
    auto leaves = splitBoundary(at, value);
    if (leaves.size() != plan.leafBits.size())
      return {};
    SmallVector<Value> segments;
    for (const auto &segment : plan.segments) {
      Value part = boundarySegment(at, leaves[segment.leaf], segment.leafOffset, segment.bits);
      if (!part)
        return {};
      segments.push_back(part);
    }
    Value result = concatenateBoundary(at, std::move(segments));
    if (!result || !result.getType().isInteger(plan.bits))
      return {};
    // A correlated wire keeps the inverse record off a shared input value.
    auto wire = circt::hw::WireOp::create(builder, at->getLoc(), result);
    correlate(wire, at);
    wire->setAttr("nodal.boundary_pack", plan.layout);
    return wire.getResult();
  }
  Value unpackFixedBoundary(Operation *at, Type original, Value value) {
    auto found = fixedBoundaryAdapters.find(original);
    if (found == fixedBoundaryAdapters.end())
      return value;
    const auto &plan = found->second;
    if (!value.getType().isInteger(plan.bits))
      return {};
    SmallVector<SmallVector<Value>> segments(plan.leafBits.size());
    for (const auto &segment : plan.segments) {
      Value part = boundarySegment(at, value, segment.logicalOffset, segment.bits);
      if (!part)
        return {};
      segments[segment.leaf].push_back(part);
    }
    SmallVector<Value> leaves;
    for (auto [index, parts] : llvm::enumerate(segments)) {
      Value leaf = concatenateBoundary(at, std::move(parts));
      if (!leaf || !leaf.getType().isInteger(plan.leafBits[index]))
        return {};
      leaves.push_back(leaf);
    }
    Value result = joinBoundary(at, plan.targetType, leaves);
    result.getDefiningOp()->setAttr("nodal.boundary_unpack", plan.layout);
    return result;
  }
  LogicalResult finishCrossingPayload(const ExecutableCrossing &crossing) {
    Operation *op = crossing.operation;
    Value value =
        unpackFixedBoundary(op, op->getResult(0).getType(), values.lookup(op->getResult(0)));
    if (!value)
      return reject(op, "crossing packed result disagrees with its exact named boundary layout");
    values[op->getResult(0)] = value;
    return success();
  }
  struct FixedShapeSelection {
    Value root;
    unsigned offset, bits;
  };
  LogicalResult planFixedShapeSelections() {
    if (!namedBoundaryABI || source.getBody().front().empty())
      return success();
    Operation *use = &source.getBody().front().back();
    SmallVector<Value> selectors;
    for (Operation &op : source.getBody().front()) {
      if (!isa<ShapeIndexOp>(op) ||
          !getDigitalControlBoundaryKind(op.getResult(0).getType()).empty())
        continue;
      auto projection = resolveStaticShapeProjection(op.getResult(0), use);
      if (failed(projection) || !projection->contiguousLiteralRegion ||
          !projection->literalFirstOffset || !projection->literalLastOffset ||
          !projection->exactFixedCardinality ||
          llvm::any_of(projection->projections,
                       [](Operation *part) { return !isa<ShapeIndexOp>(part); }))
        return reject(&op,
                      "named-field shape selection requires an exact fixed contiguous index image");
      Type rootType = projection->root.getType();
      Type element = rootType;
      while (auto shape = dyn_cast<nodal::ShapedType>(element))
        element = shape.getElementType();
      auto elementBits = inferDigitalBitPatternFixedBits(element, source);
      auto rootBits = inferDigitalBitPatternFixedBits(rootType, source);
      auto resultBits = inferDigitalBitPatternFixedBits(op.getResult(0).getType(), source);
      int64_t first = *projection->literalFirstOffset;
      int64_t count = *projection->literalLastOffset - first + 1;
      if (failed(elementBits) || failed(rootBits) || failed(resultBits) || !*elementBits ||
          !*resultBits || *rootBits > maximumWidth || first < 0 || count <= 0 ||
          *projection->exactFixedCardinality != *rootBits / *elementBits ||
          first > *rootBits / *elementBits ||
          count > (*rootBits - first * *elementBits) / *elementBits ||
          count * *elementBits != *resultBits)
        return reject(&op, "named-field shape selection requires exact fixed logical bit offsets");
      for (Type type : {rootType, op.getResult(0).getType()}) {
        Type mapped = targetValueType(type);
        if (!mapped || (!isa<IntegerType>(mapped) && !isa<circt::hw::StructType>(mapped)))
          return reject(&op, "named-field shape selection requires fixed data types");
        if (isa<circt::hw::StructType>(mapped) && failed(planFixedBoundaryAdapter(type, &op)))
          return failure();
      }
      llvm::append_range(selectors, op.getOperands().drop_front());
      fixedShapeSelections.try_emplace(&op, FixedShapeSelection{projection->root,
                                                                unsigned(first * *elementBits),
                                                                unsigned(*resultBits)});
      for (Type type : {rootType, op.getResult(0).getType()}) {
        auto plan = fixedBoundaryAdapters.find(type);
        if (plan == fixedBoundaryAdapters.end())
          continue;
        uint64_t expansion = 2 * plan->second.segments.size() + plan->second.leafBits.size() + 2;
        if (expansion > maximumGeneratedOperations - cost)
          return reject(&op, "fixed shape adapter exceeds target operation budget");
        cost += expansion;
      }
    }
    while (!selectors.empty()) {
      Value selector = selectors.pop_back_val();
      Operation *op = selector.getDefiningOp();
      if (!op || !isa<ConstantOp, ConstLiteralOp, ConstExprOp, ConstParameterRefOp>(op) ||
          (!selector.getType().isIndex() && !selector.getType().isInteger(64)))
        return reject(source, "fixed shape selectors require proved compile-time expressions");
      if (!staticOperations.insert(op).second)
        continue;
      llvm::append_range(selectors, op->getOperands());
    }
    return success();
  }
  Value lowerFixedShapeSelection(Operation *op) {
    const auto &selection = fixedShapeSelections.find(op)->second;
    Value root = values.lookup(selection.root);
    if (!root)
      return {};
    Value packed = packFixedBoundary(op, selection.root.getType(), root);
    if (!packed)
      return {};
    Value selected = boundarySegment(op, packed, selection.offset, selection.bits);
    if (!selected)
      return {};
    return unpackFixedBoundary(op, op->getResult(0).getType(), selected);
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
    if (aggregatePayload(op->getOperand(0).getType())) {
      auto adapter = fixedBoundaryAdapters.find(op->getOperand(0).getType());
      state->setAttr("nodal.packed_abi", adapter == fixedBoundaryAdapters.end()
                                             ? packedLayout(op->getOperand(0).getType())
                                             : adapter->second.layout);
    }
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
    Type resultType = targetValueType(op->getResult(0).getType());
    if (isa_and_nonnull<circt::hw::IntType>(resultType)) {
      if (!generatedSymbolicValue(op)) {
        (void)reject(op, "symbolic scalar emitter requires exact-type bitwise/select transport");
        return {};
      }
      if (char fill = symbolicLiteralFill(op))
        return typedFill(op, resultType, fill, "nodal.literal_fill");
      if (isa<SequentialSelectOp>(op) || operationCode == "digital_select")
        return mux(op, inputs[0], inputs[1], inputs[2]);
      if (operationCode == "digital_alias")
        return inputs[0];
      StringRef target = llvm::StringSwitch<StringRef>(operationCode)
                             .Case("digital_and", "comb.and")
                             .Case("digital_or", "comb.or")
                             .Case("digital_xor", "comb.xor")
                             .Default("");
      return make(op, target, resultType, inputs);
    }
    auto scalarResult = dyn_cast_or_null<IntegerType>(resultType);
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
  llvm::DenseMap<std::pair<const InstantiatedIntegerNode *, GeneratedFamilyPlan *>, TypedAttr>
      parameterExpressions;
  SmallVector<std::shared_ptr<const InstantiatedIntegerNode>> parameterExpressionRoots;
  llvm::DenseMap<Operation *, ArrayAttr> targetInstanceParameters;
  llvm::DenseMap<Attribute, std::pair<Value, Value>> domainInputs;
  SmallVector<Operation *> instances;
  llvm::DenseMap<InstanceLogicalPortKey, SmallVector<Value>> instancePortValues;
  struct ResolvedLocalComponent {
    Value representative;
    Type type, laneType, enableType, originalData;
    const DigitalResolvedEndpoint *geometry = nullptr;
    StringAttr mode, profile, physicalName;
    SmallVector<StringAttr, 2> aliasNames;
    SmallVector<LogicalPortKey, 2> localPins;
    SmallVector<InstanceLogicalPortKey, 2> childPins;
    SmallVector<Value, 2> values;
  };
  struct UnconnectedResolvedPort {
    InstanceLogicalPortKey key;
    Type type;
    StringAttr name;
  };
  SmallVector<UnconnectedResolvedPort> unconnectedResolvedPorts;
  SmallVector<ResolvedLocalComponent> resolvedLocalComponents;
  llvm::DenseMap<LogicalPortKey, unsigned> resolvedEndpointComponents;
  llvm::DenseMap<LogicalPortKey, const DigitalResolvedEndpoint *> resolvedEndpointFacts;
  llvm::DenseMap<LogicalPortKey, Value> resolvedPortCarriers;
  llvm::DenseMap<InstanceLogicalPortKey, Value> resolvedChildCarriers;
  llvm::DenseSet<InstanceLogicalPortKey> projectedResolvedPorts;
  llvm::DenseMap<Value, unsigned> resolvedValueComponents;
  llvm::DenseSet<Operation *> resolvedOperations;
  SmallVector<Operation *> resolvedEffects;
  unsigned resolvedDriverOrdinal = 0;

  SmallVector<LogicalPortPlan> logicalPorts;
  SmallVector<DigitalInterfaceEndpoint> localEndpoints;
  SmallVector<DigitalInterfaceAccess> localInterfaceAccesses;
  ArrayRef<DigitalInterfaceEndpoint> endpoints;
  ArrayRef<DigitalInterfaceAccess> interfaceAccesses;
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
  llvm::DenseMap<Type, FixedBoundaryAdapter> fixedBoundaryAdapters;
  llvm::DenseMap<Operation *, FixedShapeSelection> fixedShapeSelections;
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
      if (isa<InterfaceMemberOp>(op))
        if (auto type = op->getAttrOfType<TypeAttr>("type"))
          hierarchy |= isa<ResolvedType>(type.getValue());
      hierarchy |=
          isa<InstanceOp, ResolvedNetOp, ResolvedBoundaryOp>(op) ||
          llvm::any_of(op->getResultTypes(), [](Type type) { return isa<ResolvedType>(type); }) ||
          (isa<nodal::ModuleOp>(op) &&
           (hasLocalHierarchyObligations(op) || op->hasAttr("external_name")));
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
    llvm::StringSet<> physicalModuleNames;
    for (Operation &operation : *candidate->getBody()) {
      StringRef name;
      if (auto external = dyn_cast<circt::hw::HWModuleExternOp>(operation))
        name = external.getVerilogModuleName();
      else if (isa<circt::hw::HWModuleOp>(operation))
        name = text(&operation, "sym_name");
      else
        continue;
      if (name.empty() || !physicalModuleNames.insert(name).second) {
        (void)reject(&operation,
                     "actual physical module names collide after target specialization");
        signalPassFailure();
        return;
      }
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
