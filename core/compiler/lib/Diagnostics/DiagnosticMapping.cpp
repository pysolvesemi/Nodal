#include "nodal/Diagnostics/DiagnosticMapping.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Location.h"
#include "mlir/IR/SymbolTable.h"
#include "mlir/Pass/PassRegistry.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"

#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringSet.h"
#include "llvm/Support/Casting.h"

#include <algorithm>
#include <cstdint>
#include <optional>
#include <string>
#include <utility>

using namespace mlir;

namespace nodal {
namespace {

DictionaryAttr metadata(Operation *operation) {
  return operation ? operation->getAttrOfType<DictionaryAttr>("metadata") : DictionaryAttr();
}

StringAttr stringAttribute(DictionaryAttr values, llvm::StringRef name) {
  return values ? values.getAs<StringAttr>(name) : StringAttr();
}

std::optional<int64_t> integerAttribute(DictionaryAttr values, llvm::StringRef name) {
  if (!values)
    return std::nullopt;
  if (auto integer = values.getAs<IntegerAttr>(name))
    return integer.getInt();
  if (auto text = values.getAs<StringAttr>(name)) {
    int64_t result = 0;
    if (!text.getValue().getAsInteger(10, result))
      return result;
  }
  return std::nullopt;
}

std::optional<bool> booleanAttribute(DictionaryAttr values, llvm::StringRef name) {
  if (!values)
    return std::nullopt;
  if (auto value = values.getAs<BoolAttr>(name))
    return value.getValue();
  if (auto text = values.getAs<StringAttr>(name)) {
    if (text.getValue() == "true")
      return true;
    if (text.getValue() == "false")
      return false;
  }
  return std::nullopt;
}

llvm::StringRef inventoryPath(DictionaryAttr entry) {
  for (llvm::StringRef name : {llvm::StringRef("semantic_path"), llvm::StringRef("path"),
                               llvm::StringRef("logical_path")}) {
    if (auto value = entry.getAs<StringAttr>(name))
      return value.getValue();
  }
  return {};
}

bool compatibleRoles(llvm::StringRef left, llvm::StringRef right) {
  const std::pair<llvm::StringRef, llvm::StringRef> pair(left, right);
  return pair == std::pair<llvm::StringRef, llvm::StringRef>("master", "slave") ||
         pair == std::pair<llvm::StringRef, llvm::StringRef>("slave", "master") ||
         pair == std::pair<llvm::StringRef, llvm::StringRef>("source", "sink") ||
         pair == std::pair<llvm::StringRef, llvm::StringRef>("sink", "source") ||
         pair == std::pair<llvm::StringRef, llvm::StringRef>("initiator", "target") ||
         pair == std::pair<llvm::StringRef, llvm::StringRef>("target", "initiator") ||
         pair == std::pair<llvm::StringRef, llvm::StringRef>("controller", "peripheral") ||
         pair == std::pair<llvm::StringRef, llvm::StringRef>("peripheral", "controller") ||
         pair == std::pair<llvm::StringRef, llvm::StringRef>("device", "environment") ||
         pair == std::pair<llvm::StringRef, llvm::StringRef>("environment", "device");
}

bool inverseRoles(llvm::StringRef source, llvm::StringRef destination) {
  return compatibleRoles(source, destination);
}

LogicalResult verifyInterfaceStorage(mlir::ModuleOp module) {
  LogicalResult result = success();
  module.walk([&](Operation *operation) {
    for (llvm::StringRef name : {llvm::StringRef("type"), llvm::StringRef("underlying_type"),
                                 llvm::StringRef("state_type")}) {
      auto type = operation->getAttrOfType<TypeAttr>(name);
      if (type && llvm::isa<InterfaceType>(type.getValue()))
        result = emitMappedFailure(operation, "NODAL-INTERFACE-STORAGE-001",
                                   "Interface connectivity cannot be stored as a value");
    }
  });
  return result;
}

LogicalResult verifyInterfaceDefinitions(mlir::ModuleOp module) {
  struct InterfaceInfo {
    llvm::StringSet<> roles;
    llvm::StringSet<> members;
  };
  llvm::StringMap<InterfaceInfo> definitions;
  for (Operation &operation : module.getBody()->getOperations()) {
    if (operation.getName().getStringRef() != "nodal.interface")
      continue;
    auto symbol = operation.getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName());
    if (!symbol || operation.getNumRegions() != 1 || operation.getRegion(0).empty())
      continue;
    InterfaceInfo &info = definitions[symbol.getValue()];
    for (Operation &nested : operation.getRegion(0).front()) {
      auto nestedSymbol = nested.getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName());
      if (!nestedSymbol)
        continue;
      if (nested.getName().getStringRef() == "nodal.interface_role")
        info.roles.insert(nestedSymbol.getValue());
      else if (nested.getName().getStringRef() == "nodal.interface_member")
        info.members.insert(nestedSymbol.getValue());
    }
  }

