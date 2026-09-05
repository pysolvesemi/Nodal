#include "nodal/Backend/AnalogVerticalSlice.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Operation.h"
#include "mlir/IR/SymbolTable.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"
#include "nodal/Dialect/Nodal/PotentialFlowAccess.h"
#include "nodal/Dialect/Nodal/TimeWaveform.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/SmallString.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringExtras.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/ADT/StringSet.h"
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
    "nodal.unit",
    "nodal.module",
    "nodal.nature",
    "nodal.nature_import",
    "nodal.discipline",
    "nodal.discipline_import",
    "nodal.parameter",
    "nodal.const_literal",
    "nodal.const_parameter_ref",
    "nodal.const_expr",
    "nodal.parameter_value",
    "nodal.parameter_constraint",
    "nodal.parameter_envelope",
    "nodal.terminal",
    "nodal.node",
    "nodal.branch",
    "nodal.analog",
    "nodal.real_literal",
    "nodal.analog_integer_literal",
    "nodal.parameter_ref",
    "nodal.access",
    "nodal.terminal_access",
    "nodal.port_flow_access",
    "nodal.probe",
    "nodal.analog_add",
    "nodal.analog_sub",
    "nodal.analog_mul",
    "nodal.analog_div",
    "nodal.analog_neg",
    "nodal.analog_compare",
    "nodal.analog_logic",
    "nodal.analog_select",
    "nodal.analog_ddt",
    "nodal.analog_idt",
    "nodal.analog_transition",
    "nodal.analog_slew",
    "nodal.analog_absdelay",
    "nodal.analog_abstime",
    "nodal.analog_bound_step",
    "nodal.contribute",
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
  llvm::DenseMap<Value, std::string> branchNames;
  llvm::DenseMap<Value, std::string> expressions;
  llvm::DenseMap<Value, std::string> waveformNames;
  llvm::StringMap<std::string> parameters;
};

FailureOr<std::string> renderBranch(Value value, ModuleRenderState &state, llvm::StringRef access) {
  auto iterator = state.branches.find(value);
  if (iterator == state.branches.end())
    return failure();
  if (auto named = state.branchNames.find(value); named != state.branchNames.end())
    return (llvm::Twine(access) + "(" + named->second + ")").str();
  return (llvm::Twine(access) + "(" + iterator->second.first + ", " + iterator->second.second + ")")
      .str();
}

bool isFoldedExpressionCandidate(Operation *operation) {
  llvm::StringRef name = operation->getName().getStringRef();
  return name == "nodal.analog_add" || name == "nodal.analog_sub" || name == "nodal.analog_mul" ||
         name == "nodal.analog_div" || name == "nodal.analog_neg" ||
         name == "nodal.analog_compare" || name == "nodal.analog_logic" ||
         name == "nodal.analog_select";
}

std::optional<std::string> renderFoldedExpression(Operation *operation) {
  if (!isFoldedExpressionCandidate(operation) || operation->getNumResults() != 1)
    return std::nullopt;

  auto folded = operation->getAttrOfType<BoolAttr>("nodal.folded");
  auto kind = operation->getAttrOfType<StringAttr>("nodal.folded_kind");
  auto dimension = operation->getAttrOfType<StringAttr>("nodal.folded_dimension");
  auto provenance = operation->getAttrOfType<StringAttr>("nodal.folded_provenance");
  if (!folded || !folded.getValue() || !kind || !dimension || !provenance ||
      provenance.getValue() != "increment30")
    return std::nullopt;

  auto information = getAnalogNumericTypeInfo(operation->getResult(0).getType());
  if (failed(information))
    return std::nullopt;
  llvm::StringRef expectedKind =
      information->kind == AnalogNumericKind::Integer ? llvm::StringRef("integer")
      : information->kind == AnalogNumericKind::Real  ? llvm::StringRef("real")
                                                      : llvm::StringRef("boolean");
  if (kind.getValue() != expectedKind ||
      dimension.getValue() != llvm::StringRef(information->dimension))
    return std::nullopt;

  Attribute value = operation->getAttr("nodal.folded_value");
  if (information->kind == AnalogNumericKind::Boolean) {
    if (auto boolean = llvm::dyn_cast_or_null<BoolAttr>(value))
      return boolean.getValue() ? std::string("1") : std::string("0");
    return std::nullopt;
  }
  if (information->kind == AnalogNumericKind::Real) {
    auto real = llvm::dyn_cast_or_null<FloatAttr>(value);
    if (!real || !std::isfinite(real.getValueAsDouble()))
      return std::nullopt;
    return formatReal(real.getValueAsDouble());
  }
  if (information->kind == AnalogNumericKind::Integer) {
    auto integer = llvm::dyn_cast_or_null<IntegerAttr>(value);
    if (!integer)
      return std::nullopt;
    llvm::SmallString<64> rendered;
    integer.getValue().toString(rendered, 10, true);
    return rendered.str().str();
  }
  return std::nullopt;
}

