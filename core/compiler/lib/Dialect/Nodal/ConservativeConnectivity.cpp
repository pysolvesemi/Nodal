#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"

#include "mlir/IR/Builders.h"
#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/OwningOpRef.h"
#include "mlir/IR/SymbolTable.h"
#include "mlir/IR/Verifier.h"
#include "nodal/Dialect/Nodal/NatureDiscipline.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/SmallPtrSet.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/ADT/StringSet.h"
#include "llvm/Support/Casting.h"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <initializer_list>
#include <map>
#include <string>
#include <utility>
#include <vector>

using namespace mlir;

namespace {

constexpr llvm::StringLiteral kGeneratedBy = "increment28-conservative-connectivity";

bool isNamed(Operation *operation, llvm::StringRef name) {
  return operation && operation->getName().getStringRef() == name;
}

llvm::StringRef textAttr(Operation *operation, llvm::StringRef name) {
  if (auto value = operation->getAttrOfType<StringAttr>(name))
    return value.getValue();
  return {};
}

bool oneOf(llvm::StringRef value, std::initializer_list<llvm::StringRef> choices) {
  return llvm::any_of(choices, [&](llvm::StringRef choice) { return value == choice; });
}

bool isCanonicalText(llvm::StringRef value) {
  if (value.empty() || value != value.trim())
    return false;
  return llvm::all_of(value, [](char character) {
    const unsigned char byte = static_cast<unsigned char>(character);
    return byte >= 0x20 && byte != 0x7f;
  });
}

Operation *enclosingNodalModule(Operation *operation) {
  for (Operation *current = operation; current; current = current->getParentOp()) {
    if (isNamed(current, "nodal.module"))
      return current;
  }
  return nullptr;
}

Block *moduleBody(Operation *module) {
  if (!module || !isNamed(module, "nodal.module") || module->getNumRegions() != 1 ||
      module->getRegion(0).empty())
    return nullptr;
  return &module->getRegion(0).front();
}

Block *structuralBody(Operation *scope) {
  if (!llvm::isa_and_nonnull<nodal::ModuleOp, nodal::GenerateOp>(scope) ||
      scope->getNumRegions() != 1 || !llvm::hasSingleElement(scope->getRegion(0)))
    return nullptr;
  return &scope->getRegion(0).front();
}
bool structurallyPrecedes(Operation *definition, Operation *use) {
  if (!definition || !use)
    return false;
  if (definition == use)
    return true;
  for (Operation *anchor = use; anchor; anchor = anchor->getParentOp())
    if (anchor->getBlock() == definition->getBlock())
      return definition->isBeforeInBlock(anchor);
  return false;
}

Operation *componentContract(Operation *module) {
  Block *body = moduleBody(module);
  if (!body)
    return nullptr;
  Operation *found = nullptr;
  for (Operation &operation : *body) {
    if (!isNamed(&operation, "nodal.component_contract"))
      continue;
    if (found)
      return nullptr;
    found = &operation;
  }
  return found;
}

bool generatedOperation(Operation *operation) {
  if (!operation)
    return false;
  llvm::StringRef name = operation->getName().getStringRef();
  return name == "nodal.connection_set" || name == "nodal.potential_equality" ||
         name == "nodal.reference_potential" || name == "nodal.flow_conservation";
}

llvm::StringRef metadataText(Operation *operation, llvm::StringRef name) {
  auto metadata =
      operation ? operation->getAttrOfType<DictionaryAttr>("metadata") : DictionaryAttr();
  auto value = metadata ? metadata.getAs<StringAttr>(name) : StringAttr();
  return value ? value.getValue() : llvm::StringRef();
}

bool hasCompilerOwnedMetadata(Operation *operation) {
  auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata");
  auto compilerOwned = metadata ? metadata.getAs<BoolAttr>("compiler_owned") : BoolAttr();
  return metadataText(operation, "generated_by") == kGeneratedBy && compilerOwned &&
         compilerOwned.getValue();
}

bool isCanonicalStringArray(ArrayAttr values, bool requireNonEmpty = false) {
  if (!values || (requireNonEmpty && values.empty()))
    return false;
  return llvm::all_of(values, [](Attribute value) {
    auto text = llvm::dyn_cast<StringAttr>(value);
    return text && isCanonicalText(text.getValue());
  });
}

DictionaryAttr generatedMetadata(Builder &builder, llvm::StringRef componentKind) {
  return builder.getDictionaryAttr({
      builder.getNamedAttr("compiler_owned", builder.getBoolAttr(true)),
      builder.getNamedAttr("generated_by", builder.getStringAttr(kGeneratedBy)),
      builder.getNamedAttr("component_kind", builder.getStringAttr(componentKind)),
  });
}

ArrayAttr stringArray(Builder &builder, const std::vector<std::string> &values) {
  llvm::SmallVector<Attribute, 8> attributes;
  attributes.reserve(values.size());
  for (const std::string &value : values)
    attributes.push_back(builder.getStringAttr(value));
  return builder.getArrayAttr(attributes);
}

ArrayAttr signArray(Builder &builder, const std::vector<int> &values) {
  llvm::SmallVector<Attribute, 8> attributes;
  attributes.reserve(values.size());
  Type i32 = builder.getI32Type();
  for (int value : values)
    attributes.push_back(builder.getIntegerAttr(i32, value));
  return builder.getArrayAttr(attributes);
}

FailureOr<Operation *> resolveConservativeDiscipline(Operation *scope, llvm::StringRef discipline) {
  if (!scope || discipline.trim().empty())
    return failure();
  auto reference = FlatSymbolRefAttr::get(scope->getContext(), discipline);
  FailureOr<Operation *> declaration = nodal::resolveDisciplineDeclaration(scope, reference);
  if (failed(declaration))
    return failure();
  auto domain = (*declaration)->getAttrOfType<StringAttr>("domain");
  auto flow = (*declaration)->getAttrOfType<FlatSymbolRefAttr>("flow");
  if (!domain || domain.getValue() != "continuous" || !flow)
    return failure();
  return declaration;
}

FailureOr<std::string> canonicalDiscipline(Operation *scope, llvm::StringRef discipline) {
  FailureOr<Operation *> declaration = resolveConservativeDiscipline(scope, discipline);
  if (failed(declaration))
    return failure();
  auto symbol = (*declaration)->getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName());
  if (!symbol || symbol.getValue().empty())
    return failure();
  return symbol.getValue().str();
}

FailureOr<bool> compatibleConservativeDisciplines(Operation *scope, llvm::StringRef lhs,
                                                  llvm::StringRef rhs) {
  if (failed(resolveConservativeDiscipline(scope, lhs)) ||
      failed(resolveConservativeDiscipline(scope, rhs)))
    return failure();
  return nodal::areDisciplinesCompatible(scope, FlatSymbolRefAttr::get(scope->getContext(), lhs),
                                         FlatSymbolRefAttr::get(scope->getContext(), rhs));
}

Operation *lookupLocalSymbol(Operation *operation, FlatSymbolRefAttr reference) {
  Operation *module = enclosingNodalModule(operation);
  Block *body = moduleBody(module);
  if (!body || !reference)
    return nullptr;
  for (Operation &candidate : *body) {
    auto symbol = candidate.getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName());
    if (symbol && symbol.getValue() == reference.getValue())
      return &candidate;
  }
  return nullptr;
}

bool connectionSetContains(Operation *connectionSet, Value value) {
  return connectionSet && llvm::is_contained(connectionSet->getOperands(), value);
}

LogicalResult verifyConnectionSetReference(Operation *operation, FlatSymbolRefAttr reference,
                                           Operation *&connectionSet) {
  connectionSet = lookupLocalSymbol(operation, reference);
  if (!connectionSet || !isNamed(connectionSet, "nodal.connection_set"))
    return operation->emitOpError(
        "NODAL-CONNECTION-SET-001: generated equation references an unknown connection set");
  return success();
}

LogicalResult verifyTerminalOperands(Operation *operation, unsigned minimum, llvm::StringRef code) {
  if (operation->getNumOperands() < minimum)
    return operation->emitOpError()
           << code << ": requires at least " << minimum << " conservative endpoints";
  llvm::StringRef first;
  for (Value operand : operation->getOperands()) {
    auto terminal = llvm::dyn_cast<nodal::TerminalType>(operand.getType());
    if (!terminal)
      return operation->emitOpError() << code << ": all endpoints must have !nodal.terminal type";
    if (first.empty()) {
      first = terminal.getDiscipline();
      if (failed(resolveConservativeDiscipline(operation, first)))
        return operation->emitOpError()
               << code
               << ": endpoint discipline must resolve to a continuous conservative discipline";
      continue;
    }
    FailureOr<bool> compatible =
        compatibleConservativeDisciplines(operation, first, terminal.getDiscipline());
    if (failed(compatible) || !*compatible)
      return operation->emitOpError() << code << ": endpoint disciplines are incompatible";
  }
  return success();
}

class UnionFind {
public:
  explicit UnionFind(unsigned size) : parent(size), rank(size, 0) {
    for (unsigned index = 0; index < size; ++index)
      parent[index] = index;
  }

  unsigned find(unsigned value) {
    if (parent[value] != value)
      parent[value] = find(parent[value]);
    return parent[value];
  }

  void unite(unsigned lhs, unsigned rhs) {
    lhs = find(lhs);
    rhs = find(rhs);
    if (lhs == rhs)
      return;
    if (rank[lhs] < rank[rhs])
      std::swap(lhs, rhs);
    parent[rhs] = lhs;
    if (rank[lhs] == rank[rhs])
      ++rank[lhs];
  }

private:
  std::vector<unsigned> parent;
  std::vector<unsigned> rank;
};

uint64_t stableHash(llvm::ArrayRef<std::string> values) {
  uint64_t hash = 14695981039346656037ULL;
  for (const std::string &value : values) {
    for (unsigned char byte : value) {
      hash ^= byte;
      hash *= 1099511628211ULL;
    }
    hash ^= 0xff;
    hash *= 1099511628211ULL;
  }
  return hash;
}

