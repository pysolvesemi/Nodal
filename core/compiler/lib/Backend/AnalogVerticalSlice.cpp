#include "nodal/Backend/AnalogVerticalSlice.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Operation.h"
#include "mlir/IR/SymbolTable.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringExtras.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/Support/raw_ostream.h"

#include <algorithm>
#include <array>
#include <charconv>
#include <cmath>
#include <string>
#include <system_error>

using namespace mlir;

namespace nodal {
namespace {

constexpr llvm::StringLiteral kSupportedOperations[] = {
    "nodal.module",     "nodal.parameter",  "nodal.terminal",     "nodal.node",
    "nodal.branch",     "nodal.analog",     "nodal.real_literal", "nodal.parameter_ref",
    "nodal.access",     "nodal.analog_add", "nodal.analog_sub",   "nodal.analog_mul",
    "nodal.analog_div", "nodal.analog_ddt", "nodal.contribute",
};

llvm::StringRef symbolName(Operation *operation) {
  if (auto name = operation->getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName()))
    return name.getValue();
  return {};
}

std::string formatReal(double value) {
  std::array<char, 128> buffer{};
  auto result = std::to_chars(buffer.data(), buffer.data() + buffer.size(), value,
                              std::chars_format::general);
  if (result.ec != std::errc())
    return {};
  return std::string(buffer.data(), result.ptr);
}

FailureOr<std::string> requiredString(Operation *operation, llvm::StringRef name,
                                      llvm::StringRef code) {
  auto value = operation->getAttrOfType<StringAttr>(name);
  if (!value) {
    (void)emitMappedFailure(operation, code,
                            llvm::Twine("missing string attribute '") + name + "'");
    return failure();
  }
  return value.getValue().str();
}

std::string terminalDirection(Operation *operation) {
  auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata");
  if (!metadata)
    return {};
  auto kind = metadata.getAs<StringAttr>("declaration_kind");
  if (!kind)
    return {};
  if (kind.getValue() == "analog-input")
    return "input";
  if (kind.getValue() == "analog-output")
    return "output";
  if (kind.getValue() == "analog-inout")
    return "inout";
  return {};
}

struct ModuleRenderState {
  llvm::DenseMap<Value, std::string> terminals;
  llvm::DenseMap<Value, std::pair<std::string, std::string>> branches;
  llvm::DenseMap<Value, std::string> expressions;
  llvm::StringMap<std::string> parameters;
};

FailureOr<std::string> renderBranch(Value value, ModuleRenderState &state, llvm::StringRef access) {
  auto iterator = state.branches.find(value);
  if (iterator == state.branches.end())
    return failure();
  return (llvm::Twine(access) + "(" + iterator->second.first + ", " + iterator->second.second + ")")
      .str();
}

FailureOr<std::string> renderExpression(Value value, ModuleRenderState &state) {
  if (auto iterator = state.expressions.find(value); iterator != state.expressions.end())
    return iterator->second;

  Operation *operation = value.getDefiningOp();
  if (!operation)
    return failure();
  llvm::StringRef name = operation->getName().getStringRef();
  std::string rendered;

  if (name == "nodal.real_literal") {
    auto literal = operation->getAttrOfType<FloatAttr>("value");
    if (!literal || !std::isfinite(literal.getValueAsDouble()))
      return failure();
    rendered = formatReal(literal.getValueAsDouble());
  } else if (name == "nodal.parameter_ref") {
    auto parameter = operation->getAttrOfType<FlatSymbolRefAttr>("parameter");
    if (!parameter)
      return failure();
    rendered = parameter.getValue().str();
  } else if (name == "nodal.access") {
    auto kind = operation->getAttrOfType<StringAttr>("kind");
    if (!kind || operation->getNumOperands() != 1)
      return failure();
    if (kind.getValue() == "potential") {
      auto expression = renderBranch(operation->getOperand(0), state, "V");
      if (failed(expression))
        return failure();
      rendered = *expression;
    } else if (kind.getValue() == "flow") {
      auto expression = renderBranch(operation->getOperand(0), state, "I");
      if (failed(expression))
        return failure();
      rendered = *expression;
    } else {
      return failure();
    }
  } else if (name == "nodal.analog_ddt") {
    if (operation->getNumOperands() != 1)
      return failure();
    auto input = renderExpression(operation->getOperand(0), state);
    if (failed(input))
      return failure();
    rendered = (llvm::Twine("ddt(") + *input + ")").str();
  } else if (name == "nodal.analog_add" || name == "nodal.analog_sub" ||
             name == "nodal.analog_mul" || name == "nodal.analog_div") {
    if (operation->getNumOperands() != 2)
      return failure();
    auto lhs = renderExpression(operation->getOperand(0), state);
    auto rhs = renderExpression(operation->getOperand(1), state);
    if (failed(lhs) || failed(rhs))
      return failure();
    llvm::StringRef spelling = name == "nodal.analog_add"   ? "+"
                               : name == "nodal.analog_sub" ? "-"
                               : name == "nodal.analog_mul" ? "*"
                                                            : "/";
    rendered = (llvm::Twine("(") + *lhs + " " + spelling + " " + *rhs + ")").str();
  } else {
    return failure();
  }

  state.expressions.try_emplace(value, rendered);
  return rendered;
}

LogicalResult collectModuleState(Operation *definition, ModuleRenderState &state,
                                 llvm::SmallVectorImpl<Operation *> &parameters,
                                 llvm::SmallVectorImpl<Operation *> &ports,
                                 llvm::SmallVectorImpl<Operation *> &nodes,
                                 llvm::SmallVectorImpl<Operation *> &analogs) {
  Region &region = definition->getRegion(0);
  if (!llvm::hasSingleElement(region))
    return failure();
  for (Operation &operation : region.front()) {
    llvm::StringRef name = operation.getName().getStringRef();
    if (name == "nodal.parameter") {
      parameters.push_back(&operation);
    } else if (name == "nodal.terminal") {
      ports.push_back(&operation);
      if (operation.getNumResults() != 1)
        return failure();
      auto terminalName = requiredString(&operation, "name", "NODAL-BACKEND-RC-001");
      if (failed(terminalName))
        return failure();
      state.terminals.try_emplace(operation.getResult(0), *terminalName);
    } else if (name == "nodal.node") {
      nodes.push_back(&operation);
      if (operation.getNumResults() != 1)
        return failure();
      auto nodeName = requiredString(&operation, "name", "NODAL-BACKEND-RC-001");
      if (failed(nodeName))
        return failure();
      state.terminals.try_emplace(operation.getResult(0), *nodeName);
    } else if (name == "nodal.branch") {
      if (operation.getNumOperands() != 2 || operation.getNumResults() != 1)
        return failure();
      auto positive = state.terminals.find(operation.getOperand(0));
      auto negative = state.terminals.find(operation.getOperand(1));
      if (positive == state.terminals.end() || negative == state.terminals.end())
        return failure();
      state.branches.try_emplace(operation.getResult(0),
                                 std::make_pair(positive->second, negative->second));
    } else if (name == "nodal.analog") {
      analogs.push_back(&operation);
    }
  }
  llvm::sort(parameters,
             [](Operation *lhs, Operation *rhs) { return symbolName(lhs) < symbolName(rhs); });
  llvm::sort(ports, [](Operation *lhs, Operation *rhs) {
    return lhs->getAttrOfType<StringAttr>("name").getValue() <
           rhs->getAttrOfType<StringAttr>("name").getValue();
  });
  llvm::sort(nodes, [](Operation *lhs, Operation *rhs) {
    return lhs->getAttrOfType<StringAttr>("name").getValue() <
           rhs->getAttrOfType<StringAttr>("name").getValue();
  });
  return success();
}

LogicalResult renderAnalog(Operation *analog, ModuleRenderState &state, llvm::raw_ostream &output) {
  Region &region = analog->getRegion(0);
  if (!llvm::hasSingleElement(region))
    return failure();
  output << "  analog begin\n";
  for (Operation &operation : region.front()) {
    llvm::StringRef name = operation.getName().getStringRef();
    if (name != "nodal.contribute")
      continue;
    if (operation.getNumOperands() != 2)
      return failure();
    auto kind = operation.getAttrOfType<StringAttr>("kind");
    if (!kind)
      return failure();
    auto target =
        renderBranch(operation.getOperand(0), state, kind.getValue() == "flow" ? "I" : "V");
    auto value = renderExpression(operation.getOperand(1), state);
    if (failed(target) || failed(value))
      return emitMappedFailure(&operation, "NODAL-BACKEND-RC-002",
                               "could not render RC contribution expression");
    output << "    " << *target << " <+ " << *value << ";\n";
  }
  output << "  end\n";
  return success();
}

LogicalResult renderDefinition(Operation *definition, llvm::raw_ostream &output) {
  ModuleRenderState state;
  llvm::SmallVector<Operation *, 8> parameters;
  llvm::SmallVector<Operation *, 8> ports;
  llvm::SmallVector<Operation *, 8> nodes;
  llvm::SmallVector<Operation *, 2> analogs;
  if (failed(collectModuleState(definition, state, parameters, ports, nodes, analogs)))
    return emitMappedFailure(definition, "NODAL-BACKEND-RC-001",
                             "could not collect RC module structure");

  output << "module " << symbolName(definition);
  if (!ports.empty()) {
    output << "(";
    llvm::interleaveComma(ports, output, [&](Operation *port) {
      output << port->getAttrOfType<StringAttr>("name").getValue();
    });
    output << ")";
  }
  output << ";\n";

  llvm::StringMap<llvm::SmallVector<llvm::StringRef, 4>> portsByDirection;
  for (Operation *port : ports) {
    std::string direction = terminalDirection(port);
    if (direction.empty())
      return emitMappedFailure(port, "NODAL-BACKEND-RC-003",
                               "RC terminal requires analog port direction");
    portsByDirection[direction].push_back(port->getAttrOfType<StringAttr>("name").getValue());
  }
  for (llvm::StringRef direction :
       {llvm::StringRef("input"), llvm::StringRef("output"), llvm::StringRef("inout")}) {
    auto iterator = portsByDirection.find(direction);
    if (iterator == portsByDirection.end())
      continue;
    output << "  " << direction << " ";
    llvm::interleaveComma(iterator->second, output);
    output << ";\n";
  }

  if (!ports.empty() || !nodes.empty()) {
    output << "  electrical ";
    bool first = true;
    auto emitElectricalName = [&](Operation *operation) {
      if (!first)
        output << ", ";
      first = false;
      output << operation->getAttrOfType<StringAttr>("name").getValue();
    };
    for (Operation *port : ports)
      emitElectricalName(port);
    for (Operation *node : nodes)
      emitElectricalName(node);
    output << ";\n";
  }

  for (Operation *parameter : parameters) {
    auto type = parameter->getAttrOfType<TypeAttr>("type");
    auto value = parameter->getAttrOfType<FloatAttr>("default_value");
    if (!type || !type.getValue().isF64() || !value || !std::isfinite(value.getValueAsDouble()))
      return emitMappedFailure(parameter, "NODAL-BACKEND-RC-004",
                               "RC parameters must be finite real values");
    output << "  parameter real " << symbolName(parameter) << " = "
           << formatReal(value.getValueAsDouble()) << ";\n";
  }

  if ((!ports.empty() || !nodes.empty() || !parameters.empty()) && !analogs.empty())
    output << "\n";
  for (Operation *analog : analogs) {
    if (failed(renderAnalog(analog, state, output)))
      return failure();
  }
  output << "endmodule\n";
  return success();
}

bool validIdentifierList(llvm::StringRef value) {
  llvm::SmallVector<llvm::StringRef, 8> names;
  value.split(names, ',', -1, false);
  if (names.empty())
    return false;
  return llvm::all_of(names, [](llvm::StringRef name) {
    name = name.trim();
    if (name.empty() || !(llvm::isAlpha(name.front()) || name.front() == '_'))
      return false;
    return llvm::all_of(name.drop_front(), [](char character) {
      return llvm::isAlnum(character) || character == '_' || character == '$';
    });
  });
}

} // namespace

