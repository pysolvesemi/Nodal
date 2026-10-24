#include "nodal/Backend/ScalarHierarchyMaterialization.h"

#include "mlir/IR/Builders.h"
#include "mlir/IR/IRMapping.h"
#include "mlir/IR/SymbolTable.h"
#include "mlir/IR/Verifier.h"
#include "nodal/Dialect/Nodal/DigitalInterfaceModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/TargetCapabilities.h"
#include "nodal/Backend/Backend.h"
#include "nodal/Backend/ShapedValueBackend.h"
#include "nodal/Diagnostics/DiagnosticSupport.h"
#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"
#include "nodal/Dialect/Nodal/PotentialFlowAccess.h"
#include "nodal/Support/HierarchyOrder.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringSet.h"

#include <algorithm>
#include <functional>
#include <map>
#include <set>
#include <tuple>
#include <string>
#include <vector>

using namespace mlir;

namespace nodal {
namespace {
constexpr uint64_t kOccurrences = 1000, kOperations = 100000, kDepth = 64;

llvm::StringRef text(Operation *op, llvm::StringRef key) {
  auto attr = op->getAttrOfType<StringAttr>(key);
  return attr ? attr.getValue() : llvm::StringRef();
}
llvm::StringRef path(Operation *op) {
  auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
  auto value = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
  return value ? value.getValue() : text(op, "sym_name");
}
LogicalResult reject(Operation *op, llvm::StringRef message) {
  return emitMappedFailure(op, "NODAL-BACKEND-HIERARCHY-MATERIALIZE-001", message);
}
uint64_t add(uint64_t left, uint64_t right, uint64_t limit) {
  return left > limit || right > limit || right > limit - left ? limit + 1 : left + right;
}
bool structural(Operation *op) {
  return llvm::isa<InstanceOp, InstanceTerminalOp, ConnectOp, ParameterOverrideOp>(op);
}
bool scalarParameter(Operation *op) {
  auto type = op->getAttrOfType<TypeAttr>("type");
  return llvm::isa<ParameterOp>(op) && type && type.getValue().isF64() &&
         getParameterKind(op) == "real" && !op->hasAttr("unit") &&
         getParameterClassification(op) == "ordinary" &&
         llvm::isa<FloatAttr>(op->getAttr("default_value"));
}
bool childOperation(Operation *op) {
  return llvm::isa<ModuleOp, ParameterOp, ParameterValueOp, ConstLiteralOp, ConstParameterRefOp,
                   InstanceOp, InstanceTerminalOp, ConnectOp, ParameterOverrideOp, TerminalOp,
                   NodeOp, BranchOp, ProbeOp, AnalogOp, AccessOp, TerminalAccessOp, RealLiteralOp,
                   AnalogIntegerLiteralOp, ParameterRefOp, AnalogAddOp, AnalogSubOp, AnalogMulOp,
                   AnalogDivOp, AnalogNegOp, AnalogCompareOp, AnalogLogicOp, AnalogSelectOp,
                   AnalogFunctionOp, AnalogUserFunctionOp, AnalogFunctionValueOp,
                   AnalogFunctionReturnOp, AnalogUserCallOp, ContributeOp>(op);
}

/// Physical adaptation consumes the complete Interface owner once per definition.
/// It does not assign analog values or build a second connectivity graph.
class InterfaceMaterializer {
public:
  explicit InterfaceMaterializer(mlir::ModuleOp outer) : outer(outer), builder(outer.getContext()) {}

