#include "nodal/Dialect/Nodal/DigitalInterfaceModel.h"

#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"
#include "nodal/Dialect/Nodal/DigitalRegionModel.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/NatureDiscipline.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"
#include "nodal/Dialect/Nodal/StructuralIfModel.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringSet.h"

#include <limits>
#include <map>
#include <memory>
#include <optional>
#include <set>
#include <string>
#include <tuple>

using namespace mlir;
namespace nodal {
namespace {
constexpr llvm::StringLiteral versionName = "interface_abi_version";
DictionaryAttr metadata(Operation *operation) {
  return operation->getAttrOfType<DictionaryAttr>("metadata");
}
StringRef text(DictionaryAttr dictionary, StringRef name) {
  auto value = dictionary ? dictionary.getAs<StringAttr>(name) : StringAttr();
  return value ? value.getValue() : StringRef();
}
StringRef text(Operation *operation, StringRef name) {
  auto value = operation->getAttrOfType<StringAttr>(name);
  return value ? value.getValue() : StringRef();
}
StringRef symbol(Operation *operation) { return text(operation, SymbolTable::getSymbolAttrName()); }
bool named(Operation *operation, StringRef name) {
  return operation && operation->getName().getStringRef() == name;
}
bool newOperation(Operation *operation) {
  return named(operation, "nodal.interface_connect") || named(operation, "nodal.interface_drive") ||
         named(operation, "nodal.interface_observe") || named(operation, "nodal.net_release") ||
         named(operation, "nodal.net_drive_level") || named(operation, "nodal.net_pass_through") ||
         named(operation, "nodal.net_split") || named(operation, "nodal.interface_boundary") ||
         named(operation, "nodal.net_boundary");
}
bool oldOperation(Operation *operation) {
  return llvm::isa<InterfaceOp, InterfaceRoleOp, InterfaceMemberOp, InterfaceInstanceOp,
                   MemberAccessOp, InterfaceAbiOp, ResolvedNetOp, NetReadOp, NetDriverOp,
                   NetDriveOp>(operation);
}
bool marked(Operation *operation) {
  auto dictionary = metadata(operation);
  return dictionary && dictionary.get(versionName);
}
LogicalResult reject(Operation *operation, StringRef message) {
  return operation->emitOpError("NODAL-INTERFACE-054-001: ") << message;
}
bool identity(StringRef value) {
  return !value.empty() && value == value.trim() &&
         value.size() <= digitalTypeMaximumWidthTokenBytes;
}
bool boolean(Type type) {
  auto bits = llvm::dyn_cast<BitsType>(type);
  return bits && bits.getWidth() == 1;
}
bool driveMode(StringRef mode) {
  return mode == "push_pull" || mode == "open_drain" || mode == "open_source" ||
         mode == "read_only" || mode == "pass_through";
}
bool bitVector(Type type) {
  return llvm::isa<BitsType, UIntType, SIntType, ParametricIntegerType>(type);
}
bool analog(StringRef protocol) { return protocol == "conservative" || protocol == "signal_flow"; }
LogicalResult version(Operation *operation) {
  auto dictionary = metadata(operation);
  auto value = dictionary ? dictionary.getAs<IntegerAttr>(versionName) : IntegerAttr();
  if (!value || !value.getType().isSignlessInteger(64) || value.getInt() != 1)
    return reject(operation, "fully typed connectivity requires interface_abi_version = 1 : i64");
  return success();
}
LogicalResult arity(Operation *operation, unsigned operands, unsigned results,
                    unsigned regions = 0) {
  if (operation->getNumOperands() != operands || operation->getNumResults() != results ||
      operation->getNumRegions() != regions || operation->getNumSuccessors())
    return reject(operation, "malformed typed connectivity operand/result/region arity");
  return success();
}
// Symbol capabilities are lexical. In particular a structural arm cannot use a
// module/ancestor endpoint by name instead of retaining its actual family capture.
Operation *localInterfaceSymbol(Operation *use, FlatSymbolRefAttr reference) {
  if (!use || !reference || !use->getBlock())
    return nullptr;
  Operation *found = nullptr;
  for (Operation &candidate : *use->getBlock()) {
    if (symbol(&candidate) != reference.getValue())
      continue;
    if (found)
      return nullptr;
    found = &candidate;
  }
  return found;
}
Operation *interfaceModule(Operation *operation) {
  return operation ? operation->getParentOfType<nodal::ModuleOp>() : nullptr;
}
bool lexicalInterfaceOperation(Operation *operation) {
  if (!operation)
    return false;
  Operation *owner = operation->getParentOp();
  if (llvm::isa_and_nonnull<nodal::ModuleOp>(owner))
    return true;
  bool definition = llvm::isa<InterfaceOp>(operation);
  bool declaration = llvm::isa<InterfaceInstanceOp, InterfaceAbiOp>(operation);
  bool projection = llvm::isa<InterfaceBoundaryOp, MemberAccessOp>(operation);
  bool effect = named(operation, "nodal.interface_connect") ||
                named(operation, "nodal.interface_drive") ||
                named(operation, "nodal.interface_observe");
  if ((!definition && !declaration && !projection && !effect) ||
      (declaration && !llvm::isa_and_nonnull<GenerateOp>(owner)))
    return false;
  unsigned depth = 0;
  for (Operation *scope = owner; scope; scope = scope->getParentOp()) {
    if (llvm::isa<nodal::ModuleOp>(scope))
      return true;
    if (++depth > digitalTypeMaximumDepth)
      return false;
    if (llvm::isa<GenerateOp>(scope)) {
      if (failed(resolveGeneratedDomain(scope)))
        return false;
    } else if (llvm::isa<StructuralIfOp>(scope)) {
      if (failed(inspectStructuralIfHeader(scope)))
        return false;
    } else {
      return false;
    }
  }
  return false;
}
// Query-local indexes are derived from unchanged authored blocks. They avoid
// rescanning a Module for every member/endpoint while charging every operation
// and ancestry step to the existing finite model budget. They own no semantics.
struct DefinitionScopeIndex {
  llvm::StringMap<Operation *> symbols, semantics;
};
using DefinitionLookupCache = std::map<Block *, DefinitionScopeIndex>;
FailureOr<DefinitionScopeIndex *> definitionScopeIndex(Block *block, unsigned &work,
                                                       DefinitionLookupCache &cache) {
  auto known = cache.find(block);
  if (known != cache.end())
    return &known->second;
  DefinitionScopeIndex index;
  auto insert = [](llvm::StringMap<Operation *> &entries, StringRef key, Operation *op) {
    if (key.empty())
      return;
    auto [entry, inserted] = entries.try_emplace(key, op);
    if (!inserted)
      entry->second = nullptr; // A duplicate is an error, never an outer fallback.
  };
  for (Operation &candidate : *block) {
    if (++work > digitalTypeMaximumNodes)
      return failure();
    insert(index.symbols, symbol(&candidate), &candidate);
    if (llvm::isa<InterfaceOp>(candidate))
      insert(index.semantics, text(metadata(&candidate), "semantic_identity"), &candidate);
  }
  return &cache.emplace(block, std::move(index)).first->second;
}
// One lexical definition resolver serves the canonical inventory and shallow
// leaf consumers. Module declarations retain order-independent lookup; regional
// declarations must precede the exact actual ancestor anchor.
Operation *lexicalInterfaceDefinition(Operation *use, StringRef name, bool semantic, unsigned &work,
                                      DefinitionLookupCache *shared = nullptr) {
  if (!use || !identity(name))
    return nullptr;
  DefinitionLookupCache local;
  auto &cache = shared ? *shared : local;
  Operation *anchor = use;
  for (unsigned depth = 0; anchor && depth < digitalTypeMaximumDepth; ++depth) {
    if (++work > digitalTypeMaximumNodes)
      return nullptr;
    Block *block = anchor->getBlock();
    Operation *scope = block ? block->getParentOp() : nullptr;
    if (!block || !llvm::isa_and_nonnull<nodal::ModuleOp, GenerateOp, StructuralIfOp>(scope))
      return nullptr;
    auto index = definitionScopeIndex(block, work, cache);
    if (failed(index))
      return nullptr;
    auto &entries = semantic ? (*index)->semantics : (*index)->symbols;
    if (auto entry = entries.find(name); entry != entries.end()) {
      Operation *found = entry->second;
      if (!llvm::isa_and_nonnull<InterfaceOp>(found) || !lexicalInterfaceOperation(found) ||
          (!llvm::isa<nodal::ModuleOp>(scope) && !found->isBeforeInBlock(anchor)))
        return nullptr;
      return found;
    }
    if (llvm::isa<nodal::ModuleOp>(scope))
      return nullptr;
    anchor = scope;
  }
  return nullptr;
}

struct Member {
  Operation *operation = nullptr;
  Type type;
  StringRef protocol;
  DictionaryAttr analogMetadata, analogReferences;
};
struct Entry {
  DictionaryAttr dictionary;
  StringRef access;
  StringRef leafRole;
  ArrayAttr nestedRoles, nestedInterfaces;
};
struct Role {
  Operation *operation = nullptr;
  llvm::StringMap<Entry> entries;
};
struct Definition {
  Operation *operation = nullptr;
  StringRef semanticIdentity;
  llvm::StringMap<Member> members;
  llvm::StringMap<Role> roles;
};
struct Instance {
  Operation *operation = nullptr;
  Definition *definition = nullptr;
  Role *role = nullptr;
  FlatSymbolRefAttr domain;
  std::string dimensions;
  Operation *childInstance = nullptr;
  Operation *childEndpoint = nullptr;
  llvm::StringMap<Type> actualTypes;
  Operation *selection = nullptr;
};
DigitalInterfaceLeaf leafFacts(const Member &member, const Entry &entry) {
  DigitalInterfaceLeaf leaf;
  leaf.declaration = member.operation;
  leaf.path = member.operation->getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName());
  leaf.type = member.type;
  leaf.protocol = member.operation->getAttrOfType<StringAttr>("protocol");
  leaf.role = entry.dictionary.getAs<StringAttr>("leaf_role");
  leaf.access = entry.dictionary.getAs<StringAttr>("access");
  leaf.nestedRoles = entry.nestedRoles;
  leaf.nestedInterfaces = entry.nestedInterfaces;
  leaf.analogMetadata = member.analogMetadata;
  leaf.analogReferences = member.analogReferences;
  return leaf;
}

// Fact-only admission: callers authenticate the declaration/role first. In
// particular this must never call the full Interface or connectivity models.
LogicalResult fixedConservativeArrayProfile(const DigitalInterfaceEndpoint &endpoint,
                                           unsigned &remaining) {
  auto charge = [&]() { if (!remaining) return false; --remaining; return true; };
  auto invalid = [&]() {
    if (!endpoint.operation) return failure();
    return reject(endpoint.operation,
                  "conservative Interface array requires direct fixed rank-one Electrical connect leaves");
  };
  if (!charge() || !endpoint.operation || !endpoint.declaration || !endpoint.domainOwner ||
      !endpoint.declarationOwner || !endpoint.selectedRole || !endpoint.domain ||
      endpoint.selection || endpoint.operation->getParentOp() != endpoint.domainOwner ||
      endpoint.declaration->getParentOp() != endpoint.declarationOwner ||
      !llvm::isa<ModuleOp>(endpoint.domainOwner) ||
      !llvm::isa<ModuleOp>(endpoint.declarationOwner) ||
      endpoint.domainOwner->hasAttr("external_name") ||
      endpoint.declarationOwner->hasAttr("external_name") ||
      (endpoint.instance && endpoint.instance->getParentOp() != endpoint.domainOwner) ||
      !endpoint.dimensions || endpoint.dimensions.size() != 1 || endpoint.leaves.empty())
    return invalid();
  auto formalDimensions = metadata(endpoint.declaration).getAs<ArrayAttr>("dimensions");
  auto actualDimensions = metadata(endpoint.operation).getAs<ArrayAttr>("dimensions");
  if (!formalDimensions || formalDimensions.size() != 1 ||
      !actualDimensions || actualDimensions.size() != 1)
    return invalid();
  int64_t extent = 0;
  for (auto [owner, dimensions] :
       {std::make_pair(endpoint.domainOwner, actualDimensions),
        std::make_pair(endpoint.declarationOwner, formalDimensions)}) {
    if (!charge()) return invalid();
    auto token = llvm::dyn_cast<StringAttr>(dimensions[0]);
    if (!token) return invalid();
    auto axis = resolveStaticShapeDimension(owner, token.getValue(), "shape", remaining);
    if (failed(axis) || axis->parameter || axis->expression || axis->literal <= 0 ||
        (extent && extent != axis->literal))
      return invalid();
    extent = axis->literal;
  }
  for (const auto &leaf : endpoint.leaves) {
    if (!charge()) return invalid();
    auto terminal = llvm::dyn_cast<TerminalType>(leaf.type);
    if (!leaf.protocol || leaf.protocol.getValue() != "conservative" || !terminal ||
        terminal.getDiscipline() != "electrical" || !leaf.access ||
        leaf.access.getValue() != "connect")
      return invalid();
  }
  return success();
}

// Only the independently admitted declaration-only external endpoint has no
// clock-domain constraint. An ordinary missing domain is never a wildcard.
static bool unconstrainedExternal(const Instance &instance) {
  Operation *endpoint = instance.childEndpoint ? instance.childEndpoint : instance.operation;
  Operation *owner = endpoint ? endpoint->getParentOp() : nullptr;
  return !instance.domain && !instance.selection && llvm::isa_and_nonnull<ModuleOp>(owner) &&
         owner->hasAttr("external_name");
}
struct Access {
  Instance *instance = nullptr;
  Member *member = nullptr;
  Entry *entry = nullptr;
  StringRef path;
};
struct Net {
  Value value;
  StringRef profile;
  StringRef mode;
};

class Model {
public:
  explicit Model(Operation *module, unsigned initialWork = 0) : module(module), work(initialWork) {}
  unsigned consumedWork() const { return work; }
  FailureOr<SmallVector<DigitalInterfaceTemplateDriver, 4>>
  templateDrivers(ArrayRef<std::pair<Value, Operation *>> seeds);
  LogicalResult verify();
  LogicalResult endpoints(SmallVectorImpl<DigitalInterfaceEndpoint> &result);
  LogicalResult memberAccesses(SmallVectorImpl<DigitalInterfaceAccess> &result);
  LogicalResult topology(DigitalResolvedTopology &result);
  LogicalResult dependencies(SmallVectorImpl<DigitalInterfaceValueDependencies> &result,
                             SmallVectorImpl<DigitalInterfaceObservationGroup> *observations);

private:
  LogicalResult definition(Operation *operation);
  LogicalResult nestedDefinitions(Definition &definition);
  LogicalResult instance(Operation *operation);
  LogicalResult boundary(Operation *operation);
  LogicalResult access(Operation *operation);
  LogicalResult abi(Operation *operation);
  LogicalResult net(Operation *operation);
  LogicalResult netBoundary(Operation *operation);
  void addNet(Value value, StringRef profile, StringRef mode);
  LogicalResult effect(Operation *operation);
  LogicalResult resolvedEffect(Operation *operation);
  LogicalResult ownership(Operation *operation);
  LogicalResult localValue(Value value, Operation *use);
  LogicalResult protocol(Role &role, Definition &definition);
  LogicalResult validateEntry(Operation *operation, Entry &entry, StringRef path,
                              const Member &member);
  FailureOr<unsigned> netIndex(Value value, Operation *operation);
  unsigned representative(unsigned index);
  void unite(unsigned a, unsigned b);
  llvm::DenseMap<unsigned, Value> resolvedComponentValues();
  using PhysicalMember = std::tuple<Operation *, Operation *, std::string>;
  struct OrdinaryDriver {
    Value target, source;
    Operation *operation = nullptr;
    Operation *projection = nullptr;
  };
  PhysicalMember physicalMember(const Access &access) const;
  LogicalResult addOrdinaryDriver(const Access &access, Value target, Value source,
                                  Operation *operation);
  bool equivalentBoundaryTypes(Type left, Operation *leftInstance, Type right,
                               Operation *rightInstance, Operation *leftSelection = nullptr,
                               Operation *rightSelection = nullptr);