std::string hex64(uint64_t value) {
  static constexpr char digits[] = "0123456789abcdef";
  std::string result(16, '0');
  for (int index = 15; index >= 0; --index) {
    result[static_cast<size_t>(index)] = digits[value & 0xf];
    value >>= 4;
  }
  return result;
}

std::string sanitizeSymbol(llvm::StringRef value) {
  std::string result;
  result.reserve(std::min<size_t>(value.size(), 40));
  bool previousUnderscore = false;
  for (char character : value) {
    if (result.size() >= 40)
      break;
    const unsigned char byte = static_cast<unsigned char>(character);
    if (std::isalnum(byte)) {
      result.push_back(static_cast<char>(std::tolower(byte)));
      previousUnderscore = false;
    } else if (!previousUnderscore) {
      result.push_back('_');
      previousUnderscore = true;
    }
  }
  while (!result.empty() && result.back() == '_')
    result.pop_back();
  if (result.empty())
    result = "connection";
  if (std::isdigit(static_cast<unsigned char>(result.front())))
    result.insert(result.begin(), 'n');
  return result;
}

std::string endpointPath(Operation *operation, llvm::StringRef modulePath) {
  if (nodal::isNodeArraySelection(operation))
    return metadataText(operation, "semantic_path").str();
  llvm::StringRef explicitPath = textAttr(operation, "source_path");
  if (!explicitPath.empty())
    return explicitPath.str();
  llvm::StringRef name = textAttr(operation, "name");
  return modulePath.str() + "." + name.str();
}

bool hasSourceUse(Value value) {
  for (Operation *user : value.getUsers()) {
    if (!generatedOperation(user))
      return true;
  }
  return false;
}

struct EndpointInfo {
  Value value;
  Operation *operation = nullptr;
  std::string path;
  std::string discipline;
  bool terminal = false;
  std::string flowOrientation;
};

using ArrayIndexIdentity = nodal::StaticShapeIndexIdentity;

struct ArraySelectionInfo {
  Operation *operation;
  Value array;
  std::string canonicalKey;
  std::vector<nodal::ParameterIntegerBounds> bounds;
  std::optional<nodal::ParameterIntegerBounds> flatBounds;
  std::string path;
  std::vector<std::string> uses;
};

LogicalResult collectArraySelections(Operation *scope,
                                     std::vector<ArraySelectionInfo> &selections) {
  Operation *module = enclosingNodalModule(scope);
  Block *body = structuralBody(scope);
  if (!body)
    return success();
  llvm::StringRef ownerPath = metadataText(module, "semantic_path");
  if (ownerPath.empty())
    ownerPath = textAttr(module, "sym_name");
  const std::string prefix = (ownerPath + ".").str();
  auto ownedPath = [&](llvm::StringRef path) {
    return isCanonicalText(path) && path.starts_with(prefix) && path.size() > prefix.size();
  };
  for (Operation &operation : *body) {
    if (!nodal::isNodeArraySelection(&operation))
      continue;
    // The operation verifier owns rank, element and all-legal-index proofs.
    if (failed(mlir::verify(&operation)))
      return failure();
    auto resolved = nodal::resolveConservativeSelection(operation.getResult(0), &operation);
    if (failed(resolved))
      return failure();
    ArraySelectionInfo selection{
        &operation, resolved->projection.root,           resolved->projection.canonicalScalarKey,
        {},         resolved->projection.rootFlatBounds, {},
        {}};
    selection.bounds.assign(resolved->projection.rootCoordinateBounds.begin(),
                            resolved->projection.rootCoordinateBounds.end());
    Operation *input = operation.getOperand(0).getDefiningOp();
    selection.path = metadataText(input, "semantic_path").str() + ".at(";
    unsigned indexCount = 0;
    for (Value index : operation.getOperands().drop_front()) {
      auto proof = nodal::resolveShapeIndexProof(index, &operation);
      if (failed(proof))
        return operation.emitOpError("NODAL-SHAPE-043-006: static index identity is unavailable");
      Operation *definition = index.getDefiningOp();
      std::string token;
      if (proof->identity.kind == nodal::StaticShapeIndexIdentity::Induction) {
        token = "induction:" + textAttr(proof->identity.root, "induction_path").str();
      } else if (llvm::isa_and_nonnull<nodal::ConstantOp, nodal::ConstLiteralOp>(definition)) {
        auto literal = definition->getAttrOfType<IntegerAttr>("value");
        if (!literal || !literal.getValue().isSignedIntN(64))
          return operation.emitOpError("NODAL-SHAPE-043-006: invalid literal index identity");
        int64_t value = literal.getValue().getSExtValue();
        token = "literal:" + std::to_string(value);
      } else if (llvm::isa<nodal::ConstParameterRefOp>(definition)) {
        auto reference = definition->getAttrOfType<FlatSymbolRefAttr>("parameter");
        Operation *parameter = nullptr;
        for (Operation &candidate : *moduleBody(module))
          if (reference && llvm::isa<nodal::ParameterOp>(candidate) &&
              textAttr(&candidate, "sym_name") == reference.getValue())
            parameter = &candidate;
        llvm::StringRef path =
            parameter ? metadataText(parameter, "semantic_path") : llvm::StringRef();
        if (!parameter || !ownedPath(path))
          return operation.emitOpError(
              "NODAL-SHAPE-043-006: parameter index source identity is absent");
        token = "parameter:" + path.str();
      } else {
        llvm::StringRef path = metadataText(definition, "semantic_path");
        unsigned matches = 0;
        for (Operation &candidate : *moduleBody(module))
          if (metadataText(&candidate, "semantic_path") == path)
            ++matches;
        if (!llvm::isa<nodal::ConstExprOp>(definition) || !ownedPath(path) || matches != 1)
          return operation.emitOpError(
              "NODAL-SHAPE-043-006: compound index requires a unique owned root");
        token = "expression:" + path.str();
      }
      if (indexCount++ > 0)
        selection.path += ',';
      selection.path += token;
    }
    selection.path += ')';
    if (llvm::isa<nodal::GenerateOp>(scope))
      selection.path += ".in(" + textAttr(scope, "region_id").str() + ")";
    auto metadata = operation.getAttrOfType<DictionaryAttr>("metadata");
    auto uses = metadata ? metadata.getAs<ArrayAttr>("source_uses") : ArrayAttr();
    if (metadataText(&operation, "semantic_path") != selection.path ||
        metadataText(&operation, "input_path") != metadataText(input, "semantic_path") ||
        metadataText(&operation, "storage") != "structural" ||
        metadataText(&operation, "selection_kind") != "node_array" ||
        !isCanonicalStringArray(uses, true))
      return operation.emitOpError(
          "NODAL-SHAPE-043-006: selected endpoint identity or source provenance is invalid");
    for (Attribute attribute : uses) {
      llvm::StringRef use = llvm::cast<StringAttr>(attribute).getValue();
      if (!ownedPath(use) || use == selection.path ||
          (!selection.uses.empty() && use <= llvm::StringRef(selection.uses.back())))
        return operation.emitOpError(
            "NODAL-SHAPE-043-006: source uses must be owned, sorted and unique");
      selection.uses.push_back(use.str());
    }
    for (const ArraySelectionInfo &previous : selections) {
      if (llvm::is_contained(selection.uses, previous.path) ||
          llvm::is_contained(previous.uses, selection.path))
        return operation.emitOpError(
            "NODAL-SHAPE-043-006: source uses cannot alias selected endpoint identities");
      const bool same =
          previous.array == selection.array && previous.canonicalKey == selection.canonicalKey;
      if (!same && previous.path == selection.path)
        return operation.emitOpError(
            "NODAL-SHAPE-043-006: distinct selected endpoints cannot share source identity");
      if (!same) {
        for (const std::string &use : selection.uses)
          if (std::binary_search(previous.uses.begin(), previous.uses.end(), use))
            return operation.emitOpError(
                "NODAL-SHAPE-043-006: one source use cannot name distinct endpoints");
      }
      if (same || previous.array != selection.array || llvm::isa<nodal::GenerateOp>(scope))
        continue; // Distinct generated maps are symbolic families, not global aliases.
      bool disjoint = false;
      if (selection.bounds.size() == previous.bounds.size())
        for (unsigned axis = 0; axis < selection.bounds.size(); ++axis)
          disjoint |= selection.bounds[axis].upper < previous.bounds[axis].lower ||
                      previous.bounds[axis].upper < selection.bounds[axis].lower;
      if (selection.flatBounds && previous.flatBounds)
        disjoint |= selection.flatBounds->upper < previous.flatBounds->lower ||
                    previous.flatBounds->upper < selection.flatBounds->lower;
      if (!disjoint)
        return operation.emitOpError(
            "NODAL-SHAPE-043-006: distinct node-array selectors may alias for legal parameters");
    }
    selections.push_back(std::move(selection));
  }
  return success();
}

struct SetInfo {
  unsigned root = 0;
  std::vector<unsigned> endpointIndices;
  std::vector<Value> members;
  std::vector<std::string> memberPaths;
  std::vector<std::string> provenance;
  std::string discipline;
  std::string symbol;
  bool reference = false;
  std::string referenceScope = "none";
  std::string referenceIdentity;
};

struct BranchInfo {
  Value result;
  unsigned positive = 0;
  unsigned negative = 0;
  std::string path;
};

struct FlowTerm {
  Value value;
  int sign = 0;
  std::string key;
  std::string provenance;
};