  LogicalResult run() {
    bool present = false;
    outer.walk([&](InterfaceInstanceOp) { present = true; });
    if (!present)
      return success();
    if (failed(mlir::verify(outer)) || failed(preflight()))
      return failure();
    // All borrowed source facts remain alive until replacement and correspondence
    // construction finish. No operation is changed during admission.
    for (auto &entry : endpoints) {
      auto &endpoint = entry.endpoint;
      if (endpoint.selection || (endpoint.instance && !entry.family))
        continue;
      builder.setInsertionPoint(endpoint.operation);
      for (auto &leaf : entry.leaves) {
        if (entry.family) {
          builder.setInsertionPoint(entry.families.front());
          OperationState array(endpoint.operation->getLoc(), NodeArrayOp::getOperationName());
          array.addTypes(ShapedType::get(outer.getContext(), entry.extent, leaf.type));
          array.addAttribute("name", builder.getStringAttr(leaf.name));
          const std::string arrayPath = path(endpoint.domainOwner).str() + "." + leaf.name;
          array.addAttribute("source_path", builder.getStringAttr(arrayPath));
          auto attrs = NamedAttrList(metadata(endpoint, leaf, false));
          attrs.set("semantic_path", builder.getStringAttr(arrayPath));
          attrs.set("storage", builder.getStringAttr("structural"));
          array.addAttribute("metadata", attrs.getDictionary(outer.getContext()));
          leaf.array = builder.create(array)->getResult(0);
          builder.setInsertionPoint(endpoint.operation);
          llvm::SmallVector<Value, 2> ordinals;
          for (Operation *family : entry.families) {
            Value ordinal = normalizedIndices.lookup(family);
            if (!ordinal) {
              Value induction = family->getRegion(0).front().getArgument(0);
              InstantiatedIntegerExpression expression;
              if (resolveGeneratedInductionOrdinal(family, induction, endpoint.operation,
                                                   expression, remainingIntegerWork) !=
                      ShapeRegionProofStatus::Proved ||
                  materializeStructuralIntegerExpression(
                      expression, endpoint.operation, {induction},
                      (text(family, "region_id") + ".normalized_index").str(),
                      ordinal, remainingIntegerWork) != IntegerExpressionEmissionStatus::Proved)
                return invalid(endpoint.operation, "generated conservative ordinal cannot be materialized safely");
              normalizedIndices.try_emplace(family, ordinal);
            }
            ordinals.push_back(ordinal);
          }
          leaf.value = select(entry, leaf, ordinals, endpoint.operation);
          record(endpoint, leaf, "local_node");
          continue;
        }
        const bool local = llvm::isa<GenerateOp>(endpoint.operation->getParentOp());
        OperationState state(endpoint.operation->getLoc(),
                             local ? NodeOp::getOperationName() : TerminalOp::getOperationName());
        state.addTypes(leaf.type);
        state.addAttribute("name", builder.getStringAttr(leaf.name));
        if (local) {
          Operation *scope = endpoint.operation->getParentOp();
          state.addAttribute("generated_owner", scope->getAttr("region_id"));
          state.addAttribute("generated_induction", scope->getAttr("induction_path"));
        } else {
          state.addAttribute("direction", builder.getStringAttr("inout"));
          state.addAttribute("flow_orientation", builder.getStringAttr("into_component"));
        }
        state.addAttribute("source_path", builder.getStringAttr(leaf.logical));
        state.addAttribute("metadata", metadata(endpoint, leaf, false));
        leaf.value = builder.create(state)->getResult(0);
        record(endpoint, leaf, local ? "local_node" : "into_component");
      }
    }
    for (auto &entry : endpoints) {
      auto &endpoint = entry.endpoint;
      if (endpoint.selection)
        continue;
      if (!endpoint.instance)
        continue;
      builder.setInsertionPoint(endpoint.operation);
      for (auto &leaf : entry.leaves) {
        const auto &target = endpoints[positions.lookup(endpoint.declaration)];
        auto port = llvm::find_if(target.leaves, [&](const Leaf &candidate) {
          return candidate.path == leaf.path;
        });
        OperationState state(endpoint.operation->getLoc(), InstanceTerminalOp::getOperationName());
        state.addTypes(leaf.type);
        state.addAttribute("instance", FlatSymbolRefAttr::get(
            outer.getContext(), text(endpoint.instance, "sym_name")));
        state.addAttribute("port", builder.getStringAttr(port->name));
        state.addAttribute("name", builder.getStringAttr(
            entry.family ? leaf.name + "_child_port" : leaf.name));
        state.addAttribute("direction", builder.getStringAttr("inout"));
        state.addAttribute("flow_orientation", builder.getStringAttr("out_of_component"));
        if (auto scope = llvm::dyn_cast<GenerateOp>(endpoint.operation->getParentOp())) {
          state.addAttribute("generated_owner", scope->getAttr("region_id"));
          state.addAttribute("generated_induction", scope->getAttr("induction_path"));
        }
        state.addAttribute("source_path", builder.getStringAttr(leaf.logical));
        state.addAttribute("metadata", metadata(endpoint, leaf, true));
        Value terminal = builder.create(state)->getResult(0);
        if (entry.family) {
          OperationState connection(endpoint.operation->getLoc(), ConnectOp::getOperationName());
          connection.addOperands({terminal, leaf.value});
          const std::string identity = leaf.logical + ".selected_child_binding";
          connection.addAttribute("connection_id", builder.getStringAttr(identity));
          connection.addAttribute("source_path", builder.getStringAttr(identity));
          connection.addAttribute("generated_owner", entry.family->getAttr("region_id"));
          connection.addAttribute("generated_induction", entry.family->getAttr("induction_path"));
          connection.addAttribute("metadata", builder.getDictionaryAttr({}));
          builder.create(connection);
        } else {
          leaf.value = terminal;
        }
        Leaf incidence = leaf;
        if (entry.family) incidence.name += "_child_port";
        record(endpoint, incidence, "out_of_component");
      }
    }
    for (auto &entry : endpoints) {
      auto &endpoint = entry.endpoint;
      if (endpoint.selection) {
        auto &original = endpoints[selectedPlans.lookup(endpoint.operation)];
        builder.setInsertionPoint(endpoint.operation);
        for (auto &leaf : entry.leaves) {
          auto source = llvm::find_if(original.leaves, [&](const Leaf &item) { return item.path == leaf.path; });
          leaf.value = select(original, *source, entry.selectedOrdinals, endpoint.operation);
          // Correspondence names the same physical array root as the checked view.
          leaf.name = source->name;
          record(endpoint, leaf, endpoint.instance ? "out_of_component" : "local_node");
          if (endpoint.instance) selectedPhysical.push_back(leaf.value);
        }
        continue;
      }
    }
    for (auto &access : accesses) {
      auto &entry = endpoints[positions.lookup(access.projection)];
      auto leaf = llvm::find_if(entry.leaves, [&](const Leaf &candidate) {
        return candidate.path == access.identity.path.getValue();
      });
      access.value.replaceAllUsesWith(leaf->value);
    }
    // Canonical proof-only domain records are removed only after every actual
    // SSA and symbol use has been shown to belong to this closed erase set.
    for (Operation *module : modules) {
      llvm::SmallVector<Operation *> ordered;
      module->walk<WalkOrder::PostOrder>([&](Operation *operation) {
        if (!erase.contains(operation))
          return;
        for (Operation *owner = operation->getParentOp(); owner && owner != module;
             owner = owner->getParentOp())
          if (erase.contains(owner))
            return;
        ordered.push_back(operation);
      });
      for (Operation *operation : llvm::reverse(ordered))
        operation->erase();
    }
    for (Operation *instance : structuralInstances)
      instance->setAttr("domain_bindings", builder.getDictionaryAttr({}));
    outer->setAttr("nodal.backend.interface_correspondence", builder.getArrayAttr(correspondence));
    if (Attribute profile = outer->getAttr("nodal.target.profile"))
      outer->setAttr("nodal.backend.interface_source_profile", profile);
    outer->setAttr("nodal.target.profile", builder.getStringAttr("analog"));
    if (failed(mlir::verify(outer))) return failure();
    // Real resulting electrical topology, not ordinal separation, authorizes routing.
    for (unsigned i = 0; i < selectedPhysical.size(); ++i)
      for (unsigned j = 0; j < i; ++j) {
        if (!remainingIntegerWork) return invalid(outer, "selected child relation budget exhausted");
        --remainingIntegerWork;
        auto value = selectedPhysical[i];
        Operation *use = &value.getDefiningOp()->getBlock()->back();
        auto relation = compareConservativeEndpoints(selectedPhysical[j], value, use);
        if (relation != ConservativeAlias::Equal && relation != ConservativeAlias::Distinct)
          return invalid(use, "selected child physical coupling is not proved");
      }
    return success();
  }

private:
  struct Leaf {
    std::string path, name, logical;
    Type type;
    DictionaryAttr analog, references;
    StringAttr role, access;
    ArrayAttr nestedRoles, nestedInterfaces;
    Value value;
    Value array;
  };
  struct Endpoint {
    DigitalInterfaceEndpoint endpoint;
    llvm::SmallVector<Leaf> leaves;
    Operation *family = nullptr;
    llvm::SmallVector<Operation *, 2> families;
    llvm::SmallVector<Value, 2> selectedOrdinals;
    std::string extent;
  };
  LogicalResult invalid(Operation *site, llvm::StringRef message) {
    return emitMappedFailure(site, "NODAL-BACKEND-INTERFACE-CONSERVATIVE-001", message);
  }
  static std::string encode(llvm::StringRef value) {
    constexpr char hex[] = "0123456789abcdef";
    std::string result;
    for (unsigned char byte : value.bytes()) {
      result.push_back(hex[byte >> 4]);
      result.push_back(hex[byte & 15]);
    }
    return result;
  }
  DictionaryAttr metadata(const DigitalInterfaceEndpoint &endpoint, const Leaf &leaf, bool child) {
    return builder.getDictionaryAttr({
        builder.getNamedAttr("semantic_path", builder.getStringAttr(leaf.logical)),
        builder.getNamedAttr("logical_interface", builder.getStringAttr(text(endpoint.declaration, "sym_name"))),
        builder.getNamedAttr("logical_member", builder.getStringAttr(leaf.path)),
        builder.getNamedAttr("selected_role", endpoint.selectedRole->getAttr("sym_name")),
        builder.getNamedAttr("leaf_role", leaf.role),
        builder.getNamedAttr("selected_access", leaf.access),
        builder.getNamedAttr("nested_roles", leaf.nestedRoles),
        builder.getNamedAttr("nested_interfaces", leaf.nestedInterfaces),
        builder.getNamedAttr("analog", leaf.analog),
        builder.getNamedAttr("analog_refs", leaf.references),
        builder.getNamedAttr("allow_floating", builder.getBoolAttr(true)),
        builder.getNamedAttr("interface_boundary", builder.getBoolAttr(child)),
        builder.getNamedAttr("declaration_kind", builder.getStringAttr("analog-inout"))});
  }
  void record(const DigitalInterfaceEndpoint &endpoint, const Leaf &leaf, llvm::StringRef orientation) {
    correspondence.push_back(builder.getDictionaryAttr({
        builder.getNamedAttr("owner", builder.getStringAttr(text(endpoint.domainOwner, "sym_name"))),
        builder.getNamedAttr("endpoint", builder.getStringAttr(text(endpoint.declaration, "sym_name"))),
        builder.getNamedAttr("member", builder.getStringAttr(leaf.path)),
        builder.getNamedAttr("source", builder.getStringAttr(leaf.logical)),
        builder.getNamedAttr("target", builder.getStringAttr(leaf.name)),
        builder.getNamedAttr("instance", builder.getStringAttr(endpoint.instance ? text(endpoint.instance, "sym_name") : "")),
        builder.getNamedAttr("flow_orientation", builder.getStringAttr(orientation)),
        builder.getNamedAttr("selected_role", endpoint.selectedRole->getAttr("sym_name")),
        builder.getNamedAttr("leaf_role", leaf.role),
        builder.getNamedAttr("selected_access", leaf.access),
        builder.getNamedAttr("nested_roles", leaf.nestedRoles),
        builder.getNamedAttr("nested_interfaces", leaf.nestedInterfaces),
        builder.getNamedAttr("analog", leaf.analog),
        builder.getNamedAttr("analog_refs", leaf.references)}));
  }
  Value select(const Endpoint &entry, const Leaf &leaf, ArrayRef<Value> ordinals,
               Operation *site) {
    Operation *scope = site->getParentOp();
    std::string token;
    for (Value ordinal : ordinals) {
      if (!token.empty()) token += ',';
      if (auto argument = llvm::dyn_cast<BlockArgument>(ordinal))
        token += "induction:" + text(argument.getOwner()->getParentOp(), "induction_path").str();
      else if (llvm::isa<ConstExprOp>(ordinal.getDefiningOp()))
        token += "expression:" + path(ordinal.getDefiningOp()).str();
      else
        token += "literal:" + std::to_string(
            ordinal.getDefiningOp()->getAttrOfType<IntegerAttr>("value").getInt());
    }
    const std::string arrayPath = path(entry.endpoint.domainOwner).str() + "." + leaf.name;
    std::string selectedPath = arrayPath + ".at(" + token + ")";
    if (llvm::isa<GenerateOp>(scope))
      selectedPath += ".in(" + text(scope, "region_id").str() + ")";
    OperationState state(site->getLoc(), ShapeIndexOp::getOperationName());
    state.addOperands(leaf.array);
    state.addOperands(ordinals);
    state.addTypes(leaf.type);
    if (llvm::isa<GenerateOp>(scope)) {
      state.addAttribute("generated_owner", scope->getAttr("region_id"));
      state.addAttribute("generated_induction", scope->getAttr("induction_path"));
    }
    auto attrs = NamedAttrList(metadata(entry.endpoint, leaf, false));
    attrs.set("input_path", builder.getStringAttr(arrayPath));
    attrs.set("semantic_path", builder.getStringAttr(selectedPath));
    attrs.set("source_uses", builder.getStrArrayAttr({path(site).str() + "." + leaf.path}));
    attrs.set("storage", builder.getStringAttr("structural"));
    attrs.set("selection_kind", builder.getStringAttr("node_array"));
    state.addAttribute("metadata", attrs.getDictionary(outer.getContext()));
    return builder.create(state)->getResult(0);
  }
  LogicalResult preflight() {
    uint64_t work = 0;
    WalkResult bounded = outer.walk([&](Operation *operation) {
      if (llvm::isa<InstanceOp>(operation))
        structuralInstances.push_back(operation);
      if (++work <= kOperations)
        return WalkResult::advance();
      (void)invalid(operation, "conservative Interface physical plan exceeds finite operation budget");
      return WalkResult::interrupt();
    });
    if (bounded.wasInterrupted())
      return failure();
    uint64_t leaves = 0;
    for (Operation &module : outer.getBody()->getOperations()) {
      if (!llvm::isa<ModuleOp>(module))
        continue;
      modules.push_back(&module);
      llvm::SmallVector<DigitalInterfaceEndpoint> logical;
      llvm::SmallVector<DigitalInterfaceAccess> represented;
      if (failed(collectDigitalInterfaceEndpoints(&module, logical, &represented)) ||
          failed(resolveConservativeStructuralScope(&module)))
        return failure();
      llvm::StringSet<> names;
      // The initial bounded walk already counted every authored operation.
      // Inventory all lexical names in source order before allocating adapters;
      // suffix selection uses membership only, never hash iteration order.
      module.walk([&](Operation *operation) {
        for (llvm::StringRef key : {"name", "sym_name"})
          if (!text(operation, key).empty())
            names.insert(text(operation, key));
      });
      for (auto &endpoint : logical) {
        if ((endpoint.selection && failed(resolveConservativeGeneratedSelection(endpoint.operation))) ||
            !llvm::isa<ModuleOp, GenerateOp>(endpoint.operation->getParentOp()))
          return invalid(endpoint.operation, "generated conservative Interface requires selected occurrence routing");
        if (!endpoint.dimensions.empty())
          return invalid(endpoint.operation, "conservative Interface emission requires scalar endpoints");
        Endpoint entry{endpoint, {}};
        for (const auto &leaf : endpoint.leaves) {
          if (++leaves > kOperations)
            return invalid(endpoint.operation, "conservative Interface leaf plan exceeds finite budget");
          if (endpoint.selection && endpoint.instance && leaf.access.getValue() != "connect")
            return invalid(endpoint.operation, "selected child requires its exact connect role");
          if (leaf.protocol.getValue() != "conservative" ||
              !llvm::isa<TerminalType>(leaf.type))
            return invalid(endpoint.operation, "mixed/digital/signal-flow Interface emission is unsupported");
          std::string base = "nodal_i" + encode(text(endpoint.operation, "sym_name")) + "_" + encode(leaf.path.getValue());
          std::string physical = base;
          unsigned ordinal = 0;
          while (!names.insert(physical).second)
            physical = base + "_" + std::to_string(++ordinal);
          std::string logicalPath = path(endpoint.operation).str() + "." + leaf.path.getValue().str();
          entry.leaves.push_back(Leaf{leaf.path.getValue().str(), std::move(physical),
              std::move(logicalPath), leaf.type, leaf.analogMetadata, leaf.analogReferences,
              leaf.role, leaf.access, leaf.nestedRoles, leaf.nestedInterfaces, {}});
        }
        positions.try_emplace(endpoint.operation, endpoints.size());
        endpoints.push_back(std::move(entry));
        erase.insert(endpoint.operation);
        Operation *domain = SymbolTable::lookupSymbolIn(&module, endpoint.domain);
        if (!domain)
          return invalid(endpoint.operation, "Interface domain witness disappeared");
        domains.insert(domain);
      }
      accesses.append(represented.begin(), represented.end());
      module.walk([&](Operation *operation) {
        if (llvm::isa<InterfaceOp, InterfaceAbiOp, MemberAccessOp>(operation))
          erase.insert(operation);
      });
    }
    // Index original incidence once. Selection lookup must not rescan every
    // endpoint and bypass the authored-operation budget.
    using ChildKey = std::tuple<Operation *, Operation *, Operation *>;
    std::map<ChildKey, unsigned> bodyIncidences;
    std::set<ChildKey> ambiguousIncidences;
    for (auto [index, entry] : llvm::enumerate(endpoints)) {
      if (++work > kOperations)
        return invalid(entry.endpoint.operation, "selected child incidence inventory exceeds operation budget");
      const auto &candidate = entry.endpoint;
      if (candidate.selection || !candidate.instance ||
          !llvm::isa<GenerateOp>(candidate.operation->getParentOp()))
        continue;
      ChildKey key{candidate.operation->getParentOp(), candidate.instance, candidate.declaration};
      if (!bodyIncidences.emplace(key, index).second)
        ambiguousIncidences.insert(key);
    }
    for (auto &entry : endpoints) {
      if (!entry.endpoint.selection)
        continue;
      if (++work > kOperations)
        return invalid(entry.endpoint.operation, "selected child incidence lookup exceeds operation budget");
      auto proof = resolveConservativeGeneratedSelection(entry.endpoint.operation);
      if (failed(proof) || proof->selection.selections.empty() ||
          proof->selection.selections.size() != proof->domains.size() ||
          entry.leaves.empty() || !positions.count(proof->declaration))
        return invalid(entry.endpoint.operation, "selected conservative endpoint lacks exact scalar export plan");
      unsigned originalIndex = positions.lookup(proof->declaration);
      if (entry.endpoint.instance) {
        ChildKey key{proof->selection.family, entry.endpoint.instance, proof->declaration};
        if (ambiguousIncidences.count(key))
          return invalid(entry.endpoint.operation, "selected child has multiple body incidence plans");
        auto found = bodyIncidences.find(key);
        if (found == bodyIncidences.end())
          return invalid(entry.endpoint.operation, "selected child requires its actual original body incidence");
        originalIndex = found->second;
      }
      selectedPlans.try_emplace(entry.endpoint.operation, originalIndex);
      auto &original = endpoints[originalIndex];
      if (original.leaves.size() != entry.leaves.size())
        return invalid(entry.endpoint.operation, "selected conservative leaves require complete original inventory");
      for (auto [source, selected] : llvm::zip(original.leaves, entry.leaves)) {
        if (++work > kOperations)
          return invalid(entry.endpoint.operation, "selected conservative leaf proof exceeds operation budget");
        if (source.path != selected.path || source.type != selected.type ||
            source.analog != selected.analog || source.references != selected.references ||
            source.role != selected.role || source.access != selected.access ||
            source.nestedRoles != selected.nestedRoles ||
            source.nestedInterfaces != selected.nestedInterfaces)
          return invalid(entry.endpoint.operation,
                         "selected conservative leaf changes original ordered typed role identity");
      }
      llvm::SmallVector<Operation *, 2> families;
      std::string extent;
      for (auto [axis, domain] : llvm::zip(proof->selection.selections, proof->domains)) {
        families.push_back(axis.family);
        entry.selectedOrdinals.push_back(axis.ordinal);
        if (!extent.empty()) extent += ',';
        extent += std::to_string(domain.maximumTripCount);
      }
      if (!original.families.empty() && original.families != families)
        return invalid(entry.endpoint.operation, "selected child changes original ordered family ancestry");
      original.family = proof->selection.family;
      original.families = std::move(families);
      original.extent = std::move(extent);
      if (original.families.size() == 2) {
        if (proof->extentParameter ||
            failed(resolveTargetShapedLayout(entry.endpoint.domainOwner, original.extent)))
          return invalid(entry.endpoint.operation, "nested conservative carrier exceeds signed32 target layout");
        for (auto [axis, domain] : llvm::zip(proof->selection.selections, proof->domains))
          normalizedIndices.try_emplace(axis.family, domain.induction);
        // The canonical inspector already checked this original prefix. Reuse
        // its owner to identify the exact proof-only consumer closure, not an
        // operation-kind whitelist or a second family/occurrence registry.
        Operation *prefix = entry.endpoint.operation->getOperand(0).getDefiningOp();
        if (!llvm::isa_and_nonnull<GeneratedMemberOp>(prefix))
          return invalid(entry.endpoint.operation, "nested conservative selection has no original prefix");
        llvm::SmallVector<Operation *, 2> uses;
        unsigned remaining = unsigned(kOperations - work), before = remaining;
        auto member = inspectGeneratedMember(prefix, uses, remaining);
        work += before - remaining;
        if (failed(member) || !member->familyView || member->selections.size() != 1 ||
            member->family != original.families.front() ||
            member->storage.getDefiningOp() != original.family ||
            prefix->getParentOp() != entry.endpoint.domainOwner ||
            member->selections.front().ordinal != entry.selectedOrdinals.front())
          return invalid(entry.endpoint.operation, "nested conservative prefix lost its original family export");
        selectionWitnesses.insert(prefix);
        selectionWitnesses.insert(member->exportOperation);
      }
      if (proof->extentParameter) {
        original.extent = text(proof->extentParameter, "sym_name").str();
        // Preflight owns the shared authored-operation budget until its final
        // residual is transferred to run(). Charge these proofs into that same
        // counter instead of consuming the not-yet-initialized run residual.
        unsigned queryWork = unsigned(kOperations - work);
        const unsigned queryBudget = queryWork;
        auto dimension = resolveStaticShapeDimension(proof->selection.family->getParentOp(), original.extent,
                                                     "shape", queryWork);
        Value induction = proof->selection.family->getRegion(0).front().getArgument(0);
        InstantiatedIntegerExpression normalized;
        const bool invalidIdentity = failed(dimension) || dimension->parameter != proof->extentParameter ||
            uint64_t(dimension->bounds.upper) != proof->domains.front().maximumTripCount ||
            resolveGeneratedInductionOrdinal(proof->selection.family, induction, original.endpoint.operation,
                                             normalized, queryWork) !=
                ShapeRegionProofStatus::Proved ||
            !normalized.root ||
            normalized.root->kind != InstantiatedIntegerNode::Kind::OpaqueValue ||
            normalized.root->identity != induction;
        work += queryBudget - queryWork;
        if (invalidIdentity)
          return invalid(
              entry.endpoint.operation,
              "symbolic conservative extent requires exact parent and induction identity");
        // No expression emission or new proof may fail after NodeArray creation
        // on this identity path. All leaves reuse the original induction SSA.
        normalizedIndices.try_emplace(proof->selection.family, induction);
      }
    }
    // Every nested proof consumer must belong to the admitted original graph.
    // Keep both Generate bodies/results, but erase only their now-replaced
    // selected views and exact family-export witnesses. Unknown consumers fail.
    llvm::DenseSet<Operation *> checkedFamilies;
    for (const auto &entry : endpoints) {
      if (entry.families.size() != 2) continue;
      for (Operation *family : entry.families) {
        if (!checkedFamilies.insert(family).second) continue;
        for (Value result : family->getResults())
          for (OpOperand &use : result.getUses()) {
            if (++work > kOperations)
              return invalid(family, "nested conservative consumer closure exceeds operation budget");
            Operation *user = use.getOwner();
            bool selected = positions.count(user) &&
                            endpoints[positions.lookup(user)].endpoint.selection;
            if (use.getOperandNumber() != 0 || (!selected && !selectionWitnesses.contains(user)))
              return invalid(user, "nested conservative family has an unplanned consumer");
          }
      }
    }
    for (Operation *witness : selectionWitnesses) {
      for (Value result : witness->getResults())
        for (OpOperand &use : result.getUses()) {
          if (++work > kOperations || use.getOperandNumber() != 0 ||
              !positions.count(use.getOwner()) ||
              !endpoints[positions.lookup(use.getOwner())].endpoint.selection)
            return invalid(witness, "nested conservative prefix has an unplanned consumer");
        }
      erase.insert(witness);
    }
    llvm::StringSet<> physicalNames;
    bool namingBudget = false;
    outer.walk([&](Operation *op) {
      if (++work > kOperations) { namingBudget = true; return; }
      for (StringRef key : {"name", "sym_name"})
        if (!text(op, key).empty()) physicalNames.insert(text(op, key));
    });
    if (namingBudget) return invalid(outer, "selected child name inventory exceeds operation budget");
    for (const auto &entry : endpoints)
      for (const auto &leaf : entry.leaves) physicalNames.insert(leaf.name);
    for (const auto &entry : endpoints) {
      if (!entry.family || !entry.endpoint.instance) continue;
      for (const auto &leaf : entry.leaves) {
        if (++work > kOperations || !physicalNames.insert(leaf.name + "_child_port").second)
          return invalid(entry.endpoint.operation, "selected child port spelling collides or exceeds budget");
      }
    }
    // Authored exports are proof witnesses even when no selected view exists.
    // Resolve their exact original target and close every actual family user;
    // spelling alone never grants permission to discard a symbol consumer.
    for (Operation *module : modules) {
      WalkResult exports = module->walk([&](GeneratedExportOp exported) {
        if (text(exported, "kind") != "interface")
          return WalkResult::advance();
        if (++work > kOperations) {
          (void)invalid(exported, "conservative export inventory exceeds operation budget");
          return WalkResult::interrupt();
        }
        auto target = inspectGeneratedExportSymbol(exported);
        Operation *family = exported->getParentOp();
        if (failed(target) || !positions.count(*target) ||
            !llvm::isa<GenerateOp>(family) || (*target)->getParentOp() != family ||
            text(exported, "kind") != "interface") {
          (void)invalid(exported, "generated export lacks exact conservative endpoint plan");
          return WalkResult::interrupt();
        }
        for (Value result : family->getResults())
          for (Operation *user : result.getUsers()) {
            if (++work > kOperations || !positions.count(user) ||
                !endpoints[positions.lookup(user)].endpoint.selection) {
              (void)invalid(exported, "generated conservative family has an unplanned consumer");
              return WalkResult::interrupt();
            }
          }
        erase.insert(exported);
        return WalkResult::advance();
      });
      if (exports.wasInterrupted())
        return failure();
    }
    for (const auto &entry : endpoints)
      if (entry.endpoint.instance && !positions.count(entry.endpoint.declaration))
        return invalid(entry.endpoint.operation, "Interface child endpoint has no exact physical plan");
    for (const auto &access : accesses) {
      if (access.leaf.protocol.getValue() != "conservative" || !positions.count(access.projection))
        return invalid(access.value.getDefiningOp(), "member has no conservative physical plan");
      for (Operation *user : access.value.getUsers())
        if (!llvm::isa<ConnectOp, BranchOp>(user))
          return invalid(user, "conservative Interface member requires supported connection or branch use");
    }
    // Keep domain erasure bounded to the exact Interface witness closure. Unknown
    // producers or any surviving SSA/symbol consumer fail before mutation.
    for (Operation *domain : domains)
      erase.insert(domain);
    for (Operation *module : modules) {
      module->walk([&](Operation *site) {
        Operation &operation = *site;
        auto reference = [&](llvm::StringRef key) {
          auto ref = operation.getAttrOfType<FlatSymbolRefAttr>(key);
          return ref ? SymbolTable::lookupSymbolIn(module, ref) : nullptr;
        };
        if ((llvm::isa<LocalDomainBindingOp>(operation) && domains.contains(reference("domain"))) ||
            (llvm::isa<InstanceDomainBindingOp>(operation) && domains.contains(reference("actual"))) ||
            (llvm::isa<DomainBindOp, RequirementClockOp, RequirementResetOp>(operation) &&
             domains.contains(reference("requirement"))))
          erase.insert(&operation);
      });
    }
    llvm::SmallVector<Operation *> pending(erase.begin(), erase.end());
    while (!pending.empty()) {
      Operation *operation = pending.pop_back_val();
      // Selected family/ordinal operands belong to the retained structural
      // program, not the erased domain-witness closure. The complete selected
      // endpoint plan above independently checked both operands.
      if ((positions.count(operation) && endpoints[positions.lookup(operation)].endpoint.selection) ||
          selectionWitnesses.contains(operation))
        continue;
      for (Value operand : operation->getOperands()) {
        Operation *producer = operand.getDefiningOp();
        if (!producer || (!erase.contains(producer) &&
            !llvm::isa<PortValueOp, RequirementClockOp, RequirementResetOp>(producer)))
          return invalid(operation, "Interface-only domain witness has an unsupported producer");
        if (erase.insert(producer).second)
          pending.push_back(producer);
        if (llvm::isa<PortValueOp>(producer)) {
          auto ref = producer->getAttrOfType<FlatSymbolRefAttr>("port");
          Operation *port = ref ? SymbolTable::lookupSymbolIn(producer->getParentOp(), ref) : nullptr;
          auto type = port ? port->getAttrOfType<TypeAttr>("type") : TypeAttr();
          if (!llvm::isa_and_nonnull<PortOp>(port) || !type ||
              !isDigitalControlBoundaryType(type.getValue()))
            return invalid(producer, "domain witness is not an exact control port");
          erase.insert(port);
        }
      }
    }
    // Instance domain_bindings are cleared below, but no other attribute on
    // that operation may retain a reference to an erased proof-domain symbol.
    // Validate once per instance rather than rescanning attributes per use.
    for (Operation *instance : structuralInstances) {
      Operation &operation = *instance;
      Operation *module = instance->getParentOfType<ModuleOp>();
      for (NamedAttribute named : operation.getAttrs()) {
        if (named.getName() == "domain_bindings")
          continue;
        Attribute value = named.getValue();
        WalkResult uses = value.walk<WalkOrder::PreOrder>([&](Attribute nested) {
          if (++work > kOperations) {
            (void)invalid(&operation, "Interface domain attribute inventory exceeds finite budget");
            return WalkResult::interrupt();
          }
          auto reference = llvm::dyn_cast<SymbolRefAttr>(nested);
          if (reference &&
              domains.contains(SymbolTable::lookupSymbolIn(module, reference.getRootReference()))) {
            (void)invalid(
                &operation,
                "Interface domain symbol survives outside exact instance domain_bindings");
            return WalkResult::interrupt();
          }
          return WalkResult::advance();
        });
        if (uses.wasInterrupted())
          return failure();
      }
    }
    for (Operation *operation : erase) {
      for (Value value : operation->getResults())
        for (Operation *user : value.getUsers())
          if (!erase.contains(user) && !llvm::isa<MemberAccessOp>(operation))
            return invalid(user, "Interface domain witness has a surviving actual consumer");
      if (operation->hasAttr("sym_name")) {
        auto uses = SymbolTable::getSymbolUses(operation, operation->getParentOp());
        if (!uses)
          return invalid(operation, "Interface domain symbol use inventory is not closed");
        for (const auto &use : *uses)
          if (!erase.contains(use.getUser()) &&
              !(llvm::isa<InstanceOp>(use.getUser()) && domains.contains(operation)))
            return invalid(use.getUser(), "Interface witness symbol has a surviving consumer");
      }
    }
    for (Operation *module : modules) {
      WalkResult digital = module->walk([&](Operation *operation) {
        if (erase.contains(operation) || operation->getParentOfType<InterfaceOp>())
          return WalkResult::advance();
        // Family proof records are neutral, not digital target capabilities.
        // Only the exact inspected witness closure may disappear; unrelated
        // handles must fail before any physical endpoint is materialized.
        if (llvm::isa<GeneratedMemberOp>(operation) ||
            (llvm::isa<GeneratedExportOp>(operation) && text(operation, "kind") == "family")) {
          (void)invalid(operation, "unplanned generated family proof consumer survives adaptation");
          return WalkResult::interrupt();
        }
        if (hasDigitalTargetCapability(operation)) {
          (void)invalid(operation, "real digital behavior remains outside conservative Interface emission");
          return WalkResult::interrupt();
        }
        return WalkResult::advance();
      });
      if (digital.wasInterrupted())
        return failure();
    }
    remainingIntegerWork = unsigned(kOperations - work);
    return success();
  }
  unsigned remainingIntegerWork = 0;
  llvm::DenseMap<Operation *, Value> normalizedIndices;
  mlir::ModuleOp outer;
  OpBuilder builder;
  llvm::SmallVector<Operation *> modules, structuralInstances;
  llvm::SmallVector<Endpoint, 0> endpoints;
  llvm::SmallVector<DigitalInterfaceAccess> accesses;
  llvm::DenseMap<Operation *, unsigned> positions, selectedPlans;
  llvm::SmallVector<Value, 8> selectedPhysical;
  llvm::DenseSet<Operation *> domains, erase, selectionWitnesses;
  llvm::SmallVector<Attribute> correspondence;
};

struct Definition {
  Definition(Operation *op, ConservativeStructuralScope topology)
      : op(op), topology(std::move(topology)) {}
  Operation *op;
  ConservativeStructuralScope topology;
  llvm::StringMap<Operation *> parameters;
  llvm::DenseMap<Operation *, llvm::StringMap<Value>> ports;
  llvm::DenseMap<Operation *, llvm::StringMap<Operation *>> overrides;
  uint64_t cost = 0;
};

class Materializer {
public:
  explicit Materializer(mlir::ModuleOp outer) : outer(outer), builder(outer.getContext()) {}

