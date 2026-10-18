#include "mlir/IR/BuiltinAttributes.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogEquationModel.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"

#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringSet.h"

#include <string>
#include <utility>
using namespace mlir;
namespace nodal {
namespace {
constexpr llvm::StringLiteral inventoryName = "nodal.bridge.analog_semantics";
LogicalResult fail(Operation *op, llvm::StringRef code, const llvm::Twine &message) {
  return emitMappedFailure(op, code, message);
}
bool nonempty(DictionaryAttr record, llvm::StringRef key) {
  auto value = record.getAs<StringAttr>(key);
  return value && !value.getValue().trim().empty();
}
llvm::StringRef semanticOwner(Operation *op) {
  if (auto metadata = op->getAttrOfType<DictionaryAttr>("metadata"))
    if (auto path = metadata.getAs<StringAttr>("semantic_path"))
      return path.getValue();
  if (auto symbol = op->getAttrOfType<StringAttr>("sym_name"))
    return symbol.getValue();
  return {};
}

// F32 inventories include authored occurrence owners even when the bridge emits
// only one shared module definition. Resolve the existing instance child_path
// aliases lazily, per requested owner; do not expand the instantiated hierarchy.
class InventoryOwners {
public:
  explicit InventoryOwners(mlir::ModuleOp module) {
    llvm::StringMap<Operation *> definitions;
    for (Operation &op : module.getBody()->getOperations()) {
      if (op.getName().getStringRef() != "nodal.module")
        continue;
      auto symbol = op.getAttrOfType<StringAttr>("sym_name");
      llvm::StringRef path = semanticOwner(&op);
      if (!symbol || path.empty() || !definitions.try_emplace(symbol.getValue(), &op).second ||
          !owners.insert(path).second)
        valid = false;
    }
    module.walk([&](Operation *op) {
      if (op->getName().getStringRef() != "nodal.instance")
        return;
      auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
      auto child = metadata ? metadata.getAs<StringAttr>("child_path") : StringAttr();
      if (!child)
        return; // Hand-authored native instances need not carry bridge aliases.
      auto reference = op->getAttrOfType<FlatSymbolRefAttr>("module");
      Operation *target = reference ? definitions.lookup(reference.getValue()) : nullptr;
      Operation *parent = op->getParentOp();
      while (parent && parent->getName().getStringRef() != "nodal.module")
        parent = parent->getParentOp();
      llvm::StringRef parentPath = parent ? semanticOwner(parent) : llvm::StringRef();
      llvm::StringRef childPath = child.getValue();
      auto instancePath = metadata.getAs<StringAttr>("semantic_path");
      if (!instancePath || instancePath.getValue() != childPath.str() + "_instance") {
        valid = false;
        return;
      }
      if (!target || parentPath.empty() || !childPath.starts_with(parentPath) ||
          !childPath.drop_front(parentPath.size()).starts_with(".") ||
          childPath.size() <= parentPath.size() + 1 ||
          childPath.drop_front(parentPath.size() + 1).contains('.')) {
        valid = false;
        return;
      }
      auto [entry, inserted] = aliases.try_emplace(childPath, semanticOwner(target).str());
      if (!inserted && entry->second != semanticOwner(target))
        valid = false;
    });
  }