  Operation *module;
  SmallVector<Operation *> lexicalOperations;
  unsigned work = 0;
  DefinitionLookupCache definitionLookup;
  std::map<Operation *, std::unique_ptr<Definition>> definitions;
  using SemanticKey = std::pair<Block *, std::string>;
  std::map<SemanticKey, Definition *> semanticDefinitions;
  std::map<SemanticKey, Operation *> semanticDefinitionInstances, semanticDefinitionSelections;
  std::map<SemanticKey, llvm::StringMap<const Role *>> semanticRoles;
  std::map<Operation *, Instance> instances;
  std::map<Operation *, std::unique_ptr<Model>> childDefinitions;
  std::set<std::pair<Operation *, Operation *>> boundaryIdentities;
  std::map<std::tuple<Operation *, Operation *, Block *>, SmallVector<GeneratedSelectionProof, 2>>
      selectedBoundaryIdentities;
  std::set<std::pair<Operation *, Operation *>> comparedDefinitions;
  using ContextualType = std::pair<Type, std::pair<Operation *, Operation *>>;
  llvm::DenseMap<std::pair<ContextualType, ContextualType>, bool> boundaryTypeEqualities;
  llvm::DenseMap<Value, Access> accesses;
  llvm::DenseMap<Value, unsigned> netIndices;
  SmallVector<Net> nets;
  std::map<Operation *, llvm::StringMap<Operation *>> childNetDeclarations;
  std::map<Operation *, std::pair<Operation *, Operation *>> netBoundaries;
  std::set<std::pair<Operation *, Operation *>> netBoundaryIdentities;
  llvm::DenseMap<unsigned, FlatSymbolRefAttr> componentDomains;
  SmallVector<unsigned> parents, ranks;
  llvm::StringMap<Operation *> driverIdentities;
  llvm::DenseSet<Value> usedDrivers;
  llvm::DenseSet<Operation *> seenAbi;
  std::map<PhysicalMember, SmallVector<OrdinaryDriver, 2>> ordinaryDrivers;
  SmallVector<std::pair<unsigned, Operation *>> conditionalDrivers;
  SmallVector<std::pair<unsigned, Operation *>> splits;
};

LogicalResult Model::ownership(Operation *operation) {
  if (failed(version(operation)))
    return failure();
  if (++work > digitalTypeMaximumNodes)
    return reject(operation, "typed connectivity exceeds its finite represented-node limit");
  if (interfaceModule(operation) != module || !lexicalInterfaceOperation(operation))
    return reject(operation,
                  "typed connectivity requires exact Module/Generate structural ownership");
  return success();
}
LogicalResult Model::localValue(Value value, Operation *use) {
  if (!value || interfaceModule(use) != module || failed(verifyDigitalValueScope(value, use)))
    return reject(use, "connectivity operands must retain exact owned SSA/capture provenance");
  return success();
}
bool Model::equivalentBoundaryTypes(Type left, Operation *leftInstance, Type right,
                                    Operation *rightInstance, Operation *leftSelection,
                                    Operation *rightSelection) {
  // This immutable-model query owns one parent context. Both exact occurrence
  // contexts remain part of the key even when the raw types are identical.
  // Callers charge every represented member to the existing structural budget,
  // so the memo cannot grow beyond that bounded traversal.
  // Outer Interface axes qualify a family of resolved components. Compare
  // their digital payload shapes without treating the capability itself as
  // storage, while retaining the exact mode and every ordered outer axis.
  auto payload = [](Type type) -> std::pair<Type, StringRef> {
    if (auto resolved = llvm::dyn_cast<ResolvedType>(type))
      return {resolved.getElementType(), resolved.getDriveMode()};
    if (auto shaped = llvm::dyn_cast<nodal::ShapedType>(type))
      if (auto resolved = llvm::dyn_cast<ResolvedType>(shaped.getElementType()))
        return {nodal::ShapedType::get(type.getContext(), shaped.getDimensions(),
                                       resolved.getElementType()),
                resolved.getDriveMode()};
    return {type, {}};
  };
  auto [leftPayload, leftMode] = payload(left);
  auto [rightPayload, rightMode] = payload(right);
  if (leftMode != rightMode)
    return false;
  left = leftPayload;
  right = rightPayload;
  const auto key = std::make_pair(ContextualType{left, {leftInstance, leftSelection}},
                                  ContextualType{right, {rightInstance, rightSelection}});
  auto found = boundaryTypeEqualities.find(key);
  if (found != boundaryTypeEqualities.end())
    return found->second;
  bool equal = leftSelection || rightSelection
                   ? equivalentSelectedDigitalStorageTypesAcross(
                         leftSelection, leftInstance, left, rightSelection, rightInstance, right)
                   : equivalentDigitalStorageTypesAcrossInstances(module, left, leftInstance, right,
                                                                  rightInstance);
  boundaryTypeEqualities.try_emplace(key, equal);
  return equal;
}
LogicalResult Model::validateEntry(Operation *operation, Entry &entry, StringRef path,
                                   const Member &member) {
  entry.access = text(entry.dictionary, "access");
  entry.leafRole = text(entry.dictionary, "leaf_role");
  entry.nestedRoles = entry.dictionary.getAs<ArrayAttr>("nested_roles");
  entry.nestedInterfaces = entry.dictionary.getAs<ArrayAttr>("nested_interfaces");
  if (!identity(entry.leafRole) || !entry.nestedRoles || !entry.nestedInterfaces ||
      entry.nestedRoles.size() != entry.nestedInterfaces.size() ||
      entry.nestedRoles.size() > digitalTypeMaximumDepth)
    return reject(operation,
                  "role leaf requires exact selected-role and bounded nested identity chains");
  StringRef previous;
  for (unsigned i = 0; i < entry.nestedRoles.size(); ++i) {
    auto role = llvm::dyn_cast<DictionaryAttr>(entry.nestedRoles[i]);
    auto interface = llvm::dyn_cast<DictionaryAttr>(entry.nestedInterfaces[i]);
    StringRef prefix = text(role, "path");
    if (!identity(prefix) || prefix != text(interface, "path") ||
        !path.starts_with((prefix + ".").str()) ||
        (!previous.empty() && !prefix.starts_with((previous + ".").str())) ||
        !identity(text(role, "role")) || !identity(text(interface, "definition")))
      return reject(operation, "nested role/interface chain is not an ordered exact member prefix");
    previous = prefix;
  }
  StringRef permission = entry.access;
  bool valid = false;
  if (member.protocol == "plain" || member.protocol == "valid" || member.protocol == "stream" ||
      member.protocol == "signal_flow")
    valid = permission == "in" || permission == "out" || permission == "observe";
  else if (member.protocol == "resolved")
    valid = permission == "read" || permission == "drive" || permission == "connect" ||
            permission == "observe";
  else if (member.protocol == "conservative")
    valid = permission == "connect" || permission == "sense" || permission == "contribute";
  if (!valid)
    return reject(operation, "role access is not legal for the actual member protocol");
  if (symbol(operation) == "monitor" && permission != "observe" && permission != "sense")
    return reject(operation, "monitor role must be read-only for every represented leaf");
  return success();
}

LogicalResult Model::protocol(Role &role, Definition &definition) {
  for (auto &item : definition.members) {
    const Member &member = item.getValue();
    if (member.protocol != "valid" && member.protocol != "stream")
      continue;
    auto [prefix, suffix] = item.getKey().rsplit('.');
    if (prefix.empty() || (suffix != "payload" && suffix != "valid" &&
                           !(member.protocol == "stream" && suffix == "ready")))
      return reject(member.operation,
                    "protocol leaf requires its exact payload/valid/ready suffix");
    if (suffix != "payload" && !boolean(member.type))
      return reject(member.operation, "protocol valid/ready leaves require canonical Bool");
    auto payload = definition.members.find((prefix + ".payload").str());
    auto valid = definition.members.find((prefix + ".valid").str());
    auto ready = definition.members.find((prefix + ".ready").str());
    if (payload == definition.members.end() || valid == definition.members.end() ||
        (member.protocol == "stream" && ready == definition.members.end()) ||
        (member.protocol == "valid" && ready != definition.members.end()) ||
        payload->second.protocol != member.protocol || valid->second.protocol != member.protocol ||
        (member.protocol == "stream" && ready->second.protocol != member.protocol))
      return reject(member.operation,
                    "protocol declaration must contain a complete exact leaf group");
    Entry &forward = role.entries.find(payload->getKey())->second;
    Entry &current = role.entries.find(item.getKey())->second;
    StringRef expected = forward.access;
    if (suffix == "ready")
      expected = expected == "in" ? "out" : expected == "out" ? "in" : "observe";
    if (current.access != expected || current.leafRole != forward.leafRole ||
        current.nestedRoles != forward.nestedRoles ||
        current.nestedInterfaces != forward.nestedInterfaces)
      return reject(role.operation,
                    "protocol role requires matching forward leaves and complementary ready");
  }
  return success();
}

LogicalResult Model::definition(Operation *operation) {
  if (failed(ownership(operation)) || failed(arity(operation, 0, 0, 1)))
    return failure();
  auto &region = operation->getRegion(0);
  if (!region.hasOneBlock() || region.front().getNumArguments() || !identity(symbol(operation)))
    return reject(operation,
                  "typed Interface requires one argument-free definition block and identity");
  auto index = definitionScopeIndex(operation->getBlock(), work, definitionLookup);
  if (failed(index) || (*index)->symbols.lookup(symbol(operation)) != operation)
    return reject(operation, "Interface declaration requires a unique lexical symbol");
  auto result = std::make_unique<Definition>();
  result->operation = operation;
  result->semanticIdentity = text(metadata(operation), "semantic_identity");
  if (!identity(result->semanticIdentity))
    return reject(operation, "typed Interface requires its authored semantic identity");
  for (Operation &child : region.front()) {
    if (++work > digitalTypeMaximumNodes || failed(version(&child)) || failed(arity(&child, 0, 0)))
      return failure();
    if (!identity(symbol(&child)))
      return reject(&child, "Interface member/role requires a bounded exact symbol");
    if (llvm::isa<InterfaceMemberOp>(child)) {
      auto type = child.getAttrOfType<TypeAttr>("type");
      StringRef kind = text(&child, "protocol");
      if (!type || (kind != "plain" && kind != "valid" && kind != "stream" && kind != "resolved" &&
                    kind != "conservative" && kind != "signal_flow"))
        return reject(&child, "typed member requires an exact type and supported protocol");
      Type value = type.getValue();
      auto analogMetadata = metadata(&child).getAs<DictionaryAttr>("analog");
      auto analogReferences = metadata(&child).getAs<DictionaryAttr>("analog_refs");
      if (!analogMetadata)
        analogMetadata = DictionaryAttr::get(module->getContext());
      if (!analogReferences)
        analogReferences = DictionaryAttr::get(module->getContext());
      if (!analog(kind) && (!analogMetadata.empty() || !analogReferences.empty()))
        return reject(&child, "digital Interface member cannot claim analog identity metadata");
      if (kind == "resolved") {
        auto resolved = llvm::dyn_cast<ResolvedType>(value);
        if (!resolved || !driveMode(resolved.getDriveMode()) ||
            !bitVector(resolved.getElementType()) ||
            failed(inferDigitalStorageMaximumBits(resolved.getElementType(), operation)))
          return reject(&child, "resolved Interface leaf requires a finite digital bit-vector net");
      } else if (kind == "conservative") {
        auto terminal = llvm::dyn_cast<TerminalType>(value);
        auto discipline = analogReferences.getAs<FlatSymbolRefAttr>("discipline");
        auto potential = analogReferences.getAs<FlatSymbolRefAttr>("potential");
        auto flow = analogReferences.getAs<FlatSymbolRefAttr>("flow");
        if (!terminal || !discipline || !potential || !flow || analogReferences.size() != 3 ||
            analogMetadata.size() != 3 || !identity(text(analogMetadata, "discipline")) ||
            !identity(text(analogMetadata, "potential_nature")) ||
            !identity(text(analogMetadata, "flow_nature")) ||
            terminal.getDiscipline() != discipline.getValue())
          return reject(&child, "conservative Interface member requires exact authored metadata "
                                "and canonical references");
        auto declared = resolveDisciplineDeclaration(module, discipline);
        auto actualPotential = resolveNatureDeclaration(module, potential);
        auto actualFlow = resolveNatureDeclaration(module, flow);
        if (failed(declared) || failed(actualPotential) || failed(actualFlow))
          return reject(
              &child,
              "conservative Interface references must resolve through the existing analog owner");
        auto expectedPotential = resolveNatureDeclaration(
            module, (*declared)->getAttrOfType<FlatSymbolRefAttr>("potential"));
        auto expectedFlow =
            resolveNatureDeclaration(module, (*declared)->getAttrOfType<FlatSymbolRefAttr>("flow"));
        if (failed(expectedPotential) || failed(expectedFlow) ||
            *actualPotential != *expectedPotential || *actualFlow != *expectedFlow)
          return reject(
              &child,
              "conservative Interface nature references disagree with its actual discipline");
      } else if (kind == "signal_flow") {
        if (!llvm::isa<Float64Type>(value) || analogMetadata.size() != 1 ||
            !analogReferences.empty() ||
            failed(canonicalizeAnalogSourceDimension(text(analogMetadata, "dimension"))))
          return reject(&child, "signal-flow Interface leaf requires its exact existing real "
                                "carrier and source dimension");
      } else if (isDigitalControlBoundaryType(value)) {
        if (kind != "plain" || failed(inferDigitalControlMaximumElements(value, module)))
          return reject(&child,
                        "Clock/Reset capability requires an exact finite plain boundary member");
      } else if (!analogMetadata.empty() || !analogReferences.empty() ||
                 failed(inferDigitalStorageMaximumBits(value, operation))) {
        return reject(&child, "digital Interface leaf requires an owned finite exact storage type");
      }
      if (!result->members
               .try_emplace(symbol(&child),
                            Member{&child, value, kind, analogMetadata, analogReferences})
               .second)
        return reject(&child, "duplicate Interface member identity");
    } else if (llvm::isa<InterfaceRoleOp>(child)) {
      if (text(&child, "kind") != "explicit" ||
          !result->roles.try_emplace(symbol(&child), Role{&child, {}}).second)
        return reject(&child, "typed role requires unique explicit role identity");
    } else {
      return reject(&child, "typed Interface contains only actual member and role definitions");
    }
  }
  if (result->roles.empty())
    return reject(operation, "typed Interface requires at least one actual complete role map");
  for (auto &item : result->roles) {
    Role &role = item.getValue();
    auto entries = metadata(role.operation).getAs<ArrayAttr>("access");
    if (!entries || entries.size() != result->members.size())
      return reject(role.operation, "role map must describe each exact Interface leaf once");
    for (Attribute attribute : entries) {
      if (++work > digitalTypeMaximumNodes)
        return reject(role.operation, "role metadata exceeds finite represented-node limit");
      auto dictionary = llvm::dyn_cast<DictionaryAttr>(attribute);
      StringRef path = text(dictionary, "path");
      auto member = result->members.find(path);
      if (!dictionary || member == result->members.end())
        return reject(role.operation, "role map references an unknown exact Interface member");
      Entry entry{dictionary, {}, {}, {}, {}};
      if (failed(validateEntry(role.operation, entry, path, member->second)))
        return failure();
      if (!role.entries.try_emplace(path, entry).second)
        return reject(role.operation, "role map repeats an Interface leaf");
    }
    if (failed(protocol(role, *result)))
      return failure();
  }
  for (auto &item : result->members) {
    auto roles = item.second.operation->getAttrOfType<ArrayAttr>("roles");
    llvm::StringSet<> seen;
    if (!roles || roles.size() != result->roles.size())
      return reject(item.second.operation,
                    "member role list must match the actual definition maps");
    for (Attribute attribute : roles) {
      auto name = llvm::dyn_cast<StringAttr>(attribute);
      if (!name || !result->roles.contains(name.getValue()) || !seen.insert(name.getValue()).second)
        return reject(item.second.operation,
                      "member role list contains an unknown or duplicate role");
    }
    ArrayAttr chain;
    for (auto &role : result->roles) {
      Entry &entry = role.second.entries.find(item.getKey())->second;
      if (chain && chain != entry.nestedInterfaces)
        return reject(role.second.operation, "roles disagree on nested Interface member identity");
      chain = entry.nestedInterfaces;
      if (entry.nestedRoles.empty() && entry.leafRole != role.getKey())
        return reject(role.second.operation, "root leaf role must be the actual selected role");
      if (!entry.nestedRoles.empty() &&
          entry.leafRole !=
              text(llvm::cast<DictionaryAttr>(entry.nestedRoles[entry.nestedRoles.size() - 1]),
                   "role"))
        return reject(role.second.operation, "nested leaf role must match the final resolved role");
    }
  }
  SemanticKey key{operation->getBlock(), result->semanticIdentity.str()};
  if (!semanticDefinitions.try_emplace(key, result.get()).second)
    return reject(operation, "one lexical scope cannot redeclare an Interface semantic identity");
  auto &canonicalRoles = semanticRoles[key];
  for (const auto &role : result->roles)
    canonicalRoles.try_emplace(role.getKey(), &role.second);
  definitions.emplace(operation, std::move(result));
  return success();
}

LogicalResult Model::nestedDefinitions(Definition &definition) {
  for (auto &roleItem : definition.roles) {
    Role &role = roleItem.second;
    llvm::StringMap<unsigned> prefixCounts, prefixSizes;
    llvm::StringMap<std::pair<StringRef, StringRef>> prefixBindings;
    for (auto &item : role.entries) {
      Entry &entry = item.second;
      for (unsigned i = 0; i < entry.nestedInterfaces.size(); ++i) {
        auto reference = llvm::cast<DictionaryAttr>(entry.nestedInterfaces[i]);
        auto selected = llvm::cast<DictionaryAttr>(entry.nestedRoles[i]);
        StringRef prefix = text(reference, "path");
        StringRef name = text(reference, "definition");
        StringRef roleName = text(selected, "role");
        auto [binding, inserted] =
            prefixBindings.try_emplace(prefix, std::make_pair(name, roleName));
        if (!inserted && binding->second != std::make_pair(name, roleName))
          return reject(role.operation,
                        "one nested member prefix must select one actual Interface and role");
        Operation *nested =
            lexicalInterfaceDefinition(definition.operation, name, true, work, &definitionLookup);
        auto known = definitions.find(nested);
        if (known == definitions.end())
          return reject(role.operation, "nested Interface identity has no actual owned definition");
        Definition &child = *known->second;
        prefixSizes.try_emplace(prefix, child.members.size());
        auto childRole = child.roles.find(roleName);
        StringRef suffix = item.getKey().drop_front(prefix.size() + 1);
        auto member = child.members.find(suffix);
        if (childRole == child.roles.end() || member == child.members.end())
          return reject(role.operation,
                        "nested role/member does not resolve in its actual Interface");
        Entry &childEntry = childRole->second.entries.find(suffix)->second;
        Member &parentMember = definition.members.find(item.getKey())->second;
        bool sameType = member->second.type == parentMember.type;
        if (!analog(parentMember.protocol) && parentMember.protocol != "resolved" &&
            !isDigitalControlBoundaryType(parentMember.type) &&
            (isDigitalRegionOwner(child.operation->getParentOp()) ||
             isDigitalRegionOwner(definition.operation->getParentOp())))
          sameType = equivalentDigitalStorageTypesAt(member->second.type, child.operation,
                                                     parentMember.type, definition.operation);
        if (!sameType || member->second.protocol != parentMember.protocol ||
            member->second.analogMetadata != parentMember.analogMetadata ||
            member->second.analogReferences != parentMember.analogReferences ||
            childEntry.access != entry.access || childEntry.leafRole != entry.leafRole ||
            childEntry.nestedRoles.size() != entry.nestedRoles.size() - i - 1)
          return reject(role.operation,
                        "nested Interface projection changed exact type, protocol or role access");
        for (unsigned j = 0; j < childEntry.nestedRoles.size(); ++j) {
          auto sourceRole = llvm::cast<DictionaryAttr>(entry.nestedRoles[i + j + 1]);
          auto sourceType = llvm::cast<DictionaryAttr>(entry.nestedInterfaces[i + j + 1]);
          auto projectedRole = llvm::cast<DictionaryAttr>(childEntry.nestedRoles[j]);
          auto projectedType = llvm::cast<DictionaryAttr>(childEntry.nestedInterfaces[j]);
          if (text(sourceRole, "path").drop_front(prefix.size() + 1) !=
                  text(projectedRole, "path") ||
              text(sourceRole, "role") != text(projectedRole, "role") ||
              text(sourceType, "definition") != text(projectedType, "definition"))
            return reject(role.operation,
                          "nested role chain is not the exact child definition projection");
        }
        ++prefixCounts[prefix];
      }
    }
    // Each referenced child is complete; a prefix cannot silently omit leaves.
    llvm::StringSet<> checked;
    for (auto &item : role.entries)
      for (Attribute attribute : item.second.nestedInterfaces) {
        auto reference = llvm::cast<DictionaryAttr>(attribute);
        StringRef prefix = text(reference, "path");
        if (checked.insert(prefix).second && prefixCounts[prefix] != prefixSizes.lookup(prefix))
          return reject(role.operation,
                        "nested Interface projection omits or repeats child leaves");
      }
  }
  return success();
}

LogicalResult Model::instance(Operation *operation) {
  if (failed(ownership(operation)) || failed(arity(operation, 0, 0)))
    return failure();
  auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("definition");
  Operation *target = reference ? lexicalInterfaceDefinition(operation, reference.getValue(), false,
                                                             work, &definitionLookup)
                                : nullptr;
  auto found = definitions.find(target);
  if (!identity(symbol(operation)) || found == definitions.end())
    return reject(operation,
                  "typed Interface instance requires an actual fully typed local definition");
  Definition &definition = *found->second;
  auto role = definition.roles.find(text(operation, "role"));
  auto dictionary = metadata(operation);
  auto domain = dictionary.getAs<FlatSymbolRefAttr>("domain");
  Operation *domainDeclaration = domain ? SymbolTable::lookupSymbolIn(module, domain) : nullptr;
  const bool external = module->hasAttr("external_name");
  if (role == definition.roles.end() || !identity(text(dictionary, "semantic_path")) ||
      (external ? bool(dictionary.get("domain"))
                : !llvm::isa_and_nonnull<DomainOp, DomainRequirementOp>(domainDeclaration) ||
                      domainDeclaration->getParentOp() != module))
    return reject(operation,
                  "Interface instance requires an actual role, local domain and semantic path");
  if (external) {
    if (definition.members.empty())
      return reject(operation, "external Interface requires at least one resolved pin leaf");
    if (operation->getParentOp() != module || text(dictionary, "placement") != "black_box_pin" ||
        text(dictionary, "profile") != "portable_boundary_only")
      return reject(operation, "external Interface requires explicit portable black-box placement");
    for (const auto &item : definition.members) {
      if (++work > digitalTypeMaximumNodes)
        return reject(operation, "external Interface signature exceeds its declaration budget");
      auto resolved = llvm::dyn_cast<ResolvedType>(item.second.type);
      if (item.second.protocol != "resolved" || !resolved ||
          role->second.entries.find(item.getKey())->second.access != "connect" ||
          failed(inferDigitalBitPatternFixedBits(resolved.getElementType(), definition.operation)))
        return reject(operation,
                      "external Interface requires only fixed resolved connect-role leaves");
    }
  } else if (dictionary.get("placement") || dictionary.get("profile")) {
    return reject(operation,
                  "ordinary Interface cannot claim external signature placement/profile");
  }
  auto dimensions = dictionary.getAs<ArrayAttr>("dimensions");
  if (!dimensions || dimensions.size() > digitalTypeMaximumDepth)
    return reject(operation,
                  "Interface array requires bounded explicit ordered structural dimensions");
  std::string shape;
  for (Attribute attribute : dimensions) {
    auto value = llvm::dyn_cast<StringAttr>(attribute);
    if (!value || value.getValue().contains(',') || !identity(value.getValue()))
      return reject(operation, "Interface dimensions require individual canonical shape tokens");
    if (external) {
      auto axis = resolveStaticShapeDimension(module, value.getValue());
      if (failed(axis) || axis->parameter || axis->expression || axis->literal <= 0)
        return reject(operation,
                      "external Interface axes require fixed positive literal dimensions");
    }
    if (!shape.empty())
      shape += ',';
    shape += value.getValue().str();
  }
  if (!shape.empty()) {
    auto cardinality = inferDigitalStorageMaximumBits(
        nodal::ShapedType::get(module->getContext(), shape, BitsType::get(module->getContext(), 1)),
        operation);
    if (failed(cardinality))
      return reject(operation, "Interface array dimensions require owned finite structural proofs");
    // Checked logical footprint, not lane expansion. Analog leaf storage remains
    // with its existing owner and has no invented digital bit width.
    for (auto &item : definition.members) {
      if (analog(item.second.protocol))
        continue;
      Type type = item.second.type;
      if (auto resolved = llvm::dyn_cast<ResolvedType>(type))
        type = resolved.getElementType();
      auto bits = isDigitalControlBoundaryType(type)
                      ? inferDigitalControlMaximumElements(type, module)
                      : inferDigitalStorageMaximumBits(type, definition.operation);
      if (failed(bits) || *bits > std::numeric_limits<int64_t>::max() / *cardinality)
        return reject(operation,
                      "Interface array leaf footprint exceeds finite metadata arithmetic");
    }
  }
  if (operation->getParentOp() != module)
    for (const auto &member : definition.members)
      if ((analog(member.second.protocol) &&
           !(member.second.protocol == "conservative" && shape.empty() &&
             llvm::isa<TerminalType>(member.second.type) &&
             llvm::isa<GenerateOp>(operation->getParentOp()))) ||
          member.second.protocol == "resolved" || isDigitalControlBoundaryType(member.second.type))
        return reject(
            operation,
            "generated Interface requires separately admitted analog/resolved/control ownership");
  instances.emplace(operation, Instance{operation, &definition, &role->second, domain, shape});
  return success();
}

LogicalResult Model::boundary(Operation *operation) {
  if (failed(ownership(operation)))
    return failure();
  auto boundary = inspectDigitalInterfaceBoundary(operation);
  if (failed(boundary))
    return failure();
  if (boundary->selection) {
    auto selection = inspectGeneratedSelection(operation->getOperand(0), operation->getOperand(1),
                                               text(operation, "member"), operation);
    if (failed(selection))
      return failure();
    // A symbol view is local to its exact lexical block. Separate structural
    // arms may need distinct read aliases; they retain the same declaration and
    // checked selection rather than creating additional physical endpoints.
    auto &known =
        selectedBoundaryIdentities[{boundary->instance, boundary->endpoint, operation->getBlock()}];
    for (const auto &previous : known) {
      if (previous.selections.size() != selection->selections.size())
        continue;
      bool same = true;
      for (auto [left, right] : llvm::zip(previous.selections, selection->selections)) {
        if (++work > digitalTypeMaximumNodes)
          return reject(operation, "selected Interface identity comparison exceeds finite budget");
        same &= left.family == right.family &&
                equivalentInstantiatedIntegerExpressions(left.coordinate, right.coordinate);
      }
      if (same)
        return reject(operation, "duplicate selected Interface must reuse its physical identity");
    }
    known.push_back(*selection);
  } else if (!boundaryIdentities.emplace(boundary->instance, boundary->endpoint).second) {
    return reject(operation,
                  "duplicate child Interface projection must reuse its physical identity");
  }
  const Instance *imported = nullptr;
  if (!boundary->instance) {
    auto local = instances.find(boundary->endpoint);
    if (local == instances.end())
      return reject(operation, "selected Interface must retain its actual inventoried declaration");
    imported = &local->second;
  } else {
    auto &child = childDefinitions[boundary->module];
    if (!child) {
      child = std::make_unique<Model>(boundary->module);
      // Import only canonical declarations. Never recurse through child projections
      // or duplicate its driver graph; the hierarchy occurrence graph owns edges.
      for (Operation &op : boundary->module->getRegion(0).front())
        if (llvm::isa<InterfaceOp>(op) && marked(&op) && failed(child->definition(&op)))
          return failure();
      for (auto &[unused, definition] : child->definitions)
        if (failed(child->nestedDefinitions(*definition)))
          return failure();
    }
    if (!child->instances.count(boundary->endpoint) && failed(child->instance(boundary->endpoint)))
      return failure();
    imported = &child->instances.find(boundary->endpoint)->second;
    // A semantic label is not an ABI proof. Compare each imported definition once
    // against the local canonical definition, including unaccessed members and
    // any identically named role maps; disjoint selected roles remain additive.
    for (const auto &[unused, imported] : child->definitions) {
      if (!comparedDefinitions
               .emplace(imported->operation,
                        boundary->selection ? boundary->selection : boundary->instance)
               .second)
        continue;
      SemanticKey semanticKey{&module->getRegion(0).front(), imported->semanticIdentity.str()};
      auto found = semanticDefinitions.find(semanticKey);
      if (found == semanticDefinitions.end()) {
        semanticDefinitions.try_emplace(semanticKey, imported.get());
        semanticDefinitionInstances.try_emplace(semanticKey, boundary->instance);
        semanticDefinitionSelections.try_emplace(semanticKey, boundary->selection);
      } else {
        const Definition &local = *found->second;
        if (local.members.size() != imported->members.size())
          return reject(operation,
                        "child Interface semantic identity changes its complete member set");
        // One authored definition has one invariant declaration contract. Its
        // distinct selected occurrences may specialize that same formal type
        // differently; every boundary below still checks its own complete
        // actual inventory against its independently inspected exact prefix.
        for (const auto &member : imported->members) {
          if (++work > digitalTypeMaximumNodes)
            return reject(operation, "Interface boundary comparison exceeds its structural budget");
          auto known = local.members.find(member.getKey());
          if (known == local.members.end() ||
              ((local.operation == imported->operation || analog(member.second.protocol) ||
                isDigitalControlBoundaryType(member.second.type))
                   ? known->second.type != member.second.type
                   : !equivalentBoundaryTypes(
                         known->second.type, semanticDefinitionInstances[semanticKey],
                         member.second.type, boundary->instance,
                         semanticDefinitionSelections[semanticKey], boundary->selection)) ||
              known->second.protocol != member.second.protocol ||
              known->second.analogMetadata != member.second.analogMetadata ||
              known->second.analogReferences != member.second.analogReferences)
            return reject(operation,
                          "child Interface semantic identity changes its complete member ABI");
        }
      }
      // This validation cache accumulates disjoint imported roles without granting
      // those roles to any endpoint definition. Later siblings must agree even if
      // the first local/imported definition did not declare their shared role.
      auto &canonicalRoles = semanticRoles[semanticKey];
      for (const auto &role : imported->roles) {
        if (++work > digitalTypeMaximumNodes)
          return reject(operation, "Interface boundary roles exceed their structural budget");
        auto [known, inserted] = canonicalRoles.try_emplace(role.getKey(), &role.second);
        if (inserted)
          continue;
        if (known->second->entries.size() != role.second.entries.size())
          return reject(operation, "child Interface changes an identically named complete role");
        for (const auto &entry : role.second.entries) {
          auto selected = known->second->entries.find(entry.getKey());
          if (++work > digitalTypeMaximumNodes || selected == known->second->entries.end() ||
              selected->second.dictionary != entry.second.dictionary)
            return reject(operation, "child Interface changes an identically named role map");
        }
      }
    }
  }
  const Instance &original = *imported;
  auto actualDimensions = metadata(operation).getAs<ArrayAttr>("dimensions");
  auto formalDimensions = metadata(boundary->endpoint).getAs<ArrayAttr>("dimensions");
  if ((metadata(operation).get("dimensions") && !actualDimensions) ||
      (!actualDimensions && !original.dimensions.empty()) ||
      (actualDimensions && actualDimensions.size() != formalDimensions.size()))
    return reject(operation, "child Interface projection changes its exact structural rank");
  std::string shape;
  if (actualDimensions) {
    for (auto [formalAttribute, actualAttribute] : llvm::zip(formalDimensions, actualDimensions)) {
      if (++work > digitalTypeMaximumNodes)
        return reject(operation, "Interface boundary dimensions exceed their structural budget");
      auto formal = llvm::dyn_cast<StringAttr>(formalAttribute);
      auto actual = llvm::dyn_cast<StringAttr>(actualAttribute);
      if (!formal || !actual || actual.getValue().contains(',') || !identity(actual.getValue()))
        return reject(operation, "Interface boundary dimensions require canonical shape tokens");
      bool equal = false;
      if (boundary->selection) {
        Type unit = BitsType::get(module->getContext(), 1);
        Type formalShape = nodal::ShapedType::get(module->getContext(), formal.getValue(), unit);
        Type actualShape = nodal::ShapedType::get(module->getContext(), actual.getValue(), unit);
        equal = boundary->instance
                    ? equivalentSelectedDigitalStorageTypes(operation, boundary->instance,
                                                            formalShape, actualShape)
                    : equivalentSelectedDigitalStorageTypesAt(operation, boundary->endpoint,
                                                              formalShape, actualShape);
      } else {
        auto childExtent = resolveStaticShapeDimension(boundary->module, formal.getValue());
        auto parentExtent = resolveStaticShapeDimension(module, actual.getValue());
        equal = succeeded(childExtent) && succeeded(parentExtent) &&
                succeeded(
                    verifyInstanceShapeDimension(boundary->instance, *childExtent, *parentExtent));
      }
      if (!equal)
        return reject(operation,
                      "Interface boundary shape differs from exact finalized selection binding");
      if (!shape.empty())
        shape += ',';
      shape += actual.getValue().str();
    }
    if (!shape.empty() && failed(inferDigitalStorageMaximumBits(
                              nodal::ShapedType::get(module->getContext(), shape,
                                                     BitsType::get(module->getContext(), 1)),
                              operation)))
      return reject(operation, "Interface boundary shape requires finite structural cardinality");
  }
  auto actualInventory = metadata(operation).getAs<ArrayAttr>("actual_types");
  if ((metadata(operation).get("actual_types") && !actualInventory) ||
      (actualInventory && actualInventory.size() != original.definition->members.size()))
    return reject(operation,
                  "Interface boundary actual types require the complete member inventory");
  bool scalarConservativeInventory = boundary->selection &&
      !original.definition->members.empty();
  if (scalarConservativeInventory)
    for (const auto &entry : original.definition->members) {
      if (++work > digitalTypeMaximumNodes)
        return reject(operation, "selected conservative inventory exceeds structural budget");
      scalarConservativeInventory &= entry.second.protocol == "conservative" &&
          llvm::isa<TerminalType>(entry.second.type);
    }
  const bool selectedConservativeEndpoint = scalarConservativeInventory && shape.empty() &&
      succeeded(resolveConservativeGeneratedSelection(operation));
  bool fixedConservativeArray = false;
  if (!shape.empty() && llvm::any_of(original.definition->members, [](const auto &entry) {
        return entry.second.protocol == "conservative";
      })) {
    DigitalInterfaceEndpoint facts;
    facts.operation = operation;
    facts.declaration = boundary->endpoint;
    facts.declarationOwner = boundary->module;
    facts.domainOwner = module;
    facts.instance = boundary->instance;
    facts.selectedRole = original.role->operation;
    facts.domain = boundary->domain;
    facts.dimensions = actualDimensions;
    facts.selection = boundary->selection;
    for (Operation &member : original.definition->operation->getRegion(0).front()) {
      if (!llvm::isa<InterfaceMemberOp>(member)) continue;
      if (++work > digitalTypeMaximumNodes)
        return reject(operation, "conservative array inventory exceeds structural budget");
      auto found = original.definition->members.find(symbol(&member));
      facts.leaves.push_back(leafFacts(found->second,
          original.role->entries.find(found->getKey())->second));
    }
    unsigned remaining = digitalTypeMaximumNodes - work;
    auto status = fixedConservativeArrayProfile(facts, remaining);
    work = digitalTypeMaximumNodes - remaining;
    if (failed(status)) return failure();
    fixedConservativeArray = true;
  }
  llvm::StringMap<Type> actualTypes;
  unsigned memberIndex = 0;
  for (Operation &memberOperation : original.definition->operation->getRegion(0).front()) {
    if (!llvm::isa<InterfaceMemberOp>(memberOperation))
      continue;
    if (++work > digitalTypeMaximumNodes)
      return reject(operation, "Interface boundary actual types exceed their structural budget");
    StringRef path = symbol(&memberOperation);
    const Member &member = original.definition->members.find(path)->second;
    auto resolved = llvm::dyn_cast<ResolvedType>(member.type);
    Type payload = resolved ? resolved.getElementType() : member.type;
    const bool conservative = member.protocol == "conservative";
    const bool selectedConservative = conservative &&
        llvm::isa<TerminalType>(member.type) && selectedConservativeEndpoint;
    if ((boundary->selection &&
         ((analog(member.protocol) && !selectedConservative) || resolved ||
          isDigitalControlBoundaryType(member.type))) ||
        (conservative && !shape.empty() && !fixedConservativeArray) || member.protocol == "signal_flow" ||
        isDigitalControlBoundaryType(member.type) ||
        (!conservative &&
         failed(inferDigitalBitPatternMaximumBits(payload, original.definition->operation))))
      return reject(
          operation,
          "child Interface projection requires digital data or scalar conservative leaves");
    Type actual = member.type;
    if (actualInventory) {
      auto item = llvm::dyn_cast<DictionaryAttr>(actualInventory[memberIndex]);
      auto type = item ? item.getAs<TypeAttr>("type") : TypeAttr();
      if (!item || item.size() != 2 || text(item, "path") != path || !type)
        return reject(operation, "Interface boundary actual types change ordered member identity");
      actual = type.getValue();
    } else if (!conservative &&
               failed(inferDigitalBitPatternFixedBits(payload, original.definition->operation))) {
      return reject(operation, "symbolic Interface boundary requires explicit actual member types");
    }
    ++memberIndex;
    if (conservative ? actual != member.type
        : boundary->selection
            ? !(boundary->instance
                    ? equivalentSelectedDigitalStorageTypes(operation, boundary->instance,
                                                            member.type, actual)
                    : equivalentSelectedDigitalStorageTypesAt(
                          operation, original.definition->operation, member.type, actual))
            : !equivalentBoundaryTypes(member.type, boundary->instance, actual, nullptr))
      return reject(operation,
                    "Interface actual member type differs from exact finalized child binding");
    auto actualResolved = llvm::dyn_cast<ResolvedType>(actual);
    Type actualPayload = actualResolved ? actualResolved.getElementType() : actual;
    Type shaped = shape.empty()
                      ? actualPayload
                      : nodal::ShapedType::get(module->getContext(), shape, actualPayload);
    if (!conservative && failed(inferDigitalBitPatternMaximumBits(shaped, operation)))
      return reject(operation, "Interface actual payload requires finite owned bit-pattern shape");
    actualTypes.try_emplace(path, actual);
  }
  instances.emplace(operation,
                    Instance{operation, original.definition, original.role, boundary->domain, shape,
                             boundary->instance, boundary->endpoint, std::move(actualTypes),
                             boundary->selection});
  return success();
}

LogicalResult Model::access(Operation *operation) {
  if (failed(ownership(operation)) || failed(arity(operation, 0, 1)))
    return failure();
  auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("instance");
  Operation *target = localInterfaceSymbol(operation, reference);
  auto found = instances.find(target);
  if (found == instances.end())
    return reject(operation,
                  "typed member access must resolve an actual fully typed local instance");
  Instance &instance = found->second;
  StringRef selected = text(metadata(operation), "role");
  if (selected.empty())
    selected = symbol(instance.role->operation);
  auto role = instance.definition->roles.find(selected);
  StringRef path = text(operation, "path");
  auto member = instance.definition->members.find(path);
  if (role == instance.definition->roles.end() || member == instance.definition->members.end())
    return reject(operation, "member access must resolve its exact path and actual selected role");
  if (instance.childEndpoint && &role->second != instance.role)
    return reject(operation,
                  "child Interface projection cannot elevate or invert its declared role");
  if (&role->second != instance.role && selected != "monitor") {
    for (auto &item : instance.definition->members) {
      StringRef original = instance.role->entries.find(item.getKey())->second.access;
      StringRef viewed = role->second.entries.find(item.getKey())->second.access;
      if (analog(item.second.protocol) || item.second.protocol == "resolved" ||
          !((original == "in" && viewed == "out") || (original == "out" && viewed == "in")))
        return reject(operation,
                      "non-monitor Interface views require fully complementary digital leaves");
    }
  }
  Type expected = instance.childEndpoint ? instance.actualTypes.lookup(path) : member->second.type;
  if (!instance.dimensions.empty())
    expected = nodal::ShapedType::get(module->getContext(), instance.dimensions, expected);
  Type actual = operation->getResult(0).getType();
  bool equal = actual == expected;
  Operation *payloadAnchor =
      instance.childEndpoint ? instance.operation : instance.definition->operation;
  if (!analog(member->second.protocol) && member->second.protocol != "resolved" &&
      !isDigitalControlBoundaryType(member->second.type) &&
      (isDigitalRegionOwner(operation->getParentOp()) ||
       isDigitalRegionOwner(payloadAnchor->getParentOp()))) {
    Type payload = actual;
    equal = true;
    if (!instance.dimensions.empty()) {
      auto shaped = llvm::dyn_cast<nodal::ShapedType>(actual);
      Type unit = BitsType::get(module->getContext(), 1);
      equal =
          shaped && equivalentDigitalStorageTypesAt(
                        nodal::ShapedType::get(module->getContext(), instance.dimensions, unit),
                        instance.operation,
                        nodal::ShapedType::get(module->getContext(), shaped.getDimensions(), unit),
                        operation);
      if (shaped)
        payload = shaped.getElementType();
    }
    Type declared =
        instance.childEndpoint ? instance.actualTypes.lookup(path) : member->second.type;
    equal &= equivalentDigitalStorageTypesAt(declared, payloadAnchor, payload, operation);
  }
  if (!equal)
    return reject(operation,
                  "member SSA result must retain the exact declared type and ordered dimensions");
  accesses.try_emplace(
      operation->getResult(0),
      Access{&instance, &member->second, &role->second.entries.find(path)->second, path});
  if (member->second.protocol == "resolved") {
    auto type = llvm::cast<ResolvedType>(instance.childEndpoint ? instance.actualTypes.lookup(path)
                                                                : member->second.type);
    unsigned index = nets.size();
    netIndices.try_emplace(operation->getResult(0), index);
    nets.push_back(Net{operation->getResult(0), "portable_boundary_only", type.getDriveMode()});
    parents.push_back(index);
    ranks.push_back(0);
  }
  return success();
}

LogicalResult Model::abi(Operation *operation) {
  if (failed(ownership(operation)) || failed(arity(operation, 0, 0)))
    return failure();
  auto reference = metadata(operation).getAs<FlatSymbolRefAttr>("instance");
  Operation *target = localInterfaceSymbol(operation, reference);
  auto found = instances.find(target);
  if (found == instances.end() || !seenAbi.insert(target).second)
    return reject(operation, "typed ABI must identify one actual local instance exactly once");
  Instance &instance = found->second;
  auto members = operation->getAttrOfType<ArrayAttr>("members");
  if (text(operation, "layout_policy") != "structural" ||
      text(operation, "logical_path") != text(metadata(target), "semantic_path") || !members ||
      members.size() != instance.definition->members.size())
    return reject(operation,
                  "ABI must preserve the complete structural instance identity and leaf set");
  llvm::StringSet<> seen;
  for (Attribute attribute : members) {
    auto item = llvm::dyn_cast<DictionaryAttr>(attribute);
    StringRef path = text(item, "path");
    auto member = instance.definition->members.find(path);
    auto type = item ? item.getAs<TypeAttr>("type") : TypeAttr();
    if (!item || !type || member == instance.definition->members.end() ||
        !seen.insert(path).second || type.getValue() != member->second.type)
      return reject(operation, "ABI member must exactly match one declared leaf type");
    auto analogMetadata = item.getAs<DictionaryAttr>("analog");
    if (!analogMetadata)
      analogMetadata = DictionaryAttr::get(module->getContext());
    if (analogMetadata != member->second.analogMetadata)
      return reject(operation, "ABI analog metadata must match the actual member declaration");
    Entry &entry = instance.role->entries.find(path)->second;
    if (text(item, "role") != entry.leafRole || text(item, "access") != entry.access ||
        item.getAs<ArrayAttr>("nested_roles") != entry.nestedRoles ||
        item.getAs<ArrayAttr>("nested_interfaces") != entry.nestedInterfaces)
      return reject(operation,
                    "ABI role/access/nested identities must be the actual instance projection");
  }
  return success();
}

// Shared declaration policy for local nets and exact imported declarations. This
// reads facts only: importing a declaration never imports its local driver graph.
LogicalResult verifyResolvedNetDeclaration(Operation *operation) {
  if (!llvm::isa_and_nonnull<ResolvedNetOp>(operation) ||
      failed(verifyDigitalInterfaceOperation(operation)) || failed(arity(operation, 0, 1)))
    return failure();
  auto type = llvm::dyn_cast<ResolvedType>(operation->getResult(0).getType());
  if (!identity(text(operation, "name")) || !type || !driveMode(type.getDriveMode()) ||
      !bitVector(type.getElementType()) ||
      failed(inferDigitalStorageMaximumBits(type.getElementType(), operation)))
    return reject(operation,
                  "typed resolved net requires an exact finite bit-vector element and identity");
  StringRef profile = text(metadata(operation), "profile");
  StringRef placement = text(metadata(operation), "placement");
  if (profile != "portable_boundary_only" && profile != "full_resolved_simulation" &&
      profile != "technology_mapped")
    return reject(operation, "resolved net requires its explicit resolution profile");
  if (placement != "top_level_pin" && placement != "black_box_pin" &&
      placement != "hierarchy_pass_through" && placement != "pad_cell_boundary" &&
      placement != "internal_resolved_net")
    return reject(operation, "resolved net requires its explicit supported placement");
  if (profile == "portable_boundary_only" && placement == "internal_resolved_net")
    return reject(operation,
                  "portable boundary resolution cannot represent an internal resolved net");
  return success();
}
void Model::addNet(Value value, StringRef profile, StringRef mode) {
  unsigned index = nets.size();
  netIndices.try_emplace(value, index);
  nets.push_back(Net{value, profile, mode});
  parents.push_back(index);
  ranks.push_back(0);
}
LogicalResult Model::net(Operation *operation) {
  if (failed(ownership(operation)) || failed(verifyResolvedNetDeclaration(operation)))
    return failure();
  auto type = llvm::cast<ResolvedType>(operation->getResult(0).getType());
  addNet(operation->getResult(0), text(metadata(operation), "profile"), type.getDriveMode());
  return success();
}
LogicalResult Model::netBoundary(Operation *operation) {
  if (failed(ownership(operation)) || failed(arity(operation, 0, 1)))
    return failure();
  auto child = resolveHierarchyInstance(operation, operation->getAttr("instance"));
  if (failed(child))
    return failure();
  auto [inventory, inserted] = childNetDeclarations.try_emplace(child->module);
  if (inserted)
    for (Operation &declaration : child->module->getRegion(0).front()) {
      if (++work > digitalTypeMaximumNodes)
        return reject(operation, "resolved boundary declaration inventory exceeds its budget");
      if (!llvm::isa<ResolvedNetOp>(declaration) || !marked(&declaration))
        continue;
      if (failed(verifyResolvedNetDeclaration(&declaration)))
        return failure();
      if (!inventory->second.try_emplace(text(&declaration, "name"), &declaration).second)
        return reject(operation, "child resolved net names must be unambiguous identities");
    }
  StringRef endpoint = text(operation, "endpoint");
  auto original = inventory->second.find(endpoint);
  if (!identity(endpoint) || original == inventory->second.end())
    return reject(operation, "resolved boundary requires its exact immediate child's direct net");
  Operation *declaration = original->second;
  if (!netBoundaryIdentities.emplace(child->instance, declaration).second)
    return reject(operation,
                  "duplicate child resolved projection must reuse its physical identity");
  auto formal = llvm::cast<ResolvedType>(declaration->getResult(0).getType());
  auto actual = llvm::dyn_cast<ResolvedType>(operation->getResult(0).getType());
  auto facts = metadata(operation);
  if (!actual || !identity(text(facts, "semantic_path")) ||
      text(facts, "profile") != text(metadata(declaration), "profile") ||
      text(facts, "placement") != text(metadata(declaration), "placement") || facts.get("domain") ||
      metadata(declaration).get("domain") ||
      !equivalentBoundaryTypes(formal, child->instance, actual, nullptr) ||
      failed(inferDigitalStorageMaximumBits(actual.getElementType(), operation)))
    return reject(operation,
                  "resolved boundary must preserve exact mode, actual type, placement and profile "
                  "without inventing a domain constraint");
  addNet(operation->getResult(0), text(metadata(declaration), "profile"), formal.getDriveMode());
  netBoundaries.emplace(operation, std::make_pair(child->instance, declaration));
  return success();
}
FailureOr<unsigned> Model::netIndex(Value value, Operation *operation) {
  auto found = netIndices.find(value);
  if (found == netIndices.end() || failed(localValue(value, operation))) {
    (void)reject(operation, "resolved operation requires an actual fully typed owned net identity");
    return failure();
  }
  return found->second;
}
unsigned Model::representative(unsigned index) {
  unsigned root = index;
  while (parents[root] != root)
    root = parents[root];
  while (parents[index] != index) {
    unsigned next = parents[index];
    parents[index] = root;
    index = next;
  }
  return root;
}
void Model::unite(unsigned a, unsigned b) {
  a = representative(a);
  b = representative(b);
  if (a == b)
    return;
  if (ranks[a] < ranks[b])
    std::swap(a, b);
  parents[b] = a;
  if (ranks[a] == ranks[b])
    ++ranks[a];
}
Model::PhysicalMember Model::physicalMember(const Access &access) const {
  const Instance &instance = *access.instance;
  return {instance.childEndpoint ? instance.childEndpoint : instance.operation,
          instance.childInstance, access.path.str()};
}
LogicalResult Model::addOrdinaryDriver(const Access &access, Value target, Value source,
                                       Operation *operation) {
  auto &drivers = ordinaryDrivers[physicalMember(access)];
  for (const OrdinaryDriver &previous : drivers) {
    if (++work > digitalTypeMaximumNodes)
      return reject(operation,
                    "ordinary Interface driver proof exceeds its finite relation budget");
    // Preserve the established same-projection rule without asking Shape to grant
    // control or protocol rights. Distinct selected aliases need physical proof.
    if (previous.projection == access.instance->operation)
      return reject(operation, "ordinary Interface member has multiple authored drivers");
    auto left = resolveStaticShapeProjection(previous.target, previous.operation);
    auto right = resolveStaticShapeProjection(target, operation);
    if (failed(left) || failed(right))
      return reject(operation, "ordinary Interface writers require exact physical shape proof");
    auto overlap = compareStaticShapeProjections(*left, *right, operation);
    if (overlap.status != ShapeRegionProofStatus::Proved ||
        overlap.overlap != ShapeRegionOverlap::Disjoint)
      return reject(operation,
                    "ordinary Interface member has overlapping or unproved selected drivers");
  }
  drivers.push_back({target, source, operation, access.instance->operation});
  return success();
}

LogicalResult Model::effect(Operation *operation) {
  bool connect = named(operation, "nodal.interface_connect");
  bool drive = named(operation, "nodal.interface_drive");
  if (failed(ownership(operation)) || failed(arity(operation, connect || drive ? 2 : 1, 0)))
    return failure();
  auto found = accesses.find(operation->getOperand(0));
  if (found == accesses.end())
    return reject(operation,
                  "Interface operation requires an actual typed member-access SSA value");
  Access &left = found->second;
  if (analog(left.member->protocol))
    return reject(operation, "analog Interface members retain their existing analog access owners");
  if (failed(localValue(operation->getOperand(0), operation)))
    return failure();
  StringRef permission = left.entry->access;
  bool resolved = left.member->protocol == "resolved";
  if (drive) {
    if (resolved                       ? permission != "drive"
        : left.instance->childInstance ? permission != "in"
                                       : permission != "out")
      return reject(operation, "actual Interface role does not permit driving this member");
    Type expected = operation->getOperand(0).getType();
    if (resolved) {
      auto net = llvm::dyn_cast<ResolvedType>(expected);
      if (!net || net.getDriveMode() != "push_pull")
        return reject(
            operation,
            "Interface drive requires scalar push-pull; constrained nets use explicit drivers");
      expected = net.getElementType();
    }
    if (!(isDigitalControlBoundaryType(expected)
              ? operation->getOperand(1).getType() == expected
              : equivalentDigitalStorageTypesAt(operation->getOperand(1).getType(), operation,
                                                expected, operation)) ||
        failed(localValue(operation->getOperand(1), operation)))
      return reject(operation, "Interface drive value must have the exact owned member type");
    if (isDigitalControlBoundaryType(expected)) {
      auto projection = resolveStaticShapeProjection(operation->getOperand(1), operation);
      if (failed(projection))
        return reject(operation,
                      "Clock/Reset Interface drive requires actual structural boundary provenance");
      auto sourceMember = accesses.find(projection->root);
      if (sourceMember != accesses.end()) {
        if (sourceMember->second.instance->domain != left.instance->domain ||
            sourceMember->second.entry->access == "drive" ||
            analog(sourceMember->second.member->protocol))
          return reject(
              operation,
              "Clock/Reset Interface source requires readable exact-domain member access");
      } else {
        Operation *root = projection->root.getDefiningOp();
        auto reference =
            root ? root->getAttrOfType<FlatSymbolRefAttr>("port") : FlatSymbolRefAttr();
        Operation *port = reference ? SymbolTable::lookupSymbolIn(module, reference) : nullptr;
        auto type = port ? port->getAttrOfType<TypeAttr>("type") : TypeAttr();
        if (!llvm::isa_and_nonnull<PortValueOp>(root) || root->getParentOp() != module ||
            !llvm::isa_and_nonnull<PortOp>(port) || port->getParentOp() != module ||
            text(port, "direction") != "input" || !type ||
            type.getValue() != projection->root.getType() ||
            port->getAttrOfType<FlatSymbolRefAttr>("domain") != left.instance->domain)
          return reject(
              operation,
              "Clock/Reset Interface source requires actual exact-domain input port provenance");
      }
    }
    auto source = accesses.find(operation->getOperand(1));
    if (source != accesses.end() &&
        (source->second.instance->domain != left.instance->domain ||
         source->second.entry->access == "drive" || analog(source->second.member->protocol)))
      return reject(
          operation,
          "Interface member drive source must be readable digital access in the same domain");
    if (!resolved)
      return addOrdinaryDriver(left, operation->getOperand(0), operation->getOperand(1), operation);
    auto index = netIndex(operation->getOperand(0), operation);
    if (failed(index))
      return failure();
    // An unconditional Interface drive remains a possible value writer. It
    // cannot supply a conditional split enable and must not be silently ignored.
    conditionalDrivers.emplace_back(*index, operation);
    return success();
  }
  if (!connect) {
    if (permission == "drive")
      return reject(operation, "drive-only Interface role does not permit observation");
    return success();
  }
  auto rightFound = accesses.find(operation->getOperand(1));
  if (rightFound == accesses.end())
    return reject(operation, "Interface connection requires two actual typed member accesses");
  Access &right = rightFound->second;
  if (failed(localValue(operation->getOperand(1), operation)))
    return failure();
  if (left.instance == right.instance ||
      (left.instance->definition != right.instance->definition &&
       (left.instance->definition->operation->getParentOp() !=
            interfaceModule(left.instance->definition->operation) ||
        right.instance->definition->operation->getParentOp() !=
            interfaceModule(right.instance->definition->operation) ||
        left.instance->definition->semanticIdentity !=
            right.instance->definition->semanticIdentity)) ||
      left.member->protocol != right.member->protocol ||
      left.entry->nestedInterfaces != right.entry->nestedInterfaces || left.path != right.path ||
      (left.instance->domain != right.instance->domain &&
       (!resolved ||
        (!unconstrainedExternal(*left.instance) && !unconstrainedExternal(*right.instance)))) ||
      (isDigitalControlBoundaryType(operation->getOperand(0).getType())
           ? operation->getOperand(0).getType() != operation->getOperand(1).getType()
       : resolved
           ? !equivalentBoundaryTypes(operation->getOperand(0).getType(), nullptr,
                                      operation->getOperand(1).getType(), nullptr)
           : !equivalentDigitalStorageTypesAt(operation->getOperand(0).getType(), operation,
                                              operation->getOperand(1).getType(), operation)))
    return reject(
        operation,
        "Interface connection requires distinct exact members, dimensions and domain identity");
  StringRef other = right.entry->access;
  if (resolved) {
    if (permission != "connect" || other != "connect")
      return reject(operation,
                    "resolved Interface connection requires connect permission at both endpoints");
    auto first = netIndex(operation->getOperand(0), operation);
    auto second = netIndex(operation->getOperand(1), operation);
    if (failed(first) || failed(second))
      return failure();
    unite(*first, *second);
    return success();
  }
  bool leftBoundary = left.instance->childInstance, rightBoundary = right.instance->childInstance;
  bool forwarding = leftBoundary != rightBoundary;
  if (forwarding
          ? permission != other || (permission != "in" && permission != "out")
          : !((permission == "in" && other == "out") || (permission == "out" && other == "in")))
    return reject(operation, "Interface connection requires exact boundary-facing directions");
  bool leftSink =
      forwarding ? (leftBoundary ? permission == "in" : permission == "out") : permission == "in";
  return addOrdinaryDriver(leftSink ? left : right, operation->getOperand(leftSink ? 0 : 1),
                           operation->getOperand(leftSink ? 1 : 0), operation);
}

LogicalResult Model::resolvedEffect(Operation *operation) {
  if (failed(ownership(operation)))
    return failure();
  bool read = llvm::isa<NetReadOp>(operation), driver = llvm::isa<NetDriverOp>(operation);
  bool conditional = llvm::isa<NetDriveOp>(operation);
  bool level = named(operation, "nodal.net_drive_level");
  bool release = named(operation, "nodal.net_release");
  bool through = named(operation, "nodal.net_pass_through");
  bool split = named(operation, "nodal.net_split");
  if (failed(arity(operation,
                   conditional          ? 4
                   : level              ? 3
                   : release || through ? 2
                                        : 1,
                   read || driver ? 1
                   : split        ? 2
                                  : 0)))
    return failure();
  auto index = netIndex(operation->getOperand(0), operation);
  if (failed(index))
    return failure();
  Net &net = nets[*index];
  Type carrier = net.value.getType();
  auto shape = llvm::dyn_cast<nodal::ShapedType>(carrier);
  auto type = llvm::dyn_cast<ResolvedType>(shape ? shape.getElementType() : carrier);
  auto member = accesses.find(net.value);
  if (!type || !bitVector(type.getElementType()) ||
      (shape && (member == accesses.end() || member->second.instance->dimensions.empty() ||
                 member->second.instance->selection ||
                 member->second.instance->operation->getParentOp() != module)))
    return reject(operation,
                  "resolved family requires one actual module-owned Interface member shape");
  // The admitted member access owns the ordered outer axes. Replacing only its
  // scalar capability leaf preserves the exact pointwise coordinate tuple.
  auto pointwise = [&](Type element) -> Type {
    return shape ? nodal::ShapedType::get(module->getContext(), shape.getDimensions(), element)
                 : element;
  };
  Type valueType = pointwise(type.getElementType());
  Type enableType = pointwise(BitsType::get(module->getContext(), 1));
  Type driverType = pointwise(DriverType::get(module->getContext(), type.getElementType()));
  auto exactData = [&](Type actual, Type expected) {
    return equivalentDigitalStorageTypesAt(actual, operation, expected, operation);
  };
  StringRef permission = member == accesses.end() ? StringRef() : member->second.entry->access;
  if (read || split) {
    if (permission == "drive")
      return reject(operation, "drive-only Interface capability cannot be read or split");
    if ((shape ? !exactData(operation->getResult(0).getType(), valueType)
               : operation->getResult(0).getType() != type.getElementType()) ||
        (split && (shape ? !exactData(operation->getResult(1).getType(), enableType)
                         : !boolean(operation->getResult(1).getType()))))
      return reject(operation,
                    "net read/split must retain exact value and canonical Bool result types");
    if (split) {
      if (operation->hasAttr("hierarchy_driver") &&
          !operation->getAttrOfType<UnitAttr>("hierarchy_driver"))
        return reject(operation, "split hierarchy-driver intent must be the closed unit property");
      splits.emplace_back(*index, operation);
    }
    return success();
  }
  if (through) {
    auto other = netIndex(operation->getOperand(1), operation);
    if (failed(other))
      return failure();
    if (*other == *index || nets[*other].value.getType() != net.value.getType() ||
        nets[*other].profile != net.profile)
      return reject(operation,
                    "pass-through requires distinct nets with exact type, mode and profile");
    auto otherMember = accesses.find(nets[*other].value);
    if ((!permission.empty() && permission != "connect") ||
        (otherMember != accesses.end() && otherMember->second.entry->access != "connect"))
      return reject(operation, "Interface pass-through requires actual connect capability");
    if (member != accesses.end() && otherMember != accesses.end() &&
        member->second.instance->domain != otherMember->second.instance->domain &&
        !unconstrainedExternal(*member->second.instance) &&
        !unconstrainedExternal(*otherMember->second.instance))
      return reject(operation, "Interface resolved pass-through requires exact domain identity");
    unite(*index, *other);
    return success();
  }
  if (net.mode == "read_only" ||
      (!permission.empty() && permission != "drive" && permission != "connect"))
    return reject(operation, "resolved endpoint does not permit creating or using a driver");
  if (driver) {
    StringRef id = text(operation, "driver_id");
    if (operation->getResult(0).getType() != driverType || !identity(id) ||
        !driverIdentities.try_emplace(id, operation).second)
      return reject(operation,
                    "driver requires exact element type and unique owned authored identity");
    return success();
  }
  Value driverValue = operation->getOperand(1);
  Operation *declaration = driverValue.getDefiningOp();
  if (!llvm::isa_and_nonnull<NetDriverOp>(declaration) || !marked(declaration) ||
      declaration->getParentOp() != module || declaration->getNumOperands() != 1 ||
      declaration->getNumResults() != 1 || declaration->getOperand(0) != net.value ||
      declaration->getResult(0) != driverValue || driverValue.getType() != driverType ||
      !usedDrivers.insert(driverValue).second)
    return reject(operation,
                  "drive/release must use one actual unique driver of this exact owned net");
  if (release)
    return success();
  Value enable = operation->getOperand(conditional ? 3 : 2);
  if (!exactData(enable.getType(), enableType) || failed(localValue(enable, operation)))
    return reject(operation, "conditional driver enable requires an actual owned canonical Bool");
  if (conditional) {
    if ((net.mode != "push_pull" && net.mode != "open_source") ||
        text(operation, "mode") != net.mode ||
        !exactData(operation->getOperand(2).getType(), valueType) ||
        failed(localValue(operation->getOperand(2), operation)))
      return reject(operation,
                    "conditional value drive requires permitted mode and exact owned value type");
  } else if (level) {
    StringRef active = text(operation, "level");
    if (!((active == "low" && net.mode == "open_drain") ||
          (active == "high" && net.mode == "open_source")))
      return reject(operation,
                    "constrained level drive requires its declared low/high resolution mode");
  } else {
    return reject(operation, "unknown typed resolved-net effect");
  }
  conditionalDrivers.emplace_back(*index, operation);
  return success();
}

LogicalResult Model::verify() {
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(module) || module->getNumRegions() != 1 ||
      !module->getRegion(0).hasOneBlock())
    return reject(module, "typed connectivity requires one owning module block");
  // Reject typed fragments in arbitrary nested executable regions. Definitions
  // have their own strict child schema; independent modules are checked there.
  WalkResult ownershipResult = module->walk<WalkOrder::PreOrder>([&](Operation *operation) {
    if (operation != module && llvm::isa<nodal::ModuleOp>(operation))
      return WalkResult::skip();
    if (operation == module)
      return WalkResult::advance();
    if ((oldOperation(operation) || newOperation(operation)) &&
        hasDigitalInterfaceContract(operation) &&
        failed(verifyDigitalInterfaceOperation(operation)))
      return WalkResult::interrupt();
    if (++work > digitalTypeMaximumNodes) {
      (void)reject(operation, "typed connectivity lexical inventory exceeds finite work budget");
      return WalkResult::interrupt();
    }
    if (!llvm::isa<InterfaceRoleOp, InterfaceMemberOp>(operation))
      lexicalOperations.push_back(operation);
    return WalkResult::advance();
  });
  if (ownershipResult.wasInterrupted())
    return failure();
  for (Operation &operation : llvm::make_pointee_range(lexicalOperations))
    if (llvm::isa<InterfaceOp>(operation)) {
      if (marked(&operation)) {
        if (failed(definition(&operation)))
          return failure();
      } else {
        for (Region &region : operation.getRegions())
          for (Block &block : region)
            for (Operation &child : block)
              if (marked(&child))
                return reject(&child,
                              "fully typed role/member cannot belong to a legacy Interface");
      }
    }
  for (auto &[unused, value] : definitions)
    if (failed(nestedDefinitions(*value)))
      return failure();
  for (Operation &operation : llvm::make_pointee_range(lexicalOperations))
    if (llvm::isa<InterfaceInstanceOp>(operation) && marked(&operation))
      if (failed(instance(&operation)))
        return failure();
  for (Operation &operation : llvm::make_pointee_range(lexicalOperations))
    if (llvm::isa<InterfaceBoundaryOp>(operation) && failed(boundary(&operation)))
      return failure();
  for (Operation &operation : llvm::make_pointee_range(lexicalOperations)) {
    if (llvm::isa<MemberAccessOp>(operation) && marked(&operation)) {
      if (failed(access(&operation)))
        return failure();
    } else if (llvm::isa<ResolvedNetOp>(operation) && marked(&operation)) {
      if (failed(net(&operation)))
        return failure();
    } else if (llvm::isa<ResolvedBoundaryOp>(operation)) {
      if (failed(netBoundary(&operation)))
        return failure();
    }
  }
  // Multiple role-qualified accesses retain one physical member identity.
  std::map<std::pair<Operation *, std::string>, unsigned> resolvedMembers;
  for (auto &item : accesses) {
    auto index = netIndices.find(item.first);
    if (index == netIndices.end())
      continue;
    auto [known, inserted] = resolvedMembers.emplace(
        std::make_pair(item.second.instance->operation, item.second.path.str()), index->second);
    if (!inserted)
      unite(known->second, index->second);
  }
  for (Operation &operation : llvm::make_pointee_range(lexicalOperations)) {
    if (!marked(&operation) && !newOperation(&operation)) {
      // No marker omission can bridge a legacy declaration/use into a typed
      // identity. Legacy standalone shells retain their predecessor contract.
      StringRef key = llvm::isa<InterfaceInstanceOp>(operation) ? "definition"
                      : llvm::isa<MemberAccessOp>(operation)    ? "instance"
                                                                : "";
      if (!key.empty()) {
        auto reference = operation.getAttrOfType<FlatSymbolRefAttr>(key);
        Operation *target =
            key == "definition"
                ? (reference ? lexicalInterfaceDefinition(&operation, reference.getValue(), false,
                                                          work, &definitionLookup)
                             : nullptr)
                : localInterfaceSymbol(&operation, reference);
        if (target && marked(target))
          return reject(&operation,
                        "legacy Interface use cannot consume a fully typed declaration");
      }
      if (llvm::isa<NetReadOp, NetDriverOp, NetDriveOp>(operation))
        for (Value operand : operation.getOperands())
          if (Operation *source = operand.getDefiningOp(); source && marked(source))
            return reject(&operation,
                          "legacy resolved operation cannot consume a fully typed capability");
      continue;
    }
    if (llvm::isa<InterfaceOp, InterfaceInstanceOp, InterfaceBoundaryOp, MemberAccessOp,
                  ResolvedNetOp, ResolvedBoundaryOp>(operation))
      continue;
    if (llvm::isa<InterfaceAbiOp>(operation)) {
      if (failed(abi(&operation)))
        return failure();
    } else if (named(&operation, "nodal.interface_connect") ||
               named(&operation, "nodal.interface_drive") ||
               named(&operation, "nodal.interface_observe")) {
      if (failed(effect(&operation)))
        return failure();
    } else if (llvm::isa<NetReadOp, NetDriverOp, NetDriveOp>(operation) ||
               newOperation(&operation)) {
      if (failed(resolvedEffect(&operation)))
        return failure();
    }
  }
  for (auto &[operation, instance] : instances)
    if (!instance.childEndpoint && !seenAbi.contains(operation))
      return reject(operation,
                    "fully typed Interface instance requires its exact structural ABI projection");
  for (auto &item : driverIdentities) {
    Operation *driver = item.second;
    if (!usedDrivers.contains(driver->getResult(0)))
      return reject(driver,
                    "authored resolved driver must have exactly one conditional drive or release");
  }
  // Domain ownership is a property of the entire resolved component. A
  // standalone net must not erase a constraint between two Interface leaves.
  for (auto &item : accesses) {
    auto index = netIndices.find(item.first);
    if (index == netIndices.end())
      continue;
    // A proven external declaration adds an opaque electrical contribution,
    // not a domain. Retain every ordinary constraint across the entire union.
    if (unconstrainedExternal(*item.second.instance))
      continue;
    unsigned root = representative(index->second);
    auto [known, inserted] = componentDomains.try_emplace(root, item.second.instance->domain);
    if (!inserted && known->second != item.second.instance->domain)
      return reject(item.first.getDefiningOp(),
                    "resolved pass-through component crosses exact Interface domain ownership");
  }
  llvm::DenseMap<unsigned, SmallVector<Operation *, 2>> possibleDrivers;
  for (auto [index, operation] : conditionalDrivers)
    possibleDrivers[representative(index)].push_back(operation);
  for (auto [index, operation] : splits) {
    auto found = possibleDrivers.find(representative(index));
    if (operation->hasAttr("hierarchy_driver")) {
      // This is an unresolved obligation, never an external driver. The mandatory
      // closed-root proof must establish an actual joined boundary and driver.
      if (found != possibleDrivers.end() && !found->second.empty())
        return reject(operation, "hierarchy-driver split must have no local authored value driver");
      continue;
    }
    if (found == possibleDrivers.end() || found->second.size() != 1)
      return reject(
          operation,
          "split requires exactly one authored conditional driver after pass-through resolution");
    Operation *driver = found->second.front();
    if (!llvm::isa<NetDriveOp>(driver) && !named(driver, "nodal.net_drive_level"))
      return reject(operation,
                    "split cannot invent an enable for an unconditional Interface drive");
  }
  // Analog rights stay with this same complete selected-role model. They do not
  // enter the digital driver graph or resolved union. Inspect each represented
  // use once, including nested contribution regions.
  if (llvm::none_of(accesses, [](const auto &entry) {
        return entry.second.member->protocol == "conservative";
      }))
    return success();
  unsigned analogWork = 0;
  WalkResult analogUses = module->walk<WalkOrder::PreOrder>([&](Operation *operation) {
    if (operation != module && llvm::isa<nodal::ModuleOp>(operation))
      return WalkResult::skip();
    if (++analogWork > digitalTypeMaximumNodes) {
      (void)reject(operation, "conservative Interface use inventory exceeds finite work budget");
      return WalkResult::interrupt();
    }
    auto requireAccess = [&](Value value, StringRef permission) -> LogicalResult {
      if (++analogWork > digitalTypeMaximumNodes)
        return reject(operation, "conservative Interface operands exceed finite work budget");
      auto found = accesses.find(value);
      if (found == accesses.end())
        return success();
      if (found->second.member->protocol != "conservative" ||
          found->second.entry->access != permission)
        return reject(operation,
                      "conservative Interface use lacks its actual selected-role permission");
      return success();
    };
    if (llvm::isa<ConnectOp, ConnectArrayOp, AliasOp, ReferenceOp>(operation)) {
      for (Value operand : operation->getOperands())
        if (failed(requireAccess(operand, "connect")))
          return WalkResult::interrupt();
    } else if (llvm::isa<ContributeOp>(operation) && operation->getNumOperands()) {
      Operation *branch = operation->getOperand(0).getDefiningOp();
      if (llvm::isa_and_nonnull<BranchOp>(branch))
        for (Value operand : branch->getOperands())
          if (failed(requireAccess(operand, "contribute")))
            return WalkResult::interrupt();
    }
    return WalkResult::advance();
  });
  return failure(analogUses.wasInterrupted());
}
llvm::DenseMap<unsigned, Value> Model::resolvedComponentValues() {
  llvm::DenseMap<unsigned, Value> components;
  // Both logical endpoint and dependency consumers use the same authored-order
  // representative, independent of union-find rank or pointer/hash order.
  for (Operation &operation : llvm::make_pointee_range(lexicalOperations))
    for (Value value : operation.getResults()) {
      auto net = netIndices.find(value);
      if (net != netIndices.end())
        components.try_emplace(representative(net->second), value);
    }
  return components;
}
LogicalResult Model::endpoints(SmallVectorImpl<DigitalInterfaceEndpoint> &result) {
  auto components = resolvedComponentValues();
  std::map<std::pair<Operation *, std::string>, Value> resolvedMembers;
  for (const auto &access : accesses) {
    auto net = netIndices.find(access.first);
    if (net != netIndices.end())
      resolvedMembers.try_emplace(
          std::make_pair(access.second.instance->operation, access.second.path.str()),
          components.lookup(representative(net->second)));
  }
  SmallVector<DigitalInterfaceEndpoint> collected;
  for (Operation &operation : llvm::make_pointee_range(lexicalOperations)) {
    auto found = instances.find(&operation);
    if (found == instances.end())
      continue;
    const Instance &instance = found->second;
    if (++work > digitalTypeMaximumNodes)
      return reject(&operation, "Interface endpoint projection exceeds its structural budget");
    Operation *declaration = instance.childEndpoint ? instance.childEndpoint : &operation;
    DigitalInterfaceEndpoint endpoint;
    endpoint.operation = &operation;
    endpoint.declaration = declaration;
    endpoint.declarationOwner = interfaceModule(declaration);
    endpoint.domainOwner = module;
    endpoint.instance = instance.childInstance;
    endpoint.definition = instance.definition->operation;
    endpoint.selectedRole = instance.role->operation;
    endpoint.semanticIdentity =
        metadata(endpoint.definition).getAs<StringAttr>("semantic_identity");
    endpoint.domain = instance.domain;
    endpoint.dimensions = metadata(declaration).getAs<ArrayAttr>("dimensions");
    endpoint.selection = instance.selection;
    for (Operation &memberOperation : endpoint.definition->getRegion(0).front()) {
      if (!llvm::isa<InterfaceMemberOp>(memberOperation))
        continue;
      if (++work > digitalTypeMaximumNodes)
        return reject(&operation, "Interface leaf projection exceeds its structural budget");
      auto member = instance.definition->members.find(symbol(&memberOperation));
      const Entry &entry = instance.role->entries.find(member->getKey())->second;
      DigitalInterfaceLeaf leaf = leafFacts(member->second, entry);
      auto component = resolvedMembers.find(std::make_pair(&operation, leaf.path.getValue().str()));
      if (component != resolvedMembers.end())
        leaf.resolvedComponent = component->second;
      endpoint.leaves.push_back(std::move(leaf));
    }
    collected.push_back(std::move(endpoint));
  }
  result.append(std::make_move_iterator(collected.begin()),
                std::make_move_iterator(collected.end()));
  return success();
}
FailureOr<SmallVector<DigitalInterfaceTemplateDriver, 4>>
Model::templateDrivers(ArrayRef<std::pair<Value, Operation *>> seeds) {
  if (!isa_and_nonnull<nodal::ModuleOp>(module) || seeds.size() > digitalTypeMaximumNodes ||
      failed(verify()))
    return failure();
  SmallVector<DigitalInterfaceTemplateDriver, 4> result;
  DenseMap<std::pair<Value, Operation *>, unsigned> memo;
  DenseSet<Operation *> checkedFamilies;
  auto charge = [&]() { return ++work <= digitalTypeMaximumNodes; };
  auto ancestry = [&](Operation *op) -> FailureOr<SmallVector<Region *, 4>> {
    SmallVector<Region *, 4> arms;
    unsigned depth = 0;
    for (; op && op != module; op = op->getParentOp()) {
      if (!charge() || ++depth > digitalTypeMaximumDepth)
        return failure();
      if (isa_and_nonnull<StructuralIfOp>(op->getParentOp()))
        arms.push_back(op->getParentRegion());
    }
    return op == module ? FailureOr<SmallVector<Region *, 4>>(std::move(arms))
                        : FailureOr<SmallVector<Region *, 4>>(failure());
  };
  for (auto seed : seeds) {
    Value value = seed.first;
    Operation *use = seed.second;
    if (!charge() || !value || !use)
      return failure();
    if (auto known = memo.find(seed); known != memo.end()) {
      result.push_back(result[known->second]);
      continue;
    }
    auto refuse =
        [&](StringRef reason) -> FailureOr<SmallVector<DigitalInterfaceTemplateDriver, 4>> {
      (void)reject(use, reason);
      return failure();
    };
    auto found = accesses.find(value);
    Operation *root = value.getDefiningOp();
    Operation *family = root ? root->getParentOp() : nullptr;
    if (found == accesses.end() || !isa_and_nonnull<GenerateOp>(family) ||
        use->getParentOp() != family || family->getNumRegions() != 1 ||
        !family->getRegion(0).hasOneBlock() || family->getRegion(0).front().empty() ||
        use != &family->getRegion(0).front().back())
      return refuse(
          "authored Interface driver requires its original whole root and final body use");
    const Access &access = found->second;
    if (access.instance->selection || access.instance->childInstance ||
        access.instance->childEndpoint || access.instance->operation->getParentOp() != family ||
        access.entry->access != "out" || !isDigitalStorageType(access.member->type))
      return refuse("authored Interface driver requires an original ordinary local output");
    if (checkedFamilies.insert(family).second) {
      auto inventory = family->walk(
          [&](Operation *) { return charge() ? WalkResult::advance() : WalkResult::interrupt(); });
      if (inventory.wasInterrupted() || failed(verifyDigitalGenerateBody(family)))
        return refuse("authored Interface driver requires a valid complete generated body");
    }
    auto read = resolveStaticShapeProjection(value, use);
    if (failed(read) || read->root != value || !read->projections.empty())
      return refuse("authored Interface driver cannot transport a partial projection");
    auto bucket = ordinaryDrivers.find(physicalMember(access));
    if (bucket == ordinaryDrivers.end() || bucket->second.size() != 1)
      return refuse("authored Interface driver requires exactly one original whole writer");
    const OrdinaryDriver &driver = bucket->second.front();
    auto writer = accesses.find(driver.target);
    if (!charge() || writer == accesses.end() || writer->second.instance->selection ||
        writer->second.instance->childEndpoint || driver.operation->getParentOp() != family ||
        driver.target.getDefiningOp()->getParentOp() != family)
      return refuse("authored Interface driver cannot transport a selected or foreign writer");
    auto written = resolveStaticShapeProjection(driver.target, driver.operation);
    if (failed(written) || !written->projections.empty() ||
        proveStaticShapeProjectionIdentity(*read, *written, use) != ShapeRegionProofStatus::Proved)
      return refuse("authored Interface driver requires exact whole physical identity");
    auto useArms = ancestry(use), rootArms = ancestry(root), writeArms = ancestry(driver.operation);
    if (failed(useArms) || failed(rootArms) || failed(writeArms) || *useArms != *rootArms ||
        *useArms != *writeArms)
      return refuse(
          "authored Interface driver requires the exact original structural arm ancestry");
    memo.try_emplace(seed, result.size());
    result.push_back({value, driver.source, driver.operation, family});
  }
  return result;
}
LogicalResult Model::memberAccesses(SmallVectorImpl<DigitalInterfaceAccess> &result) {
  SmallVector<DigitalInterfaceAccess> collected;
  auto components = resolvedComponentValues();
  std::map<std::pair<Operation *, std::string>, Value> canonical;
  for (Operation &operation : llvm::make_pointee_range(lexicalOperations)) {
    if (!llvm::isa<MemberAccessOp>(operation) || operation.getNumResults() != 1)
      continue;
    auto found = accesses.find(operation.getResult(0));
    if (found == accesses.end())
      continue;
    if (++work > digitalTypeMaximumNodes)
      return reject(&operation, "Interface access query exceeds its structural budget");
    const Access &access = found->second;
    const Instance &instance = *access.instance;
    Operation *declaration = instance.childEndpoint ? instance.childEndpoint : instance.operation;
    auto [first, inserted] = canonical.try_emplace(
        std::make_pair(instance.operation, access.path.str()), operation.getResult(0));
    DigitalInterfaceAccess value;
    value.value = operation.getResult(0);
    value.canonicalValue = first->second;
    value.projection = instance.operation;
    value.identity = DigitalInterfaceMember{declaration,
                                            instance.childInstance,
                                            interfaceModule(declaration),
                                            operation.getAttrOfType<StringAttr>("path"),
                                            access.entry->dictionary.getAs<StringAttr>("access"),
                                            instance.selection};
    value.leaf.declaration = access.member->operation;
    value.leaf.path = value.identity.path;
    value.leaf.type = access.member->type;
    value.leaf.protocol = access.member->operation->getAttrOfType<StringAttr>("protocol");
    value.leaf.role = access.entry->dictionary.getAs<StringAttr>("leaf_role");
    value.leaf.access = value.identity.access;
    value.leaf.nestedRoles = access.entry->nestedRoles;
    value.leaf.nestedInterfaces = access.entry->nestedInterfaces;
    value.leaf.analogMetadata = access.member->analogMetadata;
    value.leaf.analogReferences = access.member->analogReferences;
    auto net = netIndices.find(value.value);
    if (net != netIndices.end())
      value.leaf.resolvedComponent = components.lookup(representative(net->second));
    collected.push_back(std::move(value));
  }
  result.append(std::make_move_iterator(collected.begin()),
                std::make_move_iterator(collected.end()));
  return success();
}
LogicalResult Model::topology(DigitalResolvedTopology &result) {
  DigitalResolvedTopology collected;
  // Query-local geometry is proved once per original endpoint, independently of
  // how many members/role aliases use it. Payload is never part of these axes.
  llvm::DenseMap<Operation *, DigitalResolvedShape> shapes;
  auto shape = [&](Operation *owner) -> FailureOr<const DigitalResolvedShape *> {
    auto found = shapes.find(owner);
    if (found != shapes.end())
      return &found->second;
    if (!owner || owner->getParentOp() != module ||
        !llvm::isa<InterfaceInstanceOp, InterfaceBoundaryOp, ResolvedNetOp, ResolvedBoundaryOp>(
            owner)) {
      (void)reject(owner ? owner : module,
                   "resolved family geometry requires its actual local endpoint owner");
      return failure();
    }
    auto dimensions = metadata(owner).getAs<ArrayAttr>("dimensions");
    if (!dimensions)
      dimensions = ArrayAttr::get(module->getContext(), {});
    if (dimensions.size() > digitalTypeMaximumDepth ||
        (llvm::isa<ResolvedNetOp, ResolvedBoundaryOp>(owner) && !dimensions.empty())) {
      (void)reject(owner, "resolved family geometry requires bounded original outer axes");
      return failure();
    }
    DigitalResolvedShape result{owner, dimensions, {}};
    for (Attribute attribute : dimensions) {
      if (++work > digitalTypeMaximumNodes) {
        (void)reject(owner, "resolved family geometry exceeds its structural budget");
        return failure();
      }
      auto token = llvm::dyn_cast<StringAttr>(attribute);
      if (!token || token.getValue().contains(',')) {
        (void)reject(owner, "resolved family geometry requires canonical ordered axis identities");
        return failure();
      }
      auto axis = resolveStaticShapeDimension(module, token.getValue());
      if (failed(axis)) {
        (void)reject(owner, "resolved family axis lost its exact owned Shape proof");
        return failure();
      }
      result.axes.push_back(*axis);
    }
    return &shapes.try_emplace(owner, std::move(result)).first->second;
  };
  auto components = resolvedComponentValues();
  llvm::DenseMap<unsigned, unsigned> positions;
  for (Operation &operation : llvm::make_pointee_range(lexicalOperations))
    for (Value value : operation.getResults()) {
      auto net = netIndices.find(value);
      if (net == netIndices.end())
        continue;
      unsigned root = representative(net->second);
      if (components.lookup(root) != value)
        continue;
      positions.try_emplace(root, collected.components.size());
      auto access = accesses.find(value);
      Operation *owner = access == accesses.end() ? &operation : access->second.instance->operation;
      auto geometry = shape(owner);
      if (failed(geometry))
        return failure();
      collected.components.push_back(
          {value, componentDomains.lookup(root), {}, {}, {}, {}, **geometry});
    }
  if (module->hasAttr("external_name"))
    for (Operation &operation : llvm::make_pointee_range(lexicalOperations))
      if (llvm::isa<ResolvedNetOp>(operation)) {
        auto index = netIndices.find(operation.getResult(0));
        if (index != netIndices.end())
          collected.components[positions.lookup(representative(index->second))]
              .externalContributions.push_back(&operation);
      }
  for (auto [index, operation] : conditionalDrivers)
    collected.components[positions.lookup(representative(index))].drivers.push_back(operation);
  for (auto [index, operation] : splits)
    collected.components[positions.lookup(representative(index))].splits.push_back(operation);
  std::map<std::pair<Operation *, std::string>, Value> resolvedMembers;
  for (Operation &operation : llvm::make_pointee_range(lexicalOperations)) {
    for (Value value : operation.getResults()) {
      auto net = netIndices.find(value);
      if (net == netIndices.end())
        continue;
      unsigned root = representative(net->second);
      collected.components[positions.lookup(root)].values.push_back(value);
      auto access = accesses.find(value);
      if (access != accesses.end())
        resolvedMembers.try_emplace(
            std::make_pair(access->second.instance->operation, access->second.path.str()),
            components.lookup(root));
    }
    if (llvm::isa<NetReadOp>(operation) && marked(&operation)) {
      unsigned root = representative(netIndices.lookup(operation.getOperand(0)));
      collected.components[positions.lookup(root)].values.push_back(operation.getResult(0));
    }
    if (llvm::isa<ResolvedNetOp, ResolvedBoundaryOp>(operation) && marked(&operation)) {
      if (++work > digitalTypeMaximumNodes)
        return reject(&operation, "resolved net endpoint slots exceed their structural budget");
      auto boundary = netBoundaries.find(&operation);
      auto index = netIndices.find(operation.getResult(0));
      if (index == netIndices.end())
        return reject(&operation, "resolved endpoint has no existing component identity");
      auto geometry = shape(&operation);
      if (failed(geometry))
        return failure();
      collected.endpoints.push_back(
          {&operation,
           boundary == netBoundaries.end() ? nullptr : boundary->second.first,
           boundary == netBoundaries.end() ? &operation : boundary->second.second,
           StringAttr::get(module->getContext(), ""),
           {},
           components.lookup(representative(index->second)),
           operation.getResult(0).getType(),
           StringAttr::get(
               module->getContext(),
               llvm::cast<ResolvedType>(operation.getResult(0).getType()).getDriveMode()),
           metadata(&operation).getAs<StringAttr>("profile"),
           metadata(&operation).getAs<StringAttr>("placement"),
           **geometry});
    }
    auto found = instances.find(&operation);
    if (found == instances.end())
      continue;
    const Instance &instance = found->second;
    for (Operation &memberOperation : instance.definition->operation->getRegion(0).front()) {
      if (!llvm::isa<InterfaceMemberOp>(memberOperation) ||
          text(&memberOperation, "protocol") != "resolved")
        continue;
      if (++work > digitalTypeMaximumNodes)
        return reject(&operation, "resolved endpoint slots exceed their structural budget");
      auto geometry = shape(&operation);
      if (failed(geometry))
        return failure();
      StringAttr path = memberOperation.getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName());
      auto represented = resolvedMembers.find({&operation, path.getValue().str()});
      // Member accesses may be authored after their declaration. Populate the
      // represented component below, after the single authored-order inventory.
      collected.endpoints.push_back(
          {&operation, instance.childInstance,
           instance.childEndpoint ? instance.childEndpoint : &operation, path, instance.domain,
           represented == resolvedMembers.end() ? Value() : represented->second,
           instance.childInstance ? instance.actualTypes.lookup(path.getValue())
                                  : memberOperation.getAttrOfType<TypeAttr>("type").getValue(),
           StringAttr::get(
               module->getContext(),
               llvm::cast<ResolvedType>(memberOperation.getAttrOfType<TypeAttr>("type").getValue())
                   .getDriveMode()),
           StringAttr::get(module->getContext(), "portable_boundary_only"),
           metadata(instance.childEndpoint ? instance.childEndpoint : &operation)
               .getAs<StringAttr>("placement"),
           **geometry, module->hasAttr("external_name") ? &operation : nullptr});
    }
  }
  for (auto &endpoint : collected.endpoints) {
    if (llvm::isa<ResolvedNetOp, ResolvedBoundaryOp>(endpoint.operation))
      continue;
    auto represented = resolvedMembers.find({endpoint.operation, endpoint.path.getValue().str()});
    if (represented != resolvedMembers.end())
      endpoint.component = represented->second;
  }
  result = std::move(collected);
  return success();
}
LogicalResult Model::dependencies(SmallVectorImpl<DigitalInterfaceValueDependencies> &result,
                                  SmallVectorImpl<DigitalInterfaceObservationGroup> *observations) {
  SmallVector<DigitalInterfaceValueDependencies> collected;
  llvm::DenseMap<Value, unsigned> positions;
  std::map<std::pair<Operation *, std::string>, Value> members;
  auto components = resolvedComponentValues();
  auto add = [&](Value value) -> DigitalInterfaceValueDependencies & {
    auto [entry, inserted] = positions.try_emplace(value, collected.size());
    if (inserted)
      collected.push_back({value, {}, {}});
    return collected[entry->second];
  };
  // Choose representatives in authored source order, never hash iteration order.
  // Aliases point one way to their canonical value; pass-through alone is not a
  // combinational feedback edge and must not manufacture a two-node cycle.
  for (Operation &operation : llvm::make_pointee_range(lexicalOperations)) {
    for (Value value : operation.getResults()) {
      auto net = netIndices.find(value);
      if (net != netIndices.end()) {
        Value canonical = components.lookup(representative(net->second));
        auto &entry = add(value);
        entry.domain = componentDomains.lookup(representative(net->second));
        entry.externalContribution =
            module->hasAttr("external_name") && llvm::isa<ResolvedNetOp>(operation);
        if (canonical != value)
          entry.sources.emplace_back(canonical, &operation);
        continue;
      }
      auto access = accesses.find(value);
      if (access == accesses.end() || analog(access->second.member->protocol))
        continue;
      auto key = std::make_pair(access->second.instance->operation, access->second.path.str());
      auto [known, inserted] = members.try_emplace(key, value);
      auto &entry = add(value);
      entry.domain = access->second.instance->domain;
      if (!inserted)
        entry.sources.emplace_back(known->second, &operation);
    }
  }
  // Keep the existing lexical aliases one-way. Physical writer buckets use the
  // original declaration, while selected reads retain their own actual SSA root
  // so Assignment can carry its checked context into template-local RHS edges.
  for (const auto &[unused, value] : members) {
    const Access &access = accesses.find(value)->second;
    auto found = ordinaryDrivers.find(physicalMember(access));
    if (found == ordinaryDrivers.end())
      continue;
    SmallVector<const OrdinaryDriver *, 2> matches;
    SmallVector<StaticShapeProjection, 2> writes;
    std::optional<StaticShapeProjection> read;
    bool sameProjection = false;
    for (const OrdinaryDriver &driver : found->second) {
      if (++work > digitalTypeMaximumNodes)
        return reject(value.getDefiningOp(),
                      "ordinary Interface dependency proof exceeds its finite relation budget");
      if (driver.projection == access.instance->operation) {
        matches.push_back(&driver);
        sameProjection = true;
        continue;
      }
      if (!read) {
        auto projection = resolveStaticShapeProjection(value, value.getDefiningOp());
        if (failed(projection))
          return reject(value.getDefiningOp(),
                        "selected Interface read requires an exact physical shape");
        read = *projection;
      }
      auto write = resolveStaticShapeProjection(driver.target, driver.operation);
      if (failed(write))
        return reject(driver.operation,
                      "selected Interface writer requires an exact physical shape");
      auto overlap = compareStaticShapeProjections(*read, *write, value.getDefiningOp());
      if (overlap.status != ShapeRegionProofStatus::Proved ||
          overlap.overlap == ShapeRegionOverlap::Unknown)
        return reject(value.getDefiningOp(), "selected Interface dependency overlap is unproved");
      if (overlap.overlap == ShapeRegionOverlap::Disjoint)
        continue;
      matches.push_back(&driver);
      writes.push_back(*write);
    }
    // An existing physical writer bucket establishes local drive ownership.
    // A different disjoint lane is not an external/domain-only source for this
    // read. Genuine external input roles have no local writer bucket.
    if (!sameProjection && (matches.empty() || verifyStaticShapeProjectionCoverage(
                                                   *read, writes, value.getDefiningOp()) !=
                                                   ShapeRegionProofStatus::Proved))
      return reject(value.getDefiningOp(),
                    "selected Interface drivers do not prove complete read coverage");
    for (const OrdinaryDriver *driver : matches)
      add(value).sources.emplace_back(driver->source, driver->operation);
  }
  for (auto [index, operation] : conditionalDrivers) {
    auto &entry = add(components.find(representative(index))->second);
    if (named(operation, "nodal.interface_drive")) {
      entry.sources.emplace_back(operation->getOperand(1), operation);
    } else {
      if (llvm::isa<NetDriveOp>(operation))
        entry.sources.emplace_back(operation->getOperand(2), operation);
      entry.sources.emplace_back(operation->getOperand(llvm::isa<NetDriveOp>(operation) ? 3 : 2),
                                 operation);
    }
  }
  for (Operation &operation : llvm::make_pointee_range(lexicalOperations)) {
    if (!marked(&operation))
      continue;
    if (llvm::isa<NetReadOp>(operation)) {
      auto &entry = add(operation.getResult(0));
      entry.domain =
          componentDomains.lookup(representative(netIndices.lookup(operation.getOperand(0))));
      entry.sources.emplace_back(operation.getOperand(0), &operation);
    }
  }
  llvm::DenseMap<unsigned, Operation *> componentDriver;
  for (auto [index, operation] : conditionalDrivers)
    componentDriver.try_emplace(representative(index), operation);
  for (auto [index, operation] : splits) {
    Operation *driver = componentDriver.lookup(representative(index));
    // A contextual split retains explicit empty obligations until the existing
    // occurrence proof binds both results to one actual conditional driver.
    if (!driver && operation->hasAttr("hierarchy_driver")) {
      add(operation->getResult(0));
      add(operation->getResult(1));
      continue;
    }
    // verify() proved exactly one conditional local driver.
    if (!driver)
      return reject(operation, "split dependency has no verified conditional driver");
    auto &data = add(operation->getResult(0));
    data.domain = componentDomains.lookup(representative(index));
    if (llvm::isa<NetDriveOp>(driver))
      data.sources.emplace_back(driver->getOperand(2), driver);
    auto &enable = add(operation->getResult(1));
    enable.domain = componentDomains.lookup(representative(index));
    enable.sources.emplace_back(driver->getOperand(llvm::isa<NetDriveOp>(driver) ? 3 : 2), driver);
  }
  uint64_t edges = 0;
  for (const auto &entry : collected)
    edges += entry.sources.size();
  if (collected.size() > digitalTypeMaximumNodes || edges > digitalTypeMaximumNodes)
    return reject(module, "typed dependency graph exceeds its finite represented-node limit");
  SmallVector<DigitalInterfaceObservationGroup> groups;
  if (observations) {
    // Reuse the exact canonical member/component maps above. Aliases are not
    // additional observations; distinct concrete endpoints are not one group.
    std::map<std::tuple<Operation *, bool, std::string>, unsigned> protocolGroups;
    for (const auto &entry : collected) {
      auto projected = accesses.find(entry.value);
      if (entry.sources.empty() &&
          (projected == accesses.end() || !projected->second.instance->childInstance ||
           projected->second.entry->access != "out"))
        continue;
      auto net = netIndices.find(entry.value);
      if (net != netIndices.end()) {
        if (components.find(representative(net->second))->second == entry.value)
          groups.push_back({entry.value.getDefiningOp(), entry.domain, {entry.value}});
        continue;
      }
      auto access = accesses.find(entry.value);
      if (access == accesses.end())
        continue;
      const Access &member = access->second;
      auto key = std::make_pair(member.instance->operation, member.path.str());
      if (members.find(key)->second != entry.value)
        continue;
      auto [prefix, suffix] = member.path.rsplit('.');
      bool forward = (member.member->protocol == "valid" || member.member->protocol == "stream") &&
                     (suffix == "payload" || suffix == "valid");
      auto groupKey = std::make_tuple(member.instance->operation, forward,
                                      (forward ? prefix : member.path).str());
      auto [group, inserted] = protocolGroups.try_emplace(groupKey, groups.size());
      if (inserted)
        groups.push_back({entry.value.getDefiningOp(), entry.domain, {}});
      groups[group->second].values.push_back(entry.value);
    }
  }
  result.append(std::make_move_iterator(collected.begin()),
                std::make_move_iterator(collected.end()));
  if (observations)
    observations->append(std::make_move_iterator(groups.begin()),
                         std::make_move_iterator(groups.end()));
  return success();
}

} // namespace

