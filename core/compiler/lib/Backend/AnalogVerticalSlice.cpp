#include "nodal/Backend/AnalogVerticalSlice.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Operation.h"
#include "mlir/IR/SymbolTable.h"
#include "nodal/Backend/AnalogEventBackend.h"
#include "nodal/Backend/AnalogUserFunctionBackend.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogEvents.h"
#include "nodal/Dialect/Nodal/AnalogFunctions.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"
#include "nodal/Dialect/Nodal/PotentialFlowAccess.h"
#include "nodal/Dialect/Nodal/TimeWaveform.h"
#include "nodal/Support/AnalogHierarchySyntax.h"

#include "llvm/ADT/APFloat.h"
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
#include <functional>
#include <map>
#include <optional>
#include <set>
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
    "nodal.parameter_override",
    "nodal.terminal",
    "nodal.instance",
    "nodal.instance_terminal",
    "nodal.node",
    "nodal.connect",
    "nodal.branch",
    "nodal.generate",
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
    "nodal.analog_noise",
    "nodal.analog_transfer",
    "nodal.analog_function",
    "nodal.analog_user_function",
    "nodal.analog_function_value",
    "nodal.analog_function_return",
    "nodal.analog_user_call",
    "nodal.analog_analysis",
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
  llvm::DenseMap<Value, std::string> noiseNames;
  llvm::DenseMap<Value, std::string> transferNames;
  llvm::StringMap<std::string> parameters;
  AnalogEventRenderState eventState;
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
  if (name == "nodal.analog_held_read") {
    auto variable = resolveAnalogHeldVariable(operation);
    if (failed(variable))
      return failure();
    auto found = state.eventState.names.find(*variable);
    if (found == state.eventState.names.end())
      return failure();
    return found->second;
  }

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
  } else if (name == "nodal.analog_user_call") {
    auto callee = operation->getAttrOfType<FlatSymbolRefAttr>("callee");
    if (!callee || !isPortableVerilogIdentifier(callee.getValue()))
      return failure();
    rendered = callee.getValue().str() + "(";
    for (auto [index, operand] : llvm::enumerate(operation->getOperands())) {
      auto input = renderExpression(operand, state);
      if (failed(input))
        return failure();
      rendered += (index ? ", " : "") + *input;
    }
    rendered += ")";
  } else if (name == "nodal.analog_function") {
    auto id = operation->getAttrOfType<StringAttr>("function_id");
    auto *entry = id ? lookupAnalogFunction(id.getValue()) : nullptr;
    if (!entry || operation->getNumOperands() != entry->arity)
      return failure();
    rendered = entry->verilogA.str() + "(";
    for (unsigned i = 0; i < operation->getNumOperands(); ++i) {
      auto argument = renderExpression(operation->getOperand(i), state);
      if (failed(argument))
        return failure();
      rendered += (i ? ", " : "") + *argument;
    }
    rendered += ")";
  } else if (name == "nodal.analog_analysis") {
    auto id = operation->getAttrOfType<StringAttr>("analysis_kind");
    auto *entry = id ? lookupAnalogAnalysis(id.getValue()) : nullptr;
    if (!entry)
      return failure();
    rendered = "analysis(\"" + entry->verilogA.str() + "\")";
  } else if (name == "nodal.analog_abstime") {
    rendered = "$abstime";
  } else if (name == "nodal.analog_transfer") {
    return emitMappedFailure(operation, "NODAL-BACKEND-TRANSFER-001",
                             "transfer state used before its single materialization");
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

FailureOr<std::string> renderWaveformCall(Operation *operation, ModuleRenderState &state) {
  llvm::StringRef name = operation->getName().getStringRef();
  llvm::StringRef spelling = name == "nodal.analog_bound_step"   ? "$bound_step"
                             : name == "nodal.analog_transition" ? "transition"
                             : name == "nodal.analog_slew"       ? "slew"
                             : name == "nodal.analog_absdelay"   ? "absdelay"
                                                                 : "";
  if (spelling.empty())
    return failure();

  std::string call = spelling.str() + "(";
  for (unsigned index = 0; index < operation->getNumOperands(); ++index) {
    auto argument = renderExpression(operation->getOperand(index), state);
    if (failed(argument))
      return failure();
    if (index)
      call += ", ";
    call += *argument;
  }
  call += ")";
  return call;
}

FailureOr<std::string> renderTransferCall(Operation *operation, ModuleRenderState &state) {
  auto kind = operation->getAttrOfType<StringAttr>("transfer_kind");
  auto numerator = operation->getAttrOfType<IntegerAttr>("numerator_size");
  auto denominator = operation->getAttrOfType<IntegerAttr>("denominator_size");
  if (!kind || !numerator || !denominator || numerator.getInt() <= 0 || denominator.getInt() <= 0)
    return failure();

  const auto numeratorSize = static_cast<unsigned>(numerator.getInt());
  const auto denominatorSize = static_cast<unsigned>(denominator.getInt());
  const auto coefficientCount = numeratorSize + denominatorSize;
  if (operation->getNumOperands() <= coefficientCount)
    return failure();

  auto input = renderExpression(operation->getOperand(0), state);
  if (failed(input))
    return failure();
  std::string call = kind.getValue().str() + "(" + *input;
  unsigned index = 1;
  for (unsigned size : {numeratorSize, denominatorSize}) {
    call += ", '{";
    for (unsigned coefficient = 0; coefficient < size; ++coefficient, ++index) {
      auto value = renderExpression(operation->getOperand(index), state);
      if (failed(value))
        return failure();
      call += (coefficient ? ", " : "") + *value;
    }
    call += "}";
  }
  for (; index < operation->getNumOperands(); ++index) {
    auto value = renderExpression(operation->getOperand(index), state);
    if (failed(value))
      return failure();
    call += ", " + *value;
  }
  call += ")";
  return call;
}

FailureOr<std::string> renderNoiseCall(Operation *operation, ModuleRenderState &state) {
  auto kind = operation->getAttrOfType<StringAttr>("noise_kind");
  auto label = operation->getAttrOfType<StringAttr>("noise_name");
  if (!kind || !label || label.getValue().empty())
    return failure();

  const unsigned operands = operation->getNumOperands();
  std::string call;
  if (kind.getValue() == "white" && operands == 1)
    call = "white_noise(";
  else if (kind.getValue() == "flicker" && operands == 2)
    call = "flicker_noise(";
  else if (kind.getValue() == "table" && operands > 0 && operands % 2 == 0)
    call = "noise_table('{";
  else
    return failure();

  for (unsigned index = 0; index < operands; ++index) {
    auto argument = renderExpression(operation->getOperand(index), state);
    if (failed(argument))
      return failure();
    if (index)
      call += ", ";
    call += *argument;
  }
  if (kind.getValue() == "table")
    call += "}";
  call += ", \"" + label.getValue().str() + "\")";
  return call;
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
                                 llvm::SmallVectorImpl<Operation *> &analogs,
                                 llvm::SmallVectorImpl<Operation *> &generates) {
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
    } else if (name == "nodal.generate") {
      generates.push_back(&operation);
    }
  }
  // These names share a target namespace even though the source IR uses
  // different declaration kinds. Reject invalid names before publishing HDL.
  llvm::StringSet<> declarations;
  for (const auto *group : {&parameters, &ports, &nodes, &namedBranches}) {
    for (Operation *declaration : *group) {
      auto spelling = declaration->getAttrOfType<StringAttr>(
          declaration->getName().getStringRef() == "nodal.parameter" ? "sym_name" : "name");
      if (!spelling || !isPortableVerilogIdentifier(spelling.getValue()))
        return emitMappedFailure(declaration, "NODAL-BACKEND-NAMING-001",
                                 "declaration requires a non-keyword portable HDL identifier");
      if (!declarations.insert(spelling.getValue()).second)
        return emitMappedFailure(declaration, "NODAL-BACKEND-NAMING-002",
                                 "declarations collide in the target namespace");
    }
  }
  bool validGeneratedNames = true;
  for (Operation *generate : generates) {
    generate->walk([&](Operation *operation) {
      if (operation->getName().getStringRef() != "nodal.node")
        return;
      auto spelling = operation->getAttrOfType<StringAttr>("name");
      if (!spelling || !isPortableVerilogIdentifier(spelling.getValue())) {
        operation->emitError(
            "[NODAL-BACKEND-NAMING-001] generated node requires a portable HDL identifier");
        validGeneratedNames = false;
        return;
      }
      if (!declarations.insert(spelling.getValue()).second) {
        operation->emitError(
            "[NODAL-BACKEND-NAMING-002] generated node collides in the target namespace");
        validGeneratedNames = false;
      }
    });
  }
  if (!validGeneratedNames)
    return failure();
  for (Operation &operation : region.front()) {
    if (operation.getName().getStringRef() != "nodal.analog_user_function")
      continue;
    auto name = operation.getAttrOfType<StringAttr>("sym_name");
    if (!name || !isPortableVerilogIdentifier(name.getValue()))
      return emitMappedFailure(&operation, "NODAL-BACKEND-NAMING-001",
                               "invalid function target name");
    if (!declarations.insert(name.getValue()).second)
      return emitMappedFailure(&operation, "NODAL-BACKEND-NAMING-002",
                               "function collides with a module declaration");
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

Operation *findDirectDefinition(Operation *definition, llvm::StringRef name) {
  auto outer = definition->getParentOfType<mlir::ModuleOp>();
  if (!outer)
    return nullptr;
  for (Operation &candidate : outer.getBody()->getOperations())
    if (candidate.getName().getStringRef() == "nodal.module" && symbolName(&candidate) == name)
      return &candidate;
  return nullptr;
}

Operation *findDirectParameter(Operation *definition, llvm::StringRef name) {
  if (!definition || definition->getNumRegions() != 1 ||
      !llvm::hasSingleElement(definition->getRegion(0)))
    return nullptr;
  for (Operation &candidate : definition->getRegion(0).front())
    if (candidate.getName().getStringRef() == "nodal.parameter" && symbolName(&candidate) == name)
      return &candidate;
  return nullptr;
}

FailureOr<std::string> renderParameterBinding(Attribute value, Operation *parameter) {
  llvm::StringRef kind = nodal::getParameterKind(parameter);
  if (kind == "real") {
    auto real = llvm::dyn_cast<FloatAttr>(value);
    if (!real || !std::isfinite(real.getValueAsDouble()))
      return failure();
    return formatReal(real.getValueAsDouble()) + nodal::getParameterUnitNativeSuffix(parameter);
  }
  if (kind == "integer") {
    auto integer = llvm::dyn_cast<IntegerAttr>(value);
    return integer ? renderIntegerAttribute(integer, parameter) : FailureOr<std::string>(failure());
  }
  if (kind == "boolean") {
    if (auto boolean = llvm::dyn_cast<BoolAttr>(value))
      return boolean.getValue() ? std::string("1") : std::string("0");
    if (auto integer = llvm::dyn_cast<IntegerAttr>(value))
      return integer.getInt() == 0 ? std::string("0") : std::string("1");
  }
  return failure();
}

FailureOr<bool> parameterBindingsEqual(Attribute actual, Attribute authored, Operation *parameter) {
  llvm::StringRef kind = nodal::getParameterKind(parameter);
  if (kind == "real") {
    auto actualReal = llvm::dyn_cast<FloatAttr>(actual);
    auto authoredReal = llvm::dyn_cast<FloatAttr>(authored);
    if (!actualReal || !authoredReal || !std::isfinite(actualReal.getValueAsDouble()) ||
        !std::isfinite(authoredReal.getValueAsDouble()))
      return failure();
    return actualReal.getValue().compare(authoredReal.getValue()) == llvm::APFloat::cmpEqual;
  }
  if (kind == "integer") {
    auto actualInteger = llvm::dyn_cast<IntegerAttr>(actual);
    auto authoredInteger = llvm::dyn_cast<IntegerAttr>(authored);
    if (!actualInteger || !authoredInteger)
      return failure();
    return actualInteger.getValue() == authoredInteger.getValue();
  }
  if (kind == "boolean") {
    auto booleanValue = [](Attribute value) -> std::optional<bool> {
      if (auto boolean = llvm::dyn_cast<BoolAttr>(value))
        return boolean.getValue();
      if (auto integer = llvm::dyn_cast<IntegerAttr>(value))
        return integer.getInt() != 0;
      return std::nullopt;
    };
    auto actualBoolean = booleanValue(actual);
    auto authoredBoolean = booleanValue(authored);
    if (!actualBoolean || !authoredBoolean)
      return failure();
    return *actualBoolean == *authoredBoolean;
  }
  return failure();
}

struct HierarchyInstanceRender {
  Operation *child = nullptr;
  std::map<std::string, std::string> ports;
  std::map<std::string, std::string> parameters;
};

LogicalResult renderHierarchy(Operation *definition, ModuleRenderState &state,
                              llvm::raw_ostream &output) {
  Region &region = definition->getRegion(0);
  if (!llvm::hasSingleElement(region))
    return failure();

  llvm::SmallVector<Operation *, 8> instances;
  llvm::SmallVector<Operation *, 16> instanceTerminals;
  llvm::SmallVector<Operation *, 16> connections;
  llvm::SmallVector<Operation *, 8> overrides;
  for (Operation &operation : region.front()) {
    llvm::StringRef name = operation.getName().getStringRef();
    if (name == "nodal.instance")
      instances.push_back(&operation);
    else if (name == "nodal.instance_terminal")
      instanceTerminals.push_back(&operation);
    else if (name == "nodal.connect")
      connections.push_back(&operation);
    else if (name == "nodal.parameter_override")
      overrides.push_back(&operation);
  }
  if (instances.empty() && instanceTerminals.empty() && connections.empty() && overrides.empty())
    return success();

  llvm::sort(instances, [](Operation *left, Operation *right) {
    return symbolName(left) < symbolName(right);
  });
  llvm::StringMap<Operation *> instancesByName;
  llvm::DenseMap<Operation *, HierarchyInstanceRender> rendered;
  for (Operation *instance : instances) {
    llvm::StringRef name = symbolName(instance);
    auto target = instance->getAttrOfType<FlatSymbolRefAttr>("module");
    Operation *child = target ? findDirectDefinition(definition, target.getValue()) : nullptr;
    auto domains = instance->getAttrOfType<DictionaryAttr>("domain_bindings");
    if (name.empty() || !isPortableVerilogIdentifier(name) || !child ||
        (domains && !domains.empty()) || !instancesByName.try_emplace(name, instance).second)
      return emitMappedFailure(instance, "NODAL-BACKEND-HIERARCHY-001",
                               "instance identity, target, or domain profile is unsupported");
    rendered[instance] = HierarchyInstanceRender{child, {}, {}};
  }
  if (instances.empty())
    return emitMappedFailure(definition, "NODAL-BACKEND-HIERARCHY-002",
                             "hierarchy endpoints or connections require an instance");

  llvm::SmallVector<Value, 32> endpoints;
  llvm::DenseMap<Value, unsigned> endpointIndices;
  auto addEndpoint = [&](Value value) {
    if (!endpointIndices.count(value)) {
      endpointIndices[value] = static_cast<unsigned>(endpoints.size());
      endpoints.push_back(value);
    }
  };
  for (const auto &entry : state.terminals)
    addEndpoint(entry.first);
  for (Operation *terminal : instanceTerminals) {
    if (terminal->getNumResults() != 1)
      return emitMappedFailure(terminal, "NODAL-BACKEND-HIERARCHY-002",
                               "child terminal must produce one conservative endpoint");
    addEndpoint(terminal->getResult(0));
  }
  llvm::SmallVector<unsigned, 32> parent(endpoints.size());
  for (unsigned index = 0; index < parent.size(); ++index)
    parent[index] = index;
  auto root = [&](unsigned index) {
    while (parent[index] != index) {
      parent[index] = parent[parent[index]];
      index = parent[index];
    }
    return index;
  };
  auto unite = [&](unsigned left, unsigned right) {
    left = root(left);
    right = root(right);
    if (left != right)
      parent[right] = left;
  };
  for (Operation *connection : connections) {
    if (connection->getNumOperands() < 2)
      return emitMappedFailure(connection, "NODAL-BACKEND-HIERARCHY-002",
                               "connection requires at least two known endpoints");
    auto first = endpointIndices.find(connection->getOperand(0));
    if (first == endpointIndices.end())
      return failure();
    for (Value operand : connection->getOperands()) {
      auto found = endpointIndices.find(operand);
      if (found == endpointIndices.end())
        return emitMappedFailure(connection, "NODAL-BACKEND-HIERARCHY-002",
                                 "connection references an unsupported endpoint");
      unite(first->second, found->second);
    }
  }

  llvm::DenseSet<unsigned> childRoots;
  for (Operation *terminal : instanceTerminals) {
    auto instanceReference = terminal->getAttrOfType<FlatSymbolRefAttr>("instance");
    auto port = terminal->getAttrOfType<StringAttr>("port");
    Operation *instance =
        instanceReference ? instancesByName.lookup(instanceReference.getValue()) : nullptr;
    if (!instance || !port || !isPortableVerilogIdentifier(port.getValue()))
      return emitMappedFailure(terminal, "NODAL-BACKEND-HIERARCHY-003",
                               "child terminal does not resolve to a portable instance port");
    const unsigned terminalRoot = root(endpointIndices.lookup(terminal->getResult(0)));
    childRoots.insert(terminalRoot);
    std::set<std::string> anchors;
    for (const auto &entry : state.terminals)
      if (root(endpointIndices.lookup(entry.first)) == terminalRoot)
        anchors.insert(entry.second);
    if (anchors.size() != 1)
      return emitMappedFailure(terminal, "NODAL-BACKEND-HIERARCHY-004",
                               "child port requires exactly one parent-local net anchor");
    auto &ports = rendered[instance].ports;
    if (!ports.emplace(port.getValue().str(), *anchors.begin()).second)
      return emitMappedFailure(terminal, "NODAL-BACKEND-HIERARCHY-003",
                               "child port is bound more than once");
  }
  for (Operation *connection : connections)
    if (!childRoots.count(root(endpointIndices.lookup(connection->getOperand(0)))))
      return emitMappedFailure(connection, "NODAL-BACKEND-HIERARCHY-005",
                               "parent-only connection is not representable by the scalar "
                               "instance profile");

  for (Operation *instance : instances) {
    auto &entry = rendered[instance];
    std::set<std::string> childPorts;
    for (Operation &operation : entry.child->getRegion(0).front())
      if (operation.getName().getStringRef() == "nodal.terminal") {
        auto name = operation.getAttrOfType<StringAttr>("name");
        if (!name || !isPortableVerilogIdentifier(name.getValue()) ||
            !childPorts.insert(name.getValue().str()).second)
          return emitMappedFailure(&operation, "NODAL-BACKEND-HIERARCHY-003",
                                   "child module has an invalid or duplicate boundary port");
      }
    if (entry.ports.size() != childPorts.size())
      return emitMappedFailure(instance, "NODAL-BACKEND-HIERARCHY-006",
                               "instance must bind every child boundary port exactly once");
    for (const auto &port : childPorts)
      if (!entry.ports.count(port))
        return emitMappedFailure(instance, "NODAL-BACKEND-HIERARCHY-006",
                                 "instance is missing a child boundary port binding");

    auto bindings = instance->getAttrOfType<DictionaryAttr>("parameter_bindings");
    if (!bindings)
      return emitMappedFailure(instance, "NODAL-BACKEND-HIERARCHY-007",
                               "instance parameter bindings are unavailable");
    for (NamedAttribute binding : bindings) {
      Operation *parameter = findDirectParameter(entry.child, binding.getName().getValue());
      auto variability =
          parameter ? parameter->getAttrOfType<StringAttr>("variability") : StringAttr();
      auto value = parameter ? renderParameterBinding(binding.getValue(), parameter)
                             : FailureOr<std::string>(failure());
      if (!parameter || (variability && variability.getValue() == "fixed") || failed(value) ||
          !entry.parameters.emplace(binding.getName().getValue().str(), *value).second)
        return emitMappedFailure(instance, "NODAL-BACKEND-HIERARCHY-007",
                                 "literal parameter binding is not losslessly renderable");
    }
  }

  for (Operation *override : overrides) {
    auto instanceReference = override->getAttrOfType<FlatSymbolRefAttr>("instance");
    auto parameterReference = override->getAttrOfType<FlatSymbolRefAttr>("parameter");
    Operation *instance =
        instanceReference ? instancesByName.lookup(instanceReference.getValue()) : nullptr;
    Operation *parameter =
        instance && parameterReference
            ? findDirectParameter(rendered[instance].child, parameterReference.getValue())
            : nullptr;
    auto variability =
        parameter ? parameter->getAttrOfType<StringAttr>("variability") : StringAttr();
    auto value = parameter && override->getNumOperands() == 1
                     ? nodal::renderParameterConstantExpression(override->getOperand(0), parameter)
                     : FailureOr<std::string>(failure());
    if (!instance || !parameter || (variability && variability.getValue() == "fixed") ||
        failed(value) ||
        !rendered[instance].parameters.emplace(parameterReference.getValue().str(), *value).second)
      return emitMappedFailure(override, "NODAL-BACKEND-HIERARCHY-008",
                               "symbolic parameter override is not uniquely renderable");
  }

  for (Operation *instance : instances) {
    const auto &entry = rendered[instance];
    output << "  " << symbolName(entry.child);
    if (!entry.parameters.empty()) {
      output << " #(";
      bool first = true;
      for (const auto &[name, value] : entry.parameters) {
        output << (first ? "" : ", ") << "." << name << "(" << value << ")";
        first = false;
      }
      output << ")";
    }
    output << " " << symbolName(instance) << "(";
    bool first = true;
    for (const auto &[port, actual] : entry.ports) {
      output << (first ? "" : ", ") << "." << port << "(" << actual << ")";
      first = false;
    }
    output << ");\n";
  }
  return success();
}

LogicalResult verifyRootExport(llvm::ArrayRef<Operation *> definitions) {
  if (definitions.empty())
    return failure();
  auto outer = definitions.front()->getParentOfType<mlir::ModuleOp>();
  if (!outer)
    return failure();
  auto rootReference = outer->getAttrOfType<FlatSymbolRefAttr>("nodal.root.module");
  auto bindings = outer->getAttrOfType<DictionaryAttr>("nodal.root.parameter_bindings");
  if (!rootReference && !bindings)
    return success();
  Operation *root = nullptr;
  for (Operation *definition : definitions)
    if (rootReference && symbolName(definition) == rootReference.getValue())
      root = definition;
  if (!root || !bindings)
    return emitMappedFailure(outer.getOperation(), "NODAL-BACKEND-HIERARCHY-009",
                             "root parameter bindings require one resolved root definition");

  for (NamedAttribute binding : bindings) {
    Operation *parameter = findDirectParameter(root, binding.getName().getValue());
    auto matches = parameter
                       ? parameterBindingsEqual(binding.getValue(),
                                                parameter->getAttr("default_value"), parameter)
                       : FailureOr<bool>(failure());
    if (!parameter || failed(matches) || !*matches)
      return emitMappedFailure(
          parameter ? parameter : root, "NODAL-BACKEND-HIERARCHY-010",
          "non-default root actual requires an external top-binding adapter and is unsupported");
  }
  return success();
}

LogicalResult renderAnalog(Operation *analog, ModuleRenderState &state, llvm::raw_ostream &output,
                           bool wrapped = true, bool skipProcedures = false) {
  Region &region = analog->getRegion(0);
  if (!llvm::hasSingleElement(region))
    return failure();
  if (wrapped)
    output << "  analog begin\n";
  for (Operation &operation : region.front()) {
    llvm::StringRef name = operation.getName().getStringRef();
    if (name == "nodal.analog_transfer") {
      // A generated transfer profile may pre-render one single-use call so its
      // state stays local to the generated occurrence. Ordinary transfer state
      // still materializes exactly once in a private module-scope temporary.
      if (operation.getNumResults() == 1 && state.expressions.count(operation.getResult(0)))
        continue;
      auto call = renderTransferCall(&operation, state);
      if (failed(call))
        return failure();
      auto temporary = state.transferNames.find(operation.getResult(0));
      if (temporary == state.transferNames.end())
        return emitMappedFailure(&operation, "NODAL-BACKEND-TRANSFER-001",
                                 "transfer state has no private storage");
      // Do not inline at each consumer, normalize coefficients, or omit zero filters.
      output << "    " << temporary->second << " = " << *call << ";\n";
      state.expressions[operation.getResult(0)] = temporary->second;
      continue;
    }
    if (name == "nodal.analog_noise") {
      // A supported generated-noise profile may pre-render one single-use call
      // so its source stays local to the generated occurrence. Ordinary noise
      // sources still materialize exactly once to preserve SSA correlation.
      if (operation.getNumResults() == 1 && state.expressions.count(operation.getResult(0)))
        continue;
      auto call = renderNoiseCall(&operation, state);
      if (failed(call))
        return emitMappedFailure(&operation, "NODAL-BACKEND-NOISE-001",
                                 "cannot render noise source");
      auto temporary = state.noiseNames.find(operation.getResult(0));
      if (temporary == state.noiseNames.end())
        return emitMappedFailure(&operation, "NODAL-BACKEND-NOISE-001",
                                 "noise source has no private storage");
      // One evaluation per owned SSA source, including unused and zero-power sources.
      // Never inline this call at every use: that would silently lose correlation.
      output << "    " << temporary->second << " = " << *call << ";\n";
      state.expressions[operation.getResult(0)] = temporary->second;
      continue;
    }
    if (nodal::isStatefulWaveformOperation(&operation) || name == "nodal.analog_bound_step") {
      // The generated waveform profile pre-renders one single-use call so it
      // stays per generated occurrence instead of acquiring shared module
      // storage. Ordinary waveform operations still materialize exactly once.
      if (operation.getNumResults() == 1 && state.expressions.count(operation.getResult(0)))
        continue;
      auto call = renderWaveformCall(&operation, state);
      if (failed(call))
        return failure();
      if (name == "nodal.analog_bound_step")
        output << "    " << *call << ";\n";
      else {
        const auto &temporary = state.waveformNames[operation.getResult(0)];
        output << "    " << temporary << " = " << *call << ";\n";
        state.expressions[operation.getResult(0)] = temporary;
      }
      continue;
    }
    if (name == "nodal.analog_procedure") {
      if (skipProcedures)
        continue;
      if (failed(renderAnalogEventProcedure(&operation, state.eventState, output)))
        return failure();
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
  if (wrapped)
    output << "  end\n";
  return success();
}

struct GeneratedTargetBound {
  std::string text;
  nodal::ParameterIntegerBounds range;
};

struct GeneratedNodeArrayLayout {
  std::string lower;
  std::string upper;
  std::string index;
  std::string count;
};

GeneratedNodeArrayLayout generatedNodeArrayLayout(const GeneratedTargetBound &lower,
                                                  const GeneratedTargetBound &upper,
                                                  const GeneratedTargetBound &step,
                                                  llvm::StringRef induction) {
  if (step.range.lower == 1 && step.range.upper == 1)
    return {lower.text, (llvm::Twine("(") + upper.text + " - 1)").str(), induction.str(),
            (llvm::Twine("(") + upper.text + " - " + lower.text + ")").str()};

  const std::string extent = (llvm::Twine("(") + upper.text + " - " + lower.text + ")").str();
  const std::string rounded = (llvm::Twine("((") + extent + " + " + step.text + ") - 1)").str();
  const std::string count = (llvm::Twine("(") + rounded + " / " + step.text + ")").str();
  return {"0", (llvm::Twine("(") + count + " - 1)").str(),
          (llvm::Twine("((") + induction + " - " + lower.text + ") / " + step.text + ")").str(),
          count};
}

FailureOr<GeneratedTargetBound> generatedTargetBound(Operation *generate, llvm::StringRef name) {
  Attribute attribute = generate->getAttr(name);
  if (auto value = llvm::dyn_cast_or_null<IntegerAttr>(attribute)) {
    if (!value.getValue().isSignedIntN(64)) {
      (void)emitMappedFailure(generate, "NODAL-BACKEND-CAPABILITY-001",
                              llvm::Twine("generated contribution requires signed-64 '") + name +
                                  "' bound");
      return failure();
    }
    const int64_t literal = value.getValue().getSExtValue();
    return GeneratedTargetBound{std::to_string(literal), {literal, literal}};
  }

  auto reference = llvm::dyn_cast_or_null<FlatSymbolRefAttr>(attribute);
  auto owner = generate->getParentOfType<nodal::ModuleOp>();
  Operation *parameter = reference && owner
                             ? findDirectParameter(owner.getOperation(), reference.getValue())
                             : nullptr;
  if (!parameter || nodal::getParameterKind(parameter) != "integer" ||
      !nodal::isStructuralParameter(parameter)) {
    (void)emitMappedFailure(
        generate, "NODAL-BACKEND-CAPABILITY-001",
        llvm::Twine("generated contribution requires a literal or structural ") +
            "Integer parameter '" + name + "' bound");
    return failure();
  }
  auto range = nodal::inferParameterIntegerBounds(parameter);
  if (failed(range)) {
    (void)emitMappedFailure(generate, "NODAL-BACKEND-CAPABILITY-001",
                            llvm::Twine("generated contribution cannot prove finite '") + name +
                                "' parameter bounds");
    return failure();
  }
  return GeneratedTargetBound{reference.getValue().str(), *range};
}

LogicalResult verifyGeneratedTargetBoundDependency(Value value, Operation *owner,
                                                   Operation *diagnostic,
                                                   llvm::DenseSet<Operation *> &active,
                                                   llvm::DenseSet<Operation *> &visited) {
  auto type = llvm::dyn_cast<IntegerType>(value.getType());
  Operation *definition = value.getDefiningOp();
  if (!type || !type.isSignless() || type.getWidth() != 64 || !definition ||
      definition->getParentOp() != owner || definition->hasAttr("unit"))
    return emitMappedFailure(diagnostic, "NODAL-BACKEND-CAPABILITY-001",
                             "compound generated bound escapes its signed-64 module scope");
  if (visited.contains(definition))
    return success();
  if (active.size() >= 512 || !active.insert(definition).second)
    return emitMappedFailure(diagnostic, "NODAL-BACKEND-CAPABILITY-001",
                             "compound generated bound is cyclic or exceeds the depth limit");

  auto finish = [&](LogicalResult result) {
    active.erase(definition);
    if (succeeded(result))
      visited.insert(definition);
    return result;
  };
  llvm::StringRef operationName = definition->getName().getStringRef();
  if (operationName == "nodal.const_literal" || operationName == "nodal.constant")
    return finish(success());
  if (operationName == "nodal.const_parameter_ref") {
    auto reference = definition->getAttrOfType<FlatSymbolRefAttr>("parameter");
    Operation *parameter = reference ? findDirectParameter(owner, reference.getValue()) : nullptr;
    auto declaredType = parameter ? parameter->getAttrOfType<TypeAttr>("type") : TypeAttr();
    if (!parameter || nodal::getParameterKind(parameter) != "integer" ||
        !nodal::isStructuralParameter(parameter) || !declaredType ||
        declaredType.getValue() != value.getType() || parameter->hasAttr("unit"))
      return finish(emitMappedFailure(
          diagnostic, "NODAL-BACKEND-CAPABILITY-001",
          "compound generated bound requires same-module structural Integer dependencies"));
    return finish(success());
  }
  if (operationName != "nodal.const_expr")
    return finish(emitMappedFailure(diagnostic, "NODAL-BACKEND-CAPABILITY-001",
                                    "compound generated bound contains a non-static value"));

  auto operatorName = definition->getAttrOfType<StringAttr>("operator_name");
  const unsigned arity = operatorName && operatorName.getValue() == "neg" ? 1 : 2;
  const bool supported =
      operatorName && (operatorName.getValue() == "add" || operatorName.getValue() == "sub" ||
                       operatorName.getValue() == "mul" || operatorName.getValue() == "div" ||
                       operatorName.getValue() == "mod" || operatorName.getValue() == "neg");
  if (!supported || definition->getNumOperands() != arity)
    return finish(
        emitMappedFailure(diagnostic, "NODAL-BACKEND-CAPABILITY-001",
                          "compound generated bound contains unsupported integer arithmetic"));
  for (Value operand : definition->getOperands()) {
    if (operand.getType() != value.getType() ||
        failed(verifyGeneratedTargetBoundDependency(operand, owner, diagnostic, active, visited)))
      return finish(failure());
  }
  return finish(success());
}

FailureOr<GeneratedTargetBound> generatedTargetBound(Value value, Operation *generate,
                                                     llvm::StringRef name) {
  auto module = generate->getParentOfType<nodal::ModuleOp>();
  Operation *owner = module ? module.getOperation() : nullptr;
  llvm::DenseSet<Operation *> active;
  llvm::DenseSet<Operation *> visited;
  if (!owner ||
      failed(verifyGeneratedTargetBoundDependency(value, owner, generate, active, visited)))
    return failure();
  auto range = nodal::inferParameterIntegerBounds(value);
  auto rendered = nodal::renderParameterConstantExpression(value);
  if (failed(range) || failed(rendered)) {
    (void)emitMappedFailure(generate, "NODAL-BACKEND-CAPABILITY-001",
                            llvm::Twine("generated contribution cannot prove finite '") + name +
                                "' expression bounds");
    return failure();
  }
  return GeneratedTargetBound{*rendered, *range};
}

bool generatedValueFeedsContribution(Value value, Block &body,
                                     llvm::DenseSet<Operation *> &active) {
  Operation *definition = value.getDefiningOp();
  if (!definition || !active.insert(definition).second || value.use_empty())
    return false;

  bool reachedContribution = false;
  for (OpOperand &use : value.getUses()) {
    Operation *user = use.getOwner();
    if (user->getBlock() != &body) {
      active.erase(definition);
      return false;
    }
    llvm::StringRef name = user->getName().getStringRef();
    if (name == "nodal.contribute") {
      if (use.getOperandNumber() != 1) {
        active.erase(definition);
        return false;
      }
      reachedContribution = true;
      continue;
    }
    const bool expression = name == "nodal.analog_add" || name == "nodal.analog_sub" ||
                            name == "nodal.analog_mul" || name == "nodal.analog_div" ||
                            name == "nodal.analog_neg";
    if (!expression || user->getNumResults() != 1 ||
        !generatedValueFeedsContribution(user->getResult(0), body, active)) {
      active.erase(definition);
      return false;
    }
    reachedContribution = true;
  }
  active.erase(definition);
  return reachedContribution;
}

enum class GeneratedValueEndpoint { Contribution, IdtInput };

bool generatedValueFeedsOneEndpoint(Value value, Block &body, llvm::DenseSet<Operation *> &active,
                                    GeneratedValueEndpoint endpoint,
                                    bool traversedExpression = false,
                                    Value contributionBranch = {}) {
  Operation *definition = value.getDefiningOp();
  if (!definition || !value.hasOneUse() || !active.insert(definition).second)
    return false;

  OpOperand &use = *value.getUses().begin();
  Operation *user = use.getOwner();
  if (user->getBlock() != &body) {
    active.erase(definition);
    return false;
  }
  llvm::StringRef name = user->getName().getStringRef();
  const bool reachedContribution =
      endpoint == GeneratedValueEndpoint::Contribution && name == "nodal.contribute" &&
      use.getOperandNumber() == 1 &&
      (!contributionBranch || user->getOperand(0) == contributionBranch);
  const bool reachedIdt = endpoint == GeneratedValueEndpoint::IdtInput &&
                          name == "nodal.analog_idt" && traversedExpression &&
                          use.getOperandNumber() == 0;
  if (reachedContribution || reachedIdt) {
    active.erase(definition);
    return true;
  }
  const bool expression = name == "nodal.analog_add" || name == "nodal.analog_sub" ||
                          name == "nodal.analog_mul" || name == "nodal.analog_div" ||
                          name == "nodal.analog_neg";
  const bool result = expression && user->getNumResults() == 1 &&
                      generatedValueFeedsOneEndpoint(user->getResult(0), body, active, endpoint,
                                                     true, contributionBranch);
  active.erase(definition);
  return result;
}

bool generatedValueFeedsOneContribution(Value value, Block &body,
                                        llvm::DenseSet<Operation *> &active,
                                        Value contributionBranch = {}) {
  return generatedValueFeedsOneEndpoint(value, body, active, GeneratedValueEndpoint::Contribution,
                                        false, contributionBranch);
}

bool generatedValueFeedsOneIdtInput(Value value, Block &body, llvm::DenseSet<Operation *> &active) {
  return generatedValueFeedsOneEndpoint(value, body, active, GeneratedValueEndpoint::IdtInput);
}

bool isGeneratedNodeContributionProfile(Operation *generate) {
  Region &body = generate->getRegion(0);
  if (!llvm::hasSingleElement(body) || body.front().getOperations().size() < 3)
    return false;
  auto operation = body.front().begin();
  while (operation != body.front().end() && operation->getName().getStringRef() == "nodal.node")
    ++operation;
  if (operation == body.front().begin() || operation == body.front().end() ||
      operation->getName().getStringRef() != "nodal.branch")
    return false;
  while (operation != body.front().end() && operation->getName().getStringRef() == "nodal.branch")
    ++operation;
  if (operation == body.front().end() || operation->getName().getStringRef() != "nodal.analog")
    return false;
  return ++operation == body.front().end();
}

Operation *nestedGeneratedNodeContribution(Operation *generate) {
  Region &body = generate->getRegion(0);
  if (!llvm::hasSingleElement(body) || body.front().getOperations().size() != 1)
    return nullptr;
  Operation &child = body.front().front();
  if (child.getName().getStringRef() != "nodal.generate" ||
      !isGeneratedNodeContributionProfile(&child))
    return nullptr;

  Region &childBody = child.getRegion(0);
  unsigned nodes = 0, branches = 0;
  Operation *analog = nullptr;
  for (Operation &operation : childBody.front()) {
    llvm::StringRef name = operation.getName().getStringRef();
    if (name == "nodal.node")
      ++nodes;
    else if (name == "nodal.branch")
      ++branches;
    else if (name == "nodal.analog")
      analog = &operation;
  }
  if (nodes == 0 || nodes > 2 || branches == 0 || branches > nodes || !analog ||
      !llvm::hasSingleElement(analog->getRegion(0)))
    return nullptr;

  unsigned accesses = 0, contributions = 0, derivatives = 0, integrals = 0;
  for (Operation &operation : analog->getRegion(0).front()) {
    llvm::StringRef name = operation.getName().getStringRef();
    if (name == "nodal.access")
      ++accesses;
    else if (name == "nodal.analog_ddt")
      ++derivatives;
    else if (name == "nodal.analog_idt")
      ++integrals;
    else if (name == "nodal.contribute")
      ++contributions;
    else if (name != "nodal.real_literal" && name != "nodal.parameter_reference" &&
             name != "nodal.analog_add" && name != "nodal.analog_sub" &&
             name != "nodal.analog_mul" && name != "nodal.analog_div" && name != "nodal.analog_neg")
      return nullptr;
  }
  const unsigned operators = derivatives + integrals;
  if (operators != 0)
    return nodes == 1 && branches == 1 && accesses == 1 && contributions == 1 && operators == 1
               ? &child
               : nullptr;
  return accesses == branches && contributions == branches ? &child : nullptr;
}

LogicalResult renderGeneratedContribution(Operation *generate, ModuleRenderState &state,
                                          llvm::raw_ostream &output,
                                          Operation *parentGenerate = nullptr) {
  if (generate->getNumOperands() != 0 && generate->getNumOperands() != 3)
    return emitMappedFailure(generate, "NODAL-BACKEND-CAPABILITY-001",
                             "generated Verilog-A bounds require attributes or three SSA values");
  const bool compound = generate->getNumOperands() == 3;
  auto lower = compound ? generatedTargetBound(generate->getOperand(0), generate, "lower")
                        : generatedTargetBound(generate, "lower");
  auto upper = compound ? generatedTargetBound(generate->getOperand(1), generate, "upper")
                        : generatedTargetBound(generate, "upper");
  auto step = compound ? generatedTargetBound(generate->getOperand(2), generate, "step")
                       : generatedTargetBound(generate, "step");
  if (failed(lower) || failed(upper) || failed(step))
    return failure();
  if (step->range.lower <= 0 || lower->range.upper > upper->range.lower)
    return emitMappedFailure(generate, "NODAL-BACKEND-CAPABILITY-001",
                             "generated Verilog-A range must be forward for every legal setting");

  auto induction = generate->getAttrOfType<StringAttr>("induction");
  auto inductionPath = generate->getAttrOfType<StringAttr>("induction_path");
  auto regionId = generate->getAttrOfType<StringAttr>("region_id");
  if (!induction || !inductionPath || !regionId ||
      !isPortableVerilogIdentifier(induction.getValue()))
    return emitMappedFailure(generate, "NODAL-BACKEND-CAPABILITY-001",
                             "generated Verilog-A range requires canonical target identities");
  llvm::StringRef label = regionId.getValue();
  if (size_t separator = label.rfind('.'); separator != llvm::StringRef::npos)
    label = label.drop_front(separator + 1);
  if (!isPortableVerilogIdentifier(label))
    return emitMappedFailure(generate, "NODAL-BACKEND-CAPABILITY-001",
                             "generated Verilog-A label is not a portable identifier");

  std::optional<GeneratedNodeArrayLayout> parentLayout;
  StringAttr parentInduction;
  StringAttr parentRegionId;
  StringAttr parentInductionPath;
  std::optional<GeneratedTargetBound> parentLower;
  std::optional<GeneratedTargetBound> parentUpper;
  std::optional<GeneratedTargetBound> parentStep;
  if (parentGenerate) {
    if (nestedGeneratedNodeContribution(parentGenerate) != generate)
      return emitMappedFailure(
          generate, "NODAL-BACKEND-CAPABILITY-001",
          "nested generated analog-node equation requires one direct child equation profile");
    const bool parentCompound = parentGenerate->getNumOperands() == 3;
    if (parentGenerate->getNumOperands() != 0 && !parentCompound)
      return emitMappedFailure(
          parentGenerate, "NODAL-BACKEND-CAPABILITY-001",
          "nested generated Verilog-A bounds require attributes or three SSA values");
    auto resolvedParentLower = parentCompound ? generatedTargetBound(parentGenerate->getOperand(0),
                                                                     parentGenerate, "lower")
                                              : generatedTargetBound(parentGenerate, "lower");
    auto resolvedParentUpper = parentCompound ? generatedTargetBound(parentGenerate->getOperand(1),
                                                                     parentGenerate, "upper")
                                              : generatedTargetBound(parentGenerate, "upper");
    auto resolvedParentStep =
        parentCompound ? generatedTargetBound(parentGenerate->getOperand(2), parentGenerate, "step")
                       : generatedTargetBound(parentGenerate, "step");
    if (failed(resolvedParentLower) || failed(resolvedParentUpper) || failed(resolvedParentStep))
      return failure();
    parentLower = *resolvedParentLower;
    parentUpper = *resolvedParentUpper;
    parentStep = *resolvedParentStep;
    parentInduction = parentGenerate->getAttrOfType<StringAttr>("induction");
    parentRegionId = parentGenerate->getAttrOfType<StringAttr>("region_id");
    parentInductionPath = parentGenerate->getAttrOfType<StringAttr>("induction_path");
    if (!parentInduction || !parentRegionId || !parentInductionPath ||
        !isPortableVerilogIdentifier(parentInduction.getValue()) || parentStep->range.lower <= 0 ||
        parentLower->range.upper >= parentUpper->range.lower)
      return emitMappedFailure(parentGenerate, "NODAL-BACKEND-CAPABILITY-001",
                               "nested generated analog-node equation requires canonical nonempty "
                               "positive parent bounds");
    parentLayout = generatedNodeArrayLayout(*parentLower, *parentUpper, *parentStep,
                                            parentInduction.getValue());
  }

  Region &body = generate->getRegion(0);
  if (!llvm::hasSingleElement(body))
    return emitMappedFailure(generate, "NODAL-BACKEND-CAPABILITY-001",
                             "generated Verilog-A range requires one body block");
  Operation *analog = nullptr;
  llvm::SmallVector<Operation *, 4> generatedNodes;
  llvm::SmallVector<Operation *, 4> generatedBranches;
  const bool generatedNodeContributionProfile = isGeneratedNodeContributionProfile(generate);
  if (generatedNodeContributionProfile) {
    auto operation = body.front().begin();
    while (operation->getName().getStringRef() == "nodal.node") {
      generatedNodes.push_back(&*operation);
      ++operation;
    }
    while (operation->getName().getStringRef() == "nodal.branch") {
      generatedBranches.push_back(&*operation);
      ++operation;
    }
    analog = &body.front().back();
    if (step->range.lower <= 0 || lower->range.upper >= upper->range.lower)
      return emitMappedFailure(
          generate, "NODAL-BACKEND-CAPABILITY-001",
          "generated analog-node contribution requires a nonempty positive-step range");
    const GeneratedNodeArrayLayout layout =
        generatedNodeArrayLayout(*lower, *upper, *step, induction.getValue());
    llvm::StringSet<> nodeNames;
    llvm::StringSet<> sourcePaths;
    for (Operation *node : generatedNodes) {
      auto nodeName = node->getAttrOfType<StringAttr>("name");
      auto sourcePath = node->getAttrOfType<StringAttr>("source_path");
      auto nodeOwner = node->getAttrOfType<StringAttr>("generated_owner");
      auto nodeInduction = node->getAttrOfType<StringAttr>("generated_induction");
      auto terminalType = node->getNumResults() == 1
                              ? llvm::dyn_cast<nodal::TerminalType>(node->getResult(0).getType())
                              : nodal::TerminalType();
      if (!nodeName || !sourcePath || sourcePath.getValue().trim().empty() ||
          !isPortableVerilogIdentifier(nodeName.getValue()) || !nodeOwner || !nodeInduction ||
          nodeOwner.getValue() != regionId.getValue() ||
          nodeInduction.getValue() != inductionPath.getValue() || !terminalType ||
          terminalType.getDiscipline() != "electrical" ||
          !nodeNames.insert(nodeName.getValue()).second ||
          !sourcePaths.insert(sourcePath.getValue()).second)
        return emitMappedFailure(
            node, "NODAL-BACKEND-CAPABILITY-001",
            "generated analog-node contribution requires exactly-owned electrical nodes with "
            "unique names and source identities");
      const std::string targetIndex = parentLayout
                                          ? (llvm::Twine("((") + parentLayout->index + " * " +
                                             layout.count + ") + " + layout.index + ")")
                                                .str()
                                          : layout.index;
      const std::string indexedNode =
          (llvm::Twine(nodeName.getValue()) + "[" + targetIndex + "]").str();
      state.terminals.try_emplace(node->getResult(0), indexedNode);
    }
    llvm::DenseSet<Value> selectedNodes;
    for (Operation *node : generatedNodes) {
      for (OpOperand &use : node->getResult(0).getUses()) {
        if (!llvm::is_contained(generatedBranches, use.getOwner()) || use.getOperandNumber() > 1)
          return emitMappedFailure(
              node, "NODAL-BACKEND-CAPABILITY-001",
              "generated analog-node arrays may escape only through direct owned branches");
      }
    }
    for (Operation *branch : generatedBranches) {
      auto branchMetadata = branch->getAttrOfType<DictionaryAttr>("metadata");
      auto branchOwner =
          branchMetadata ? branchMetadata.getAs<StringAttr>("generated_owner") : StringAttr();
      auto branchInduction =
          branchMetadata ? branchMetadata.getAs<StringAttr>("generated_induction") : StringAttr();
      if (branch->getNumOperands() != 2 || branch->getNumResults() != 1)
        return emitMappedFailure(
            branch, "NODAL-BACKEND-CAPABILITY-001",
            "generated analog-node branch requires two terminals and one branch result");
      if (!branchOwner || !branchInduction || branchOwner.getValue() != regionId.getValue() ||
          branchInduction.getValue() != inductionPath.getValue())
        return emitMappedFailure(branch, "NODAL-BACKEND-CAPABILITY-001",
                                 "generated analog-node branch ownership does not match its range");

      Operation *positiveNode = nullptr;
      Operation *negativeNode = nullptr;
      for (Operation *node : generatedNodes) {
        const bool positive = branch->getOperand(0) == node->getResult(0);
        const bool negative = branch->getOperand(1) == node->getResult(0);
        if (!positive && !negative)
          continue;
        if ((positive && positiveNode) || (negative && negativeNode))
          return emitMappedFailure(
              branch, "NODAL-BACKEND-CAPABILITY-001",
              "generated analog-node branches require unambiguous owned array elements");
        if (positive)
          positiveNode = node;
        if (negative)
          negativeNode = node;
      }
      if (!positiveNode && !negativeNode)
        return emitMappedFailure(
            branch, "NODAL-BACKEND-CAPABILITY-001",
            "generated analog-node branch requires at least one owned array element");
      if (positiveNode && positiveNode == negativeNode)
        return emitMappedFailure(
            branch, "NODAL-BACKEND-CAPABILITY-001",
            "generated analog-node branch cannot select the same array element twice");
      auto positive = state.terminals.find(branch->getOperand(0));
      auto negative = state.terminals.find(branch->getOperand(1));
      if (positive == state.terminals.end() || negative == state.terminals.end())
        return emitMappedFailure(branch, "NODAL-BACKEND-CAPABILITY-001",
                                 "generated analog-node branch requires only known terminals");
      if (positiveNode)
        selectedNodes.insert(positiveNode->getResult(0));
      if (negativeNode)
        selectedNodes.insert(negativeNode->getResult(0));
      if (!state.branches
               .try_emplace(branch->getResult(0),
                            std::make_pair(positive->second, negative->second))
               .second)
        return emitMappedFailure(branch, "NODAL-BACKEND-CAPABILITY-001",
                                 "generated analog-node branch result is duplicated");
    }
    for (Operation *node : generatedNodes)
      if (!selectedNodes.contains(node->getResult(0)) && !node->getResult(0).use_empty())
        return emitMappedFailure(
            node, "NODAL-BACKEND-CAPABILITY-001",
            "unselected generated analog-node array must not escape its direct body");
  } else {
    for (Operation &operation : body.front()) {
      if (operation.getName().getStringRef() != "nodal.analog" || analog)
        return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                                 "fixed generated Verilog-A profile supports one analog region");
      analog = &operation;
    }
  }
  if (!analog)
    return emitMappedFailure(generate, "NODAL-BACKEND-CAPABILITY-001",
                             "fixed generated Verilog-A range has no analog contribution region");
  auto analogOwner = analog->getAttrOfType<StringAttr>("generated_owner");
  auto analogInduction = analog->getAttrOfType<StringAttr>("generated_induction");
  if (!analogOwner || !analogInduction || analogOwner.getValue() != regionId.getValue() ||
      analogInduction.getValue() != inductionPath.getValue())
    return emitMappedFailure(analog, "NODAL-BACKEND-CAPABILITY-001",
                             "generated analog region ownership does not match its range");
  if (!llvm::hasSingleElement(analog->getRegion(0)))
    return emitMappedFailure(analog, "NODAL-BACKEND-CAPABILITY-001",
                             "generated analog region requires one body block");

  Block &analogBody = analog->getRegion(0).front();
  unsigned contributions = 0;
  unsigned generatedNodeAccesses = 0;
  unsigned generatedNodeOperatorAccesses = 0;
  llvm::DenseSet<Value> accessedGeneratedBranches;
  llvm::DenseSet<Value> contributedGeneratedBranches;
  unsigned procedures = 0;
  unsigned boundStepEffects = 0;
  Operation *eventProcedure = nullptr;
  for (Operation &operation : analogBody) {
    llvm::StringRef name = operation.getName().getStringRef();
    if (name == "nodal.analog_procedure") {
      auto owner = operation.getAttrOfType<StringAttr>("generated_owner");
      auto generatedInduction = operation.getAttrOfType<StringAttr>("generated_induction");
      if (!owner || !generatedInduction || owner.getValue() != regionId.getValue() ||
          generatedInduction.getValue() != inductionPath.getValue())
        return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                                 "generated event procedure ownership does not match its range");
      const bool eventProcedureProfile = hasAnalogEvents(&operation);
      bool nonEventLoopConditionalProfile = false;
      bool nonEventLoopConditionalExitProfile = false;
      bool nonEventLoopConditionalScopeProfile = false;
      bool nonEventLoopConditionalScopeExitProfile = false;
      bool nonEventLoopCaseProfile = false;
      bool nonEventLoopCaseExitProfile = false;
      bool nonEventLoopCaseScopeProfile = false;
      bool nonEventLoopCaseScopeExitProfile = false;
      bool nonEventLoopExitProfile = false;
      bool nonEventLoopScopeProfile = false;
      bool nonEventLoopScopeExitProfile = false;
      if (!eventProcedureProfile) {
        unsigned variables = 0;
        unsigned reads = 0;
        unsigned assignments = 0;
        unsigned conditionals = 0;
        unsigned conditionalArms = 0;
        unsigned cases = 0;
        unsigned caseArms = 0;
        unsigned breaks = 0;
        unsigned continues = 0;
        unsigned loops = 0;
        unsigned scopes = 0;
        unsigned loopConditionalScopeReads = 0;
        unsigned loopConditionalScopeAssignments = 0;
        unsigned loopCaseScopeReads = 0;
        unsigned loopCaseScopeAssignments = 0;
        bool directProfile = true;
        bool conditionalProfile = true;
        bool caseProfile = true;
        bool loopProfile = true;
        bool loopConditionalProfile = true;
        bool loopConditionalExitProfile = true;
        bool loopConditionalScopeProfile = true;
        bool loopConditionalScopeExitProfile = true;
        bool loopCaseProfile = true;
        bool loopCaseExitProfile = true;
        bool loopCaseScopeProfile = true;
        bool loopCaseScopeExitProfile = true;
        bool loopExitProfile = true;
        bool loopScopeProfile = true;
        bool loopScopeExitProfile = true;
        auto isDirectLoopConditionalArm = [&](Operation *candidate) {
          auto arm = llvm::dyn_cast_or_null<AnalogIfArmOp>(candidate);
          Operation *conditional = arm ? arm->getParentOp() : nullptr;
          Operation *loop = conditional ? conditional->getParentOp() : nullptr;
          return conditional && llvm::isa<AnalogIfOp>(conditional) && loop &&
                 llvm::isa<AnalogLoopOp>(loop) && loop->getParentOp() == &operation;
        };
        auto isDirectLoopCaseArm = [&](Operation *candidate) {
          auto arm = llvm::dyn_cast_or_null<AnalogCaseArmOp>(candidate);
          Operation *caseOperation = arm ? arm->getParentOp() : nullptr;
          Operation *loop = caseOperation ? caseOperation->getParentOp() : nullptr;
          return caseOperation && llvm::isa<AnalogCaseOp>(caseOperation) && loop &&
                 llvm::isa<AnalogLoopOp>(loop) && loop->getParentOp() == &operation;
        };
        auto isDirectLoopConditionalArmScope = [&](Operation *candidate) {
          auto scope = llvm::dyn_cast_or_null<AnalogScopeOp>(candidate);
          return scope && isDirectLoopConditionalArm(scope->getParentOp());
        };
        auto isDirectLoopCaseArmScope = [&](Operation *candidate) {
          auto scope = llvm::dyn_cast_or_null<AnalogScopeOp>(candidate);
          return scope && isDirectLoopCaseArm(scope->getParentOp());
        };
        auto isDirectLoopScope = [&](Operation *candidate) {
          auto scope = llvm::dyn_cast_or_null<AnalogScopeOp>(candidate);
          Operation *loop = scope ? scope->getParentOp() : nullptr;
          return loop && llvm::isa<AnalogLoopOp>(loop) && loop->getParentOp() == &operation;
        };
        operation.walk([&](Operation *nested) {
          if (nested == &operation)
            return;
          Operation *parent = nested->getParentOp();
          if (llvm::isa<AnalogVariableOp>(nested)) {
            ++variables;
            directProfile &= parent == &operation;
            conditionalProfile &= parent == &operation;
            caseProfile &= parent == &operation;
            loopProfile &= parent == &operation;
            loopConditionalProfile &= parent == &operation;
            loopConditionalExitProfile &= parent == &operation;
            loopConditionalScopeProfile &= parent == &operation;
            loopConditionalScopeExitProfile &= parent == &operation;
            loopCaseProfile &= parent == &operation;
            loopCaseExitProfile &= parent == &operation;
            loopCaseScopeProfile &= parent == &operation;
            loopCaseScopeExitProfile &= parent == &operation;
            loopExitProfile &= parent == &operation;
            loopScopeProfile &= parent == &operation;
            loopScopeExitProfile &= parent == &operation;
            return;
          }
          if (llvm::isa<AnalogVariableReadOp>(nested)) {
            ++reads;
            if (isDirectLoopConditionalArmScope(parent))
              ++loopConditionalScopeReads;
            if (isDirectLoopCaseArmScope(parent))
              ++loopCaseScopeReads;
            directProfile &= parent == &operation;
            conditionalProfile &= llvm::isa<AnalogIfArmOp>(parent) && parent->getParentOp() &&
                                  parent->getParentOp()->getParentOp() == &operation;
            caseProfile &= llvm::isa<AnalogCaseArmOp>(parent) && parent->getParentOp() &&
                           parent->getParentOp()->getParentOp() == &operation;
            loopProfile &= llvm::isa<AnalogLoopOp>(parent) && parent->getParentOp() == &operation;
            loopConditionalProfile &= isDirectLoopConditionalArm(parent);
            loopConditionalExitProfile &= isDirectLoopConditionalArm(parent);
            loopConditionalScopeProfile &=
                isDirectLoopConditionalArm(parent) || isDirectLoopConditionalArmScope(parent);
            loopConditionalScopeExitProfile &=
                isDirectLoopConditionalArm(parent) || isDirectLoopConditionalArmScope(parent);
            loopCaseProfile &= isDirectLoopCaseArm(parent);
            loopCaseExitProfile &= isDirectLoopCaseArm(parent);
            loopCaseScopeProfile &= isDirectLoopCaseArm(parent) || isDirectLoopCaseArmScope(parent);
            loopCaseScopeExitProfile &=
                isDirectLoopCaseArm(parent) || isDirectLoopCaseArmScope(parent);
            loopExitProfile &=
                llvm::isa<AnalogLoopOp>(parent) && parent->getParentOp() == &operation;
            loopScopeProfile &= llvm::isa<AnalogScopeOp>(parent) && parent->getParentOp() &&
                                llvm::isa<AnalogLoopOp>(parent->getParentOp()) &&
                                parent->getParentOp()->getParentOp() == &operation;
            loopScopeExitProfile &= isDirectLoopScope(parent);
            return;
          }
          if (llvm::isa<AnalogAssignOp>(nested)) {
            ++assignments;
            if (isDirectLoopConditionalArmScope(parent))
              ++loopConditionalScopeAssignments;
            if (isDirectLoopCaseArmScope(parent))
              ++loopCaseScopeAssignments;
            directProfile &= parent == &operation;
            conditionalProfile &= llvm::isa<AnalogIfArmOp>(parent) && parent->getParentOp() &&
                                  parent->getParentOp()->getParentOp() == &operation;
            caseProfile &= llvm::isa<AnalogCaseArmOp>(parent) && parent->getParentOp() &&
                           parent->getParentOp()->getParentOp() == &operation;
            loopProfile &= llvm::isa<AnalogLoopOp>(parent) && parent->getParentOp() == &operation;
            loopConditionalProfile &= isDirectLoopConditionalArm(parent);
            loopConditionalExitProfile &= isDirectLoopConditionalArm(parent);
            loopConditionalScopeProfile &=
                isDirectLoopConditionalArm(parent) || isDirectLoopConditionalArmScope(parent);
            loopConditionalScopeExitProfile &=
                isDirectLoopConditionalArm(parent) || isDirectLoopConditionalArmScope(parent);
            loopCaseProfile &= isDirectLoopCaseArm(parent);
            loopCaseExitProfile &= isDirectLoopCaseArm(parent);
            loopCaseScopeProfile &= isDirectLoopCaseArm(parent) || isDirectLoopCaseArmScope(parent);
            loopCaseScopeExitProfile &=
                isDirectLoopCaseArm(parent) || isDirectLoopCaseArmScope(parent);
            loopExitProfile &=
                llvm::isa<AnalogLoopOp>(parent) && parent->getParentOp() == &operation;
            loopScopeProfile &= llvm::isa<AnalogScopeOp>(parent) && parent->getParentOp() &&
                                llvm::isa<AnalogLoopOp>(parent->getParentOp()) &&
                                parent->getParentOp()->getParentOp() == &operation;
            loopScopeExitProfile &= isDirectLoopScope(parent);
            return;
          }
          directProfile = false;
          if (llvm::isa<AnalogIfOp>(nested)) {
            ++conditionals;
            conditionalProfile &= parent == &operation;
            caseProfile = false;
            loopProfile = false;
            loopConditionalProfile &=
                llvm::isa<AnalogLoopOp>(parent) && parent->getParentOp() == &operation;
            loopConditionalExitProfile &=
                llvm::isa<AnalogLoopOp>(parent) && parent->getParentOp() == &operation;
            loopConditionalScopeProfile &=
                llvm::isa<AnalogLoopOp>(parent) && parent->getParentOp() == &operation;
            loopConditionalScopeExitProfile &=
                llvm::isa<AnalogLoopOp>(parent) && parent->getParentOp() == &operation;
            loopCaseProfile = false;
            loopCaseExitProfile = false;
            loopCaseScopeProfile = false;
            loopCaseScopeExitProfile = false;
            loopExitProfile = false;
            loopScopeProfile = false;
            loopScopeExitProfile = false;
            return;
          }
          if (llvm::isa<AnalogIfArmOp>(nested)) {
            ++conditionalArms;
            conditionalProfile &=
                llvm::isa<AnalogIfOp>(parent) && parent->getParentOp() == &operation;
            caseProfile = false;
            loopProfile = false;
            loopConditionalProfile &= isDirectLoopConditionalArm(nested);
            loopConditionalExitProfile &= isDirectLoopConditionalArm(nested);
            loopConditionalScopeProfile &= isDirectLoopConditionalArm(nested);
            loopConditionalScopeExitProfile &= isDirectLoopConditionalArm(nested);
            loopCaseProfile = false;
            loopCaseExitProfile = false;
            loopCaseScopeProfile = false;
            loopCaseScopeExitProfile = false;
            loopExitProfile = false;
            loopScopeProfile = false;
            loopScopeExitProfile = false;
            return;
          }
          if (llvm::isa<AnalogCaseOp>(nested)) {
            ++cases;
            caseProfile &= parent == &operation;
            conditionalProfile = false;
            loopProfile = false;
            loopConditionalProfile = false;
            loopConditionalExitProfile = false;
            loopConditionalScopeProfile = false;
            loopConditionalScopeExitProfile = false;
            loopCaseProfile &=
                llvm::isa<AnalogLoopOp>(parent) && parent->getParentOp() == &operation;
            loopCaseExitProfile &=
                llvm::isa<AnalogLoopOp>(parent) && parent->getParentOp() == &operation;
            loopCaseScopeProfile &=
                llvm::isa<AnalogLoopOp>(parent) && parent->getParentOp() == &operation;
            loopCaseScopeExitProfile &=
                llvm::isa<AnalogLoopOp>(parent) && parent->getParentOp() == &operation;
            loopExitProfile = false;
            loopScopeProfile = false;
            loopScopeExitProfile = false;
            return;
          }
          if (llvm::isa<AnalogCaseArmOp>(nested)) {
            ++caseArms;
            caseProfile &= llvm::isa<AnalogCaseOp>(parent) && parent->getParentOp() == &operation;
            conditionalProfile = false;
            loopProfile = false;
            loopConditionalProfile = false;
            loopConditionalExitProfile = false;
            loopConditionalScopeProfile = false;
            loopConditionalScopeExitProfile = false;
            loopCaseProfile &= isDirectLoopCaseArm(nested);
            loopCaseExitProfile &= isDirectLoopCaseArm(nested);
            loopCaseScopeProfile &= isDirectLoopCaseArm(nested);
            loopCaseScopeExitProfile &= isDirectLoopCaseArm(nested);
            loopExitProfile = false;
            loopScopeProfile = false;
            loopScopeExitProfile = false;
            return;
          }
          if (llvm::isa<AnalogLoopOp>(nested)) {
            ++loops;
            loopProfile &= parent == &operation;
            conditionalProfile = false;
            caseProfile = false;
            loopConditionalProfile &= parent == &operation;
            loopConditionalExitProfile &= parent == &operation;
            loopConditionalScopeProfile &= parent == &operation;
            loopConditionalScopeExitProfile &= parent == &operation;
            loopCaseProfile &= parent == &operation;
            loopCaseExitProfile &= parent == &operation;
            loopCaseScopeProfile &= parent == &operation;
            loopCaseScopeExitProfile &= parent == &operation;
            loopExitProfile &= parent == &operation;
            loopScopeProfile &= parent == &operation;
            loopScopeExitProfile &= parent == &operation;
            return;
          }
          if (llvm::isa<AnalogScopeOp>(nested)) {
            ++scopes;
            conditionalProfile = false;
            caseProfile = false;
            loopProfile = false;
            loopConditionalProfile = false;
            loopConditionalExitProfile = false;
            loopConditionalScopeProfile &= isDirectLoopConditionalArm(parent);
            loopConditionalScopeExitProfile &= isDirectLoopConditionalArm(parent);
            loopCaseProfile = false;
            loopCaseExitProfile = false;
            loopCaseScopeProfile &= isDirectLoopCaseArm(parent);
            loopCaseScopeExitProfile &= isDirectLoopCaseArm(parent);
            loopExitProfile = false;
            loopScopeProfile &=
                llvm::isa<AnalogLoopOp>(parent) && parent->getParentOp() == &operation;
            loopScopeExitProfile &=
                llvm::isa<AnalogLoopOp>(parent) && parent->getParentOp() == &operation;
            return;
          }
          if (llvm::isa<AnalogBreakOp, AnalogContinueOp>(nested)) {
            breaks += llvm::isa<AnalogBreakOp>(nested);
            continues += llvm::isa<AnalogContinueOp>(nested);
            conditionalProfile = false;
            caseProfile = false;
            loopProfile = false;
            loopConditionalProfile = false;
            loopConditionalScopeProfile = false;
            loopConditionalScopeExitProfile &= llvm::isa<AnalogBreakOp, AnalogContinueOp>(nested) &&
                                               isDirectLoopConditionalArmScope(parent);
            loopCaseProfile = false;
            loopCaseScopeProfile = false;
            loopCaseScopeExitProfile &= llvm::isa<AnalogBreakOp, AnalogContinueOp>(nested) &&
                                        isDirectLoopCaseArmScope(parent);
            loopConditionalExitProfile &= llvm::isa<AnalogBreakOp, AnalogContinueOp>(nested) &&
                                          isDirectLoopConditionalArm(parent);
            loopCaseExitProfile &=
                llvm::isa<AnalogBreakOp, AnalogContinueOp>(nested) && isDirectLoopCaseArm(parent);
            loopExitProfile &=
                llvm::isa<AnalogLoopOp>(parent) && parent->getParentOp() == &operation;
            loopScopeProfile = false;
            loopScopeExitProfile &= isDirectLoopScope(parent);
            return;
          }
          conditionalProfile = false;
          caseProfile = false;
          loopProfile = false;
          loopConditionalProfile = false;
          loopConditionalExitProfile = false;
          loopConditionalScopeProfile = false;
          loopConditionalScopeExitProfile = false;
          loopCaseProfile = false;
          loopCaseExitProfile = false;
          loopCaseScopeProfile = false;
          loopCaseScopeExitProfile = false;
          loopExitProfile = false;
          loopScopeProfile = false;
          loopScopeExitProfile = false;
        });
        directProfile &= variables == 1 && reads == 1 && assignments == 1;
        conditionalProfile &= variables == 2 && reads == 2 && assignments == 2 &&
                              conditionals == 1 && conditionalArms == 2;
        caseProfile &=
            variables == 2 && reads == 2 && assignments == 2 && cases == 1 && caseArms == 2;
        loopProfile &= variables == 2 && reads == 1 && assignments == 1 && loops == 1;
        loopConditionalProfile &= variables == 3 && reads == 2 && assignments == 2 &&
                                  conditionals == 1 && conditionalArms == 2 && loops == 1;
        loopConditionalExitProfile &= variables == 3 && reads == 2 && assignments == 2 &&
                                      conditionals == 1 && conditionalArms == 2 && loops == 1 &&
                                      breaks + continues == 1;
        loopConditionalScopeProfile &= variables == 3 && reads == 2 && assignments == 2 &&
                                       conditionals == 1 && conditionalArms == 2 && loops == 1 &&
                                       scopes == 1 && loopConditionalScopeReads == 1 &&
                                       loopConditionalScopeAssignments == 1;
        loopConditionalScopeExitProfile &=
            variables == 3 && reads == 2 && assignments == 2 && conditionals == 1 &&
            conditionalArms == 2 && loops == 1 && scopes == 1 && loopConditionalScopeReads == 1 &&
            loopConditionalScopeAssignments == 1 && breaks + continues == 1;
        loopCaseProfile &= variables == 3 && reads == 2 && assignments == 2 && cases == 1 &&
                           caseArms == 2 && loops == 1;
        loopCaseExitProfile &= variables == 3 && reads == 2 && assignments == 2 && cases == 1 &&
                               caseArms == 2 && loops == 1 && breaks + continues == 1;
        loopCaseScopeProfile &= variables == 3 && reads == 2 && assignments == 2 && cases == 1 &&
                                caseArms == 2 && loops == 1 && scopes == 1 &&
                                loopCaseScopeReads == 1 && loopCaseScopeAssignments == 1;
        loopCaseScopeExitProfile &= variables == 3 && reads == 2 && assignments == 2 &&
                                    cases == 1 && caseArms == 2 && loops == 1 && scopes == 1 &&
                                    loopCaseScopeReads == 1 && loopCaseScopeAssignments == 1 &&
                                    breaks + continues == 1;
        loopExitProfile &= variables == 2 && reads == 1 && assignments == 1 && loops == 1 &&
                           breaks == 1 && continues == 1;
        loopScopeProfile &=
            variables == 2 && reads == 1 && assignments == 1 && loops == 1 && scopes == 1;
        loopScopeExitProfile &= variables == 2 && reads == 1 && assignments == 1 && loops == 1 &&
                                scopes == 1 && breaks == 1 && continues == 1;
        nonEventLoopConditionalProfile = loopConditionalProfile;
        nonEventLoopConditionalExitProfile = loopConditionalExitProfile;
        nonEventLoopConditionalScopeProfile = loopConditionalScopeProfile;
        nonEventLoopConditionalScopeExitProfile = loopConditionalScopeExitProfile;
        nonEventLoopCaseProfile = loopCaseProfile;
        nonEventLoopCaseExitProfile = loopCaseExitProfile;
        nonEventLoopCaseScopeProfile = loopCaseScopeProfile;
        nonEventLoopCaseScopeExitProfile = loopCaseScopeExitProfile;
        nonEventLoopExitProfile = loopExitProfile;
        nonEventLoopScopeProfile = loopScopeProfile;
        nonEventLoopScopeExitProfile = loopScopeExitProfile;
        if (!directProfile && !conditionalProfile && !caseProfile && !loopProfile &&
            !loopConditionalProfile && !loopConditionalExitProfile &&
            !loopConditionalScopeProfile && !loopConditionalScopeExitProfile && !loopCaseProfile &&
            !loopCaseExitProfile && !loopCaseScopeProfile && !loopCaseScopeExitProfile &&
            !loopExitProfile && !loopScopeProfile && !loopScopeExitProfile)
          return emitMappedFailure(
              &operation, "NODAL-BACKEND-CAPABILITY-001",
              "generated lexical storage target profile requires either one direct variable/read/"
              "assignment, one direct two-arm conditional, one direct case/default tree, or one "
              "direct runtime loop over root variables with an optional direct two-arm "
              "conditional, conditional-arm exit, conditional-arm lexical scope, "
              "conditional-arm lexical-scope exit, direct "
              "case/default tree, case-arm exit, case-arm lexical scope, case-arm "
              "lexical-scope exit, direct lexical scope, "
              "direct lexical-scope continue/break pair, or one direct continue/break pair");
      }
      bool unsupportedStorage = false;
      bool mismatchedStorage = false;
      unsigned proceduralLoops = 0;
      operation.walk([&](Operation *nested) {
        if (llvm::isa<AnalogLoopOp>(nested)) {
          ++proceduralLoops;
          auto stage = nested->getAttrOfType<StringAttr>("stage");
          auto loopOwner = nested->getAttrOfType<StringAttr>("owner");
          auto procedureOwner = operation.getAttrOfType<StringAttr>("owner");
          if (!eventProcedureProfile) {
            auto boundReads = nested->getAttrOfType<ArrayAttr>("bound_reads");
            bool ownedRootBound = boundReads && boundReads.size() == 1;
            if (ownedRootBound) {
              auto boundIdentity = llvm::dyn_cast<StringAttr>(boundReads[0]);
              ownedRootBound =
                  boundIdentity &&
                  llvm::any_of(operation.getRegion(0).front(), [&](Operation &candidate) {
                    auto variable = llvm::dyn_cast<AnalogVariableOp>(&candidate);
                    auto identity = candidate.getAttrOfType<StringAttr>("identity");
                    return variable && identity && identity == boundIdentity &&
                           llvm::cast<VariableType>(variable.getResult().getType()).getKind() ==
                               "integer";
                  });
            }
            unsigned directConditionals = 0;
            unsigned directConditionalArms = 0;
            unsigned directCases = 0;
            unsigned directCaseArms = 0;
            unsigned directBreaks = 0;
            unsigned directContinues = 0;
            unsigned directScopes = 0;
            nested->walk([&](Operation *bodyOperation) {
              if (bodyOperation == nested)
                return;
              if (llvm::isa<AnalogIfOp>(bodyOperation)) {
                auto conditionalOwner = bodyOperation->getAttrOfType<StringAttr>("owner");
                if ((!nonEventLoopConditionalProfile && !nonEventLoopConditionalExitProfile &&
                     !nonEventLoopConditionalScopeProfile &&
                     !nonEventLoopConditionalScopeExitProfile) ||
                    bodyOperation->getParentOp() != nested || ++directConditionals > 1 ||
                    !conditionalOwner || !procedureOwner ||
                    conditionalOwner.getValue() != procedureOwner.getValue())
                  unsupportedStorage = true;
                return;
              }
              if (llvm::isa<AnalogIfArmOp>(bodyOperation)) {
                auto armOwner = bodyOperation->getAttrOfType<StringAttr>("owner");
                Operation *conditional = bodyOperation->getParentOp();
                if ((!nonEventLoopConditionalProfile && !nonEventLoopConditionalExitProfile &&
                     !nonEventLoopConditionalScopeProfile &&
                     !nonEventLoopConditionalScopeExitProfile) ||
                    !conditional || !llvm::isa<AnalogIfOp>(conditional) ||
                    conditional->getParentOp() != nested || ++directConditionalArms > 2 ||
                    !armOwner || !procedureOwner ||
                    armOwner.getValue() != procedureOwner.getValue())
                  unsupportedStorage = true;
                return;
              }
              if (llvm::isa<AnalogCaseOp>(bodyOperation)) {
                auto caseOwner = bodyOperation->getAttrOfType<StringAttr>("owner");
                auto selectorReads = bodyOperation->getAttrOfType<ArrayAttr>("selector_reads");
                bool ownedRootSelector = selectorReads && selectorReads.size() == 1;
                if (ownedRootSelector) {
                  auto selectorIdentity = llvm::dyn_cast<StringAttr>(selectorReads[0]);
                  ownedRootSelector =
                      selectorIdentity &&
                      llvm::any_of(operation.getRegion(0).front(), [&](Operation &candidate) {
                        auto variable = llvm::dyn_cast<AnalogVariableOp>(&candidate);
                        auto identity = candidate.getAttrOfType<StringAttr>("identity");
                        return variable && identity && identity == selectorIdentity &&
                               llvm::cast<VariableType>(variable.getResult().getType()).getKind() ==
                                   "integer";
                      });
                }
                if ((!nonEventLoopCaseProfile && !nonEventLoopCaseExitProfile &&
                     !nonEventLoopCaseScopeProfile && !nonEventLoopCaseScopeExitProfile) ||
                    bodyOperation->getParentOp() != nested || ++directCases > 1 || !caseOwner ||
                    !procedureOwner || caseOwner.getValue() != procedureOwner.getValue() ||
                    !ownedRootSelector)
                  unsupportedStorage = true;
                return;
              }
              if (llvm::isa<AnalogCaseArmOp>(bodyOperation)) {
                auto armOwner = bodyOperation->getAttrOfType<StringAttr>("owner");
                Operation *caseOperation = bodyOperation->getParentOp();
                if ((!nonEventLoopCaseProfile && !nonEventLoopCaseExitProfile &&
                     !nonEventLoopCaseScopeProfile && !nonEventLoopCaseScopeExitProfile) ||
                    !caseOperation || !llvm::isa<AnalogCaseOp>(caseOperation) ||
                    caseOperation->getParentOp() != nested || ++directCaseArms > 2 || !armOwner ||
                    !procedureOwner || armOwner.getValue() != procedureOwner.getValue())
                  unsupportedStorage = true;
                return;
              }
              if (llvm::isa<AnalogScopeOp>(bodyOperation)) {
                auto scopeOwner = bodyOperation->getAttrOfType<StringAttr>("owner");
                Operation *parent = bodyOperation->getParentOp();
                auto arm = llvm::dyn_cast_or_null<AnalogIfArmOp>(parent);
                const bool directConditionalArmScope = arm && arm->getParentOp() &&
                                                       llvm::isa<AnalogIfOp>(arm->getParentOp()) &&
                                                       arm->getParentOp()->getParentOp() == nested;
                auto caseArm = llvm::dyn_cast_or_null<AnalogCaseArmOp>(parent);
                const bool directCaseArmScope = caseArm && caseArm->getParentOp() &&
                                                llvm::isa<AnalogCaseOp>(caseArm->getParentOp()) &&
                                                caseArm->getParentOp()->getParentOp() == nested;
                const bool acceptedDirectScope =
                    (nonEventLoopScopeProfile || nonEventLoopScopeExitProfile) && parent == nested;
                const bool acceptedConditionalScope = (nonEventLoopConditionalScopeProfile ||
                                                       nonEventLoopConditionalScopeExitProfile) &&
                                                      directConditionalArmScope;
                const bool acceptedCaseScope =
                    (nonEventLoopCaseScopeProfile || nonEventLoopCaseScopeExitProfile) &&
                    directCaseArmScope;
                if ((!acceptedDirectScope && !acceptedConditionalScope && !acceptedCaseScope) ||
                    ++directScopes > 1 || !scopeOwner || !procedureOwner ||
                    scopeOwner.getValue() != procedureOwner.getValue())
                  unsupportedStorage = true;
                return;
              }
              if (llvm::isa<AnalogBreakOp, AnalogContinueOp>(bodyOperation)) {
                auto exitOwner = bodyOperation->getAttrOfType<StringAttr>("owner");
                Operation *parent = bodyOperation->getParentOp();
                auto conditionalArm = llvm::dyn_cast_or_null<AnalogIfArmOp>(parent);
                Operation *conditional = conditionalArm ? conditionalArm->getParentOp() : nullptr;
                auto caseArm = llvm::dyn_cast_or_null<AnalogCaseArmOp>(parent);
                Operation *caseOperation = caseArm ? caseArm->getParentOp() : nullptr;
                const bool acceptedDirectExit = nonEventLoopExitProfile && parent == nested;
                const bool acceptedConditionalExit =
                    nonEventLoopConditionalExitProfile &&
                    llvm::isa<AnalogBreakOp, AnalogContinueOp>(bodyOperation) && conditional &&
                    llvm::isa<AnalogIfOp>(conditional) && conditional->getParentOp() == nested;
                const bool acceptedCaseExit =
                    nonEventLoopCaseExitProfile &&
                    llvm::isa<AnalogBreakOp, AnalogContinueOp>(bodyOperation) && caseOperation &&
                    llvm::isa<AnalogCaseOp>(caseOperation) &&
                    caseOperation->getParentOp() == nested;
                const bool acceptedScopeExit = nonEventLoopScopeExitProfile &&
                                               llvm::isa<AnalogScopeOp>(parent) &&
                                               parent->getParentOp() == nested;
                Operation *conditionalScopeArm =
                    llvm::isa<AnalogScopeOp>(parent) ? parent->getParentOp() : nullptr;
                Operation *scopeConditional =
                    conditionalScopeArm ? conditionalScopeArm->getParentOp() : nullptr;
                const bool acceptedConditionalScopeExit =
                    nonEventLoopConditionalScopeExitProfile &&
                    llvm::isa<AnalogBreakOp, AnalogContinueOp>(bodyOperation) &&
                    conditionalScopeArm && llvm::isa<AnalogIfArmOp>(conditionalScopeArm) &&
                    scopeConditional && llvm::isa<AnalogIfOp>(scopeConditional) &&
                    scopeConditional->getParentOp() == nested;
                Operation *caseScopeArm =
                    llvm::isa<AnalogScopeOp>(parent) ? parent->getParentOp() : nullptr;
                Operation *scopeCase = caseScopeArm ? caseScopeArm->getParentOp() : nullptr;
                const bool acceptedCaseScopeExit =
                    nonEventLoopCaseScopeExitProfile &&
                    llvm::isa<AnalogBreakOp, AnalogContinueOp>(bodyOperation) && caseScopeArm &&
                    llvm::isa<AnalogCaseArmOp>(caseScopeArm) && scopeCase &&
                    llvm::isa<AnalogCaseOp>(scopeCase) && scopeCase->getParentOp() == nested;
                if ((!acceptedDirectExit && !acceptedConditionalExit && !acceptedCaseExit &&
                     !acceptedScopeExit && !acceptedConditionalScopeExit &&
                     !acceptedCaseScopeExit) ||
                    !exitOwner || !procedureOwner ||
                    exitOwner.getValue() != procedureOwner.getValue()) {
                  unsupportedStorage = true;
                  return;
                }
                directBreaks += llvm::isa<AnalogBreakOp>(bodyOperation);
                directContinues += llvm::isa<AnalogContinueOp>(bodyOperation);
                return;
              }
              if (llvm::isa<AnalogVariableReadOp, AnalogAssignOp>(bodyOperation)) {
                if (!nonEventLoopConditionalProfile && !nonEventLoopCaseProfile &&
                    !nonEventLoopScopeProfile && bodyOperation->getParentOp() == nested)
                  return;
                if ((nonEventLoopScopeProfile || nonEventLoopScopeExitProfile) &&
                    llvm::isa<AnalogScopeOp>(bodyOperation->getParentOp()) &&
                    bodyOperation->getParentOp()->getParentOp() == nested)
                  return;
                if ((nonEventLoopConditionalScopeProfile ||
                     nonEventLoopConditionalScopeExitProfile) &&
                    llvm::isa<AnalogScopeOp>(bodyOperation->getParentOp())) {
                  Operation *arm = bodyOperation->getParentOp()->getParentOp();
                  Operation *conditional = arm ? arm->getParentOp() : nullptr;
                  if (arm && llvm::isa<AnalogIfArmOp>(arm) && conditional &&
                      llvm::isa<AnalogIfOp>(conditional) && conditional->getParentOp() == nested)
                    return;
                }
                if ((nonEventLoopCaseScopeProfile || nonEventLoopCaseScopeExitProfile) &&
                    llvm::isa<AnalogScopeOp>(bodyOperation->getParentOp())) {
                  Operation *arm = bodyOperation->getParentOp()->getParentOp();
                  Operation *caseOperation = arm ? arm->getParentOp() : nullptr;
                  if (arm && llvm::isa<AnalogCaseArmOp>(arm) && caseOperation &&
                      llvm::isa<AnalogCaseOp>(caseOperation) &&
                      caseOperation->getParentOp() == nested)
                    return;
                }
                auto arm = llvm::dyn_cast<AnalogIfArmOp>(bodyOperation->getParentOp());
                Operation *conditional = arm ? arm->getParentOp() : nullptr;
                if ((nonEventLoopConditionalProfile || nonEventLoopConditionalExitProfile ||
                     nonEventLoopConditionalScopeProfile ||
                     nonEventLoopConditionalScopeExitProfile) &&
                    conditional && llvm::isa<AnalogIfOp>(conditional) &&
                    conditional->getParentOp() == nested)
                  return;
                auto caseArm = llvm::dyn_cast<AnalogCaseArmOp>(bodyOperation->getParentOp());
                Operation *caseOperation = caseArm ? caseArm->getParentOp() : nullptr;
                if ((nonEventLoopCaseProfile || nonEventLoopCaseExitProfile ||
                     nonEventLoopCaseScopeProfile || nonEventLoopCaseScopeExitProfile) &&
                    caseOperation && llvm::isa<AnalogCaseOp>(caseOperation) &&
                    caseOperation->getParentOp() == nested)
                  return;
              }
              unsupportedStorage = true;
            });
            unsupportedStorage |=
                nested->getParentOp() != &operation || !stage || stage.getValue() != "runtime" ||
                hasAnalogEvents(nested) || !loopOwner || !procedureOwner ||
                loopOwner.getValue() != procedureOwner.getValue() || !ownedRootBound ||
                ((nonEventLoopConditionalProfile || nonEventLoopConditionalExitProfile ||
                  nonEventLoopConditionalScopeProfile || nonEventLoopConditionalScopeExitProfile) &&
                 (directConditionals != 1 || directConditionalArms != 2)) ||
                ((nonEventLoopCaseProfile || nonEventLoopCaseExitProfile ||
                  nonEventLoopCaseScopeProfile || nonEventLoopCaseScopeExitProfile) &&
                 (directCases != 1 || directCaseArms != 2)) ||
                (nonEventLoopExitProfile && (directBreaks != 1 || directContinues != 1)) ||
                (nonEventLoopScopeExitProfile && (directBreaks != 1 || directContinues != 1)) ||
                (nonEventLoopConditionalExitProfile && directBreaks + directContinues != 1) ||
                (nonEventLoopConditionalScopeExitProfile && directBreaks + directContinues != 1) ||
                (nonEventLoopCaseExitProfile && directBreaks + directContinues != 1) ||
                (nonEventLoopCaseScopeExitProfile && directBreaks + directContinues != 1) ||
                ((nonEventLoopScopeProfile || nonEventLoopScopeExitProfile ||
                  nonEventLoopConditionalScopeProfile || nonEventLoopConditionalScopeExitProfile) &&
                 directScopes != 1) ||
                ((nonEventLoopCaseScopeProfile || nonEventLoopCaseScopeExitProfile) &&
                 directScopes != 1);
            return;
          }
          bool nestedLoop = false;
          unsigned directConditionals = 0;
          unsigned directCases = 0;
          unsigned admittedScopes = 0;
          unsigned admittedScopeExits = 0;
          for (Operation *parent = nested->getParentOp(); parent && parent != &operation;
               parent = parent->getParentOp())
            if (llvm::isa<AnalogLoopOp>(parent)) {
              nestedLoop = true;
              break;
            }
          nested->walk([&](Operation *bodyOperation) {
            if (bodyOperation == nested)
              return;
            if (llvm::isa<AnalogIfOp>(bodyOperation)) {
              if (bodyOperation->getParentOp() != nested || ++directConditionals > 1)
                unsupportedStorage = true;
              return;
            }
            if (llvm::isa<AnalogIfArmOp>(bodyOperation)) {
              Operation *conditional = bodyOperation->getParentOp();
              if (!conditional || !llvm::isa<AnalogIfOp>(conditional) ||
                  conditional->getParentOp() != nested)
                unsupportedStorage = true;
              return;
            }
            if (llvm::isa<AnalogCaseOp>(bodyOperation)) {
              if (bodyOperation->getParentOp() != nested || ++directCases > 1)
                unsupportedStorage = true;
              return;
            }
            if (llvm::isa<AnalogCaseArmOp>(bodyOperation)) {
              Operation *caseOperation = bodyOperation->getParentOp();
              if (!caseOperation || !llvm::isa<AnalogCaseOp>(caseOperation) ||
                  caseOperation->getParentOp() != nested)
                unsupportedStorage = true;
              return;
            }
            if (llvm::isa<AnalogScopeOp>(bodyOperation)) {
              auto scopeOwner = bodyOperation->getAttrOfType<StringAttr>("owner");
              auto procedureOwner = operation.getAttrOfType<StringAttr>("owner");
              Operation *parent = bodyOperation->getParentOp();
              auto arm = llvm::dyn_cast_or_null<AnalogIfArmOp>(parent);
              Operation *conditional = arm ? arm->getParentOp() : nullptr;
              auto caseArm = llvm::dyn_cast_or_null<AnalogCaseArmOp>(parent);
              Operation *caseOperation = caseArm ? caseArm->getParentOp() : nullptr;
              const bool directLoopScope = parent == nested;
              const bool directConditionalArmScope = conditional &&
                                                     llvm::isa<AnalogIfOp>(conditional) &&
                                                     conditional->getParentOp() == nested;
              const bool directCaseArmScope = caseOperation &&
                                              llvm::isa<AnalogCaseOp>(caseOperation) &&
                                              caseOperation->getParentOp() == nested;
              if ((!directLoopScope && !directConditionalArmScope && !directCaseArmScope) ||
                  ++admittedScopes > 1 || !scopeOwner || !procedureOwner ||
                  scopeOwner.getValue() != procedureOwner.getValue())
                unsupportedStorage = true;
              return;
            }
            if (llvm::isa<AnalogBreakOp, AnalogContinueOp>(bodyOperation)) {
              auto exitOwner = bodyOperation->getAttrOfType<StringAttr>("owner");
              auto procedureOwner = operation.getAttrOfType<StringAttr>("owner");
              Operation *parent = bodyOperation->getParentOp();
              auto arm = llvm::dyn_cast_or_null<AnalogIfArmOp>(parent);
              Operation *conditional = arm ? arm->getParentOp() : nullptr;
              auto caseArm = llvm::dyn_cast_or_null<AnalogCaseArmOp>(parent);
              Operation *caseOperation = caseArm ? caseArm->getParentOp() : nullptr;
              auto scope = llvm::dyn_cast_or_null<AnalogScopeOp>(parent);
              auto scopeConditionalArm =
                  scope ? llvm::dyn_cast_or_null<AnalogIfArmOp>(scope->getParentOp()) : nullptr;
              Operation *scopeConditional =
                  scopeConditionalArm ? scopeConditionalArm->getParentOp() : nullptr;
              auto scopeCaseArm =
                  scope ? llvm::dyn_cast_or_null<AnalogCaseArmOp>(scope->getParentOp()) : nullptr;
              Operation *scopeCase = scopeCaseArm ? scopeCaseArm->getParentOp() : nullptr;
              const bool directLoopExit = parent == nested;
              const bool directConditionalArmExit = conditional &&
                                                    llvm::isa<AnalogIfOp>(conditional) &&
                                                    conditional->getParentOp() == nested;
              const bool directCaseArmExit = caseOperation &&
                                             llvm::isa<AnalogCaseOp>(caseOperation) &&
                                             caseOperation->getParentOp() == nested;
              const bool directConditionalArmScopeExit =
                  llvm::isa<AnalogBreakOp, AnalogContinueOp>(bodyOperation) && scopeConditional &&
                  llvm::isa<AnalogIfOp>(scopeConditional) &&
                  scopeConditional->getParentOp() == nested;
              const bool directCaseArmScopeExit =
                  llvm::isa<AnalogBreakOp, AnalogContinueOp>(bodyOperation) && scopeCase &&
                  llvm::isa<AnalogCaseOp>(scopeCase) && scopeCase->getParentOp() == nested;
              if ((!directLoopExit && !directConditionalArmExit && !directCaseArmExit &&
                   !directConditionalArmScopeExit && !directCaseArmScopeExit) ||
                  ((directConditionalArmScopeExit || directCaseArmScopeExit) &&
                   ++admittedScopeExits > 1) ||
                  !exitOwner || !procedureOwner ||
                  exitOwner.getValue() != procedureOwner.getValue())
                unsupportedStorage = true;
              return;
            }
            if (llvm::isa<AnalogVariableReadOp, AnalogAssignOp>(bodyOperation)) {
              Operation *parent = bodyOperation->getParentOp();
              if (parent == nested)
                return;
              if (llvm::isa<AnalogScopeOp>(parent)) {
                if (parent->getParentOp() == nested)
                  return;
                auto scopeArm = llvm::dyn_cast_or_null<AnalogIfArmOp>(parent->getParentOp());
                Operation *scopeConditional = scopeArm ? scopeArm->getParentOp() : nullptr;
                if (scopeConditional && llvm::isa<AnalogIfOp>(scopeConditional) &&
                    scopeConditional->getParentOp() == nested)
                  return;
                auto scopeCaseArm = llvm::dyn_cast_or_null<AnalogCaseArmOp>(parent->getParentOp());
                Operation *scopeCase = scopeCaseArm ? scopeCaseArm->getParentOp() : nullptr;
                if (scopeCase && llvm::isa<AnalogCaseOp>(scopeCase) &&
                    scopeCase->getParentOp() == nested)
                  return;
              }
              auto arm = llvm::dyn_cast<AnalogIfArmOp>(parent);
              Operation *conditional = arm ? arm->getParentOp() : nullptr;
              if (conditional && llvm::isa<AnalogIfOp>(conditional) &&
                  conditional->getParentOp() == nested)
                return;
              auto caseArm = llvm::dyn_cast<AnalogCaseArmOp>(parent);
              Operation *caseOperation = caseArm ? caseArm->getParentOp() : nullptr;
              if (caseOperation && llvm::isa<AnalogCaseOp>(caseOperation) &&
                  caseOperation->getParentOp() == nested)
                return;
            }
            unsupportedStorage = true;
          });
          unsupportedStorage |= nestedLoop || !stage || stage.getValue() != "runtime" ||
                                hasAnalogEvents(nested) ||
                                !llvm::isa<AnalogOnOp>(nested->getParentOp());
          return;
        }
        if (!llvm::isa<AnalogVariableOp, AnalogVariableReadOp, AnalogAssignOp>(nested))
          return;
        if (llvm::isa<AnalogVariableOp>(nested))
          for (Operation *parent = nested->getParentOp(); parent && parent != &operation;
               parent = parent->getParentOp())
            if (llvm::isa<AnalogLoopOp>(parent)) {
              unsupportedStorage = true;
              break;
            }
        auto storageOwner = nested->getAttrOfType<StringAttr>("generated_owner");
        auto storageInduction = nested->getAttrOfType<StringAttr>("generated_induction");
        mismatchedStorage |= !storageOwner || !storageInduction ||
                             storageOwner.getValue() != regionId.getValue() ||
                             storageInduction.getValue() != inductionPath.getValue();
      });
      if (unsupportedStorage || proceduralLoops > 1)
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            "generated lexical storage admits one direct runtime loop; event-local storage may "
            "also use one direct conditional and case tree, one direct loop/conditional-arm/"
            "case-arm lexical scope, plus direct loop/conditional-arm/case-arm exits or one "
            "direct conditional-arm/case-arm lexical-scope exit; "
            "non-event "
            "loop storage may use one direct continue/break pair, one direct lexical-scope "
            "continue/break pair, one direct conditional-arm/case-arm lexical-scope exit, or one "
            "direct conditional-arm/case-arm exit");
      if (mismatchedStorage)
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            "generated event-local storage ownership does not match its range");
      eventProcedure = &operation;
      ++procedures;
      continue;
    }
    if (name == "nodal.contribute") {
      auto owner = operation.getAttrOfType<StringAttr>("generated_owner");
      auto generatedInduction = operation.getAttrOfType<StringAttr>("generated_induction");
      if (!owner || !generatedInduction || owner.getValue() != regionId.getValue() ||
          generatedInduction.getValue() != inductionPath.getValue())
        return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                                 "generated contribution ownership does not match its range");
      if (generatedNodeContributionProfile) {
        if (operation.getNumOperands() < 1 ||
            llvm::none_of(generatedBranches,
                          [&](Operation *branch) {
                            return operation.getOperand(0) == branch->getResult(0);
                          }) ||
            !contributedGeneratedBranches.insert(operation.getOperand(0)).second)
          return emitMappedFailure(
              &operation, "NODAL-BACKEND-CAPABILITY-001",
              "generated analog-node contributions must target distinct owned indexed branches");
      }
      ++contributions;
      continue;
    }
    if (name == "nodal.analog_ddt" || name == "nodal.analog_idt") {
      const bool stateful = name == "nodal.analog_idt";
      auto owner = operation.getAttrOfType<StringAttr>("generated_owner");
      auto generatedInduction = operation.getAttrOfType<StringAttr>("generated_induction");
      llvm::DenseSet<Operation *> active;
      if (!owner || !generatedInduction || owner.getValue() != regionId.getValue() ||
          generatedInduction.getValue() != inductionPath.getValue())
        return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                                 stateful ? "generated idt ownership does not match its range"
                                          : "generated ddt ownership does not match its range");
      if (stateful) {
        auto operatorId = operation.getAttrOfType<StringAttr>("operator_id");
        auto stateId = operation.getAttrOfType<StringAttr>("state_id");
        const std::string expectedState =
            operatorId ? (operatorId.getValue() + ".state").str() : std::string();
        if (!operatorId || !stateId || stateId.getValue() != expectedState)
          return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                                   "generated idt state identity is not owned by its operator");
      }
      const bool feedsContribution =
          operation.getNumResults() == 1 &&
          (stateful && generatedNodeContributionProfile
               ? generatedValueFeedsOneContribution(operation.getResult(0), analogBody, active)
               : generatedValueFeedsContribution(operation.getResult(0), analogBody, active));
      if (!feedsContribution)
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            stateful
                ? "generated idt must feed an additive contribution without escaping its range"
                : "generated ddt must feed an additive contribution without escaping its range");
      continue;
    }
    if (name == "nodal.analog_transfer") {
      auto kind = operation.getAttrOfType<StringAttr>("transfer_kind");
      const bool sampled = kind && kind.getValue() == "zi_nd";
      const llvm::StringRef transferName = sampled ? "ziNd" : "laplaceNd";
      auto owner = operation.getAttrOfType<StringAttr>("generated_owner");
      auto generatedInduction = operation.getAttrOfType<StringAttr>("generated_induction");
      auto operatorId = operation.getAttrOfType<StringAttr>("operator_id");
      auto stateId = operation.getAttrOfType<StringAttr>("state_id");
      const std::string expectedState =
          operatorId ? (operatorId.getValue() + ".state").str() : std::string();
      llvm::DenseSet<Operation *> active;
      if (!kind || (kind.getValue() != "laplace_nd" && !sampled))
        return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                                 "generated transfer target profile admits only laplaceNd/ziNd");
      if (!owner || !generatedInduction || owner.getValue() != regionId.getValue() ||
          generatedInduction.getValue() != inductionPath.getValue())
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            (llvm::Twine("generated ") + transferName + " ownership does not match its range")
                .str());
      if (!operatorId || !stateId || stateId.getValue() != expectedState)
        return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                                 (llvm::Twine("generated ") + transferName +
                                  " state identity is not owned by its operator")
                                     .str());
      if (operation.getNumResults() != 1 ||
          !generatedValueFeedsOneContribution(operation.getResult(0), analogBody, active))
        return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                                 (llvm::Twine("generated ") + transferName +
                                  " must feed one additive contribution without escaping its range")
                                     .str());
      auto rendered = renderTransferCall(&operation, state);
      if (failed(rendered) ||
          !state.expressions.try_emplace(operation.getResult(0), *rendered).second)
        return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                                 (llvm::Twine("generated ") + transferName +
                                  " cannot be rendered as one owned expression")
                                     .str());
      continue;
    }
    if (name == "nodal.analog_transition" || name == "nodal.analog_slew" ||
        name == "nodal.analog_absdelay") {
      const bool slew = name == "nodal.analog_slew";
      const llvm::StringRef waveform = slew                              ? "slew"
                                       : name == "nodal.analog_absdelay" ? "absdelay"
                                                                         : "transition";
      auto owner = operation.getAttrOfType<StringAttr>("generated_owner");
      auto generatedInduction = operation.getAttrOfType<StringAttr>("generated_induction");
      auto operatorId = operation.getAttrOfType<StringAttr>("operator_id");
      auto stateId = operation.getAttrOfType<StringAttr>("state_id");
      const std::string expectedState =
          operatorId ? (operatorId.getValue() + ".state").str() : std::string();
      llvm::DenseSet<Operation *> active;
      if (!owner || !generatedInduction || owner.getValue() != regionId.getValue() ||
          generatedInduction.getValue() != inductionPath.getValue())
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            (llvm::Twine("generated ") + waveform + " ownership does not match its range").str());
      if (!operatorId || !stateId || stateId.getValue() != expectedState)
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            (llvm::Twine("generated ") + waveform + " state identity is not owned by its operator")
                .str());
      if (operation.getNumResults() != 1 ||
          !generatedValueFeedsOneContribution(operation.getResult(0), analogBody, active))
        return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                                 (llvm::Twine("generated ") + waveform +
                                  " must feed one additive contribution without escaping its range")
                                     .str());
      auto rendered = renderWaveformCall(&operation, state);
      if (failed(rendered) ||
          !state.expressions.try_emplace(operation.getResult(0), *rendered).second)
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            (llvm::Twine("generated ") + waveform + " cannot be rendered as one owned expression")
                .str());
      continue;
    }
    if (name == "nodal.analog_abstime") {
      auto owner = operation.getAttrOfType<StringAttr>("generated_owner");
      auto generatedInduction = operation.getAttrOfType<StringAttr>("generated_induction");
      llvm::DenseSet<Operation *> active;
      if (!owner || !generatedInduction || owner.getValue() != regionId.getValue() ||
          generatedInduction.getValue() != inductionPath.getValue())
        return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                                 "generated abstime ownership does not match its range");
      if (operation.getNumResults() != 1 ||
          !generatedValueFeedsOneContribution(operation.getResult(0), analogBody, active))
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            "generated abstime must feed one additive contribution without escaping its range");
      if (!state.expressions.try_emplace(operation.getResult(0), "$abstime").second)
        return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                                 "generated abstime cannot be rendered as one owned expression");
      continue;
    }
    if (name == "nodal.analog_bound_step") {
      auto owner = operation.getAttrOfType<StringAttr>("generated_owner");
      auto generatedInduction = operation.getAttrOfType<StringAttr>("generated_induction");
      FailureOr<std::string> operandDimension = failure();
      if (operation.getNumOperands() == 1)
        operandDimension = nodal::getAnalogRealDimension(operation.getOperand(0));
      if (!owner || !generatedInduction || owner.getValue() != regionId.getValue() ||
          generatedInduction.getValue() != inductionPath.getValue())
        return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                                 "generated boundStep ownership does not match its range");
      if (operation.getNumOperands() != 1 || operation.getNumResults() != 0 ||
          failed(operandDimension) || *operandDimension != "time")
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            "generated boundStep requires one time-valued operand and no result");
      ++boundStepEffects;
      continue;
    }
    if (name == "nodal.analog_noise") {
      auto kind = operation.getAttrOfType<StringAttr>("noise_kind");
      auto owner = operation.getAttrOfType<StringAttr>("generated_owner");
      auto generatedInduction = operation.getAttrOfType<StringAttr>("generated_induction");
      auto sourceId = operation.getAttrOfType<StringAttr>("source_id");
      auto noiseOwner = operation.getAttrOfType<StringAttr>("owner");
      auto metadata = operation.getAttrOfType<DictionaryAttr>("metadata");
      auto semanticPath = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
      auto module = operation.getParentOfType<nodal::ModuleOp>();
      auto moduleMetadata =
          module ? module->getAttrOfType<DictionaryAttr>("metadata") : DictionaryAttr();
      auto modulePath =
          moduleMetadata ? moduleMetadata.getAs<StringAttr>("semantic_path") : StringAttr();
      llvm::StringRef expectedOwner = modulePath ? modulePath.getValue()
                                      : module   ? symbolName(module.getOperation())
                                                 : llvm::StringRef();
      llvm::DenseSet<Operation *> active;
      if (!kind || (kind.getValue() != "white" && kind.getValue() != "flicker" &&
                    kind.getValue() != "table"))
        return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                                 "generated noise target profile admits only whiteNoise, "
                                 "flickerNoise, and tableNoise");
      llvm::StringRef noise = kind.getValue() == "white"     ? "whiteNoise"
                              : kind.getValue() == "flicker" ? "flickerNoise"
                                                             : "tableNoise";
      if (!owner || !generatedInduction || owner.getValue() != regionId.getValue() ||
          generatedInduction.getValue() != inductionPath.getValue())
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            (llvm::Twine("generated ") + noise + " ownership does not match its range").str());
      if (!sourceId || !noiseOwner || !semanticPath || expectedOwner.empty() ||
          noiseOwner.getValue() != expectedOwner ||
          sourceId.getValue() != semanticPath.getValue() ||
          !sourceId.getValue().starts_with((expectedOwner + ".").str()))
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            (llvm::Twine("generated ") + noise + " source identity is not owned by its module")
                .str());
      if (operation.getNumResults() != 1 ||
          !generatedValueFeedsOneContribution(operation.getResult(0), analogBody, active))
        return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                                 (llvm::Twine("generated ") + noise +
                                  " must feed one additive contribution without escaping its range")
                                     .str());
      auto rendered = renderNoiseCall(&operation, state);
      if (failed(rendered) ||
          !state.expressions.try_emplace(operation.getResult(0), *rendered).second)
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            (llvm::Twine("generated ") + noise + " cannot be rendered as one owned expression")
                .str());
      continue;
    }
    bool supportedExpression =
        name == "nodal.real_literal" || name == "nodal.analog_integer_literal" ||
        name == "nodal.parameter_ref" || name == "nodal.access" ||
        name == "nodal.terminal_access" || name == "nodal.port_flow_access" ||
        name == "nodal.analog_add" || name == "nodal.analog_sub" || name == "nodal.analog_mul" ||
        name == "nodal.analog_div" || name == "nodal.analog_neg";
    if (generatedNodeContributionProfile && name == "nodal.access") {
      auto metadata = operation.getAttrOfType<DictionaryAttr>("metadata");
      auto owner = metadata ? metadata.getAs<StringAttr>("generated_owner") : StringAttr();
      auto generatedInduction =
          metadata ? metadata.getAs<StringAttr>("generated_induction") : StringAttr();
      if (!owner || !generatedInduction || owner.getValue() != regionId.getValue() ||
          generatedInduction.getValue() != inductionPath.getValue() ||
          operation.getNumOperands() != 1 ||
          llvm::none_of(generatedBranches, [&](Operation *branch) {
            return operation.getOperand(0) == branch->getResult(0);
          }))
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            "generated analog-node access ownership does not match its branch");
      if (operation.getNumResults() != 1 || !operation.getResult(0).hasOneUse())
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            "generated analog-node access requires one owned contribution path");
      OpOperand &use = *operation.getResult(0).getUses().begin();
      Operation *user = use.getOwner();
      const bool directDdt = user->getBlock() == &analogBody && use.getOperandNumber() == 0 &&
                             user->getName().getStringRef() == "nodal.analog_ddt";
      llvm::DenseSet<Operation *> active;
      const bool ownedIdtInput =
          generatedValueFeedsOneIdtInput(operation.getResult(0), analogBody, active);
      if (!directDdt && !ownedIdtInput &&
          !generatedValueFeedsOneContribution(operation.getResult(0), analogBody, active,
                                              operation.getOperand(0)))
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            "generated analog-node access must feed one memoryless contribution on its branch, "
            "direct owned "
            "ddt input, or one memoryless path into an owned idt without escaping its range");
      if (!accessedGeneratedBranches.insert(operation.getOperand(0)).second)
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            "generated analog-node branch requires one access without fan-out");
      ++generatedNodeAccesses;
      generatedNodeOperatorAccesses += static_cast<unsigned>(directDdt || ownedIdtInput);
    }
    if (!supportedExpression)
      return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                               "generated Verilog-A backend profile supports contributions and "
                               "owned ddt/idt/transition/slew/absdelay/abstime/boundStep/"
                               "laplaceNd/ziNd/"
                               "whiteNoise/"
                               "flickerNoise/tableNoise expressions "
                               "only");
  }
  const unsigned profiles = static_cast<unsigned>(contributions != 0) +
                            static_cast<unsigned>(procedures != 0) +
                            static_cast<unsigned>(boundStepEffects != 0);
  if (profiles != 1 || procedures > 1 || boundStepEffects > 1)
    return emitMappedFailure(analog, "NODAL-BACKEND-CAPABILITY-001",
                             "generated Verilog-A range requires either additive contributions or "
                             "one owned event procedure or boundStep effect");
  if (generatedNodeContributionProfile &&
      (contributions != generatedBranches.size() ||
       contributedGeneratedBranches.size() != generatedBranches.size()))
    return emitMappedFailure(
        analog, "NODAL-BACKEND-CAPABILITY-001",
        "generated analog-node contribution profile requires one additive contribution per "
        "owned indexed branch");
  if (generatedNodeOperatorAccesses != 0 &&
      (generatedNodeAccesses != 1 || generatedBranches.size() != 1))
    return emitMappedFailure(
        analog, "NODAL-BACKEND-CAPABILITY-001",
        "generated analog-node operator access profile requires one access on one owned branch");

  output << "  analog begin\n";
  if (parentGenerate) {
    llvm::StringRef parentLabel = parentRegionId.getValue();
    if (size_t separator = parentLabel.rfind('.'); separator != llvm::StringRef::npos)
      parentLabel = parentLabel.drop_front(separator + 1);
    if (!isPortableVerilogIdentifier(parentLabel))
      return emitMappedFailure(parentGenerate, "NODAL-BACKEND-CAPABILITY-001",
                               "nested generated Verilog-A parent label is invalid");
    output << "    for (" << parentInduction.getValue() << " = " << parentLower->text << "; "
           << parentInduction.getValue() << " < " << parentUpper->text << "; "
           << parentInduction.getValue() << " = " << parentInduction.getValue() << " + "
           << parentStep->text << ") begin : " << parentLabel << "\n";
  }
  output << "    for (" << induction.getValue() << " = " << lower->text << "; "
         << induction.getValue() << " < " << upper->text << "; " << induction.getValue() << " = "
         << induction.getValue() << " + " << step->text << ") begin : " << label << "\n";
  if (eventProcedure &&
      failed(prepareGeneratedAnalogEventBackend(eventProcedure, state.eventState, output, 6)))
    return failure();
  if (failed(renderAnalog(analog, state, output, false)))
    return failure();
  output << "    end\n";
  if (parentGenerate)
    output << "    end\n";
  output << "  end\n";
  return success();
}