  LogicalResult result = success();
  module.walk([&](Operation *owner) {
    if (owner->getName().getStringRef() != "nodal.module" || owner->getNumRegions() != 1 ||
        owner->getRegion(0).empty())
      return;
    struct InstanceInfo {
      std::string definition;
      std::string role;
    };
    llvm::StringMap<InstanceInfo> instances;
    for (Operation &operation : owner->getRegion(0).front()) {
      if (operation.getName().getStringRef() != "nodal.interface_instance")
        continue;
      auto symbol = operation.getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName());
      auto definition = operation.getAttrOfType<FlatSymbolRefAttr>("definition");
      auto role = operation.getAttrOfType<StringAttr>("role");
      if (!symbol || !definition || !definitions.contains(definition.getValue()))
        continue;
      if (!role || !definitions[definition.getValue()].roles.contains(role.getValue())) {
        result = emitMappedFailure(&operation, "NODAL-INTERFACE-ROLE-001",
                                   "Interface instance selects a missing or unknown role");
        continue;
      }
      if (auto inverted = stringAttribute(metadata(&operation), "inverted_from")) {
        if (!inverseRoles(inverted.getValue(), role.getValue()))
          result =
              emitMappedFailure(&operation, "NODAL-INTERFACE-INVERSION-001",
                                "Interface role inversion is not a defined complementary pair");
      }
      instances[symbol.getValue()] =
          InstanceInfo{definition.getValue().str(), role.getValue().str()};
    }

    for (Operation &operation : owner->getRegion(0).front()) {
      if (operation.getName().getStringRef() != "nodal.member_access")
        continue;
      auto instance = operation.getAttrOfType<FlatSymbolRefAttr>("instance");
      auto path = operation.getAttrOfType<StringAttr>("path");
      if (!instance || !path || !instances.contains(instance.getValue()))
        continue;
      llvm::StringRef member = path.getValue().split('.').first;
      const InstanceInfo &info = instances[instance.getValue()];
      if (!definitions[info.definition].members.contains(member))
        result = emitMappedFailure(&operation, "NODAL-INTERFACE-MEMBER-001",
                                   llvm::Twine("Interface role references missing member '") +
                                       member + "'");
      if (info.role == "monitor") {
        if (auto access = stringAttribute(metadata(&operation), "access")) {
          if (access.getValue() == "drive" || access.getValue() == "contribute")
            result =
                emitMappedFailure(&operation, "NODAL-INTERFACE-MONITOR-001",
                                  "monitor role cannot drive or contribute to an Interface member");
        }
      }
    }
  });
  return result;
}

LogicalResult verifyInterfaceInventories(mlir::ModuleOp module) {
  LogicalResult result = success();
  if (ArrayAttr connections =
          module->getAttrOfType<ArrayAttr>("nodal.verify.interface_connections")) {
    for (Attribute value : connections) {
      auto entry = llvm::dyn_cast<DictionaryAttr>(value);
      if (!entry)
        continue;
      auto left = entry.getAs<StringAttr>("left_role");
      auto right = entry.getAs<StringAttr>("right_role");
      if (left && right && !compatibleRoles(left.getValue(), right.getValue()))
        result = emitMappedFailureForPath(module, inventoryPath(entry), "NODAL-INTERFACE-ROLE-002",
                                          llvm::Twine("incompatible Interface roles '") +
                                              left.getValue() + "' and '" + right.getValue() + "'");
    }
  }

  if (ArrayAttr actions = module->getAttrOfType<ArrayAttr>("nodal.verify.interface_actions")) {
    for (Attribute value : actions) {
      auto entry = llvm::dyn_cast<DictionaryAttr>(value);
      if (!entry)
        continue;
      auto role = entry.getAs<StringAttr>("role");
      auto action = entry.getAs<StringAttr>("action");
      if (role && action && role.getValue() == "monitor" &&
          (action.getValue() == "drive" || action.getValue() == "contribute"))
        result =
            emitMappedFailureForPath(module, inventoryPath(entry), "NODAL-INTERFACE-MONITOR-001",
                                     "monitor role cannot drive or contribute");
    }
  }

  if (ArrayAttr inversions =
          module->getAttrOfType<ArrayAttr>("nodal.verify.interface_inversions")) {
    for (Attribute value : inversions) {
      auto entry = llvm::dyn_cast<DictionaryAttr>(value);
      if (!entry)
        continue;
      auto source = entry.getAs<StringAttr>("source_role");
      auto destination = entry.getAs<StringAttr>("destination_role");
      if (source && destination && !inverseRoles(source.getValue(), destination.getValue()))
        result =
            emitMappedFailureForPath(module, inventoryPath(entry), "NODAL-INTERFACE-INVERSION-001",
                                     "invalid Interface role inversion");
    }
  }
  return result;
}

