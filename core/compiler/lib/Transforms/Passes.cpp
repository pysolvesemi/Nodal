#include "nodal/Transforms/Passes.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinTypes.h"
#include "mlir/IR/SymbolTable.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Pass/PassManager.h"
#include "mlir/Pass/PassRegistry.h"
#include "mlir/Support/LogicalResult.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"
#include "nodal/Dialect/Nodal/PotentialFlowAccess.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringExtras.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringSet.h"
#include "llvm/ADT/Twine.h"
#include "llvm/Support/Casting.h"

#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <utility>

using namespace mlir;

namespace nodal {
namespace {

enum class VerificationStage {
  Construction,
  Drivers,
  Latches,
  Cycles,
  Hierarchy,
  Types,
  Parameters,
  EnumFsm,
  Domains,
  Protocols,
  Effects,
  Analog,
  Capabilities,
};

struct InventoryAnalysis {
  explicit InventoryAnalysis(Operation *operation) {
    operation->walk([&](Operation *nested) {
      ++operationCount;
      if (nested->getName().getStringRef().starts_with("nodal."))
        ++nodalOperationCount;
    });
  }

  unsigned operationCount = 0;
  unsigned nodalOperationCount = 0;
};

LogicalResult emitFailure(Operation *operation, llvm::StringRef code, const llvm::Twine &message) {
  return emitMappedFailure(operation, code, message);
}

bool isNamed(Operation *operation, llvm::StringRef name) {
  return operation->getName().getStringRef() == name;
}

llvm::StringRef symbolName(Operation *operation) {
  if (auto name = operation->getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName()))
    return name.getValue();
  return {};
}

FlatSymbolRefAttr flatReference(Operation *operation, llvm::StringRef name) {
  return operation->getAttrOfType<FlatSymbolRefAttr>(name);
}

DictionaryAttr metadata(Operation *operation) {
  return operation->getAttrOfType<DictionaryAttr>("metadata");
}

std::optional<bool> booleanMetadata(Operation *operation, llvm::StringRef name) {
  if (DictionaryAttr values = metadata(operation)) {
    if (auto value = values.getAs<BoolAttr>(name))
      return value.getValue();
  }
  return std::nullopt;
}

LogicalResult verifyGuard(mlir::ModuleOp module, llvm::StringRef attribute, llvm::StringRef code,
                          llvm::StringRef label) {
  if (auto guard = module->getAttrOfType<BoolAttr>(attribute)) {
    if (!guard.getValue())
      return emitFailure(module.getOperation(), code, llvm::Twine(label) + " reported failure");
  }
  return success();
}

Operation *enclosingNodalModule(Operation *operation) {
  for (Operation *parent = operation; parent; parent = parent->getParentOp()) {
    if (isNamed(parent, "nodal.module"))
      return parent;
  }
  return nullptr;
}

llvm::StringSet<> moduleParameters(Operation *operation) {
  llvm::StringSet<> parameters;
  Operation *module = enclosingNodalModule(operation);
  if (!module || module->getNumRegions() != 1 || module->getRegion(0).empty())
    return parameters;
  for (Operation &nested : module->getRegion(0).front()) {
    if (isNamed(&nested, "nodal.parameter"))
      parameters.insert(symbolName(&nested));
  }
  return parameters;
}

bool isIdentifier(llvm::StringRef value) {
  if (value.empty() || !(llvm::isAlpha(value.front()) || value.front() == '_'))
    return false;
  for (char character : value.drop_front()) {
    if (!(llvm::isAlnum(character) || character == '_'))
      return false;
  }
  return true;
}

std::optional<unsigned> finiteWidth(Type type) {
  if (auto value = llvm::dyn_cast<BitsType>(type))
    return static_cast<unsigned>(value.getWidth());
  if (auto value = llvm::dyn_cast<UIntType>(type))
    return static_cast<unsigned>(value.getWidth());
  if (auto value = llvm::dyn_cast<SIntType>(type))
    return static_cast<unsigned>(value.getWidth());
  if (auto value = llvm::dyn_cast<EnumType>(type))
    return static_cast<unsigned>(value.getWidth());
  if (auto value = llvm::dyn_cast<IntegerType>(type))
    return value.getWidth();
  return std::nullopt;
}

bool integerFits(IntegerAttr value, Type type) {
  std::optional<unsigned> width = finiteWidth(type);
  if (!width)
    return false;
  const bool isSigned = llvm::isa<SIntType>(type) ||
                        (llvm::isa<IntegerType>(type) && llvm::cast<IntegerType>(type).isSigned());
  if (isSigned)
    return value.getValue().isSignedIntN(*width);
  return !value.getValue().isNegative() && value.getValue().isIntN(*width);
}

bool bindingFits(Attribute value, Type type) {
  if (auto typed = llvm::dyn_cast<TypedAttr>(value)) {
    if (typed.getType() == type)
      return true;
  }
  if (auto integer = llvm::dyn_cast<IntegerAttr>(value))
    return integerFits(integer, type);
  if (llvm::isa<BoolAttr>(value)) {
    if (type.isInteger(1))
      return true;
    if (auto bits = llvm::dyn_cast<BitsType>(type))
      return bits.getWidth() == 1;
  }
  return false;
}

LogicalResult verifyType(Type type, Operation *owner, const llvm::StringSet<> &parameters) {
  if (auto shaped = llvm::dyn_cast<ShapedType>(type)) {
    llvm::SmallVector<llvm::StringRef> dimensions;
    shaped.getDimensions().split(dimensions, ',', -1, false);
    if (dimensions.empty())
      return emitFailure(owner, "NODAL-VERIFY-TYPE-001",
                         "shaped type must have at least one dimension");
    for (llvm::StringRef dimension : dimensions) {
      dimension = dimension.trim();
      int64_t numeric = 0;
      if (!dimension.getAsInteger(10, numeric)) {
        if (numeric <= 0)
          return emitFailure(owner, "NODAL-VERIFY-TYPE-002", "shaped dimensions must be positive");
      } else if (!isIdentifier(dimension) || !parameters.contains(dimension)) {
        return emitFailure(owner, "NODAL-VERIFY-TYPE-003",
                           llvm::Twine("unknown symbolic dimension '") + dimension + "'");
      }
    }
    return verifyType(shaped.getElementType(), owner, parameters);
  }
  if (auto valid = llvm::dyn_cast<ValidType>(type))
    return verifyType(valid.getPayloadType(), owner, parameters);
  if (auto stream = llvm::dyn_cast<StreamType>(type))
    return verifyType(stream.getPayloadType(), owner, parameters);
  if (auto resolved = llvm::dyn_cast<ResolvedType>(type))
    return verifyType(resolved.getElementType(), owner, parameters);
  if (auto driver = llvm::dyn_cast<DriverType>(type))
    return verifyType(driver.getElementType(), owner, parameters);
  if (std::optional<unsigned> width = finiteWidth(type)) {
    if (*width == 0)
      return emitFailure(owner, "NODAL-VERIFY-TYPE-004",
                         "finite-width type must have a non-zero width");
  }
  return success();
}

llvm::StringMap<Operation *> collectModuleDefinitions(mlir::ModuleOp module,
                                                      LogicalResult &result) {
  llvm::StringMap<Operation *> definitions;
  for (Operation &operation : module.getBody()->getOperations()) {
    if (!isNamed(&operation, "nodal.module"))
      continue;
    llvm::StringRef name = symbolName(&operation);
    if (name.empty()) {
      result =
          emitFailure(&operation, "NODAL-VERIFY-HIERARCHY-001", "module definition lacks a symbol");
      continue;
    }
    if (!definitions.try_emplace(name, &operation).second)
      result = emitFailure(&operation, "NODAL-VERIFY-HIERARCHY-002",
                           llvm::Twine("duplicate module symbol '") + name + "'");
  }
  return definitions;
}

LogicalResult verifyConstruction(mlir::ModuleOp module) {
  if (failed(verifyGuard(module, "nodal.verify.construction_closed",
                         "NODAL-VERIFY-CONSTRUCTION-001", "construction closure")))
    return failure();

  unsigned modules = 0;
  LogicalResult result = success();
  module.walk([&](Operation *operation) {
    if (isNamed(operation, "nodal.placeholder"))
      result = emitFailure(operation, "NODAL-VERIFY-CONSTRUCTION-002",
                           "placeholder operation is not accepted by semantic gates");
    if (!isNamed(operation, "nodal.module"))
      return;
    ++modules;
    if (operation->getNumRegions() != 1 || operation->getRegion(0).getBlocks().size() != 1)
      result = emitFailure(operation, "NODAL-VERIFY-CONSTRUCTION-003",
                           "module construction must be closed with one body block");
  });
  if (modules == 0)
    return emitFailure(module.getOperation(), "NODAL-VERIFY-CONSTRUCTION-004",
                       "semantic pipeline requires at least one nodal.module");

  if (auto version = module->getAttrOfType<IntegerAttr>("nodal.bridge.version")) {
    if (version.getInt() != 1)
      return emitFailure(module.getOperation(), "NODAL-VERIFY-CONSTRUCTION-005",
                         "unsupported Scala-to-MLIR bridge version");
  }
  return result;
}

LogicalResult verifyDrivers(mlir::ModuleOp module) {
  if (failed(verifyGuard(module, "nodal.verify.driver_coverage", "NODAL-VERIFY-DRIVER-001",
                         "driver coverage")) ||
      failed(verifyGuard(module, "nodal.verify.assignment_coverage", "NODAL-VERIFY-DRIVER-002",
                         "assignment coverage")))
    return failure();

  LogicalResult result = success();
  module.walk([&](Operation *candidate) {
    if (!isNamed(candidate, "nodal.module") || candidate->getRegion(0).empty())
      return;
    llvm::StringMap<Operation *> drivers;
    candidate->walk([&](Operation *operation) {
      if (!isNamed(operation, "nodal.net_driver"))
        return;
      auto id = operation->getAttrOfType<StringAttr>("driver_id");
      if (!id || id.getValue().empty()) {
        result = emitFailure(operation, "NODAL-VERIFY-DRIVER-003",
                             "resolved-net driver lacks a stable identity");
        return;
      }
      if (!drivers.try_emplace(id.getValue(), operation).second)
        result = emitFailure(operation, "NODAL-VERIFY-DRIVER-004",
                             llvm::Twine("duplicate driver identity '") + id.getValue() + "'");
      if (operation->getNumResults() != 1 || operation->getResult(0).use_empty())
        result = emitFailure(operation, "NODAL-VERIFY-DRIVER-005",
                             "declared resolved-net driver has no drive operation");
    });
    candidate->walk([&](Operation *operation) {
      if (!isNamed(operation, "nodal.net_drive"))
        return;
      if (operation->getNumOperands() < 2) {
        result = emitFailure(operation, "NODAL-VERIFY-DRIVER-006",
                             "net drive lacks net and driver operands");
        return;
      }
      Operation *driver = operation->getOperand(1).getDefiningOp();
      if (!driver || !isNamed(driver, "nodal.net_driver")) {
        result = emitFailure(operation, "NODAL-VERIFY-DRIVER-007",
                             "net drive does not use a declared driver identity");
        return;
      }
      if (driver->getNumOperands() != 1 || driver->getOperand(0) != operation->getOperand(0))
        result = emitFailure(operation, "NODAL-VERIFY-DRIVER-008",
                             "driver identity and drive target different resolved nets");
    });
  });
  return result;
}

LogicalResult verifyLatches(mlir::ModuleOp module) {
  return verifyGuard(module, "nodal.verify.latch_free", "NODAL-VERIFY-LATCH-001", "latch analysis");
}

LogicalResult verifyCycles(mlir::ModuleOp module) {
  if (failed(verifyGuard(module, "nodal.verify.combinational_acyclic", "NODAL-VERIFY-CYCLE-001",
                         "combinational-cycle analysis")))
    return failure();

  auto inventory = module->getAttrOfType<ArrayAttr>("nodal.verify.combinational_edges");
  if (!inventory)
    return success();

  llvm::StringMap<llvm::SmallVector<std::string, 4>> edges;
  for (Attribute value : inventory) {
    auto entry = llvm::dyn_cast<DictionaryAttr>(value);
    if (!entry)
      return emitFailure(module.getOperation(), "NODAL-VERIFY-CYCLE-002",
                         "combinational edge inventory contains a non-dictionary entry");
    auto source = entry.getAs<StringAttr>("source");
    auto destination = entry.getAs<StringAttr>("destination");
    if (!source || !destination || source.getValue().empty() || destination.getValue().empty())
      return emitFailure(module.getOperation(), "NODAL-VERIFY-CYCLE-003",
                         "combinational edge lacks source or destination");
    edges[source.getValue()].push_back(destination.getValue().str());
  }

  llvm::StringMap<unsigned> colors;
  std::function<LogicalResult(llvm::StringRef)> visit = [&](llvm::StringRef node) {
    unsigned &color = colors[node];
    if (color == 1)
      return emitFailure(module.getOperation(), "NODAL-VERIFY-CYCLE-004",
                         llvm::Twine("combinational cycle reaches '") + node + "'");
    if (color == 2)
      return success();
    color = 1;
    for (const std::string &next : edges[node]) {
      if (failed(visit(next)))
        return failure();
    }
    color = 2;
    return success();
  };
  for (const auto &entry : edges) {
    if (failed(visit(entry.getKey())))
      return failure();
  }
  return success();
}

LogicalResult verifyHierarchy(mlir::ModuleOp module) {
  if (failed(verifyGuard(module, "nodal.verify.hierarchy_closed", "NODAL-VERIFY-HIERARCHY-003",
                         "hierarchy closure")))
    return failure();

  LogicalResult result = success();
  llvm::StringMap<Operation *> definitions = collectModuleDefinitions(module, result);
  if (failed(result))
    return failure();

  llvm::StringMap<llvm::SmallVector<std::string, 4>> edges;
  for (const auto &entry : definitions) {
    Operation *definition = entry.getValue();
    definition->walk([&](Operation *operation) {
      if (!isNamed(operation, "nodal.instance"))
        return;
      FlatSymbolRefAttr target = flatReference(operation, "module");
      if (!target || !definitions.contains(target.getValue())) {
        result = emitFailure(operation, "NODAL-VERIFY-HIERARCHY-004",
                             llvm::Twine("instance references unknown module '") +
                                 (target ? target.getValue() : llvm::StringRef("")) + "'");
        return;
      }
      edges[entry.getKey()].push_back(target.getValue().str());
    });
  }
  if (failed(result))
    return failure();

  llvm::StringMap<unsigned> colors;
  std::function<LogicalResult(llvm::StringRef)> visit = [&](llvm::StringRef name) {
    unsigned &color = colors[name];
    if (color == 1)
      return emitFailure(definitions[name], "NODAL-VERIFY-HIERARCHY-005",
                         llvm::Twine("recursive module hierarchy includes '") + name + "'");
    if (color == 2)
      return success();
    color = 1;
    for (const std::string &child : edges[name]) {
      if (failed(visit(child)))
        return failure();
    }
    color = 2;
    return success();
  };
  for (const auto &entry : definitions) {
    if (failed(visit(entry.getKey())))
      return failure();
  }
  return success();
}

LogicalResult verifyTypes(mlir::ModuleOp module) {
  if (failed(verifyGuard(module, "nodal.verify.width_sign_shape", "NODAL-VERIFY-TYPE-005",
                         "width/sign/shape analysis")) ||
      failed(verifyGuard(module, "nodal.verify.layout_storage", "NODAL-VERIFY-TYPE-006",
                         "layout/storage analysis")))
    return failure();

  LogicalResult result = success();
  module.walk([&](Operation *operation) {
    llvm::StringSet<> parameters = moduleParameters(operation);
    for (Type type : operation->getOperandTypes()) {
      if (failed(verifyType(type, operation, parameters)))
        result = failure();
    }
    for (Type type : operation->getResultTypes()) {
      if (failed(verifyType(type, operation, parameters)))
        result = failure();
    }
    for (llvm::StringRef name : {llvm::StringRef("type"), llvm::StringRef("underlying_type"),
                                 llvm::StringRef("state_type")}) {
      if (auto value = operation->getAttrOfType<TypeAttr>(name)) {
        if (failed(verifyType(value.getValue(), operation, parameters)))
          result = failure();
      }
    }

    if (isNamed(operation, "nodal.shape_view")) {
      if (DictionaryAttr values = metadata(operation)) {
        if (auto storage = values.getAs<StringAttr>("storage")) {
          if (storage.getValue() != "structural" && storage.getValue() != "memory")
            result = emitFailure(operation, "NODAL-VERIFY-TYPE-007",
                                 "shape view has an unsupported storage intent");
        }
      }
    }
  });
  return result;
}

llvm::StringSet<> directSymbols(Operation *container, llvm::StringRef operationName) {
  llvm::StringSet<> symbols;
  if (container->getNumRegions() != 1 || container->getRegion(0).empty())
    return symbols;
  for (Operation &operation : container->getRegion(0).front()) {
    if (isNamed(&operation, operationName))
      symbols.insert(symbolName(&operation));
  }
  return symbols;
}

Operation *findDirectSymbol(Operation *container, llvm::StringRef operationName,
                            llvm::StringRef name) {
  if (container->getNumRegions() != 1 || container->getRegion(0).empty())
    return nullptr;
  for (Operation &operation : container->getRegion(0).front()) {
    if (isNamed(&operation, operationName) && symbolName(&operation) == name)
      return &operation;
  }
  return nullptr;
}

LogicalResult verifyParameters(mlir::ModuleOp module) {
  if (failed(verifyGuard(module, "nodal.verify.parameters_complete", "NODAL-VERIFY-PARAMETER-001",
                         "parameter/generate/loop analysis")))
    return failure();

  if (failed(nodal::verifyParameterModel(module)))
    return failure();

  LogicalResult result = success();
  llvm::StringMap<Operation *> definitions = collectModuleDefinitions(module, result);
  if (failed(result))
    return failure();

  for (const auto &entry : definitions) {
    Operation *owner = entry.getValue();
    owner->walk([&](Operation *operation) {
      if (isNamed(operation, "nodal.instance")) {
        FlatSymbolRefAttr target = flatReference(operation, "module");
        if (!target || !definitions.contains(target.getValue())) {
          result = emitFailure(operation, "NODAL-VERIFY-PARAMETER-002",
                               "instance target is unavailable for binding validation");
          return;
        }
        Operation *targetModule = definitions[target.getValue()];
        DictionaryAttr parameterBindings =
            operation->getAttrOfType<DictionaryAttr>("parameter_bindings");
        if (parameterBindings) {
          for (NamedAttribute binding : parameterBindings) {
            Operation *parameter =
                findDirectSymbol(targetModule, "nodal.parameter", binding.getName().getValue());
            if (!parameter) {
              result = emitFailure(operation, "NODAL-VERIFY-PARAMETER-003",
                                   llvm::Twine("unknown parameter binding '") +
                                       binding.getName().getValue() + "'");
              continue;
            }
            auto type = parameter->getAttrOfType<TypeAttr>("type");
            if (!type || !bindingFits(binding.getValue(), type.getValue()))
              result = emitFailure(operation, "NODAL-VERIFY-PARAMETER-004",
                                   llvm::Twine("incompatible parameter binding '") +
                                       binding.getName().getValue() + "'");
          }
        }

        DictionaryAttr domainBindings = operation->getAttrOfType<DictionaryAttr>("domain_bindings");
        llvm::StringSet<> requirements = directSymbols(targetModule, "nodal.domain_requirement");
        if (domainBindings) {
          for (NamedAttribute binding : domainBindings) {
            llvm::StringRef key = binding.getName().getValue();
            if (key == "default" && requirements.size() == 1)
              continue;
            if (!requirements.contains(key))
              result = emitFailure(operation, "NODAL-VERIFY-PARAMETER-005",
                                   llvm::Twine("unknown domain requirement binding '") + key + "'");
          }
        }
      }

      if (!isNamed(operation, "nodal.generate") && !isNamed(operation, "nodal.hardware_loop"))
        return;
      auto lower = operation->getAttrOfType<IntegerAttr>("lower");
      auto upper = operation->getAttrOfType<IntegerAttr>("upper");
      auto step = operation->getAttrOfType<IntegerAttr>("step");
      if (!lower || !upper || !step)
        return;
      const int64_t lowerValue = lower.getInt();
      const int64_t upperValue = upper.getInt();
      const int64_t stepValue = step.getInt();
      if ((lowerValue < upperValue && stepValue <= 0) ||
          (lowerValue > upperValue && stepValue >= 0))
        result = emitFailure(operation, "NODAL-VERIFY-PARAMETER-006",
                             "loop step does not progress toward its bound");
    });
  }
  return result;
}

LogicalResult verifyEnumFsm(mlir::ModuleOp module) {
  if (failed(verifyGuard(module, "nodal.verify.enum_fsm", "NODAL-VERIFY-FSM-001",
                         "enum/FSM analysis")))
    return failure();

  llvm::StringSet<> enumSymbols;
  for (Operation &operation : module.getBody()->getOperations()) {
    if (isNamed(&operation, "nodal.enum"))
      enumSymbols.insert(symbolName(&operation));
  }

  LogicalResult result = success();
  module.walk([&](Operation *fsm) {
    if (!isNamed(fsm, "nodal.fsm") || fsm->getNumRegions() != 1 || fsm->getRegion(0).empty())
      return;
    auto stateType = fsm->getAttrOfType<TypeAttr>("state_type");
    auto enumType = stateType ? llvm::dyn_cast<EnumType>(stateType.getValue()) : EnumType();
    if (!enumType || !enumSymbols.contains(enumType.getSymbol())) {
      result = emitFailure(fsm, "NODAL-VERIFY-FSM-002",
                           "FSM state type does not resolve to a semantic enum");
      return;
    }

    llvm::StringSet<> states;
    llvm::StringRef initial;
    llvm::StringMap<llvm::SmallVector<std::string, 4>> edges;
    for (Operation &state : fsm->getRegion(0).front()) {
      if (!isNamed(&state, "nodal.fsm_state"))
        continue;
      llvm::StringRef name = symbolName(&state);
      states.insert(name);
      if (auto value = state.getAttrOfType<BoolAttr>("initial")) {
        if (value.getValue())
          initial = name;
      }
      llvm::DenseSet<int64_t> priorities;
      if (state.getNumRegions() == 1 && !state.getRegion(0).empty()) {
        for (Operation &nested : state.getRegion(0).front()) {
          if (!isNamed(&nested, "nodal.fsm_transition"))
            continue;
          FlatSymbolRefAttr destination = flatReference(&nested, "destination");
          if (destination)
            edges[name].push_back(destination.getValue().str());
          if (auto priority = nested.getAttrOfType<IntegerAttr>("priority")) {
            if (!priorities.insert(priority.getInt()).second)
              result = emitFailure(&nested, "NODAL-VERIFY-FSM-003",
                                   "FSM state contains duplicate transition priorities");
          }
        }
      }
    }
    for (Operation &nested : fsm->getRegion(0).front()) {
      if (!isNamed(&nested, "nodal.fsm_completion"))
        continue;
      FlatSymbolRefAttr source = flatReference(&nested, "source");
      FlatSymbolRefAttr destination = flatReference(&nested, "destination");
      if (source && destination)
        edges[source.getValue()].push_back(destination.getValue().str());
    }

    for (const auto &entry : edges) {
      if (!states.contains(entry.getKey()))
        result = emitFailure(fsm, "NODAL-VERIFY-FSM-004",
                             llvm::Twine("unknown transition source '") + entry.getKey() + "'");
      for (const std::string &destination : entry.getValue()) {
        if (!states.contains(destination))
          result = emitFailure(fsm, "NODAL-VERIFY-FSM-005",
                               llvm::Twine("unknown transition destination '") + destination + "'");
      }
    }
    if (failed(result) || initial.empty())
      return;

    llvm::StringSet<> reachable;
    llvm::SmallVector<std::string, 8> worklist;
    worklist.push_back(initial.str());
    while (!worklist.empty()) {
      std::string state = std::move(worklist.pop_back_val());
      if (!reachable.insert(state).second)
        continue;
      for (const std::string &destination : edges[state])
        worklist.push_back(destination);
    }
    for (const auto &state : states) {
      if (!reachable.contains(state.getKey()))
        result = emitFailure(fsm, "NODAL-VERIFY-FSM-006",
                             llvm::Twine("unreachable FSM state '") + state.getKey() + "'");
    }
  });
  return result;
}

LogicalResult verifyDomains(mlir::ModuleOp module) {
  if (failed(verifyGuard(module, "nodal.verify.clock_reset_domains", "NODAL-VERIFY-DOMAIN-001",
                         "clock/reset-domain analysis")) ||
      failed(verifyGuard(module, "nodal.verify.cdc_rdc_safe", "NODAL-VERIFY-DOMAIN-002",
                         "CDC/RDC analysis")))
    return failure();

  LogicalResult result = success();
  module.walk([&](Operation *owner) {
    if (!isNamed(owner, "nodal.module") || owner->getNumRegions() != 1 ||
        owner->getRegion(0).empty())
      return;
    llvm::StringSet<> domains = directSymbols(owner, "nodal.domain");
    llvm::StringSet<> requirements = directSymbols(owner, "nodal.domain_requirement");
    auto resolves = [&](FlatSymbolRefAttr reference) {
      return reference && (domains.contains(reference.getValue()) ||
                           requirements.contains(reference.getValue()));
    };

    for (Operation &operation : owner->getRegion(0).front()) {
      if (isNamed(&operation, "nodal.port")) {
        if (!resolves(flatReference(&operation, "domain")))
          result = emitFailure(&operation, "NODAL-VERIFY-DOMAIN-003",
                               "port domain does not resolve in its module");
      } else if (isNamed(&operation, "nodal.domain_bind")) {
        FlatSymbolRefAttr requirement = flatReference(&operation, "requirement");
        FlatSymbolRefAttr actual = flatReference(&operation, "actual");
        if (!requirement || !requirements.contains(requirement.getValue()) || !actual ||
            !domains.contains(actual.getValue()))
          result = emitFailure(&operation, "NODAL-VERIFY-DOMAIN-004",
                               "domain binding does not resolve requirement and actual domain");
      } else if (isNamed(&operation, "nodal.fsm") || isNamed(&operation, "nodal.state_owner")) {
        if (!resolves(flatReference(&operation, "domain")))
          result = emitFailure(&operation, "NODAL-VERIFY-DOMAIN-005",
                               "state ownership domain does not resolve");
      } else if (isNamed(&operation, "nodal.crossing")) {
        FlatSymbolRefAttr source = flatReference(&operation, "source_domain");
        FlatSymbolRefAttr destination = flatReference(&operation, "destination_domain");
        if (!resolves(source) || !resolves(destination))
          result = emitFailure(&operation, "NODAL-VERIFY-DOMAIN-006",
                               "crossing endpoint domain does not resolve");
      } else if (isNamed(&operation, "nodal.instance")) {
        DictionaryAttr bindings = operation.getAttrOfType<DictionaryAttr>("domain_bindings");
        if (!bindings)
          continue;
        for (NamedAttribute binding : bindings) {
          auto actual = llvm::dyn_cast<FlatSymbolRefAttr>(binding.getValue());
          if (!actual || !domains.contains(actual.getValue()))
            result = emitFailure(&operation, "NODAL-VERIFY-DOMAIN-007",
                                 "instance domain binding references an unknown actual domain");
        }
      }
    }
  });
  return result;
}

struct InterfaceInfo {
  llvm::StringSet<> roles;
  llvm::StringSet<> members;
};

LogicalResult verifyProtocols(mlir::ModuleOp module) {
  if (failed(verifyGuard(module, "nodal.verify.protocol_pipeline", "NODAL-VERIFY-PROTOCOL-001",
                         "protocol/pipeline analysis")))
    return failure();

  llvm::StringMap<InterfaceInfo> interfaces;
  LogicalResult result = success();
  for (Operation &operation : module.getBody()->getOperations()) {
    if (!isNamed(&operation, "nodal.interface"))
      continue;
    llvm::StringRef name = symbolName(&operation);
    InterfaceInfo &info = interfaces[name];
    if (operation.getNumRegions() != 1 || operation.getRegion(0).empty())
      continue;
    for (Operation &nested : operation.getRegion(0).front()) {
      if (isNamed(&nested, "nodal.interface_role"))
        info.roles.insert(symbolName(&nested));
      else if (isNamed(&nested, "nodal.interface_member"))
        info.members.insert(symbolName(&nested));
    }
  }

  module.walk([&](Operation *owner) {
    if (!isNamed(owner, "nodal.module") || owner->getNumRegions() != 1 ||
        owner->getRegion(0).empty())
      return;
    struct InstanceInfo {
      std::string definition;
      std::string role;
    };
    llvm::StringMap<InstanceInfo> instances;
    for (Operation &operation : owner->getRegion(0).front()) {
      if (!isNamed(&operation, "nodal.interface_instance"))
        continue;
      FlatSymbolRefAttr definition = flatReference(&operation, "definition");
      auto role = operation.getAttrOfType<StringAttr>("role");
      if (!definition || !interfaces.contains(definition.getValue())) {
        result = emitFailure(&operation, "NODAL-VERIFY-PROTOCOL-002",
                             "Interface instance references an unknown definition");
        continue;
      }
      if (!role || !interfaces[definition.getValue()].roles.contains(role.getValue())) {
        result = emitFailure(&operation, "NODAL-VERIFY-PROTOCOL-003",
                             "Interface instance selects an unknown role");
        continue;
      }
      instances[symbolName(&operation)] =
          InstanceInfo{definition.getValue().str(), role.getValue().str()};
    }

    for (Operation &operation : owner->getRegion(0).front()) {
      if (!isNamed(&operation, "nodal.member_access"))
        continue;
      FlatSymbolRefAttr instance = flatReference(&operation, "instance");
      auto path = operation.getAttrOfType<StringAttr>("path");
      if (!instance || !instances.contains(instance.getValue()) || !path) {
        result = emitFailure(&operation, "NODAL-VERIFY-PROTOCOL-004",
                             "member access does not resolve an Interface instance");
        continue;
      }
      llvm::StringRef member = path.getValue().split('.').first;
      const InstanceInfo &info = instances[instance.getValue()];
      if (!interfaces[info.definition].members.contains(member))
        result =
            emitFailure(&operation, "NODAL-VERIFY-PROTOCOL-005",
                        llvm::Twine("member access references unknown member '") + member + "'");
    }
  });
  return result;
}

LogicalResult verifyEffects(mlir::ModuleOp module) {
  if (failed(verifyGuard(module, "nodal.verify.memory_effects", "NODAL-VERIFY-EFFECT-001",
                         "memory/effect analysis")))
    return failure();

  ArrayAttr declarations = module->getAttrOfType<ArrayAttr>("nodal.bridge.declarations");
  if (!declarations)
    return success();
  for (Attribute value : declarations) {
    auto declaration = llvm::dyn_cast<DictionaryAttr>(value);
    if (!declaration)
      return emitFailure(module.getOperation(), "NODAL-VERIFY-EFFECT-002",
                         "bridge declaration inventory contains a non-dictionary entry");
    auto kind = declaration.getAs<StringAttr>("kind");
    if (!kind)
      continue;
    DictionaryAttr attributes = declaration.getAs<DictionaryAttr>("attributes");
    if (kind.getValue() == "memory") {
      auto domain = declaration.getAs<StringAttr>("domain");
      if (!domain || domain.getValue().empty() || !attributes || !attributes.get("readlatency") ||
          !attributes.get("readunderwrite") || !attributes.get("ordering"))
        return emitFailure(module.getOperation(), "NODAL-VERIFY-EFFECT-003",
                           "memory declaration lacks latency, ordering, or domain contract");
    } else if (kind.getValue() == "external-operation") {
      if (!attributes || !attributes.get("latency") || !attributes.get("effect") ||
          !attributes.get("models"))
        return emitFailure(module.getOperation(), "NODAL-VERIFY-EFFECT-004",
                           "external operation lacks latency, effect, or model availability");
    }
  }
  return success();
}

LogicalResult verifyAnalog(mlir::ModuleOp module) {
  if (failed(verifyGuard(module, "nodal.verify.analog_topology", "NODAL-VERIFY-ANALOG-001",
                         "analog topology analysis")) ||
      failed(verifyGuard(module, "nodal.verify.mixed_signal_bridges", "NODAL-VERIFY-ANALOG-002",
                         "mixed-signal bridge analysis")))
    return failure();

  LogicalResult result = success();
  module.walk([&](Operation *owner) {
    if (!isNamed(owner, "nodal.module"))
      return;
    bool digital = false;
    bool analog = false;
    bool bridge = false;
    const bool partial = isPartialPhysicalComponent(owner);
    owner->walk([&](Operation *operation) {
      llvm::StringRef name = operation->getName().getStringRef();
      if (name == "nodal.component_contract" || name == "nodal.terminal" || name == "nodal.node" ||
          name == "nodal.connect" || name == "nodal.alias" || name == "nodal.reference" ||
          name == "nodal.branch" || name == "nodal.connection_set" ||
          name == "nodal.potential_equality" || name == "nodal.reference_potential" ||
          name == "nodal.flow_conservation" || name == "nodal.access" ||
          name == "nodal.terminal_access" || name == "nodal.port_flow_access" ||
          name == "nodal.probe" || name == "nodal.analog" || name == "nodal.real_literal" ||
          name == "nodal.analog_integer_literal" || name == "nodal.parameter_ref" ||
          name == "nodal.analog_add" || name == "nodal.analog_sub" || name == "nodal.analog_mul" ||
          name == "nodal.analog_div" || name == "nodal.analog_neg" ||
          name == "nodal.analog_compare" || name == "nodal.analog_logic" ||
          name == "nodal.analog_select" || name == "nodal.analog_ddt" ||
          name == "nodal.analog_idt" || name == "nodal.contribute")
        analog = true;
      if (name == "nodal.port" || name == "nodal.resolved_net" || name == "nodal.net_drive" ||
          name == "nodal.crossing")
        digital = true;
      if (name == "nodal.bridge")
        bridge = true;
      if ((name == "nodal.terminal" || name == "nodal.node") && operation->getNumResults() == 1 &&
          operation->getResult(0).use_empty() && !partial &&
          booleanMetadata(operation, "allow_floating") != std::optional<bool>(true))
        result = emitFailure(operation, "NODAL-VERIFY-ANALOG-003",
                             "floating conservative terminal requires explicit approval");
    });
    if (digital && analog && !bridge)
      result = emitFailure(owner, "NODAL-VERIFY-ANALOG-004",
                           "mixed digital/analog module requires an explicit bridge");
  });
  return result;
}

LogicalResult verifyCapabilities(mlir::ModuleOp module) {
  if (failed(verifyGuard(module, "nodal.verify.target_capability", "NODAL-VERIFY-CAPABILITY-001",
                         "target capability analysis")))
    return failure();

  llvm::StringRef profile = "target_neutral";
  if (auto value = module->getAttrOfType<StringAttr>("nodal.target.profile"))
    profile = value.getValue();
  if (profile != "target_neutral" && profile != "digital" && profile != "analog" &&
      profile != "mixed_signal")
    return emitFailure(module.getOperation(), "NODAL-VERIFY-CAPABILITY-002",
                       llvm::Twine("unknown target profile '") + profile + "'");

  LogicalResult result = success();
  module.walk([&](Operation *operation) {
    llvm::StringRef name = operation->getName().getStringRef();
    const bool analog =
        name == "nodal.component_contract" || name == "nodal.terminal" || name == "nodal.node" ||
        name == "nodal.connect" || name == "nodal.alias" || name == "nodal.reference" ||
        name == "nodal.branch" || name == "nodal.connection_set" ||
        name == "nodal.potential_equality" || name == "nodal.reference_potential" ||
        name == "nodal.flow_conservation" || name == "nodal.access" ||
        name == "nodal.terminal_access" || name == "nodal.port_flow_access" ||
        name == "nodal.probe" || name == "nodal.bridge" || name == "nodal.analog" ||
        name == "nodal.real_literal" || name == "nodal.analog_integer_literal" ||
        name == "nodal.parameter_ref" || name == "nodal.analog_add" || name == "nodal.analog_sub" ||
        name == "nodal.analog_mul" || name == "nodal.analog_div" || name == "nodal.analog_neg" ||
        name == "nodal.analog_compare" || name == "nodal.analog_logic" ||
        name == "nodal.analog_select" || name == "nodal.analog_ddt" || name == "nodal.analog_idt" ||
        name == "nodal.contribute";
    const bool digital = name == "nodal.resolved_net" || name == "nodal.net_driver" ||
                         name == "nodal.net_drive" || name == "nodal.crossing" ||
                         name == "nodal.fsm";
    if (profile == "digital" && analog)
      result = emitFailure(operation, "NODAL-VERIFY-CAPABILITY-003",
                           "digital target profile rejects analog or mixed-signal operations");
    if (profile == "analog" && digital)
      result = emitFailure(operation, "NODAL-VERIFY-CAPABILITY-004",
                           "analog target profile rejects digital state/connectivity operations");
  });
  return result;
}

LogicalResult verifyStage(mlir::ModuleOp module, VerificationStage stage) {
  switch (stage) {
  case VerificationStage::Construction:
    return verifyConstruction(module);
  case VerificationStage::Drivers:
    return verifyDrivers(module);
  case VerificationStage::Latches:
    return verifyLatches(module);
  case VerificationStage::Cycles:
    return verifyCycles(module);
  case VerificationStage::Hierarchy:
    return verifyHierarchy(module);
  case VerificationStage::Types:
    return verifyTypes(module);
  case VerificationStage::Parameters:
    return verifyParameters(module);
  case VerificationStage::EnumFsm:
    return verifyEnumFsm(module);
  case VerificationStage::Domains:
    return verifyDomains(module);
  case VerificationStage::Protocols:
    return verifyProtocols(module);
  case VerificationStage::Effects:
    return verifyEffects(module);
  case VerificationStage::Analog:
    return verifyAnalog(module);
  case VerificationStage::Capabilities:
    return verifyCapabilities(module);
  }
  llvm_unreachable("unknown Nodal verification stage");
}

template <typename Derived, VerificationStage StageValue>
class VerificationPassBase : public PassWrapper<Derived, OperationPass<mlir::ModuleOp>> {
public:
  void runOnOperation() final {
    (void)this->template getAnalysis<InventoryAnalysis>();
    if (failed(verifyStage(this->getOperation(), StageValue))) {
      this->signalPassFailure();
      return;
    }
    this->markAllAnalysesPreserved();
  }
};

#define NODAL_DEFINE_VERIFICATION_PASS(CLASS_NAME, ARGUMENT, DESCRIPTION, STAGE)                   \
  class CLASS_NAME final : public VerificationPassBase<CLASS_NAME, VerificationStage::STAGE> {     \
  public:                                                                                          \
    MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(CLASS_NAME)                                       \
    llvm::StringRef getArgument() const final { return ARGUMENT; }                                 \
    llvm::StringRef getDescription() const final { return DESCRIPTION; }                           \
  }

NODAL_DEFINE_VERIFICATION_PASS(VerifyConstructionPass, "nodal-verify-construction",
                               "Verify closed Nodal construction state", Construction);
NODAL_DEFINE_VERIFICATION_PASS(VerifyDriversPass, "nodal-verify-drivers",
                               "Verify driver and assignment coverage", Drivers);
NODAL_DEFINE_VERIFICATION_PASS(VerifyLatchesPass, "nodal-verify-latches", "Verify latch freedom",
                               Latches);
NODAL_DEFINE_VERIFICATION_PASS(VerifyCyclesPass, "nodal-verify-cycles",
                               "Verify combinational-cycle freedom", Cycles);
NODAL_DEFINE_VERIFICATION_PASS(VerifyHierarchyPass, "nodal-verify-hierarchy",
                               "Verify hierarchy closure and recursion freedom", Hierarchy);
NODAL_DEFINE_VERIFICATION_PASS(VerifyTypesPass, "nodal-verify-types",
                               "Verify widths, signs, shapes, layouts, and storage", Types);
NODAL_DEFINE_VERIFICATION_PASS(VerifyParametersPass, "nodal-verify-parameters",
                               "Verify parameters, generate regions, and loops", Parameters);
NODAL_DEFINE_VERIFICATION_PASS(VerifyEnumFsmPass, "nodal-verify-enum-fsm",
                               "Verify semantic enums and FSM graphs", EnumFsm);
NODAL_DEFINE_VERIFICATION_PASS(VerifyDomainsPass, "nodal-verify-domains",
                               "Verify clock/reset domains and CDC/RDC", Domains);
NODAL_DEFINE_VERIFICATION_PASS(VerifyProtocolsPass, "nodal-verify-protocols",
                               "Verify Interface, protocol, and pipeline identity", Protocols);
NODAL_DEFINE_VERIFICATION_PASS(VerifyEffectsPass, "nodal-verify-effects",
                               "Verify memory and external-effect contracts", Effects);
NODAL_DEFINE_VERIFICATION_PASS(VerifyAnalogPass, "nodal-verify-analog",
                               "Verify analog topology and explicit mixed-signal bridges", Analog);
NODAL_DEFINE_VERIFICATION_PASS(VerifyCapabilitiesPass, "nodal-verify-capabilities",
                               "Verify selected target capabilities", Capabilities);

#undef NODAL_DEFINE_VERIFICATION_PASS

llvm::SmallVector<llvm::StringRef, 16> stageNames(GateProfile profile) {
  if (profile == GateProfile::Fast)
    return {"construction",   "hierarchy", "types",       "parameters",
            "analog-numeric", "domains",   "capabilities"};
  return {"construction", "drivers",    "latches",        "cycles",      "hierarchy",
          "types",        "parameters", "analog-numeric", "enum-fsm",    "domains",
          "protocols",    "effects",    "analog",         "capabilities"};
}

std::unique_ptr<Pass> createFoldAnalogConstantsPass();
std::unique_ptr<Pass> createVerifyAnalogNumericPass();

void addVerifierPasses(OpPassManager &manager, GateProfile profile) {
  manager.addPass(std::make_unique<VerifyConstructionPass>());
  if (profile != GateProfile::Fast) {
    manager.addPass(std::make_unique<VerifyDriversPass>());
    manager.addPass(std::make_unique<VerifyLatchesPass>());
    manager.addPass(std::make_unique<VerifyCyclesPass>());
  }
  manager.addPass(std::make_unique<VerifyHierarchyPass>());
  manager.addPass(std::make_unique<VerifyTypesPass>());
  manager.addPass(std::make_unique<VerifyParametersPass>());
  manager.addPass(createFoldAnalogConstantsPass());
  manager.addPass(createVerifyAnalogNumericPass());
  if (profile != GateProfile::Fast)
    manager.addPass(std::make_unique<VerifyEnumFsmPass>());
  manager.addPass(std::make_unique<VerifyDomainsPass>());
  if (profile != GateProfile::Fast) {
    manager.addPass(std::make_unique<VerifyProtocolsPass>());
    manager.addPass(std::make_unique<VerifyEffectsPass>());
    manager.addPass(std::make_unique<VerifyAnalogPass>());
  }
  manager.addPass(createCrossLayerDiagnosticPass());
  manager.addPass(std::make_unique<VerifyCapabilitiesPass>());
}

class MaterializeConservativeConnectivityPass final
    : public PassWrapper<MaterializeConservativeConnectivityPass, OperationPass<mlir::ModuleOp>> {
public:
  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(MaterializeConservativeConnectivityPass)

  llvm::StringRef getArgument() const final {
    return "nodal-materialize-conservative-connectivity";
  }
  llvm::StringRef getDescription() const final {
    return "Build deterministic conservative connection sets and conservation equations";
  }

  void runOnOperation() final {
    if (failed(materializeConservativeConnectivity(getOperation())))
      signalPassFailure();
  }
};

class FoldAnalogConstantsPass final
    : public PassWrapper<FoldAnalogConstantsPass, OperationPass<mlir::ModuleOp>> {
public:
  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(FoldAnalogConstantsPass)

  llvm::StringRef getArgument() const final { return "nodal-fold-analog-constants"; }
  llvm::StringRef getDescription() const final {
    return "Annotate pure analog numeric constants while retaining authored operations";
  }

  void runOnOperation() final {
    if (failed(foldAnalogNumericConstants(getOperation())))
      signalPassFailure();
  }
};

class VerifyAnalogNumericPass final
    : public PassWrapper<VerifyAnalogNumericPass, OperationPass<mlir::ModuleOp>> {
public:
  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(VerifyAnalogNumericPass)

  llvm::StringRef getArgument() const final { return "nodal-verify-analog-numeric"; }
  llvm::StringRef getDescription() const final {
    return "Verify analog numeric promotion, dimensions, logic, and selection";
  }

  void runOnOperation() final {
    if (failed(verifyAnalogNumericModel(getOperation()))) {
      signalPassFailure();
      return;
    }
    markAllAnalysesPreserved();
  }
};

std::unique_ptr<Pass> createFoldAnalogConstantsPass() {
  return std::make_unique<FoldAnalogConstantsPass>();
}

std::unique_ptr<Pass> createVerifyAnalogNumericPass() {
  return std::make_unique<VerifyAnalogNumericPass>();
}

class NormalizePipelinePass final
    : public PassWrapper<NormalizePipelinePass, OperationPass<mlir::ModuleOp>> {
public:
  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(NormalizePipelinePass)

  explicit NormalizePipelinePass(GateProfile profile = GateProfile::Default) : profile(profile) {}

  llvm::StringRef getArgument() const final { return "nodal-normalize-pipeline"; }
  llvm::StringRef getDescription() const final {
    return "Record deterministic accepted Nodal pipeline state";
  }

  void runOnOperation() final {
    mlir::ModuleOp module = getOperation();
    MLIRContext *context = module.getContext();
    llvm::SmallVector<Attribute, 16> stages;
    for (llvm::StringRef name : stageNames(profile))
      stages.push_back(StringAttr::get(context, name));
    module->setAttr("nodal.pipeline.version", IntegerAttr::get(IntegerType::get(context, 64), 1));
    module->setAttr("nodal.pipeline.profile",
                    StringAttr::get(context, stringifyGateProfile(profile)));
    module->setAttr("nodal.pipeline.stages", ArrayAttr::get(context, stages));
    module->setAttr("nodal.pipeline.normalized", StringAttr::get(context, "v1"));
  }

private:
  GateProfile profile;
};

LogicalResult runPipeline(mlir::ModuleOp module, GateProfile profile) {
  PassManager manager(module.getContext(), mlir::ModuleOp::getOperationName());
  manager.enableVerifier(true);
  manager.addPass(std::make_unique<MaterializeConservativeConnectivityPass>());
  manager.addPass(createNormalizePotentialFlowAccessPass());
  addVerifierPasses(manager, profile);
  manager.addPass(std::make_unique<NormalizePipelinePass>(profile));
  return manager.run(module);
}

void commitModule(mlir::ModuleOp destination, mlir::ModuleOp source) {
  destination->setLoc(source->getLoc());
  destination->setAttrs(source->getAttrDictionary());
  destination.getBodyRegion().takeBody(source.getBodyRegion());
}

class TransactionalGatePass final
    : public PassWrapper<TransactionalGatePass, OperationPass<mlir::ModuleOp>> {
public:
  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(TransactionalGatePass)

  explicit TransactionalGatePass(GateProfile profile = GateProfile::Default) : profile(profile) {}

  llvm::StringRef getArgument() const final { return "nodal-transactional-gate"; }
  llvm::StringRef getDescription() const final {
    return "Clone, verify, normalize, and commit one Nodal gate transaction";
  }

  void runOnOperation() final {
    if (failed(runNodalPipelineTransaction(getOperation(), profile)))
      signalPassFailure();
  }

private:
  GateProfile profile;
};

} // namespace

