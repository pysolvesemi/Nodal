//===- Verification.cpp - Nodal staged semantic verification -------------===//
//
// SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
//
//===----------------------------------------------------------------------===//

#include "nodal/Transforms/Verification.h"

#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"

#include "mlir/IR/AsmState.h"
#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinTypes.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/Pass/PassRegistry.h"

#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringSet.h"
#include "llvm/Support/CommandLine.h"
#include "llvm/Support/Regex.h"
#include "llvm/Support/raw_ostream.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <functional>
#include <initializer_list>
#include <map>
#include <set>
#include <string>
#include <utility>
#include <vector>

using namespace mlir;

namespace nodal {
namespace {

constexpr llvm::StringLiteral kBridgeSchema = "nodal.scala-to-mlir";
constexpr int64_t kBridgeVersion = 1;

const VerificationStage kStages[] = {
    VerificationStage::Construction,      VerificationStage::Hierarchy,
    VerificationStage::Connectivity,      VerificationStage::TypeShape,
    VerificationStage::ParameterLoop,     VerificationStage::EnumFsm,
    VerificationStage::Domain,            VerificationStage::ProtocolPipeline,
    VerificationStage::MemoryEffect,      VerificationStage::AnalogMixed,
    VerificationStage::TargetCapability,
};

bool isOneOf(llvm::StringRef value, std::initializer_list<llvm::StringRef> choices) {
  return llvm::any_of(choices, [value](llvm::StringRef choice) { return value == choice; });
}

LogicalResult reject(Operation *operation, llvm::StringRef code, const llvm::Twine &message) {
  operation->emitError() << code << ": " << message;
  return failure();
}

llvm::StringRef operationName(Operation *operation) {
  return operation->getName().getStringRef();
}

llvm::StringRef stringAttribute(Operation *operation, llvm::StringRef name) {
  if (auto value = operation->getAttrOfType<StringAttr>(name))
    return value.getValue();
  return {};
}

llvm::StringRef stringEntry(DictionaryAttr dictionary, llvm::StringRef name) {
  if (!dictionary)
    return {};
  if (auto value = dictionary.getAs<StringAttr>(name))
    return value.getValue();
  return {};
}

FlatSymbolRefAttr flatReference(Operation *operation, llvm::StringRef name) {
  return operation->getAttrOfType<FlatSymbolRefAttr>(name);
}

SmallVector<Operation *> directChildren(Operation *operation) {
  SmallVector<Operation *> children;
  for (Region &region : operation->getRegions())
    for (Block &block : region)
      for (Operation &child : block)
        children.push_back(&child);
  return children;
}

SmallVector<Operation *> semanticModules(ModuleOp module) {
  SmallVector<Operation *> result;
  for (Operation &operation : module.getBody()->getOperations())
    if (operationName(&operation) == "nodal.module")
      result.push_back(&operation);
  return result;
}

ArrayAttr rootArray(ModuleOp module, llvm::StringRef name) {
  return module->getAttrOfType<ArrayAttr>(name);
}

struct DeclarationInfo {
  std::string path;
  std::string kind;
  std::string name;
  std::string dataType;
  std::string domain;
  DictionaryAttr attributes;
};

SmallVector<DeclarationInfo> declarationInventory(ModuleOp module) {
  SmallVector<DeclarationInfo> declarations;
  ArrayAttr inventory = rootArray(module, "nodal.bridge.declarations");
  if (!inventory)
    return declarations;
  for (Attribute attribute : inventory) {
    auto dictionary = dyn_cast<DictionaryAttr>(attribute);
    if (!dictionary)
      continue;
    declarations.push_back(
        {stringEntry(dictionary, "path").str(), stringEntry(dictionary, "kind").str(),
         stringEntry(dictionary, "name").str(), stringEntry(dictionary, "data_type").str(),
         stringEntry(dictionary, "domain").str(),
         dictionary.getAs<DictionaryAttr>("attributes")});
  }
  return declarations;
}

std::map<std::string, DeclarationInfo> declarationMap(ModuleOp module) {
  std::map<std::string, DeclarationInfo> result;
  for (const DeclarationInfo &declaration : declarationInventory(module))
    result.emplace(declaration.path, declaration);
  return result;
}

LogicalResult verifyConstruction(ModuleOp module) {
  if (semanticModules(module).empty())
    return reject(module, "NODAL-VERIFY-CONSTRUCTION-001",
                  "the builtin module contains no closed nodal.module operation");

  if (auto schema = module->getAttrOfType<StringAttr>("nodal.bridge.schema"))
    if (schema.getValue() != kBridgeSchema)
      return reject(module, "NODAL-VERIFY-CONSTRUCTION-002",
                    "unsupported Scala-to-MLIR bridge schema '" + schema.getValue() + "'");
  if (auto version = module->getAttrOfType<IntegerAttr>("nodal.bridge.version"))
    if (version.getInt() != kBridgeVersion)
      return reject(module, "NODAL-VERIFY-CONSTRUCTION-003",
                    "unsupported Scala-to-MLIR bridge version");

  std::set<std::pair<const Operation *, std::string>> symbols;
  WalkResult result = module.walk([&](Operation *operation) -> WalkResult {
    llvm::StringRef name = operationName(operation);
    if (name == "nodal.placeholder" && semanticModules(module).size() != 0) {
      (void)reject(operation, "NODAL-VERIFY-CONSTRUCTION-004",
                   "placeholder operations cannot enter the semantic gate pipeline");
      return WalkResult::interrupt();
    }
    if (isOneOf(name, {"nodal.module", "nodal.interface", "nodal.enum", "nodal.fsm",
                       "nodal.fsm_state", "nodal.generate", "nodal.hardware_loop"})) {
      if (operation->getNumRegions() != 1 || operation->getRegion(0).getBlocks().size() != 1) {
        (void)reject(operation, "NODAL-VERIFY-CONSTRUCTION-005",
                     "region-owning semantic operation is not construction-closed");
        return WalkResult::interrupt();
      }
    }
    if (auto symbol = operation->getAttrOfType<StringAttr>("sym_name")) {
      if (symbol.getValue().trim().empty()) {
        (void)reject(operation, "NODAL-VERIFY-CONSTRUCTION-006",
                     "semantic symbol must not be empty");
        return WalkResult::interrupt();
      }
      auto key = std::make_pair(operation->getParentOp(), symbol.getValue().str());
      if (!symbols.insert(key).second) {
        (void)reject(operation, "NODAL-VERIFY-CONSTRUCTION-007",
                     "duplicate semantic symbol in one scope: '" + symbol.getValue() + "'");
        return WalkResult::interrupt();
      }
    }
    return WalkResult::advance();
  });
  return result.wasInterrupted() ? failure() : success();
}

LogicalResult verifyHierarchy(ModuleOp module) {
  std::map<std::string, Operation *> modules;
  for (Operation *semanticModule : semanticModules(module)) {
    llvm::StringRef symbol = stringAttribute(semanticModule, "sym_name");
    if (!modules.emplace(symbol.str(), semanticModule).second)
      return reject(semanticModule, "NODAL-VERIFY-HIERARCHY-001",
                    "duplicate module symbol '" + symbol + "'");
  }

  std::map<std::string, std::vector<std::string>> graph;
  for (const auto &[symbol, semanticModule] : modules) {
    for (Operation *child : directChildren(semanticModule)) {
      if (operationName(child) != "nodal.instance")
        continue;
      FlatSymbolRefAttr target = flatReference(child, "module");
      if (!target || !modules.count(target.getValue().str()))
        return reject(child, "NODAL-VERIFY-HIERARCHY-002",
                      "instance references unknown module '" +
                          (target ? target.getValue() : llvm::StringRef("")) + "'");
      graph[symbol].push_back(target.getValue().str());
    }
  }

  std::map<std::string, unsigned> color;
  std::function<LogicalResult(const std::string &)> visit =
      [&](const std::string &symbol) -> LogicalResult {
    if (color[symbol] == 1)
      return reject(modules[symbol], "NODAL-VERIFY-HIERARCHY-003",
                    "recursive module-instantiation cycle reaches '" + symbol + "'");
    if (color[symbol] == 2)
      return success();
    color[symbol] = 1;
    for (const std::string &child : graph[symbol])
      if (failed(visit(child)))
        return failure();
    color[symbol] = 2;
    return success();
  };
  for (const auto &[symbol, operation] : modules)
    if (failed(visit(symbol)))
      return failure();
  return success();
}

LogicalResult verifyConnectivity(ModuleOp module) {
  std::map<std::string, DeclarationInfo> declarations = declarationMap(module);
  ArrayAttr origins = rootArray(module, "nodal.bridge.origins");
  if (!origins)
    return success();

  std::map<std::string, std::string> idToPath;
  for (Attribute attribute : origins) {
    auto origin = dyn_cast<DictionaryAttr>(attribute);
    if (!origin)
      continue;
    llvm::StringRef id = stringEntry(origin, "id");
    llvm::StringRef path = stringEntry(origin, "path");
    if (!id.empty() && !path.empty())
      idToPath[id.str()] = path.str();
  }

  std::map<std::string, unsigned> drivers;
  std::map<std::string, std::vector<std::string>> graph;
  std::set<std::string> stateBoundaries;
  for (const auto &[path, declaration] : declarations)
    if (isOneOf(declaration.kind, {"register", "memory"}))
      stateBoundaries.insert(path);

  for (Attribute attribute : origins) {
    auto origin = dyn_cast<DictionaryAttr>(attribute);
    if (!origin)
      continue;
    llvm::StringRef path = stringEntry(origin, "path");
    llvm::StringRef sink = stringEntry(origin, "sink");
    llvm::StringRef operation = stringEntry(origin, "operation");
    if (operation == "assignment" && !sink.empty())
      ++drivers[sink.str()];

    if (auto parents = origin.getAs<ArrayAttr>("parents")) {
      for (Attribute parentAttribute : parents) {
        auto parent = dyn_cast<StringAttr>(parentAttribute);
        if (!parent)
          continue;
        auto found = idToPath.find(parent.getValue().str());
        llvm::StringRef parentPath =
            found == idToPath.end() ? parent.getValue() : llvm::StringRef(found->second);
        if (!parentPath.empty() && parentPath != path && !stateBoundaries.count(parentPath.str()))
          graph[parentPath.str()].push_back(path.str());
      }
    }
    if (!path.empty() && !sink.empty() && path != sink && !stateBoundaries.count(sink.str()))
      graph[path.str()].push_back(sink.str());
  }

  for (const auto &[path, declaration] : declarations) {
    if (isOneOf(declaration.kind, {"output", "wire", "variable", "register"}) &&
        drivers[path] == 0)
      return reject(module, "NODAL-VERIFY-DRIVER-001",
                    "declaration has no complete assignment driver: '" + path + "'");
    llvm::StringRef allowMultiple = stringEntry(declaration.attributes, "allow_multiple_drivers");
    if (drivers[path] > 1 && allowMultiple != "true")
      return reject(module, "NODAL-VERIFY-DRIVER-002",
                    "ordinary declaration has multiple assignment drivers: '" + path + "'");
    if (stringEntry(declaration.attributes, "assignment_coverage") == "partial")
      return reject(module, "NODAL-VERIFY-LATCH-001",
                    "partial assignment coverage would infer a latch: '" + path + "'");
  }

  std::map<std::string, unsigned> color;
  std::function<bool(const std::string &)> cycle = [&](const std::string &node) {
    if (color[node] == 1)
      return true;
    if (color[node] == 2)
      return false;
    color[node] = 1;
    for (const std::string &next : graph[node])
      if (cycle(next))
        return true;
    color[node] = 2;
    return false;
  };
  for (const auto &[node, edges] : graph)
    if (cycle(node))
      return reject(module, "NODAL-VERIFY-CYCLE-001",
                    "combinational origin graph contains a cycle through '" + node + "'");
  return success();
}

LogicalResult verifyType(Type type, Operation *owner) {
  if (auto bits = dyn_cast<nodal::BitsType>(type)) {
    if (bits.getWidth() <= 0)
      return reject(owner, "NODAL-VERIFY-TYPE-001", "Bits width must be positive");
    return success();
  }
  if (auto integer = dyn_cast<nodal::UIntType>(type)) {
    if (integer.getWidth() <= 0)
      return reject(owner, "NODAL-VERIFY-TYPE-001", "UInt width must be positive");
    return success();
  }
  if (auto integer = dyn_cast<nodal::SIntType>(type)) {
    if (integer.getWidth() <= 0)
      return reject(owner, "NODAL-VERIFY-TYPE-001", "SInt width must be positive");
    return success();
  }
  if (auto shaped = dyn_cast<nodal::ShapedType>(type)) {
    SmallVector<llvm::StringRef> dimensions;
    shaped.getDimensions().split(dimensions, ',');
    llvm::Regex symbolic("^[A-Za-z_][A-Za-z0-9_+*/-]*$");
    if (dimensions.empty())
      return reject(owner, "NODAL-VERIFY-SHAPE-001", "shaped type has zero rank");
    for (llvm::StringRef dimension : dimensions) {
      dimension = dimension.trim();
      bool validInteger = false;
      int64_t value = 0;
      if (!dimension.getAsInteger(10, value))
        validInteger = value > 0;
      if (!validInteger && !symbolic.match(dimension))
        return reject(owner, "NODAL-VERIFY-SHAPE-002",
                      "invalid shaped dimension '" + dimension + "'");
    }
    return verifyType(shaped.getElementType(), owner);
  }
  if (auto valid = dyn_cast<nodal::ValidType>(type))
    return verifyType(valid.getPayloadType(), owner);
  if (auto stream = dyn_cast<nodal::StreamType>(type))
    return verifyType(stream.getPayloadType(), owner);
  if (auto resolved = dyn_cast<nodal::ResolvedType>(type))
    return verifyType(resolved.getElementType(), owner);
  if (auto driver = dyn_cast<nodal::DriverType>(type))
    return verifyType(driver.getElementType(), owner);
  if (auto enumeration = dyn_cast<nodal::EnumType>(type)) {
    if (enumeration.getWidth() <= 0)
      return reject(owner, "NODAL-VERIFY-TYPE-001", "enum width must be positive");
  }
  return success();
}

LogicalResult verifyTypeShape(ModuleOp module) {
  WalkResult result = module.walk([&](Operation *operation) -> WalkResult {
    for (Value operand : operation->getOperands())
      if (failed(verifyType(operand.getType(), operation)))
        return WalkResult::interrupt();
    for (Value resultValue : operation->getResults())
      if (failed(verifyType(resultValue.getType(), operation)))
        return WalkResult::interrupt();
    for (NamedAttribute attribute : operation->getAttrs())
      if (auto type = dyn_cast<TypeAttr>(attribute.getValue()))
        if (failed(verifyType(type.getValue(), operation)))
          return WalkResult::interrupt();
    return WalkResult::advance();
  });
  if (result.wasInterrupted())
    return failure();

  for (const DeclarationInfo &declaration : declarationInventory(module)) {
    llvm::StringRef storage = stringEntry(declaration.attributes, "storage");
    if (declaration.kind == "memory" && storage == "structural")
      return reject(module, "NODAL-VERIFY-STORAGE-001",
                    "memory declaration claims structural Vec storage: '" + declaration.path + "'");
    if (declaration.kind != "memory" && storage == "memory")
      return reject(module, "NODAL-VERIFY-STORAGE-002",
                    "non-memory declaration claims addressable memory storage: '" +
                        declaration.path + "'");
    llvm::StringRef layout = stringEntry(declaration.attributes, "layout");
    if (layout == "unknown" || layout == "implicit")
      return reject(module, "NODAL-VERIFY-LAYOUT-001",
                    "declaration has no explicit legal layout contract: '" + declaration.path + "'");
  }
  return success();
}

LogicalResult verifyParameterLoop(ModuleOp module) {
  std::map<std::string, Operation *> modules;
  std::map<std::string, std::set<std::string>> parameters;
  for (Operation *semanticModule : semanticModules(module)) {
    std::string symbol = stringAttribute(semanticModule, "sym_name").str();
    modules[symbol] = semanticModule;
    for (Operation *child : directChildren(semanticModule))
      if (operationName(child) == "nodal.parameter")
        parameters[symbol].insert(stringAttribute(child, "sym_name").str());
  }

  WalkResult result = module.walk([&](Operation *operation) -> WalkResult {
    llvm::StringRef name = operationName(operation);
    if (name == "nodal.generate" || name == "nodal.hardware_loop") {
      Attribute lowerAttribute = operation->getAttr("lower");
      Attribute upperAttribute = operation->getAttr("upper");
      Attribute stepAttribute = operation->getAttr("step");
      if (!lowerAttribute || !upperAttribute || !stepAttribute) {
        (void)reject(operation, "NODAL-VERIFY-LOOP-001",
                     "loop requires lower, upper, and step bounds");
        return WalkResult::interrupt();
      }
      auto step = dyn_cast<IntegerAttr>(stepAttribute);
      if (step && step.getInt() == 0) {
        (void)reject(operation, "NODAL-VERIFY-LOOP-002", "loop step must not be zero");
        return WalkResult::interrupt();
      }
      auto lower = dyn_cast<IntegerAttr>(lowerAttribute);
      auto upper = dyn_cast<IntegerAttr>(upperAttribute);
      if (lower && upper && step) {
        int64_t low = lower.getInt();
        int64_t high = upper.getInt();
        int64_t stride = step.getInt();
        if ((stride > 0 && low > high) || (stride < 0 && low < high)) {
          (void)reject(operation, "NODAL-VERIFY-LOOP-003",
                       "loop step direction cannot reach its upper bound");
          return WalkResult::interrupt();
        }
        long double span = std::fabs(static_cast<long double>(high) - low);
        long double trips = stride == 0 ? 0 : span / std::fabs(static_cast<long double>(stride));
        if (trips > 1000000.0L) {
          (void)reject(operation, "NODAL-VERIFY-LOOP-004",
                       "loop exceeds the bounded hardware-iteration limit");
          return WalkResult::interrupt();
        }
      }
      if (name == "nodal.hardware_loop") {
        WalkResult nested = operation->walk([&](Operation *nestedOperation) -> WalkResult {
          if (nestedOperation == operation)
            return WalkResult::advance();
          if (isOneOf(operationName(nestedOperation),
                      {"nodal.module", "nodal.port", "nodal.parameter", "nodal.instance",
                       "nodal.interface"})) {
            (void)reject(nestedOperation, "NODAL-VERIFY-LOOP-005",
                         "bounded hardware loop contains a structural declaration");
            return WalkResult::interrupt();
          }
          return WalkResult::advance();
        });
        if (nested.wasInterrupted())
          return WalkResult::interrupt();
      }
    }

    if (name == "nodal.instance") {
      FlatSymbolRefAttr target = flatReference(operation, "module");
      auto bindings = operation->getAttrOfType<DictionaryAttr>("parameter_bindings");
      if (target && bindings) {
        auto found = parameters.find(target.getValue().str());
        if (found != parameters.end())
          for (NamedAttribute binding : bindings)
            if (!found->second.count(binding.getName().getValue().str())) {
              (void)reject(operation, "NODAL-VERIFY-PARAMETER-001",
                           "instance binds unknown parameter '" + binding.getName().getValue() +
                               "'");
              return WalkResult::interrupt();
            }
      }
    }
    return WalkResult::advance();
  });
  return result.wasInterrupted() ? failure() : success();
}

LogicalResult verifyEnumFsm(ModuleOp module) {
  std::map<std::string, Operation *> enumerations;
  for (Operation &operation : module.getBody()->getOperations())
    if (operationName(&operation) == "nodal.enum")
      enumerations[stringAttribute(&operation, "sym_name").str()] = &operation;

  WalkResult result = module.walk([&](Operation *operation) -> WalkResult {
    if (operationName(operation) != "nodal.fsm")
      return WalkResult::advance();
    auto stateType = operation->getAttrOfType<TypeAttr>("state_type");
    auto enumeration = stateType ? dyn_cast<nodal::EnumType>(stateType.getValue())
                                 : nodal::EnumType();
    if (!enumeration || !enumerations.count(enumeration.getSymbol().str())) {
      (void)reject(operation, "NODAL-VERIFY-FSM-001",
                   "FSM state type does not resolve to a semantic enum");
      return WalkResult::interrupt();
    }

    std::map<std::string, Operation *> states;
    std::string initial;
    for (Operation *child : directChildren(operation)) {
      if (operationName(child) != "nodal.fsm_state")
        continue;
      std::string symbol = stringAttribute(child, "sym_name").str();
      states[symbol] = child;
      if (auto value = child->getAttrOfType<BoolAttr>("initial"))
        if (value.getValue())
          initial = symbol;
    }
    if (initial.empty()) {
      (void)reject(operation, "NODAL-VERIFY-FSM-002", "FSM has no initial state");
      return WalkResult::interrupt();
    }

    std::map<std::string, std::vector<std::string>> graph;
    for (const auto &[symbol, state] : states)
      for (Operation *child : directChildren(state))
        if (operationName(child) == "nodal.fsm_transition") {
          FlatSymbolRefAttr destination = flatReference(child, "destination");
          if (!destination || !states.count(destination.getValue().str())) {
            (void)reject(child, "NODAL-VERIFY-FSM-003",
                         "transition destination is outside its FSM");
            return WalkResult::interrupt();
          }
          graph[symbol].push_back(destination.getValue().str());
        }
    for (Operation *child : directChildren(operation))
      if (operationName(child) == "nodal.fsm_completion") {
        FlatSymbolRefAttr source = flatReference(child, "source");
        FlatSymbolRefAttr destination = flatReference(child, "destination");
        if (!source || !destination || !states.count(source.getValue().str()) ||
            !states.count(destination.getValue().str())) {
          (void)reject(child, "NODAL-VERIFY-FSM-003",
                       "completion endpoint is outside its FSM");
          return WalkResult::interrupt();
        }
        graph[source.getValue().str()].push_back(destination.getValue().str());
      }

    std::set<std::string> reachable;
    std::vector<std::string> worklist{initial};
    while (!worklist.empty()) {
      std::string current = worklist.back();
      worklist.pop_back();
      if (!reachable.insert(current).second)
        continue;
      for (const std::string &next : graph[current])
        worklist.push_back(next);
    }
    for (const auto &[symbol, state] : states)
      if (!reachable.count(symbol)) {
        (void)reject(state, "NODAL-VERIFY-FSM-004",
                     "FSM state is unreachable from reset: '" + symbol + "'");
        return WalkResult::interrupt();
      }
    return WalkResult::advance();
  });
  return result.wasInterrupted() ? failure() : success();
}

LogicalResult verifyDomain(ModuleOp module) {
  for (Operation *semanticModule : semanticModules(module)) {
    std::set<std::string> domains;
    std::set<std::string> requirements;
    std::set<std::string> boundRequirements;
    for (Operation *child : directChildren(semanticModule)) {
      llvm::StringRef name = operationName(child);
      if (name == "nodal.domain")
        domains.insert(stringAttribute(child, "sym_name").str());
      if (name == "nodal.domain_requirement") {
        std::string symbol = stringAttribute(child, "sym_name").str();
        domains.insert(symbol);
        requirements.insert(symbol);
      }
    }

    for (Operation *child : directChildren(semanticModule)) {
      llvm::StringRef name = operationName(child);
      if (name == "nodal.port") {
        FlatSymbolRefAttr domain = flatReference(child, "domain");
        if (!domain || !domains.count(domain.getValue().str()))
          return reject(child, "NODAL-VERIFY-DOMAIN-001",
                        "port references an unknown local domain");
      }
      if (name == "nodal.domain_bind") {
        FlatSymbolRefAttr requirement = flatReference(child, "requirement");
        FlatSymbolRefAttr actual = flatReference(child, "actual");
        if (!requirement || !requirements.count(requirement.getValue().str()))
          return reject(child, "NODAL-VERIFY-DOMAIN-002",
                        "domain binding references an unknown requirement");
        DictionaryAttr metadata = child->getAttrOfType<DictionaryAttr>("metadata");
        if (!actual || (!domains.count(actual.getValue().str()) &&
                        stringEntry(metadata, "actual_path").empty()))
          return reject(child, "NODAL-VERIFY-DOMAIN-003",
                        "domain binding actual is not visible and has no hierarchy path");
        boundRequirements.insert(requirement.getValue().str());
      }
      if (name == "nodal.instance") {
        auto bindings = child->getAttrOfType<DictionaryAttr>("domain_bindings");
        if (bindings)
          for (NamedAttribute binding : bindings) {
            auto actual = dyn_cast<FlatSymbolRefAttr>(binding.getValue());
            if (!actual || !domains.count(actual.getValue().str()))
              return reject(child, "NODAL-VERIFY-DOMAIN-004",
                            "instance binds an unknown parent domain");
          }
      }
    }

    WalkResult nested = semanticModule->walk([&](Operation *operation) -> WalkResult {
      llvm::StringRef name = operationName(operation);
      if (name == "nodal.crossing") {
        FlatSymbolRefAttr source = flatReference(operation, "source_domain");
        FlatSymbolRefAttr destination = flatReference(operation, "destination_domain");
        if (!source || !destination || !domains.count(source.getValue().str()) ||
            !domains.count(destination.getValue().str())) {
          (void)reject(operation, "NODAL-VERIFY-DOMAIN-005",
                       "crossing endpoint does not resolve in the owning module");
          return WalkResult::interrupt();
        }
      }
      if (name == "nodal.state_owner") {
        FlatSymbolRefAttr domain = flatReference(operation, "domain");
        if (!domain || !domains.count(domain.getValue().str())) {
          (void)reject(operation, "NODAL-VERIFY-DOMAIN-006",
                       "state owner references an unknown domain");
          return WalkResult::interrupt();
        }
      }
      return WalkResult::advance();
    });
    if (nested.wasInterrupted())
      return failure();

    for (const std::string &requirement : requirements)
      if (!boundRequirements.count(requirement))
        return reject(semanticModule, "NODAL-VERIFY-DOMAIN-007",
                      "domain requirement has no explicit binding: '" + requirement + "'");
  }
  return success();
}

LogicalResult verifyProtocolPipeline(ModuleOp module) {
  std::map<std::string, std::set<std::string>> roles;
  std::set<std::string> interfaces;
  for (Operation &operation : module.getBody()->getOperations()) {
    if (operationName(&operation) != "nodal.interface")
      continue;
    std::string symbol = stringAttribute(&operation, "sym_name").str();
    interfaces.insert(symbol);
    for (Operation *child : directChildren(&operation))
      if (operationName(child) == "nodal.interface_role")
        roles[symbol].insert(stringAttribute(child, "sym_name").str());
    for (Operation *child : directChildren(&operation))
      if (operationName(child) == "nodal.interface_member")
        if (auto memberRoles = child->getAttrOfType<ArrayAttr>("roles"))
          for (Attribute attribute : memberRoles) {
            auto role = dyn_cast<StringAttr>(attribute);
            if (!role || !roles[symbol].count(role.getValue().str()))
              return reject(child, "NODAL-VERIFY-PROTOCOL-001",
                            "Interface member references an unknown role");
          }
  }

  std::set<std::string> logicalPaths;
  for (Operation *semanticModule : semanticModules(module)) {
    std::set<std::string> instances;
    for (Operation *child : directChildren(semanticModule)) {
      llvm::StringRef name = operationName(child);
      if (name == "nodal.interface_instance") {
        FlatSymbolRefAttr definition = flatReference(child, "definition");
        if (!definition || !interfaces.count(definition.getValue().str()))
          return reject(child, "NODAL-VERIFY-PROTOCOL-002",
                        "Interface instance references an unknown definition");
        instances.insert(stringAttribute(child, "sym_name").str());
      }
      if (name == "nodal.interface_abi") {
        std::string path = stringAttribute(child, "logical_path").str();
        if (!logicalPaths.insert(path).second)
          return reject(child, "NODAL-VERIFY-PROTOCOL-003",
                        "logical Interface ABI path is duplicated");
      }
    }
    WalkResult nested = semanticModule->walk([&](Operation *operation) -> WalkResult {
      if (operationName(operation) != "nodal.member_access")
        return WalkResult::advance();
      FlatSymbolRefAttr instance = flatReference(operation, "instance");
      if (!instance || !instances.count(instance.getValue().str())) {
        (void)reject(operation, "NODAL-VERIFY-PROTOCOL-004",
                     "member access references an unknown Interface instance");
        return WalkResult::interrupt();
      }
      return WalkResult::advance();
    });
    if (nested.wasInterrupted())
      return failure();
  }

  if (auto schedules = rootArray(module, "nodal.pipeline.schedules"))
    for (Attribute attribute : schedules) {
      auto schedule = dyn_cast<DictionaryAttr>(attribute);
      if (!schedule || stringEntry(schedule, "protocol").empty() ||
          stringEntry(schedule, "latency").empty())
        return reject(module, "NODAL-VERIFY-PIPELINE-001",
                      "pipeline schedule lacks protocol or latency provenance");
    }
  return success();
}

LogicalResult verifyMemoryEffect(ModuleOp module) {
  for (const DeclarationInfo &declaration : declarationInventory(module)) {
    if (declaration.kind != "memory")
      continue;
    if (declaration.dataType.empty() || declaration.domain.empty())
      return reject(module, "NODAL-VERIFY-MEMORY-001",
                    "memory lacks element type or owning domain: '" + declaration.path + "'");
    llvm::StringRef depth = stringEntry(declaration.attributes, "depth");
    llvm::StringRef latency = stringEntry(declaration.attributes, "readlatency");
    if (latency.empty())
      latency = stringEntry(declaration.attributes, "read_latency");
    int64_t depthValue = 0;
    int64_t latencyValue = 0;
    if (depth.empty() || depth.getAsInteger(10, depthValue) || depthValue <= 0)
      return reject(module, "NODAL-VERIFY-MEMORY-002",
                    "memory depth must be a positive static value: '" + declaration.path + "'");
    if (latency.empty() || latency.getAsInteger(10, latencyValue) || latencyValue < 0)
      return reject(module, "NODAL-VERIFY-MEMORY-003",
                    "memory read latency must be known and non-negative: '" +
                        declaration.path + "'");
    if (stringEntry(declaration.attributes, "ordering").empty())
      return reject(module, "NODAL-VERIFY-EFFECT-001",
                    "memory ordering/effect contract is unavailable: '" + declaration.path + "'");
  }
  return success();
}

bool analogDeclaration(llvm::StringRef kind) {
  return isOneOf(kind, {"analog-input", "analog-output", "analog-inout", "analog-node",
                        "conservative-terminal", "analog-signal"});
}

LogicalResult verifyAnalogMixed(ModuleOp module) {
  WalkResult result = module.walk([&](Operation *operation) -> WalkResult {
    if (operationName(operation) != "nodal.bridge")
      return WalkResult::advance();
    if (stringAttribute(operation, "kind").empty() ||
        stringAttribute(operation, "source_domain").empty() ||
        stringAttribute(operation, "destination_domain").empty()) {
      (void)reject(operation, "NODAL-VERIFY-ANALOG-001",
                   "mixed-signal bridge lacks kind or domain provenance");
      return WalkResult::interrupt();
    }
    return WalkResult::advance();
  });
  if (result.wasInterrupted())
    return failure();

  std::map<std::string, DeclarationInfo> declarations = declarationMap(module);
  if (auto topology = rootArray(module, "nodal.bridge.topology"))
    for (Attribute attribute : topology) {
      auto edge = dyn_cast<DictionaryAttr>(attribute);
      if (!edge)
        continue;
      llvm::StringRef kind = stringEntry(edge, "kind");
      llvm::StringRef left = stringEntry(edge, "left");
      llvm::StringRef right = stringEntry(edge, "right");
      auto leftDeclaration = declarations.find(left.str());
      auto rightDeclaration = declarations.find(right.str());
      if (leftDeclaration == declarations.end() || rightDeclaration == declarations.end())
        continue;
      if (isOneOf(kind, {"terminal-connect", "node-connect"}) &&
          (!analogDeclaration(leftDeclaration->second.kind) ||
           !analogDeclaration(rightDeclaration->second.kind)))
        return reject(module, "NODAL-VERIFY-ANALOG-002",
                      "conservative topology connects a non-analog declaration");
      if (kind == "inout-pass-through" &&
          (leftDeclaration->second.kind != "digital-inout" ||
           rightDeclaration->second.kind != "digital-inout"))
        return reject(module, "NODAL-VERIFY-ANALOG-003",
                      "digital inout pass-through crosses semantic categories");
      if (kind == "value-connect" &&
          analogDeclaration(leftDeclaration->second.kind) !=
              analogDeclaration(rightDeclaration->second.kind))
        return reject(module, "NODAL-VERIFY-ANALOG-004",
                      "implicit analog/digital value conversion requires a bridge");
    }
  return success();
}

llvm::StringRef effectiveTarget(ModuleOp module, llvm::StringRef requested) {
  if (requested != "auto")
    return requested;
  if (auto target = module->getAttrOfType<StringAttr>("nodal.target"))
    return target.getValue();
  return "core";
}

LogicalResult verifyTargetCapability(ModuleOp module, llvm::StringRef requested) {
  llvm::StringRef target = effectiveTarget(module, requested);
  if (!isOneOf(target, {"core", "digital", "analog", "mixed"}))
    return reject(module, "NODAL-VERIFY-TARGET-001",
                  "unknown target capability profile '" + target + "'");

  const std::set<std::string> analogOperations = {
      "nodal.terminal", "nodal.node", "nodal.branch", "nodal.access", "nodal.bridge"};
  const std::set<std::string> digitalOperations = {
      "nodal.resolved_net", "nodal.net_read",      "nodal.net_driver",
      "nodal.net_drive",   "nodal.crossing",      "nodal.state_owner",
      "nodal.fsm",         "nodal.fsm_state",     "nodal.fsm_transition",
      "nodal.fsm_action",  "nodal.fsm_completion"};

  WalkResult result = module.walk([&](Operation *operation) -> WalkResult {
    std::string name = operationName(operation).str();
    if (target == "digital" && analogOperations.count(name)) {
      (void)reject(operation, "NODAL-VERIFY-TARGET-002",
                   "digital capability profile rejects analog operation '" + name + "'");
      return WalkResult::interrupt();
    }
    if (target == "analog" && digitalOperations.count(name)) {
      (void)reject(operation, "NODAL-VERIFY-TARGET-003",
                   "analog capability profile rejects digital state/net operation '" + name + "'");
      return WalkResult::interrupt();
    }
    return WalkResult::advance();
  });
  return result.wasInterrupted() ? failure() : success();
}

struct VerifyStagePass
    : public PassWrapper<VerifyStagePass, OperationPass<ModuleOp>> {
  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(VerifyStagePass)

  Option<std::string> stage{*this, "stage", llvm::cl::desc("verification stage or all"),
                            llvm::cl::init("all")};
  Option<std::string> target{*this, "target", llvm::cl::desc("target capability profile"),
                             llvm::cl::init("auto")};

  llvm::StringRef getArgument() const final { return "nodal-verify-stage"; }
  llvm::StringRef getDescription() const final {
    return "Run one mandatory Nodal semantic verification stage";
  }

  void runOnOperation() override {
    if (stage == "all") {
      if (failed(verifyNodalPipeline(getOperation(), target)))
        signalPassFailure();
      return;
    }
    std::optional<VerificationStage> selected = symbolizeVerificationStage(stage);
    if (!selected) {
      getOperation().emitError() << "NODAL-VERIFY-PIPELINE-001: unknown stage '" << stage
                                 << "'";
      signalPassFailure();
      return;
    }
    if (failed(verifyNodalStage(getOperation(), *selected, target)))
      signalPassFailure();
  }
};

struct TransactionalGatePass
    : public PassWrapper<TransactionalGatePass, OperationPass<ModuleOp>> {
  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(TransactionalGatePass)

  Option<std::string> target{*this, "target", llvm::cl::desc("target capability profile"),
                             llvm::cl::init("auto")};

  llvm::StringRef getArgument() const final { return "nodal-transactional-gate"; }
  llvm::StringRef getDescription() const final {
    return "Verify, normalize, reverify, and transactionally accept Nodal IR";
  }

  void runOnOperation() override {
    ModuleOp module = getOperation();
    if (failed(verifyNodalPipeline(module, target))) {
      signalPassFailure();
      return;
    }

    DictionaryAttr originalAttributes = module->getAttrDictionary();
    Builder builder(module.getContext());
    SmallVector<Attribute> stages;
    for (VerificationStage stage : kStages)
      stages.push_back(builder.getStringAttr(stringifyVerificationStage(stage)));
    module->setAttr("nodal.pipeline.normalized", builder.getBoolAttr(true));
    module->setAttr("nodal.pipeline.version", builder.getI64IntegerAttr(1));
    module->setAttr("nodal.pipeline.target",
                    builder.getStringAttr(effectiveTarget(module, target)));
    module->setAttr("nodal.pipeline.stages", builder.getArrayAttr(stages));

    if (failed(verifyNodalPipeline(module, target))) {
      module->setAttrs(originalAttributes);
      module.emitError()
          << "NODAL-VERIFY-TRANSACTION-001: post-normalization reverification failed; "
             "the prior accepted attributes were restored";
      signalPassFailure();
      return;
    }
    // Deliberately preserve no analyses: accepted normalization metadata invalidates
    // cached analyses, and every mandatory stage has already been rerun.
  }
};

} // namespace

llvm::StringRef stringifyVerificationStage(VerificationStage stage) {
  switch (stage) {
  case VerificationStage::Construction:
    return "construction";
  case VerificationStage::Hierarchy:
    return "hierarchy";
  case VerificationStage::Connectivity:
    return "connectivity";
  case VerificationStage::TypeShape:
    return "type-shape";
  case VerificationStage::ParameterLoop:
    return "parameter-loop";
  case VerificationStage::EnumFsm:
    return "enum-fsm";
  case VerificationStage::Domain:
    return "domain";
  case VerificationStage::ProtocolPipeline:
    return "protocol-pipeline";
  case VerificationStage::MemoryEffect:
    return "memory-effect";
  case VerificationStage::AnalogMixed:
    return "analog-mixed";
  case VerificationStage::TargetCapability:
    return "target-capability";
  }
  llvm_unreachable("unknown Nodal verification stage");
}

std::optional<VerificationStage> symbolizeVerificationStage(llvm::StringRef value) {
  for (VerificationStage stage : kStages)
    if (stringifyVerificationStage(stage) == value)
      return stage;
  return std::nullopt;
}

LogicalResult verifyNodalStage(ModuleOp module, VerificationStage stage,
                               llvm::StringRef target) {
  switch (stage) {
  case VerificationStage::Construction:
    return verifyConstruction(module);
  case VerificationStage::Hierarchy:
    return verifyHierarchy(module);
  case VerificationStage::Connectivity:
    return verifyConnectivity(module);
  case VerificationStage::TypeShape:
    return verifyTypeShape(module);
  case VerificationStage::ParameterLoop:
    return verifyParameterLoop(module);
  case VerificationStage::EnumFsm:
    return verifyEnumFsm(module);
  case VerificationStage::Domain:
    return verifyDomain(module);
  case VerificationStage::ProtocolPipeline:
    return verifyProtocolPipeline(module);
  case VerificationStage::MemoryEffect:
    return verifyMemoryEffect(module);
  case VerificationStage::AnalogMixed:
    return verifyAnalogMixed(module);
  case VerificationStage::TargetCapability:
    return verifyTargetCapability(module, target);
  }
  return failure();
}

LogicalResult verifyNodalPipeline(ModuleOp module, llvm::StringRef target) {
  for (VerificationStage stage : kStages)
    if (failed(verifyNodalStage(module, stage, target)))
      return failure();
  return success();
}

LogicalResult VerificationSession::accept(ModuleOp candidate, llvm::StringRef target) {
  if (failed(verifyNodalPipeline(candidate, target)))
    return failure();
  std::string text;
  llvm::raw_string_ostream stream(text);
  candidate.print(stream, OpPrintingFlags().enableDebugInfo(false));
  stream << '\n';
  stream.flush();
  acceptedIR = std::move(text);
  return success();
}

std::unique_ptr<Pass> createNodalVerifyStagePass() {
  return std::make_unique<VerifyStagePass>();
}

std::unique_ptr<Pass> createNodalTransactionalGatePass() {
  return std::make_unique<TransactionalGatePass>();
}

void registerNodalPasses() {
  PassRegistration<VerifyStagePass>();
  PassRegistration<TransactionalGatePass>();
  PassPipelineRegistration<>(
      "nodal-gate-check", "Run every mandatory Nodal verification stage",
      [](OpPassManager &manager) { manager.addPass(createNodalVerifyStagePass()); });
  PassPipelineRegistration<>(
      "nodal-gate-normalize",
      "Run transactional verification, normalized acceptance metadata, and reverification",
      [](OpPassManager &manager) { manager.addPass(createNodalTransactionalGatePass()); });
}

} // namespace nodal