LogicalResult verifyInterfaceLayouts(mlir::ModuleOp module) {
  llvm::StringMap<std::string> emittedToLogical;
  LogicalResult result = success();
  module.walk([&](Operation *operation) {
    if (operation->getName().getStringRef() != "nodal.interface_abi")
      return;
    auto logical = operation->getAttrOfType<StringAttr>("logical_path");
    auto emitted = stringAttribute(metadata(operation), "emitted_path");
    if (!logical || !emitted || emitted.getValue().empty())
      return;
    auto existing = emittedToLogical.find(emitted.getValue());
    if (existing != emittedToLogical.end() && existing->second != logical.getValue())
      result = emitMappedFailure(operation, "NODAL-INTERFACE-LAYOUT-001",
                                 llvm::Twine("Interface layout collision on emitted path '") +
                                     emitted.getValue() + "'");
    else
      emittedToLogical[emitted.getValue()] = logical.getValue().str();
  });
  return result;
}

LogicalResult verifyOrdinaryDrivers(mlir::ModuleOp module) {
  ArrayAttr drivers = module->getAttrOfType<ArrayAttr>("nodal.verify.ordinary_drivers");
  if (!drivers)
    return success();
  LogicalResult result = success();
  for (Attribute value : drivers) {
    auto entry = llvm::dyn_cast<DictionaryAttr>(value);
    if (!entry)
      continue;
    auto count = entry.getAs<IntegerAttr>("count");
    if (count && count.getInt() > 1)
      result = emitMappedFailureForPath(module, inventoryPath(entry), "NODAL-DRIVER-MULTIPLE-001",
                                        "ordinary value has multiple active drivers");
  }
  return result;
}

LogicalResult verifyResolvedNets(mlir::ModuleOp module) {
  LogicalResult result = success();
  module.walk([&](Operation *operation) {
    llvm::StringRef name = operation->getName().getStringRef();
    if (name == "nodal.resolved_net") {
      if (booleanAttribute(metadata(operation), "resolution_supported") ==
          std::optional<bool>(false))
        result =
            emitMappedFailure(operation, "NODAL-INOUT-RESOLUTION-001",
                              "selected profile does not support this resolved-net capability");
      return;
    }
    if (name != "nodal.net_drive" || operation->getNumOperands() == 0)
      return;
    auto net = llvm::dyn_cast<ResolvedType>(operation->getOperand(0).getType());
    auto level = stringAttribute(metadata(operation), "drive_level");
    if (!net || !level)
      return;
    if (net.getDriveMode() == "open_drain" && level.getValue() != "low" &&
        level.getValue() != "0" && level.getValue() != "z")
      result = emitMappedFailure(operation, "NODAL-INOUT-OPEN-DRAIN-001",
                                 "open-drain endpoint may drive only low or high impedance");
    if (net.getDriveMode() == "open_source" && level.getValue() != "high" &&
        level.getValue() != "1" && level.getValue() != "z")
      result = emitMappedFailure(operation, "NODAL-INOUT-OPEN-SOURCE-001",
                                 "open-source endpoint may drive only high or high impedance");
  });
  return result;
}