LogicalResult verifyBackendOperations(ModuleOp module, const BackendProfile &profile) {
  LogicalResult result = success();
  module.walk([&](Operation *operation) {
    if (failed(result) || operation == module.getOperation())
      return;
    llvm::StringRef name = operation->getName().getStringRef();
    if (llvm::is_contained(kSupportedOperations, name))
      return;
    result = emitMappedFailure(operation, "NODAL-BACKEND-CAPABILITY-001",
                               llvm::Twine("operation '") + name +
                                   "' is not yet supported by profile '" + profile.id + "'");
  });
  return result;
}

LogicalResult renderBackendCandidate(llvm::ArrayRef<Operation *> definitions,
                                     const BackendConfiguration &configuration,
                                     llvm::raw_ostream &output) {
  output << "/* Nodal backend framework v1\n";
  output << " * profile: " << configuration.profile->id << "\n";
  output << " * check-profile: " << stringifyGateProfile(configuration.checkProfile) << "\n";
  output << " * shaped-layout: " << stringifyShapedValueLayout(configuration.shapedValueLayout)
         << "\n";
  output << " * materialization: " << stringifyMaterializationPolicy(configuration.materialization)
         << "\n";
  output << " * naming: " << stringifyNamingPolicy(configuration.naming) << "\n";
  output << " */\n";
  output << "`include \"constants.vams\"\n";
  output << "`include \"disciplines.vams\"\n\n";
  bool firstDefinition = true;
  for (Operation *definition : definitions) {
    if (!firstDefinition)
      output << "\n";
    firstDefinition = false;
    if (failed(renderDefinition(definition, output)))
      return failure();
  }
  return success();
}

