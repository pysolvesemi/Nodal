#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"

#include "mlir/IR/Builders.h"
#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/OwningOpRef.h"
#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/NatureDiscipline.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/STLExtras.h"
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
    if (!isNamed(&operation, "nodal.terminal") && !isNamed(&operation, "nodal.node"))
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

    const llvm::StringRef endpointName = textAttr(&operation, "name");
    if (!isCanonicalText(endpointName) || !endpointNames.insert(endpointName).second)
      return operation.emitOpError(
          "NODAL-CONNECTIVITY-PROVENANCE-001: endpoint names must be canonical and unique "
          "within a physical component");

    const bool terminal = isNamed(&operation, "nodal.terminal");
    if (terminal) {
      llvm::StringRef direction = textAttr(&operation, "direction");
      if (!oneOf(direction, {"input", "output", "inout"}))
        return operation.emitOpError(
            "NODAL-TERMINAL-DIRECTION-001: conservative terminal direction must be input, "
            "output, or inout");
      llvm::StringRef orientation = textAttr(&operation, "flow_orientation");
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
                                     textAttr(&operation, "flow_orientation").str()});
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
      if (!isNamed(definition, "nodal.terminal") || !connectionSetContains(connectionSet, term))
        return emitOpError(
            "NODAL-CONNECTION-FLOW-001: terminal flow terms must be boundary members of the "
            "connection set");
      llvm::StringRef orientation = textAttr(definition, "flow_orientation");
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

LogicalResult nodal::materializeConservativeConnectivity(mlir::ModuleOp module) {
  OwningOpRef<mlir::ModuleOp> working(llvm::cast<mlir::ModuleOp>(module->clone()));
  if (failed(materializeInPlace(*working)))
    return failure();
  commitModule(module, *working);
  return success();
}