bool isGeneratedNodeArrayTreeProfile(Operation *generate, unsigned depth = 0) {
  Region &body = generate->getRegion(0);
  if (!llvm::hasSingleElement(body) || body.front().empty())
    return false;
  for (Operation &operation : body.front()) {
    llvm::StringRef name = operation.getName().getStringRef();
    if (name == "nodal.node") {
      if (operation.getNumResults() != 1 || !operation.getResult(0).use_empty())
        return false;
      continue;
    }
    if (name != "nodal.generate" || depth >= 1 ||
        !isGeneratedNodeArrayTreeProfile(&operation, depth + 1))
      return false;
  }
  return true;
}

bool hasGeneratedNodeArray(Operation *generate) {
  return isGeneratedNodeArrayTreeProfile(generate) ||
         isGeneratedNodeContributionProfile(generate) || nestedGeneratedNodeContribution(generate);
}

LogicalResult renderNestedGeneratedNodeContributionArray(Operation *generate,
                                                         llvm::raw_ostream &output) {
  Operation *child = nestedGeneratedNodeContribution(generate);
  if (!child)
    return emitMappedFailure(
        generate, "NODAL-BACKEND-CAPABILITY-001",
        "nested generated analog-node equation requires one direct child equation profile");

  auto resolveLayout = [&](Operation *range) -> FailureOr<GeneratedNodeArrayLayout> {
    if (range->getNumOperands() != 0 && range->getNumOperands() != 3)
      return emitMappedFailure(
          range, "NODAL-BACKEND-CAPABILITY-001",
          "nested generated analog-node arrays require attributes or three SSA bounds");
    const bool compound = range->getNumOperands() == 3;
    auto lower = compound ? generatedTargetBound(range->getOperand(0), range, "lower")
                          : generatedTargetBound(range, "lower");
    auto upper = compound ? generatedTargetBound(range->getOperand(1), range, "upper")
                          : generatedTargetBound(range, "upper");
    auto step = compound ? generatedTargetBound(range->getOperand(2), range, "step")
                         : generatedTargetBound(range, "step");
    if (failed(lower) || failed(upper) || failed(step))
      return failure();
    if (step->range.lower <= 0 || lower->range.upper >= upper->range.lower)
      return emitMappedFailure(
          range, "NODAL-BACKEND-CAPABILITY-001",
          "nested generated analog-node arrays require nonempty positive-step ranges");
    return generatedNodeArrayLayout(*lower, *upper, *step, "");
  };

  auto outerLayout = resolveLayout(generate);
  auto innerLayout = resolveLayout(child);
  if (failed(outerLayout) || failed(innerLayout))
    return failure();
  auto childRegion = child->getAttrOfType<StringAttr>("region_id");
  auto childInduction = child->getAttrOfType<StringAttr>("induction_path");
  if (!childRegion || !childInduction)
    return emitMappedFailure(
        child, "NODAL-BACKEND-CAPABILITY-001",
        "nested generated analog-node array requires canonical child ownership");

  llvm::StringSet<> sourcePaths;
  for (Operation &operation : child->getRegion(0).front()) {
    if (operation.getName().getStringRef() != "nodal.node")
      continue;
    auto nodeName = operation.getAttrOfType<StringAttr>("name");
    auto sourcePath = operation.getAttrOfType<StringAttr>("source_path");
    auto generatedOwner = operation.getAttrOfType<StringAttr>("generated_owner");
    auto generatedInduction = operation.getAttrOfType<StringAttr>("generated_induction");
    auto terminalType = operation.getNumResults() == 1
                            ? llvm::dyn_cast<nodal::TerminalType>(operation.getResult(0).getType())
                            : nodal::TerminalType();
    if (!nodeName || !sourcePath || sourcePath.getValue().trim().empty() ||
        !isPortableVerilogIdentifier(nodeName.getValue()) || !generatedOwner ||
        !generatedInduction || generatedOwner.getValue() != childRegion.getValue() ||
        generatedInduction.getValue() != childInduction.getValue() || !terminalType ||
        terminalType.getDiscipline() != "electrical" ||
        !sourcePaths.insert(sourcePath.getValue()).second)
      return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                               "nested generated analog-node equation requires an exactly-owned "
                               "electrical node with unique source identity");
    const std::string count =
        (llvm::Twine("(") + outerLayout->count + " * " + innerLayout->count + ")").str();
    output << "  electrical " << nodeName.getValue() << "[0:(" << count << " - 1)];\n";
  }
  return success();
}