LogicalResult checkFixedConservativeInterfaceArrayProfile(
    const DigitalInterfaceEndpoint &endpoint, unsigned &remainingWork) {
  return fixedConservativeArrayProfile(endpoint, remainingWork);
}

FailureOr<DigitalInterfaceBoundary> inspectDigitalInterfaceBoundary(Operation *operation) {
  const unsigned operands = operation ? operation->getNumOperands() : 0;
  if (!llvm::isa_and_nonnull<InterfaceBoundaryOp>(operation) || failed(version(operation)) ||
      (operands != 0 && operands != 2) || failed(arity(operation, operands, 0)) ||
      !lexicalInterfaceOperation(operation) || !identity(symbol(operation)) ||
      !identity(text(metadata(operation), "semantic_path")) ||
      (operands == 0 && operation->hasAttr("member"))) {
    (void)reject(operation, "Interface boundary requires an exact typed lexical identity");
    return failure();
  }
  // A boundary is a lexical projection, not a SymbolTable declaration: guarded
  // arms have no SymbolTable trait. Preserve exact same-block name uniqueness
  // independently, including unaccessed projections of distinct physical lanes.
  auto self = FlatSymbolRefAttr::get(operation->getContext(), symbol(operation));
  if (localInterfaceSymbol(operation, self) != operation) {
    (void)reject(operation, "Interface boundary requires one unique exact lexical name");
    return failure();
  }
  Operation *selected = nullptr, *instance = nullptr, *childModule = nullptr;
  StringRef name = text(operation, "endpoint");
  if (operands == 2) {
    auto proof = inspectGeneratedSelection(operation->getOperand(0), operation->getOperand(1),
                                           text(operation, "member"), operation);
    if (failed(proof))
      return failure();
    auto target = inspectGeneratedExportSymbol(proof->exportOperation);
    if (failed(target))
      return failure();
    if (text(proof->exportOperation, "kind") == "interface") {
      Operation *endpoint = *target;
      Operation *owner = interfaceModule(endpoint);
      auto domain = metadata(endpoint).getAs<FlatSymbolRefAttr>("domain");
      Operation *declaration = domain ? SymbolTable::lookupSymbolIn(owner, domain) : nullptr;
      if (operation->hasAttr("instance") || name != symbol(endpoint) || !marked(endpoint) ||
          !llvm::isa_and_nonnull<InterfaceInstanceOp>(endpoint) ||
          !llvm::isa_and_nonnull<DomainOp, DomainRequirementOp>(declaration) ||
          declaration->getParentOp() != owner ||
          failed(verifyDigitalInterfaceOperation(endpoint))) {
        (void)reject(operation,
                     "selected Interface requires its exact exported endpoint and domain");
        return failure();
      }
      return DigitalInterfaceBoundary{nullptr, owner, endpoint, domain, operation};
    }
    auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("instance");
    if (text(proof->exportOperation, "kind") != "instance" ||
        !llvm::isa_and_nonnull<InstanceOp>(*target) || !reference ||
        reference.getValue() != symbol(*target) ||
        failed(verifyHierarchyInstanceOperation(*target))) {
      (void)reject(operation, "selected child Interface requires its exact exported instance");
      return failure();
    }
    instance = *target;
    childModule = resolveInstanceModuleDefinition(instance);
    selected = operation;
  } else {
    auto child = resolveHierarchyInstance(operation, operation->getAttr("instance"));
    if (failed(child))
      return failure();
    instance = child->instance;
    childModule = child->module;
  }
  Operation *endpoint =
      identity(name) && childModule ? SymbolTable::lookupSymbolIn(childModule, name) : nullptr;
  if (!llvm::isa_and_nonnull<InterfaceInstanceOp>(endpoint) || !marked(endpoint) ||
      endpoint->getParentOp() != childModule) {
    (void)reject(operation,
                 "Interface boundary requires its exact immediate child's direct endpoint");
    return failure();
  }
  if (childModule->hasAttr("external_name")) {
    if (selected || failed(verifyDigitalInterfaceOperation(endpoint)) ||
        metadata(operation).get("domain")) {
      (void)reject(operation,
                   "external Interface projection retains its domainless direct declaration");
      return failure();
    }
    return DigitalInterfaceBoundary{instance, childModule, endpoint, {}, nullptr};
  }
  auto requirement = metadata(endpoint).getAs<FlatSymbolRefAttr>("domain");
  Operation *required =
      requirement ? SymbolTable::lookupSymbolIn(childModule, requirement) : nullptr;
  if (!llvm::isa_and_nonnull<DomainRequirementOp>(required)) {
    (void)reject(operation,
                 "child Interface domain requires an actual instance requirement binding");
    return failure();
  }
  Operation *binding = nullptr;
  for (Operation &candidate : *instance->getBlock()) {
    auto reference = candidate.getAttrOfType<FlatSymbolRefAttr>("instance");
    if (!llvm::isa<InstanceDomainBindingOp>(candidate) || !reference ||
        reference.getValue() != symbol(instance) || candidate.getAttr("requirement") != requirement)
      continue;
    if (binding || failed(verifyInstanceDomainBinding(&candidate))) {
      (void)reject(operation, "child Interface domain requires one exact executable binding");
      return failure();
    }
    binding = &candidate;
  }
  if (!binding) {
    (void)reject(operation, "child Interface domain has no executable actual binding");
    return failure();
  }
  return DigitalInterfaceBoundary{instance, childModule, endpoint,
                                  binding->getAttrOfType<FlatSymbolRefAttr>("actual"), selected};
}

