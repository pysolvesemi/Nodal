#include "nodal/Backend/AnalogVerticalSlice.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Operation.h"
#include "mlir/IR/SymbolTable.h"
#include "nodal/Backend/AnalogEventBackend.h"
#include "nodal/Backend/AnalogUserFunctionBackend.h"
#include "nodal/Backend/ShapedValueBackend.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/AnalogEvents.h"
#include "nodal/Dialect/Nodal/AnalogFunctions.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"
#include "nodal/Dialect/Nodal/PotentialFlowAccess.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"
#include "nodal/Dialect/Nodal/TimeWaveform.h"
#include "nodal/Support/AnalogHierarchySyntax.h"
#include "nodal/Support/BackendCapabilityRegistry.h"

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
#include "llvm/Support/Base64.h"
#include "llvm/Support/JSON.h"
#include "llvm/Support/raw_ostream.h"

#include <algorithm>
#include <array>
#include <charconv>
#include <cmath>
#include <functional>
#include <limits>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <system_error>

using namespace mlir;

namespace nodal {
namespace {

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
  if (auto direction = operation->getAttrOfType<StringAttr>("direction"))
    if (direction.getValue() == "input" || direction.getValue() == "output" ||
        direction.getValue() == "inout")
      return direction.getValue().str();
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

struct StaticNodeArrayLayout {
  std::string name;
  std::string extent;
  llvm::SmallVector<std::string> dimensions;
  llvm::SmallVector<ParameterIntegerBounds> bounds;
};

struct ModuleRenderState {
  const AuthoredSourceMap *sourceMap = nullptr;
  llvm::DenseMap<Value, StaticNodeArrayLayout> nodeArrays;
  llvm::SmallVector<std::pair<std::string, std::string>> nodeMapConstants;
  unsigned nodeMapOrdinal = 0;
  llvm::DenseMap<Value, std::string> childArrayActuals;
  llvm::DenseMap<Value, std::string> relationScalarActuals;
  llvm::DenseMap<Operation *, std::string> directRelations;
  llvm::SmallVector<Value> childArrayDeclarations;
  llvm::SmallVector<std::string> childScalarDeclarations;
  bool structuralScope = false;
  llvm::DenseMap<Value, TargetShapeExpression> structuralIndices;
  llvm::StringSet<> visibleTargetNames;
  llvm::DenseMap<Operation *, std::string> targetInstanceNames;
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

// Each marker covers exactly the immediately following physical statement line.
// Base64 keeps paths and semantic identities inert even when they contain comment
// delimiters, newlines, spaces, or non-ASCII UTF-8 characters.
void renderStatementProvenance(Operation *operation, const ModuleRenderState &state,
                               llvm::StringRef indent, llvm::raw_ostream &output) {
  if (!state.sourceMap)
    return;
  const AuthoredSourceSpan *span = state.sourceMap->resolve(operation);
  if (!span)
    return;
  output << indent << "// nodal-source-v1: " << llvm::encodeBase64(span->semanticPath) << "|"
         << llvm::encodeBase64(span->sourcePath) << "|" << span->startLine << "|"
         << span->startColumn << "|" << span->endLine << "|" << span->endColumn << "\n";
}

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
      if (operation->getNumOperands() != 1 ||
          failed(resolveConservativePortIncidence(operation->getOperand(0), operation)))
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
  } else if (name == "nodal.analog_context") {
    auto id = operation->getAttrOfType<StringAttr>("query_kind");
    auto *entry = id ? lookupAnalogContext(id.getValue()) : nullptr;
    if (!entry || operation->getNumOperands() != 0)
      return failure();
    rendered = entry->verilogA.str();
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

FailureOr<StaticNodeArrayLayout> resolveConservativeLayout(Operation *definition, ShapedType shaped,
                                                           llvm::StringRef name) {
  if (failed(inferStaticShapeMaximumCardinality(definition, shaped.getDimensions())))
    return failure();
  StaticNodeArrayLayout result;
  result.name = name.str();
  result.extent = "1";
  llvm::SmallVector<llvm::StringRef> dimensions;
  shaped.getDimensions().split(dimensions, ',', -1, true);
  for (llvm::StringRef token : dimensions) {
    auto dimension = resolveStaticShapeDimension(definition, token);
    if (failed(dimension))
      return failure();
    FailureOr<std::string> text = std::to_string(dimension->literal);
    if (dimension->parameter)
      text = symbolName(dimension->parameter).str();
    else if (dimension->expression)
      text = renderParameterConstantExpression(dimension->expression);
    if (failed(text))
      return failure();
    auto extent = staticTargetArithmetic(result.extent, '*', *text);
    if (failed(extent))
      return failure();
    result.extent = *extent;
    result.dimensions.push_back(*text);
    result.bounds.push_back(dimension->bounds);
  }
  return result;
}
FailureOr<StaticNodeArrayLayout> resolveStaticNodeArray(Operation *operation,
                                                        Operation *definition) {
  auto shaped = operation->getNumResults() == 1
                    ? llvm::dyn_cast<nodal::ShapedType>(operation->getResult(0).getType())
                    : nodal::ShapedType();
  auto terminal =
      shaped ? llvm::dyn_cast<nodal::TerminalType>(shaped.getElementType()) : nodal::TerminalType();
  auto name = operation->getAttrOfType<StringAttr>("name");
  auto sourcePath = operation->getAttrOfType<StringAttr>("source_path");
  auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata");
  auto semanticPath = metadata ? metadata.getAs<StringAttr>("semantic_path") : StringAttr();
  auto storage = metadata ? metadata.getAs<StringAttr>("storage") : StringAttr();
  auto generated = llvm::dyn_cast<GenerateOp>(definition);
  auto module = llvm::dyn_cast<ModuleOp>(definition);
  if (!module)
    module = definition->getParentOfType<ModuleOp>();
  auto ownerMetadata =
      module ? module->getAttrOfType<DictionaryAttr>("metadata") : DictionaryAttr();
  auto ownerPath = ownerMetadata ? ownerMetadata.getAs<StringAttr>("semantic_path") : StringAttr();
  llvm::StringRef owner = ownerPath ? ownerPath.getValue()
                          : module  ? symbolName(module)
                                    : llvm::StringRef();
  if (!module || (generated && !llvm::isa<NodeArrayOp>(operation)) ||
      operation->getParentOp() != definition || operation->getNumOperands() || !terminal ||
      terminal.getDiscipline() != "electrical" || !name ||
      !isPortableVerilogIdentifier(name.getValue()) || !sourcePath ||
      sourcePath.getValue() != (owner + "." + name.getValue()).str() || !semanticPath ||
      semanticPath.getValue() != sourcePath.getValue() || !storage ||
      storage.getValue() != "structural")
    return failure();
  if (failed(resolveConservativeShape(operation->getResult(0), operation)))
    return failure();
  // The generated structural carrier has the same checked signed32 profile as
  // its map stages. Module-owned direct layouts retain their signed64 profile.
  if (generated && failed(resolveTargetShapedLayout(module, shaped.getDimensions())))
    return failure();
  return resolveConservativeLayout(module, shaped, name.getValue());
}

void renderConservativeArrayDeclaration(const StaticNodeArrayLayout &layout,
                                        llvm::raw_ostream &output, llvm::StringRef direction = {}) {
  int64_t extent = 0;
  const std::string upper = !llvm::StringRef(layout.extent).getAsInteger(10, extent)
                                ? std::to_string(extent - 1)
                                : "(" + layout.extent + " - 1)";
  if (!direction.empty())
    output << "  " << direction << " [0:" << upper << "] " << layout.name << ";\n";
  output << "  electrical [0:" << upper << "] " << layout.name << ";\n";
}

FailureOr<std::string> renderStaticNodeSelection(Operation *operation, Operation *definition,
                                                 ModuleRenderState &state) {
  if (operation->getParentOp() != definition || operation->getNumOperands() < 2 ||
      operation->getNumResults() != 1)
    return failure();
  auto terminal = llvm::dyn_cast<nodal::TerminalType>(operation->getResult(0).getType());
  auto array = state.nodeArrays.find(operation->getOperand(0));
  if (!terminal || terminal.getDiscipline() != "electrical" || array == state.nodeArrays.end() ||
      operation->getNumOperands() - 1 != array->second.dimensions.size())
    return failure();
  std::string ordinal = "0";
  for (size_t axis = 0; axis < array->second.dimensions.size(); ++axis) {
    Value index = operation->getOperand(axis + 1);
    auto bounds = inferStaticShapeIndexBounds(definition, index);
    if (failed(bounds) || bounds->lower < 0 || bounds->upper >= array->second.bounds[axis].lower)
      return failure();
    FailureOr<std::string> text = failure();
    Operation *producer = index.getDefiningOp();
    if (producer && producer->getName().getStringRef() == "nodal.constant")
      text = std::to_string(bounds->lower);
    else
      text = renderParameterConstantExpression(index);
    if (failed(text))
      return failure();
    auto product = staticTargetArithmetic(ordinal, '*', array->second.dimensions[axis]);
    if (failed(product))
      return failure();
    auto sum = staticTargetArithmetic(*product, '+', *text);
    if (failed(sum))
      return failure();
    ordinal = *sum;
  }
  return array->second.name + "[" + ordinal + "]";
}

FailureOr<TargetShapeExpression> renderScopedShapeIndex(ModuleRenderState &state, Value index,
                                                        Operation *use) {
  auto proof = resolveShapeIndexProof(index, use);
  if (failed(proof))
    return failure();
  if (proof->identity.kind == StaticShapeIndexIdentity::Induction) {
    auto found = state.structuralIndices.find(index);
    if (found == state.structuralIndices.end())
      return failure();
    return found->second;
  }
  if (proof->identity.kind == StaticShapeIndexIdentity::Literal) {
    const int64_t value = proof->identity.literal;
    if (value < std::numeric_limits<int32_t>::min() || value > std::numeric_limits<int32_t>::max())
      return failure();
    return TargetShapeExpression{std::to_string(value), {value, value}};
  }
  if (proof->identity.kind == StaticShapeIndexIdentity::Expression &&
      index.getDefiningOp() && isa<GenerateOp>(index.getDefiningOp()->getParentOp())) {
    unsigned remaining = 16384;
    auto facts = resolveStructuralIntegerExpression(index, use, remaining);
    if (failed(facts) || !facts->expression ||
        failed(verifyTargetStructuralIntegerExpression(*facts->expression)))
      return failure();
    SmallVector<std::pair<Value, std::string>> bindings;
    SmallVector<std::shared_ptr<const InstantiatedIntegerNode>> pending{facts->expression->root};
    llvm::SmallPtrSet<const InstantiatedIntegerNode *, 16> visited;
    llvm::DenseSet<Value> bound;
    while (!pending.empty()) {
      auto node = pending.pop_back_val();
      if (!remaining-- || !node)
        return failure();
      if (!visited.insert(node.get()).second)
        continue;
      if (node->kind == InstantiatedIntegerNode::Kind::OpaqueValue &&
          bound.insert(node->identity).second) {
        auto spelling = state.structuralIndices.find(node->identity);
        if (spelling == state.structuralIndices.end())
          return failure();
        bindings.emplace_back(node->identity, spelling->second.text);
      }
      for (const auto &operand : llvm::reverse(node->operands))
        pending.push_back(operand);
    }
    std::string rendered;
    if (renderStructuralIntegerExpression(*facts->expression, use, bindings,
                                          rendered, remaining) !=
        IntegerExpressionEmissionStatus::Proved)
      return failure();
    return TargetShapeExpression{std::move(rendered), facts->expression->bounds};
  }
  return renderShapeStaticIndex(use->getParentOfType<nodal::ModuleOp>(), index);
}

FailureOr<std::string> renderConservativeNodeSelection(Operation *operation, Operation *definition,
                                                       ModuleRenderState &state) {
  if (operation->getNumResults() != 1 || operation->getParentOp() != definition)
    return failure();
  auto selection = resolveConservativeSelection(operation->getResult(0), operation);
  if (failed(selection))
    return failure();
  auto array = state.nodeArrays.find(selection->projection.root);
  if (array == state.nodeArrays.end())
    return failure();
  // Keep the published direct signed64 spelling and capability. A composed map
  // proven fully concrete also needs no target Integer intermediate at all.
  if (!state.structuralScope && operation->getOperand(0) == selection->projection.root)
    return renderStaticNodeSelection(operation, definition, state);
  if (selection->projection.literalRootFlatOffset)
    return array->second.name + "[" + std::to_string(*selection->projection.literalRootFlatOffset) +
           "]";
  unsigned ordinal = state.nodeMapOrdinal++;
  auto program = renderShapedProjectionProgram(
      selection->projection, "0",
      [&](unsigned stage) {
        std::string base = "node_map_" + array->second.name + "_" + std::to_string(ordinal) + "_" +
                           std::to_string(stage);
        std::string name = base;
        unsigned collision = 0;
        while (!state.eventState.reserved.insert(name).second)
          name = base + "_" + std::to_string(++collision);
        return name;
      },
      [&](Value index, Operation *use) -> FailureOr<TargetShapeExpression> {
        return renderScopedShapeIndex(state, index, use);
      });
  if (failed(program))
    return failure();
  for (const auto &step : program->steps)
    state.nodeMapConstants.emplace_back(step.destination, step.expression);
  return array->second.name + "[" + program->result + "]";
}

bool requiresStructuralGenerate(Operation *generate) {
  if (generate->getNumRegions() != 1 || !llvm::hasSingleElement(generate->getRegion(0)))
    return false;
  Block &body = generate->getRegion(0).front();
  if (body.getNumArguments() != 0)
    return true;
  for (Operation &operation : body) {
    if (llvm::isa<NodeArrayOp, InstanceOp, ConnectOp, ConnectArrayOp, ShapeIndexOp, ShapeViewOp,
                  ShapeSliceOp>(operation) ||
        (llvm::isa<GenerateOp>(operation) && requiresStructuralGenerate(&operation)))
      return true;
    if (llvm::isa<AnalogOp>(operation) && llvm::hasSingleElement(operation.getRegion(0)))
      for (Operation &statement : operation.getRegion(0).front())
        if (hasPersistentShapedInitializer(&statement))
          return true;
  }
  return false;
}

LogicalResult prepareConservativeRelationRoots(Operation *scope, ModuleRenderState &state);
void renderConservativeRootDeclarations(ModuleRenderState &state, llvm::raw_ostream &output);

LogicalResult collectModuleState(Operation *definition, ModuleRenderState &state,
                                 llvm::SmallVectorImpl<Operation *> &parameters,
                                 llvm::SmallVectorImpl<Operation *> &ports,
                                 llvm::SmallVectorImpl<Operation *> &nodes,
                                 llvm::SmallVectorImpl<Operation *> &nodeArrays,
                                 llvm::SmallVectorImpl<Operation *> &namedBranches,
                                 llvm::SmallVectorImpl<Operation *> &analogs,
                                 llvm::SmallVectorImpl<Operation *> &generates) {
  Region &region = definition->getRegion(0);
  if (!llvm::hasSingleElement(region))
    return failure();
  definition->walk([&](Operation *operation) {
    for (auto key : {"name", "sym_name", "induction"})
      if (auto name = operation->getAttrOfType<StringAttr>(key))
        state.eventState.reserved.insert(name.getValue());
  });
  // Establish local physical roots before child-port roots and projected uses.
  for (Operation &operation : region.front())
    if (llvm::isa<NodeArrayOp, TerminalArrayOp>(operation)) {
      auto layout = resolveStaticNodeArray(&operation, definition);
      if (failed(layout))
        return emitMappedFailure(&operation, "NODAL-BACKEND-SHAPE-001",
                                 "node array requires owned bounded electrical dimensions");
      state.nodeArrays.try_emplace(operation.getResult(0), std::move(*layout));
    }
  if (failed(prepareConservativeRelationRoots(definition, state)))
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
    } else if (name == "nodal.node_array" || name == "nodal.terminal_array") {
      nodeArrays.push_back(&operation);
      if (name == "nodal.terminal_array")
        ports.push_back(&operation);
    } else if (llvm::isa<ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(operation)) {
      auto reference = operation.getNumResults() == 1
                           ? resolveConservativeShape(operation.getResult(0), &operation)
                           : FailureOr<ConservativeShape>(failure());
      if (failed(reference))
        return emitMappedFailure(&operation, "NODAL-BACKEND-SHAPE-002",
                                 "structural view requires a verified owned node array map");
      if (llvm::isa<TerminalType>(operation.getResult(0).getType()) &&
          !state.terminals.count(operation.getResult(0))) {
        auto endpoint = renderConservativeNodeSelection(&operation, definition, state);
        if (failed(endpoint))
          return emitMappedFailure(&operation, "NODAL-BACKEND-SHAPE-002",
                                   "node selection cannot be rendered within the static target "
                                   "integer map profile");
        state.terminals.try_emplace(operation.getResult(0), *endpoint);
      }
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
  llvm::DenseSet<Operation *> visitedDeclarations;
  for (const auto *group : {&parameters, &ports, &nodes, &nodeArrays, &namedBranches}) {
    for (Operation *declaration : *group) {
      if (!visitedDeclarations.insert(declaration).second)
        continue;
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
  for (const auto &name : declarations)
    state.visibleTargetNames.insert(name.getKey());
  for (Operation &operation : region.front())
    if (llvm::isa<InstanceOp, AnalogUserFunctionOp>(operation))
      state.visibleTargetNames.insert(symbolName(&operation));
  bool validGeneratedNames = true;
  for (Operation *generate : generates) {
    if (requiresStructuralGenerate(generate))
      continue;
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
  llvm::sort(nodeArrays, [](Operation *lhs, Operation *rhs) {
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

bool requiresConservativeAdapter(const StaticShapeProjection &projection) {
  return !projection.wholeRoot && !(projection.contiguousLiteralRegion &&
                                    projection.literalFirstOffset && projection.literalLastOffset);
}

std::string reserveStructuralName(ModuleRenderState &state, llvm::StringRef base) {
  std::string name = base.str();
  unsigned collision = 0;
  while (!state.eventState.reserved.insert(name).second)
    name = (base + "_" + std::to_string(++collision)).str();
  return name;
}

LogicalResult prepareConservativeRelationRoots(Operation *scope, ModuleRenderState &state) {
  auto topology = resolveConservativeStructuralScope(scope);
  auto module = llvm::dyn_cast<ModuleOp>(scope);
  if (!module)
    module = scope->getParentOfType<ModuleOp>();
  if (failed(topology) || !module)
    return failure();
  llvm::DenseMap<Value, llvm::SmallVector<const ConservativeRelation *, 2>> incident;
  for (const auto &relation : topology->relations) {
    incident[relation.left.shape.projection.root].push_back(&relation);
    if (relation.right.shape.projection.root != relation.left.shape.projection.root)
      incident[relation.right.shape.projection.root].push_back(&relation);
  }
  for (Operation &operation : scope->getRegion(0).front()) {
    if (!llvm::isa<InstanceTerminalArrayOp>(operation))
      continue;
    auto endpoint = resolveConservativeEndpoint(operation.getResult(0), &operation);
    if (failed(endpoint) || !endpoint->instance || !endpoint->port)
      return failure();
    Value root = endpoint->shape.projection.root;
    if (state.childArrayActuals.count(root))
      continue;
    auto shaped = llvm::dyn_cast<ShapedType>(root.getType());
    auto port = endpoint->port->getAttrOfType<StringAttr>("name");
    if (!shaped || !port)
      return failure();
    std::string actual;
    // A direct full-root substitution is the identity flat map, including
    // future scalar consumers. A part-select shortcut is only used when the
    // full child root has no other projected/consumer use at all.
    for (const auto *occurrence : incident[root]) {
      const auto &relation = *occurrence;
      const ConservativeEndpoint *child = nullptr, *other = nullptr;
      if (relation.left.shape.projection.root == root) {
        child = &relation.left;
        other = &relation.right;
      } else if (relation.right.shape.projection.root == root) {
        child = &relation.right;
        other = &relation.left;
      }
      if (!child || !child->shape.projection.wholeRoot || other->instance ||
          !llvm::isa<ShapedType>(other->value.getType()))
        continue;
      const auto &projection = other->shape.projection;
      auto local = state.nodeArrays.find(projection.root);
      if (local == state.nodeArrays.end())
        continue;
      if (projection.wholeRoot)
        actual = local->second.name;
      else if (child->value == root && root.hasOneUse() && projection.contiguousLiteralRegion &&
               projection.literalFirstOffset && projection.literalLastOffset)
        actual = local->second.name + "[" + std::to_string(*projection.literalFirstOffset) + ":" +
                 std::to_string(*projection.literalLastOffset) + "]";
      if (!actual.empty()) {
        state.directRelations[relation.operation] = actual;
        break;
      }
    }
    bool declaration = actual.empty();
    if (declaration)
      actual = reserveStructuralName(
          state,
          ("binding_" + symbolName(endpoint->instance) + "_" + port.getValue() + "_bus").str());
    auto layout = resolveConservativeLayout(module, shaped, actual);
    if (failed(layout))
      return failure();
    state.nodeArrays[root] = std::move(*layout);
    state.childArrayActuals[root] = actual;
    if (declaration)
      state.childArrayDeclarations.push_back(root);
  }
  for (const auto &relation : topology->relations)
    for (const auto *endpoint : {&relation.left, &relation.right}) {
      Value root = endpoint->shape.projection.root;
      if (!endpoint->instance || !llvm::isa<TerminalType>(root.getType()) ||
          state.relationScalarActuals.count(root))
        continue;
      auto port = endpoint->port->getAttrOfType<StringAttr>("name");
      if (!port)
        return failure();
      const auto &other = endpoint == &relation.left ? relation.right : relation.left;
      if (root.hasOneUse() && !other.instance &&
          isNodeArraySelection(other.value.getDefiningOp())) {
        auto actual = renderConservativeNodeSelection(
            other.value.getDefiningOp(), other.value.getDefiningOp()->getParentOp(), state);
        if (failed(actual))
          return failure();
        state.terminals[other.value] = *actual;
        state.relationScalarActuals[root] = *actual;
        state.terminals[root] = *actual;
        state.directRelations[relation.operation] = *actual;
      } else {
        auto actual = reserveStructuralName(
            state,
            ("binding_" + symbolName(endpoint->instance) + "_" + port.getValue() + "_net").str());
        state.relationScalarActuals[root] = actual;
        state.terminals[root] = actual;
        state.childScalarDeclarations.push_back(actual);
      }
    }
  return success();
}

void renderConservativeRootDeclarations(ModuleRenderState &state, llvm::raw_ostream &output) {
  for (Value root : state.childArrayDeclarations) {
    const auto &layout = state.nodeArrays.find(root)->second;
    output << "  electrical [0:(" << layout.extent << " - 1)] " << layout.name << ";\n";
  }
  for (const auto &name : state.childScalarDeclarations)
    output << "  electrical " << name << ";\n";
}

std::string conservativeSourcePath(Operation *operation) {
  if (!operation)
    return {};
  if (auto path = operation->getAttrOfType<StringAttr>("source_path"))
    return path.getValue().str();
  if (auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata"))
    if (auto path = metadata.getAs<StringAttr>("semantic_path"))
      return path.getValue().str();
  return {};
}

llvm::json::Array conservativeMapStages(const ShapedProjectionProgram *program) {
  llvm::json::Array stages;
  if (program)
    for (const auto &stage : program->steps)
      stages.push_back(llvm::json::Object{{"name", stage.destination},
                                          {"expression", stage.expression},
                                          {"source", conservativeSourcePath(stage.projection)}});
  return stages;
}
llvm::json::Array conservativeMapProjections(const ConservativeEndpoint &endpoint) {
  llvm::json::Array projections;
  for (Operation *projection : endpoint.shape.projection.projections)
    projections.push_back(llvm::json::Object{{"operation", projection->getName().getStringRef()},
                                             {"source", conservativeSourcePath(projection)}});
  return projections;
}

std::string conservativeSourceLocation(Operation *operation) {
  // Parser locations can name a different transport temporary on every run.
  // Only retained authored source-map fields belong in deterministic target text.
  std::string location = "unknown";
  auto rootModule = operation->getParentOfType<mlir::ModuleOp>();
  auto sourceMap =
      rootModule ? rootModule->getAttrOfType<ArrayAttr>("nodal.bridge.source_map") : ArrayAttr();
  if (sourceMap)
    for (Attribute attribute : sourceMap) {
      auto entry = llvm::dyn_cast<DictionaryAttr>(attribute);
      auto semantic = entry ? entry.getAs<StringAttr>("semantic_path") : StringAttr();
      if (!semantic || semantic.getValue() != conservativeSourcePath(operation))
        continue;
      auto path = entry.getAs<StringAttr>("source_path");
      auto line = entry.getAs<IntegerAttr>("source_line");
      auto column = entry.getAs<IntegerAttr>("source_column");
      auto endLine = entry.getAs<IntegerAttr>("source_end_line");
      auto endColumn = entry.getAs<IntegerAttr>("source_end_column");
      if (path && !path.getValue().empty() && line && line.getInt() > 0 && column &&
          column.getInt() > 0)
        location = (path.getValue() + ":" + llvm::Twine(line.getInt()) + ":" +
                    llvm::Twine(column.getInt()) + "-" +
                    llvm::Twine(endLine ? endLine.getInt() : line.getInt()) + ":" +
                    llvm::Twine(endColumn ? endColumn.getInt() : column.getInt()))
                       .str();
      break;
    }
  return location;
}

void renderConservativeRelationMap(const ConservativeRelation &relation, llvm::StringRef leftActual,
                                   llvm::StringRef rightActual, llvm::StringRef helper,
                                   llvm::StringRef scope,
                                   const ShapedProjectionProgram *leftProgram,
                                   const ShapedProjectionProgram *rightProgram,
                                   ModuleRenderState &state, llvm::raw_ostream &output) {
  const std::string location = conservativeSourceLocation(relation.operation);
  auto describe = [&](const ConservativeEndpoint &endpoint, llvm::StringRef actual,
                      const ShapedProjectionProgram *program) {
    auto shaped = llvm::dyn_cast<ShapedType>(endpoint.value.getType());
    auto rootShape = llvm::dyn_cast<ShapedType>(endpoint.shape.projection.root.getType());
    auto rootMetadata = endpoint.shape.declaration->getAttrOfType<DictionaryAttr>("metadata");
    auto rootPath = rootMetadata ? rootMetadata.getAs<StringAttr>("semantic_path") : StringAttr();
    auto incidence = endpoint.shape.declaration->getAttrOfType<StringAttr>("flow_orientation");
    llvm::json::Object result{
        {"root_reference", rootPath ? rootPath.getValue() : llvm::StringRef()},
        {"root_shape", rootShape ? rootShape.getDimensions() : llvm::StringRef()},
        {"flow_orientation", incidence ? incidence.getValue() : llvm::StringRef("local")},
        {"root", conservativeSourcePath(endpoint.shape.declaration)},
        {"reference", conservativeSourcePath(endpoint.value.getDefiningOp())},
        {"instance", endpoint.instance ? symbolName(endpoint.instance) : llvm::StringRef()},
        {"instance_source", conservativeSourcePath(endpoint.instance)},
        {"formal", conservativeSourcePath(endpoint.port)},
        {"effective_shape", shaped ? shaped.getDimensions() : llvm::StringRef()},
        {"projections", conservativeMapProjections(endpoint)},
        {"target_actual", actual},
        {"stages", conservativeMapStages(program)}};
    if (endpoint.instance) {
      auto target = state.targetInstanceNames.find(endpoint.instance);
      if (target != state.targetInstanceNames.end() &&
          target->second != symbolName(endpoint.instance))
        result["target_instance"] = target->second;
    }
    return result;
  };
  const bool childRight = relation.right.instance != nullptr;
  const auto &child = childRight ? relation.right : relation.left;
  const auto &local = childRight ? relation.left : relation.right;
  auto shaped = llvm::dyn_cast<ShapedType>(relation.left.value.getType());
  auto actual = childRight ? rightActual : leftActual;
  if (child.instance && state.childArrayActuals.count(child.shape.projection.root))
    actual = state.childArrayActuals.find(child.shape.projection.root)->second;
  llvm::json::Object record{
      {"binding", conservativeSourcePath(relation.operation)},
      {"source_location", location},
      {"instance", child.instance ? symbolName(child.instance) : llvm::StringRef()},
      {"instance_source", conservativeSourcePath(child.instance)},
      {"formal", conservativeSourcePath(child.port)},
      {"effective_shape", shaped ? shaped.getDimensions() : llvm::StringRef()},
      {"root", conservativeSourcePath(local.shape.declaration)},
      {"projections", conservativeMapProjections(local)},
      {"flow_orientation", child.instance ? "out_of_component" : "local"},
      {"target_actual", actual},
      {"alias_helper", helper},
      {"generated_scope", scope},
      {"stages", conservativeMapStages(childRight ? leftProgram : rightProgram)},
      {"left", describe(relation.left, leftActual, leftProgram)},
      {"right", describe(relation.right, rightActual, rightProgram)}};
  if (auto generated = llvm::dyn_cast<GenerateOp>(relation.operation->getParentOp()))
    record["source_scope"] = generated->getAttrOfType<StringAttr>("region_id").getValue();
  if (child.instance) {
    auto target = state.targetInstanceNames.find(child.instance);
    if (target != state.targetInstanceNames.end() && target->second != symbolName(child.instance))
      record["target_instance"] = target->second;
  }
  // JSON escapes source controls; a line comment makes delimiters harmless.
  output << "  // nodal.array_binding " << llvm::json::Value(std::move(record)) << "\n";
}

LogicalResult renderConservativeRelation(const ConservativeRelation &relation, unsigned ordinal,
                                         ModuleRenderState &state, llvm::StringRef aliasHelper,
                                         llvm::raw_ostream &output) {
  if (auto direct = state.directRelations.find(relation.operation);
      direct != state.directRelations.end()) {
    renderConservativeRelationMap(relation, direct->second, direct->second, "", "", nullptr,
                                  nullptr, state, output);
    return success();
  }
  if (aliasHelper.empty())
    return failure();
  auto shape = llvm::dyn_cast<ShapedType>(relation.left.value.getType());
  auto module = relation.operation->getParentOfType<ModuleOp>();
  auto layout = shape ? resolveTargetShapedLayout(module, shape.getDimensions())
                      : FailureOr<TargetShapedLayout>(TargetShapedLayout{{}, {"1", {1, 1}}});
  if (failed(layout))
    return failure();
  std::string prefix = "relation_" + std::to_string(ordinal);
  // Keep established whole-child adapter names when the actual root identity
  // gives that unique namespace; partial and symmetric relations use ordinal names.
  if (!relation.left.instance && relation.right.instance &&
      relation.right.shape.projection.wholeRoot) {
    auto port = relation.right.port->getAttrOfType<StringAttr>("name");
    if (port)
      prefix = ("binding_" + symbolName(relation.right.instance) + "_" + port.getValue()).str();
  }
  const std::string lane = shape ? reserveStructuralName(state, prefix + "_lane") : "0";
  const std::string scope = shape ? reserveStructuralName(state, prefix + "_map") : "";
  ShapedProjectionProgram programs[2];
  std::string actuals[2];
  unsigned side = 0;
  for (const auto *endpoint : {&relation.left, &relation.right}) {
    const auto &projection = endpoint->shape.projection;
    if (llvm::isa<ShapedType>(projection.root.getType())) {
      auto root = state.nodeArrays.find(projection.root);
      if (root == state.nodeArrays.end())
        return failure();
      auto program = renderShapedProjectionProgram(
          projection, lane,
          [&](unsigned stage) {
            return reserveStructuralName(state, prefix + (side == 0 ? "_stage_" : "_right_stage_") +
                                                    std::to_string(stage));
          },
          [&](Value index, Operation *use) { return renderScopedShapeIndex(state, index, use); });
      if (failed(program) || program->root != projection.root)
        return failure();
      programs[side] = std::move(*program);
      actuals[side] = root->second.name + "[" + programs[side].result + "]";
    } else {
      auto terminal = state.terminals.find(endpoint->value);
      if (terminal == state.terminals.end())
        return failure();
      actuals[side] = terminal->second;
    }
    ++side;
  }
  renderConservativeRelationMap(relation, actuals[0], actuals[1], aliasHelper, scope, &programs[0],
                                &programs[1], state, output);
  if (shape) {
    output << "  genvar " << lane << ";\n";
    if (!state.structuralScope)
      output << "  generate\n";
    output << "    for (" << lane << " = 0; " << lane << " < " << layout->count.text << "; " << lane
           << " = " << lane << " + 1) begin : " << scope << "\n";
  }
  llvm::StringRef indent = shape ? "      " : "  ";
  for (const auto &program : programs)
    for (const auto &step : program.steps)
      output << indent << "localparam integer " << step.destination << " = " << step.expression
             << ";\n";
  output << indent << aliasHelper << " "
         << (shape ? "link" : reserveStructuralName(state, prefix + "_link")) << "(.lhs("
         << actuals[0] << "), .rhs(" << actuals[1] << "));\n";
  if (shape) {
    output << "    end\n";
    if (!state.structuralScope)
      output << "  endgenerate\n";
  }
  return success();
}

LogicalResult renderHierarchy(Operation *definition, ModuleRenderState &state,
                              llvm::StringRef aliasHelper, llvm::raw_ostream &output) {
  Region &region = definition->getRegion(0);
  if (!llvm::hasSingleElement(region))
    return failure();

  auto topology = resolveConservativeStructuralScope(definition);
  if (failed(topology))
    return failure();
  llvm::SmallVector<Operation *, 8> instances;
  llvm::SmallVector<Operation *, 16> instanceTerminals;
  llvm::SmallVector<Operation *, 16> connections;
  llvm::SmallVector<Operation *, 8> overrides;
  llvm::SmallVector<Operation *, 8> arrayConnections;
  llvm::SmallVector<Operation *, 8> instanceArrays;
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
    else if (name == "nodal.connect_array")
      arrayConnections.push_back(&operation);
    else if (name == "nodal.instance_terminal_array")
      instanceArrays.push_back(&operation);
  }
  if (instances.empty() && instanceTerminals.empty() && connections.empty() && overrides.empty() &&
      arrayConnections.empty() && instanceArrays.empty())
    return success();

  llvm::sort(instances, [](Operation *left, Operation *right) {
    return symbolName(left) < symbolName(right);
  });
  llvm::StringMap<Operation *> instancesByName;
  llvm::DenseMap<Operation *, HierarchyInstanceRender> rendered;
  for (Operation *instance : instances) {
    llvm::StringRef name = symbolName(instance);
    Operation *child = resolveInstanceModuleDefinition(instance);
    auto domains = instance->getAttrOfType<DictionaryAttr>("domain_bindings");
    if (name.empty() || !isPortableVerilogIdentifier(name) || !child ||
        (domains && !domains.empty()) || !instancesByName.try_emplace(name, instance).second)
      return emitMappedFailure(instance, "NODAL-BACKEND-HIERARCHY-001",
                               "instance identity, target, or domain profile is unsupported");
    rendered[instance] = HierarchyInstanceRender{child, {}, {}};
  }
  if (instances.empty() && topology->relations.empty() && !state.structuralScope)
    return emitMappedFailure(definition, "NODAL-BACKEND-HIERARCHY-002",
                             "hierarchy endpoints or connections require an instance");

  llvm::DenseMap<Value, std::string> childActuals;
  unsigned groupOrdinal = 0;
  for (const auto &group : topology->scalarGroups) {
    std::set<std::string> anchors;
    llvm::SmallVector<Value> children;
    for (Value member : group.members) {
      auto endpoint = state.terminals.find(member);
      if (endpoint != state.terminals.end())
        anchors.insert(endpoint->second);
      else if (llvm::isa_and_nonnull<InstanceTerminalOp>(member.getDefiningOp()))
        children.push_back(member);
      else
        return emitMappedFailure(definition, "NODAL-BACKEND-HIERARCHY-002",
                                 "scoped connection contains an unsupported endpoint");
    }
    if (!state.structuralScope && children.empty() && !group.connections.empty())
      return emitMappedFailure(group.connections.front(), "NODAL-BACKEND-HIERARCHY-005",
                               "parent-only connection is not representable by the scalar "
                               "instance profile");
    if (!state.structuralScope && !children.empty() && anchors.size() != 1)
      return emitMappedFailure(children.front().getDefiningOp(), "NODAL-BACKEND-HIERARCHY-004",
                               "child port requires exactly one parent-local net anchor");
    if (children.empty() && group.connections.empty())
      continue;
    if (anchors.empty()) {
      std::string net =
          reserveStructuralName(state, "topology_net_" + std::to_string(groupOrdinal));
      output << "  electrical " << net << ";\n";
      anchors.insert(net);
    }
    const std::string &anchor = *anchors.begin();
    unsigned aliasOrdinal = 0;
    for (const auto &other : llvm::drop_begin(anchors)) {
      if (aliasHelper.empty())
        return failure();
      auto name = reserveStructuralName(state, "topology_join_" + std::to_string(groupOrdinal) +
                                                   "_" + std::to_string(aliasOrdinal++));
      output << "  " << aliasHelper << " " << name << "(.lhs(" << anchor << "), .rhs(" << other
             << "));\n";
    }
    for (Value child : children)
      childActuals.try_emplace(child, anchor);
    ++groupOrdinal;
  }
  for (Operation *terminal : instanceTerminals) {
    auto reference = terminal->getAttrOfType<FlatSymbolRefAttr>("instance");
    Operation *instance = resolveStructuralInstance(terminal, reference);
    auto port = terminal->getAttrOfType<StringAttr>("port");
    if (terminal->getNumResults() == 1) {
      auto relationActual = state.relationScalarActuals.find(terminal->getResult(0));
      if (relationActual != state.relationScalarActuals.end())
        childActuals.try_emplace(terminal->getResult(0), relationActual->second);
    }
    auto actual = terminal->getNumResults() == 1 ? childActuals.find(terminal->getResult(0))
                                                 : childActuals.end();
    if (!instance || !rendered.count(instance) || !port || actual == childActuals.end() ||
        !rendered[instance].ports.emplace(port.getValue().str(), actual->second).second)
      return emitMappedFailure(terminal, "NODAL-BACKEND-HIERARCHY-003",
                               "child port requires one verified scoped connection");
  }

  for (Operation *reference : instanceArrays) {
    auto endpoint = resolveConservativeEndpoint(reference->getResult(0), reference);
    auto actual = state.childArrayActuals.find(reference->getResult(0));
    auto port = succeeded(endpoint) && endpoint->port
                    ? endpoint->port->getAttrOfType<StringAttr>("name")
                    : StringAttr();
    if (failed(endpoint) || !rendered.count(endpoint->instance) || !port ||
        actual == state.childArrayActuals.end() ||
        !rendered[endpoint->instance].ports.emplace(port.getValue().str(), actual->second).second)
      return emitMappedFailure(reference, "NODAL-BACKEND-HIERARCHY-011",
                               "child array requires one verified full-port actual");
  }
  unsigned relationOrdinal = 0;
  for (const auto &relation : topology->relations)
    if (failed(renderConservativeRelation(relation, relationOrdinal++, state, aliasHelper, output)))
      return emitMappedFailure(
          relation.operation, "NODAL-BACKEND-HIERARCHY-012",
          "conservative relation map exceeds the portable target structural Integer profile");

  for (Operation *instance : instances) {
    auto &entry = rendered[instance];
    std::set<std::string> childPorts;
    for (Operation &operation : entry.child->getRegion(0).front())
      if (llvm::isa<TerminalOp, TerminalArrayOp>(operation)) {
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
    renderStatementProvenance(instance, state, "  ", output);
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
    auto targetName = state.targetInstanceNames.find(instance);
    output << " "
           << (targetName == state.targetInstanceNames.end() ? symbolName(instance)
                                                             : llvm::StringRef(targetName->second))
           << "(";
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
    renderStatementProvenance(&operation, state, "    ", output);
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
  std::string ordinal;
};

GeneratedNodeArrayLayout generatedNodeArrayLayout(const GeneratedTargetBound &lower,
                                                  const GeneratedTargetBound &upper,
                                                  const GeneratedTargetBound &step,
                                                  llvm::StringRef induction) {
  if (step.range.lower == 1 && step.range.upper == 1) {
    // A direct array retains authored bounds, but a nested product layout is
    // zero-based and must compose occurrence ordinals rather than HDL indices.
    const std::string ordinal =
        lower.range.lower == 0 && lower.range.upper == 0
            ? induction.str()
            : (llvm::Twine("(") + induction + " - " + lower.text + ")").str();
    return {lower.text, (llvm::Twine("(") + upper.text + " - 1)").str(), induction.str(),
            (llvm::Twine("(") + upper.text + " - " + lower.text + ")").str(), ordinal};
  }

  const std::string extent = (llvm::Twine("(") + upper.text + " - " + lower.text + ")").str();
  const std::string rounded = (llvm::Twine("((") + extent + " + " + step.text + ") - 1)").str();
  const std::string count = (llvm::Twine("(") + rounded + " / " + step.text + ")").str();
  const std::string ordinal =
      (llvm::Twine("((") + induction + " - " + lower.text + ") / " + step.text + ")").str();
  return {"0", (llvm::Twine("(") + count + " - 1)").str(), ordinal, count, ordinal};
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

FailureOr<GeneratedTargetBound> generatedTargetBound(Value value, Operation *generate,
                                                     llvm::StringRef name) {
  // The Parameter owner proves the original exact lexical use and its complete
  // all-lane domain. Rendering does not grant scope or specialize an occurrence.
  auto facts = resolveStructuralIntegerExpression(value, generate,
                                                  ParameterIntegerMode::MathematicalSigned64);
  if (failed(facts) || !facts->expression)
    return failure();
  auto rendered = nodal::renderParameterConstantExpression(value);
  if (failed(rendered)) {
    (void)emitMappedFailure(generate, "NODAL-BACKEND-CAPABILITY-001",
                            llvm::Twine("generated contribution cannot prove finite '") + name +
                                "' expression bounds");
    return failure();
  }
  return GeneratedTargetBound{*rendered, facts->expression->bounds};
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

bool generatedValueFeedsOneIdtInput(Value value, Block &body, llvm::DenseSet<Operation *> &active,
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
  if (name == "nodal.analog_idt" && use.getOperandNumber() == 0) {
    llvm::DenseSet<Operation *> downstream;
    const bool result = user->getNumResults() == 1 &&
                        generatedValueFeedsOneContribution(user->getResult(0), body, downstream,
                                                           contributionBranch);
    active.erase(definition);
    return result;
  }
  const bool expression = name == "nodal.analog_add" || name == "nodal.analog_sub" ||
                          name == "nodal.analog_mul" || name == "nodal.analog_div" ||
                          name == "nodal.analog_neg";
  const bool result =
      expression && user->getNumResults() == 1 &&
      generatedValueFeedsOneIdtInput(user->getResult(0), body, active, contributionBranch);
  active.erase(definition);
  return result;
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
  Operation *leaf = generate;
  while (!isGeneratedNodeContributionProfile(leaf)) {
    Region &body = leaf->getRegion(0);
    if (!llvm::hasSingleElement(body) || body.front().getOperations().size() != 1)
      return nullptr;
    leaf = &body.front().front();
    if (leaf->getName().getStringRef() != "nodal.generate")
      return nullptr;
  }
  if (leaf == generate)
    return nullptr;
  Operation &child = *leaf;
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
  if (nodes == 0 || branches == 0 || !analog || !llvm::hasSingleElement(analog->getRegion(0)))
    return nullptr;

  unsigned accesses = 0, contributions = 0, derivatives = 0, integrals = 0;
  for (Operation &operation : analog->getRegion(0).front()) {
    llvm::StringRef name = operation.getName().getStringRef();
    if (name == "nodal.access") {
      if (operation.getNumResults() != 1)
        return nullptr;
      accesses += !operation.getResult(0).use_empty();
    } else if (name == "nodal.analog_ddt")
      ++derivatives;
    else if (name == "nodal.analog_idt")
      ++integrals;
    else if (name == "nodal.contribute")
      ++contributions;
    else if (name != "nodal.real_literal" && name != "nodal.parameter_ref" &&
             name != "nodal.analog_add" && name != "nodal.analog_sub" &&
             name != "nodal.analog_mul" && name != "nodal.analog_div" && name != "nodal.analog_neg")
      return nullptr;
  }
  // Cardinality is not an ownership rule. The renderer validates each branch's
  // access, operator/state identities and contribution independently, so disjoint
  // equations compose without enumerating one/two-node or operator-kind tuples.
  return accesses == branches && contributions == branches && derivatives + integrals <= accesses
             ? &child
             : nullptr;
}

// A chain carries authored ranges, not expanded occurrences. Both storage and
// equation emission resolve this same path and compose its row-major layout.
struct GeneratedEquationRange {
  Operation *operation;
  GeneratedTargetBound lower, upper, step;
  StringAttr induction, regionId, inductionPath;
  GeneratedNodeArrayLayout layout;
};

FailureOr<llvm::SmallVector<GeneratedEquationRange, 2>>
resolveGeneratedEquationChain(Operation *root, Operation *leaf) {
  llvm::SmallVector<GeneratedEquationRange, 2> ranges;
  __int128 maximumProduct = 1;
  for (Operation *current = root;;) {
    const bool compound = current->getNumOperands() == 3;
    if (current->getNumOperands() != 0 && !compound)
      return emitMappedFailure(current, "NODAL-BACKEND-CAPABILITY-001",
                               "generated equation bounds require attributes or three SSA values");
    auto lower = compound ? generatedTargetBound(current->getOperand(0), current, "lower")
                          : generatedTargetBound(current, "lower");
    auto upper = compound ? generatedTargetBound(current->getOperand(1), current, "upper")
                          : generatedTargetBound(current, "upper");
    auto step = compound ? generatedTargetBound(current->getOperand(2), current, "step")
                         : generatedTargetBound(current, "step");
    if (failed(lower) || failed(upper) || failed(step))
      return failure();
    auto induction = current->getAttrOfType<StringAttr>("induction");
    auto regionId = current->getAttrOfType<StringAttr>("region_id");
    auto inductionPath = current->getAttrOfType<StringAttr>("induction_path");
    if (!induction || !regionId || !inductionPath ||
        !isPortableVerilogIdentifier(induction.getValue()) || step->range.lower <= 0 ||
        lower->range.upper >= upper->range.lower)
      return emitMappedFailure(
          current, "NODAL-BACKEND-CAPABILITY-001",
          "generated equation chain requires canonical nonempty positive ranges");
    const __int128 span = static_cast<__int128>(upper->range.upper) - lower->range.lower;
    const __int128 count = (span + step->range.lower - 1) / step->range.lower;
    // Limit before multiplying, so deep symbolic paths cannot overflow the
    // signed-64 structural layout envelope even in the validation arithmetic.
    if (count <= 0 || count > INT64_MAX || maximumProduct > INT64_MAX / count)
      return emitMappedFailure(current, "NODAL-BACKEND-CAPABILITY-001",
                               "generated equation layout exceeds the signed-64 extent envelope");
    maximumProduct *= count;
    ranges.push_back({current, *lower, *upper, *step, induction, regionId, inductionPath,
                      generatedNodeArrayLayout(*lower, *upper, *step, induction.getValue())});
    if (current == leaf)
      return ranges;
    Region &body = current->getRegion(0);
    if (!llvm::hasSingleElement(body) || body.front().getOperations().size() != 1 ||
        body.front().front().getName().getStringRef() != "nodal.generate")
      return emitMappedFailure(current, "NODAL-BACKEND-CAPABILITY-001",
                               "generated equation layout requires one direct lexical chain");
    current = &body.front().front();
  }
}

struct GeneratedEquationLayout {
  std::string count, ordinal;
};

GeneratedEquationLayout
composeGeneratedEquationLayout(llvm::ArrayRef<GeneratedEquationRange> ranges) {
  GeneratedEquationLayout result;
  llvm::raw_string_ostream count(result.count), ordinal(result.ordinal);
  for (size_t index = 1; index < ranges.size(); ++index) {
    count << "(";
    ordinal << "((";
  }
  count << ranges.front().layout.count;
  ordinal << ranges.front().layout.ordinal;
  for (const GeneratedEquationRange &range : llvm::drop_begin(ranges)) {
    count << " * " << range.layout.count << ")";
    ordinal << " * " << range.layout.count << ") + " << range.layout.ordinal << ")";
  }
  count.flush();
  ordinal.flush();
  return result;
}

struct GeneratedAnalogPayload {
  Operation *procedure = nullptr;
  unsigned contributions = 0;
  unsigned procedures = 0;
  unsigned boundStepEffects = 0;
  unsigned contributedBranches = 0;
};

FailureOr<GeneratedAnalogPayload>
prepareGeneratedAnalogPayload(Operation *analog, Operation *generate, ModuleRenderState &state,
                              bool generatedNodeContributionProfile,
                              const llvm::DenseSet<Value> &generatedBranchValues) {
  auto regionId = generate->getAttrOfType<StringAttr>("region_id");
  auto inductionPath = generate->getAttrOfType<StringAttr>("induction_path");
  if (!regionId || !inductionPath)
    return failure();
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
      if (failed(verifyAnalogProcedureTarget(&operation)))
        return failure();
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
            !generatedBranchValues.contains(operation.getOperand(0)) ||
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
    if (name == "nodal.analog_context") {
      auto owner = operation.getAttrOfType<StringAttr>("generated_owner");
      auto generatedInduction = operation.getAttrOfType<StringAttr>("generated_induction");
      auto kind = operation.getAttrOfType<StringAttr>("query_kind");
      if (!owner || !generatedInduction || owner.getValue() != regionId.getValue() ||
          generatedInduction.getValue() != inductionPath.getValue() || !kind ||
          !lookupAnalogContext(kind.getValue()) || operation.getNumOperands() != 0 ||
          operation.getNumResults() != 1)
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            "generated context query ownership or identity does not match its range");
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
          !generatedBranchValues.contains(operation.getOperand(0)))
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            "generated analog-node access ownership does not match its branch");
      if (operation.getNumResults() != 1)
        return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                                 "generated analog-node access requires one result");
      // Public contribution construction also captures the target-side access.
      // It is pure and unused: retain its ownership checks, but do not count it
      // as an equation input or impose a destination path on a dead value.
      if (operation.getResult(0).use_empty())
        continue;
      if (!operation.getResult(0).hasOneUse())
        return emitMappedFailure(
            &operation, "NODAL-BACKEND-CAPABILITY-001",
            "generated analog-node access requires one owned contribution path");
      OpOperand &use = *operation.getResult(0).getUses().begin();
      Operation *user = use.getOwner();
      llvm::DenseSet<Operation *> derivativePath;
      const bool directDdt =
          user->getBlock() == &analogBody && use.getOperandNumber() == 0 &&
          user->getName().getStringRef() == "nodal.analog_ddt" && user->getNumResults() == 1 &&
          generatedValueFeedsOneContribution(user->getResult(0), analogBody, derivativePath,
                                             operation.getOperand(0));
      llvm::DenseSet<Operation *> active;
      const bool ownedIdtInput = generatedValueFeedsOneIdtInput(operation.getResult(0), analogBody,
                                                                active, operation.getOperand(0));
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
  return GeneratedAnalogPayload{eventProcedure, contributions, procedures, boundStepEffects,
                                static_cast<unsigned>(contributedGeneratedBranches.size())};
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

  llvm::SmallVector<GeneratedEquationRange, 2> chain;
  if (parentGenerate) {
    if (nestedGeneratedNodeContribution(parentGenerate) != generate)
      return emitMappedFailure(generate, "NODAL-BACKEND-CAPABILITY-001",
                               "nested generated equation requires one direct lexical chain");
    auto resolved = resolveGeneratedEquationChain(parentGenerate, generate);
    if (failed(resolved))
      return failure();
    chain = std::move(*resolved);
  }

  Region &body = generate->getRegion(0);
  if (!llvm::hasSingleElement(body))
    return emitMappedFailure(generate, "NODAL-BACKEND-CAPABILITY-001",
                             "generated Verilog-A range requires one body block");
  Operation *analog = nullptr;
  llvm::SmallVector<Operation *, 4> generatedNodes;
  llvm::SmallVector<Operation *, 4> generatedBranches;
  llvm::DenseSet<Operation *> generatedBranchOperations;
  llvm::DenseSet<Value> generatedBranchValues;
  const bool generatedNodeContributionProfile = isGeneratedNodeContributionProfile(generate);
  if (generatedNodeContributionProfile) {
    auto operation = body.front().begin();
    while (operation->getName().getStringRef() == "nodal.node") {
      generatedNodes.push_back(&*operation);
      ++operation;
    }
    while (operation->getName().getStringRef() == "nodal.branch") {
      generatedBranches.push_back(&*operation);
      generatedBranchOperations.insert(&*operation);
      if (operation->getNumResults() == 1)
        generatedBranchValues.insert(operation->getResult(0));
      ++operation;
    }
    analog = &body.front().back();
    if (step->range.lower <= 0 || lower->range.upper >= upper->range.lower)
      return emitMappedFailure(
          generate, "NODAL-BACKEND-CAPABILITY-001",
          "generated analog-node contribution requires a nonempty positive-step range");
    const GeneratedNodeArrayLayout layout =
        generatedNodeArrayLayout(*lower, *upper, *step, induction.getValue());
    const std::string targetIndex =
        chain.empty() ? layout.index : composeGeneratedEquationLayout(chain).ordinal;
    llvm::DenseMap<Value, Operation *> ownedNodes;
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
      const std::string indexedNode =
          (llvm::Twine(nodeName.getValue()) + "[" + targetIndex + "]").str();
      state.terminals.try_emplace(node->getResult(0), indexedNode);
      ownedNodes.try_emplace(node->getResult(0), node);
    }
    llvm::DenseSet<Value> selectedNodes;
    for (Operation *node : generatedNodes) {
      for (OpOperand &use : node->getResult(0).getUses()) {
        if (!generatedBranchOperations.contains(use.getOwner()) || use.getOperandNumber() > 1)
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

      Operation *positiveNode = ownedNodes.lookup(branch->getOperand(0));
      Operation *negativeNode = ownedNodes.lookup(branch->getOperand(1));
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
      // Source-free probe records carry checked intent, not another equation.
      // Keep the inherited analog-iteration layout and signed64 range profile.
      if (llvm::isa<ProbeOp>(operation))
        continue;
      if (operation.getName().getStringRef() != "nodal.analog" || analog)
        return emitMappedFailure(&operation, "NODAL-BACKEND-CAPABILITY-001",
                                 "fixed generated Verilog-A profile supports one analog region");
      analog = &operation;
    }
  }
  if (!analog)
    return emitMappedFailure(generate, "NODAL-BACKEND-CAPABILITY-001",
                             "fixed generated Verilog-A range has no analog contribution region");
  auto payload = prepareGeneratedAnalogPayload(
      analog, generate, state, generatedNodeContributionProfile, generatedBranchValues);
  if (failed(payload))
    return failure();
  Operation *eventProcedure = payload->procedure;
  const unsigned contributions = payload->contributions;
  const unsigned procedures = payload->procedures;
  const unsigned boundStepEffects = payload->boundStepEffects;
  const unsigned profiles = static_cast<unsigned>(contributions != 0) +
                            static_cast<unsigned>(procedures != 0) +
                            static_cast<unsigned>(boundStepEffects != 0);
  if (profiles != 1 || procedures > 1 || boundStepEffects > 1)
    return emitMappedFailure(analog, "NODAL-BACKEND-CAPABILITY-001",
                             "generated Verilog-A range requires either additive contributions or "
                             "one owned event procedure or boundStep effect");
  if (generatedNodeContributionProfile &&
      (contributions != generatedBranches.size() ||
       payload->contributedBranches != generatedBranches.size()))
    return emitMappedFailure(
        analog, "NODAL-BACKEND-CAPABILITY-001",
        "generated analog-node contribution profile requires one additive contribution per "
        "owned indexed branch");

  output << "  analog begin\n";
  for (const GeneratedEquationRange &range : llvm::drop_end(chain, chain.empty() ? 0 : 1)) {
    llvm::StringRef parentLabel = range.regionId.getValue();
    if (size_t separator = parentLabel.rfind('.'); separator != llvm::StringRef::npos)
      parentLabel = parentLabel.drop_front(separator + 1);
    if (!isPortableVerilogIdentifier(parentLabel))
      return emitMappedFailure(range.operation, "NODAL-BACKEND-CAPABILITY-001",
                               "nested generated Verilog-A parent label is invalid");
    output << "    for (" << range.induction.getValue() << " = " << range.lower.text << "; "
           << range.induction.getValue() << " < " << range.upper.text << "; "
           << range.induction.getValue() << " = " << range.induction.getValue() << " + "
           << range.step.text << ") begin : " << parentLabel << "\n";
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
  for (size_t index = 1; index < chain.size(); ++index)
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

  auto chain = resolveGeneratedEquationChain(generate, child);
  if (failed(chain))
    return failure();
  const std::string count = composeGeneratedEquationLayout(*chain).count;
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
    output << "  electrical [0:(" << count << " - 1)] " << nodeName.getValue() << ";\n";
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
        output << "  electrical [" << layouts.front().lower << ":" << layouts.front().upper << "] "
               << nodeName.getValue() << ";\n";
        continue;
      }
      std::string count = layouts.front().count;
      for (const GeneratedNodeArrayLayout &layout : llvm::drop_begin(layouts))
        count = (llvm::Twine("(") + count + " * " + layout.count + ")").str();
      output << "  electrical [0:(" << count << " - 1)] " << nodeName.getValue() << ";\n";
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

    output << "  electrical [" << layout.lower << ":" << layout.upper << "] " << name.getValue()
           << ";\n";
  }
  return success();
}

// A lexical structural scope carries declarations and template statements, not
// one module-global net for every generated occurrence.
// An unselected instance export is an authored proof record for the same
// retained physical child. It emits no additional child, net, or equation.
LogicalResult verifyUnselectedStructuralInstanceExport(Operation *operation) {
  auto exported = llvm::dyn_cast_or_null<GeneratedExportOp>(operation);
  Operation *family = operation ? operation->getParentOp() : nullptr;
  auto kind = operation ? operation->getAttrOfType<StringAttr>("kind") : StringAttr();
  if (!exported || !kind || kind.getValue() != "instance" ||
      !llvm::isa_and_nonnull<GenerateOp>(family) || failed(exported.verify()))
    return failure();
  Operation *parent = family->getParentOp();
  const bool nested = llvm::isa_and_nonnull<GenerateOp>(parent);
  if (!llvm::isa_and_nonnull<ModuleOp>(parent)) {
    if (!nested || !llvm::isa<ModuleOp>(parent->getParentOp()))
      return failure();
    auto literal = [](Value value) -> std::optional<int64_t> {
      auto op = value.getDefiningOp<ConstLiteralOp>();
      auto attr = op ? op->getAttrOfType<IntegerAttr>("value") : IntegerAttr();
      if (!attr || !value.getType().isInteger(64) || !attr.getValue().isSignedIntN(64))
        return std::nullopt;
      return attr.getValue().getSExtValue();
    };
    for (Operation *axis : {parent, family}) {
      if (axis->getNumOperands() != 3 || literal(axis->getOperand(0)) != 0 ||
          literal(axis->getOperand(2)) != 1)
        return failure();
      auto upper = literal(axis->getOperand(1));
      if (!upper || *upper <= 0 || *upper > std::numeric_limits<int32_t>::max())
        return failure();
    }
  }
  auto target = inspectGeneratedExportSymbol(operation);
  if (failed(target) || !llvm::isa<InstanceOp>(*target) ||
      (*target)->getParentOp() != family ||
      llvm::any_of(family->getResults(), [](Value result) { return !result.use_empty(); }))
    return failure();
  if (nested) {
    auto bindings = (*target)->getAttrOfType<DictionaryAttr>("parameter_bindings");
    Operation *child = resolveInstanceModuleDefinition(*target);
    if (!bindings || !child || child->hasAttr("external_name") ||
        child->getNumRegions() != 1 || !llvm::hasSingleElement(child->getRegion(0)))
      return failure();
    for (NamedAttribute binding : bindings) {
      auto value = llvm::dyn_cast<FloatAttr>(binding.getValue());
      if (!value || !value.getType().isF64() || !value.getValue().isFinite())
        return failure();
    }
    for (Operation &candidate : family->getRegion(0).front())
      if (llvm::isa<ParameterOverrideOp>(candidate) &&
          candidate.getAttrOfType<FlatSymbolRefAttr>("instance") ==
              FlatSymbolRefAttr::get(operation->getContext(), symbolName(*target)))
        return failure();
    for (Operation &parameter : child->getRegion(0).front()) {
      if (llvm::isa<ParameterValueOp>(parameter)) {
        if (parameter.getNumOperands() != 1) return failure();
        auto literal = parameter.getOperand(0).getDefiningOp<ConstLiteralOp>();
        auto value = literal ? literal->getAttrOfType<FloatAttr>("value") : FloatAttr();
        if (!literal || literal->hasAttr("unit") || !value || !value.getType().isF64() ||
            !value.getValue().isFinite())
          return failure();
      }
      if (!llvm::isa<ParameterOp>(parameter)) continue;
      auto type = parameter.getAttrOfType<TypeAttr>("type");
      auto value = parameter.getAttrOfType<FloatAttr>("default_value");
      if (!type || !type.getValue().isF64() || !value || !value.getValue().isFinite() ||
          parameter.hasAttr("unit") || getParameterKind(&parameter) != "real" ||
          getParameterClassification(&parameter) != "ordinary")
        return failure();
    }
  }
  auto scope = resolveConservativeStructuralScope(family);
  return success(succeeded(scope) && llvm::is_contained(scope->instances, *target));
}

LogicalResult renderStructuralGenerate(Operation *generate, ModuleRenderState &parentState,
                                       llvm::StringRef aliasHelper, llvm::raw_ostream &output,
                                       bool outermost = true) {
  auto topology = resolveConservativeStructuralScope(generate);
  auto domain = resolveGeneratedDomain(generate);
  if (failed(topology) || failed(domain))
    return failure();
  if (failed(verifyTargetStructuralIntegerExpression(domain->lower)) ||
      failed(verifyTargetStructuralIntegerExpression(domain->upperExclusive)) ||
      failed(verifyTargetStructuralIntegerExpression(domain->step)) ||
      domain->maximumTripCount > uint64_t(std::numeric_limits<int32_t>::max()))
    return emitMappedFailure(generate, "NODAL-BACKEND-TOPOLOGY-001",
                             "structural generated domain exceeds the signed32 target carrier");
  int64_t updated = 0;
  if (domain->attainedInductionBounds &&
      (__builtin_add_overflow(domain->attainedInductionBounds->upper, domain->step.bounds.upper,
                              &updated) ||
       updated > std::numeric_limits<int32_t>::max()))
    return emitMappedFailure(generate, "NODAL-BACKEND-TOPOLOGY-001",
                             "structural generated update exceeds the signed32 target carrier");
  const bool compound = generate->getNumOperands() == 3;
  auto lower = compound ? generatedTargetBound(generate->getOperand(0), generate, "lower")
                        : generatedTargetBound(generate, "lower");
  auto upper = compound ? generatedTargetBound(generate->getOperand(1), generate, "upper")
                        : generatedTargetBound(generate, "upper");
  auto step = compound ? generatedTargetBound(generate->getOperand(2), generate, "step")
                       : generatedTargetBound(generate, "step");
  auto induction = generate->getAttrOfType<StringAttr>("induction");
  auto region = generate->getAttrOfType<StringAttr>("region_id");
  if (failed(lower) || failed(upper) || failed(step) || !induction || !region ||
      !isPortableVerilogIdentifier(induction.getValue()))
    return failure();
  llvm::StringRef labelBase = region.getValue();
  if (size_t dot = labelBase.rfind('.'); dot != llvm::StringRef::npos)
    labelBase = labelBase.drop_front(dot + 1);
  if (!isPortableVerilogIdentifier(labelBase))
    return failure();
  // Derived names of enclosing storage and expression carriers share the
  // target namespace with the structural scope's genvar and label.
  for (const auto &entry : parentState.eventState.names)
    parentState.visibleTargetNames.insert(entry.second);
  for (const auto *names :
       {&parentState.waveformNames, &parentState.noiseNames, &parentState.transferNames})
    for (const auto &entry : *names)
      parentState.visibleTargetNames.insert(entry.second);
  for (const auto &[name, expression] : parentState.nodeMapConstants)
    parentState.visibleTargetNames.insert(name);
  auto allocate = [](ModuleRenderState &state, llvm::StringRef base) {
    std::string name = state.visibleTargetNames.insert(base).second
                           ? base.str()
                           : reserveStructuralName(state, base);
    state.visibleTargetNames.insert(name);
    return name;
  };
  const std::string indexName = allocate(parentState, induction.getValue());
  const std::string label = allocate(parentState, labelBase);
  ModuleRenderState state = parentState;
  state.structuralScope = true;
  state.nodeMapConstants.clear();
  state.childArrayDeclarations.clear();
  state.childScalarDeclarations.clear();
  if (domain->induction)
    state.structuralIndices[domain->induction] = TargetShapeExpression{
        indexName, domain->attainedInductionBounds.value_or(domain->lower.bounds)};
  std::string bodyText;
  llvm::raw_string_ostream body(bodyText);
  for (Operation *node : topology->nodes) {
    auto name = node->getAttrOfType<StringAttr>("name");
    auto type = node->getNumResults() == 1
                    ? llvm::dyn_cast<TerminalType>(node->getResult(0).getType())
                    : TerminalType();
    if (!name || !isPortableVerilogIdentifier(name.getValue()) || !type ||
        type.getDiscipline() != "electrical")
      return emitMappedFailure(node, "NODAL-BACKEND-TOPOLOGY-002",
                               "generated local node requires one owned electrical terminal");
    auto target = allocate(state, name.getValue());
    state.terminals[node->getResult(0)] = target;
    body << "  electrical " << target << ";\n";
  }
  for (Operation *array : topology->nodeArrays) {
    auto layout = resolveStaticNodeArray(array, generate);
    if (failed(layout))
      return emitMappedFailure(array, "NODAL-BACKEND-SHAPE-001",
                               "generated node array requires an owned bounded electrical "
                               "shape within the signed32 structural target carrier");
    layout->name = allocate(state, layout->name);
    state.nodeArrays[array->getResult(0)] = *layout;
    auto shaped = llvm::cast<ShapedType>(array->getResult(0).getType());
    auto inductionPath = generate->getAttrOfType<StringAttr>("induction_path");
    llvm::json::Object correspondence{
        {"source_path", conservativeSourcePath(array)},
        {"source_location", conservativeSourceLocation(array)},
        {"source_scope", region.getValue()},
        {"source_induction", inductionPath ? inductionPath.getValue() : llvm::StringRef()},
        {"dimensions", shaped.getDimensions()},
        {"target_name", layout->name},
        {"target_extent", layout->extent}};
    body << "  // nodal.node_array " << llvm::json::Value(std::move(correspondence)) << "\n";
    renderConservativeArrayDeclaration(*layout, body);
  }
  for (Operation *instance : topology->instances) {
    if (!isPortableVerilogIdentifier(symbolName(instance)))
      return failure();
    state.targetInstanceNames[instance] = allocate(state, symbolName(instance));
  }
  if (failed(prepareConservativeRelationRoots(generate, state)))
    return failure();
  renderConservativeRootDeclarations(state, body);
  llvm::SmallVector<Operation *> analogs, branches;
  for (Operation &operation : generate->getRegion(0).front()) {
    if (llvm::isa<ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(operation)) {
      auto reference = operation.getNumResults() == 1
                           ? resolveConservativeShape(operation.getResult(0), &operation)
                           : FailureOr<ConservativeShape>(failure());
      if (failed(reference))
        return failure();
      if (llvm::isa<TerminalType>(operation.getResult(0).getType()) &&
          !state.terminals.count(operation.getResult(0))) {
        auto terminal = renderConservativeNodeSelection(&operation, generate, state);
        if (failed(terminal))
          return emitMappedFailure(
              &operation, "NODAL-BACKEND-TOPOLOGY-003",
              "generated selection exceeds the checked structural map profile");
        state.terminals[operation.getResult(0)] = *terminal;
      }
    } else if (llvm::isa<BranchOp>(operation)) {
      auto positive = state.terminals.find(operation.getOperand(0));
      auto negative = state.terminals.find(operation.getOperand(1));
      if (positive == state.terminals.end() || negative == state.terminals.end())
        return failure();
      state.branches[operation.getResult(0)] = {positive->second, negative->second};
      if (auto name = operation.getAttrOfType<StringAttr>("name")) {
        if (!name.getValue().empty()) {
          if (!isPortableVerilogIdentifier(name.getValue()))
            return failure();
          state.branchNames[operation.getResult(0)] = allocate(state, name.getValue());
          branches.push_back(&operation);
        }
      }
    } else if (llvm::isa<AnalogOp>(operation)) {
      analogs.push_back(&operation);
    } else if (llvm::isa<GeneratedExportOp>(operation)) {
      if (failed(verifyUnselectedStructuralInstanceExport(&operation)))
        return emitMappedFailure(&operation, "NODAL-BACKEND-TOPOLOGY-004",
                                 "generated export lacks an unselected exact physical child proof");
    } else if (llvm::isa<ProbeOp>(operation)) {
      // The complete native access/probe model is verified at the candidate
      // boundary. Intent records add neither an equation nor a physical branch.
      continue;
    } else if (!llvm::isa<NodeOp, NodeArrayOp, InstanceOp, InstanceTerminalOp,
                          InstanceTerminalArrayOp, ConnectOp, ConnectArrayOp, ParameterOverrideOp,
                          GenerateOp, ConstLiteralOp, ConstParameterRefOp, ConstExprOp, ConstantOp>(
                   operation)) {
      return emitMappedFailure(&operation, "NODAL-BACKEND-TOPOLOGY-004",
                               "operation has no structural occurrence lowering");
    }
  }
  for (const auto &[name, expression] : state.nodeMapConstants)
    body << "  localparam integer " << name << " = " << expression << ";\n";
  for (Operation *branch : branches) {
    const auto &endpoints = state.branches.find(branch->getResult(0))->second;
    body << "  branch (" << endpoints.first << ", " << endpoints.second << ") "
         << state.branchNames.find(branch->getResult(0))->second << ";\n";
  }
  if (failed(renderHierarchy(generate, state, aliasHelper, body)))
    return failure();
  Operation *procedure = nullptr;
  unsigned boundSteps = 0;
  const llvm::DenseSet<Value> noLegacyIndexedBranches;
  for (Operation *analog : analogs) {
    auto payload =
        prepareGeneratedAnalogPayload(analog, generate, state, false, noLegacyIndexedBranches);
    if (failed(payload))
      return failure();
    if (payload->procedures > 1 || (procedure && payload->procedure))
      return emitMappedFailure(analog, "NODAL-BACKEND-TOPOLOGY-005",
                               "one ordered procedure is supported per generated occurrence");
    if (payload->procedure)
      procedure = payload->procedure;
    boundSteps += payload->boundStepEffects;
  }
  if (boundSteps > 1)
    return emitMappedFailure(generate, "NODAL-BACKEND-TOPOLOGY-005",
                             "multiple generated boundStep effects remain unsupported");
  if (procedure && failed(prepareGeneratedAnalogEventBackend(
                       procedure, state.eventState, body, 2,
                       {GeneratedAnalogDeclarationContext::Kind::StructuralOccurrence, generate})))
    return failure();
  // VAMS concatenates analog blocks after elaboration in textual order. Keep
  // each actual block beside its authored nested scopes; its own procedure still
  // executes before that block's continuous reads, without duplicate execution.
  for (Operation &operation : generate->getRegion(0).front()) {
    if (llvm::isa<GenerateOp>(operation)) {
      if (failed(renderStructuralGenerate(&operation, state, aliasHelper, body, false)))
        return failure();
    } else if (llvm::isa<AnalogOp>(operation)) {
      body << "  analog begin\n";
      if (procedure && procedure->getParentOp() == &operation &&
          failed(renderAnalogEventProcedure(procedure, state.eventState, body)))
        return failure();
      if (failed(renderAnalog(&operation, state, body, false, true)))
        return failure();
      body << "  end\n";
    }
  }
  body.flush();
  auto inductionPath = generate->getAttrOfType<StringAttr>("induction_path");
  llvm::json::Object correspondence{
      {"source_scope", region.getValue()},
      {"source_induction", inductionPath ? inductionPath.getValue() : llvm::StringRef()},
      {"target_genvar", indexName},
      {"target_label", label},
      {"lower", lower->text},
      {"upper_exclusive", upper->text},
      {"step", step->text}};
  output << "  // nodal.structural_scope " << llvm::json::Value(std::move(correspondence)) << "\n";
  output << "  genvar " << indexName << ";\n";
  if (outermost)
    output << "  generate\n";
  const llvm::StringRef loopIndent = outermost ? "    " : "  ";
  output << loopIndent << "for (" << indexName << " = " << lower->text << "; " << indexName << " < "
         << upper->text << "; " << indexName << " = " << indexName << " + " << step->text
         << ") begin : " << label << "\n";
  llvm::SmallVector<llvm::StringRef> lines;
  llvm::StringRef(bodyText).split(lines, '\n');
  for (auto line : lines)
    if (!line.empty())
      output << loopIndent << line << "\n";
  output << loopIndent << "end\n";
  if (outermost)
    output << "  endgenerate\n";
  return success();
}

LogicalResult renderDefinition(Operation *definition, llvm::StringRef aliasHelper,
                               const AuthoredSourceMap *sourceMap, llvm::raw_ostream &output) {
  ModuleRenderState state;
  state.sourceMap = sourceMap;
  llvm::SmallVector<Operation *, 8> parameters;
  llvm::SmallVector<Operation *, 8> ports;
  llvm::SmallVector<Operation *, 8> nodes;
  llvm::SmallVector<Operation *, 8> nodeArrays;
  llvm::SmallVector<Operation *, 4> namedBranches;
  llvm::SmallVector<Operation *, 2> analogs;
  llvm::SmallVector<Operation *, 2> generates;
  if (failed(collectModuleState(definition, state, parameters, ports, nodes, nodeArrays,
                                namedBranches, analogs, generates)))
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
    if (!state.nodeArrays.count(port->getResult(0)))
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

  if (llvm::any_of(ports,
                   [&](Operation *port) { return !state.nodeArrays.count(port->getResult(0)); }) ||
      !nodes.empty()) {
    output << "  electrical ";
    bool first = true;
    auto emitElectricalName = [&](Operation *operation) {
      if (!first)
        output << ", ";
      first = false;
      output << operation->getAttrOfType<StringAttr>("name").getValue();
    };
    for (Operation *port : ports)
      if (!state.nodeArrays.count(port->getResult(0)))
        emitElectricalName(port);
    for (Operation *node : nodes)
      emitElectricalName(node);
    output << ";\n";
  }

  auto renderNamedBranches = [&]() -> LogicalResult {
    for (Operation *branch : namedBranches) {
      auto endpoints = state.branches.find(branch->getResult(0));
      auto name = branch->getAttrOfType<StringAttr>("name");
      if (endpoints == state.branches.end() || !name || name.getValue().trim().empty())
        return emitMappedFailure(branch, "NODAL-BACKEND-RC-004",
                                 "named branch is not losslessly renderable");
      output << "  branch (" << endpoints->second.first << ", " << endpoints->second.second << ") "
             << name.getValue() << ";\n";
    }

    return success();
  };
  if (nodeArrays.empty() && state.childArrayDeclarations.empty() && failed(renderNamedBranches()))
    return failure();

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
    renderStatementProvenance(parameter, state, "  ", output);
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

  for (Operation *array : nodeArrays) {
    const auto &layout = state.nodeArrays.find(array->getResult(0))->second;
    renderConservativeArrayDeclaration(
        layout, output, llvm::isa<TerminalArrayOp>(array) ? terminalDirection(array) : "");
  }
  renderConservativeRootDeclarations(state, output);
  for (const auto &[name, initializer] : state.nodeMapConstants)
    output << "  localparam integer " << name << " = " << initializer << ";\n";
  if ((!nodeArrays.empty() || !state.childArrayDeclarations.empty()) &&
      failed(renderNamedBranches()))
    return failure();

  llvm::StringSet<> generatedInductions;
  llvm::StringSet<> generatedLabels;
  for (Operation *generate : generates) {
    if (requiresStructuralGenerate(generate))
      continue;
    llvm::SmallVector<Operation *, 2> targetRanges{generate};
    if (Operation *leaf = nestedGeneratedNodeContribution(generate)) {
      auto chain = resolveGeneratedEquationChain(generate, leaf);
      if (failed(chain))
        return failure();
      targetRanges.clear();
      for (const GeneratedEquationRange &range : *chain)
        targetRanges.push_back(range.operation);
    }
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
      if (!isGeneratedNodeArrayTreeProfile(generate)) {
        state.visibleTargetNames.insert(induction.getValue());
        output << "  genvar " << induction.getValue() << ";\n";
      }
    }
  }

  for (Operation *generate : generates)
    if (!requiresStructuralGenerate(generate) && hasGeneratedNodeArray(generate) &&
        failed(renderGeneratedNodeArray(generate, output)))
      return failure();

  if (failed(renderHierarchy(definition, state, aliasHelper, output)))
    return failure();

  if (failed(prepareAnalogEventBackend(definition, state.eventState, output)))
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

  if ((!ports.empty() || !nodes.empty() || !nodeArrays.empty() || !namedBranches.empty() ||
       !parameters.empty()) &&
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
    if (requiresStructuralGenerate(generate)) {
      if (failed(renderStructuralGenerate(generate, state, aliasHelper, output)))
        return failure();
    } else if (!isGeneratedNodeArrayTreeProfile(generate)) {
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
  using IdentifierResolver = std::function<bool(llvm::StringRef, std::optional<int64_t> &)>;
  GeneratedBoundExpressionParser(llvm::StringRef text, const std::set<std::string> &parameters,
                                 IdentifierResolver resolver = {}, bool signed32 = false)
      : text(text), parameters(parameters), resolver(std::move(resolver)), signed32(signed32) {}

  bool parse() { return expression(0, value) && position == text.size(); }
  std::optional<int64_t> constantValue() const { return value; }

private:
  bool expression(unsigned depth, std::optional<int64_t> &result) {
    result.reset();
    if (depth >= 512 || position >= text.size())
      return false;
    if (text[position] == '(') {
      ++position;
      const size_t contents = position;
      std::optional<int64_t> left, right;
      if (expression(depth + 1, left) && consume(' ') && position < text.size() &&
          llvm::StringRef("+-*/%").contains(text[position])) {
        char operation = text[position++];
        if (consume(' ') && expression(depth + 1, right) && consume(')')) {
          if ((operation == '/' || operation == '%') && right && *right == 0)
            return false;
          if (!left || !right)
            return true;
          int64_t computed = 0;
          if ((operation == '+' && __builtin_add_overflow(*left, *right, &computed)) ||
              (operation == '-' && __builtin_sub_overflow(*left, *right, &computed)) ||
              (operation == '*' && __builtin_mul_overflow(*left, *right, &computed)))
            return false;
          if (operation == '/' || operation == '%') {
            if (*left == std::numeric_limits<int64_t>::min() && *right == -1)
              return false;
            computed = operation == '/' ? *left / *right : *left % *right;
          }
          if (!fits(computed))
            return false;
          result = computed;
          return true;
        }
      }
      position = contents;
      if (consume('-') && expression(depth + 1, left) && consume(')')) {
        if (left) {
          if (*left == std::numeric_limits<int64_t>::min())
            return false;
          result = -*left;
          if (!fits(*result))
            return false;
        }
        return true;
      }
      return false;
    }

    const size_t start = position;
    if (text[position] == '-')
      ++position;
    const size_t digits = position;
    while (position < text.size() && text[position] >= '0' && text[position] <= '9')
      ++position;
    if (position != digits) {
      int64_t number = 0;
      auto parsed = std::from_chars(text.begin() + start, text.begin() + position, number);
      if (parsed.ec != std::errc() || parsed.ptr != text.begin() + position || !fits(number))
        return false;
      result = number;
      return true;
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
    return isPortableVerilogIdentifier(identifier) && parameters.count(identifier.str()) != 0 &&
           (!resolver || (resolver(identifier, result) && (!result || fits(*result))));
  }

  bool fits(int64_t value) const {
    return !signed32 || (value >= std::numeric_limits<int32_t>::min() &&
                         value <= std::numeric_limits<int32_t>::max());
  }

  bool consume(char expected) {
    if (position >= text.size() || text[position] != expected)
      return false;
    ++position;
    return true;
  }

  llvm::StringRef text;
  const std::set<std::string> &parameters;
  IdentifierResolver resolver;
  bool signed32;
  size_t position = 0;
  std::optional<int64_t> value;
};

bool validGeneratedBoundExpression(llvm::StringRef text, const std::set<std::string> &parameters) {
  return GeneratedBoundExpressionParser(text, parameters).parse();
}

// Shared static arithmetic/rendering does not establish an analog target
// contract. Keep the new reduction formulas at verified IR until a backend
// explicitly supports and checks their integer/conditional target semantics.
LogicalResult verifyStaticIntegerTargetBoundary(Operation *root) {
  Operation *unsupported = nullptr;
  root->walk([&](ConstExprOp expression) {
    const auto opcode = expression->getAttrOfType<StringAttr>("operator_name");
    if (!unsupported && opcode &&
        (opcode.getValue() == "ceil_log2" || opcode.getValue() == "choose_zero"))
      unsupported = expression.getOperation();
  });
  if (!unsupported)
    return success();
  return emitMappedFailure(unsupported, "NODAL-BACKEND-CAPABILITY-001",
                           "static ceil_log2/choose_zero require an explicit target lowering "
                           "contract; current Verilog-A/AMS profiles do not support them");
}

} // namespace

LogicalResult verifyBackendOperations(mlir::ModuleOp module, const BackendProfile &profile) {
  if (failed(verifyStaticIntegerTargetBoundary(module.getOperation())))
    return failure();
  Operation *sourceEquations = nullptr;
  module.walk([&](Operation *operation) {
    if (!sourceEquations && (operation->hasAttr("source_equations") ||
                             llvm::isa<AnalogUnknownOp, AnalogEquationOp>(operation)))
      sourceEquations = operation;
  });
  if (sourceEquations)
    return emitMappedFailure(sourceEquations, "NODAL-BACKEND-EQUATION-134-001",
                             "typed source equations require their equation-to-target lowering; "
                             "structural matching is not HDL emission");
  if (failed(normalizePotentialFlowAccess(module)))
    return failure();
  if (failed(verifyAnalogQuantityErasure(module)))
    return failure();
  LogicalResult result = success();
  llvm::DenseSet<Operation *> admittedProcedures;
  module.walk([&](Operation *operation) {
    if (failed(result) || operation == module.getOperation())
      return;
    llvm::StringRef name = operation->getName().getStringRef();
    auto procedure = llvm::dyn_cast<nodal::AnalogProcedureOp>(operation);
    if (!procedure)
      procedure = operation->getParentOfType<nodal::AnalogProcedureOp>();
    if (procedure) {
      if (admittedProcedures.insert(procedure.getOperation()).second)
        result = verifyAnalogProcedureTarget(procedure);
      return;
    }
    if (auto contract = llvm::dyn_cast<ComponentContractOp>(operation)) {
      result = contract.verify();
      return;
    }
    // Partial topology records describe the source net/branch incidence already
    // implemented by the target's conservative nets. They are not complete
    // equations and must never produce additional numeric contributions.
    if (llvm::isa<ConnectionSetOp, FlowConservationOp>(operation)) {
      auto owner = operation->getParentOfType<nodal::ModuleOp>();
      auto complete = operation->getAttrOfType<BoolAttr>("complete");
      if (owner && isPartialPhysicalComponent(owner) && complete && !complete.getValue()) {
        result = llvm::isa<ConnectionSetOp>(operation)
                     ? llvm::cast<ConnectionSetOp>(operation).verify()
                     : llvm::cast<FlowConservationOp>(operation).verify();
        return;
      }
    }
    if (auto equality = llvm::dyn_cast<PotentialEqualityOp>(operation)) {
      auto owner = operation->getParentOfType<nodal::ModuleOp>();
      if (owner && isPartialPhysicalComponent(owner) && succeeded(equality.verify()) &&
          compareConservativeEndpoints(operation->getOperand(0), operation->getOperand(1),
                                       operation) == ConservativeAlias::Equal)
        return;
      result =
          emitMappedFailure(operation, "NODAL-BACKEND-HIERARCHY-013",
                            "partial potential record requires an actual verified net relation");
      return;
    }
    if (llvm::isa<ShapeIndexOp, ShapeViewOp, ShapeSliceOp>(operation) &&
        operation->getNumResults() == 1 &&
        succeeded(resolveConservativeShape(operation->getResult(0), operation)))
      return;
    if (name == "nodal.constant" && operation->getNumResults() == 1 &&
        operation->getResult(0).getType().isIndex() && !operation->use_empty() &&
        llvm::all_of(operation->getUsers(), [](Operation *user) {
          return llvm::isa<ShapeIndexOp, ShapeSliceOp>(user) && user->getNumResults() == 1 &&
                 succeeded(resolveConservativeShape(user->getResult(0), user));
        }))
      return;
    if (llvm::isa<ProbeOp>(operation)) {
      result = verifyPotentialFlowAccessOperation(operation);
      return;
    }
    if (llvm::isa<GeneratedExportOp>(operation)) {
      if (failed(verifyUnselectedStructuralInstanceExport(operation)))
        result = emitMappedFailure(operation, "NODAL-BACKEND-CAPABILITY-001",
                                   "generated export lacks an unselected exact physical child proof");
      return;
    }
    if (isDirectBackendOperation(name))
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
  for (Operation *definition : definitions)
    if (failed(verifyStaticIntegerTargetBoundary(definition)))
      return failure();
  if (failed(verifyRootExport(definitions)))
    return failure();
  llvm::SmallVector<std::string> hierarchyCorrespondence;
  if (!definitions.empty()) {
    auto root = definitions.front()->getParentOfType<mlir::ModuleOp>();
    Attribute raw = root->getAttr("nodal.backend.hierarchy_correspondence");
    if (raw) {
      auto entries = llvm::dyn_cast<ArrayAttr>(raw);
      auto profile = root->getAttrOfType<StringAttr>("nodal.backend.hierarchy_materialization");
      if (!entries || !profile || profile.getValue() != "scalar-v1" || definitions.size() != 1)
        return emitMappedFailure(root, "NODAL-BACKEND-HIERARCHY-MATERIALIZE-003",
                                 "invalid scalar hierarchy correspondence inventory");
      llvm::StringSet<> identities;
      for (Attribute entry : entries) {
        auto dictionary = llvm::dyn_cast<DictionaryAttr>(entry);
        auto source = dictionary ? dictionary.getAs<StringAttr>("source") : StringAttr();
        auto target = dictionary ? dictionary.getAs<StringAttr>("target") : StringAttr();
        auto kind = dictionary ? dictionary.getAs<StringAttr>("kind") : StringAttr();
        if (!source || !target || !kind || !validCanonicalCommentText(source.getValue()) ||
            !isPortableVerilogIdentifier(target.getValue()) ||
            (kind.getValue() != "instance" && kind.getValue() != "terminal" &&
             kind.getValue() != "parameter") ||
            !identities.insert((source.getValue() + ":" + kind.getValue()).str()).second)
          return emitMappedFailure(root, "NODAL-BACKEND-HIERARCHY-MATERIALIZE-003",
                                   "malformed scalar hierarchy correspondence entry");
        std::string encoded;
        llvm::raw_string_ostream stream(encoded);
        stream << llvm::json::Value(llvm::json::Object{{"source", source.getValue()},
                                                       {"target", target.getValue()},
                                                       {"kind", kind.getValue()}});
        stream.flush();
        hierarchyCorrespondence.push_back(llvm::encodeBase64(encoded));
      }
    }
  }
  // Rendering is also a direct boundary: independently validate normalized
  // physical groups and their exact active support placements before emitting
  // any declaration. A derived record's name or owner attributes are not proof.
  llvm::DenseSet<Operation *> verifiedAccessRoots;
  llvm::DenseMap<Operation *, AuthoredSourceMap> sourceMaps;
  for (Operation *definition : definitions) {
    auto root = definition->getParentOfType<mlir::ModuleOp>();
    if (!root)
      return failure();
    if (verifiedAccessRoots.insert(root.getOperation()).second) {
      if (failed(verifyPotentialFlowAccessModel(root)))
        return failure();
      if (configuration.sourceProvenance) {
        auto sourceMap = AuthoredSourceMap::create(root);
        if (failed(sourceMap))
          return failure();
        sourceMaps.try_emplace(root.getOperation(), std::move(*sourceMap));
      }
    }
  }
  bool requiresAlias = false;
  llvm::StringSet<> moduleNames;
  for (Operation *definition : definitions) {
    moduleNames.insert(symbolName(definition));
    LogicalResult topologyResult = success();
    definition->walk([&](Operation *operation) {
      if (failed(topologyResult) ||
          !(operation == definition ||
            (llvm::isa<GenerateOp>(operation) && requiresStructuralGenerate(operation))))
        return;
      auto scope = resolveConservativeStructuralScope(operation);
      if (failed(scope)) {
        topologyResult = failure();
        return;
      }
      for (const auto &relation : scope->relations) {
        // Identity whole-child binding and an unobserved fixed contiguous
        // actual need no helper. All other relations use real net aliases.
        const ConservativeEndpoint *child = nullptr, *other = nullptr;
        if (relation.right.instance && !relation.left.instance) {
          child = &relation.right;
          other = &relation.left;
        } else if (relation.left.instance && !relation.right.instance) {
          child = &relation.left;
          other = &relation.right;
        }
        bool direct = child && llvm::isa<ShapedType>(child->shape.projection.root.getType()) &&
                      child->shape.projection.root.getDefiningOp()->getParentOp() ==
                          relation.operation->getParentOp() &&
                      child->shape.projection.wholeRoot &&
                      llvm::isa<ShapedType>(other->value.getType()) &&
                      (other->shape.projection.wholeRoot ||
                       (child->value == child->shape.projection.root &&
                        child->shape.projection.root.hasOneUse() &&
                        !requiresConservativeAdapter(other->shape.projection)));
        if (child && llvm::isa<TerminalType>(child->shape.projection.root.getType()) &&
            child->shape.projection.root.hasOneUse() && !other->instance &&
            isNodeArraySelection(other->value.getDefiningOp()))
          direct = true;
        requiresAlias |= !direct;
      }
      for (const auto &group : scope->scalarGroups) {
        unsigned anchors = llvm::count_if(group.members, [](Value member) {
          return !llvm::isa_and_nonnull<InstanceTerminalOp>(member.getDefiningOp());
        });
        requiresAlias |= anchors > 1;
      }
    });
    if (failed(topologyResult))
      return failure();
  }

  std::string aliasHelper = "nodal_alias_electrical";
  unsigned aliasCollision = 0;
  while (moduleNames.contains(aliasHelper))
    aliasHelper = "nodal_alias_electrical_" + std::to_string(++aliasCollision);
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
  if (!hierarchyCorrespondence.empty()) {
    output << "// nodal.hierarchy_materialization scalar-v1\n";
    for (const auto &entry : hierarchyCorrespondence)
      output << "// nodal.hierarchy_correspondence " << entry << "\n";
    output << "\n";
  }
  if (requiresAlias)
    output << "module " << aliasHelper << "(.lhs(n), .rhs(n));\n"
           << "  inout n;\n"
           << "  electrical n;\n"
           << "endmodule\n\n";
  bool firstDefinition = true;
  for (Operation *definition : definitions) {
    if (!firstDefinition)
      output << "\n";
    firstDefinition = false;
    auto sourceMap = sourceMaps.find(definition->getParentOfType<mlir::ModuleOp>().getOperation());
    if (failed(renderDefinition(definition, requiresAlias ? aliasHelper : "",
                                sourceMap == sourceMaps.end() ? nullptr : &sourceMap->second,
                                output)))
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

// Structural scopes are reparsed through synthetic module wrappers. Share only
// their resource accounting with the original module; lexical symbols and the
// helper declaration proof are reconstructed independently in every wrapper.
static LogicalResult reparseBackendTargetWithBudgets(
    llvm::StringRef candidate, const BackendConfiguration &configuration,
    std::map<std::string, std::pair<uint64_t, uint64_t>> &privateBudgets) {
  llvm::StringMap<unsigned> userFunctions;
  AnalogEventPrivateFunctions privateFunctions;
  // Generic continuous-expression recognizers intentionally accept a broader
  // subset. They must never become a second route for the event-only effect ABI.
  auto containsPrivateEventCall = [&](llvm::StringRef text) {
    bool quoted = false;
    for (size_t position = 0; position < text.size();) {
      const char c = text[position];
      if (c == '"') {
        quoted = !quoted;
        ++position;
      } else if (quoted) {
        position += c == '\\' && position + 1 < text.size() ? 2 : 1;
      } else if (llvm::isAlpha(c) || c == '_' || c == '$') {
        const size_t start = position++;
        while (position < text.size() &&
               (llvm::isAlnum(text[position]) || text[position] == '_' || text[position] == '$'))
          ++position;
        auto name = text.slice(start, position);
        if (name.starts_with("event_live_store") && !userFunctions.contains(name) &&
            text.drop_front(position).ltrim().starts_with("("))
          return true;
      } else {
        ++position;
      }
    }
    return false;
  };
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

    if (!code.ends_with(";") || failed(reparseAnalogContextQueries(code, false)))
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
    auto validArgument = [&](llvm::StringRef argument) {
      argument = argument.trim();
      auto context = reparseAnalogContextQueries(argument, true);
      return !argument.empty() && succeeded(context);
    };
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
        if (!validArgument(arguments.slice(start, index)))
          return false;
        start = index + 1;
        ++count;
      }
    }
    return !depth && validArgument(arguments.drop_front(start)) && count >= minimum &&
           count <= maximum;
  };
  llvm::StringSet<> realNames;
  llvm::StringSet<> assignedRealNames;
  AnalogEventSymbols eventSymbols;
  std::vector<AnalogEventSymbols> eventSymbolScopes;
  llvm::StringSet<> generatedNames;
  llvm::StringSet<> generatedLabels;
  llvm::StringSet<> namedBranchNames;
  struct StructuralParameter {
    bool integer;
    bool fixed;
    size_t order;
    std::string initializer;
  };
  llvm::StringMap<StructuralParameter> structuralParameters;
  llvm::StringMap<std::optional<int64_t>> structuralConstants;
  std::set<std::string> structuralIntegerNames;
  // Only an actual structural use opts a fixed parameter into this arithmetic
  // subset. Other scalar parameter expressions retain their existing grammar.
  // Ordinary parameter defaults are never values in this derived constant map.
  auto structuralInteger = [&](llvm::StringRef name, size_t useOrder,
                               std::optional<int64_t> &value) {
    auto declaration = structuralParameters.find(name);
    if (declaration == structuralParameters.end() || !declaration->second.integer ||
        declaration->second.order >= useOrder)
      return false;
    value.reset();
    if (!declaration->second.fixed)
      return true;
    // An explicit postorder stack keeps a deep stage chain linear without
    // using C++ recursion or substituting an earlier expression's source.
    llvm::SmallVector<std::pair<std::string, bool>> pending{{name.str(), false}};
    while (!pending.empty()) {
      auto [current, expanded] = pending.pop_back_val();
      if (structuralConstants.contains(current))
        continue;
      const auto &record = structuralParameters.find(current)->second;
      llvm::SmallVector<std::string> dependencies;
      GeneratedBoundExpressionParser initializer(
          record.initializer, structuralIntegerNames,
          [&](llvm::StringRef dependency, std::optional<int64_t> &constant) {
            auto input = structuralParameters.find(dependency);
            if (input == structuralParameters.end() || !input->second.integer ||
                input->second.order >= record.order)
              return false;
            constant.reset();
            if (!input->second.fixed)
              return true;
            auto known = structuralConstants.find(dependency);
            if (known != structuralConstants.end()) {
              constant = known->second;
              return true;
            }
            dependencies.push_back(dependency.str());
            return !expanded;
          },
          true);
      if (!initializer.parse())
        return false;
      if (dependencies.empty())
        structuralConstants.try_emplace(current, initializer.constantValue());
      else {
        pending.emplace_back(current, true);
        for (const auto &dependency : llvm::reverse(dependencies))
          pending.emplace_back(dependency, false);
      }
    }
    value = structuralConstants.find(name)->second;
    return true;
  };
  using ConcreteArrayBounds = std::pair<std::optional<int64_t>, std::optional<int64_t>>;
  llvm::StringMap<ConcreteArrayBounds> arrayBounds;
  std::vector<HierarchyModuleSyntax> hierarchyModules;
  // Render-local syntax context only. The scoped hierarchy parser delegates its
  // analog payload back to this same grammar, with the actual visible declarations.
  std::map<std::string, std::vector<std::string>> analogContextDeclarations;
  std::map<std::string, std::vector<std::string>> parameterDeclarations;
  std::optional<std::size_t> currentHierarchyModule;
  // This structural target gate proves concrete bounds and validates symbolic
  // syntax/identity. Correlated symbolic bounds are proved per axis by the native
  // shape owner and checked again before row-major target arithmetic is emitted.
  auto validEndpoint = [&](llvm::StringRef text, bool allowBranch = false,
                           bool allowGenerated = false, bool allowAggregate = false) {
    if (!currentHierarchyModule)
      return false;
    auto endpoint = parseHierarchyEndpoint(text.str());
    if (!endpoint || !isPortableVerilogIdentifier(endpoint->name))
      return false;
    auto array = arrayBounds.find(endpoint->name);
    if (endpoint->index.empty())
      return (array == arrayBounds.end() || allowAggregate) &&
             (hierarchyModules[*currentHierarchyModule].nodes.count(endpoint->name) ||
              (allowBranch && namedBranchNames.contains(endpoint->name)));
    if (array == arrayBounds.end() || (!endpoint->partSelectEnd.empty() && !allowAggregate))
      return false;
    auto parameters = hierarchyModules[*currentHierarchyModule].parameters;
    if (allowGenerated)
      for (const auto &name : generatedNames)
        parameters.insert(name.getKey().str());
    GeneratedBoundExpressionParser index(
        endpoint->index, parameters, [&](llvm::StringRef name, std::optional<int64_t> &value) {
          if (allowGenerated && generatedNames.contains(name)) {
            value.reset();
            return true;
          }
          return structuralInteger(name, structuralParameters.size(), value);
        });
    if (!index.parse())
      return false;
    auto value = index.constantValue();
    if (value && ((array->second.first && *value < *array->second.first) ||
                  (array->second.second && *value > *array->second.second)))
      return false;
    if (endpoint->partSelectEnd.empty())
      return true;
    GeneratedBoundExpressionParser end(endpoint->partSelectEnd, parameters,
                                       [&](llvm::StringRef name, std::optional<int64_t> &constant) {
                                         return structuralInteger(name, structuralParameters.size(),
                                                                  constant);
                                       });
    if (!end.parse())
      return false;
    auto last = end.constantValue();
    return !last || ((!value || *last >= *value) &&
                     (!array->second.first || *last >= *array->second.first) &&
                     (!array->second.second || *last <= *array->second.second));
  };
  auto validSelectedEndpoints = [&](llvm::StringRef statement, bool allowGenerated) {
    bool quoted = false;
    for (size_t position = 0; position < statement.size(); ++position) {
      if (quoted && statement[position] == '\\') {
        if (++position == statement.size())
          return false;
        continue;
      }
      if (statement[position] == '"') {
        quoted = !quoted;
        continue;
      }
      if (quoted)
        continue;
      if (statement[position] == ']')
        return false;
      if (statement[position] == '[') {
        size_t start = position;
        while (start && (llvm::isAlnum(statement[start - 1]) || statement[start - 1] == '_' ||
                         statement[start - 1] == '$'))
          --start;
        size_t close = statement.find(']', position + 1);
        if (start == position || close == llvm::StringRef::npos ||
            !validEndpoint(statement.slice(start, close + 1), false, allowGenerated))
          return false;
        position = close;
      }
    }
    return !quoted;
  };
  auto validElectricalAccesses = [&](llvm::StringRef statement, bool allowGenerated) {
    bool quoted = false;
    for (size_t position = 0; position < statement.size(); ++position) {
      if (quoted && statement[position] == '\\') {
        if (++position == statement.size())
          return false;
        continue;
      }
      if (statement[position] == '"') {
        quoted = !quoted;
        continue;
      }
      if (quoted || (statement[position] != 'V' && statement[position] != 'I') ||
          (position && (llvm::isAlnum(statement[position - 1]) || statement[position - 1] == '_' ||
                        statement[position - 1] == '$')) ||
          position + 1 == statement.size() || statement[position + 1] != '(')
        continue;
      size_t close = position + 2;
      unsigned depth = 1;
      for (; close < statement.size() && depth; ++close) {
        if (statement[close] == '(')
          ++depth;
        else if (statement[close] == ')')
          --depth;
      }
      if (depth)
        return false;
      auto arguments = statement.slice(position + 2, close - 1).trim();
      if (statement[position] == 'I' && arguments.starts_with("<") && arguments.ends_with(">")) {
        auto port = arguments.drop_front().drop_back();
        auto endpoint = parseHierarchyEndpoint(port.str());
        if (!currentHierarchyModule || !endpoint ||
            !hierarchyModules[*currentHierarchyModule].portDirections.count(endpoint->name) ||
            !validEndpoint(port, false, allowGenerated))
          return false;
      } else {
        llvm::SmallVector<llvm::StringRef, 2> endpoints;
        arguments.split(endpoints, ',', -1, true);
        if (endpoints.empty() || endpoints.size() > 2 ||
            !llvm::all_of(endpoints, [&](llvm::StringRef endpoint) {
              return validEndpoint(endpoint.trim(), endpoints.size() == 1, allowGenerated);
            }))
          return false;
      }
      position = close - 1;
    }
    return !quoted;
  };
  auto eventDeclaration = [&](llvm::StringRef line) {
    if ((!line.starts_with("real event_") && !line.starts_with("integer event_") &&
         !line.starts_with("genvar event_")) ||
        !line.ends_with(";"))
      return false;
    return succeeded(reparseAnalogEventDeclaration(line, eventSymbols));
  };

  llvm::SmallVector<llvm::StringRef, 64> lines;
  candidate.split(lines, '\n', -1, true);
  bool insideModule = false;
  bool insideAnalog = false;
  size_t generatedDepth = 0;
  bool generatedStatement = false;
  bool sawModule = false;
  for (size_t lineIndex = 0; lineIndex < lines.size(); ++lineIndex) {
    llvm::StringRef line = lines[lineIndex].trim();
    if (line.empty() || line.starts_with("//") || line.starts_with("/*") || line.starts_with("*") ||
        line.starts_with("`include "))
      continue;
    if (!insideModule && line.starts_with("module ") && line.ends_with(";")) {
      auto header = parseHierarchyModuleHeader(line.str());
      if (!header)
        return failure();
      HierarchyModuleSyntax hierarchy;
      hierarchy.name = header->name;
      hierarchy.ports = header->ports;
      hierarchy.portAliases = header->portAliases;
      for (const auto &[port, internal] : header->portAliases)
        hierarchy.nodes.insert(internal);
      if (header->portAliases.empty())
        hierarchy.nodes = header->ports;
      hierarchyModules.push_back(std::move(hierarchy));
      currentHierarchyModule = hierarchyModules.size() - 1;
      realNames.clear();
      assignedRealNames.clear();
      eventSymbols.clear();
      eventSymbolScopes.clear();
      generatedNames.clear();
      generatedLabels.clear();
      namedBranchNames.clear();
      structuralParameters.clear();
      structuralConstants.clear();
      structuralIntegerNames.clear();
      arrayBounds.clear();
      userFunctions.clear();
      privateFunctions = {};
      auto &budget = privateBudgets[hierarchyModules[*currentHierarchyModule].name];
      privateFunctions.demandWrappers = budget.first;
      privateFunctions.expressionBytes = budget.second;
      insideModule = true;
      sawModule = true;
      continue;
    }
    if (!insideModule)
      return failure();
    if (line.starts_with("analog function ")) {
      if (insideAnalog)
        return failure();
      hierarchyModules[*currentHierarchyModule].hasBehavior = true;
      std::string remaining;
      for (size_t i = lineIndex; i < lines.size(); ++i)
        remaining += lines[i].str() + "\n";
      auto consumed = reparseAnalogEventPrivateFunction(remaining, privateFunctions);
      if (failed(consumed))
        consumed = reparseAnalogUserFunction(remaining, userFunctions);
      if (failed(consumed) || !*consumed)
        return failure();
      for (const auto &function : privateFunctions.names)
        if (userFunctions.contains(function.getKey()))
          return failure();
      auto count = llvm::count(llvm::StringRef(remaining).take_front(*consumed), '\n');
      if (!count)
        return failure();
      analogContextDeclarations[hierarchyModules[*currentHierarchyModule].name].push_back(
          remaining.substr(0, *consumed));
      lineIndex += count - 1;
      continue;
    }
    if (line == "analog begin") {
      if (insideAnalog)
        return failure();
      insideAnalog = true;
      hierarchyModules[*currentHierarchyModule].hasBehavior = true;
      continue;
    }
    if (line == "generate" && !insideAnalog) {
      std::string block;
      size_t end = lineIndex;
      for (; end < lines.size(); ++end) {
        block += lines[end].str() + "\n";
        if (lines[end].trim() == "endgenerate")
          break;
      }
      if (end == lines.size())
        return failure();
      // Find the analog block boundary lexically; all statements and expressions
      // are subsequently checked by the existing analog/event grammar below.
      auto scope =
          parseStructuralGenerate(block, [](std::string_view text) -> std::optional<size_t> {
            size_t position = 0;
            unsigned depth = 0;
            bool began = false;
            while (position < text.size()) {
              const char c = text[position];
              if (c == '\0')
                return std::nullopt;
              if (c == '"') {
                ++position;
                bool closed = false;
                while (position < text.size()) {
                  if (text[position] == '\\') {
                    position += 2;
                    continue;
                  }
                  if (text[position++] == '"') {
                    closed = true;
                    break;
                  }
                }
                if (!closed)
                  return std::nullopt;
              } else if (text.substr(position, 2) == "//") {
                position = text.find('\n', position);
                if (position == std::string_view::npos)
                  return std::nullopt;
              } else if (text.substr(position, 2) == "/*") {
                position = text.find("*/", position + 2);
                if (position == std::string_view::npos)
                  return std::nullopt;
                position += 2;
              } else if (llvm::isAlpha(c) || c == '_' || c == '$') {
                size_t start = position++;
                while (position < text.size() && (llvm::isAlnum(text[position]) ||
                                                  text[position] == '_' || text[position] == '$'))
                  ++position;
                auto token = text.substr(start, position - start);
                if (token == "begin") {
                  began = true;
                  ++depth;
                } else if (token == "end") {
                  if (!depth)
                    return std::nullopt;
                  if (--depth == 0)
                    return began ? std::optional<size_t>(position) : std::nullopt;
                }
              } else {
                ++position;
              }
            }
            return std::nullopt;
          });
      if (!scope)
        return failure();
      hierarchyModules[*currentHierarchyModule].generatedScopes.push_back(std::move(*scope));
      lineIndex = end;
      continue;
    }
    if (line == "end") {
      if (!insideAnalog)
        return failure();
      if (generatedDepth) {
        --generatedDepth;
        if (eventSymbolScopes.empty())
          return failure();
        eventSymbols = std::move(eventSymbolScopes.back());
        eventSymbolScopes.pop_back();
      } else
        insideAnalog = false;
      continue;
    }
    if (line == "endmodule") {
      if (insideAnalog || assignedRealNames.size() != realNames.size())
        return failure();
      const auto &hierarchy = hierarchyModules[*currentHierarchyModule];
      for (const auto &function : privateFunctions.names)
        if (eventSymbols.contains(function.getKey()) ||
            llvm::any_of(hierarchy.instances, [&](const HierarchyInstanceSyntax &instance) {
              return instance.name == function.getKey();
            }))
          return failure();
      privateBudgets[hierarchyModules[*currentHierarchyModule].name] = {
          privateFunctions.demandWrappers, privateFunctions.expressionBytes};
      insideModule = false;
      currentHierarchyModule.reset();
      continue;
    }
    if (insideAnalog) {
      if (line.starts_with("for (")) {
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
        eventSymbolScopes.push_back(eventSymbols);
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
        auto consumed = reparseAnalogEventBlock(remaining, &eventSymbols, &privateFunctions);
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
      if (containsPrivateEventCall(line) || !line.ends_with(";") ||
          !validSelectedEndpoints(line, generatedDepth != 0) ||
          !validElectricalAccesses(line, generatedDepth != 0))
        return failure();
      auto context = reparseAnalogContextQueries(line, true);
      if (failed(context))
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
    if (containsPrivateEventCall(line))
      return failure();
    if (line.starts_with("real event_") || line.starts_with("integer event_")) {
      if (!eventDeclaration(line))
        return failure();
      hierarchyModules[*currentHierarchyModule].hasBehavior = true;
      analogContextDeclarations[hierarchyModules[*currentHierarchyModule].name].push_back(
          line.str());
      continue;
    }
    if (line.starts_with("genvar ") && line.ends_with(";")) {
      auto identifier = line.drop_front(sizeof("genvar ") - 1).drop_back().trim();
      if (!isPortableVerilogIdentifier(identifier) || !generatedNames.insert(identifier).second ||
          !eventSymbols
               .try_emplace(identifier, AnalogEventSymbol{AnalogEventSymbolKind::Integer, true})
               .second)
        return failure();
      hierarchyModules[*currentHierarchyModule].genvars.insert(identifier.str());
      continue;
    }
    if (line.starts_with("real ") && line.ends_with(";")) {
      hierarchyModules[*currentHierarchyModule].hasBehavior = true;
      auto identifier = line.drop_front(5).drop_back().trim();
      if (identifier.contains(',') || !validIdentifierList(identifier) ||
          !realNames.insert(identifier).second)
        return failure();
      eventSymbols.try_emplace(identifier, AnalogEventSymbol{AnalogEventSymbolKind::Real, true});
      continue;
    }
    if (line.starts_with("branch (") && line.ends_with(";")) {
      hierarchyModules[*currentHierarchyModule].hasBehavior = true;
      llvm::StringRef declaration = line.drop_front(sizeof("branch (") - 1).drop_back();
      size_t close = declaration.rfind(") ");
      if (close == llvm::StringRef::npos)
        return failure();
      llvm::SmallVector<llvm::StringRef, 2> endpoints;
      declaration.take_front(close).split(endpoints, ',', -1, true);
      llvm::StringRef name = declaration.drop_front(close + 2).trim();
      if (endpoints.size() != 2 || !isPortableVerilogIdentifier(name) ||
          !namedBranchNames.insert(name).second ||
          !llvm::all_of(endpoints,
                        [&](llvm::StringRef endpoint) { return validEndpoint(endpoint.trim()); }))
        return failure();
      eventSymbols.try_emplace(name, AnalogEventSymbol{AnalogEventSymbolKind::Terminal, false});
      analogContextDeclarations[hierarchyModules[*currentHierarchyModule].name].push_back(
          line.str());
      continue;
    }
    if ((line.starts_with("input ") || line.starts_with("output ") || line.starts_with("inout ") ||
         line.starts_with("electrical ")) &&
        line.ends_with(";")) {
      auto &hierarchy = hierarchyModules[*currentHierarchyModule];
      const auto kind = line.take_front(line.find(' '));
      const bool electrical = kind == "electrical";
      auto declaration = line.drop_front(line.find(' ') + 1).drop_back().trim();
      std::optional<HierarchyVectorSyntax> vector;
      ConcreteArrayBounds concrete;
      if (declaration.starts_with("[")) {
        size_t close = declaration.find(']');
        if (close == llvm::StringRef::npos)
          return failure();
        auto range = declaration.slice(1, close).split(':');
        if (range.first.empty() || range.second.empty() || range.second.contains(':'))
          return failure();
        auto resolveInteger = [&](llvm::StringRef name, std::optional<int64_t> &value) {
          return structuralInteger(name, structuralParameters.size(), value);
        };
        GeneratedBoundExpressionParser lower(range.first.trim(), hierarchy.parameters,
                                             resolveInteger);
        GeneratedBoundExpressionParser upper(range.second.trim(), hierarchy.parameters,
                                             resolveInteger);
        if (!lower.parse() || !upper.parse() ||
            (lower.constantValue() && upper.constantValue() &&
             *lower.constantValue() > *upper.constantValue()))
          return failure();
        vector = HierarchyVectorSyntax{range.first.trim().str(), range.second.trim().str()};
        concrete = {lower.constantValue(), upper.constantValue()};
        declaration = declaration.drop_front(close + 1).trim();
        if (!isPortableVerilogIdentifier(declaration))
          return failure();
      }
      if (!validIdentifierList(declaration))
        return failure();
      llvm::SmallVector<llvm::StringRef, 8> names;
      declaration.split(names, ',', -1, true);
      for (llvm::StringRef raw : names) {
        const std::string name = raw.trim().str();
        auto known = hierarchy.vectors.find(name);
        const bool declaredScalar =
            known == hierarchy.vectors.end() &&
            (hierarchy.electricalNodes.count(name) || hierarchy.portDirections.count(name));
        if (vector && declaredScalar)
          return failure();
        if (electrical) {
          if (!hierarchy.electricalNodes.insert(name).second)
            return failure();
          hierarchy.nodes.insert(name);
        } else {
          if (!hierarchy.nodes.count(name) ||
              !hierarchy.portDirections.emplace(name, kind.str()).second)
            return failure();
        }
        if (vector) {
          if (known != hierarchy.vectors.end() &&
              (known->second.lower != vector->lower || known->second.upper != vector->upper))
            return failure();
          hierarchy.vectors.emplace(name, *vector);
          hierarchy.arrays.insert(name);
          arrayBounds.try_emplace(name, concrete);
          eventSymbols.try_emplace(name, AnalogEventSymbol{AnalogEventSymbolKind::TerminalArray,
                                                           false, concrete.first, concrete.second});
        } else {
          if (known != hierarchy.vectors.end())
            return failure();
          eventSymbols.try_emplace(name, AnalogEventSymbol{AnalogEventSymbolKind::Terminal, false});
        }
      }
      continue;
    }
    if (validParameterDeclaration(line)) {
      parameterDeclarations[hierarchyModules[*currentHierarchyModule].name].push_back(line.str());
      auto parameter = parameterDeclarationName(line);
      if (!parameter)
        return failure();
      auto initializer = line.drop_front(line.find(" = ") + 3);
      initializer = initializer.take_front(initializer.find(';'));
      initializer = initializer.take_front(initializer.find(" from "));
      initializer = initializer.take_front(initializer.find(" exclude ")).trim();
      const bool integer = line.contains(" integer ");
      if (!structuralParameters
               .try_emplace(parameter->first,
                            StructuralParameter{integer, parameter->second,
                                                structuralParameters.size(), initializer.str()})
               .second)
        return failure();
      if (integer) {
        structuralIntegerNames.insert(parameter->first);
        hierarchyModules[*currentHierarchyModule].integerDeclarations.push_back(
            {parameter->first, initializer.str(), parameter->second});
      }
      hierarchyModules[*currentHierarchyModule].parameters.insert(parameter->first);
      eventSymbols.try_emplace(parameter->first,
                               AnalogEventSymbol{line.contains(" integer ")
                                                     ? AnalogEventSymbolKind::Integer
                                                     : AnalogEventSymbolKind::Real,
                                                 false});
      if (parameter->second)
        hierarchyModules[*currentHierarchyModule].fixedParameters.insert(parameter->first);
      continue;
    }
    if (auto instance = parseHierarchyInstance(line.str())) {
      for (const auto &[port, actual] : instance->ports) {
        (void)port;
        if (!validEndpoint(actual, false, false, true))
          return failure();
      }
      hierarchyModules[*currentHierarchyModule].instances.push_back(std::move(*instance));
      continue;
    }
    return failure();
  }
  auto validateScopedAnalog = [&](std::string_view text, const HierarchyModuleSyntax &visible,
                                  const std::vector<std::string> &declarations) {
    std::string wrapper;
    llvm::raw_string_ostream output(wrapper);
    output << "module " << visible.name;
    if (!visible.ports.empty()) {
      output << "(";
      llvm::interleaveComma(visible.ports, output);
      output << ")";
    }
    output << ";\n";
    std::set<std::string> parameters;
    for (const auto &declaration : parameterDeclarations[visible.name]) {
      auto parameter = parameterDeclarationName(declaration);
      if (!parameter)
        return false;
      parameters.insert(parameter->first);
      output << declaration << "\n";
    }
    // An active genvar is a symbolic integer constant in each occurrence. Its
    // exact domain is checked by the enclosing structural grammar, never by a
    // substituted default in this analog-only syntax context.
    for (const auto &name : visible.genvars) {
      if (!parameters.insert(name).second)
        return false;
      output << "parameter integer " << name << " = 0;\n";
    }
    for (const auto &parameter : visible.integerDeclarations)
      if (parameters.insert(parameter.name).second)
        output << (parameter.fixed ? "localparam" : "parameter") << " integer " << parameter.name
               << " = " << parameter.initializer << ";\n";
    for (const auto &name : visible.nodes) {
      const auto vector = visible.vectors.find(name);
      std::string range;
      if (vector != visible.vectors.end())
        range = "[" + vector->second.lower + ":" + vector->second.upper + "] ";
      if (auto direction = visible.portDirections.find(name);
          direction != visible.portDirections.end())
        output << direction->second << " " << range << name << ";\n";
      output << "electrical " << range << name << ";\n";
    }
    for (const auto &declaration : analogContextDeclarations[visible.name])
      output << declaration << "\n";
    for (const auto &declaration : declarations)
      output << declaration << "\n";
    output << text << "\nendmodule\n";
    output.flush();
    return succeeded(reparseBackendTargetWithBudgets(wrapper, configuration, privateBudgets));
  };
  return sawModule && !insideModule && !insideAnalog && !generatedDepth &&
                 verifyHierarchySyntax(hierarchyModules, validateScopedAnalog)
             ? success()
             : failure();
}

LogicalResult reparseBackendTarget(llvm::StringRef candidate,
                                   const BackendConfiguration &configuration) {
  std::map<std::string, std::pair<uint64_t, uint64_t>> privateBudgets;
  return reparseBackendTargetWithBudgets(candidate, configuration, privateBudgets);
}

} // namespace nodal