LogicalResult renderGeneratedNodeArrayTree(Operation *generate, llvm::raw_ostream &output) {
  llvm::StringSet<> sourcePaths;
  llvm::SmallVector<GeneratedNodeArrayLayout, 2> layouts;
  std::function<LogicalResult(Operation *, unsigned)> render =
      [&](Operation *current, unsigned depth) -> LogicalResult {
    if (current->getNumOperands() != 0 && current->getNumOperands() != 3)
      return emitMappedFailure(
          current, "NODAL-BACKEND-CAPABILITY-001",
          "generated analog-node arrays require attributes or three SSA bounds");
    const bool compound = current->getNumOperands() == 3;
    auto lower = compound ? generatedTargetBound(current->getOperand(0), current, "lower")
                          : generatedTargetBound(current, "lower");
    auto upper = compound ? generatedTargetBound(current->getOperand(1), current, "upper")
                          : generatedTargetBound(current, "upper");
    auto step = compound ? generatedTargetBound(current->getOperand(2), current, "step")
                         : generatedTargetBound(current, "step");
    if (failed(lower) || failed(upper) || failed(step))
      return failure();
    if (step->range.lower <= 0 || lower->range.upper >= upper->range.lower)
      return emitMappedFailure(
          current, "NODAL-BACKEND-CAPABILITY-001",
          "generated analog-node arrays require a nonempty positive-step range for every legal "
          "setting");

    auto inductionPath = current->getAttrOfType<StringAttr>("induction_path");
    auto regionId = current->getAttrOfType<StringAttr>("region_id");
    if (!inductionPath || !regionId || depth >= 2)
      return emitMappedFailure(
          current, "NODAL-BACKEND-CAPABILITY-001",
          "generated analog-node array nesting requires canonical ownership and at most two "
          "target-visible dimensions");

    layouts.push_back(generatedNodeArrayLayout(*lower, *upper, *step, ""));
    Region &body = current->getRegion(0);
    for (Operation &operation : body.front()) {
      llvm::StringRef name = operation.getName().getStringRef();
      if (name == "nodal.generate") {
        if (failed(render(&operation, depth + 1))) {
          layouts.pop_back();
          return failure();
        }
        continue;
      }
      if (name != "nodal.node" || operation.getNumResults() != 1) {
        layouts.pop_back();
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            "nested generated analog-node layout admits node declarations only");
      }
      auto nodeName = operation.getAttrOfType<StringAttr>("name");
      auto sourcePath = operation.getAttrOfType<StringAttr>("source_path");
      auto generatedOwner = operation.getAttrOfType<StringAttr>("generated_owner");
      auto generatedInduction = operation.getAttrOfType<StringAttr>("generated_induction");
      auto terminalType = llvm::dyn_cast<nodal::TerminalType>(operation.getResult(0).getType());
      if (!nodeName || !sourcePath || sourcePath.getValue().trim().empty() ||
          !isPortableVerilogIdentifier(nodeName.getValue()) || !generatedOwner ||
          !generatedInduction || generatedOwner.getValue() != regionId.getValue() ||
          generatedInduction.getValue() != inductionPath.getValue() || !terminalType ||
          terminalType.getDiscipline() != "electrical" || !operation.getResult(0).use_empty() ||
          !sourcePaths.insert(sourcePath.getValue()).second) {
        layouts.pop_back();
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            "generated analog-node array requires an unused exactly-owned electrical node with "
            "unique source identity");
      }

      if (layouts.size() == 1) {
        output << "  electrical " << nodeName.getValue() << "[" << layouts.front().lower << ":"
               << layouts.front().upper << "];\n";
        continue;
      }
      std::string count = layouts.front().count;
      for (const GeneratedNodeArrayLayout &layout : llvm::drop_begin(layouts))
        count = (llvm::Twine("(") + count + " * " + layout.count + ")").str();
      output << "  electrical " << nodeName.getValue() << "[0:(" << count << " - 1)];\n";
    }
    layouts.pop_back();
    return success();
  };
  return render(generate, 0);
}