LogicalResult materializeModule(Operation *module) {
  Operation *contract = componentContract(module);
  if (!contract)
    return success();

  Block *body = moduleBody(module);
  if (!body)
    return module->emitOpError(
        "NODAL-COMPONENT-CONTRACT-001: physical component requires one module body");

  const llvm::StringRef componentKind = textAttr(contract, "kind");
  const llvm::StringRef ownership = textAttr(contract, "connectivity_ownership");
  const llvm::StringRef modulePath = textAttr(contract, "source_path");
  if (!oneOf(componentKind, {"partial", "concrete"}) || !isCanonicalText(modulePath) ||
      (componentKind == "partial" && ownership != "extensible") ||
      (componentKind == "concrete" && ownership != "local"))
    return contract->emitOpError(
        "NODAL-COMPONENT-CONTRACT-001: invalid physical-component ownership contract");
  const bool partial = componentKind == "partial";

  llvm::SmallVector<Operation *, 16> generated;
  for (Operation &operation : *body) {
    if (!generatedOperation(&operation))
      continue;
    if (!hasCompilerOwnedMetadata(&operation))
      return operation.emitOpError(
          "NODAL-CONNECTION-SET-001: normalized connectivity operations are compiler-owned");
    generated.push_back(&operation);
  }
  for (Operation *operation : generated)
    operation->erase();

  std::vector<EndpointInfo> endpoints;
  llvm::DenseMap<Value, unsigned> endpointIndex;
  llvm::StringSet<> endpointNames;
  llvm::StringSet<> sourcePaths;
  for (Operation &operation : *body) {
    if (!isNamed(&operation, "nodal.terminal") && !isNamed(&operation, "nodal.node") &&
        !isNamed(&operation, "nodal.instance_terminal") && !nodal::isNodeArraySelection(&operation))
      continue;
    if (operation.getNumResults() != 1)
      return operation.emitOpError(
          "NODAL-CONNECTION-001: conservative endpoint must produce one terminal value");
    auto type = llvm::dyn_cast<nodal::TerminalType>(operation.getResult(0).getType());
    if (!type)
      return operation.emitOpError(
          "NODAL-CONNECTION-001: conservative endpoint must use !nodal.terminal type");
    FailureOr<std::string> discipline = canonicalDiscipline(&operation, type.getDiscipline());
    if (failed(discipline))
      return operation.emitOpError(
          "NODAL-TERMINAL-DISCIPLINE-001: endpoint discipline must resolve to a "
          "continuous conservative discipline");

    const llvm::StringRef endpointName = nodal::isNodeArraySelection(&operation)
                                             ? metadataText(&operation, "semantic_path")
                                             : textAttr(&operation, "name");
    if (!isCanonicalText(endpointName) || !endpointNames.insert(endpointName).second)
      return operation.emitOpError(
          "NODAL-CONNECTIVITY-PROVENANCE-001: endpoint names must be canonical and unique "
          "within a physical component");

    Operation *incidence = &operation;
    if (nodal::isNodeArraySelection(&operation)) {
      auto selected = nodal::resolveConservativeSelection(operation.getResult(0), &operation);
      if (failed(selected))
        return failure();
      if (llvm::isa<nodal::TerminalArrayOp>(selected->declaration))
        incidence = selected->declaration;
    }
    const bool terminal = llvm::isa<nodal::TerminalArrayOp>(incidence) ||
                          isNamed(&operation, "nodal.terminal") ||
                          isNamed(&operation, "nodal.instance_terminal");
    if (terminal) {
      llvm::StringRef direction = textAttr(incidence, "direction");
      if (!oneOf(direction, {"input", "output", "inout"}))
        return operation.emitOpError(
            "NODAL-TERMINAL-DIRECTION-001: conservative terminal direction must be input, "
            "output, or inout");
      llvm::StringRef orientation = textAttr(incidence, "flow_orientation");
      if (!oneOf(orientation, {"into_component", "out_of_component"}))
        return operation.emitOpError(
            "NODAL-TERMINAL-ORIENTATION-001: flow orientation must be into_component or "
            "out_of_component");
    }

    std::string path = endpointPath(&operation, modulePath);
    if (!isCanonicalText(path) || !sourcePaths.insert(path).second)
      return operation.emitOpError(
          "NODAL-CONNECTIVITY-PROVENANCE-001: endpoint source paths must be canonical and unique");
    if (!partial && !hasSourceUse(operation.getResult(0)))
      return operation.emitOpError(
          "NODAL-CONNECTION-001: concrete component contains an unowned floating endpoint");

    const unsigned index = static_cast<unsigned>(endpoints.size());
    endpointIndex[operation.getResult(0)] = index;
    endpoints.push_back(EndpointInfo{operation.getResult(0), &operation, std::move(path),
                                     std::move(*discipline), terminal,
                                     textAttr(incidence, "flow_orientation").str()});
  }

  UnionFind sets(static_cast<unsigned>(endpoints.size()));
  std::vector<Operation *> sourceRelations;
  std::vector<Operation *> references;
  std::vector<BranchInfo> branches;
  llvm::StringSet<> connectionIds;
  llvm::StringSet<> aliasIds;
  llvm::StringSet<> namedBranches;
  llvm::StringSet<> implicitPairs;

  auto claimSourcePath = [&](Operation *operation) -> LogicalResult {
    llvm::StringRef path = textAttr(operation, "source_path");
    if (!isCanonicalText(path) || !sourcePaths.insert(path).second)
      return operation->emitOpError(
          "NODAL-CONNECTIVITY-PROVENANCE-001: connectivity source paths must be canonical "
          "and unique");
    return success();
  };

  auto indexOf = [&](Operation *operation, Value value) -> FailureOr<unsigned> {
    auto found = endpointIndex.find(value);
    if (found == endpointIndex.end()) {
      operation->emitOpError(
          "NODAL-CONNECTION-001: connectivity relation references a non-local endpoint");
      return failure();
    }
    return found->second;
  };

  for (Operation &operation : *body) {
    if (isNamed(&operation, "nodal.connect")) {
      llvm::StringRef connectionId = textAttr(&operation, "connection_id");
      if (!isCanonicalText(connectionId) || !connectionIds.insert(connectionId).second)
        return operation.emitOpError(
            "NODAL-CONNECTION-001: connection identities must be canonical and unique");
      if (failed(claimSourcePath(&operation)))
        return failure();
      if (operation.getNumOperands() < 2)
        return operation.emitOpError(
            "NODAL-CONNECTION-001: connection requires at least two endpoints");
      FailureOr<unsigned> first = indexOf(&operation, operation.getOperand(0));
      if (failed(first))
        return failure();
      for (Value member : operation.getOperands().drop_front()) {
        FailureOr<unsigned> next = indexOf(&operation, member);
        if (failed(next))
          return failure();
        sets.unite(*first, *next);
      }
      sourceRelations.push_back(&operation);
    } else if (isNamed(&operation, "nodal.alias")) {
      llvm::StringRef aliasId = textAttr(&operation, "alias_id");
      if (!isCanonicalText(aliasId) || !aliasIds.insert(aliasId).second)
        return operation.emitOpError(
            "NODAL-ALIAS-001: alias identities must be canonical and unique");
      if (failed(claimSourcePath(&operation)))
        return failure();
      FailureOr<unsigned> lhs = indexOf(&operation, operation.getOperand(0));
      FailureOr<unsigned> rhs = indexOf(&operation, operation.getOperand(1));
      if (failed(lhs) || failed(rhs))
        return failure();
      sets.unite(*lhs, *rhs);
      sourceRelations.push_back(&operation);
    } else if (isNamed(&operation, "nodal.reference")) {
      if (failed(claimSourcePath(&operation)))
        return failure();
      if (failed(indexOf(&operation, operation.getOperand(0))))
        return failure();
      references.push_back(&operation);
    } else if (isNamed(&operation, "nodal.branch")) {
      if (failed(claimSourcePath(&operation)))
        return failure();
      if (operation.getNumOperands() != 2 || operation.getNumResults() != 1)
        return operation.emitOpError(
            "NODAL-BRANCH-ORIENTATION-001: branch requires positive and negative endpoints "
            "and one result");
      FailureOr<unsigned> positive = indexOf(&operation, operation.getOperand(0));
      FailureOr<unsigned> negative = indexOf(&operation, operation.getOperand(1));
      if (failed(positive) || failed(negative))
        return failure();
      llvm::StringRef declarationKind = textAttr(&operation, "declaration_kind");
      llvm::StringRef name = textAttr(&operation, "name");
      llvm::StringRef path = textAttr(&operation, "source_path");
      if (!oneOf(declarationKind, {"named", "implicit"}) || !isCanonicalText(path))
        return operation.emitOpError(
            "NODAL-BRANCH-ORIENTATION-001: branch requires named or implicit kind and "
            "canonical source path");
      if ((declarationKind == "named" && name.empty()) ||
          (declarationKind == "implicit" && !name.empty()))
        return operation.emitOpError(
            "NODAL-BRANCH-ORIENTATION-001: named branches require a name and implicit "
            "branches must remain unnamed");
      if (declarationKind == "named") {
        if (!isCanonicalText(name) || !namedBranches.insert(name).second)
          return operation.emitOpError(
              "NODAL-BRANCH-ORIENTATION-001: named branch identities must be canonical and unique");
      } else {
        std::string lhs = endpoints[*positive].path;
        std::string rhs = endpoints[*negative].path;
        if (rhs < lhs)
          std::swap(lhs, rhs);
        std::string key = lhs + "\n" + rhs;
        if (!implicitPairs.insert(key).second)
          return operation.emitOpError(
              "NODAL-BRANCH-IMPLICIT-001: only one implicit branch is permitted for an "
              "endpoint pair");
      }
      branches.push_back(BranchInfo{operation.getResult(0), *positive, *negative, path.str()});
    }
  }

  // References sharing scope and canonical discipline denote one reference
  // identity. Unite them before connection-set identities are assigned.
  llvm::StringMap<unsigned> referenceRoots;
  for (Operation *reference : references) {
    unsigned endpoint = endpointIndex.lookup(reference->getOperand(0));
    llvm::StringRef scope = textAttr(reference, "scope");
    std::string key = scope.str() + "\n" + endpoints[endpoint].discipline;
    auto inserted = referenceRoots.try_emplace(key, endpoint);
    if (!inserted.second)
      sets.unite(inserted.first->second, endpoint);
  }

  std::map<unsigned, std::vector<unsigned>> groups;
  for (unsigned index = 0; index < endpoints.size(); ++index)
    groups[sets.find(index)].push_back(index);

  std::vector<SetInfo> connectionSets;
  llvm::StringMap<unsigned> symbolOwners;
  llvm::StringSet<> existingSymbols;
  for (Operation &operation : *body) {
    if (auto symbol = operation.getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName()))
      existingSymbols.insert(symbol.getValue());
  }
  for (auto &[root, indices] : groups) {
    llvm::sort(indices, [&](unsigned lhs, unsigned rhs) {
      return endpoints[lhs].path < endpoints[rhs].path;
    });
    SetInfo info;
    info.root = root;
    info.endpointIndices = indices;
    for (unsigned index : indices) {
      info.members.push_back(endpoints[index].value);
      info.memberPaths.push_back(endpoints[index].path);
      if (nodal::isNodeArraySelection(endpoints[index].operation)) {
        auto metadata = endpoints[index].operation->getAttrOfType<DictionaryAttr>("metadata");
        for (Attribute use : metadata.getAs<ArrayAttr>("source_uses"))
          info.provenance.push_back("node-array-use:" +
                                    llvm::cast<StringAttr>(use).getValue().str());
      }
      if (info.discipline.empty()) {
        info.discipline = endpoints[index].discipline;
      } else if (info.discipline != endpoints[index].discipline) {
        FailureOr<bool> compatible = compatibleConservativeDisciplines(
            endpoints[index].operation, info.discipline, endpoints[index].discipline);
        if (failed(compatible) || !*compatible)
          return endpoints[index].operation->emitOpError(
              "NODAL-CONNECTION-DISCIPLINE-001: normalized connection set contains "
              "incompatible disciplines");
        if (endpoints[index].discipline < info.discipline)
          info.discipline = endpoints[index].discipline;
      }
    }

    for (Operation *relation : sourceRelations) {
      bool belongs = false;
      for (Value member : relation->getOperands()) {
        auto found = endpointIndex.find(member);
        if (found != endpointIndex.end() && sets.find(found->second) == root) {
          belongs = true;
          break;
        }
      }
      if (belongs) {
        llvm::StringRef path = textAttr(relation, "source_path");
        info.provenance.push_back(relation->getName().getStringRef().drop_front(6).str() + ":" +
                                  path.str());
      }
    }

    for (Operation *reference : references) {
      unsigned endpoint = endpointIndex.lookup(reference->getOperand(0));
      if (sets.find(endpoint) != root)
        continue;
      const std::string scope = textAttr(reference, "scope").str();
      if (info.reference && info.referenceScope != scope)
        return reference->emitOpError(
            "NODAL-REFERENCE-002: one connection set cannot mix global and module reference "
            "scopes");
      info.reference = true;
      info.referenceScope = scope;
      info.provenance.push_back("reference:" + textAttr(reference, "source_path").str());
    }
    llvm::sort(info.provenance);
    info.provenance.erase(std::unique(info.provenance.begin(), info.provenance.end()),
                          info.provenance.end());

    std::vector<std::string> identity = info.memberPaths;
    identity.push_back(info.discipline);
    identity.push_back(modulePath.str());
    info.symbol = "_connection_" + sanitizeSymbol(info.memberPaths.front()) + "_" +
                  hex64(stableHash(identity));
    if (existingSymbols.contains(info.symbol) ||
        !symbolOwners.try_emplace(info.symbol, root).second)
      return module->emitOpError(
          "NODAL-CONNECTION-SET-001: deterministic connection-set symbol collision");
    if (info.reference) {
      if (info.referenceScope == "global")
        info.referenceIdentity = "global::" + info.discipline;
      else
        info.referenceIdentity = modulePath.str() + "::reference::" + info.discipline;
    }

    connectionSets.push_back(std::move(info));
  }

  llvm::sort(connectionSets,
             [](const SetInfo &lhs, const SetInfo &rhs) { return lhs.symbol < rhs.symbol; });
  OpBuilder builder(module->getContext());
  builder.setInsertionPointToEnd(body);
  DictionaryAttr metadata = generatedMetadata(builder, componentKind);
  const bool complete = !partial;

  for (const SetInfo &info : connectionSets) {
    OperationState state(contract->getLoc(), nodal::ConnectionSetOp::getOperationName());
    state.addOperands(info.members);
    state.propertiesAttr = builder.getDictionaryAttr({
        builder.getNamedAttr(SymbolTable::getSymbolAttrName(), builder.getStringAttr(info.symbol)),
        builder.getNamedAttr("discipline",
                             FlatSymbolRefAttr::get(module->getContext(), info.discipline)),
        builder.getNamedAttr("ownership", builder.getStringAttr(ownership)),
        builder.getNamedAttr("complete", builder.getBoolAttr(complete)),
        builder.getNamedAttr("reference", builder.getBoolAttr(info.reference)),
        builder.getNamedAttr("reference_scope", builder.getStringAttr(info.referenceScope)),
        builder.getNamedAttr("reference_identity", builder.getStringAttr(info.referenceIdentity)),
        builder.getNamedAttr("member_paths", stringArray(builder, info.memberPaths)),
        builder.getNamedAttr("provenance", stringArray(builder, info.provenance)),
        builder.getNamedAttr("metadata", metadata),
    });
    builder.create(state);
  }

  auto setReference = [&](const SetInfo &info) {
    return FlatSymbolRefAttr::get(module->getContext(), info.symbol);
  };

  for (const SetInfo &info : connectionSets) {
    std::vector<std::string> equationProvenance = info.provenance;
    if (equationProvenance.empty())
      equationProvenance = info.memberPaths;

    for (unsigned index = 1; index < info.members.size(); ++index) {
      OperationState state(contract->getLoc(), nodal::PotentialEqualityOp::getOperationName());
      state.addOperands({info.members.front(), info.members[index]});
      state.propertiesAttr = builder.getDictionaryAttr({
          builder.getNamedAttr("connection_set", setReference(info)),
          builder.getNamedAttr("provenance", stringArray(builder, equationProvenance)),
          builder.getNamedAttr("metadata", metadata),
      });
      builder.create(state);
    }

    if (info.reference) {
      OperationState state(contract->getLoc(), nodal::ReferencePotentialOp::getOperationName());
      state.addOperands({info.members.front()});
      state.propertiesAttr = builder.getDictionaryAttr({
          builder.getNamedAttr("connection_set", setReference(info)),
          builder.getNamedAttr("reference_identity", builder.getStringAttr(info.referenceIdentity)),
          builder.getNamedAttr("provenance", stringArray(builder, equationProvenance)),
          builder.getNamedAttr("metadata", metadata),
      });
      builder.create(state);
    }

    std::vector<FlowTerm> flowTerms;
    for (unsigned endpointIndexValue : info.endpointIndices) {
      const EndpointInfo &endpoint = endpoints[endpointIndexValue];
      if (!endpoint.terminal)
        continue;
      const int sign = endpoint.flowOrientation == "into_component" ? -1 : 1;
      flowTerms.push_back(FlowTerm{endpoint.value, sign, "terminal:" + endpoint.path,
                                   "terminal-flow:" + endpoint.path});
    }
    for (const BranchInfo &branch : branches) {
      if (sets.find(branch.positive) == info.root)
        flowTerms.push_back(FlowTerm{branch.result, 1, "branch:+:" + branch.path,
                                     "branch-positive:" + branch.path});
      if (sets.find(branch.negative) == info.root)
        flowTerms.push_back(FlowTerm{branch.result, -1, "branch:-:" + branch.path,
                                     "branch-negative:" + branch.path});
    }
    llvm::sort(flowTerms, [](const FlowTerm &lhs, const FlowTerm &rhs) {
      if (lhs.key != rhs.key)
        return lhs.key < rhs.key;
      return lhs.sign < rhs.sign;
    });
    if (complete && flowTerms.empty())
      return module->emitOpError(
          "NODAL-CONNECTION-FLOW-001: concrete connection set has no owned flow terms");

    llvm::SmallVector<Value, 8> terms;
    std::vector<int> signs;
    std::vector<std::string> flowProvenance;
    for (const FlowTerm &term : flowTerms) {
      terms.push_back(term.value);
      signs.push_back(term.sign);
      flowProvenance.push_back(term.provenance);
    }
    if (flowProvenance.empty())
      flowProvenance = equationProvenance;

    OperationState state(contract->getLoc(), nodal::FlowConservationOp::getOperationName());
    state.addOperands(terms);
    state.propertiesAttr = builder.getDictionaryAttr({
        builder.getNamedAttr("signs", signArray(builder, signs)),
        builder.getNamedAttr("connection_set", setReference(info)),
        builder.getNamedAttr("ownership", builder.getStringAttr(ownership)),
        builder.getNamedAttr("complete", builder.getBoolAttr(complete)),
        builder.getNamedAttr("provenance", stringArray(builder, flowProvenance)),
        builder.getNamedAttr("metadata", metadata),
    });
    builder.create(state);
  }

  return success();
}