llvm::StringRef stringifyGateProfile(GateProfile profile) {
  switch (profile) {
  case GateProfile::Fast:
    return "fast";
  case GateProfile::Default:
    return "default";
  case GateProfile::Release:
    return "release";
  }
  llvm_unreachable("unknown Nodal gate profile");
}

PipelineSession::PipelineSession(MLIRContext *context) : context(context) {
  assert(context && "PipelineSession requires an MLIR context");
}

PipelineSession::~PipelineSession() = default;

LogicalResult PipelineSession::accept(mlir::ModuleOp candidate, GateProfile profile) {
  if (candidate.getContext() != context)
    return emitFailure(candidate.getOperation(), "NODAL-PIPELINE-TRANSACTION-001",
                       "candidate belongs to another MLIR context");
  OwningOpRef<mlir::ModuleOp> working(llvm::cast<mlir::ModuleOp>(candidate->clone()));
  if (failed(runPipeline(*working, profile)))
    return failure();
  accepted = std::move(working);
  return success();
}

bool PipelineSession::hasAccepted() const { return static_cast<bool>(accepted); }

mlir::ModuleOp PipelineSession::getAccepted() const {
  return accepted ? accepted.get() : mlir::ModuleOp();
}

OwningOpRef<mlir::ModuleOp> PipelineSession::cloneAccepted() const {
  if (!accepted)
    return {};
  return OwningOpRef<mlir::ModuleOp>(
      llvm::cast<mlir::ModuleOp>(accepted.get().getOperation()->clone()));
}