LogicalResult renderGeneratedNodeArray(Operation *generate, llvm::raw_ostream &output) {
  if (isGeneratedNodeArrayTreeProfile(generate))
    return renderGeneratedNodeArrayTree(generate, output);
  if (nestedGeneratedNodeContribution(generate))
    return renderNestedGeneratedNodeContributionArray(generate, output);

  if (generate->getNumOperands() != 0 && generate->getNumOperands() != 3)
    return emitMappedFailure(generate, "NODAL-BACKEND-CAPABILITY-001",
                             "generated analog-node arrays require attributes or three SSA bounds");
  const bool compound = generate->getNumOperands() == 3;
  auto lower = compound ? generatedTargetBound(generate->getOperand(0), generate, "lower")
                        : generatedTargetBound(generate, "lower");
  auto upper = compound ? generatedTargetBound(generate->getOperand(1), generate, "upper")
                        : generatedTargetBound(generate, "upper");
  auto step = compound ? generatedTargetBound(generate->getOperand(2), generate, "step")
                       : generatedTargetBound(generate, "step");
  if (failed(lower) || failed(upper) || failed(step))
    return failure();
  if (step->range.lower <= 0 || lower->range.upper >= upper->range.lower)
    return emitMappedFailure(
        generate, "NODAL-BACKEND-CAPABILITY-001",
        "generated analog-node arrays require a nonempty positive-step range for every legal "
        "setting");

  const GeneratedNodeArrayLayout layout = generatedNodeArrayLayout(*lower, *upper, *step, "");

  auto inductionPath = generate->getAttrOfType<StringAttr>("induction_path");
  auto regionId = generate->getAttrOfType<StringAttr>("region_id");
  Region &body = generate->getRegion(0);
  if (!inductionPath || !regionId || !hasGeneratedNodeArray(generate))
    return emitMappedFailure(
        generate, "NODAL-BACKEND-CAPABILITY-001",
        "generated analog-node arrays require canonical ownership and an admitted direct body");
  llvm::StringSet<> sourcePaths;
  for (Operation &operation : body.front()) {
    if (operation.getName().getStringRef() != "nodal.node")
      continue;
    Operation *node = &operation;
    if (node->getNumResults() != 1)
      return emitMappedFailure(node, "NODAL-BACKEND-CAPABILITY-001",
                               "generated analog-node array requires one terminal per declaration");
    auto name = node->getAttrOfType<StringAttr>("name");
    auto sourcePath = node->getAttrOfType<StringAttr>("source_path");
    auto generatedOwner = node->getAttrOfType<StringAttr>("generated_owner");
    auto generatedInduction = node->getAttrOfType<StringAttr>("generated_induction");
    auto terminalType = llvm::dyn_cast<nodal::TerminalType>(node->getResult(0).getType());
    if (!name || !sourcePath || sourcePath.getValue().trim().empty() ||
        !isPortableVerilogIdentifier(name.getValue()) || !generatedOwner || !generatedInduction ||
        generatedOwner.getValue() != regionId.getValue() ||
        generatedInduction.getValue() != inductionPath.getValue() || !terminalType ||
        terminalType.getDiscipline() != "electrical" ||
        !sourcePaths.insert(sourcePath.getValue()).second)
      return emitMappedFailure(
          node, "NODAL-BACKEND-CAPABILITY-001",
          "generated analog-node array requires an electrical node with exact owner, induction, "
          "and unique source identity");

    output << "  electrical " << name.getValue() << "[" << layout.lower << ":" << layout.upper
           << "];\n";
  }
  return success();
}