  LogicalResult run() {
    if (failed(preflight()))
      return failure();
    Operation *root = definitions[rootIndex].op;
    // A fresh module body avoids dangling source SSA references while the source
    // definition graph remains available throughout occurrence materialization.
    Operation *target = root->cloneWithoutRegions();
    target->getRegion(0).push_back(new Block());
    outer.getBody()->push_back(target);
    output = target;
    root->walk([&](Operation *op) {
      for (llvm::StringRef key : {"name", "sym_name"})
        if (!text(op, key).empty())
          reserved.insert(text(op, key));
    });
    IRMapping values;
    llvm::StringMap<std::string> symbols;
    if (failed(emit(rootIndex, path(root).str(), {}, values, symbols, nullptr))) {
      target->erase();
      return failure();
    }
    for (auto &definition : definitions)
      definition.op->erase();
    outer->setAttr("nodal.backend.hierarchy_materialization", builder.getStringAttr("scalar-v1"));
    outer->setAttr("nodal.backend.hierarchy_correspondence", builder.getArrayAttr(correspondence));
    return success();
  }

private:
  LogicalResult preflight() {
    // Derived probes are accepted only with their independently verified source
    // support and compiler ownership. They are recomputed after materialization,
    // since occurrence mapping can merge or remove source-free access groups.
    if (failed(verifyPotentialFlowAccessModel(outer)))
      return failure();
    // Legacy equation inventories are source semantics, even when no equation
    // operation was lowered. Never turn their historical comments into behavior.
    for (llvm::StringRef key : {"nodal.bridge.analog_semantics", "nodal.bridge.source_equations"})
      if (auto inventory = outer->getAttrOfType<ArrayAttr>(key); inventory && !inventory.empty())
        return reject(outer, "equation inventories are outside scalar hierarchy materialization");
    auto root = outer->getAttrOfType<FlatSymbolRefAttr>("nodal.root.module");
    if (!root)
      return reject(outer, "scalar hierarchy materialization requires one explicit root");
    for (Operation &op : outer.getBody()->getOperations()) {
      if (!llvm::isa<ModuleOp>(op))
        continue;
      auto topology = resolveConservativeStructuralScope(&op);
      if (failed(topology))
        return failure();
      indices[&op] = definitions.size();
      definitions.emplace_back(&op, std::move(*topology));
      if (text(&op, "sym_name") == root.getValue())
        rootIndex = definitions.size() - 1;
    }
    if (rootIndex >= definitions.size())
      return reject(outer, "scalar hierarchy root does not resolve");
    std::vector<std::vector<std::size_t>> edges(definitions.size());
    for (std::size_t index = 0; index < definitions.size(); ++index) {
      auto &definition = definitions[index];
      Operation *unsupported = nullptr;
      definition.op->walk([&](Operation *op) {
        // Two inserted operations per parameter is an upper bound for actual
        // references/default attachments, included before any graph expansion.
        definition.cost = add(definition.cost, llvm::isa<ParameterOp>(op) ? 3 : 1, kOperations);
        if (unsupported)
          return;
        // Prefixing a materialized name must not make a source spelling legal
        // when the ordinary target boundary would reject it.
        if ((llvm::isa<ModuleOp, ParameterOp, InstanceOp, AnalogUserFunctionOp>(op) &&
             !isPortableVerilogIdentifier(text(op, "sym_name"))) ||
            (llvm::isa<TerminalOp, NodeOp>(op) && !isPortableVerilogIdentifier(text(op, "name"))) ||
            (llvm::isa<BranchOp>(op) && !text(op, "name").empty() &&
             !isPortableVerilogIdentifier(text(op, "name")))) {
          unsupported = op;
          return;
        }
        bool shaped = llvm::any_of(op->getResultTypes(),
                                   [](Type type) { return llvm::isa<ShapedType>(type); });
        for (Type type : op->getResultTypes()) {
          if (auto terminal = llvm::dyn_cast<TerminalType>(type))
            shaped |= terminal.getDiscipline() != "electrical";
          if (auto branch = llvm::dyn_cast<BranchType>(type))
            shaped |= branch.getDiscipline() != "electrical";
        }
        if (shaped ||
            llvm::isa<GenerateOp, AnalogEquationOp, AnalogUnknownOp, ComponentContractOp,
                      PortFlowAccessOp>(op) ||
            (index != rootIndex && !childOperation(op)))
          unsupported = op;
      });
      if (unsupported)
        return reject(unsupported, "operation is outside the finite scalar hierarchy profile");
      for (Operation &op : definition.op->getRegion(0).front()) {
        if (llvm::isa<ParameterOp>(op)) {
          definition.parameters[text(&op, "sym_name")] = &op;
          if (index != rootIndex && !scalarParameter(&op))
            return reject(&op, "child parameters require ordinary dimensionless scalar Real");
        }
        if (index != rootIndex && llvm::isa<ParameterValueOp>(op)) {
          Operation *literal = op.getOperand(0).getDefiningOp();
          if (!llvm::isa_and_nonnull<ConstLiteralOp>(literal) || literal->hasAttr("unit"))
            return reject(&op, "child parameter defaults require literal initializer DAGs");
        }
        if (llvm::isa<InstanceTerminalOp>(op))
          for (Operation *user : op.getResult(0).getUsers())
            if (!llvm::isa<ConnectOp>(user))
              return reject(user, "child endpoint must be consumed by canonical connections only");
        if (llvm::isa<ParameterOverrideOp>(op)) {
          auto instance =
              resolveStructuralInstance(&op, op.getAttrOfType<FlatSymbolRefAttr>("instance"));
          auto parameter = op.getAttrOfType<FlatSymbolRefAttr>("parameter");
          if (!instance || !parameter ||
              !definition.overrides[instance].try_emplace(parameter.getValue(), &op).second)
            return reject(&op, "parameter override is not a unique immediate binding");
          auto source = op.getOperand(0).getDefiningOp();
          if (!llvm::isa_and_nonnull<ConstParameterRefOp>(source))
            return reject(&op, "symbolic child actuals require direct parent parameter references");
        }
      }
      for (const auto &group : definition.topology.scalarGroups) {
        Value anchor;
        unsigned anchors = 0;
        for (Value value : group.members)
          if (!llvm::isa_and_nonnull<InstanceTerminalOp>(value.getDefiningOp())) {
            anchor = value;
            ++anchors;
          }
        if (group.connections.empty())
          continue;
        if (anchors != 1)
          return reject(group.connections.front(),
                        "connected child group requires exactly one parent-local anchor");
        bool child = false;
        for (Value value : group.members) {
          Operation *endpoint = value.getDefiningOp();
          if (!llvm::isa_and_nonnull<InstanceTerminalOp>(endpoint))
            continue;
          child = true;
          auto resolved = resolveConservativeEndpoint(value, endpoint);
          if (failed(resolved) || !resolved->instance || !resolved->port ||
              !definition.ports[resolved->instance]
                   .try_emplace(text(resolved->port, "name"), anchor)
                   .second)
            return reject(endpoint, "child port must have one canonical parent-local binding");
        }
        if (!child)
          return reject(group.connections.front(),
                        "parent-only joins are outside this hierarchy profile");
      }
      for (Operation *instance : definition.topology.instances) {
        Operation *child = resolveInstanceModuleDefinition(instance);
        auto domains = instance->getAttrOfType<DictionaryAttr>("domain_bindings");
        if (!child || !indices.count(child) || (domains && !domains.empty()))
          return reject(instance, "instance target or domain bindings are unsupported");
        edges[index].push_back(indices.lookup(child));
        for (Operation &op : child->getRegion(0).front()) {
          if (llvm::isa<TerminalOp>(op) && !definition.ports[instance].count(text(&op, "name")))
            return reject(instance, "every child boundary terminal must be bound exactly once");
          if (!llvm::isa<ParameterOp>(op))
            continue;
          auto override = definition.overrides[instance].lookup(text(&op, "sym_name"));
          if (!override)
            continue;
          auto reference =
              override->getOperand(0).getDefiningOp()->getAttrOfType<FlatSymbolRefAttr>(
                  "parameter");
          Operation *parentParameter =
              reference ? definition.parameters.lookup(reference.getValue()) : nullptr;
          if (!scalarParameter(&op) || !parentParameter || !scalarParameter(parentParameter))
            return reject(override,
                          "direct overrides require matching dimensionless Real parameters");
        }
      }
    }
    auto ordered = orderHierarchy(edges);
    if (ordered.status != HierarchyOrder::Status::Success)
      return reject(outer, "materialized hierarchy must be closed and acyclic");
    std::vector<uint64_t> counts(definitions.size(), 1), costs(definitions.size()),
        depths(definitions.size(), 1);
    for (std::size_t index : ordered.leavesFirst) {
      costs[index] = definitions[index].cost;
      for (std::size_t child : edges[index]) {
        counts[index] = add(counts[index], counts[child], kOccurrences);
        costs[index] = add(costs[index], costs[child], kOperations);
        depths[index] = std::max(depths[index], add(1, depths[child], kDepth));
      }
    }
    if (counts[rootIndex] > kOccurrences || costs[rootIndex] > kOperations ||
        depths[rootIndex] > kDepth)
      return emitMappedFailure(
          outer, "NODAL-BACKEND-HIERARCHY-MATERIALIZE-002",
          "scalar expansion exceeds 1000 occurrences, 100000 operations, or depth 64");
    llvm::DenseSet<std::size_t> reachable;
    std::vector<std::size_t> pending{rootIndex};
    while (!pending.empty()) {
      auto index = pending.back();
      pending.pop_back();
      if (reachable.insert(index).second)
        pending.insert(pending.end(), edges[index].begin(), edges[index].end());
    }
    if (reachable.size() != definitions.size())
      return reject(outer,
                    "all supplied module definitions must belong to the selected root graph");
    return success();
  }