LogicalResult materializeInPlace(mlir::ModuleOp module) {
  if (failed(nodal::normalizeNodeArraySelections(module)))
    return failure();
  bool optedIn = false;
  llvm::StringSet<> componentPaths;
  for (Operation &operation : module.getBody()->getOperations()) {
    if (!isNamed(&operation, "nodal.module"))
      continue;
    if (Operation *contract = componentContract(&operation)) {
      optedIn = true;
      llvm::StringRef path = textAttr(contract, "source_path");
      if (!isCanonicalText(path) || !componentPaths.insert(path).second)
        return contract->emitOpError(
            "NODAL-CONNECTIVITY-PROVENANCE-001: component source paths must be canonical "
            "and unique");
    }
    if (failed(materializeModule(&operation)))
      return failure();
  }
  if (optedIn)
    module->setAttr("nodal.connectivity.normalized", StringAttr::get(module.getContext(), "v1"));
  return success();
}

void commitModule(mlir::ModuleOp destination, mlir::ModuleOp source) {
  destination->setLoc(source->getLoc());
  destination->setAttrs(source->getAttrDictionary());
  destination.getBodyRegion().takeBody(source.getBodyRegion());
}

} // namespace

LogicalResult nodal::ComponentContractOp::verify() {
  llvm::StringRef kind = textAttr(getOperation(), "kind");
  llvm::StringRef ownership = textAttr(getOperation(), "connectivity_ownership");
  llvm::StringRef sourcePath = textAttr(getOperation(), "source_path");
  if (!oneOf(kind, {"partial", "concrete"}) || !isCanonicalText(sourcePath))
    return emitOpError(
        "NODAL-COMPONENT-CONTRACT-001: component kind and canonical source path are required");
  if ((kind == "partial" && ownership != "extensible") ||
      (kind == "concrete" && ownership != "local"))
    return emitOpError(
        "NODAL-COMPONENT-CONTRACT-001: partial components require extensible ownership and "
        "concrete components require local ownership");
  Operation *module = enclosingNodalModule(getOperation());
  unsigned contracts = 0;
  if (Block *body = moduleBody(module)) {
    for (Operation &operation : *body)
      contracts += isNamed(&operation, "nodal.component_contract");
  }
  if (contracts != 1)
    return emitOpError(
        "NODAL-COMPONENT-CONTRACT-001: physical module requires exactly one component contract");
  return success();
}