FailureOr<std::string> renderExpression(Value value, ModuleRenderState &state) {
  if (auto iterator = state.expressions.find(value); iterator != state.expressions.end())
    return iterator->second;

  Operation *operation = value.getDefiningOp();
  if (!operation)
    return failure();
  llvm::StringRef name = operation->getName().getStringRef();
  std::string rendered;

  if (auto folded = renderFoldedExpression(operation)) {
    rendered = *folded;
  } else if (name == "nodal.const_literal" || name == "nodal.const_parameter_ref" ||
             name == "nodal.const_expr") {
    auto expression = nodal::renderParameterConstantExpression(value);
    if (failed(expression))
      return failure();
    rendered = *expression;
  } else if (name == "nodal.real_literal") {
    auto literal = operation->getAttrOfType<FloatAttr>("value");
    if (!literal || !std::isfinite(literal.getValueAsDouble()))
      return failure();
    rendered = formatReal(literal.getValueAsDouble());
  } else if (name == "nodal.analog_integer_literal") {
    auto literal = operation->getAttrOfType<IntegerAttr>("value");
    if (!literal)
      return failure();
    llvm::SmallString<64> spelling;
    literal.getValue().toString(spelling, 10, true);
    rendered = spelling.str().str();
  } else if (name == "nodal.parameter_ref") {
    auto parameter = operation->getAttrOfType<FlatSymbolRefAttr>("parameter");
    if (!parameter)
      return failure();
    rendered = parameter.getValue().str();
  } else if (name == "nodal.access" || name == "nodal.terminal_access" ||
             name == "nodal.port_flow_access") {
    auto kind = operation->getAttrOfType<StringAttr>("kind");
    auto function = operation->getAttrOfType<StringAttr>("function");
    if (!kind)
      return failure();
    std::string access;
    if (function) {
      access = function.getValue().str();
    } else if (name == "nodal.access" && kind.getValue() == "potential") {
      access = "V";
    } else if (name == "nodal.access" && kind.getValue() == "flow") {
      access = "I";
    } else {
      return failure();
    }

    if (name == "nodal.access") {
      if (operation->getNumOperands() != 1)
        return failure();
      auto expression = renderBranch(operation->getOperand(0), state, access);
      if (failed(expression))
        return failure();
      rendered = *expression;
    } else if (name == "nodal.terminal_access") {
      if (operation->getNumOperands() != 1 && operation->getNumOperands() != 2)
        return failure();
      auto first = state.terminals.find(operation->getOperand(0));
      if (first == state.terminals.end())
        return failure();
      rendered = (llvm::Twine(access) + "(" + first->second).str();
      if (operation->getNumOperands() == 2) {
        auto second = state.terminals.find(operation->getOperand(1));
        if (second == state.terminals.end())
          return failure();
        rendered += (llvm::Twine(", ") + second->second).str();
      }
      rendered += ")";
    } else {
      if (operation->getNumOperands() != 1)
        return failure();
      auto port = state.terminals.find(operation->getOperand(0));
      if (port == state.terminals.end())
        return failure();
      rendered = (llvm::Twine(access) + "(<" + port->second + ">)").str();
    }
  } else if (name == "nodal.analog_abstime") {
    rendered = "$abstime";
  } else if (nodal::isStatefulWaveformOperation(operation)) {
    // A stateful source operator is evaluated exactly once in renderAnalog.
    // Reaching it here without its materialized name is an ordering violation.
    return emitMappedFailure(operation, "NODAL-ANALOG-036-006",
                             "waveform state used before its single materialization");
  } else if (name == "nodal.analog_ddt") {
    if (operation->getNumOperands() != 1)
      return failure();
    auto contract = operation->getAttrOfType<StringAttr>("operator_contract");
    auto simplified = operation->getAttrOfType<BoolAttr>("nodal.simplified");
    auto rule = operation->getAttrOfType<StringAttr>("nodal.simplification_rule");
    auto provenance = operation->getAttrOfType<StringAttr>("nodal.simplification_provenance");
    auto value = operation->getAttrOfType<FloatAttr>("nodal.simplified_value");
    if (contract && contract.getValue() == "increment35" && simplified && simplified.getValue() &&
        rule && rule.getValue() == "ddt-time-invariant-zero" && provenance &&
        provenance.getValue() == "increment35" && value && value.getValueAsDouble() == 0.0) {
      rendered = formatReal(0.0);
    } else {
      auto input = renderExpression(operation->getOperand(0), state);
      if (failed(input))
        return failure();
      rendered = (llvm::Twine("ddt(") + *input + ")").str();
    }
  } else if (name == "nodal.analog_idt") {
    if (operation->getNumOperands() != 1 && operation->getNumOperands() != 2)
      return failure();
    auto input = renderExpression(operation->getOperand(0), state);
    if (failed(input))
      return failure();
    rendered = (llvm::Twine("idt(") + *input).str();
    if (operation->getNumOperands() == 2) {
      auto initial = renderExpression(operation->getOperand(1), state);
      if (failed(initial))
        return failure();
      rendered += (llvm::Twine(", ") + *initial).str();
    }
    rendered += ")";
  } else if (name == "nodal.analog_neg") {
    auto input = renderExpression(operation->getOperand(0), state);
    if (failed(input))
      return failure();
    rendered = (llvm::Twine("(-") + *input + ")").str();
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
  } else if (name == "nodal.analog_compare") {
    auto lhs = renderExpression(operation->getOperand(0), state);
    auto rhs = renderExpression(operation->getOperand(1), state);
    auto predicate = operation->getAttrOfType<StringAttr>("predicate");
    if (failed(lhs) || failed(rhs) || !predicate)
      return failure();
    llvm::StringRef spelling = predicate.getValue() == "eq"   ? "=="
                               : predicate.getValue() == "ne" ? "!="
                               : predicate.getValue() == "lt" ? "<"
                               : predicate.getValue() == "le" ? "<="
                               : predicate.getValue() == "gt" ? ">"
                                                              : ">=";
    rendered = (llvm::Twine("(") + *lhs + " " + spelling + " " + *rhs + ")").str();
  } else if (name == "nodal.analog_logic") {
    auto operatorName = operation->getAttrOfType<StringAttr>("operator_name");
    if (!operatorName)
      return failure();
    if (operatorName.getValue() == "not") {
      auto input = renderExpression(operation->getOperand(0), state);
      if (failed(input))
        return failure();
      rendered = (llvm::Twine("(!") + *input + ")").str();
    } else {
      auto lhs = renderExpression(operation->getOperand(0), state);
      auto rhs = renderExpression(operation->getOperand(1), state);
      if (failed(lhs) || failed(rhs))
        return failure();
      llvm::StringRef spelling = operatorName.getValue() == "and"  ? "&&"
                                 : operatorName.getValue() == "or" ? "||"
                                                                   : "^";
      rendered = (llvm::Twine("(") + *lhs + " " + spelling + " " + *rhs + ")").str();
    }
  } else if (name == "nodal.analog_select") {
    auto condition = renderExpression(operation->getOperand(0), state);
    auto trueValue = renderExpression(operation->getOperand(1), state);
    auto falseValue = renderExpression(operation->getOperand(2), state);
    if (failed(condition) || failed(trueValue) || failed(falseValue))
      return failure();
    rendered =
        (llvm::Twine("(") + *condition + " ? " + *trueValue + " : " + *falseValue + ")").str();
  } else {
    return failure();
  }

  state.expressions.try_emplace(value, rendered);
  return rendered;
}