  bool contains(llvm::StringRef owner) {
    if (!valid)
      return false;
    if (auto cached = resolved.find(owner); cached != resolved.end())
      return cached->second;
    std::string current = owner.str();
    bool found = false;
    for (size_t step = 0; step <= aliases.size(); ++step) {
      if (owners.contains(current)) {
        found = true;
        break;
      }
      llvm::StringRef prefix = current;
      bool mapped = false;
      while (!prefix.empty()) {
        auto alias = aliases.find(prefix);
        if (alias != aliases.end()) {
          std::string next =
              alias->second + llvm::StringRef(current).drop_front(prefix.size()).str();
          if (next != current) {
            current = std::move(next);
            mapped = true;
          }
          break;
        }
        size_t dot = prefix.rfind('.');
        prefix = dot == llvm::StringRef::npos ? llvm::StringRef() : prefix.take_front(dot);
      }
      if (!mapped)
        break;
    }
    resolved.try_emplace(owner, found);
    return found;
  }

private:
  bool valid = true;
  llvm::StringSet<> owners;
  llvm::StringMap<std::string> aliases;
  llvm::StringMap<bool> resolved;
};

} // namespace
LogicalResult verifyAnalogSemanticInventory(mlir::ModuleOp module) {
  Attribute attribute = module->getAttr(inventoryName);
  if (!attribute)
    return success();
  auto inventory = llvm::dyn_cast<ArrayAttr>(attribute);
  auto invalid = [&]() {
    return fail(module, "NODAL-ANALOG-045-001", "invalid structural F32 analog semantic inventory");
  };
  if (!inventory)
    return invalid();
  llvm::StringSet<> equationIdentities;
  llvm::StringSet<> contributionIdentities;
  InventoryOwners owners(module);
  for (Attribute entry : inventory) {
    auto record = llvm::dyn_cast<DictionaryAttr>(entry);
    if (!record)
      return invalid();
    for (llvm::StringRef key : {"kind", "identity", "owner", "continuity", "source_file"})
      if (!nonempty(record, key))
        return invalid();
    if (!record.getAs<StringAttr>("guard"))
      return invalid();
    llvm::StringRef owner = record.getAs<StringAttr>("owner").getValue();
    llvm::StringRef identity = record.getAs<StringAttr>("identity").getValue();
    if (!owners.contains(owner) || !identity.starts_with(owner) ||
        !identity.drop_front(owner.size()).starts_with(".") || identity.size() <= owner.size() + 1)
      return invalid();
    for (llvm::StringRef key : {"source_line", "source_column"}) {
      auto number = record.getAs<IntegerAttr>(key);
      if (!number || !number.getType().isInteger(64) || number.getInt() < 1)
        return invalid();
    }
    auto analyses = record.getAs<ArrayAttr>("analyses");
    if (!analyses || analyses.empty())
      return invalid();
    llvm::StringSet<> uniqueAnalyses;
    for (Attribute analysis : analyses) {
      auto text = llvm::dyn_cast<StringAttr>(analysis);
      if (!text)
        return invalid();
      llvm::StringRef name = text.getValue();
      // F32 source applicability spells operating-point with a hyphen. The
      // activity-query enum in F44 is a different contract.
      if ((name != "initialization" && name != "dc" && name != "operating-point" &&
           name != "transient" && name != "ac" && name != "noise") ||
          !uniqueAnalyses.insert(name).second)
        return invalid();
    }
    llvm::StringRef kind = record.getAs<StringAttr>("kind").getValue();
    if (kind == "equation" || kind == "initial_equation") {
      if (!equationIdentities.insert(identity).second)
        return invalid();
      for (llvm::StringRef key : {"authored_left", "authored_right", "dimension"})
        if (!nonempty(record, key))
          return invalid();
      auto convention = record.getAs<StringAttr>("residual_convention");
      auto oriented = record.getAs<BoolAttr>("causally_oriented");
      auto divided = record.getAs<BoolAttr>("divided");
      if (!isCanonicalDimensionSignature(record.getAs<StringAttr>("dimension").getValue()) ||
          !convention || convention.getValue() != "lhs-minus-rhs-equals-zero" || !oriented ||
          oriented.getValue() || !divided || divided.getValue() ||
          (kind == "initial_equation" &&
           (uniqueAnalyses.size() != 1 || !uniqueAnalyses.contains("initialization"))))
        return invalid();
    } else if (kind == "contribution") {
      if (!contributionIdentities.insert(identity).second)
        return invalid();
      for (llvm::StringRef key :
           {"target_identity", "target_kind", "target_dimension", "target_orientation", "value"})
        if (!nonempty(record, key))
          return invalid();
      llvm::StringRef targetKind = record.getAs<StringAttr>("target_kind").getValue();
      if ((targetKind != "potential" && targetKind != "flow") ||
          !isCanonicalDimensionSignature(record.getAs<StringAttr>("target_dimension").getValue()))
        return invalid();
    } else {
      return invalid();
    }
  }
  return success();
}

} // namespace nodal