LogicalResult nodal::ConnectOp::verify() {
  if (failed(verifyGeneratedStructuralOwnership(getOperation())))
    return failure();
  if (!isCanonicalText(textAttr(getOperation(), "connection_id")) ||
      !isCanonicalText(textAttr(getOperation(), "source_path")))
    return emitOpError(
        "NODAL-CONNECTION-001: connection identity and source path must be canonical");
  return verifyTerminalOperands(getOperation(), 2, "NODAL-CONNECTION-DISCIPLINE-001");
}

LogicalResult nodal::AliasOp::verify() {
  if (!isCanonicalText(textAttr(getOperation(), "alias_id")) ||
      !isCanonicalText(textAttr(getOperation(), "source_path")))
    return emitOpError("NODAL-ALIAS-001: alias identity and source path must be canonical");
  return verifyTerminalOperands(getOperation(), 2, "NODAL-CONNECTION-DISCIPLINE-001");
}

LogicalResult nodal::ReferenceOp::verify() {
  if (!oneOf(textAttr(getOperation(), "scope"), {"global", "module"}) ||
      !isCanonicalText(textAttr(getOperation(), "source_path")))
    return emitOpError(
        "NODAL-REFERENCE-001: reference requires global or module scope and canonical source path");
  return verifyTerminalOperands(getOperation(), 1, "NODAL-REFERENCE-001");
}

LogicalResult nodal::ConnectionSetOp::verify() {
  if (!hasCompilerOwnedMetadata(getOperation()))
    return emitOpError(
        "NODAL-CONNECTION-SET-001: connection sets are compiler-owned normalized topology records");
  if (failed(verifyTerminalOperands(getOperation(), 1, "NODAL-CONNECTION-SET-001")))
    return failure();
  auto discipline = getOperation()->getAttrOfType<FlatSymbolRefAttr>("discipline");
  if (!discipline)
    return emitOpError("NODAL-CONNECTION-SET-001: connection-set discipline is required");
  FailureOr<std::string> canonical = canonicalDiscipline(getOperation(), discipline.getValue());
  if (failed(canonical) || *canonical != discipline.getValue())
    return emitOpError(
        "NODAL-CONNECTION-SET-001: connection-set discipline must be canonical, continuous, "
        "and conservative");
  auto paths = getOperation()->getAttrOfType<ArrayAttr>("member_paths");
  auto provenance = getOperation()->getAttrOfType<ArrayAttr>("provenance");
  if (!paths || paths.size() != getOperation()->getNumOperands() || !provenance)
    return emitOpError(
        "NODAL-CONNECTION-SET-001: member paths and provenance must match the normalized set");
  llvm::StringRef ownership = textAttr(getOperation(), "ownership");
  auto complete = getOperation()->getAttrOfType<BoolAttr>("complete");
  if (!oneOf(ownership, {"local", "extensible"}) || !complete ||
      (complete.getValue() && ownership != "local") ||
      (!complete.getValue() && ownership != "extensible"))
    return emitOpError(
        "NODAL-CONNECTION-SET-001: completeness must agree with connectivity ownership");
  llvm::StringSet<> retainedPaths;
  std::vector<std::string> symbolIdentity;
  symbolIdentity.reserve(paths.size() + 2);
  std::string previousPath;
  for (auto [index, value] : llvm::enumerate(paths)) {
    auto path = llvm::dyn_cast<StringAttr>(value);
    if (!path || !isCanonicalText(path.getValue()) || !retainedPaths.insert(path.getValue()).second)
      return emitOpError("NODAL-CONNECTION-SET-001: member paths must be canonical and unique");
    if (!previousPath.empty() && path.getValue() < llvm::StringRef(previousPath))
      return emitOpError(
          "NODAL-CONNECTION-SET-001: member paths must retain deterministic sorted order");
    Operation *definition = getOperation()->getOperand(index).getDefiningOp();
    Operation *module = enclosingNodalModule(getOperation());
    Operation *contract = componentContract(module);
    if (!definition || !contract ||
        path.getValue() != endpointPath(definition, textAttr(contract, "source_path")))
      return emitOpError(
          "NODAL-CONNECTION-SET-001: member paths must match their source endpoints");
    previousPath = path.getValue().str();
    symbolIdentity.push_back(previousPath);
  }
  Operation *module = enclosingNodalModule(getOperation());
  Operation *contract = componentContract(module);
  if (!contract)
    return emitOpError(
        "NODAL-CONNECTION-SET-001: normalized connection set requires a component contract");
  symbolIdentity.push_back(*canonical);
  symbolIdentity.push_back(textAttr(contract, "source_path").str());
  const std::string expectedSymbol = "_connection_" + sanitizeSymbol(symbolIdentity.front()) + "_" +
                                     hex64(stableHash(symbolIdentity));
  auto symbol = getOperation()->getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName());
  if (!symbol || symbol.getValue() != expectedSymbol)
    return emitOpError(
        "NODAL-CONNECTION-SET-001: connection-set symbol does not match stable source identity");
  auto reference = getOperation()->getAttrOfType<BoolAttr>("reference");
  llvm::StringRef scope = textAttr(getOperation(), "reference_scope");
  llvm::StringRef referenceIdentity = textAttr(getOperation(), "reference_identity");
  if (!reference || (reference.getValue() && !oneOf(scope, {"global", "module"})) ||
      (!reference.getValue() && (scope != "none" || !referenceIdentity.empty())))
    return emitOpError("NODAL-CONNECTION-SET-001: invalid reference identity contract");
  if (reference.getValue()) {
    std::string expectedIdentity =
        scope == "global" ? "global::" + *canonical
                          : textAttr(contract, "source_path").str() + "::reference::" + *canonical;
    if (referenceIdentity != expectedIdentity)
      return emitOpError(
          "NODAL-CONNECTION-SET-001: reference identity does not match its scope and discipline");
  }
  if (metadataText(getOperation(), "component_kind") != textAttr(contract, "kind"))
    return emitOpError(
        "NODAL-CONNECTION-SET-001: generated component-kind provenance is inconsistent");
  if (!isCanonicalStringArray(provenance))
    return emitOpError(
        "NODAL-CONNECTION-SET-001: connection-set provenance must contain canonical strings");
  return success();
}

LogicalResult nodal::PotentialEqualityOp::verify() {
  if (!hasCompilerOwnedMetadata(getOperation()))
    return emitOpError("NODAL-CONNECTION-POTENTIAL-001: potential equality is compiler-owned");
  auto reference = getOperation()->getAttrOfType<FlatSymbolRefAttr>("connection_set");
  Operation *connectionSet = nullptr;
  if (failed(verifyConnectionSetReference(getOperation(), reference, connectionSet)))
    return failure();
  if (getOperation()->getOperand(0) == getOperation()->getOperand(1) ||
      !connectionSetContains(connectionSet, getOperation()->getOperand(0)) ||
      !connectionSetContains(connectionSet, getOperation()->getOperand(1)))
    return emitOpError(
        "NODAL-CONNECTION-POTENTIAL-001: equality operands must belong to its connection set");
  auto provenance = getOperation()->getAttrOfType<ArrayAttr>("provenance");
  if (!isCanonicalStringArray(provenance, true))
    return emitOpError(
        "NODAL-CONNECTION-POTENTIAL-001: equality must retain canonical source provenance");
  if (metadataText(getOperation(), "component_kind") !=
      metadataText(connectionSet, "component_kind"))
    return emitOpError(
        "NODAL-CONNECTION-POTENTIAL-001: equality component provenance is inconsistent");
  return success();
}

LogicalResult nodal::ReferencePotentialOp::verify() {
  if (!hasCompilerOwnedMetadata(getOperation()))
    return emitOpError("NODAL-CONNECTION-POTENTIAL-001: reference potential is compiler-owned");
  auto reference = getOperation()->getAttrOfType<FlatSymbolRefAttr>("connection_set");
  Operation *connectionSet = nullptr;
  if (failed(verifyConnectionSetReference(getOperation(), reference, connectionSet)))
    return failure();
  auto setReference = connectionSet->getAttrOfType<BoolAttr>("reference");
  llvm::StringRef setIdentity = textAttr(connectionSet, "reference_identity");
  llvm::StringRef identity = textAttr(getOperation(), "reference_identity");
  if (!connectionSetContains(connectionSet, getOperation()->getOperand(0)) || !setReference ||
      !setReference.getValue() || identity.empty() || identity != setIdentity)
    return emitOpError(
        "NODAL-CONNECTION-POTENTIAL-001: reference potential must bind a member and stable "
        "reference identity");
  auto provenance = getOperation()->getAttrOfType<ArrayAttr>("provenance");
  if (!isCanonicalStringArray(provenance, true))
    return emitOpError(
        "NODAL-CONNECTION-POTENTIAL-001: reference potential must retain canonical provenance");
  if (metadataText(getOperation(), "component_kind") !=
      metadataText(connectionSet, "component_kind"))
    return emitOpError(
        "NODAL-CONNECTION-POTENTIAL-001: reference component provenance is inconsistent");
  return success();
}