LogicalResult orderParametersByDependency(Operation *definition,
                                          llvm::SmallVectorImpl<Operation *> &parameters) {
  llvm::sort(parameters,
             [](Operation *lhs, Operation *rhs) { return symbolName(lhs) < symbolName(rhs); });

  llvm::StringMap<Operation *> parametersByName;
  for (Operation *parameter : parameters) {
    llvm::StringRef name = symbolName(parameter);
    if (name.empty() || parametersByName.count(name) != 0)
      return failure();
    parametersByName[name] = parameter;
  }

  llvm::DenseMap<Operation *, llvm::SmallVector<Operation *, 4>> dependencies;
  Region &region = definition->getRegion(0);
  if (!llvm::hasSingleElement(region))
    return failure();

  for (Operation *parameter : parameters) {
    Operation *parameterValue = nullptr;
    for (Operation &operation : region.front()) {
      if (operation.getName().getStringRef() != "nodal.parameter_value")
        continue;
      auto reference = operation.getAttrOfType<FlatSymbolRefAttr>("parameter");
      if (!reference || reference.getValue() != symbolName(parameter))
        continue;
      if (parameterValue)
        return failure();
      parameterValue = &operation;
    }
    if (!parameterValue)
      continue;

    llvm::SmallVector<Value, 8> worklist;
    worklist.push_back(parameterValue->getOperand(0));
    llvm::DenseSet<Operation *> visitedExpressions;
    while (!worklist.empty()) {
      Value value = worklist.pop_back_val();
      Operation *expression = value.getDefiningOp();
      if (!expression || !visitedExpressions.insert(expression).second)
        continue;
      if (expression->getName().getStringRef() == "nodal.const_parameter_ref") {
        auto reference = expression->getAttrOfType<FlatSymbolRefAttr>("parameter");
        if (!reference)
          return failure();
        auto dependency = parametersByName.find(reference.getValue());
        if (dependency == parametersByName.end())
          return failure();
        dependencies[parameter].push_back(dependency->second);
        continue;
      }
      for (Value operand : expression->getOperands())
        worklist.push_back(operand);
    }
  }

  for (auto &entry : dependencies) {
    auto &values = entry.second;
    llvm::sort(values,
               [](Operation *lhs, Operation *rhs) { return symbolName(lhs) < symbolName(rhs); });
    values.erase(std::unique(values.begin(), values.end()), values.end());
  }

  llvm::DenseSet<Operation *> emitted;
  llvm::SmallVector<Operation *, 8> ordered;
  while (ordered.size() != parameters.size()) {
    bool progressed = false;
    for (Operation *parameter : parameters) {
      if (emitted.count(parameter) != 0)
        continue;
      auto dependency = dependencies.find(parameter);
      const bool ready = dependency == dependencies.end() ||
                         llvm::all_of(dependency->second, [&](Operation *required) {
                           return emitted.count(required) != 0;
                         });
      if (!ready)
        continue;
      emitted.insert(parameter);
      ordered.push_back(parameter);
      progressed = true;
    }
    if (!progressed)
      return failure();
  }

  parameters.clear();
  parameters.append(ordered.begin(), ordered.end());
  return success();
}

