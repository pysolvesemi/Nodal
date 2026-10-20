#include "nodal/Dialect/Nodal/DigitalInterfaceModel.h"

#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/NatureDiscipline.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringSet.h"

#include <limits>
#include <map>
#include <memory>
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
         named(operation, "nodal.net_split") || named(operation, "nodal.interface_boundary");
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
};
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
  explicit Model(Operation *module) : module(module) {}
  LogicalResult verify();
  LogicalResult endpoints(SmallVectorImpl<DigitalInterfaceEndpoint> &result);
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
  LogicalResult addOrdinaryDriver(const Access &access, Operation *operation);

  Operation *module;
  unsigned work = 0;
  std::map<Operation *, std::unique_ptr<Definition>> definitions;
  llvm::StringMap<Definition *> semanticDefinitions;
  llvm::StringMap<Operation *> semanticDefinitionInstances;
  llvm::StringMap<llvm::StringMap<const Role *>> semanticRoles;
  std::map<Operation *, Instance> instances;
  std::map<Operation *, std::unique_ptr<Model>> childDefinitions;
  std::set<std::pair<Operation *, Operation *>> boundaryIdentities;
  std::set<std::pair<Operation *, Operation *>> comparedDefinitions;
  llvm::DenseMap<Value, Access> accesses;
  llvm::DenseMap<Value, unsigned> netIndices;
  SmallVector<Net> nets;
  llvm::DenseMap<unsigned, FlatSymbolRefAttr> componentDomains;
  SmallVector<unsigned> parents, ranks;
  llvm::StringMap<Operation *> driverIdentities;
  llvm::DenseSet<Value> usedDrivers;
  llvm::DenseSet<Operation *> seenAbi;
  std::map<std::pair<Operation *, std::string>, Operation *> ordinaryDrivers;
  SmallVector<std::pair<unsigned, Operation *>> conditionalDrivers;
  SmallVector<std::pair<unsigned, Operation *>> splits;
};

LogicalResult Model::ownership(Operation *operation) {
  if (failed(version(operation)))
    return failure();
  if (++work > digitalTypeMaximumNodes)
    return reject(operation, "typed connectivity exceeds its finite represented-node limit");
  if (operation->getParentOp() != module)
    return reject(operation, "typed connectivity requires direct ownership by one module");
  return success();
}
LogicalResult Model::localValue(Value value, Operation *use) {
  Operation *producer = value.getDefiningOp();
  if (!producer || producer->getParentOp() != module || producer == use)
    return reject(use, "connectivity operands must be actual SSA values owned by the same module");
  return success();
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
            failed(inferDigitalStorageMaximumBits(resolved.getElementType(), module)))
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
                 failed(inferDigitalStorageMaximumBits(value, module))) {
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
  if (!semanticDefinitions.try_emplace(result->semanticIdentity, result.get()).second)
    return reject(operation, "one module cannot redeclare an Interface semantic identity");
  auto &canonicalRoles = semanticRoles[result->semanticIdentity];
  for (const auto &role : result->roles)
    canonicalRoles.try_emplace(role.getKey(), &role.second);
  definitions.emplace(operation, std::move(result));
  return success();
}