LogicalResult runNodalPipelineTransaction(mlir::ModuleOp module, GateProfile profile) {
  OwningOpRef<mlir::ModuleOp> working(llvm::cast<mlir::ModuleOp>(module->clone()));
  if (failed(runPipeline(*working, profile)))
    return failure();
  commitModule(module, *working);
  return success();
}

void registerNodalPasses() {
  static PassRegistration<MaterializeConservativeConnectivityPass> connectivity;
  static PassRegistration<VerifyConstructionPass> construction;
  static PassRegistration<VerifyDriversPass> drivers;
  static PassRegistration<VerifyLatchesPass> latches;
  static PassRegistration<VerifyCyclesPass> cycles;
  static PassRegistration<VerifyHierarchyPass> hierarchy;
  static PassRegistration<VerifyTypesPass> types;
  static PassRegistration<VerifyParametersPass> parameters;
  static PassRegistration<FoldAnalogConstantsPass> analogFolding;
  static PassRegistration<VerifyAnalogNumericPass> analogNumeric;
  static PassRegistration<VerifyEnumFsmPass> enumFsm;
  static PassRegistration<VerifyDomainsPass> domains;
  static PassRegistration<VerifyProtocolsPass> protocols;
  static PassRegistration<VerifyEffectsPass> effects;
  static PassRegistration<VerifyAnalogPass> analog;
  static PassRegistration<VerifyCapabilitiesPass> capabilities;

  static PassPipelineRegistration<> fast(
      "nodal-gate-fast", "Transactional fast Nodal semantic gate", [](OpPassManager &manager) {
        manager.addPass(std::make_unique<TransactionalGatePass>(GateProfile::Fast));
      });
  static PassPipelineRegistration<> defaults(
      "nodal-gate-default", "Transactional default Nodal semantic gate",
      [](OpPassManager &manager) {
        manager.addPass(std::make_unique<TransactionalGatePass>(GateProfile::Default));
      });
  static PassPipelineRegistration<> release(
      "nodal-gate-release", "Transactional release Nodal semantic gate",
      [](OpPassManager &manager) {
        manager.addPass(std::make_unique<TransactionalGatePass>(GateProfile::Release));
      });

  (void)connectivity;
  (void)construction;
  (void)drivers;
  (void)latches;
  (void)cycles;
  (void)hierarchy;
  (void)types;
  (void)parameters;
  (void)analogFolding;
  (void)analogNumeric;
  (void)enumFsm;
  (void)domains;
  (void)protocols;
  (void)effects;
  (void)analog;
  (void)capabilities;
  (void)fast;
  (void)defaults;
  (void)release;
}

} // namespace nodal