LogicalResult verifyBackendTarget(llvm::StringRef candidate,
                                  const BackendConfiguration &configuration) {
  if (candidate.empty() || !candidate.starts_with("/* Nodal backend framework v1\n") ||
      !candidate.ends_with("\n") || candidate.contains('\r') || candidate.contains('\0'))
    return failure();
  std::string expectedProfile =
      (llvm::Twine(" * profile: ") + configuration.profile->id + "\n").str();
  if (!candidate.contains(expectedProfile))
    return failure();
  llvm::SmallVector<llvm::StringRef, 64> lines;
  candidate.split(lines, '\n', -1, true);
  size_t modules = llvm::count_if(
      lines, [](llvm::StringRef line) { return line.trim().starts_with("module "); });
  size_t endmodules =
      llvm::count_if(lines, [](llvm::StringRef line) { return line.trim() == "endmodule"; });
  return modules > 0 && modules == endmodules ? success() : failure();
}

LogicalResult reparseBackendTarget(llvm::StringRef candidate, const BackendConfiguration &) {
  llvm::SmallVector<llvm::StringRef, 64> lines;
  candidate.split(lines, '\n', -1, true);
  bool insideModule = false;
  bool insideAnalog = false;
  bool sawModule = false;
  for (llvm::StringRef raw : lines) {
    llvm::StringRef line = raw.trim();
    if (line.empty() || line.starts_with("/*") || line.starts_with("*") ||
        line.starts_with("`include "))
      continue;
    if (!insideModule && line.starts_with("module ") && line.ends_with(";")) {
      llvm::StringRef declaration = line.drop_front(sizeof("module ") - 1).drop_back();
      size_t open = declaration.find('(');
      if (open != llvm::StringRef::npos) {
        if (!declaration.ends_with(")") ||
            !validIdentifierList(declaration.slice(open + 1, declaration.size() - 1)))
          return failure();
        declaration = declaration.take_front(open);
      }
      if (!validIdentifierList(declaration))
        return failure();
      insideModule = true;
      sawModule = true;
      continue;
    }
    if (!insideModule)
      return failure();
    if (line == "analog begin") {
      if (insideAnalog)
        return failure();
      insideAnalog = true;
      continue;
    }
    if (line == "end") {
      if (!insideAnalog)
        return failure();
      insideAnalog = false;
      continue;
    }
    if (line == "endmodule") {
      if (insideAnalog)
        return failure();
      insideModule = false;
      continue;
    }
    if (insideAnalog) {
      if (!line.ends_with(";") || !line.contains("<+"))
        return failure();
      continue;
    }
    if ((line.starts_with("input ") || line.starts_with("output ") || line.starts_with("inout ") ||
         line.starts_with("electrical ")) &&
        line.ends_with(";") && validIdentifierList(line.drop_front(line.find(' ') + 1).drop_back()))
      continue;
    if (line.starts_with("parameter real ") && line.ends_with(";") && line.contains(" = "))
      continue;
    return failure();
  }
  return sawModule && !insideModule && !insideAnalog ? success() : failure();
}

} // namespace nodal