  std::string reserve(llvm::StringRef original, uint64_t occurrence) {
    std::string base = "nodal_h" + std::to_string(occurrence) + "_" + original.str();
    std::string result = base;
    unsigned collision = 0;
    while (!reserved.insert(result).second)
      result = base + "_" + std::to_string(++collision);
    return result;
  }
  std::string remapPath(llvm::StringRef value, llvm::StringRef original, llvm::StringRef actual) {
    if (value == original)
      return actual.str();
    if (value.starts_with((original + ".").str()))
      return (actual + value.drop_front(original.size())).str();
    return value.str();
  }
  void provenance(Operation *op, llvm::StringRef original, llvm::StringRef actual) {
    NamedAttrList metadata(op->getAttrOfType<DictionaryAttr>("metadata"));
    // Only semantic identities are occurrence-relative. Source filenames, source
    // text and authored spans remain exact even when they share this prefix.
    for (llvm::StringRef key : {"semantic_path", "authored_path", "identity"})
      if (auto value = llvm::dyn_cast_or_null<StringAttr>(metadata.get(key)))
        metadata.set(key, builder.getStringAttr(remapPath(value.getValue(), original, actual)));
    metadata.set("hierarchy_path", builder.getStringAttr(actual));
    op->setAttr("metadata", metadata.getDictionary(outer.getContext()));
    if (auto value = op->getAttrOfType<StringAttr>("source_path"))
      op->setAttr("source_path",
                  builder.getStringAttr(remapPath(value.getValue(), original, actual)));
  }
  void record(llvm::StringRef source, llvm::StringRef target, llvm::StringRef kind) {
    correspondence.push_back(
        builder.getDictionaryAttr({builder.getNamedAttr("source", builder.getStringAttr(source)),
                                   builder.getNamedAttr("target", builder.getStringAttr(target)),
                                   builder.getNamedAttr("kind", builder.getStringAttr(kind))}));
  }
  std::string sourceIdentity(Operation *op, llvm::StringRef original, llvm::StringRef actual) {
    auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
    auto semantic = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
    if (semantic && !semantic.getValue().empty())
      return remapPath(semantic.getValue(), original, actual);
    llvm::StringRef local = text(op, "name");
    if (local.empty())
      local = text(op, "sym_name");
    return (actual + "." + local).str();
  }
  LogicalResult emit(std::size_t index, const std::string &actual,
                     const llvm::StringMap<Value> &ports, IRMapping &values,
                     llvm::StringMap<std::string> &symbols, Operation *instance) {
    auto &definition = definitions[index];
    const bool isRoot = !instance;
    const uint64_t occurrence = ordinal++;
    llvm::StringRef original = path(definition.op);
    Block &body = output->getRegion(0).front();
    record(actual, text(output, "sym_name"), "instance");
    for (Operation &op : definition.op->getRegion(0).front()) {
      if (!isRoot && llvm::isa<TerminalOp>(op)) {
        Value anchor = ports.lookup(text(&op, "name"));
        if (!anchor)
          return reject(&op, "materialized terminal has no mapped anchor");
        values.map(op.getResult(0), anchor);
        record(sourceIdentity(&op, original, actual), text(anchor.getDefiningOp(), "name"),
               "terminal");
      }
      if (auto symbol = op.getAttrOfType<StringAttr>("sym_name"))
        symbols[symbol.getValue()] =
            isRoot ? symbol.getValue().str() : reserve(symbol.getValue(), occurrence);
    }
    // Root declarations and ordinary source operation order are unchanged. Child
    // symbol references remain symbolic and are scoped to their exact occurrence.
    for (Operation &op : definition.op->getRegion(0).front()) {
      if (structural(&op) || llvm::isa<ProbeOp>(op) ||
          (!isRoot && llvm::isa<TerminalOp, ParameterValueOp>(op)))
        continue;
      Operation *cloned = op.clone(values);
      body.push_back(cloned);
      cloned->walk([&](Operation *nested) {
        if (!isRoot)
          provenance(nested, original, actual);
        for (llvm::StringRef key : {"parameter", "callee"})
          if (auto reference = nested->getAttrOfType<FlatSymbolRefAttr>(key)) {
            auto found = symbols.find(reference.getValue());
            if (found != symbols.end())
              nested->setAttr(key, FlatSymbolRefAttr::get(outer.getContext(), found->second));
          }
      });
      if (auto symbol = op.getAttrOfType<StringAttr>("sym_name"))
        cloned->setAttr("sym_name", builder.getStringAttr(symbols.lookup(symbol.getValue())));
      if (llvm::isa<ParameterOp>(op))
        materializedParameters[text(cloned, "sym_name")] = cloned;
      if (!isRoot && llvm::isa<NodeOp, BranchOp>(op) && !text(&op, "name").empty())
        cloned->setAttr("name", builder.getStringAttr(reserve(text(&op, "name"), occurrence)));
      if (!isRoot && llvm::isa<ParameterOp>(op)) {
        cloned->setAttr("variability", builder.getStringAttr("fixed"));
        auto bindings = instance->getAttrOfType<DictionaryAttr>("parameter_bindings");
        if (auto literal = bindings.get(text(&op, "sym_name")))
          cloned->setAttr("default_value", literal);
        // The parent occurrence's mapped override information is attached below
        // by emitChildren, after all enclosing declarations have been cloned.
        record(sourceIdentity(&op, original, actual), text(cloned, "sym_name"), "parameter");
      }
    }
    if (!isRoot) {
      auto parent = instance->getParentOp();
      auto &parentDefinition = definitions[indices.lookup(parent)];
      for (const auto &entry : parentDefinition.overrides[instance]) {
        auto actualName = actualParameters[instance].lookup(entry.getKey());
        Operation *parameter = materializedParameters.lookup(symbols.lookup(entry.getKey()));
        Operation *parentParameter = materializedParameters.lookup(actualName);
        if (!parameter || !parentParameter)
          return reject(instance, "materialized direct parameter binding failed to resolve");
        parameter->setAttr("default_value", parentParameter->getAttr("default_value"));
        OperationState reference(instance->getLoc(), "nodal.const_parameter_ref");
        reference.addTypes(parameter->getAttrOfType<TypeAttr>("type").getValue());
        reference.addAttribute("parameter", FlatSymbolRefAttr::get(outer.getContext(), actualName));
        reference.addAttribute("metadata", parameter->getAttr("metadata"));
        Operation *value = Operation::create(reference);
        body.push_back(value);
        OperationState attachment(instance->getLoc(), "nodal.parameter_value");
        attachment.addOperands(value->getResult(0));
        attachment.addAttribute(
            "parameter", FlatSymbolRefAttr::get(outer.getContext(), text(parameter, "sym_name")));
        attachment.addAttribute("metadata", parameter->getAttr("metadata"));
        body.push_back(Operation::create(attachment));
      }
    }
    for (Operation *child : definition.topology.instances) {
      llvm::StringMap<Value> childPorts;
      for (const auto &port : definition.ports[child])
        childPorts[port.getKey()] = values.lookupOrNull(port.second);
      for (const auto &entry : definition.overrides[child]) {
        auto reference =
            entry.second->getOperand(0).getDefiningOp()->getAttrOfType<FlatSymbolRefAttr>(
                "parameter");
        actualParameters[child][entry.getKey()] = symbols.lookup(reference.getValue());
      }
      auto metadata = child->getAttrOfType<DictionaryAttr>("metadata");
      auto childPath = metadata ? metadata.getAs<StringAttr>("child_path") : StringAttr();
      std::string childActual = childPath ? remapPath(childPath.getValue(), original, actual)
                                          : actual + "." + text(child, "sym_name").str();
      IRMapping childValues;
      llvm::StringMap<std::string> childSymbols;
      if (failed(emit(indices.lookup(resolveInstanceModuleDefinition(child)), childActual,
                      childPorts, childValues, childSymbols, child)))
        return failure();
    }
    return success();
  }

  mlir::ModuleOp outer;
  Builder builder;
  std::vector<Definition> definitions;
  llvm::DenseMap<Operation *, std::size_t> indices;
  llvm::DenseMap<Operation *, llvm::StringMap<std::string>> actualParameters;
  llvm::StringMap<Operation *> materializedParameters;
  std::size_t rootIndex = static_cast<std::size_t>(-1);
  Operation *output = nullptr;
  uint64_t ordinal = 0;
  llvm::StringSet<> reserved;
  llvm::SmallVector<Attribute> correspondence;
};
} // namespace

LogicalResult materializeConservativeInterfaceBoundary(mlir::ModuleOp module) {
  return InterfaceMaterializer(module).run();
}

LogicalResult materializeScalarElectricalHierarchy(mlir::ModuleOp module) {
  return Materializer(module).run();
}
} // namespace nodal