LogicalResult nodal::FlowConservationOp::verify() {
  if (!hasCompilerOwnedMetadata(getOperation()))
    return emitOpError("NODAL-CONNECTION-FLOW-001: flow conservation is compiler-owned");
  auto reference = getOperation()->getAttrOfType<FlatSymbolRefAttr>("connection_set");
  Operation *connectionSet = nullptr;
  if (failed(verifyConnectionSetReference(getOperation(), reference, connectionSet)))
    return failure();
  auto signs = getOperation()->getAttrOfType<ArrayAttr>("signs");
  auto provenance = getOperation()->getAttrOfType<ArrayAttr>("provenance");
  if (!signs || signs.size() != getOperation()->getNumOperands() ||
      !isCanonicalStringArray(provenance, true) ||
      (getOperation()->getNumOperands() != 0 &&
       provenance.size() != getOperation()->getNumOperands()))
    return emitOpError(
        "NODAL-CONNECTION-FLOW-001: signs and provenance must cover every flow term");
  for (Attribute sign : signs) {
    auto integer = llvm::dyn_cast<IntegerAttr>(sign);
    if (!integer || (integer.getInt() != -1 && integer.getInt() != 1))
      return emitOpError("NODAL-CONNECTION-FLOW-001: each flow sign must be -1 or +1");
  }
  auto setDiscipline = connectionSet->getAttrOfType<FlatSymbolRefAttr>("discipline");
  if (!setDiscipline)
    return emitOpError("NODAL-CONNECTION-FLOW-001: connection set lacks a discipline");
  Operation *module = enclosingNodalModule(getOperation());
  Operation *contract = componentContract(module);
  if (!contract)
    return emitOpError("NODAL-CONNECTION-FLOW-001: flow equation requires a component contract");
  for (auto [index, term] : llvm::enumerate(getOperation()->getOperands())) {
    const int64_t sign = llvm::cast<IntegerAttr>(signs[index]).getInt();
    auto retainedProvenance = llvm::cast<StringAttr>(provenance[index]);
    llvm::StringRef discipline;
    std::string expectedProvenance;
    if (auto terminal = llvm::dyn_cast<nodal::TerminalType>(term.getType())) {
      discipline = terminal.getDiscipline();
      Operation *definition = term.getDefiningOp();
      auto incidence = nodal::resolveConservativePortIncidence(term, getOperation());
      if (failed(incidence) || !connectionSetContains(connectionSet, term))
        return emitOpError(
            "NODAL-CONNECTION-FLOW-001: terminal flow terms must be boundary members of the "
            "connection set");
      llvm::StringRef orientation = textAttr(incidence->declaration, "flow_orientation");
      const int64_t expectedSign = orientation == "into_component" ? -1 : 1;
      if (!oneOf(orientation, {"into_component", "out_of_component"}) || sign != expectedSign)
        return emitOpError(
            "NODAL-CONNECTION-FLOW-001: terminal flow sign disagrees with its explicit "
            "orientation");
      expectedProvenance =
          "terminal-flow:" + endpointPath(definition, textAttr(contract, "source_path"));
    } else if (auto branch = llvm::dyn_cast<nodal::BranchType>(term.getType())) {
      discipline = branch.getDiscipline();
      Operation *definition = term.getDefiningOp();
      if (!isNamed(definition, "nodal.branch") || definition->getNumOperands() != 2)
        return emitOpError(
            "NODAL-CONNECTION-FLOW-001: branch flow term lacks an oriented branch declaration");
      Value incident = sign > 0 ? definition->getOperand(0) : definition->getOperand(1);
      if (!connectionSetContains(connectionSet, incident))
        return emitOpError(
            "NODAL-CONNECTION-FLOW-001: branch sign does not select an endpoint in the "
            "connection set");
      expectedProvenance = (sign > 0 ? "branch-positive:" : "branch-negative:") +
                           textAttr(definition, "source_path").str();
    } else {
      return emitOpError(
          "NODAL-CONNECTION-FLOW-001: flow terms must be terminal or branch identities");
    }
    if (retainedProvenance.getValue() != expectedProvenance)
      return emitOpError(
          "NODAL-CONNECTION-FLOW-001: flow provenance disagrees with its oriented source term");
    FailureOr<bool> compatible =
        compatibleConservativeDisciplines(getOperation(), discipline, setDiscipline.getValue());
    if (failed(compatible) || !*compatible)
      return emitOpError("NODAL-CONNECTION-FLOW-001: flow term discipline is incompatible with its "
                         "connection set");
  }
  llvm::StringRef ownership = textAttr(getOperation(), "ownership");
  auto complete = getOperation()->getAttrOfType<BoolAttr>("complete");
  auto setComplete = connectionSet->getAttrOfType<BoolAttr>("complete");
  if (!oneOf(ownership, {"local", "extensible"}) || !complete || !setComplete ||
      (complete.getValue() && ownership != "local") ||
      (!complete.getValue() && ownership != "extensible") ||
      ownership != textAttr(connectionSet, "ownership") ||
      complete.getValue() != setComplete.getValue())
    return emitOpError(
        "NODAL-CONNECTION-FLOW-001: completeness must agree with its connection set ownership");
  if (metadataText(getOperation(), "component_kind") !=
      metadataText(connectionSet, "component_kind"))
    return emitOpError("NODAL-CONNECTION-FLOW-001: flow component provenance is inconsistent");
  return success();
}

bool nodal::isPartialPhysicalComponent(Operation *module) {
  Operation *contract = componentContract(module);
  return contract && textAttr(contract, "kind") == "partial";
}

FailureOr<nodal::ConservativeShape> nodal::resolveConservativeShape(Value value, Operation *use) {
  auto reject = [&](llvm::StringRef message) -> FailureOr<ConservativeShape> {
    (void)(use->emitOpError("NODAL-SHAPE-043-006: ") << message);
    return failure();
  };
  if (!value || !use)
    return failure();
  auto shaped = llvm::dyn_cast<ShapedType>(value.getType());
  auto terminal = llvm::dyn_cast<TerminalType>(shaped ? shaped.getElementType() : value.getType());
  auto module = use->getParentOfType<nodal::ModuleOp>();
  if (!terminal || terminal.getDiscipline() != "electrical" || !module)
    return reject("conservative view requires owned Electrical terminal elements");
  auto projection = resolveStaticShapeProjection(value, use);
  if (failed(projection))
    return reject("conservative view requires an acyclic verified owned shape map");
  Operation *root = projection->root.getDefiningOp();
  if (!llvm::isa_and_nonnull<NodeArrayOp, TerminalArrayOp>(root) || root->getParentOp() != module ||
      failed(llvm::isa<NodeArrayOp>(root) ? llvm::cast<NodeArrayOp>(root).verify()
                                          : llvm::cast<TerminalArrayOp>(root).verify()))
    return reject("conservative shape capability requires an actual local conservative array root");
  llvm::StringRef ownerPath = metadataText(module, "semantic_path");
  if (ownerPath.empty())
    ownerPath = textAttr(module, "sym_name");
  const std::string prefix = (ownerPath + ".").str();
  auto ownedPath = [&](llvm::StringRef path) {
    return isCanonicalText(path) && path.starts_with(prefix) && path.size() > prefix.size();
  };
  for (Operation *operation : projection->projections) {
    auto path = metadataText(operation, "semantic_path");
    auto inputPath = metadataText(operation->getOperand(0).getDefiningOp(), "semantic_path");
    bool scalar = llvm::isa<TerminalType>(operation->getResult(0).getType());
    if (!structuralBody(operation->getParentOp()) ||
        failed(verifyGeneratedStructuralOwnership(operation)) ||
        (!textAttr(operation, "owner").empty() &&
         textAttr(operation, "owner") != textAttr(module, "sym_name")) ||
        !ownedPath(path) || !ownedPath(inputPath) || path == inputPath ||
        metadataText(operation, "input_path") != inputPath ||
        (llvm::isa<ShapeViewOp, ShapeSliceOp>(operation) &&
         textAttr(operation, "origin") != inputPath) ||
        metadataText(operation, "storage") != "structural" ||
        (!scalar && metadataText(operation, "access") != "read_only"))
      return reject("conservative projection requires owned immediate-input structural provenance");
    if (!scalar) {
      for (Operation &candidate : *operation->getBlock())
        if (&candidate != operation && metadataText(&candidate, "semantic_path") == path)
          return reject("conservative view source identity must be unique");
    }
  }
  return ConservativeShape{root, std::move(*projection)};
}

FailureOr<nodal::ConservativeShape> nodal::resolveConservativeSelection(Value value,
                                                                        Operation *use) {
  if (!value || !llvm::isa<TerminalType>(value.getType()))
    return failure();
  return resolveConservativeShape(value, use);
}

namespace {
struct ResolvedArrayPort {
  Operation *instance;
  Operation *port;
  Operation *parent;
  Operation *scope;
};

FailureOr<ResolvedArrayPort> resolveArrayPort(Operation *operation) {
  auto reject = [&](llvm::StringRef message) -> FailureOr<ResolvedArrayPort> {
    operation->emitOpError("NODAL-SHAPE-043-008: ") << message;
    return failure();
  };
  if (!llvm::isa_and_nonnull<nodal::InstanceTerminalArrayOp>(operation))
    return failure();
  Operation *parent = enclosingNodalModule(operation);
  Operation *scope = operation->getParentOp();
  if (!parent || !structuralBody(scope) ||
      failed(nodal::verifyGeneratedStructuralOwnership(operation)))
    return reject("child array reference must belong directly to its structural scope");
  auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("instance");
  Operation *instance = nodal::resolveStructuralInstance(operation, reference);
  if (!llvm::isa_and_nonnull<nodal::InstanceOp>(instance))
    return reject("child array reference requires an exact immediate instance");
  Operation *definition = nodal::resolveInstanceModuleDefinition(instance);
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(definition) || definition->getNumRegions() != 1 ||
      definition->getRegion(0).empty())
    return reject("child array reference has an unknown module definition");
  Operation *port = nullptr;
  for (Operation &candidate : definition->getRegion(0).front()) {
    if (textAttr(&candidate, "name") != textAttr(operation, "port"))
      continue;
    if (!llvm::isa<nodal::TerminalArrayOp>(candidate) || port)
      return reject("child array reference requires one declared array boundary port");
    port = &candidate;
  }
  if (!port)
    return reject("child array reference does not name a declared array boundary port");
  return ResolvedArrayPort{instance, port, parent, scope};
}