LogicalResult collectModuleState(Operation *definition, ModuleRenderState &state,
                                 llvm::SmallVectorImpl<Operation *> &parameters,
                                 llvm::SmallVectorImpl<Operation *> &ports,
                                 llvm::SmallVectorImpl<Operation *> &nodes,
                                 llvm::SmallVectorImpl<Operation *> &namedBranches,
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
      if (auto branchName = operation.getAttrOfType<StringAttr>("name")) {
        if (!branchName.getValue().trim().empty()) {
          namedBranches.push_back(&operation);
          state.branchNames.try_emplace(operation.getResult(0), branchName.getValue().str());
        }
      }
    } else if (name == "nodal.analog") {
      analogs.push_back(&operation);
    }
  }
  if (failed(orderParametersByDependency(definition, parameters)))
    return failure();
  llvm::sort(ports, [](Operation *lhs, Operation *rhs) {
    return lhs->getAttrOfType<StringAttr>("name").getValue() <
           rhs->getAttrOfType<StringAttr>("name").getValue();
  });
  llvm::sort(nodes, [](Operation *lhs, Operation *rhs) {
    return lhs->getAttrOfType<StringAttr>("name").getValue() <
           rhs->getAttrOfType<StringAttr>("name").getValue();
  });
  llvm::sort(namedBranches, [](Operation *lhs, Operation *rhs) {
    return lhs->getAttrOfType<StringAttr>("name").getValue() <
           rhs->getAttrOfType<StringAttr>("name").getValue();
  });
  return success();
}

