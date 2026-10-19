#include "nodal/Backend/ScalarHierarchyMaterialization.h"

#include "mlir/IR/Builders.h"
#include "mlir/IR/IRMapping.h"
#include "mlir/IR/SymbolTable.h"
#include "nodal/Backend/Backend.h"
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

LogicalResult materializeScalarElectricalHierarchy(mlir::ModuleOp module) {
  return Materializer(module).run();
}
} // namespace nodal