LogicalResult verifyInstantiatedArrayShape(Operation *diagnostic, const ResolvedArrayPort &resolved,
                                           Type actualType) {
  auto formal = llvm::dyn_cast<nodal::ShapedType>(resolved.port->getResult(0).getType());
  auto actual = llvm::dyn_cast<nodal::ShapedType>(actualType);
  if (!formal || !actual || formal.getElementType() != actual.getElementType() ||
      !llvm::isa<nodal::TerminalType>(actual.getElementType()))
    return diagnostic->emitOpError(
        "NODAL-SHAPE-043-008: child array requires matching conservative element contracts");
  llvm::SmallVector<llvm::StringRef> expectedDimensions, actualDimensions;
  formal.getDimensions().split(expectedDimensions, ',', -1, true);
  actual.getDimensions().split(actualDimensions, ',', -1, true);
  if (expectedDimensions.size() != actualDimensions.size())
    return diagnostic->emitOpError(
        "NODAL-SHAPE-043-008: child array rank must match exactly, not only cardinality");
  if (failed(nodal::inferStaticShapeMaximumCardinality(resolved.parent, actual.getDimensions())))
    return diagnostic->emitOpError("NODAL-SHAPE-043-008: effective child array requires owned "
                                   "positive finite parent dimensions");
  for (auto [formalToken, actualToken] : llvm::zip(expectedDimensions, actualDimensions)) {
    auto expected = nodal::resolveStaticShapeDimension(resolved.port->getParentOp(), formalToken);
    auto actualDimension = nodal::resolveStaticShapeDimension(resolved.parent, actualToken);
    if (failed(expected) || failed(actualDimension) ||
        failed(nodal::verifyInstanceShapeDimension(resolved.instance, *expected, *actualDimension)))
      return diagnostic->emitOpError(
          "NODAL-SHAPE-043-008: child array extent lacks exact finalized substitution proof");
  }
  return success();
}
} // namespace

LogicalResult nodal::verifyConservativeInstanceArray(Operation *operation) {
  auto resolved = resolveArrayPort(operation);
  if (failed(resolved))
    return failure();
  if (failed(llvm::cast<TerminalArrayOp>(resolved->port).verify()))
    return failure();
  // Reusable definitions retain their canonical declaration paths, while each
  // instance can retain a different authored child path. Neither spelling
  // participates in resolution of the actual instance or declared port.
  llvm::StringRef authoredChild = metadataText(resolved->instance, "child_path");
  const std::string expectedSource =
      authoredChild.empty() ? textAttr(resolved->port, "source_path").str()
                            : (authoredChild + "." + textAttr(resolved->port, "name")).str();
  if (!isCanonicalText(textAttr(operation, "name")) ||
      textAttr(operation, "source_path") != expectedSource ||
      (!authoredChild.empty() && (!isCanonicalText(authoredChild) ||
                                  metadataText(operation, "child_path") != authoredChild)) ||
      textAttr(operation, "direction") != textAttr(resolved->port, "direction") ||
      textAttr(operation, "flow_orientation") != "out_of_component" ||
      metadataText(operation, "storage") != "structural" || operation->hasAttr("dimensions"))
    return operation->emitOpError("NODAL-SHAPE-043-008: child array direction, incidence or "
                                  "declaration provenance is invalid");
  if (operation->getNumResults() != 1 ||
      failed(verifyInstantiatedArrayShape(operation, *resolved, operation->getResult(0).getType())))
    return failure();
  for (OpOperand &use : operation->getResult(0).getUses())
    if (!llvm::isa<ConnectArrayOp>(use.getOwner()) || use.getOperandNumber() != 1 ||
        use.getOwner()->getParentOp() != resolved->scope)
      return operation->emitOpError(
          "NODAL-SHAPE-043-008: child array is a whole binding endpoint, not a parent projection");
  return success();
}

FailureOr<nodal::ConservativeArrayBinding>
nodal::resolveConservativeArrayBinding(Operation *operation) {
  if (!llvm::isa_and_nonnull<ConnectArrayOp>(operation) || operation->getNumOperands() != 2)
    return failure();
  auto reject = [&](llvm::StringRef message) -> FailureOr<ConservativeArrayBinding> {
    operation->emitOpError("NODAL-SHAPE-043-008: ") << message;
    return failure();
  };
  Operation *parent = enclosingNodalModule(operation);
  Operation *child = operation->getOperand(1).getDefiningOp();
  auto resolved = resolveArrayPort(child);
  if (!parent || !structuralBody(operation->getParentOp()) || failed(resolved) ||
      resolved->parent != parent || resolved->scope != operation->getParentOp() ||
      !child->isBeforeInBlock(operation) || failed(verifyGeneratedStructuralOwnership(operation)) ||
      failed(verifyConservativeInstanceArray(child)))
    return reject("array binding requires an owned preceding immediate-child port reference");
  auto actual = resolveConservativeShape(operation->getOperand(0), operation);
  if (failed(actual) || !llvm::isa<ShapedType>(operation->getOperand(0).getType()) ||
      failed(
          verifyInstantiatedArrayShape(operation, *resolved, operation->getOperand(0).getType())))
    return reject("array binding requires an exact matching parent-owned conservative shape");
  if (llvm::isa<GenerateOp>(operation->getParentOp()))
    for (Operation *projection : actual->projection.projections)
      if (projection->getParentOp() != parent)
        return reject(
            "generated whole-array binding requires an invariant module-owned actual view");
  llvm::StringRef owner = metadataText(parent, "semantic_path");
  if (owner.empty())
    owner = textAttr(parent, "sym_name");
  auto path = textAttr(operation, "source_path");
  if (!isCanonicalText(path) || !path.starts_with((owner + ".").str()) ||
      !isCanonicalText(textAttr(operation, "connection_id")) ||
      metadataText(operation, "semantic_path") != path)
    return reject("array binding requires owned canonical occurrence provenance");
  return ConservativeArrayBinding{operation, resolved->instance, resolved->port,
                                  operation->getOperand(1), std::move(*actual)};
}

LogicalResult nodal::verifyConservativeArrayBindings(Operation *module) {
  Block *body = structuralBody(module);
  if (!body)
    return success();
  std::map<std::pair<Operation *, Operation *>, unsigned> bindings;
  llvm::StringSet<> occurrences, connectionIds;
  std::map<std::pair<Operation *, Operation *>, Operation *> references;
  for (Operation &operation : *body) {
    if (llvm::isa<InstanceTerminalArrayOp>(operation)) {
      auto resolved = resolveArrayPort(&operation);
      if (failed(resolved) || failed(verifyConservativeInstanceArray(&operation)))
        return failure();
      if (!references.emplace(std::make_pair(resolved->instance, resolved->port), &operation)
               .second)
        return operation.emitOpError("NODAL-VERIFY-HIERARCHY-010: duplicate child array reference");
    }
    if (!llvm::isa<ConnectArrayOp>(operation))
      continue;
    auto relation = resolveConservativeArrayBinding(&operation);
    if (failed(relation))
      return failure();
    if (++bindings[{relation->instance, relation->port}] != 1 ||
        !occurrences.insert(textAttr(&operation, "source_path")).second ||
        !connectionIds.insert(textAttr(&operation, "connection_id")).second)
      return operation.emitOpError(
          "NODAL-VERIFY-HIERARCHY-010: duplicate whole child array binding or occurrence");
  }
  for (Operation &operation : *body) {
    if (!llvm::isa<InstanceOp>(operation))
      continue;
    Operation *definition = resolveInstanceModuleDefinition(&operation);
    if (!llvm::isa_and_nonnull<nodal::ModuleOp>(definition))
      continue; // Existing hierarchy closure owns unknown scalar/module targets.
    if (definition->getNumRegions() != 1 || !llvm::hasSingleElement(definition->getRegion(0)))
      return operation.emitOpError(
          "NODAL-SHAPE-043-008: array boundary inventory requires a valid child module body");
    for (Operation &port : definition->getRegion(0).front())
      if (llvm::isa<TerminalArrayOp>(port) && bindings[{&operation, &port}] != 1)
        return operation.emitOpError(
            "NODAL-VERIFY-HIERARCHY-012: every child array port requires one whole binding");
  }
  return success();
}