LogicalResult Model::nestedDefinitions(Definition &definition) {
  for (auto &roleItem : definition.roles) {
    Role &role = roleItem.second;
    llvm::StringMap<unsigned> prefixCounts;
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
        auto known = semanticDefinitions.find(name);
        if (known == semanticDefinitions.end())
          return reject(role.operation, "nested Interface identity has no actual owned definition");
        Definition &child = *known->second;
        auto childRole = child.roles.find(roleName);
        StringRef suffix = item.getKey().drop_front(prefix.size() + 1);
        auto member = child.members.find(suffix);
        if (childRole == child.roles.end() || member == child.members.end())
          return reject(role.operation,
                        "nested role/member does not resolve in its actual Interface");
        Entry &childEntry = childRole->second.entries.find(suffix)->second;
        Member &parentMember = definition.members.find(item.getKey())->second;
        if (member->second.type != parentMember.type ||
            member->second.protocol != parentMember.protocol ||
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
        if (checked.insert(prefix).second &&
            prefixCounts[prefix] !=
                semanticDefinitions.find(text(reference, "definition"))->second->members.size())
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
  Operation *target = reference ? SymbolTable::lookupSymbolIn(module, reference) : nullptr;
  auto found = definitions.find(target);
  if (!identity(symbol(operation)) || found == definitions.end())
    return reject(operation,
                  "typed Interface instance requires an actual fully typed local definition");
  Definition &definition = *found->second;
  auto role = definition.roles.find(text(operation, "role"));
  auto dictionary = metadata(operation);
  auto domain = dictionary.getAs<FlatSymbolRefAttr>("domain");
  Operation *domainDeclaration = domain ? SymbolTable::lookupSymbolIn(module, domain) : nullptr;
  if (role == definition.roles.end() ||
      !llvm::isa_and_nonnull<DomainOp, DomainRequirementOp>(domainDeclaration) ||
      domainDeclaration->getParentOp() != module || !identity(text(dictionary, "semantic_path")))
    return reject(operation,
                  "Interface instance requires an actual role, local domain and semantic path");
  auto dimensions = dictionary.getAs<ArrayAttr>("dimensions");
  if (!dimensions || dimensions.size() > digitalTypeMaximumDepth)
    return reject(operation,
                  "Interface array requires bounded explicit ordered structural dimensions");
  std::string shape;
  for (Attribute attribute : dimensions) {
    auto value = llvm::dyn_cast<StringAttr>(attribute);
    if (!value || value.getValue().contains(',') || !identity(value.getValue()))
      return reject(operation, "Interface dimensions require individual canonical shape tokens");
    if (!shape.empty())
      shape += ',';
    shape += value.getValue().str();
  }
  if (!shape.empty()) {
    auto cardinality = inferStaticShapeMaximumCardinality(module, shape);
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
                      : inferDigitalStorageMaximumBits(type, module);
      if (failed(bits) || *bits > std::numeric_limits<int64_t>::max() / *cardinality)
        return reject(operation,
                      "Interface array leaf footprint exceeds finite metadata arithmetic");
    }
  }
  instances.emplace(operation, Instance{operation, &definition, &role->second, domain, shape});
  return success();
}

LogicalResult Model::boundary(Operation *operation) {
  if (failed(ownership(operation)) || failed(arity(operation, 0, 0)))
    return failure();
  auto boundary = inspectDigitalInterfaceBoundary(operation);
  if (failed(boundary))
    return failure();
  if (!boundaryIdentities.emplace(boundary->instance, boundary->endpoint).second)
    return reject(operation,
                  "duplicate child Interface projection must reuse its physical identity");
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
  const Instance &original = child->instances.find(boundary->endpoint)->second;
  // A semantic label is not an ABI proof. Compare each imported definition once
  // against the local canonical definition, including unaccessed members and
  // any identically named role maps; disjoint selected roles remain additive.
  for (const auto &[unused, imported] : child->definitions) {
    if (!comparedDefinitions.emplace(imported->operation, boundary->instance).second)
      continue;
    auto found = semanticDefinitions.find(imported->semanticIdentity);
    if (found == semanticDefinitions.end()) {
      semanticDefinitions.try_emplace(imported->semanticIdentity, imported.get());
      semanticDefinitionInstances.try_emplace(imported->semanticIdentity, boundary->instance);
    } else {
      const Definition &local = *found->second;
      if (local.members.size() != imported->members.size())
        return reject(operation,
                      "child Interface semantic identity changes its complete member set");
      for (const auto &member : imported->members) {
        if (++work > digitalTypeMaximumNodes)
          return reject(operation, "Interface boundary comparison exceeds its structural budget");
        auto known = local.members.find(member.getKey());
        if (known == local.members.end() ||
            ((analog(member.second.protocol) || member.second.protocol == "resolved" ||
              isDigitalControlBoundaryType(member.second.type))
                 ? known->second.type != member.second.type
                 : !equivalentDigitalStorageTypesAcrossInstances(
                       module, known->second.type,
                       semanticDefinitionInstances.lookup(imported->semanticIdentity),
                       member.second.type, boundary->instance)) ||
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
    auto &canonicalRoles = semanticRoles[imported->semanticIdentity];
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
      auto childExtent = resolveStaticShapeDimension(boundary->module, formal.getValue());
      auto parentExtent = resolveStaticShapeDimension(module, actual.getValue());
      if (failed(childExtent) || failed(parentExtent) ||
          failed(verifyInstanceShapeDimension(boundary->instance, *childExtent, *parentExtent)))
        return reject(operation,
                      "Interface boundary shape differs from exact finalized child binding");
      if (!shape.empty())
        shape += ',';
      shape += actual.getValue().str();
    }
    if (!shape.empty() && failed(inferStaticShapeMaximumCardinality(module, shape)))
      return reject(operation, "Interface boundary shape requires finite structural cardinality");
  }
  auto actualInventory = metadata(operation).getAs<ArrayAttr>("actual_types");
  if ((metadata(operation).get("actual_types") && !actualInventory) ||
      (actualInventory && actualInventory.size() != original.definition->members.size()))
    return reject(operation,
                  "Interface boundary actual types require the complete member inventory");
  llvm::StringMap<Type> actualTypes;
  unsigned memberIndex = 0;
  for (Operation &memberOperation : original.definition->operation->getRegion(0).front()) {
    if (!llvm::isa<InterfaceMemberOp>(memberOperation))
      continue;
    if (++work > digitalTypeMaximumNodes)
      return reject(operation, "Interface boundary actual types exceed their structural budget");
    StringRef path = symbol(&memberOperation);
    const Member &member = original.definition->members.find(path)->second;
    if (analog(member.protocol) || member.protocol == "resolved" ||
        isDigitalControlBoundaryType(member.type) ||
        failed(inferDigitalBitPatternMaximumBits(member.type, boundary->module)))
      return reject(operation, "child Interface projection requires ordinary digital data leaves");
    Type actual = member.type;
    if (actualInventory) {
      auto item = llvm::dyn_cast<DictionaryAttr>(actualInventory[memberIndex]);
      auto type = item ? item.getAs<TypeAttr>("type") : TypeAttr();
      if (!item || item.size() != 2 || text(item, "path") != path || !type)
        return reject(operation, "Interface boundary actual types change ordered member identity");
      actual = type.getValue();
    } else if (failed(inferDigitalBitPatternFixedBits(member.type, boundary->module))) {
      return reject(operation, "symbolic Interface boundary requires explicit actual member types");
    }
    ++memberIndex;
    if (!equivalentInstanceDigitalStorageTypes(boundary->instance, member.type, actual))
      return reject(operation,
                    "Interface actual member type differs from exact finalized child binding");
    Type shaped =
        shape.empty() ? actual : nodal::ShapedType::get(module->getContext(), shape, actual);
    if (failed(inferDigitalBitPatternMaximumBits(shaped, module)))
      return reject(operation, "Interface actual payload requires finite owned bit-pattern shape");
    actualTypes.try_emplace(path, actual);
  }
  instances.emplace(operation,
                    Instance{operation, original.definition, original.role, boundary->domain, shape,
                             boundary->instance, boundary->endpoint, std::move(actualTypes)});
  return success();
}

LogicalResult Model::access(Operation *operation) {
  if (failed(ownership(operation)) || failed(arity(operation, 0, 1)))
    return failure();
  auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("instance");
  Operation *target = reference ? SymbolTable::lookupSymbolIn(module, reference) : nullptr;
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
  if (instance.childInstance && &role->second != instance.role)
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
  Type expected = instance.childInstance ? instance.actualTypes.lookup(path) : member->second.type;
  if (!instance.dimensions.empty())
    expected = nodal::ShapedType::get(module->getContext(), instance.dimensions, expected);
  if (operation->getResult(0).getType() != expected)
    return reject(operation,
                  "member SSA result must retain the exact declared type and ordered dimensions");
  accesses.try_emplace(
      operation->getResult(0),
      Access{&instance, &member->second, &role->second.entries.find(path)->second, path});
  if (member->second.protocol == "resolved" && instance.dimensions.empty()) {
    auto type = llvm::cast<ResolvedType>(expected);
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
  Operation *target = reference ? SymbolTable::lookupSymbolIn(module, reference) : nullptr;
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

LogicalResult Model::net(Operation *operation) {
  if (failed(ownership(operation)) || failed(arity(operation, 0, 1)))
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
  unsigned index = nets.size();
  netIndices.try_emplace(operation->getResult(0), index);
  nets.push_back(Net{operation->getResult(0), profile, type.getDriveMode()});
  parents.push_back(index);
  ranks.push_back(0);
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
LogicalResult Model::addOrdinaryDriver(const Access &access, Operation *operation) {
  if (!ordinaryDrivers
           .emplace(std::make_pair(access.instance->operation, access.path.str()), operation)
           .second)
    return reject(operation, "ordinary Interface member has multiple authored drivers");
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
    if (left.instance->childInstance ? permission != "in"
                                     : permission != "out" && permission != "drive")
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
              : equivalentDigitalStorageTypes(operation->getOperand(1).getType(), expected,
                                              module)) ||
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
      return addOrdinaryDriver(left, operation);
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
      left.instance->definition->semanticIdentity != right.instance->definition->semanticIdentity ||
      left.member->protocol != right.member->protocol ||
      left.entry->nestedInterfaces != right.entry->nestedInterfaces || left.path != right.path ||
      left.instance->domain != right.instance->domain ||
      ((resolved || isDigitalControlBoundaryType(operation->getOperand(0).getType()))
           ? operation->getOperand(0).getType() != operation->getOperand(1).getType()
           : !equivalentDigitalStorageTypes(operation->getOperand(0).getType(),
                                            operation->getOperand(1).getType(), module)))
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
  return addOrdinaryDriver(leftSink ? left : right, operation);
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
  auto type = llvm::dyn_cast<ResolvedType>(net.value.getType());
  if (!type)
    return reject(operation, "resolved operation requires the scalar resolved-net carrier");
  auto member = accesses.find(net.value);
  StringRef permission = member == accesses.end() ? StringRef() : member->second.entry->access;
  if (read || split) {
    if (permission == "drive")
      return reject(operation, "drive-only Interface capability cannot be read or split");
    if (operation->getResult(0).getType() != type.getElementType() ||
        (split && !boolean(operation->getResult(1).getType())))
      return reject(operation,
                    "net read/split must retain exact value and canonical Bool result types");
    if (split)
      splits.emplace_back(*index, operation);
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
        member->second.instance->domain != otherMember->second.instance->domain)
      return reject(operation, "Interface resolved pass-through requires exact domain identity");
    unite(*index, *other);
    return success();
  }
  if (net.mode == "read_only" ||
      (!permission.empty() && permission != "drive" && permission != "connect"))
    return reject(operation, "resolved endpoint does not permit creating or using a driver");
  if (driver) {
    auto driverType = llvm::dyn_cast<DriverType>(operation->getResult(0).getType());
    StringRef id = text(operation, "driver_id");
    if (!driverType || driverType.getElementType() != type.getElementType() || !identity(id) ||
        !driverIdentities.try_emplace(id, operation).second)
      return reject(operation,
                    "driver requires exact element type and unique owned authored identity");
    return success();
  }
  Value driverValue = operation->getOperand(1);
  Operation *declaration = driverValue.getDefiningOp();
  auto driverType = llvm::dyn_cast<DriverType>(driverValue.getType());
  if (!llvm::isa_and_nonnull<NetDriverOp>(declaration) || !marked(declaration) ||
      declaration->getParentOp() != module || declaration->getNumOperands() != 1 ||
      declaration->getNumResults() != 1 || declaration->getOperand(0) != net.value ||
      declaration->getResult(0) != driverValue || !driverType ||
      driverType.getElementType() != type.getElementType() ||
      !usedDrivers.insert(driverValue).second)
    return reject(operation,
                  "drive/release must use one actual unique driver of this exact owned net");
  if (release)
    return success();
  Value enable = operation->getOperand(conditional ? 3 : 2);
  if (!boolean(enable.getType()) || failed(localValue(enable, operation)))
    return reject(operation, "conditional driver enable requires an actual owned canonical Bool");
  if (conditional) {
    if ((net.mode != "push_pull" && net.mode != "open_source") ||
        text(operation, "mode") != net.mode ||
        !equivalentDigitalStorageTypes(operation->getOperand(2).getType(), type.getElementType(),
                                       module) ||
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
  auto &operations = module->getRegion(0).front();
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
    if ((marked(operation) || newOperation(operation)) &&
        (oldOperation(operation) || newOperation(operation)) &&
        operation->getParentOp() != module &&
        !llvm::isa<InterfaceRoleOp, InterfaceMemberOp>(operation)) {
      (void)reject(operation,
                   "typed connectivity declaration/access cannot escape direct module ownership");
      return WalkResult::interrupt();
    }
    return WalkResult::advance();
  });
  if (ownershipResult.wasInterrupted())
    return failure();
  for (Operation &operation : operations)
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
  for (Operation &operation : operations)
    if (llvm::isa<InterfaceInstanceOp>(operation) && marked(&operation))
      if (failed(instance(&operation)))
        return failure();
  for (Operation &operation : operations)
    if (llvm::isa<InterfaceBoundaryOp>(operation) && failed(boundary(&operation)))
      return failure();
  for (Operation &operation : operations) {
    if (llvm::isa<MemberAccessOp>(operation) && marked(&operation)) {
      if (failed(access(&operation)))
        return failure();
    } else if (llvm::isa<ResolvedNetOp>(operation) && marked(&operation)) {
      if (failed(net(&operation)))
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
  for (Operation &operation : operations) {
    if (!marked(&operation) && !newOperation(&operation)) {
      // No marker omission can bridge a legacy declaration/use into a typed
      // identity. Legacy standalone shells retain their predecessor contract.
      StringRef key = llvm::isa<InterfaceInstanceOp>(operation) ? "definition"
                      : llvm::isa<MemberAccessOp>(operation)    ? "instance"
                                                                : "";
      if (!key.empty()) {
        auto reference = operation.getAttrOfType<FlatSymbolRefAttr>(key);
        Operation *target = reference ? SymbolTable::lookupSymbolIn(module, reference) : nullptr;
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
                  ResolvedNetOp>(operation))
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
    if (!instance.childInstance && !seenAbi.contains(operation))
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
    if (found == possibleDrivers.end() || found->second.size() != 1)
      return reject(
          operation,
          "split requires exactly one authored conditional driver after pass-through resolution");
    Operation *driver = found->second.front();
    if (!llvm::isa<NetDriveOp>(driver) && !named(driver, "nodal.net_drive_level"))
      return reject(operation,
                    "split cannot invent an enable for an unconditional Interface drive");
  }
  return success();
}
llvm::DenseMap<unsigned, Value> Model::resolvedComponentValues() {
  llvm::DenseMap<unsigned, Value> components;
  // Both logical endpoint and dependency consumers use the same authored-order
  // representative, independent of union-find rank or pointer/hash order.
  for (Operation &operation : module->getRegion(0).front())
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
  for (Operation &operation : module->getRegion(0).front()) {
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
    endpoint.declarationOwner = declaration->getParentOp();
    endpoint.domainOwner = module;
    endpoint.instance = instance.childInstance;
    endpoint.definition = instance.definition->operation;
    endpoint.selectedRole = instance.role->operation;
    endpoint.semanticIdentity =
        metadata(endpoint.definition).getAs<StringAttr>("semantic_identity");
    endpoint.domain = instance.domain;
    endpoint.dimensions = metadata(declaration).getAs<ArrayAttr>("dimensions");
    for (Operation &memberOperation : endpoint.definition->getRegion(0).front()) {
      if (!llvm::isa<InterfaceMemberOp>(memberOperation))
        continue;
      if (++work > digitalTypeMaximumNodes)
        return reject(&operation, "Interface leaf projection exceeds its structural budget");
      auto member = instance.definition->members.find(symbol(&memberOperation));
      const Entry &entry = instance.role->entries.find(member->getKey())->second;
      DigitalInterfaceLeaf leaf;
      leaf.declaration = &memberOperation;
      leaf.path = memberOperation.getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName());
      leaf.type = member->second.type;
      leaf.protocol = memberOperation.getAttrOfType<StringAttr>("protocol");
      leaf.role = entry.dictionary.getAs<StringAttr>("leaf_role");
      leaf.access = entry.dictionary.getAs<StringAttr>("access");
      leaf.nestedRoles = entry.nestedRoles;
      leaf.nestedInterfaces = entry.nestedInterfaces;
      leaf.analogMetadata = member->second.analogMetadata;
      leaf.analogReferences = member->second.analogReferences;
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
  for (Operation &operation : module->getRegion(0).front()) {
    for (Value value : operation.getResults()) {
      auto net = netIndices.find(value);
      if (net != netIndices.end()) {
        Value canonical = components.lookup(representative(net->second));
        auto &entry = add(value);
        entry.domain = componentDomains.lookup(representative(net->second));
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
  for (const auto &[key, operation] : ordinaryDrivers) {
    auto known = members.find(key);
    if (known == members.end())
      return reject(operation, "ordinary dependency has no canonical actual member");
    Value source = operation->getOperand(1);
    if (named(operation, "nodal.interface_connect")) {
      const Access &left = accesses.find(operation->getOperand(0))->second;
      source = operation->getOperand(
          key == std::make_pair(left.instance->operation, left.path.str()) ? 1 : 0);
    }
    add(known->second).sources.emplace_back(source, operation);
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
  for (Operation &operation : module->getRegion(0).front()) {
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
    // verify() proved exactly one conditional driver. A split exposes that
    // driver's data/enable, not an independently invented resolved signal.
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

FailureOr<DigitalInterfaceBoundary> inspectDigitalInterfaceBoundary(Operation *operation) {
  if (!llvm::isa_and_nonnull<InterfaceBoundaryOp>(operation) || failed(version(operation)) ||
      failed(arity(operation, 0, 0)) || !identity(symbol(operation)) ||
      !identity(text(metadata(operation), "semantic_path"))) {
    (void)reject(operation, "Interface boundary requires a typed parent-owned semantic identity");
    return failure();
  }
  auto child = resolveHierarchyInstance(operation, operation->getAttr("instance"));
  if (failed(child))
    return failure();
  StringRef name = text(operation, "endpoint");
  Operation *endpoint = identity(name) ? SymbolTable::lookupSymbolIn(child->module, name) : nullptr;
  if (!llvm::isa_and_nonnull<InterfaceInstanceOp>(endpoint) || !marked(endpoint) ||
      endpoint->getParentOp() != child->module) {
    (void)reject(operation,
                 "Interface boundary requires its exact immediate child's direct endpoint");
    return failure();
  }
  auto requirement = metadata(endpoint).getAs<FlatSymbolRefAttr>("domain");
  Operation *required =
      requirement ? SymbolTable::lookupSymbolIn(child->module, requirement) : nullptr;
  if (!llvm::isa_and_nonnull<DomainRequirementOp>(required)) {
    (void)reject(operation,
                 "child Interface domain requires an actual instance requirement binding");
    return failure();
  }
  Operation *binding = nullptr;
  for (Operation &candidate : operation->getParentOp()->getRegion(0).front()) {
    if (!llvm::isa<InstanceDomainBindingOp>(candidate) ||
        candidate.getAttr("instance") != operation->getAttr("instance") ||
        candidate.getAttr("requirement") != requirement)
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
  return DigitalInterfaceBoundary{child->instance, child->module, endpoint,
                                  binding->getAttrOfType<FlatSymbolRefAttr>("actual")};
}

FailureOr<DigitalInterfaceMember> inspectDigitalInterfaceMember(Operation *operation) {
  if (!llvm::isa_and_nonnull<MemberAccessOp>(operation) || !marked(operation))
    return failure();
  auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("instance");
  Operation *endpoint =
      reference ? SymbolTable::lookupSymbolIn(operation->getParentOp(), reference) : nullptr;
  Operation *instance = nullptr, *owner = operation->getParentOp();
  if (llvm::isa_and_nonnull<InterfaceBoundaryOp>(endpoint)) {
    auto boundary = inspectDigitalInterfaceBoundary(endpoint);
    if (failed(boundary))
      return failure();
    endpoint = boundary->endpoint;
    instance = boundary->instance;
    owner = boundary->module;
  }
  if (!llvm::isa_and_nonnull<InterfaceInstanceOp>(endpoint) || !marked(endpoint))
    return failure();
  auto definitionRef = endpoint->getAttrOfType<FlatSymbolRefAttr>("definition");
  Operation *definition =
      definitionRef ? SymbolTable::lookupSymbolIn(owner, definitionRef) : nullptr;
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
      return DigitalInterfaceMember{endpoint, instance, owner, path,
                                    value.getAs<StringAttr>("access")};
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
    owner = owner->getParentOp();
  }
  if (!llvm::isa_and_nonnull<nodal::ModuleOp>(owner))
    return reject(operation,
                  "fully typed Interface and resolved operations require local module ownership");
  return success();
}
LogicalResult verifyDigitalInterfaceModel(Operation *module) { return Model(module).verify(); }
LogicalResult
collectDigitalInterfaceEndpoints(Operation *module,
                                 SmallVectorImpl<DigitalInterfaceEndpoint> &endpoints) {
  Model model(module);
  if (failed(model.verify()))
    return failure();
  return model.endpoints(endpoints);
}
LogicalResult collectDigitalInterfaceDependencies(
    Operation *module, SmallVectorImpl<DigitalInterfaceValueDependencies> &dependencies,
    SmallVectorImpl<DigitalInterfaceObservationGroup> *observations) {
  Model model(module);
  if (failed(model.verify()))
    return failure();
  return model.dependencies(dependencies, observations);
}
} // namespace nodal