FailureOr<Operation *> resolveDigitalInterfaceDefinition(Operation *endpoint) {
  if (!llvm::isa_and_nonnull<InterfaceInstanceOp>(endpoint) || !marked(endpoint) ||
      failed(verifyDigitalInterfaceOperation(endpoint)))
    return failure();
  auto reference = endpoint->getAttrOfType<FlatSymbolRefAttr>("definition");
  unsigned work = 0;
  Operation *definition =
      reference ? lexicalInterfaceDefinition(endpoint, reference.getValue(), false, work) : nullptr;
  return definition && marked(definition) ? FailureOr<Operation *>(definition)
                                          : FailureOr<Operation *>(failure());
}

FailureOr<DigitalInterfaceMember> inspectDigitalInterfaceMember(Operation *operation) {
  if (!llvm::isa_and_nonnull<MemberAccessOp>(operation) || !marked(operation))
    return failure();
  auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("instance");
  if (failed(verifyDigitalInterfaceOperation(operation)))
    return failure();
  Operation *endpoint = localInterfaceSymbol(operation, reference);
  Operation *instance = nullptr, *owner = interfaceModule(operation), *selection = nullptr;
  if (llvm::isa_and_nonnull<InterfaceBoundaryOp>(endpoint)) {
    auto boundary = inspectDigitalInterfaceBoundary(endpoint);
    if (failed(boundary))
      return failure();
    endpoint = boundary->endpoint;
    instance = boundary->instance;
    owner = boundary->module;
    selection = boundary->selection;
  }
  if (!llvm::isa_and_nonnull<InterfaceInstanceOp>(endpoint) || !marked(endpoint))
    return failure();
  auto resolvedDefinition = resolveDigitalInterfaceDefinition(endpoint);
  Operation *definition = succeeded(resolvedDefinition) ? *resolvedDefinition : nullptr;
  // Occurrence substitution follows the actual endpoint, including monitor aliases.
  StringRef selected = text(endpoint, "role");
  auto path = operation->getAttrOfType<StringAttr>("path");
  if (!llvm::isa_and_nonnull<InterfaceOp>(definition) || !path)
    return failure();
  Operation *role = SymbolTable::lookupSymbolIn(definition, selected);
  auto entries = role ? metadata(role).getAs<ArrayAttr>("access") : ArrayAttr();
  if (!entries)
    return failure();
  for (Attribute entry : entries) {
    auto value = llvm::dyn_cast<DictionaryAttr>(entry);
    if (text(value, "path") == path.getValue())
      return DigitalInterfaceMember{
          endpoint, instance, owner, path, value.getAs<StringAttr>("access"), selection};
  }
  return failure();
}