LogicalResult verifyInoutPassThrough(mlir::ModuleOp module) {
  ArrayAttr declarations = module->getAttrOfType<ArrayAttr>("nodal.bridge.declarations");
  ArrayAttr topology = module->getAttrOfType<ArrayAttr>("nodal.bridge.topology");
  if (!declarations || !topology)
    return success();

  llvm::StringMap<std::string> modes;
  for (Attribute value : declarations) {
    auto entry = llvm::dyn_cast<DictionaryAttr>(value);
    if (!entry)
      continue;
    auto path = entry.getAs<StringAttr>("path");
    auto attributes = entry.getAs<DictionaryAttr>("attributes");
    auto mode = attributes ? attributes.getAs<StringAttr>("mode") : StringAttr();
    if (path && mode)
      modes[path.getValue()] = mode.getValue().str();
  }

  LogicalResult result = success();
  for (Attribute value : topology) {
    auto entry = llvm::dyn_cast<DictionaryAttr>(value);
    if (!entry)
      continue;
    auto kind = entry.getAs<StringAttr>("kind");
    auto left = entry.getAs<StringAttr>("left");
    auto right = entry.getAs<StringAttr>("right");
    if (!kind || kind.getValue() != "inout-pass-through" || !left || !right)
      continue;
    auto leftMode = modes.find(left.getValue());
    auto rightMode = modes.find(right.getValue());
    if (leftMode == modes.end() || rightMode == modes.end() ||
        leftMode->second != rightMode->second)
      result = emitMappedFailureForPath(
          module, left.getValue(), "NODAL-INOUT-HIERARCHY-001",
          "hierarchical inout pass-through does not preserve one resolved-net mode");
  }
  return result;
}

LogicalResult verifyAmsInventories(mlir::ModuleOp module) {
  LogicalResult result = success();
  if (ArrayAttr connections = module->getAttrOfType<ArrayAttr>("nodal.verify.ams_connections")) {
    for (Attribute value : connections) {
      auto entry = llvm::dyn_cast<DictionaryAttr>(value);
      if (!entry)
        continue;
      auto left = entry.getAs<StringAttr>("left_discipline");
      auto right = entry.getAs<StringAttr>("right_discipline");
      if (left && right && left.getValue() != right.getValue())
        result = emitMappedFailureForPath(module, inventoryPath(entry), "NODAL-AMS-DISCIPLINE-001",
                                          "conservative connection joins incompatible disciplines");
    }
  }

  if (ArrayAttr accesses = module->getAttrOfType<ArrayAttr>("nodal.verify.ams_accesses")) {
    for (Attribute value : accesses) {
      auto entry = llvm::dyn_cast<DictionaryAttr>(value);
      if (!entry)
        continue;
      if (booleanAttribute(entry, "allowed") == std::optional<bool>(false))
        result = emitMappedFailureForPath(module, inventoryPath(entry), "NODAL-AMS-ACCESS-001",
                                          "role does not permit the requested conservative access");
    }
  }

  if (ArrayAttr bridges = module->getAttrOfType<ArrayAttr>("nodal.verify.implicit_bridges")) {
    for (Attribute value : bridges) {
      auto entry = llvm::dyn_cast<DictionaryAttr>(value);
      if (!entry)
        continue;
      if (booleanAttribute(entry, "implicit") == std::optional<bool>(true))
        result = emitMappedFailureForPath(
            module, inventoryPath(entry), "NODAL-AMS-BRIDGE-001",
            "analog/digital or conservative/signal-flow conversion requires an explicit bridge");
    }
  }

  module.walk([&](Operation *operation) {
    if (operation->getName().getStringRef() == "nodal.bridge" &&
        booleanAttribute(metadata(operation), "implicit") == std::optional<bool>(true))
      result = emitMappedFailure(
          operation, "NODAL-AMS-BRIDGE-001",
          "analog/digital or conservative/signal-flow conversion requires an explicit bridge");
  });
  return result;
}

class CrossLayerDiagnosticPass final
    : public PassWrapper<CrossLayerDiagnosticPass, OperationPass<mlir::ModuleOp>> {
public:
  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(CrossLayerDiagnosticPass)

  llvm::StringRef getArgument() const final { return "nodal-verify-cross-layer-diagnostics"; }
  llvm::StringRef getDescription() const final {
    return "Verify and source-map cross-layer Interface, inout, and AMS diagnostics";
  }

  void runOnOperation() final {
    mlir::ModuleOp module = getOperation();
    LogicalResult result = success();
    for (LogicalResult check : {
             verifyInterfaceStorage(module),
             verifyInterfaceDefinitions(module),
             verifyInterfaceInventories(module),
             verifyInterfaceLayouts(module),
             verifyOrdinaryDrivers(module),
             verifyResolvedNets(module),
             verifyInoutPassThrough(module),
             verifyAmsInventories(module),
         }) {
      if (failed(check))
        result = failure();
    }
    if (failed(result)) {
      signalPassFailure();
      return;
    }
    markAllAnalysesPreserved();
  }
};

} // namespace

std::unique_ptr<Pass> createCrossLayerDiagnosticPass() {
  return std::make_unique<CrossLayerDiagnosticPass>();
}

void registerNodalDiagnosticPasses() {
  static PassRegistration<CrossLayerDiagnosticPass> crossLayer;
  (void)crossLayer;
}

} // namespace nodal