Operation *findParameterValue(Operation *definition, llvm::StringRef parameter) {
  Region &region = definition->getRegion(0);
  if (!llvm::hasSingleElement(region))
    return nullptr;
  for (Operation &operation : region.front()) {
    if (operation.getName().getStringRef() != "nodal.parameter_value")
      continue;
    auto reference = operation.getAttrOfType<FlatSymbolRefAttr>("parameter");
    if (reference && reference.getValue() == parameter)
      return &operation;
  }
  return nullptr;
}

llvm::SmallVector<Operation *, 4> findParameterConstraints(Operation *definition,
                                                           llvm::StringRef parameter) {
  llvm::SmallVector<Operation *, 4> constraints;
  Region &region = definition->getRegion(0);
  if (!llvm::hasSingleElement(region))
    return constraints;
  for (Operation &operation : region.front()) {
    if (operation.getName().getStringRef() != "nodal.parameter_constraint")
      continue;
    auto reference = operation.getAttrOfType<FlatSymbolRefAttr>("parameter");
    if (reference && reference.getValue() == parameter)
      constraints.push_back(&operation);
  }
  return constraints;
}

bool parameterIntegerIsSigned(Operation *parameter) {
  auto type = parameter->getAttrOfType<TypeAttr>("type");
  if (!type)
    return false;
  if (llvm::isa<nodal::SIntType>(type.getValue()))
    return true;
  if (auto integer = llvm::dyn_cast<IntegerType>(type.getValue()))
    return integer.isSigned();
  return false;
}

FailureOr<std::string> renderIntegerAttribute(IntegerAttr integer, Operation *parameter) {
  llvm::SmallString<64> rendered;
  integer.getValue().toString(rendered, 10, parameterIntegerIsSigned(parameter));
  return rendered.str().str();
}

FailureOr<std::string> legacyParameterInitializer(Operation *parameter) {
  llvm::StringRef kind = nodal::getParameterKind(parameter);
  Attribute value = parameter->getAttr("default_value");
  if (kind == "real") {
    auto real = llvm::dyn_cast<FloatAttr>(value);
    if (!real || !std::isfinite(real.getValueAsDouble()))
      return failure();
    return formatReal(real.getValueAsDouble()) + nodal::getParameterUnitNativeSuffix(parameter);
  }
  if (kind == "integer") {
    auto integer = llvm::dyn_cast<IntegerAttr>(value);
    if (!integer)
      return failure();
    return renderIntegerAttribute(integer, parameter);
  }
  if (kind == "boolean") {
    if (auto boolean = llvm::dyn_cast<BoolAttr>(value))
      return boolean.getValue() ? std::string("1") : std::string("0");
    if (auto integer = llvm::dyn_cast<IntegerAttr>(value))
      return integer.getInt() != 0 ? std::string("1") : std::string("0");
  }
  return failure();
}