LogicalResult renderDefinition(Operation *definition, llvm::raw_ostream &output) {
  ModuleRenderState state;
  llvm::SmallVector<Operation *, 8> parameters;
  llvm::SmallVector<Operation *, 8> ports;
  llvm::SmallVector<Operation *, 8> nodes;
  llvm::SmallVector<Operation *, 4> namedBranches;
  llvm::SmallVector<Operation *, 2> analogs;
  llvm::SmallVector<Operation *, 2> generates;
  if (failed(collectModuleState(definition, state, parameters, ports, nodes, namedBranches, analogs,
                                generates)))
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

  llvm::StringSet<> generatedInductions;
  llvm::StringSet<> generatedLabels;
  for (Operation *generate : generates) {
    llvm::SmallVector<Operation *, 2> targetRanges{generate};
    if (Operation *child = nestedGeneratedNodeContribution(generate))
      targetRanges.push_back(child);
    for (Operation *range : targetRanges) {
      auto induction = range->getAttrOfType<StringAttr>("induction");
      auto regionId = range->getAttrOfType<StringAttr>("region_id");
      if (!induction || !isPortableVerilogIdentifier(induction.getValue()) ||
          !generatedInductions.insert(induction.getValue()).second)
        return emitMappedFailure(range, "NODAL-BACKEND-CAPABILITY-001",
                                 "generated Verilog-A induction identity is invalid or duplicated");
      if (!regionId)
        return emitMappedFailure(range, "NODAL-BACKEND-CAPABILITY-001",
                                 "generated Verilog-A region identity is missing");
      llvm::StringRef label = regionId.getValue();
      if (size_t separator = label.rfind('.'); separator != llvm::StringRef::npos)
        label = label.drop_front(separator + 1);
      if (!isPortableVerilogIdentifier(label) || !generatedLabels.insert(label).second)
        return emitMappedFailure(range, "NODAL-BACKEND-CAPABILITY-001",
                                 "generated Verilog-A label is invalid or duplicated");
      if (!isGeneratedNodeArrayTreeProfile(generate))
        output << "  genvar " << induction.getValue() << ";\n";
    }
  }

  for (Operation *generate : generates)
    if (hasGeneratedNodeArray(generate) && failed(renderGeneratedNodeArray(generate, output)))
      return failure();

  if (failed(renderHierarchy(definition, state, output)))
    return failure();

  if (hasAnalogEvents(definition) &&
      failed(prepareAnalogEventBackend(definition, state.eventState, output)))
    return failure();

  // Reserve every authored identifier before assigning deterministic private names.
  llvm::StringSet<> reservedNames;
  definition->walk([&](Operation *op) {
    for (llvm::StringRef attribute : {llvm::StringRef("name"), llvm::StringRef("sym_name")})
      if (auto value = op->getAttrOfType<StringAttr>(attribute))
        reservedNames.insert(value.getValue());
  });
  unsigned waveformIndex = 0;
  unsigned noiseIndex = 0;
  unsigned transferIndex = 0;
  for (Operation *analog : analogs) {
    for (Operation &op : analog->getRegion(0).front()) {
      if (op.getName().getStringRef() == "nodal.analog_transfer") {
        std::string temporary;
        do {
          temporary = "transfer_" + std::to_string(transferIndex++);
        } while (!reservedNames.insert(temporary).second);
        state.transferNames[op.getResult(0)] = temporary;
        output << "  real " << temporary << ";\n";
        continue;
      }
      if (op.getName().getStringRef() == "nodal.analog_noise") {
        std::string temporary;
        do {
          temporary = "noise_" + std::to_string(noiseIndex++);
        } while (!reservedNames.insert(temporary).second);
        state.noiseNames[op.getResult(0)] = temporary;
        output << "  real " << temporary << ";\n";
        continue;
      }
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
  if (failed(renderAnalogUserFunctions(definition, output)))
    return failure();
  llvm::SmallVector<Operation *> procedures;
  for (Operation *analog : analogs)
    for (Operation &op : analog->getRegion(0).front())
      if (llvm::isa<AnalogProcedureOp>(&op))
        procedures.push_back(&op);
  if (!procedures.empty()) {
    if (procedures.size() != 1)
      return emitMappedFailure(definition, "NODAL-BACKEND-EVENT-001",
                               "one source procedure is required for ordered event updates");
    // Execute the single procedure before continuous reads and filter evaluations.
    // Separate target processes would make sample/hold output depend on scheduler order.
    output << "  analog begin\n";
    if (failed(renderAnalogEventProcedure(procedures.front(), state.eventState, output)))
      return failure();
    for (Operation *analog : analogs)
      if (failed(renderAnalog(analog, state, output, false, true)))
        return failure();
    output << "  end\n";
  } else {
    for (Operation *analog : analogs)
      if (failed(renderAnalog(analog, state, output)))
        return failure();
  }
  for (Operation *generate : generates)
    if (!isGeneratedNodeArrayTreeProfile(generate)) {
      if (Operation *child = nestedGeneratedNodeContribution(generate)) {
        if (failed(renderGeneratedContribution(child, state, output, generate)))
          return failure();
      } else if (failed(renderGeneratedContribution(generate, state, output))) {
        return failure();
      }
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
  value.split(names, ',', -1, true);
  return !names.empty() && llvm::all_of(names, [](llvm::StringRef name) {
    return isPortableVerilogIdentifier(name.trim());
  });
}

class GeneratedBoundExpressionParser {
public:
  GeneratedBoundExpressionParser(llvm::StringRef text, const std::set<std::string> &parameters)
      : text(text), parameters(parameters) {}

  bool parse() { return expression(0) && position == text.size(); }

private:
  bool expression(unsigned depth) {
    if (depth >= 512 || position >= text.size())
      return false;
    if (text[position] == '(') {
      ++position;
      const size_t contents = position;
      if (expression(depth + 1) && consume(' ') && binaryOperator() && consume(' ') &&
          expression(depth + 1) && consume(')'))
        return true;
      position = contents;
      if (consume('-') && expression(depth + 1) && consume(')'))
        return true;
      return false;
    }

    const size_t start = position;
    if (text[position] == '-')
      ++position;
    const size_t digits = position;
    while (position < text.size() && text[position] >= '0' && text[position] <= '9')
      ++position;
    if (position != digits) {
      int64_t ignored = 0;
      auto parsed = std::from_chars(text.begin() + start, text.begin() + position, ignored);
      return parsed.ec == std::errc() && parsed.ptr == text.begin() + position;
    }
    position = start;

    while (position < text.size()) {
      const char character = text[position];
      if ((character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') ||
          (character >= '0' && character <= '9') || character == '_' || character == '$') {
        ++position;
        continue;
      }
      break;
    }
    llvm::StringRef identifier = text.slice(start, position);
    return isPortableVerilogIdentifier(identifier) && parameters.count(identifier.str()) != 0;
  }

  bool consume(char expected) {
    if (position >= text.size() || text[position] != expected)
      return false;
    ++position;
    return true;
  }

  bool binaryOperator() {
    if (position >= text.size() || !llvm::StringRef("+-*/%").contains(text[position]))
      return false;
    ++position;
    return true;
  }

  llvm::StringRef text;
  const std::set<std::string> &parameters;
  size_t position = 0;
};

bool validGeneratedBoundExpression(llvm::StringRef text, const std::set<std::string> &parameters) {
  return GeneratedBoundExpressionParser(text, parameters).parse();
}

} // namespace

LogicalResult verifyBackendOperations(mlir::ModuleOp module, const BackendProfile &profile) {
  if (failed(normalizePotentialFlowAccess(module)))
    return failure();
  if (failed(verifyAnalogQuantityErasure(module)))
    return failure();
  LogicalResult result = success();
  module.walk([&](Operation *operation) {
    if (failed(result) || operation == module.getOperation())
      return;
    llvm::StringRef name = operation->getName().getStringRef();
    if (llvm::is_contained(kSupportedOperations, name) || name == "nodal.analog_held_read")
      return;
    auto procedure = llvm::dyn_cast<nodal::AnalogProcedureOp>(operation);
    if (!procedure)
      procedure = operation->getParentOfType<nodal::AnalogProcedureOp>();
    if (procedure && (hasAnalogEvents(procedure) || procedure->hasAttr("generated_owner"))) {
      if (llvm::isa<nodal::AnalogProcedureOp, nodal::AnalogVariableOp, nodal::AnalogVariableReadOp,
                    nodal::AnalogAssignOp, nodal::AnalogScopeOp, nodal::AnalogIfOp,
                    nodal::AnalogIfArmOp, nodal::AnalogCaseOp, nodal::AnalogCaseArmOp,
                    nodal::AnalogLoopOp, nodal::AnalogBreakOp, nodal::AnalogContinueOp,
                    nodal::AnalogOnOp>(operation) ||
          isAnalogEventExpression(operation))
        return;
    }
    result = emitMappedFailure(operation, "NODAL-BACKEND-CAPABILITY-001",
                               llvm::Twine("operation '") + name +
                                   "' is not yet supported by profile '" + profile.id + "'");
  });
  return result;
}

LogicalResult renderBackendCandidate(llvm::ArrayRef<Operation *> definitions,
                                     const BackendConfiguration &configuration,
                                     llvm::raw_ostream &output) {
  if (failed(verifyRootExport(definitions)))
    return failure();
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
  llvm::StringMap<unsigned> userFunctions;
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
  auto parameterDeclarationName =
      [](llvm::StringRef line) -> std::optional<std::pair<std::string, bool>> {
    llvm::StringRef code = line;
    if (size_t comment = code.find("//"); comment != llvm::StringRef::npos)
      code = code.take_front(comment).rtrim();
    if (!code.ends_with(";"))
      return std::nullopt;
    code = code.drop_back().trim();
    bool fixed = false;
    if (code.consume_front("parameter real ") || code.consume_front("parameter integer ")) {
    } else if (code.consume_front("localparam real ") ||
               code.consume_front("localparam integer ")) {
      fixed = true;
    } else {
      return std::nullopt;
    }
    size_t equals = code.find(" = ");
    llvm::StringRef name =
        equals == llvm::StringRef::npos ? llvm::StringRef() : code.take_front(equals).trim();
    if (!isPortableVerilogIdentifier(name))
      return std::nullopt;
    return std::make_pair(name.str(), fixed);
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
  llvm::StringSet<> eventNames;
  llvm::StringSet<> generatedNames;
  llvm::StringSet<> generatedLabels;
  std::vector<HierarchyModuleSyntax> hierarchyModules;
  std::optional<std::size_t> currentHierarchyModule;
  auto identifierEventDeclaration = [&](llvm::StringRef name) {
    return !name.contains(',') && validIdentifierList(name) && eventNames.insert(name).second;
  };
  auto eventDeclaration = [&](llvm::StringRef line) {
    if ((!line.starts_with("real event_") && !line.starts_with("integer event_") &&
         !line.starts_with("genvar event_")) ||
        !line.ends_with(";"))
      return false;
    auto declaration = line.drop_front(line.find(' ') + 1).drop_back();
    auto parts = declaration.split(" = ");
    if (!identifierEventDeclaration(parts.first))
      return false;
    if (!parts.second.empty()) {
      std::string check = "begin\n" + parts.first.str() + " = " + parts.second.str() + ";\nend\n";
      auto consumed = reparseAnalogEventBlock(check);
      if (failed(consumed) || *consumed != check.size())
        return false;
    }
    return true;
  };

  llvm::SmallVector<llvm::StringRef, 64> lines;
  candidate.split(lines, '\n', -1, true);
  bool insideModule = false;
  bool insideAnalog = false;
  unsigned generatedDepth = 0;
  bool generatedStatement = false;
  bool sawModule = false;
  for (size_t lineIndex = 0; lineIndex < lines.size(); ++lineIndex) {
    llvm::StringRef line = lines[lineIndex].trim();
    if (line.empty() || line.starts_with("/*") || line.starts_with("*") ||
        line.starts_with("`include "))
      continue;
    if (!insideModule && line.starts_with("module ") && line.ends_with(";")) {
      llvm::StringRef declaration = line.drop_front(sizeof("module ") - 1).drop_back();
      size_t open = declaration.find('(');
      llvm::SmallVector<llvm::StringRef, 8> headerPorts;
      if (open != llvm::StringRef::npos) {
        if (!declaration.ends_with(")") ||
            !validIdentifierList(declaration.slice(open + 1, declaration.size() - 1)))
          return failure();
        declaration.slice(open + 1, declaration.size() - 1).split(headerPorts, ',', -1, true);
        declaration = declaration.take_front(open);
      }
      if (!validIdentifierList(declaration))
        return failure();
      HierarchyModuleSyntax hierarchy;
      hierarchy.name = declaration.str();
      for (llvm::StringRef port : headerPorts) {
        hierarchy.ports.insert(port.trim().str());
        hierarchy.nodes.insert(port.trim().str());
      }
      hierarchyModules.push_back(std::move(hierarchy));
      currentHierarchyModule = hierarchyModules.size() - 1;
      realNames.clear();
      assignedRealNames.clear();
      eventNames.clear();
      generatedNames.clear();
      generatedLabels.clear();
      userFunctions.clear();
      insideModule = true;
      sawModule = true;
      continue;
    }
    if (!insideModule)
      return failure();
    if (line.starts_with("analog function ")) {
      if (insideAnalog)
        return failure();
      std::string remaining;
      for (size_t i = lineIndex; i < lines.size(); ++i)
        remaining += lines[i].str() + "\n";
      auto consumed = reparseAnalogUserFunction(remaining, userFunctions);
      if (failed(consumed) || !*consumed)
        return failure();
      auto count = llvm::count(llvm::StringRef(remaining).take_front(*consumed), '\n');
      if (!count)
        return failure();
      lineIndex += count - 1;
      continue;
    }
    if (line == "analog begin") {
      if (insideAnalog)
        return failure();
      insideAnalog = true;
      continue;
    }
    if (line == "end") {
      if (!insideAnalog)
        return failure();
      if (generatedDepth)
        --generatedDepth;
      else
        insideAnalog = false;
      continue;
    }
    if (line == "endmodule") {
      if (insideAnalog || assignedRealNames.size() != realNames.size())
        return failure();
      insideModule = false;
      currentHierarchyModule.reset();
      continue;
    }
    if (insideAnalog) {
      if (line.starts_with("for (")) {
        if (generatedDepth >= 2)
          return failure();
        size_t close = line.find(") begin : ");
        if (close == llvm::StringRef::npos || close + sizeof(") begin : ") - 1 >= line.size())
          return failure();
        llvm::StringRef label = line.drop_front(close + sizeof(") begin : ") - 1).trim();
        if (!isPortableVerilogIdentifier(label) || !generatedLabels.insert(label).second)
          return failure();
        llvm::SmallVector<llvm::StringRef, 3> clauses;
        line.slice(sizeof("for (") - 1, close).split(clauses, ';', -1, true);
        if (clauses.size() != 3)
          return failure();
        auto initial = clauses[0].trim().split(" = ");
        auto condition = clauses[1].trim().split(" < ");
        auto update = clauses[2].trim().split(" = ");
        if (initial.first.empty() || initial.second.empty() || condition.first != initial.first ||
            update.first != initial.first || !generatedNames.contains(initial.first))
          return failure();
        auto parseSigned = [](llvm::StringRef text, int64_t &value) {
          auto result = std::from_chars(text.begin(), text.end(), value);
          return result.ec == std::errc() && result.ptr == text.end();
        };
        auto validBound = [&](llvm::StringRef text) {
          return currentHierarchyModule &&
                 validGeneratedBoundExpression(
                     text, hierarchyModules[*currentHierarchyModule].parameters);
        };
        if (!validBound(initial.second) || !validBound(condition.second))
          return failure();
        int64_t lower = 0, upper = 0;
        if (parseSigned(initial.second, lower) && parseSigned(condition.second, upper) &&
            upper < lower)
          return failure();
        llvm::StringRef updatePrefix = initial.first;
        if (!update.second.consume_front(updatePrefix) || !update.second.consume_front(" + "))
          return failure();
        int64_t step = 0;
        if (!validBound(update.second) || (parseSigned(update.second, step) && step <= 0))
          return failure();
        ++generatedDepth;
        generatedStatement = false;
        continue;
      }
      if (generatedDepth &&
          (line.starts_with("real event_") || line.starts_with("integer event_") ||
           line.starts_with("genvar event_"))) {
        if (generatedStatement || !eventDeclaration(line))
          return failure();
        continue;
      }
      if (line.starts_with("begin : event_")) {
        std::string remaining;
        for (size_t i = lineIndex; i < lines.size(); ++i)
          remaining += lines[i].str() + "\n";
        auto consumed = reparseAnalogEventBlock(remaining);
        if (failed(consumed) || !*consumed)
          return failure();
        size_t count = llvm::count(llvm::StringRef(remaining).take_front(*consumed), '\n');
        if (!count)
          return failure();
        if (generatedDepth)
          generatedStatement = true;
        lineIndex += count - 1;
        continue;
      }
      if (generatedDepth)
        generatedStatement = true;
      if (!line.ends_with(";"))
        return failure();
      if (line.contains("<+") && !line.contains(" = ")) {
        if (!userFunctions.empty()) {
          auto parts = line.drop_back().split("<+");
          if (failed(reparseAnalogUserExpression(parts.first.trim(), userFunctions)) ||
              failed(reparseAnalogUserExpression(parts.second.trim(), userFunctions)))
            return failure();
        }
        continue;
      }
      auto statement = line.drop_back().trim();
      if (validWaveformCall(statement, true))
        continue;
      size_t equals = statement.find(" = ");
      if (equals == llvm::StringRef::npos)
        return failure();
      auto destination = statement.take_front(equals).trim();
      if (!realNames.contains(destination) || !assignedRealNames.insert(destination).second ||
          (!validWaveformCall(statement.drop_front(equals + 3).trim(), false) &&
           failed(
               reparseAnalogNoiseCall(statement.drop_front(equals + 3).trim(), &userFunctions)) &&
           failed(
               reparseAnalogTransferCall(statement.drop_front(equals + 3).trim(), &userFunctions))))
        return failure();
      continue;
    }
    if (line.starts_with("real event_") || line.starts_with("integer event_") ||
        line.starts_with("genvar event_")) {
      if (!eventDeclaration(line))
        return failure();
      continue;
    }
    if (line.starts_with("genvar ") && line.ends_with(";")) {
      auto identifier = line.drop_front(sizeof("genvar ") - 1).drop_back().trim();
      if (!isPortableVerilogIdentifier(identifier) || !generatedNames.insert(identifier).second)
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
    if ((line.starts_with("input ") || line.starts_with("output ") || line.starts_with("inout ")) &&
        line.ends_with(";") &&
        validIdentifierList(line.drop_front(line.find(' ') + 1).drop_back())) {
      llvm::SmallVector<llvm::StringRef, 8> names;
      line.drop_front(line.find(' ') + 1).drop_back().split(names, ',', -1, true);
      for (llvm::StringRef name : names) {
        hierarchyModules[*currentHierarchyModule].ports.insert(name.trim().str());
        hierarchyModules[*currentHierarchyModule].nodes.insert(name.trim().str());
      }
      continue;
    }
    if (line.starts_with("electrical ") && line.ends_with(";") && line.contains('[')) {
      llvm::StringRef declaration = line.drop_front(line.find(' ') + 1).drop_back().trim();
      size_t open = declaration.find('[');
      size_t close = declaration.rfind(']');
      if (open == llvm::StringRef::npos || close != declaration.size() - 1 || open == 0 ||
          !isPortableVerilogIdentifier(declaration.take_front(open)) || !currentHierarchyModule)
        return failure();
      llvm::StringRef bounds = declaration.slice(open + 1, close);
      auto range = bounds.split(':');
      if (range.first.empty() || range.second.empty() || range.second.contains(':') ||
          !validGeneratedBoundExpression(range.first.trim(),
                                         hierarchyModules[*currentHierarchyModule].parameters) ||
          !validGeneratedBoundExpression(range.second.trim(),
                                         hierarchyModules[*currentHierarchyModule].parameters))
        return failure();
      hierarchyModules[*currentHierarchyModule].nodes.insert(declaration.take_front(open).str());
      continue;
    }
    if (line.starts_with("electrical ") && line.ends_with(";") &&
        validIdentifierList(line.drop_front(line.find(' ') + 1).drop_back())) {
      llvm::SmallVector<llvm::StringRef, 8> names;
      line.drop_front(line.find(' ') + 1).drop_back().split(names, ',', -1, true);
      for (llvm::StringRef name : names)
        hierarchyModules[*currentHierarchyModule].nodes.insert(name.trim().str());
      continue;
    }
    if (validParameterDeclaration(line)) {
      auto parameter = parameterDeclarationName(line);
      if (!parameter)
        return failure();
      hierarchyModules[*currentHierarchyModule].parameters.insert(parameter->first);
      if (parameter->second)
        hierarchyModules[*currentHierarchyModule].fixedParameters.insert(parameter->first);
      continue;
    }
    if (auto instance = parseHierarchyInstance(line.str())) {
      hierarchyModules[*currentHierarchyModule].instances.push_back(std::move(*instance));
      continue;
    }
    return failure();
  }
  return sawModule && !insideModule && !insideAnalog && !generatedDepth &&
                 verifyHierarchySyntax(hierarchyModules)
             ? success()
             : failure();
}

} // namespace nodal