FailureOr<nodal::ConservativeStructuralScope>
nodal::resolveConservativeStructuralScope(Operation *scope) {
  Block *body = structuralBody(scope);
  Operation *module = enclosingNodalModule(scope);
  if (!body || !module)
    return failure();
  auto reject = [&](Operation *site, llvm::StringRef message,
                    llvm::StringRef code =
                        "NODAL-ITERATION-043-005") -> FailureOr<ConservativeStructuralScope> {
    site->emitOpError() << code << ": " << message;
    return failure();
  };
  ConservativeStructuralScope result;
  result.module = module;
  result.scope = scope;
  const bool generated = llvm::isa<GenerateOp>(scope);
  llvm::SmallVector<Operation *, 8> connections;
  llvm::SmallVector<Value, 16> endpoints;
  llvm::DenseMap<Value, unsigned> endpointIndex;
  std::map<std::pair<Operation *, Operation *>, Value> childPorts;
  llvm::StringSet<> connectionIds, connectionPaths, instanceNames, nodeNames;
  auto addEndpoint = [&](Value value) {
    auto [entry, inserted] = endpointIndex.try_emplace(value, endpoints.size());
    if (inserted)
      endpoints.push_back(value);
    return entry->second;
  };
  auto resolveChild = [&](Operation *reference) -> FailureOr<std::pair<Operation *, Operation *>> {
    auto invalid = [&](llvm::StringRef code,
                       llvm::StringRef message) -> FailureOr<std::pair<Operation *, Operation *>> {
      reference->emitOpError() << code << ": " << message;
      return failure();
    };
    if (reference->getParentOp() != scope || failed(verifyGeneratedStructuralOwnership(reference)))
      return invalid("NODAL-VERIFY-HIERARCHY-006",
                     "child reference escapes its exact structural scope");
    auto name = reference->getAttrOfType<FlatSymbolRefAttr>("instance");
    Operation *instance = resolveStructuralInstance(reference, name);
    if (!instance)
      return invalid("NODAL-VERIFY-HIERARCHY-006",
                     "child scalar reference requires an exact local instance");
    Operation *definition = resolveInstanceModuleDefinition(instance);
    Block *childBody = moduleBody(definition);
    if (!childBody)
      return invalid("NODAL-VERIFY-HIERARCHY-004",
                     "instance requires a top-level module definition");
    Operation *port = nullptr;
    for (Operation &candidate : *childBody)
      if (llvm::isa<TerminalOp, TerminalArrayOp>(candidate) &&
          textAttr(&candidate, "name") == textAttr(reference, "port")) {
        if (port)
          return invalid("NODAL-VERIFY-HIERARCHY-011", "duplicate boundary terminal name");
        port = &candidate;
      }
    if (!port)
      return invalid("NODAL-VERIFY-HIERARCHY-007",
                     "instance target has no declared boundary terminal");
    if (!llvm::isa<TerminalOp>(port) || reference->getNumResults() != 1 ||
        port->getNumResults() != 1 ||
        reference->getResult(0).getType() != port->getResult(0).getType())
      return invalid("NODAL-VERIFY-HIERARCHY-008",
                     "child terminal has an incompatible conservative type");
    if (textAttr(reference, "direction") != textAttr(port, "direction") ||
        textAttr(reference, "flow_orientation") != "out_of_component")
      return invalid("NODAL-VERIFY-HIERARCHY-009",
                     "child terminal has an incompatible incidence direction");
    return std::make_pair(instance, port);
  };
  for (Operation &operation : *body) {
    if (llvm::isa<GenerateOp>(operation)) {
      result.childScopes.push_back(&operation);
    } else if (llvm::isa<InstanceOp>(operation)) {
      if (failed(verifyGeneratedStructuralOwnership(&operation)))
        return failure();
      if (!resolveInstanceModuleDefinition(&operation))
        return reject(&operation, "instance requires a top-level module definition",
                      "NODAL-VERIFY-HIERARCHY-004");
      if (!instanceNames.insert(textAttr(&operation, "sym_name")).second)
        return reject(&operation, "instance requires a unique exact lexical symbol",
                      "NODAL-VERIFY-HIERARCHY-010");
      result.instances.push_back(&operation);
    } else if (llvm::isa<NodeOp>(operation)) {
      if (!nodeNames.insert(textAttr(&operation, "name")).second)
        return reject(&operation, "generated local node names must be unique in their scope");
      result.nodes.push_back(&operation);
    } else if (llvm::isa<InstanceTerminalOp>(operation)) {
      auto port = resolveChild(&operation);
      if (failed(port))
        return failure();
      if (!childPorts.emplace(*port, operation.getResult(0)).second)
        return reject(&operation, "duplicate child scalar port reference in one structural scope",
                      "NODAL-VERIFY-HIERARCHY-010");
    } else if (llvm::isa<ConnectOp>(operation)) {
      if (failed(llvm::cast<ConnectOp>(operation).verify()) ||
          !connectionIds.insert(textAttr(&operation, "connection_id")).second ||
          !connectionPaths.insert(textAttr(&operation, "source_path")).second)
        return reject(&operation,
                      "connection occurrence must be unique and owned by its exact scope");
      connections.push_back(&operation);
      for (Value value : operation.getOperands()) {
        Operation *endpoint = value.getDefiningOp();
        if (!endpoint || endpoint->getParentOfType<nodal::ModuleOp>() != module ||
            !structurallyPrecedes(endpoint, &operation))
          return reject(&operation, "connection endpoint escapes its lexical occurrence");
        if (llvm::isa<InstanceTerminalOp>(endpoint)) {
          if (failed(resolveChild(endpoint)))
            return failure();
        } else if (isNodeArraySelection(endpoint)) {
          if (failed(resolveConservativeSelection(value, &operation)))
            return failure();
        } else if (llvm::isa<TerminalOp>(endpoint)) {
          if (endpoint->getParentOp() != module)
            return reject(&operation, "boundary terminal must be module-owned");
        } else if (llvm::isa<NodeOp>(endpoint)) {
          if (!structuralBody(endpoint->getParentOp()))
            return reject(&operation, "local node must have a structural declaration owner");
        } else {
          return reject(&operation, "unsupported structural connection endpoint");
        }
        addEndpoint(value);
      }
    } else if (llvm::isa<ConnectArrayOp>(operation)) {
      auto binding = resolveConservativeArrayBinding(&operation);
      if (failed(binding))
        return failure();
      result.arrayBindings.push_back(std::move(*binding));
    }
  }
  const bool topology =
      !result.instances.empty() || !connections.empty() || !result.arrayBindings.empty();
  if (generated && topology && failed(resolveGeneratedDomain(scope)))
    return failure();
  if (failed(verifyConservativeArrayBindings(scope)))
    return failure();
  if (generated) {
    for (Operation *instance : result.instances) {
      Operation *definition = resolveInstanceModuleDefinition(instance);
      Block *childBody = moduleBody(definition);
      if (!childBody)
        return reject(instance, "child definition requires one valid body");
      for (Operation &port : *childBody) {
        if (!llvm::isa<TerminalOp>(port))
          continue;
        auto found = childPorts.find({instance, &port});
        if (found == childPorts.end() || !endpointIndex.contains(found->second))
          return reject(instance, "every generated child scalar port requires an actual connection",
                        "NODAL-VERIFY-HIERARCHY-012");
        unsigned occurrences = 0;
        for (Operation *connection : connections)
          occurrences += llvm::is_contained(connection->getOperands(), found->second);
        if (occurrences != 1)
          return reject(instance, "generated child scalar port has duplicate connection bindings",
                        "NODAL-VERIFY-HIERARCHY-010");
      }
    }
  }
  UnionFind groups(endpoints.size());
  for (Operation *connection : connections)
    for (Value value : connection->getOperands().drop_front())
      groups.unite(endpointIndex.lookup(connection->getOperand(0)), endpointIndex.lookup(value));
  std::map<unsigned, unsigned> groupIndices;
  for (unsigned index = 0; index < endpoints.size(); ++index) {
    unsigned root = groups.find(index);
    auto [entry, inserted] = groupIndices.emplace(root, result.scalarGroups.size());
    if (inserted)
      result.scalarGroups.emplace_back();
    result.scalarGroups[entry->second].members.push_back(endpoints[index]);
  }
  for (Operation *connection : connections) {
    unsigned root = groups.find(endpointIndex.lookup(connection->getOperand(0)));
    result.scalarGroups[groupIndices.at(root)].connections.push_back(connection);
  }
  return result;
}

FailureOr<nodal::ConservativePortIncidence>
nodal::resolveConservativePortIncidence(Value value, Operation *use) {
  if (!value || !use || !llvm::isa<TerminalType>(value.getType()))
    return failure();
  Operation *definition = value.getDefiningOp();
  auto module = use->getParentOfType<nodal::ModuleOp>();
  if (llvm::isa_and_nonnull<TerminalOp>(definition) && definition->getParentOp() == module)
    return ConservativePortIncidence{definition, value, std::nullopt};
  if (!isNodeArraySelection(definition))
    return failure();
  auto selection = resolveConservativeSelection(value, use);
  if (failed(selection) || !llvm::isa<TerminalArrayOp>(selection->declaration))
    return failure();
  return ConservativePortIncidence{selection->declaration, value, std::move(selection->projection)};
}

bool nodal::isNodeArraySelection(Operation *operation) {
  return llvm::isa_and_nonnull<nodal::ShapeIndexOp>(operation) && operation->getNumOperands() > 1 &&
         operation->getNumResults() == 1 &&
         llvm::isa<nodal::TerminalType>(operation->getResult(0).getType());
}

LogicalResult nodal::verifyNodeArraySelections(Operation *module) {
  std::vector<ArraySelectionInfo> selections;
  return collectArraySelections(module, selections);
}

LogicalResult nodal::normalizeNodeArraySelections(mlir::ModuleOp module) {
  // Validate all definitions before changing any aliases. The enclosing gate
  // and connectivity transactions retain their existing clone/commit boundary.
  std::vector<std::vector<ArraySelectionInfo>> definitions;
  LogicalResult valid = success();
  module.walk([&](Operation *definition) {
    if (!llvm::isa<nodal::ModuleOp, nodal::GenerateOp>(definition))
      return;
    definitions.emplace_back();
    if (failed(collectArraySelections(definition, definitions.back())))
      valid = failure();
  });
  if (failed(valid))
    return failure();
  for (auto &selections : definitions) {
    std::vector<ArraySelectionInfo *> representatives;
    for (ArraySelectionInfo &selection : selections) {
      ArraySelectionInfo *representative = nullptr;
      for (ArraySelectionInfo *candidate : representatives)
        if (candidate->array == selection.array &&
            candidate->canonicalKey == selection.canonicalKey) {
          representative = candidate;
          break;
        }
      if (!representative) {
        representatives.push_back(&selection);
        continue;
      }
      representative->uses.insert(representative->uses.end(), selection.uses.begin(),
                                  selection.uses.end());
      llvm::sort(representative->uses);
      representative->uses.erase(
          std::unique(representative->uses.begin(), representative->uses.end()),
          representative->uses.end());
      Builder builder(module.getContext());
      NamedAttrList metadata(representative->operation->getAttrOfType<DictionaryAttr>("metadata"));
      metadata.set("source_uses", stringArray(builder, representative->uses));
      representative->operation->setAttr("metadata", metadata.getDictionary(module.getContext()));
      llvm::SmallPtrSet<Operation *, 4> orphanedIndexLiterals;
      for (Value index : selection.operation->getOperands().drop_front())
        if (index.getType().isIndex() && llvm::isa<nodal::ConstantOp>(index.getDefiningOp()))
          orphanedIndexLiterals.insert(index.getDefiningOp());
      selection.operation->getResult(0).replaceAllUsesWith(representative->operation->getResult(0));
      selection.operation->erase();
      // Only legacy pure index literals validated above can become orphaned
      // here. This is not general dead-code cleanup or another storage model.
      for (Operation *literal : orphanedIndexLiterals)
        if (literal->getResult(0).use_empty())
          literal->erase();
    }
  }
  return success();
}

LogicalResult nodal::materializeConservativeConnectivity(mlir::ModuleOp module) {
  OwningOpRef<mlir::ModuleOp> working(llvm::cast<mlir::ModuleOp>(module->clone()));
  if (failed(materializeInPlace(*working)))
    return failure();
  commitModule(module, *working);
  return success();
}