bool hasDigitalInterfaceContract(Operation *operation) {
  if (marked(operation) || newOperation(operation))
    return true;
  return llvm::isa<InterfaceRoleOp, InterfaceMemberOp>(operation) && operation->getParentOp() &&
         marked(operation->getParentOp());
}
LogicalResult verifyDigitalInterfaceOperation(Operation *operation) {
  if (failed(version(operation)))
    return failure();
  Operation *owner = operation->getParentOp();
  if (llvm::isa<InterfaceRoleOp, InterfaceMemberOp>(operation)) {
    if (!llvm::isa_and_nonnull<InterfaceOp>(owner) || !marked(owner))
      return reject(operation, "fully typed role/member requires its fully typed Interface owner");
  }
  if (llvm::isa<InterfaceRoleOp, InterfaceMemberOp>(operation)) {
    if (!lexicalInterfaceOperation(owner))
      return reject(operation, "typed Interface member requires its exact lexical definition");
  } else if (!lexicalInterfaceOperation(operation)) {
    return reject(operation,
                  "fully typed Interface operation requires exact lexical structural ownership");
  }
  return success();
}
FailureOr<SmallVector<DigitalInterfaceTemplateDriver, 4>>
inspectGeneratedInterfaceTemplateDrivers(Operation *module,
                                         ArrayRef<std::pair<Value, Operation *>> seeds) {
  return Model(module).templateDrivers(seeds);
}
LogicalResult verifyDigitalInterfaceModel(Operation *module) { return Model(module).verify(); }
LogicalResult
collectDigitalInterfaceEndpoints(Operation *module,
                                 SmallVectorImpl<DigitalInterfaceEndpoint> &endpoints) {
  return collectDigitalInterfaceEndpoints(module, endpoints, nullptr);
}
LogicalResult collectDigitalInterfaceEndpoints(Operation *module,
                                               SmallVectorImpl<DigitalInterfaceEndpoint> &endpoints,
                                               SmallVectorImpl<DigitalInterfaceAccess> *accesses) {
  unsigned work = 0;
  return collectDigitalInterfaceEndpoints(module, endpoints, accesses, work);
}
LogicalResult collectDigitalInterfaceEndpoints(Operation *module,
                                               SmallVectorImpl<DigitalInterfaceEndpoint> &endpoints,
                                               SmallVectorImpl<DigitalInterfaceAccess> *accesses,
                                               unsigned &work) {
  if (work > digitalTypeMaximumNodes)
    return failure();
  Model model(module, work);
  SmallVector<DigitalInterfaceEndpoint> collectedEndpoints;
  SmallVector<DigitalInterfaceAccess> collectedAccesses;
  if (failed(model.verify()) || failed(model.endpoints(collectedEndpoints)) ||
      (accesses && failed(model.memberAccesses(collectedAccesses))))
    return failure();
  work = model.consumedWork();
  endpoints.append(std::make_move_iterator(collectedEndpoints.begin()),
                   std::make_move_iterator(collectedEndpoints.end()));
  if (accesses)
    accesses->append(std::make_move_iterator(collectedAccesses.begin()),
                     std::make_move_iterator(collectedAccesses.end()));
  return success();
}
FailureOr<DigitalInterfaceAccess> resolveDigitalInterfaceAccess(Value value) {
  Operation *operation = value ? value.getDefiningOp() : nullptr;
  if (!llvm::isa_and_nonnull<MemberAccessOp>(operation))
    return failure();
  Model model(interfaceModule(operation));
  SmallVector<DigitalInterfaceAccess> accesses;
  if (failed(model.verify()) || failed(model.memberAccesses(accesses)))
    return failure();
  for (const auto &access : accesses)
    if (access.value == value)
      return access;
  return failure();
}
LogicalResult collectDigitalInterfaceDependencies(
    Operation *module, SmallVectorImpl<DigitalInterfaceValueDependencies> &dependencies,
    SmallVectorImpl<DigitalInterfaceObservationGroup> *observations,
    DigitalResolvedTopology *topology) {
  Model model(module);
  DigitalResolvedTopology collected;
  if (failed(model.verify()) || (topology && failed(model.topology(collected))) ||
      failed(model.dependencies(dependencies, observations)))
    return failure();
  if (topology)
    *topology = std::move(collected);
  return success();
}
} // namespace nodal
