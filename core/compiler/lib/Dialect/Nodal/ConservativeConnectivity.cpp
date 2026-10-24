#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"

#include "mlir/IR/Builders.h"
#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/OwningOpRef.h"
#include "mlir/IR/SymbolTable.h"
#include "mlir/IR/Verifier.h"
#include "nodal/Dialect/Nodal/NatureDiscipline.h"
#include "nodal/Dialect/Nodal/DigitalInterfaceModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
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

bool hasArrayRelations(Operation *scope) {
  Operation *module = enclosingNodalModule(scope);
  bool found = false;
  if (module)
    module->walk([&](Operation *operation) {
      if (enclosingNodalModule(operation) == module && nodal::isConservativeRelation(operation))
        found = true;
    });
  return found;
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
  if (llvm::isa<nodal::MemberAccessOp>(operation)) {
    auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("instance");
    Operation *endpoint = reference
        ? SymbolTable::lookupSymbolIn(operation->getParentOp(), reference) : nullptr;
    if (endpoint)
      return (metadataText(endpoint, "semantic_path") + "." + textAttr(operation, "path")).str();
  }
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
  unsigned identityWork = 16384;
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
        // A generated compound index belongs to its exact lexical body. Its
        // independently proved root must not be rediscovered by a Module-wide
        // spelling search or conflated with an identically named sibling root.
        Block *owner = definition ? definition->getBlock() : nullptr;
        const bool lexical = definition && definition->getParentOp() == scope &&
                             llvm::isa<nodal::GenerateOp>(scope);
        if (!lexical && owner != moduleBody(module))
          owner = nullptr;
        unsigned matches = 0;
        if (owner)
          for (Operation &candidate : *owner) {
            if (!identityWork)
              return operation.emitOpError(
                  "NODAL-SHAPE-043-006: compound index identity resource limit exceeded");
            --identityWork;
            if (metadataText(&candidate, "semantic_path") == path)
              ++matches;
          }
        if (!llvm::isa_and_nonnull<nodal::ConstExprOp>(definition) ||
            proof->identity.root != definition || !ownedPath(path) || matches != 1)
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
      if (!disjoint && nodal::compareConservativeEndpoints(previous.operation->getResult(0),
                                                           operation.getResult(0), &operation) !=
                           nodal::ConservativeAlias::Equal)
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

  llvm::SmallVector<nodal::DigitalInterfaceEndpoint> interfaces;
  llvm::SmallVector<nodal::DigitalInterfaceAccess> accesses;
  llvm::DenseMap<Value, const nodal::DigitalInterfaceAccess *> interfaceValues;
  llvm::DenseMap<Value, bool> interfaceUsed;
  if (llvm::any_of(*body, [](Operation &operation) { return llvm::isa<nodal::MemberAccessOp>(operation); })) {
    if (failed(nodal::collectDigitalInterfaceEndpoints(module, interfaces, &accesses)))
      return failure();
    for (const auto &access : accesses)
      if (access.leaf.protocol.getValue() == "conservative") {
        interfaceValues.try_emplace(access.value, &access);
        interfaceUsed[access.canonicalValue] |= hasSourceUse(access.value);
      }
  }
  std::vector<EndpointInfo> endpoints;
  llvm::DenseMap<Value, unsigned> endpointIndex;
  llvm::StringSet<> endpointNames;
  llvm::StringSet<> sourcePaths;
  for (Operation &operation : *body) {
    if (!isNamed(&operation, "nodal.terminal") && !isNamed(&operation, "nodal.node") &&
        !isNamed(&operation, "nodal.instance_terminal") && !nodal::isNodeArraySelection(&operation) &&
        !(operation.getNumResults() == 1 && interfaceValues.count(operation.getResult(0))))
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

    const auto *interface = interfaceValues.lookup(operation.getResult(0));
    if (interface && interface->canonicalValue != operation.getResult(0)) {
      auto known = endpointIndex.find(interface->canonicalValue);
      if (known == endpointIndex.end())
        return operation.emitOpError("NODAL-CONNECTION-001: Interface canonical access must precede its aliases");
      endpointIndex[operation.getResult(0)] = known->second;
      continue;
    }
    std::string interfacePath = interface ? endpointPath(&operation, modulePath) : std::string();
    const llvm::StringRef endpointName = interface ? llvm::StringRef(interfacePath)
        : nodal::isNodeArraySelection(&operation) ? metadataText(&operation, "semantic_path")
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
      if (llvm::isa<nodal::TerminalArrayOp, nodal::InstanceTerminalArrayOp>(selected->declaration))
        incidence = selected->declaration;
    }
    const bool terminal = interface ||
        llvm::isa<nodal::TerminalArrayOp, nodal::InstanceTerminalArrayOp>(incidence) ||
        isNamed(&operation, "nodal.terminal") || isNamed(&operation, "nodal.instance_terminal");
    if (terminal) {
      llvm::StringRef direction = interface ? "inout" : textAttr(incidence, "direction");
      if (!oneOf(direction, {"input", "output", "inout"}))
        return operation.emitOpError(
            "NODAL-TERMINAL-DIRECTION-001: conservative terminal direction must be input, "
            "output, or inout");
      llvm::StringRef orientation = interface
          ? (interface->identity.instance ? "out_of_component" : "into_component")
          : textAttr(incidence, "flow_orientation");
      if (!oneOf(orientation, {"into_component", "out_of_component"}))
        return operation.emitOpError(
            "NODAL-TERMINAL-ORIENTATION-001: flow orientation must be into_component or "
            "out_of_component");
    }

    std::string path = endpointPath(&operation, modulePath);
    if (!isCanonicalText(path) || !sourcePaths.insert(path).second)
      return operation.emitOpError(
          "NODAL-CONNECTIVITY-PROVENANCE-001: endpoint source paths must be canonical and unique");
    bool used = hasSourceUse(operation.getResult(0));
    if (interface)
      used |= interfaceUsed.lookup(interface->canonicalValue);
    if (!partial && !used)
      return operation.emitOpError(
          "NODAL-CONNECTION-001: concrete component contains an unowned floating endpoint");

    const unsigned index = static_cast<unsigned>(endpoints.size());
    endpointIndex[operation.getResult(0)] = index;
    endpoints.push_back(EndpointInfo{operation.getResult(0), &operation, std::move(path),
                                     std::move(*discipline), terminal,
                                     interface ? (interface->identity.instance ? "out_of_component" : "into_component")
                                               : textAttr(incidence, "flow_orientation").str()});
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

  // Resolve only authored scalar occurrences through the compact array maps.
  // No per-lane endpoints are introduced, and unknown physical identity is not
  // treated as a new connection set.
  if (hasArrayRelations(module)) {
    for (unsigned i = 0; i < endpoints.size(); ++i)
      for (unsigned j = 0; j < i; ++j) {
        if (!nodal::isNodeArraySelection(endpoints[i].operation) &&
            !nodal::isNodeArraySelection(endpoints[j].operation))
          continue;
        auto relation = nodal::compareConservativeEndpoints(endpoints[j].value, endpoints[i].value,
                                                            &body->back());
        if (relation == nodal::ConservativeAlias::Equal)
          sets.unite(i, j);
        else if (relation != nodal::ConservativeAlias::Distinct)
          return endpoints[i].operation->emitOpError("NODAL-SHAPE-043-009: ")
                 << (relation == nodal::ConservativeAlias::ResourceLimit
                         ? "scalar endpoint relation proof resource limit exceeded"
                         : "scalar endpoint relation has unproved physical alias identity");
      }
    for (Operation &operation : *body)
      if (llvm::isa<nodal::ConnectArrayOp>(operation))
        sourceRelations.push_back(&operation);
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
  if (failed(verifyTerminalOperands(getOperation(), 2, "NODAL-CONNECTION-DISCIPLINE-001")))
    return failure();
  return !isConservativeRelation(getOperation()) ||
                 succeeded(resolveConservativeRelation(getOperation()))
             ? success()
             : failure();
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
      Operation *incidenceDeclaration = succeeded(incidence) ? incidence->declaration : nullptr;
      if (!incidenceDeclaration) {
        auto endpoint = nodal::resolveConservativeEndpoint(term, getOperation());
        if (succeeded(endpoint) && endpoint->port)
          incidenceDeclaration = endpoint->shape.declaration;
      }
      if (!incidenceDeclaration || !connectionSetContains(connectionSet, term))
        return emitOpError(
            "NODAL-CONNECTION-FLOW-001: terminal flow terms must be boundary members of the "
            "connection set");
      llvm::StringRef orientation = textAttr(incidenceDeclaration, "flow_orientation");
      if (llvm::isa<nodal::MemberAccessOp>(definition)) {
        auto endpoint = nodal::resolveConservativeEndpoint(term, getOperation());
        if (failed(endpoint) || !llvm::isa<nodal::MemberAccessOp>(endpoint->shape.declaration))
          return emitOpError("NODAL-CONNECTION-FLOW-001: Interface incidence lost its exact origin");
        orientation = endpoint->instance ? "out_of_component" : "into_component";
      }
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
  const bool child = llvm::isa_and_nonnull<InstanceTerminalArrayOp>(root);
  if (child) {
    Operation *scope = use;
    while (scope && !structuralBody(scope))
      scope = scope->getParentOp();
    if (root->getParentOp() != scope || failed(verifyConservativeInstanceArray(root)))
      return reject("child conservative shape escapes its exact instance scope");
  } else if (!llvm::isa_and_nonnull<NodeArrayOp, TerminalArrayOp>(root) ||
             !structuralBody(root->getParentOp()) ||
             (llvm::isa<TerminalArrayOp>(root) && root->getParentOp() != module) ||
             failed(llvm::isa<NodeArrayOp>(root) ? llvm::cast<NodeArrayOp>(root).verify()
                                                 : llvm::cast<TerminalArrayOp>(root).verify()))
    return reject("conservative shape capability requires an actual conservative array root");
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
  llvm::StringRef parentPath = metadataText(resolved->parent, "semantic_path");
  if (parentPath.empty())
    parentPath = textAttr(resolved->parent, "sym_name");
  llvm::StringRef path = metadataText(operation, "semantic_path");
  if (!isCanonicalText(path) || !path.starts_with((parentPath + ".").str()))
    return operation->emitOpError(
        "NODAL-SHAPE-043-008: child root requires owned occurrence provenance");
  for (Operation &candidate : *structuralBody(resolved->scope))
    if (&candidate != operation && candidate.getNumResults() == 1 &&
        metadataText(&candidate, "semantic_path") == path &&
        (llvm::isa<ShapedType>(candidate.getResult(0).getType()) ||
         llvm::isa<TerminalType>(candidate.getResult(0).getType())))
      return operation->emitOpError(
          "NODAL-SHAPE-043-008: child root occurrence identity aliases another endpoint");
  if (operation->getNumResults() != 1 ||
      failed(verifyInstantiatedArrayShape(operation, *resolved, operation->getResult(0).getType())))
    return failure();
  for (OpOperand &use : operation->getResult(0).getUses())
    if ((!llvm::isa<ConnectArrayOp>(use.getOwner()) &&
         !(llvm::isa<ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(use.getOwner()) &&
           use.getOperandNumber() == 0)) ||
        use.getOwner()->getParentOp() != resolved->scope)
      return operation->emitOpError("NODAL-SHAPE-043-008: child array use requires a relation or "
                                    "owned projection in its exact scope");
  return success();
}

FailureOr<nodal::ConservativeGeneratedSelection>
nodal::resolveConservativeGeneratedSelection(Operation *operation) {
  // Low-level structural fact query: never enter the Interface model or
  // conservative connectivity, since declaration inventory also consumes it.
  if (!llvm::isa_and_nonnull<InterfaceBoundaryOp>(operation) ||
      operation->getNumOperands() != 2 ||
      !llvm::isa<ModuleOp>(operation->getParentOp()))
    return failure();
  auto key = operation->getAttrOfType<StringAttr>("member");
  auto proof = key ? inspectGeneratedSelection(operation->getOperand(0),
                    operation->getOperand(1), key.getValue(), operation)
                   : FailureOr<GeneratedSelectionProof>(failure());
  auto target = succeeded(proof) ? inspectGeneratedExportSymbol(proof->exportOperation)
                                : FailureOr<Operation *>(failure());
  if (failed(proof) || failed(target) || proof->selections.size() != 1 ||
      proof->family != (*target)->getParentOp() ||
      proof->family->getParentOp() != operation->getParentOp() ||
      proof->family->getNumOperands() != 3)
    return failure();
  Operation *declaration = *target;
  const bool child = textAttr(proof->exportOperation, "kind") == "instance";
  if (child) {
    auto instance = llvm::dyn_cast<InstanceOp>(*target);
    auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("instance");
    if (!instance || !reference || reference.getValue() != textAttr(instance, "sym_name"))
      return failure();
    auto bindings = instance->getAttrOfType<DictionaryAttr>("parameter_bindings");
    Operation *module = resolveInstanceModuleDefinition(instance);
    if (!bindings || !module || module->hasAttr("external_name"))
      return failure();
    Block *body = moduleBody(module);
    if (!body)
      return failure();
    // Restrict selection admission, not binding semantics: ParameterModel and
    // the instance verifier still independently validate names, types and constraints.
    for (Operation &parameter : *body) {
      if (!llvm::isa<ParameterOp>(parameter))
        continue;
      auto type = parameter.getAttrOfType<TypeAttr>("type");
      auto value = parameter.getAttrOfType<FloatAttr>("default_value");
      if (!type || !type.getValue().isF64() || !value || !value.getValue().isFinite() ||
          parameter.hasAttr("unit") || getParameterKind(&parameter) != "real" ||
          getParameterClassification(&parameter) != "ordinary")
        return failure();
    }
    for (Operation &attachment : *body) {
      if (!llvm::isa<ParameterValueOp>(attachment))
        continue;
      if (attachment.getNumOperands() != 1)
        return failure();
      Operation *literal = attachment.getOperand(0).getDefiningOp();
      auto value = literal ? literal->getAttrOfType<FloatAttr>("value") : FloatAttr();
      if (!llvm::isa_and_nonnull<ConstLiteralOp>(literal) || literal->hasAttr("unit") || !value ||
          !value.getType().isF64() || !value.getValue().isFinite())
        return failure();
    }
    SymbolTable childSymbols(module);
    for (NamedAttribute binding : bindings) {
      Operation *parameter = childSymbols.lookup(binding.getName().getValue());
      auto value = llvm::dyn_cast<FloatAttr>(binding.getValue());
      if (!llvm::isa_and_nonnull<ParameterOp>(parameter) || !value || !value.getType().isF64() ||
          !value.getValue().isFinite())
        return failure();
    }
    // This low-level admission query independently checks direct parent Real
    // references. Evaluation and constraint semantics remain in ParameterModel.
    auto uniqueParameter = [](Block *body, llvm::StringRef name) -> Operation * {
      if (!body || name.empty())
        return nullptr;
      Operation *result = nullptr;
      for (Operation &candidate : *body) {
        if (textAttr(&candidate, "sym_name") != name)
          continue;
        if (result || !llvm::isa<ParameterOp>(candidate))
          return nullptr;
        result = &candidate;
      }
      return result;
    };
    auto ordinaryReal = [](Operation *parameter, Operation *owner) {
      auto type = parameter ? parameter->getAttrOfType<TypeAttr>("type") : TypeAttr();
      return parameter && parameter->getParentOp() == owner && type && type.getValue().isF64() &&
             !parameter->hasAttr("unit") && !parameter->hasAttr("generated_owner") &&
             !parameter->hasAttr("generated_induction") && getParameterKind(parameter) == "real" &&
             getParameterClassification(parameter) == "ordinary";
    };
    Operation *parent = operation->getParentOp();
    Block *parentBody = structuralBody(parent);
    Block *familyBody = structuralBody(proof->family);
    if (!parentBody || !familyBody)
      return failure();
    llvm::StringSet<> symbolicFormals;
    for (Operation &candidate : *familyBody) {
      if (!llvm::isa<ParameterOverrideOp>(candidate) ||
          candidate.getAttrOfType<FlatSymbolRefAttr>("instance") != reference)
        continue;
      if (candidate.getNumOperands() != 1 || candidate.getNumResults() != 0 ||
          resolveStructuralInstance(&candidate, reference) != instance ||
          failed(verifyGeneratedStructuralOwnership(&candidate)) ||
          failed(verifyGeneratedStructuralOwnership(instance)))
        return failure();
      auto formalReference = candidate.getAttrOfType<FlatSymbolRefAttr>("parameter");
      Operation *formal =
          formalReference ? uniqueParameter(body, formalReference.getValue()) : nullptr;
      Operation *actual = candidate.getOperand(0).getDefiningOp();
      if (!formalReference || !ordinaryReal(formal, module) ||
          textAttr(formal, "variability") != "symbolic" ||
          !symbolicFormals.insert(formalReference.getValue()).second ||
          bindings.get(formalReference.getValue()) ||
          !llvm::isa_and_nonnull<ConstParameterRefOp>(actual) || actual->getParentOp() != parent ||
          actual->getNumOperands() != 0 || actual->getNumResults() != 1 ||
          !candidate.getOperand(0).getType().isF64() || actual->hasAttr("unit") ||
          actual->hasAttr("generated_owner") || actual->hasAttr("generated_induction") ||
          !structurallyPrecedes(actual, &candidate))
        return failure();
      auto sourceReference = actual->getAttrOfType<FlatSymbolRefAttr>("parameter");
      Operation *source =
          sourceReference ? uniqueParameter(parentBody, sourceReference.getValue()) : nullptr;
      if (!ordinaryReal(source, parent))
        return failure();
    }
    declaration = childSymbols.lookup(textAttr(operation, "endpoint"));
    if (!llvm::isa_and_nonnull<InterfaceInstanceOp>(declaration) ||
        declaration->getParentOp() != module)
      return failure();
    // Original exact one-step family only. No parameterized domain is silently
    // specialized and no nested/guarded prefix is truncated by this profile.
    for (Value bound : proof->family->getOperands())
      if (!bound.getDefiningOp<ConstLiteralOp>()) return failure();
  } else if (operation->hasAttr("instance") ||
             textAttr(proof->exportOperation, "kind") != "interface" ||
             !llvm::isa<InterfaceInstanceOp>(*target) ||
             textAttr(operation, "endpoint") != textAttr(*target, "sym_name")) {
    return failure();
  }
  auto literal = [](Value value) -> std::optional<int64_t> {
    auto op = value.getDefiningOp<ConstLiteralOp>();
    auto attr = op ? op->getAttrOfType<IntegerAttr>("value") : IntegerAttr();
    if (!attr || !value.getType().isInteger(64) || !attr.getValue().isSignedIntN(64))
      return std::nullopt;
    return attr.getValue().getSExtValue();
  };
  unsigned remaining = 16384;
  auto domain = resolveGeneratedDomain(proof->family, remaining);
  InstantiatedIntegerExpression normalized;
  if (failed(domain) || !domain->induction ||
      resolveGeneratedInductionOrdinal(proof->family, domain->induction, *target,
                                       normalized, remaining) != ShapeRegionProofStatus::Proved)
    return failure();
  auto ordinal = literal(operation->getOperand(1));
  if (!ordinal || *ordinal < 0 || !domain->maximumTripCount ||
      domain->maximumTripCount > 2147483647 ||
      uint64_t(*ordinal) >= domain->maximumTripCount)
    return failure();
  return ConservativeGeneratedSelection{declaration, proof->family,
                                         operation->getOperand(1), domain->maximumTripCount};
}

FailureOr<nodal::ConservativeEndpoint> nodal::resolveConservativeEndpoint(Value value,
                                                                          Operation *use) {
  if (!value || !use)
    return failure();
  Operation *scope = use;
  while (scope && !structuralBody(scope))
    scope = scope->getParentOp();
  Operation *module = enclosingNodalModule(use);
  Operation *definition = value.getDefiningOp();
  if (!scope || !module || !definition || !structurallyPrecedes(definition, use))
    return failure();
  ConservativeEndpoint endpoint;
  endpoint.value = value;
  endpoint.useScope = scope;
  if (llvm::isa<ShapedType>(value.getType()) || isNodeArraySelection(definition)) {
    auto shape = resolveConservativeShape(value, use);
    if (failed(shape))
      return failure();
    endpoint.shape = std::move(*shape);
    endpoint.declarationScope = endpoint.shape.declaration->getParentOp();
    if (llvm::isa<InstanceTerminalArrayOp>(endpoint.shape.declaration)) {
      auto child = resolveArrayPort(endpoint.shape.declaration);
      if (failed(child) || child->scope != scope)
        return failure();
      endpoint.instance = child->instance;
      endpoint.port = child->port;
    }
    return endpoint;
  }
  if (llvm::isa<MemberAccessOp>(definition)) {
    auto access = resolveDigitalInterfaceAccess(value);
    if (failed(access) || !llvm::isa<TerminalType>(value.getType()) ||
        access->leaf.protocol.getValue() != "conservative" || definition->getParentOp() != scope ||
        (!llvm::isa<ModuleOp, GenerateOp>(scope)) ||
        (access->identity.selection && failed(resolveConservativeGeneratedSelection(access->projection))) ||
        access->projection->getParentOp() != scope)
      return failure();
    // Access rights are per selected view, never inherited from the first alias.
    if (llvm::isa<ConnectOp, AliasOp, ConnectArrayOp>(use) &&
        access->leaf.access.getValue() != "connect") {
      use->emitOpError("NODAL-INTERFACE-CONSERVATIVE-001: connection requires the actual connect role");
      return failure();
    }
    auto projection = resolveStaticShapeProjection(access->canonicalValue, use);
    if (failed(projection))
      return failure();
    endpoint.shape = ConservativeShape{access->canonicalValue.getDefiningOp(),
                                       std::move(*projection)};
    endpoint.declarationScope = access->identity.selection
                                    ? access->identity.endpoint->getParentOp() : scope;
    endpoint.instance = access->identity.instance;
    // A body-local Interface is a local node, not an exported component
    // terminal. Only an actual child boundary or module boundary has incidence.
    endpoint.port =
        endpoint.instance || llvm::isa<ModuleOp>(endpoint.declarationScope)
            ? access->identity.endpoint : nullptr;
    return endpoint;
  }
  if (!llvm::isa<TerminalType>(value.getType()) ||
      !llvm::isa<NodeOp, TerminalOp, InstanceTerminalOp>(definition) ||
      definition->getParentOfType<nodal::ModuleOp>() != module)
    return failure();
  auto projection = resolveStaticShapeProjection(value, use);
  if (failed(projection))
    return failure();
  endpoint.shape = ConservativeShape{definition, std::move(*projection)};
  endpoint.declarationScope = definition->getParentOp();
  if (llvm::isa<InstanceTerminalOp>(definition)) {
    if (definition->getParentOp() != scope ||
        failed(verifyGeneratedStructuralOwnership(definition)))
      return failure();
    endpoint.instance = resolveStructuralInstance(
        definition, definition->getAttrOfType<FlatSymbolRefAttr>("instance"));
    Operation *target = resolveInstanceModuleDefinition(endpoint.instance);
    Block *body = moduleBody(target);
    if (!body)
      return failure();
    for (Operation &candidate : *body)
      if (llvm::isa<TerminalOp>(candidate) &&
          textAttr(&candidate, "name") == textAttr(definition, "port")) {
        if (endpoint.port)
          return failure();
        endpoint.port = &candidate;
      }
    if (!endpoint.port || endpoint.port->getResult(0).getType() != value.getType() ||
        textAttr(endpoint.port, "direction") != textAttr(definition, "direction") ||
        textAttr(definition, "flow_orientation") != "out_of_component")
      return failure();
  } else if (llvm::isa<TerminalOp>(definition) && definition->getParentOp() != module) {
    return failure();
  }
  return endpoint;
}

bool nodal::isConservativeRelation(Operation *operation) {
  return llvm::isa_and_nonnull<ConnectArrayOp>(operation) ||
         (llvm::isa_and_nonnull<ConnectOp>(operation) &&
          llvm::any_of(operation->getOperands(),
                       [](Value value) { return isNodeArraySelection(value.getDefiningOp()); }));
}

FailureOr<nodal::ConservativeRelation> nodal::resolveConservativeRelation(Operation *operation) {
  if (!isConservativeRelation(operation))
    return failure();
  auto reject = [&](llvm::StringRef message) -> FailureOr<ConservativeRelation> {
    operation->emitOpError("NODAL-SHAPE-043-008: ") << message;
    return failure();
  };
  if (operation->getNumOperands() != 2 || !structuralBody(operation->getParentOp()) ||
      failed(verifyGeneratedStructuralOwnership(operation)))
    return reject("endpoint relation requires two actual operands in one structural scope");
  auto left = resolveConservativeEndpoint(operation->getOperand(0), operation);
  auto right = resolveConservativeEndpoint(operation->getOperand(1), operation);
  if (failed(left) || failed(right))
    return reject("endpoint relation requires actual visible conservative roots and maps");
  Type lhs = operation->getOperand(0).getType(), rhs = operation->getOperand(1).getType();
  auto leftShape = llvm::dyn_cast<ShapedType>(lhs);
  auto rightShape = llvm::dyn_cast<ShapedType>(rhs);
  if (static_cast<bool>(leftShape) != static_cast<bool>(rightShape) ||
      (llvm::isa<ConnectArrayOp>(operation) && !leftShape))
    return reject("endpoint relation rank must match without scalar broadcast");
  Type leftElement = leftShape ? leftShape.getElementType() : lhs;
  Type rightElement = rightShape ? rightShape.getElementType() : rhs;
  if (leftElement != rightElement || !llvm::isa<TerminalType>(leftElement) ||
      llvm::cast<TerminalType>(leftElement).getDiscipline() != "electrical")
    return reject("endpoint relation requires matching Electrical element contracts");
  Operation *module = enclosingNodalModule(operation);
  if (leftShape) {
    llvm::SmallVector<llvm::StringRef> leftDimensions, rightDimensions;
    leftShape.getDimensions().split(leftDimensions, ',', -1, true);
    rightShape.getDimensions().split(rightDimensions, ',', -1, true);
    if (leftDimensions.size() != rightDimensions.size())
      return reject("endpoint relation rank must match exactly");
    auto expression = [&](llvm::StringRef token) -> FailureOr<InstantiatedIntegerExpression> {
      auto dimension = resolveStaticShapeDimension(module, token.trim());
      if (failed(dimension))
        return failure();
      if (dimension->parameter)
        return resolveOwnedIntegerParameter(module, dimension->parameter);
      if (dimension->expression)
        return resolveOwnedIntegerExpression(module, dimension->expression);
      return makeLiteralIntegerExpression(dimension->literal);
    };
    for (auto [a, b] : llvm::zip(leftDimensions, rightDimensions)) {
      auto x = expression(a), y = expression(b);
      if (failed(x) || failed(y) || !equivalentInstantiatedIntegerExpressions(*x, *y))
        return reject("endpoint extents lack exact finalized corresponding shape proof");
    }
  }
  llvm::StringRef owner = metadataText(module, "semantic_path");
  if (owner.empty())
    owner = textAttr(module, "sym_name");
  auto path = textAttr(operation, "source_path");
  if (!isCanonicalText(path) || !path.starts_with((owner + ".").str()) ||
      !isCanonicalText(textAttr(operation, "connection_id")) ||
      (llvm::isa<ConnectArrayOp>(operation) && metadataText(operation, "semantic_path") != path))
    return reject("endpoint relation requires owned canonical occurrence provenance");
  return ConservativeRelation{operation, std::move(*left), std::move(*right)};
}

namespace {
using BoundaryPortKey = std::pair<Operation *, StringAttr>;
struct BoundaryAliasSummary {
  llvm::DenseMap<BoundaryPortKey, unsigned> groups;
  llvm::DenseSet<BoundaryPortKey> touched;
};

// This existing query-local over-approximation only prevents false Distinct.
// Interface keys retain the actual endpoint and ordered member path; parent
// incidences are grouped by their actual instance, never by child definition.
LogicalResult
collectChildBoundaryUncertainty(Operation *module,
                                llvm::SmallVectorImpl<std::pair<Value, Value>> &uncertain,
                                unsigned &remaining) {
  using namespace nodal;
  auto charge = [&]() { if (!remaining) return false; --remaining; return true; };
  auto key = [&](const nodal::ConservativeEndpoint &endpoint)
      -> std::optional<BoundaryPortKey> {
    if (!charge() || !endpoint.port)
      return std::nullopt;
    if (!llvm::isa<MemberAccessOp>(endpoint.value.getDefiningOp()))
      return BoundaryPortKey{endpoint.port, StringAttr()};
    auto member = inspectDigitalInterfaceMember(endpoint.value.getDefiningOp());
    if (failed(member) || member->instance != endpoint.instance ||
        member->endpoint != endpoint.port || !member->path)
      return std::nullopt;
    return BoundaryPortKey{member->endpoint, member->path};
  };
  llvm::DenseMap<Operation *, BoundaryAliasSummary> summaries;
  llvm::DenseMap<Operation *, unsigned> visiting;
  llvm::SmallVector<std::pair<Operation *, bool>, 8> pending{{module, false}};
  auto active = [&](Operation *operation, Operation *definition) -> FailureOr<bool> {
    for (Operation *scope = operation->getParentOp(); scope && scope != definition;
         scope = scope->getParentOp()) {
      if (!charge()) return failure();
      if (llvm::isa<nodal::GenerateOp>(scope)) {
        auto domain = nodal::resolveGeneratedDomain(scope, remaining);
        if (failed(domain)) return failure();
        if (!domain->maximumTripCount) return false;
      }
    }
    return true;
  };
  auto references = [&](Operation *definition,
      llvm::DenseMap<Operation *, llvm::SmallVector<nodal::ConservativeEndpoint, 2>> &refs) {
    LogicalResult status = success();
    definition->walk([&](Operation *operation) {
      if (failed(status)) return;
      if (!charge()) { status = failure(); return; }
      if (enclosingNodalModule(operation) != definition ||
          !llvm::isa<InstanceTerminalOp, InstanceTerminalArrayOp, MemberAccessOp>(operation))
        return;
      if (llvm::isa<MemberAccessOp>(operation)) {
        auto member = inspectDigitalInterfaceMember(operation);
        if (failed(member)) { status = failure(); return; }
        if (!member->instance || !llvm::isa<TerminalType>(operation->getResult(0).getType()))
          return;
      }
      auto exists = active(operation, definition);
      if (failed(exists)) { status = failure(); return; }
      if (!*exists) return;
      if (operation->getNumResults() != 1) { status = failure(); return; }
      auto endpoint = nodal::resolveConservativeEndpoint(operation->getResult(0), operation);
      if (failed(endpoint) || !endpoint->instance || !endpoint->port) {
        status = failure(); return;
      }
      refs[endpoint->instance].push_back(std::move(*endpoint));
    });
    return status;
  };
  auto appendChild = [&](Operation *instance,
                        ArrayRef<nodal::ConservativeEndpoint> endpoints,
                        auto append) -> LogicalResult {
    auto child = summaries.find(nodal::resolveInstanceModuleDefinition(instance));
    if (child == summaries.end()) return failure();
    llvm::SmallVector<std::optional<BoundaryPortKey>, 4> keys;
    for (const auto &endpoint : endpoints) {
      auto port = key(endpoint);
      if (!remaining) return failure();
      keys.push_back(port && child->second.groups.count(*port) ? port : std::nullopt);
    }
    for (unsigned i = 0; i < endpoints.size(); ++i) {
      if (!charge()) return failure();
      if (!keys[i] || child->second.touched.contains(*keys[i]))
        append(endpoints[i].shape.projection.root, endpoints[i].shape.projection.root);
      for (unsigned j = 0; j < i; ++j) {
        if (!charge()) return failure();
        if (!keys[i] || !keys[j] ||
            child->second.groups.lookup(*keys[j]) == child->second.groups.lookup(*keys[i])) {
          append(endpoints[i].shape.projection.root, endpoints[j].shape.projection.root);
          if (keys[i] && keys[j]) break; // Exact group needs only a spanning edge.
        }
      }
    }
    return success();
  };
  while (!pending.empty()) {
    if (!charge()) return failure();
    auto [definition, expanded] = pending.pop_back_val();
    if (summaries.count(definition)) continue;
    if (!expanded) {
      if (visiting.lookup(definition)) return failure();
      visiting[definition] = 1;
      pending.push_back({definition, true});
      bool valid = true;
      definition->walk([&](nodal::InstanceOp instance) {
        if (!valid) return;
        if (!charge()) { valid = false; return; }
        if (enclosingNodalModule(instance) != definition) return;
        Operation *target = nodal::resolveInstanceModuleDefinition(instance);
        if (!target) { valid = false; return; }
        if (!summaries.count(target)) pending.push_back({target, false});
      });
      if (!valid) return failure();
      continue;
    }
    llvm::DenseMap<Value, unsigned> indices;
    llvm::DenseMap<BoundaryPortKey, unsigned> ports;
    llvm::SmallVector<std::pair<Value, Value>, 8> edges;
    unsigned nextIndex = 0;
    auto index = [&](Value value) {
      auto [entry, inserted] = indices.try_emplace(value, nextIndex);
      if (inserted) ++nextIndex;
      return entry->second;
    };
    auto add = [&](Value a, Value b) { index(a); index(b); edges.push_back({a,b}); };
    if (Block *body = moduleBody(definition))
      for (Operation &operation : *body) {
        if (!charge()) return failure();
        if (llvm::isa<TerminalOp, TerminalArrayOp>(operation) && operation.getNumResults() == 1)
          ports.try_emplace({&operation, StringAttr()}, index(operation.getResult(0)));
      }
    llvm::SmallVector<DigitalInterfaceEndpoint, 4> logical;
    llvm::SmallVector<DigitalInterfaceAccess, 4> accesses;
    if (failed(collectDigitalInterfaceEndpoints(definition, logical, &accesses))) return failure();
    for (const auto &endpoint : logical) {
      if (!charge()) return failure();
      if (endpoint.instance || endpoint.selection || endpoint.operation->getParentOp() != definition)
        continue;
      for (const auto &leaf : endpoint.leaves) {
        if (!charge()) return failure();
        if (leaf.protocol.getValue() != "conservative" || !llvm::isa<TerminalType>(leaf.type))
          continue;
        auto [entry, inserted] = ports.try_emplace({endpoint.declaration, leaf.path}, nextIndex);
        if (inserted) ++nextIndex;
      }
    }
    for (const auto &access : accesses) {
      if (!charge()) return failure();
      if (access.identity.instance || access.identity.selection ||
          access.value.getDefiningOp()->getParentOp() != definition) continue;
      auto port = ports.find({access.identity.endpoint, access.identity.path});
      if (port != ports.end()) indices.try_emplace(access.canonicalValue, port->second);
    }
    LogicalResult valid = success();
    definition->walk([&](Operation *operation) {
      if (failed(valid)) return;
      if (!charge()) { valid = failure(); return; }
      if (enclosingNodalModule(operation) != definition ||
          !llvm::isa<ConnectOp, ConnectArrayOp, AliasOp>(operation)) return;
      auto exists = active(operation, definition);
      if (failed(exists)) { valid = failure(); return; }
      if (!*exists) return;
      if (operation->getNumOperands() < 2) { valid = failure(); return; }
      auto first = nodal::resolveConservativeEndpoint(operation->getOperand(0), operation);
      if (failed(first)) { valid = failure(); return; }
      for (Value operand : operation->getOperands().drop_front()) {
        if (!charge()) { valid = failure(); return; }
        auto next = nodal::resolveConservativeEndpoint(operand, operation);
        if (failed(next)) { valid = failure(); return; }
        if (first->shape.projection.root == next->shape.projection.root &&
            first->shape.projection.canonicalKey == next->shape.projection.canonicalKey) continue;
        add(first->shape.projection.root, next->shape.projection.root);
        for (Operation *scope = operation->getParentOp(); scope && scope != definition;
             scope = scope->getParentOp()) {
          if (!charge()) { valid = failure(); return; }
          if (llvm::isa<GenerateOp>(scope)) {
            auto domain = nodal::resolveGeneratedDomain(scope, remaining);
            if (failed(domain)) { valid = failure(); return; }
            if (domain->maximumTripCount > 1) {
              add(first->shape.projection.root, first->shape.projection.root);
              add(next->shape.projection.root, next->shape.projection.root);
              break;
            }
          }
        }
      }
    });
    if (failed(valid)) return failure();
    llvm::DenseMap<Operation *, llvm::SmallVector<nodal::ConservativeEndpoint, 2>> refs;
    if (failed(references(definition, refs))) return failure();
    for (const auto &[instance, endpoints] : refs)
      if (failed(appendChild(instance, endpoints, add))) return failure();
    UnionFind groups(nextIndex);
    llvm::SmallVector<Value, 4> possibleShorts;
    for (auto [a,b] : edges) {
      if (!charge()) return failure();
      if (groups.find(indices.lookup(a)) == groups.find(indices.lookup(b))) possibleShorts.push_back(a);
      groups.unite(indices.lookup(a),indices.lookup(b));
    }
    llvm::DenseSet<unsigned> touched;
    for (Value root : possibleShorts) {
      if (!charge()) return failure();
      touched.insert(groups.find(indices.lookup(root)));
    }
    BoundaryAliasSummary summary;
    for (auto [port, index] : ports) {
      if (!charge()) return failure();
      unsigned group = groups.find(index);
      summary.groups[port] = group;
      if (touched.contains(group)) summary.touched.insert(port);
    }
    summaries.try_emplace(definition,std::move(summary));visiting[definition]=0;
  }
  llvm::DenseMap<Operation *, llvm::SmallVector<nodal::ConservativeEndpoint, 2>> refs;
  if (failed(references(module, refs))) return failure();
  for (const auto &[instance,endpoints] : refs)
    if (failed(appendChild(instance,endpoints,[&](Value a,Value b){uncertain.push_back({a,b});})))
      return failure();
  return success();
}

nodal::ConservativeAlias comparePoints(const nodal::StaticShapePoint &left,
                                       const nodal::StaticShapePoint &right) {
  using Alias = nodal::ConservativeAlias;
  if (left.root != right.root)
    return Alias::Distinct;
  auto same = [](const auto &a, const auto &b) {
    return nodal::equivalentInstantiatedIntegerExpressions(a, b);
  };
  auto disjoint = [](const auto &a, const auto &b) {
    return nodal::proveIntegerExpressionLess(a, b) || nodal::proveIntegerExpressionLess(b, a);
  };
  if (left.flatOrdinal && right.flatOrdinal) {
    if (same(*left.flatOrdinal, *right.flatOrdinal))
      return Alias::Equal;
    if (disjoint(*left.flatOrdinal, *right.flatOrdinal))
      return Alias::Distinct;
  }
  if (left.coordinates.size() == right.coordinates.size() && !left.coordinates.empty()) {
    bool equal = true;
    for (auto [a, b] : llvm::zip(left.coordinates, right.coordinates)) {
      if (disjoint(a, b))
        return Alias::Distinct;
      equal &= same(a, b);
    }
    if (equal)
      return Alias::Equal;
  }
  return Alias::Unknown;
}

bool disjointRegions(const nodal::StaticShapeRegion &left, const nodal::StaticShapeRegion &right) {
  using Kind = nodal::StaticShapeRegion::Kind;
  auto disjoint = [](const auto &a, const auto &b) {
    return nodal::proveIntegerExpressionLess(a.upperExclusive, b.lower, true) ||
           nodal::proveIntegerExpressionLess(b.upperExclusive, a.lower, true);
  };
  if (left.kind == Kind::Interval && right.kind == Kind::Interval)
    return disjoint(left.interval, right.interval);
  if (left.kind == Kind::Box && right.kind == Kind::Box && left.axes.size() == right.axes.size())
    for (auto [a, b] : llvm::zip(left.axes, right.axes))
      if (disjoint(a, b))
        return true;
  return false;
}
} // namespace

nodal::ConservativeAlias nodal::compareConservativeEndpoints(Value left, Value right,
                                                             Operation *use) {
  return compareConservativeEndpointOccurrences(left, use, right, use);
}

nodal::ConservativeAlias nodal::compareConservativeEndpointOccurrences(Value left,
                                                                       Operation *leftUse,
                                                                       Value right,
                                                                       Operation *rightUse) {
  using Alias = ConservativeAlias;
  if (!left || !right || !leftUse || !rightUse || !llvm::isa<TerminalType>(left.getType()) ||
      !llvm::isa<TerminalType>(right.getType()) ||
      enclosingNodalModule(leftUse) != enclosingNodalModule(rightUse))
    return Alias::Invalid;
  auto lhs = resolveConservativeEndpoint(left, leftUse);
  auto rhs = resolveConservativeEndpoint(right, rightUse);
  if (failed(lhs) || failed(rhs))
    return Alias::Invalid;
  // Preserve the established same-use identity query. Across uses, an empty
  // occurrence has no physical support even when both references are identical.
  if (left == right && leftUse == rightUse)
    return Alias::Equal;
  for (Operation *support : {lhs->useScope, rhs->useScope})
    for (Operation *scope = support; scope && !llvm::isa<nodal::ModuleOp>(scope);
         scope = scope->getParentOp())
      if (llvm::isa<GenerateOp>(scope)) {
        auto activity = analyzeGeneratedScopeActivity(scope);
        if (failed(activity))
          return Alias::Invalid;
        if (*activity == GeneratedScopeActivity::AlwaysEmpty)
          return Alias::Inert; // No scalar occurrence, not proof of distinct nodes.
      }
  StaticShapePoint start, target;
  bool pointsProved = resolveStaticShapePoint(lhs->shape.projection, leftUse, start) ==
                          ShapeRegionProofStatus::Proved &&
                      resolveStaticShapePoint(rhs->shape.projection, rightUse, target) ==
                          ShapeRegionProofStatus::Proved;
  if (pointsProved && comparePoints(start, target) == Alias::Equal)
    return Alias::Equal;
  struct Edge {
    Operation *site;
    ConservativeEndpoint left, right;
    std::optional<StaticShapeRegion> leftRegion, rightRegion;
  };
  llvm::SmallVector<Edge, 4> edges;
  auto makeEdge = [&](ConservativeEndpoint a, ConservativeEndpoint b, Operation *site) {
    Edge edge{site, std::move(a), std::move(b), {}, {}};
    StaticShapeRegion x, y;
    if (resolveStaticShapeRegion(edge.left.shape.projection, site, x) ==
        ShapeRegionProofStatus::Proved)
      edge.leftRegion = std::move(x);
    if (resolveStaticShapeRegion(edge.right.shape.projection, site, y) ==
        ShapeRegionProofStatus::Proved)
      edge.rightRegion = std::move(y);
    return edge;
  };
  auto add = [&](ConservativeEndpoint a, ConservativeEndpoint b, Operation *site) {
    edges.push_back(makeEdge(std::move(a), std::move(b), site));
  };
  llvm::SmallVector<Operation *, 4> visibleScopes;
  for (Operation *scope = lhs->useScope; scope; scope = scope->getParentOp()) {
    if (structuralBody(scope) && (scope == rhs->useScope || scope->isProperAncestor(rhs->useScope)))
      visibleScopes.push_back(scope);
    if (llvm::isa<nodal::ModuleOp>(scope))
      break;
  }
  for (Operation *scope : visibleScopes) {
    for (Operation &operation : *structuralBody(scope)) {
      if (isConservativeRelation(&operation)) {
        auto relation = resolveConservativeRelation(&operation);
        if (failed(relation))
          return Alias::Invalid;
        add(std::move(relation->left), std::move(relation->right), &operation);
      } else if (llvm::isa<ConnectOp, AliasOp>(operation)) {
        if (operation.getNumOperands() < 2)
          return Alias::Invalid;
        auto a = resolveConservativeEndpoint(operation.getOperand(0), &operation);
        if (failed(a))
          return Alias::Invalid;
        for (Value operand : operation.getOperands().drop_front()) {
          auto b = resolveConservativeEndpoint(operand, &operation);
          if (failed(b))
            return Alias::Invalid;
          add(*a, std::move(*b), &operation);
        }
      }
    }
  }
  // Relations in another nonempty generated occurrence are families, not one
  // concrete union-find edge. Retain their possible root connectivity so an
  // outside consumer cannot infer distinctness from an omitted family.
  llvm::SmallVector<std::pair<Value, Value>, 4> families;
  llvm::SmallVector<Operation *, 4> familyScopes;
  llvm::SmallVector<bool, 4> certifiedFamilies;
  llvm::DenseMap<Operation *, llvm::SmallVector<Edge, 4>> hiddenEdges;
  bool malformedFamily = false;
  Operation *module = enclosingNodalModule(leftUse);
  module->walk([&](Operation *operation) {
    Operation *scope = operation->getParentOp();
    if (enclosingNodalModule(operation) != module || !structuralBody(scope) ||
        llvm::is_contained(visibleScopes, scope) ||
        (!isConservativeRelation(operation) && !llvm::isa<ConnectOp, AliasOp>(operation)))
      return;
    bool empty = false;
    for (Operation *parent = scope; parent && parent != module; parent = parent->getParentOp())
      if (llvm::isa<GenerateOp>(parent)) {
        auto domain = resolveGeneratedDomain(parent);
        if (failed(domain)) {
          malformedFamily = true;
          return;
        }
        empty |= domain->maximumTripCount == 0;
      }
    if (empty)
      return;
    if (operation->getNumOperands() < 2) {
      malformedFamily = true;
      return;
    }
    auto first = resolveConservativeEndpoint(operation->getOperand(0), operation);
    if (failed(first)) {
      malformedFamily = true;
      return;
    }
    for (Value operand : operation->getOperands().drop_front()) {
      auto next = resolveConservativeEndpoint(operand, operation);
      if (failed(next)) {
        malformedFamily = true;
        return;
      }
      families.push_back({first->shape.projection.root, next->shape.projection.root});
      familyScopes.push_back(scope);
      certifiedFamilies.push_back(false);
      hiddenEdges[scope].push_back(makeEdge(*first, *next, operation));
    }
  });
  llvm::SmallVector<std::pair<Value, Value>, 4> childUncertainty;
  constexpr uint64_t maximumWork = 16384, maximumStates = 1024;
  unsigned remainingSummaryWork = maximumWork;
  if (malformedFamily ||
      failed(collectChildBoundaryUncertainty(module, childUncertainty, remainingSummaryWork)))
    return remainingSummaryWork ? Alias::Invalid : Alias::ResourceLimit;
  uint64_t work = maximumWork - remainingSummaryWork;
  // Prove only an actual path in one inspected selected occurrence. Reuse the
  // original hidden relation records; never turn coordinate disjointness into
  // electrical disjointness or manufacture occurrence-local SSA roots.
  auto selectedContext = [&](Value root) -> FailureOr<GeneratedSelectionProof> {
    auto member = inspectDigitalInterfaceMember(root.getDefiningOp());
    if (failed(member) || !member->selection)
      return failure();
    Operation *selection = member->selection;
    auto key = selection->getAttrOfType<StringAttr>("member");
    if (!key || selection->getNumOperands() != 2)
      return failure();
    unsigned remaining = maximumWork - work;
    llvm::SmallVector<Operation *, 2> uses;
    auto result = inspectGeneratedSelection(selection->getOperand(0), selection->getOperand(1),
                                            key.getValue(), selection, uses, remaining);
    work = maximumWork - remaining;
    return result;
  };
  auto leftContext = selectedContext(lhs->shape.projection.root);
  auto rightContext = selectedContext(rhs->shape.projection.root);
  if (work >= maximumWork)
    return Alias::ResourceLimit;
  bool commonContext = succeeded(leftContext) && succeeded(rightContext) &&
      leftContext->family == rightContext->family &&
      leftContext->selections.size() == rightContext->selections.size();
  if (commonContext)
    for (auto [a, b] : llvm::zip(leftContext->selections, rightContext->selections))
      commonContext &= a.family == b.family &&
          equivalentInstantiatedIntegerExpressions(a.coordinate, b.coordinate);
  auto scalarRoot = [](const StaticShapeProjection &projection) {
    return llvm::isa<TerminalType>(projection.root.getType()) && projection.projections.empty();
  };
  if (commonContext && left.getType() == right.getType() &&
      scalarRoot(lhs->shape.projection) && scalarRoot(rhs->shape.projection)) {
    Operation *family = leftContext->family;
    auto found = hiddenEdges.find(family);
    // Direct family scalar topology first. Nested/array maps remain governed by
    // the original uncertainty path until their contextual maps are proved.
    if (llvm::isa<ModuleOp>(family->getParentOp()) && found != hiddenEdges.end()) {
      auto leftMember = inspectDigitalInterfaceMember(lhs->shape.projection.root.getDefiningOp());
      auto rightMember = inspectDigitalInterfaceMember(rhs->shape.projection.root.getDefiningOp());
      llvm::SmallVector<Value, 4> startRoots, targetRoots;
      llvm::DenseSet<Value> inspectedRoots;
      auto anchor = [&](const StaticShapeProjection &candidate) {
        if (++work >= maximumWork || !inspectedRoots.insert(candidate.root).second ||
            !scalarRoot(candidate) || candidate.root.getType() != left.getType() ||
            !llvm::isa_and_nonnull<MemberAccessOp>(candidate.root.getDefiningOp()))
          return;
        auto original = inspectDigitalInterfaceMember(candidate.root.getDefiningOp());
        if (failed(original) || original->selection)
          return;
        auto match = [&](const DigitalInterfaceMember &selected,
                         const StaticShapeProjection &projection,
                         llvm::SmallVectorImpl<Value> &roots) {
          // An exact owner/member key only filters candidates. Shape still
          // independently proves each actual selected-to-template anchor.
          if (original->endpoint != selected.endpoint || original->instance != selected.instance ||
              original->module != selected.module || original->path != selected.path)
            return;
          constexpr uint64_t allowance = 256;
          if (work + allowance > maximumWork) {
            work = maximumWork;
            return;
          }
          work += allowance;
          auto overlap = compareStaticShapeContextualProjections(
              projection, leftContext->selections, candidate, rightContext->selections, leftUse,
              ShapeRegionProofLimits{allowance, 64});
          if (overlap.status == ShapeRegionProofStatus::Proved &&
              overlap.overlap == ShapeRegionOverlap::Overlapping)
            roots.push_back(candidate.root);
        };
        if (succeeded(leftMember))
          match(*leftMember, lhs->shape.projection, startRoots);
        if (succeeded(rightMember))
          match(*rightMember, rhs->shape.projection, targetRoots);
      };
      for (const auto &edge : found->second) {
        anchor(edge.left.shape.projection);
        anchor(edge.right.shape.projection);
      }
      // These are existing canonical scalar roots under one common checked
      // context, not fabricated SSA occurrences. Preserve that same context
      // along every actual edge; do not cross to a sibling/other iteration.
      llvm::SmallVector<Value, 8> pending(startRoots.begin(), startRoots.end());
      for (unsigned cursor = 0; cursor < pending.size(); ++cursor) {
        if (++work >= maximumWork || pending.size() > maximumStates)
          return Alias::ResourceLimit;
        if (llvm::is_contained(targetRoots, pending[cursor]))
          return Alias::Equal;
        for (const auto &edge : found->second) {
          if (++work >= maximumWork)
            return Alias::ResourceLimit;
          if (!scalarRoot(edge.left.shape.projection) || !scalarRoot(edge.right.shape.projection) ||
              edge.left.value.getType() != left.getType() || edge.right.value.getType() != left.getType())
            continue;
          for (bool reverse : {false, true}) {
            Value from = (reverse ? edge.right : edge.left).shape.projection.root;
            Value to = (reverse ? edge.left : edge.right).shape.projection.root;
            if (pending[cursor] == from && !llvm::is_contained(pending, to))
              pending.push_back(to);
          }
        }
      }
      if (work >= maximumWork)
        return Alias::ResourceLimit;
    }
  }
  // A repeated tree with one shared root and otherwise fresh vertices cannot
  // join distinct shared lanes. Multiple shared roots additionally require
  // checked injective maps with identical coordinates in one actual Generate.
  // Retain all root connectivity even for certified families. Child uncertainty,
  // cycles, parallel paths and unproved maps prevent this sufficient proof.
  llvm::DenseMap<Operation *, llvm::DenseMap<Value, bool>> familyTrees;
  auto certifiedOccurrenceTree = [&](Operation *scope, Value root, llvm::ArrayRef<Edge> proofEdges) {
    auto cached = familyTrees.find(scope);
    if (cached == familyTrees.end()) {
      struct Vertex {
        const ConservativeEndpoint *endpoint;
        const std::optional<StaticShapeRegion> *region;
      };
      llvm::SmallVector<Vertex, 8> vertices;
      for (const auto &edge : proofEdges) {
        vertices.push_back({&edge.left, &edge.leftRegion});
        vertices.push_back({&edge.right, &edge.rightRegion});
      }
      UnionFind tree(vertices.size());
      // A fresh array is not one physical node. Keep its independently proved
      // disjoint endpoint regions separate; unknown overlap retains the old
      // conservative root aggregation. Shared roots remain aggregated because
      // other occurrences can reach different coordinates of those roots.
      for (unsigned i = 0; i < vertices.size(); ++i) {
        const auto &a = *vertices[i].endpoint;
        bool fresh = a.declarationScope == scope ||
                     (a.declarationScope && scope->isProperAncestor(a.declarationScope));
        for (unsigned j = 0; j < i; ++j) {
          const auto &b = *vertices[j].endpoint;
          if (a.shape.projection.root != b.shape.projection.root)
            continue;
          if (fresh && *vertices[i].region && *vertices[j].region &&
              disjointRegions(**vertices[i].region, **vertices[j].region))
            continue;
          tree.unite(i, j);
        }
      }
      llvm::SmallVector<unsigned, 4> cyclic;
      for (auto [index, edge] : llvm::enumerate(proofEdges)) {
        const auto &a = edge.left.shape.projection, &b = edge.right.shape.projection;
        if (a.root == b.root && a.canonicalKey == b.canonicalKey)
          continue;
        unsigned x = 2 * index, y = x + 1;
        if (tree.find(x) == tree.find(y))
          cyclic.push_back(x);
        tree.unite(x, y);
      }
      llvm::DenseSet<unsigned> unsupported;
      llvm::DenseMap<unsigned, llvm::SmallVector<Value, 2>> shared;
      for (auto [index, vertex] : llvm::enumerate(vertices)) {
        const auto &endpoint = *vertex.endpoint;
        Value value = endpoint.shape.projection.root;
        Operation *declaration = endpoint.declarationScope;
        unsigned component = tree.find(index);
        if (declaration && declaration->isProperAncestor(scope)) {
          if (!llvm::is_contained(shared[component], value))
            shared[component].push_back(value);
        } else if (!declaration ||
                   (declaration != scope && !scope->isProperAncestor(declaration))) {
          unsupported.insert(component);
        }
      }
      for (unsigned index : cyclic)
        unsupported.insert(tree.find(index));
      for (const auto &[a, b] : childUncertainty)
        for (auto [index, vertex] : llvm::enumerate(vertices))
          if (vertex.endpoint->shape.projection.root == a ||
              vertex.endpoint->shape.projection.root == b)
            unsupported.insert(tree.find(index));
      // Multiple shared roots are safe only with independently checked
      // injective scalar maps for this same direct actual Generate. Keep the
      // original one-shared-root fresh-tree proof unchanged. This sufficient
      // profile deliberately refuses shifted, permuted, nested and invariant
      // shared mappings rather than interpreting expressions here.
      llvm::DenseMap<unsigned, bool> exactMaps;
      llvm::DenseMap<unsigned, StaticShapePoint> referencePoints;
      for (auto [index, vertex] : llvm::enumerate(vertices)) {
        const auto &endpoint = *vertex.endpoint;
        unsigned component = tree.find(index);
        if (!endpoint.declarationScope || !endpoint.declarationScope->isProperAncestor(scope))
          continue;
        auto [flag, inserted] = exactMaps.try_emplace(component, true);
        StaticShapeRegion image;
        StaticShapePoint point;
        Operation *site = proofEdges[index / 2].site;
        bool valid = llvm::isa<GenerateOp>(scope) && llvm::isa<ModuleOp>(scope->getParentOp()) &&
            resolveStaticShapeIterationImage(endpoint.shape.projection, site, image) ==
                ShapeRegionProofStatus::Proved && image.iterationCount() == 1 &&
            image.iteration->loop == scope &&
            resolveStaticShapePoint(endpoint.shape.projection, site, point) ==
                ShapeRegionProofStatus::Proved && !point.coordinates.empty();
        flag->second &= valid;
        if (!valid)
          continue;
        auto [reference, first] = referencePoints.try_emplace(component, point);
        if (!first) {
          valid = reference->second.coordinates.size() == point.coordinates.size();
          if (valid)
            for (auto [a, b] : llvm::zip(reference->second.coordinates, point.coordinates))
              valid &= equivalentInstantiatedIntegerExpressions(a, b);
          flag->second &= valid;
        }
      }
      llvm::DenseMap<Value, bool> safe;
      for (auto [index, vertex] : llvm::enumerate(vertices)) {
        Value value = vertex.endpoint->shape.projection.root;
        unsigned component = tree.find(index);
        bool proved = !shared[component].empty() && !unsupported.contains(component) &&
            (shared[component].size() == 1 || exactMaps.lookup(component));
        auto [entry, inserted] = safe.try_emplace(value, proved);
        if (!inserted)
          entry->second &= proved;
      }
      cached = familyTrees.try_emplace(scope, std::move(safe)).first;
    }
    return cached->second.lookup(root);
  };
  for (unsigned i = 0; i < families.size(); ++i)
    certifiedFamilies[i] = certifiedOccurrenceTree(familyScopes[i], families[i].first,
                                             hiddenEdges.find(familyScopes[i])->second);
  // Other occurrences of a visible repeated template can join shared roots
  // too. Retain the exact current edge for Equal proofs, but never treat that
  // one occurrence as a global injective family.
  for (const auto &edge : edges) {
    for (Operation *scope = edge.site->getParentOp(); scope && scope != module;
         scope = scope->getParentOp()) {
      if (!llvm::isa<GenerateOp>(scope))
        continue;
      auto domain = resolveGeneratedDomain(scope);
      if (failed(domain)) {
        malformedFamily = true;
        break;
      }
      // A singleton inner template still participates in every occurrence of
      // its repeated outer template. Only roots declared above that template
      // are shared between those occurrences; roots inside it stay distinct.
      auto sharedAcross = [&](Operation *declarationScope) {
        return declarationScope && declarationScope != scope &&
               declarationScope->isProperAncestor(scope);
      };
      if (domain->maximumTripCount > 1 &&
          (sharedAcross(edge.left.declarationScope) || sharedAcross(edge.right.declarationScope))) {
        if (certifiedOccurrenceTree(scope, edge.left.shape.projection.root, edges))
          continue;
        families.push_back({edge.left.shape.projection.root, edge.right.shape.projection.root});
        familyScopes.push_back(scope);
        certifiedFamilies.push_back(false);
        break;
      }
    }
  }
  const size_t generatedFamilyCount = families.size();
  if (malformedFamily)
    return Alias::Invalid;
  families.append(childUncertainty.begin(), childUncertainty.end());
  // A selected Interface projection is not a disconnected electrical root.
  // Its original member may participate in generated relations, including a
  // shared scalar that couples otherwise distinct coordinates. These links
  // carry possible connectivity only, never an occurrence-coordinate mapping.
  llvm::SmallVector<Value, 8> originalRoots;
  auto rememberRoot = [&](Value root) {
    if (++work > maximumWork || originalRoots.size() > maximumStates)
      return;
    if (!llvm::is_contained(originalRoots, root))
      originalRoots.push_back(root);
  };
  rememberRoot(lhs->shape.projection.root);
  rememberRoot(rhs->shape.projection.root);
  for (const auto &edge : edges) {
    rememberRoot(edge.left.shape.projection.root);
    rememberRoot(edge.right.shape.projection.root);
  }
  for (auto [a, b] : families) {
    rememberRoot(a);
    rememberRoot(b);
  }
  if (work > maximumWork || originalRoots.size() > maximumStates)
    return Alias::ResourceLimit;
  llvm::SmallVector<std::pair<Value, DigitalInterfaceMember>, 8> members;
  for (Value root : originalRoots) {
    if (++work > maximumWork)
      return Alias::ResourceLimit;
    if (!llvm::isa_and_nonnull<MemberAccessOp>(root.getDefiningOp()))
      continue;
    auto member = inspectDigitalInterfaceMember(root.getDefiningOp());
    if (failed(member))
      return Alias::Invalid;
    members.push_back({root, *member});
  }
  for (unsigned i = 0; i < members.size(); ++i)
    for (unsigned j = 0; j < i; ++j) {
      if (++work > maximumWork)
        return Alias::ResourceLimit;
      const auto &a = members[i].second;
      const auto &b = members[j].second;
      if ((a.selection || b.selection) && a.endpoint == b.endpoint &&
          a.instance == b.instance && a.module == b.module && a.path == b.path)
        families.push_back({members[i].first, members[j].first});
    }
  // First establish which actual roots can participate. Uncertain coordinates
  // on an unrelated component cannot make a disconnected root possibly equal.
  llvm::SmallVector<Value, 8> roots{lhs->shape.projection.root};
  for (unsigned cursor = 0; cursor < roots.size(); ++cursor) {
    auto include = [&](Value a, Value b) {
      if (a == roots[cursor] && !llvm::is_contained(roots, b))
        roots.push_back(b);
      if (b == roots[cursor] && !llvm::is_contained(roots, a))
        roots.push_back(a);
    };
    for (const auto &edge : edges)
      include(edge.left.shape.projection.root, edge.right.shape.projection.root);
    for (auto [a, b] : families)
      include(a, b);
  }
  if (!llvm::is_contained(roots, rhs->shape.projection.root))
    return Alias::Distinct;
  // Root capability and every possible relation/family above are already
  // checked. Disconnected roots need no scalar coordinate certificate, while
  // a connected component still requires the exact bounded point proof.
  if (!pointsProved)
    return Alias::Unknown;
  if (start.root == target.root && comparePoints(start, target) == Alias::Distinct) {
    llvm::DenseMap<Value, unsigned> indices;
    for (auto [index, root] : llvm::enumerate(roots))
      indices[root] = index;
    UnionFind tree(roots.size());
    bool injectiveTree = true;
    auto edge = [&](Value a, Value b) {
      if (!indices.count(a) || !indices.count(b))
        return;
      unsigned x = indices.lookup(a), y = indices.lookup(b);
      if (tree.find(x) == tree.find(y))
        injectiveTree = false;
      tree.unite(x, y);
    };
    for (const auto &relation : edges) {
      const auto &a = relation.left.shape.projection, &b = relation.right.shape.projection;
      if (a.root != b.root || a.canonicalKey != b.canonicalKey)
        edge(a.root, b.root);
    }
    Operation *certifiedScope = nullptr;
    for (auto [index, family] : llvm::enumerate(families)) {
      if (index < generatedFamilyCount &&
          (indices.count(family.first) || indices.count(family.second))) {
        if (!certifiedFamilies[index] ||
            (certifiedScope && certifiedScope != familyScopes[index]))
          injectiveTree = false;
        certifiedScope = familyScopes[index];
      }
      if (index >= generatedFamilyCount &&
          (indices.count(family.first) || indices.count(family.second)))
        injectiveTree = false;
      edge(family.first, family.second);
    }
    if (injectiveTree)
      return Alias::Distinct;
  }
  // One traversal preserves the legacy edge order and allowances. The
  // optional continuation spends only the original query's remaining budget.
  // Its total state ceiling includes the completed legacy traversal.
  uint64_t totalStates = 0;
  auto traverse = [&](llvm::SmallVectorImpl<StaticShapePoint> &pending, bool enhanced,
                      llvm::ArrayRef<StaticShapePoint> opposite) -> Alias {
    auto append = [&](StaticShapePoint next) {
      for (const auto &old : pending) {
        if (enhanced && ++work > maximumWork)
          return false;
        if (comparePoints(old, next) == Alias::Equal)
          return true;
      }
      if (enhanced && ++totalStates > maximumStates)
        return false;
      pending.push_back(std::move(next));
      return true;
    };
    bool uncertain = false;
    for (unsigned cursor = 0; cursor < pending.size(); ++cursor) {
      if (++work > maximumWork || pending.size() > maximumStates)
        return Alias::ResourceLimit;
      if (!enhanced) {
        auto relation = comparePoints(pending[cursor], target);
        if (relation == Alias::Equal)
          return Alias::Equal;
        uncertain |= relation == Alias::Unknown;
      } else {
        for (const auto &other : opposite) {
          if (++work > maximumWork)
            return Alias::ResourceLimit;
          if (comparePoints(pending[cursor], other) == Alias::Equal)
            return Alias::Equal;
        }
      }
      // Copy: appending a mapped point may reallocate pending.
      StaticShapePoint point = pending[cursor];
      for (auto [a, b] : families)
        uncertain |= point.root == a || point.root == b;
      llvm::SmallPtrSet<Operation *, 4> mappedScopes;
      for (Operation *scope : familyScopes) {
        if (!mappedScopes.insert(scope).second)
          continue;
        auto found = hiddenEdges.find(scope);
        if (found == hiddenEdges.end())
          continue;
        for (const auto &edge : found->second)
          for (bool reverse : {false, true}) {
            if (++work > maximumWork)
              return Alias::ResourceLimit;
            const auto &from = reverse ? edge.right : edge.left;
            const auto &to = reverse ? edge.left : edge.right;
            if (point.root != from.shape.projection.root)
              continue;
            constexpr uint64_t allowance = 512;
            if (work + allowance > maximumWork)
              return Alias::ResourceLimit;
            work += allowance;
            StaticShapePoint mapped;
            auto mapper = enhanced ? mapStaticShapeSameOccurrenceOrInvariantPoint
                                   : mapStaticShapeSameOccurrencePoint;
            auto status = mapper(from.shape.projection, point, to.shape.projection, edge.site,
                                 mapped, ShapeRegionProofLimits{allowance, 64});
            if (status == ShapeRegionProofStatus::ResourceLimit)
              return Alias::ResourceLimit;
            if (status != ShapeRegionProofStatus::Proved)
              continue; // Existing family uncertainty remains; never infer Distinct.
            if (!append(std::move(mapped)))
              return Alias::ResourceLimit;
          }
      }
      for (const auto &edge : edges) {
        for (bool reverse : {false, true}) {
          if (++work > maximumWork)
            return Alias::ResourceLimit;
          const auto &from = reverse ? edge.right : edge.left;
          const auto &region = reverse ? edge.rightRegion : edge.leftRegion;
          const auto &destination = reverse ? edge.leftRegion : edge.rightRegion;
          if (point.root != from.shape.projection.root)
            continue;
          // A scalar relation in this exact occurrence directly connects its two
          // checked points. Aggregate iteration-image remapping is neither needed
          // nor permission to identify a different occurrence of the template.
          const auto &to = reverse ? edge.left : edge.right;
          StaticShapePoint fromPoint, toPoint;
          if (resolveStaticShapePoint(from.shape.projection, edge.site, fromPoint) ==
                  ShapeRegionProofStatus::Proved &&
              comparePoints(point, fromPoint) == Alias::Equal &&
              resolveStaticShapePoint(to.shape.projection, edge.site, toPoint) ==
                  ShapeRegionProofStatus::Proved) {
            if (!append(std::move(toPoint)))
              return Alias::ResourceLimit;
            continue;
          }
          if (!region || !destination) {
            uncertain = true;
            continue;
          }
          auto location = locateStaticShapeScalar(*region, point, edge.site);
          if (location.status == ShapeRegionProofStatus::ResourceLimit)
            return Alias::ResourceLimit;
          if (location.membership == ShapeRegionMembership::Outside)
            continue;
          if (location.membership != ShapeRegionMembership::Inside || !location.ordinal) {
            uncertain = true;
            continue;
          }
          StaticShapePoint next;
          auto status = mapStaticShapeOrdinal(*destination, *location.ordinal, edge.site, next);
          if (status == ShapeRegionProofStatus::ResourceLimit)
            return Alias::ResourceLimit;
          if (status != ShapeRegionProofStatus::Proved) {
            uncertain = true;
            continue;
          }
          if (!append(std::move(next)))
            return Alias::ResourceLimit;
        }
      }
    }
    return uncertain ? Alias::Unknown : Alias::Distinct;
  };
  llvm::SmallVector<StaticShapePoint, 8> legacy{start};
  Alias original = traverse(legacy, false, {});
  if (original != Alias::Unknown)
    return original;
  totalStates = legacy.size();
  legacy.clear();
  if (++totalStates > maximumStates)
    return Alias::ResourceLimit;
  llvm::SmallVector<StaticShapePoint, 8> fromLeft{std::move(start)};
  if (traverse(fromLeft, true, {}) == Alias::ResourceLimit)
    return Alias::ResourceLimit;
  if (++totalStates > maximumStates)
    return Alias::ResourceLimit;
  llvm::SmallVector<StaticShapePoint, 8> fromRight{std::move(target)};
  Alias intersection = traverse(fromRight, true, fromLeft);
  return intersection == Alias::Equal || intersection == Alias::ResourceLimit ? intersection
                                                                              : Alias::Unknown;
}

LogicalResult nodal::verifyConservativeArrayBindings(Operation *scope) {
  Block *body = structuralBody(scope);
  if (!body)
    return success();
  using PortKey = std::pair<Operation *, Operation *>;
  struct Coverage {
    Value root;
    llvm::SmallVector<StaticShapeRegion, 4> regions;
    llvm::StringSet<> keys;
    bool whole = false;
  };
  std::map<PortKey, Coverage> coverage;
  std::map<PortKey, Operation *> references;
  llvm::StringSet<> occurrences, connectionIds;
  for (Operation &operation : *body) {
    if (llvm::isa<InstanceTerminalArrayOp>(operation)) {
      auto resolved = resolveArrayPort(&operation);
      if (failed(resolved) || failed(verifyConservativeInstanceArray(&operation)))
        return failure();
      if (!references.emplace(PortKey{resolved->instance, resolved->port}, &operation).second)
        return operation.emitOpError("NODAL-VERIFY-HIERARCHY-010: duplicate child array reference");
    }
    if (!isConservativeRelation(&operation))
      continue;
    auto relation = resolveConservativeRelation(&operation);
    if (failed(relation))
      return failure();
    if (!occurrences.insert(textAttr(&operation, "source_path")).second ||
        !connectionIds.insert(textAttr(&operation, "connection_id")).second)
      return operation.emitOpError(
          "NODAL-VERIFY-HIERARCHY-010: duplicate endpoint relation occurrence");
    if (relation->left.shape.projection.root == relation->right.shape.projection.root &&
        relation->left.shape.projection.canonicalKey !=
            relation->right.shape.projection.canonicalKey) {
      StaticShapeRegion left, right;
      if (resolveStaticShapeRegion(relation->left.shape.projection, &operation, left) !=
              ShapeRegionProofStatus::Proved ||
          resolveStaticShapeRegion(relation->right.shape.projection, &operation, right) !=
              ShapeRegionProofStatus::Proved ||
          !disjointRegions(left, right))
        return operation.emitOpError("NODAL-SHAPE-043-009: overlapping self-relation has "
                                     "unsupported transitive alias closure");
    }
    for (const ConservativeEndpoint *endpoint : {&relation->left, &relation->right}) {
      if (!endpoint->port || !llvm::isa<TerminalArrayOp>(endpoint->port))
        continue;
      const auto &projection = endpoint->shape.projection;
      auto &entry = coverage[{endpoint->instance, endpoint->port}];
      if (entry.root && entry.root != projection.root)
        return operation.emitOpError(
            "NODAL-VERIFY-HIERARCHY-010: child port has multiple physical roots");
      entry.root = projection.root;
      if (entry.whole || (projection.wholeRoot && !entry.regions.empty()) ||
          !entry.keys.insert(projection.canonicalKey).second)
        return operation.emitOpError(
            "NODAL-VERIFY-HIERARCHY-010: child array region is multiply covered");
      StaticShapeRegion region;
      auto status = resolveStaticShapeRegion(projection, &operation, region);
      if (status != ShapeRegionProofStatus::Proved)
        return operation.emitOpError("NODAL-SHAPE-043-009: ")
               << (status == ShapeRegionProofStatus::ResourceLimit
                       ? "child region proof resource limit exceeded"
                       : "child endpoint has no exact supported region certificate");
      entry.whole = projection.wholeRoot;
      entry.regions.push_back(std::move(region));
    }
  }
  for (Operation &operation : *body) {
    if (!llvm::isa<InstanceOp>(operation))
      continue;
    Operation *definition = resolveInstanceModuleDefinition(&operation);
    Block *childBody = moduleBody(definition);
    if (!childBody)
      continue; // Existing hierarchy closure owns unknown module targets.
    for (Operation &port : *childBody) {
      if (!llvm::isa<TerminalArrayOp>(port))
        continue;
      auto found = coverage.find({&operation, &port});
      if (found == coverage.end() || found->second.regions.empty())
        return operation.emitOpError(
            "NODAL-VERIFY-HIERARCHY-012: every child array port requires exact complete coverage");
      auto status =
          verifyStaticShapeRegionPartition(found->second.root, found->second.regions, &operation);
      if (status != ShapeRegionProofStatus::Proved)
        return operation.emitOpError("NODAL-SHAPE-043-009: ")
               << (status == ShapeRegionProofStatus::ResourceLimit
                       ? "child coverage proof resource limit exceeded"
                       : "child array regions have missing, overlapping or unproved coverage");
    }
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
  llvm::SmallVector<DigitalInterfaceEndpoint> interfaceEndpoints;
  llvm::SmallVector<DigitalInterfaceAccess> interfaceAccesses;
  llvm::DenseMap<Value, const DigitalInterfaceAccess *> interfaceValues;
  if (llvm::any_of(*body, [](Operation &operation) {
        return llvm::isa<MemberAccessOp>(operation);
      })) {
    if (failed(collectDigitalInterfaceEndpoints(module, interfaceEndpoints, &interfaceAccesses)))
      return failure();
    for (const auto &access : interfaceAccesses)
      interfaceValues.try_emplace(access.value, &access);
  }
  llvm::SmallVector<Operation *, 8> connections;
  llvm::SmallVector<Operation *, 8> allScalarConnections;
  llvm::SmallVector<Value, 16> endpoints;
  llvm::DenseMap<Value, unsigned> endpointIndex;
  std::map<std::pair<Operation *, Operation *>, Value> childPorts;
  llvm::StringSet<> connectionIds, connectionPaths, instanceNames, nodeNames;
  auto addEndpoint = [&](Value value) {
    const auto *access = interfaceValues.lookup(value);
    Value canonical = access ? access->canonicalValue : value;
    auto [entry, inserted] = endpointIndex.try_emplace(canonical, endpoints.size());
    if (inserted)
      endpoints.push_back(canonical);
    unsigned index = entry->second;
    endpointIndex.try_emplace(value, index);
    return index;
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
    } else if (llvm::isa<NodeArrayOp>(operation)) {
      if (failed(llvm::cast<NodeArrayOp>(operation).verify()))
        return failure();
      if (!nodeNames.insert(textAttr(&operation, "name")).second)
        return reject(&operation, "generated local node names must be unique in their scope");
      result.nodeArrays.push_back(&operation);
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
    } else if (isConservativeRelation(&operation)) {
      auto relation = resolveConservativeRelation(&operation);
      if (failed(relation))
        return failure();
      if (!connectionIds.insert(textAttr(&operation, "connection_id")).second ||
          !connectionPaths.insert(textAttr(&operation, "source_path")).second)
        return reject(&operation, "duplicate endpoint relation occurrence");
      result.relations.push_back(std::move(*relation));
      if (llvm::isa<ConnectOp>(operation))
        allScalarConnections.push_back(&operation);
    } else if (llvm::isa<ConnectOp>(operation)) {
      if (failed(llvm::cast<ConnectOp>(operation).verify()) ||
          !connectionIds.insert(textAttr(&operation, "connection_id")).second ||
          !connectionPaths.insert(textAttr(&operation, "source_path")).second)
        return reject(&operation,
                      "connection occurrence must be unique and owned by its exact scope");
      connections.push_back(&operation);
      allScalarConnections.push_back(&operation);
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
        } else if (llvm::isa<MemberAccessOp>(endpoint)) {
          const auto *access = interfaceValues.lookup(value);
          if (!access || (access->identity.selection &&
                         failed(resolveConservativeGeneratedSelection(access->projection))) ||
              access->leaf.protocol.getValue() != "conservative" ||
              access->leaf.access.getValue() != "connect" ||
              !llvm::isa<TerminalType>(value.getType()) || endpoint->getParentOp() != scope ||
              access->projection->getParentOp() != scope)
            return reject(&operation, "Interface connection requires an exact conservative capability");
        } else if (llvm::isa<NodeOp>(endpoint)) {
          if (!structuralBody(endpoint->getParentOp()))
            return reject(&operation, "local node must have a structural declaration owner");
        } else {
          return reject(&operation, "unsupported structural connection endpoint");
        }
        addEndpoint(value);
      }
    }
  }
  const bool topology = !result.instances.empty() || !result.nodeArrays.empty() ||
                        !connections.empty() || !result.relations.empty();
  if (generated && topology && failed(resolveGeneratedDomain(scope)))
    return failure();
  if (failed(verifyConservativeArrayBindings(scope)))
    return failure();
  UnionFind groups(endpoints.size());
  for (Operation *connection : connections)
    for (Value value : connection->getOperands().drop_front())
      groups.unite(endpointIndex.lookup(connection->getOperand(0)), endpointIndex.lookup(value));
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
        if (found == childPorts.end())
          return reject(instance, "every generated child scalar port requires an actual connection",
                        "NODAL-VERIFY-HIERARCHY-012");
        unsigned occurrences = 0;
        for (Operation *connection : allScalarConnections)
          occurrences += llvm::is_contained(connection->getOperands(), found->second);
        if (!occurrences)
          return reject(instance, "every generated child scalar port requires an actual connection",
                        "NODAL-VERIFY-HIERARCHY-012");
        if (occurrences != 1) {
          // Reusing one checked scalar incidence across junction edges does not
          // create another child port binding. All such edges must belong to
          // the same canonical scalar group; array relations retain their
          // separate one-binding rule and cannot borrow this scalar proof.
          auto index = endpointIndex.find(found->second);
          bool oneGroup = index != endpointIndex.end();
          for (Operation *connection : allScalarConnections) {
            if (!llvm::is_contained(connection->getOperands(), found->second))
              continue;
            if (!llvm::is_contained(connections, connection)) {
              oneGroup = false;
              break;
            }
            for (Value operand : connection->getOperands()) {
              auto other = endpointIndex.find(operand);
              oneGroup &= other != endpointIndex.end() && index != endpointIndex.end() &&
                  groups.find(other->second) == groups.find(index->second);
            }
          }
          if (!oneGroup)
            return reject(instance, "generated child scalar port has duplicate connection bindings",
                          "NODAL-VERIFY-HIERARCHY-010");
        }
      }
    }
  }
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
  if (llvm::isa_and_nonnull<MemberAccessOp>(definition)) {
    auto endpoint = resolveConservativeEndpoint(value, use);
    if (succeeded(endpoint) && llvm::isa<MemberAccessOp>(endpoint->shape.declaration) &&
        !endpoint->instance && endpoint->port)
      return ConservativePortIncidence{definition, value, std::nullopt};
    return failure();
  }
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
  if (failed(collectArraySelections(module, selections)))
    return failure();
  Block *body = structuralBody(module);
  if (!body || !hasArrayRelations(module))
    return success();
  for (unsigned i = 0; i < selections.size(); ++i)
    for (unsigned j = 0; j < i; ++j) {
      auto relation = compareConservativeEndpoints(selections[j].operation->getResult(0),
                                                   selections[i].operation->getResult(0),
                                                   selections[i].operation);
      if (relation != ConservativeAlias::Equal && relation != ConservativeAlias::Distinct &&
          relation != ConservativeAlias::Inert)
        return selections[i].operation->emitOpError("NODAL-SHAPE-043-009: ")
               << (relation == ConservativeAlias::ResourceLimit
                       ? "scalar endpoint relation proof resource limit exceeded"
                       : "scalar endpoint relation has unproved physical alias identity");
    }
  return success();
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