LogicalResult renderAnalog(Operation *analog, ModuleRenderState &state, llvm::raw_ostream &output) {
  Region &region = analog->getRegion(0);
  if (!llvm::hasSingleElement(region))
    return failure();
  output << "  analog begin\n";
  for (Operation &operation : region.front()) {
    llvm::StringRef name = operation.getName().getStringRef();
    if (nodal::isStatefulWaveformOperation(&operation) || name == "nodal.analog_bound_step") {
      std::string call = name == "nodal.analog_bound_step"   ? "$bound_step"
                         : name == "nodal.analog_transition" ? "transition"
                         : name == "nodal.analog_slew"       ? "slew"
                                                             : "absdelay";
      call += "(";
      for (unsigned index = 0; index < operation.getNumOperands(); ++index) {
        auto argument = renderExpression(operation.getOperand(index), state);
        if (failed(argument))
          return failure();
        if (index)
          call += ", ";
        call += *argument;
      }
      call += ")";
      if (name == "nodal.analog_bound_step")
        output << "    " << call << ";\n";
      else {
        const auto &temporary = state.waveformNames[operation.getResult(0)];
        output << "    " << temporary << " = " << call << ";\n";
        state.expressions[operation.getResult(0)] = temporary;
      }
      continue;
    }
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
  llvm::SmallVector<Operation *, 4> namedBranches;
  llvm::SmallVector<Operation *, 2> analogs;
  if (failed(
          collectModuleState(definition, state, parameters, ports, nodes, namedBranches, analogs)))
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

  for (Operation *branch : namedBranches) {
    auto endpoints = state.branches.find(branch->getResult(0));
    auto name = branch->getAttrOfType<StringAttr>("name");
    if (endpoints == state.branches.end() || !name || name.getValue().trim().empty())
      return emitMappedFailure(branch, "NODAL-BACKEND-RC-004",
                               "named branch is not losslessly renderable");
    output << "  branch (" << endpoints->second.first << ", " << endpoints->second.second << ") "
           << name.getValue() << ";\n";
  }

  for (Operation *parameter : parameters) {
    llvm::StringRef kind = nodal::getParameterKind(parameter);
    llvm::StringRef nativeType = kind == "real"                           ? "real"
                                 : kind == "integer" || kind == "boolean" ? "integer"
                                                                          : "";
    if (nativeType.empty())
      return emitMappedFailure(parameter, "NODAL-BACKEND-PARAMETER-001",
                               "unsupported native parameter kind");

    FailureOr<std::string> initializer = failure();
    if (Operation *value = findParameterValue(definition, symbolName(parameter)))
      initializer = nodal::renderParameterConstantExpression(value->getOperand(0), parameter);
    else
      initializer = legacyParameterInitializer(parameter);
    if (failed(initializer))
      return emitMappedFailure(parameter, "NODAL-BACKEND-PARAMETER-001",
                               "parameter initializer is not losslessly renderable");

    auto variability = parameter->getAttrOfType<StringAttr>("variability");
    llvm::StringRef declarationKeyword =
        variability && variability.getValue() == "fixed" ? "localparam" : "parameter";
    output << "  " << declarationKeyword << " " << nativeType << " " << symbolName(parameter)
           << " = " << *initializer;
    for (Operation *constraint : findParameterConstraints(definition, symbolName(parameter))) {
      llvm::StringRef constraintKind =
          constraint->getAttrOfType<StringAttr>("constraint_kind").getValue();
      if (constraintKind == "range") {
        auto lower = nodal::renderParameterConstantExpression(constraint->getOperand(0), parameter);
        auto upper = nodal::renderParameterConstantExpression(constraint->getOperand(1), parameter);
        auto lowerInclusive = constraint->getAttrOfType<BoolAttr>("lower_inclusive");
        auto upperInclusive = constraint->getAttrOfType<BoolAttr>("upper_inclusive");
        if (failed(lower) || failed(upper) || !lowerInclusive || !upperInclusive)
          return emitMappedFailure(constraint, "NODAL-BACKEND-PARAMETER-002",
                                   "range constraint is not losslessly renderable");
        output << " from " << (lowerInclusive.getValue() ? "[" : "(") << *lower << ":" << *upper
               << (upperInclusive.getValue() ? "]" : ")");
      } else if (constraintKind == "exclude") {
        auto excluded =
            nodal::renderParameterConstantExpression(constraint->getOperand(0), parameter);
        if (failed(excluded))
          return emitMappedFailure(constraint, "NODAL-BACKEND-PARAMETER-002",
                                   "exclusion constraint is not losslessly renderable");
        output << " exclude " << *excluded;
      } else {
        return emitMappedFailure(constraint, "NODAL-BACKEND-PARAMETER-002",
                                 "unsupported native parameter constraint");
      }
    }
    output << ";";
    std::string unit = nodal::getParameterUnitSymbol(parameter);
    if (!unit.empty())
      output << " // unit: " << unit;
    output << "\n";
  }

  // Reserve every authored identifier before assigning deterministic private names.
  llvm::StringSet<> reservedNames;
  definition->walk([&](Operation *op) {
    for (llvm::StringRef attribute : {llvm::StringRef("name"), llvm::StringRef("sym_name")})
      if (auto value = op->getAttrOfType<StringAttr>(attribute))
        reservedNames.insert(value.getValue());
  });
  unsigned waveformIndex = 0;
  for (Operation *analog : analogs) {
    for (Operation &op : analog->getRegion(0).front()) {
      if (!nodal::isStatefulWaveformOperation(&op))
        continue;
      std::string temporary;
      do {
        temporary = "waveform_" + std::to_string(waveformIndex++);
      } while (!reservedNames.insert(temporary).second);
      state.waveformNames[op.getResult(0)] = temporary;
      output << "  real " << temporary << ";\n";
    }
  }

  if ((!ports.empty() || !nodes.empty() || !namedBranches.empty() || !parameters.empty()) &&
      !analogs.empty())
    output << "\n";
  for (Operation *analog : analogs) {
    if (failed(renderAnalog(analog, state, output)))
      return failure();
  }
  output << "endmodule\n";
  return success();
}

bool validCanonicalCommentText(llvm::StringRef value) {
  if (value.empty() || value != value.trim())
    return false;
  return llvm::all_of(value, [](char character) {
    const unsigned char byte = static_cast<unsigned char>(character);
    return byte >= 0x20 && byte != 0x7f;
  });
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
  if (failed(normalizePotentialFlowAccess(module)))
    return failure();
  if (failed(verifyAnalogQuantityErasure(module)))
    return failure();
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
  auto validParameterDeclaration = [](llvm::StringRef line) {
    llvm::StringRef code = line;
    size_t comment = code.find("//");
    if (comment != llvm::StringRef::npos) {
      llvm::StringRef annotation = code.drop_front(comment + 2).trim();
      constexpr llvm::StringLiteral unitPrefix = "unit: ";
      if (!annotation.starts_with(unitPrefix))
        return false;
      llvm::StringRef unit = annotation.drop_front(unitPrefix.size()).trim();
      if (!validCanonicalCommentText(unit))
        return false;
      code = code.take_front(comment).rtrim();
    }

    if (!code.ends_with(";"))
      return false;
    code = code.drop_back().trim();

    if (!code.consume_front("parameter real ") && !code.consume_front("parameter integer ") &&
        !code.consume_front("localparam real ") && !code.consume_front("localparam integer "))
      return false;

    size_t equals = code.find(" = ");
    if (equals == llvm::StringRef::npos || !validIdentifierList(code.take_front(equals).trim()))
      return false;

    llvm::StringRef tail = code.drop_front(equals + 3).trim();
    if (tail.empty())
      return false;

    size_t from = tail.find(" from ");
    size_t exclude = tail.find(" exclude ");
    if (from != llvm::StringRef::npos && exclude != llvm::StringRef::npos && exclude < from)
      return false;

    size_t initializerEnd = tail.size();
    if (from != llvm::StringRef::npos)
      initializerEnd = std::min(initializerEnd, from);
    if (exclude != llvm::StringRef::npos)
      initializerEnd = std::min(initializerEnd, exclude);
    llvm::StringRef initializer = tail.take_front(initializerEnd).trim();
    if (initializer.empty() || initializer.contains(';'))
      return false;

    if (from != llvm::StringRef::npos) {
      size_t rangeStart = from + sizeof(" from ") - 1;
      size_t rangeEnd = exclude == llvm::StringRef::npos ? tail.size() : exclude;
      if (rangeStart >= rangeEnd)
        return false;
      llvm::StringRef range = tail.slice(rangeStart, rangeEnd).trim();
      if (range.size() < 3 || (range.front() != '[' && range.front() != '(') ||
          (range.back() != ']' && range.back() != ')'))
        return false;
      llvm::StringRef bounds = range.drop_front().drop_back();
      size_t colon = bounds.find(':');
      if (colon == llvm::StringRef::npos || bounds.drop_front(colon + 1).contains(':') ||
          bounds.take_front(colon).trim().empty() || bounds.drop_front(colon + 1).trim().empty())
        return false;
    }

    if (exclude != llvm::StringRef::npos &&
        tail.drop_front(exclude + sizeof(" exclude ") - 1).trim().empty())
      return false;
    return true;
  };

  // Recognize only emitted waveform calls, retaining arity and balanced arguments.
  // This is the existing structural reparse gate, not a general Verilog-A parser.
  auto validWaveformCall = [](llvm::StringRef call, bool effect) {
    size_t open = call.find('(');
    if (open == llvm::StringRef::npos || !call.ends_with(")"))
      return false;
    llvm::StringRef function = call.take_front(open);
    unsigned minimum = 1, maximum = 1;
    if (effect) {
      if (function != "$bound_step")
        return false;
    } else if (function == "transition")
      maximum = 5;
    else if (function == "slew")
      maximum = 3;
    else if (function == "absdelay") {
      minimum = 2;
      maximum = 3;
    } else
      return false;
    auto arguments = call.drop_front(open + 1).drop_back();
    unsigned count = 1, depth = 0;
    size_t start = 0;
    for (size_t index = 0; index < arguments.size(); ++index) {
      char c = arguments[index];
      if (c == ';' || c == '{' || c == '}')
        return false;
      if (c == '(')
        ++depth;
      else if (c == ')') {
        if (!depth)
          return false;
        --depth;
      } else if (c == ',' && !depth) {
        if (arguments.slice(start, index).trim().empty())
          return false;
        start = index + 1;
        ++count;
      }
    }
    return !depth && !arguments.drop_front(start).trim().empty() && count >= minimum &&
           count <= maximum;
  };
  llvm::StringSet<> realNames;
  llvm::StringSet<> assignedRealNames;

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
      realNames.clear();
      assignedRealNames.clear();
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
      if (insideAnalog || assignedRealNames.size() != realNames.size())
        return failure();
      insideModule = false;
      continue;
    }
    if (insideAnalog) {
      if (!line.ends_with(";"))
        return failure();
      if (line.contains("<+"))
        continue;
      auto statement = line.drop_back().trim();
      if (validWaveformCall(statement, true))
        continue;
      size_t equals = statement.find(" = ");
      if (equals == llvm::StringRef::npos)
        return failure();
      auto destination = statement.take_front(equals).trim();
      if (!realNames.contains(destination) || !assignedRealNames.insert(destination).second ||
          !validWaveformCall(statement.drop_front(equals + 3).trim(), false))
        return failure();
      continue;
    }
    if (line.starts_with("real ") && line.ends_with(";")) {
      auto identifier = line.drop_front(5).drop_back().trim();
      if (identifier.contains(',') || !validIdentifierList(identifier) ||
          !realNames.insert(identifier).second)
        return failure();
      continue;
    }
    if (line.starts_with("branch (") && line.ends_with(";")) {
      llvm::StringRef declaration = line.drop_front(sizeof("branch (") - 1).drop_back();
      size_t close = declaration.find(") ");
      if (close == llvm::StringRef::npos || !validIdentifierList(declaration.take_front(close)) ||
          !validIdentifierList(declaration.drop_front(close + 2)))
        return failure();
      continue;
    }
    if ((line.starts_with("input ") || line.starts_with("output ") || line.starts_with("inout ") ||
         line.starts_with("electrical ")) &&
        line.ends_with(";") && validIdentifierList(line.drop_front(line.find(' ') + 1).drop_back()))
      continue;
    if (validParameterDeclaration(line))
      continue;
    return failure();
  }
  return sawModule && !insideModule && !insideAnalog ? success() : failure();
}

} // namespace nodal
