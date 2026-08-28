#include "nodal/Dialect/Nodal/AnalogNumeric.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinTypes.h"
#include "mlir/IR/SymbolTable.h"
#include "nodal/Diagnostics/DiagnosticMapping.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/APInt.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/SmallString.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringExtras.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/ADT/Twine.h"
#include "llvm/Support/Casting.h"

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <map>
#include <optional>
#include <string>

using namespace mlir;

namespace nodal {
namespace {

using DimensionMap = std::map<std::string, int64_t>;

enum class EvaluationStatus {
  Constant,
  Dynamic,
  Error,
};

struct ConstantValue {
  AnalogNumericKind kind = AnalogNumericKind::Invalid;
  std::string dimension;
  std::optional<llvm::APInt> integer;
  double real = 0.0;
  bool boolean = false;
};

struct EvaluationResult {
  EvaluationStatus status = EvaluationStatus::Dynamic;
  ConstantValue value;
};

bool isNamed(Operation *operation, llvm::StringRef name) {
  return operation && operation->getName().getStringRef() == name;
}

llvm::StringRef textAttr(Operation *operation, llvm::StringRef name) {
  if (auto value = operation->getAttrOfType<StringAttr>(name))
    return value.getValue();
  return {};
}

bool isDimensionAtom(llvm::StringRef atom) {
  if (atom.empty() || !(llvm::isAlpha(atom.front()) || atom.front() == '_'))
    return false;
  return llvm::all_of(atom.drop_front(), [](char character) {
    return llvm::isAlnum(character) || character == '_' || character == '.';
  });
}

std::string formatDimension(const DimensionMap &dimension) {
  if (dimension.empty())
    return "1";
  std::string result;
  for (const auto &[atom, exponent] : dimension) {
    if (!result.empty())
      result += "*";
    result += atom;
    if (exponent != 1) {
      result += "^";
      result += std::to_string(exponent);
    }
  }
  return result;
}

FailureOr<DimensionMap> parseDimension(llvm::StringRef signature, bool requireCanonical) {
  signature = signature.trim();
  if (signature == "1")
    return DimensionMap{};
  if (signature.empty())
    return failure();

  DimensionMap result;
  llvm::SmallVector<llvm::StringRef, 8> factors;
  signature.split(factors, '*', -1, false);
  if (factors.empty())
    return failure();

  for (llvm::StringRef factor : factors) {
    if (factor.empty() || factor != factor.trim())
      return failure();
    llvm::StringRef atom = factor;
    int64_t exponent = 1;
    const size_t caret = factor.find('^');
    if (caret != llvm::StringRef::npos) {
      if (factor.drop_front(caret + 1).contains('^'))
        return failure();
      atom = factor.take_front(caret);
      llvm::StringRef exponentText = factor.drop_front(caret + 1);
      if (exponentText.empty() || exponentText.getAsInteger(10, exponent) || exponent == 0)
        return failure();
      if (requireCanonical && exponent == 1)
        return failure();
    }
    if (!isDimensionAtom(atom) || result.count(atom.str()) != 0)
      return failure();
    result.emplace(atom.str(), exponent);
  }

  if (requireCanonical && formatDimension(result) != signature)
    return failure();
  return result;
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

llvm::StringRef symbolName(Operation *operation) {
  if (auto value = operation->getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName()))
    return value.getValue();
  return {};
}

Operation *findParameter(Operation *operation, llvm::StringRef name) {
  Block *body = moduleBody(enclosingNodalModule(operation));
  if (!body)
    return nullptr;
  for (Operation &candidate : *body) {
    if (isNamed(&candidate, "nodal.parameter") && symbolName(&candidate) == name)
      return &candidate;
  }
  return nullptr;
}

Operation *findTopLevelUnit(Operation *operation, llvm::StringRef name) {
  mlir::ModuleOp module = operation->getParentOfType<mlir::ModuleOp>();
  if (!module)
    return nullptr;
  for (Operation &candidate : module.getBody()->getOperations()) {
    if (isNamed(&candidate, "nodal.unit") && symbolName(&candidate) == name)
      return &candidate;
  }
  return nullptr;
}

FailureOr<std::string> parameterDimension(Operation *parameter) {
  auto reference = parameter->getAttrOfType<FlatSymbolRefAttr>("unit");
  if (!reference)
    return std::string("1");
  Operation *unit = findTopLevelUnit(parameter, reference.getValue());
  if (!unit)
    return failure();
  llvm::StringRef dimension = textAttr(unit, "dimension");
  if (!isCanonicalDimensionSignature(dimension))
    return failure();
  return dimension.str();
}

FailureOr<double> parameterScale(Operation *parameter) {
  auto reference = parameter->getAttrOfType<FlatSymbolRefAttr>("unit");
  if (!reference)
    return 1.0;
  Operation *unit = findTopLevelUnit(parameter, reference.getValue());
  auto scale = unit ? unit->getAttrOfType<FloatAttr>("scale") : FloatAttr();
  if (!scale || !std::isfinite(scale.getValueAsDouble()) || scale.getValueAsDouble() <= 0.0)
    return failure();
  return scale.getValueAsDouble();
}

bool semanticTypeMatches(Type type, AnalogNumericKind kind, llvm::StringRef dimension) {
  auto information = getAnalogNumericTypeInfo(type);
  return succeeded(information) && information->kind == kind && information->dimension == dimension;
}

FailureOr<AnalogNumericKind> promoteNumericKinds(AnalogNumericKind lhs, AnalogNumericKind rhs) {
  if ((lhs != AnalogNumericKind::Integer && lhs != AnalogNumericKind::Real) ||
      (rhs != AnalogNumericKind::Integer && rhs != AnalogNumericKind::Real))
    return failure();
  if (lhs == AnalogNumericKind::Real || rhs == AnalogNumericKind::Real)
    return AnalogNumericKind::Real;
  return AnalogNumericKind::Integer;
}

EvaluationResult dynamicResult() { return {}; }

EvaluationResult constantResult(ConstantValue value) {
  EvaluationResult result;
  result.status = EvaluationStatus::Constant;
  result.value = std::move(value);
  return result;
}

EvaluationResult errorResult(Operation *operation, llvm::StringRef code, const llvm::Twine &message,
                             bool reportErrors) {
  if (reportErrors)
    (void)emitMappedFailure(operation, code, message);
  EvaluationResult result;
  result.status = EvaluationStatus::Error;
  return result;
}

std::pair<llvm::APInt, llvm::APInt> alignSigned(const llvm::APInt &lhs, const llvm::APInt &rhs,
                                                unsigned extraBits = 1) {
  const unsigned width = std::max(lhs.getBitWidth(), rhs.getBitWidth()) + extraBits;
  return {lhs.sextOrTrunc(width), rhs.sextOrTrunc(width)};
}

std::optional<double> integerAsDouble(const llvm::APInt &value) {
  llvm::SmallString<128> spelling;
  value.toString(spelling, 10, true);
  std::string storage = spelling.str().str();
  char *end = nullptr;
  errno = 0;
  double result = std::strtod(storage.c_str(), &end);
  if (errno == ERANGE || end != storage.c_str() + storage.size() || !std::isfinite(result))
    return std::nullopt;
  return result;
}

std::optional<double> numericAsDouble(const ConstantValue &value) {
  if (value.kind == AnalogNumericKind::Real)
    return value.real;
  if (value.kind == AnalogNumericKind::Integer && value.integer)
    return integerAsDouble(*value.integer);
  return std::nullopt;
}

bool integerDivisionIsExact(const ConstantValue &lhs, const ConstantValue &rhs) {
  if (lhs.kind != AnalogNumericKind::Integer || rhs.kind != AnalogNumericKind::Integer ||
      !lhs.integer || !rhs.integer || rhs.integer->isZero())
    return false;
  auto [left, right] = alignSigned(*lhs.integer, *rhs.integer);
  return left.srem(right).isZero();
}

EvaluationResult evaluateValue(Value value, bool reportErrors);

EvaluationResult evaluateParameterReference(Operation *operation, bool reportErrors) {
  auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("parameter");
  if (!reference)
    return dynamicResult();
  Operation *parameter = findParameter(operation, reference.getValue());
  if (!parameter)
    return dynamicResult();
  auto variability = parameter->getAttrOfType<StringAttr>("variability");
  if (!variability || variability.getValue() != "fixed")
    return dynamicResult();

  auto resultInformation = getAnalogNumericTypeInfo(operation->getResult(0).getType());
  if (failed(resultInformation))
    return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                       "fixed parameter reference has no foldable semantic type", reportErrors);

  ConstantValue result;
  result.kind = resultInformation->kind;
  result.dimension = resultInformation->dimension;
  Attribute valueAttribute = parameter->getAttr("default_value");
  if (result.kind == AnalogNumericKind::Real) {
    auto value = llvm::dyn_cast_or_null<FloatAttr>(valueAttribute);
    auto scale = parameterScale(parameter);
    if (!value || failed(scale) || !std::isfinite(value.getValueAsDouble()))
      return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                         "fixed real parameter has no finite canonical default or unit scale",
                         reportErrors);
    result.real = value.getValueAsDouble() * *scale;
    if (!std::isfinite(result.real))
      return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                         "fixed real parameter scale produced a non-finite result", reportErrors);
    return constantResult(std::move(result));
  }
  if (result.kind == AnalogNumericKind::Integer) {
    auto value = llvm::dyn_cast_or_null<IntegerAttr>(valueAttribute);
    if (!value)
      return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                         "fixed integer parameter has no integer canonical default", reportErrors);
    result.integer = value.getValue();
    return constantResult(std::move(result));
  }
  if (result.kind == AnalogNumericKind::Boolean) {
    if (auto value = llvm::dyn_cast_or_null<BoolAttr>(valueAttribute)) {
      result.boolean = value.getValue();
      return constantResult(std::move(result));
    }
    if (auto value = llvm::dyn_cast_or_null<IntegerAttr>(valueAttribute)) {
      if (value.getValue().isZero() || value.getValue().isOne()) {
        result.boolean = value.getValue().isOne();
        return constantResult(std::move(result));
      }
    }
    return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                       "fixed Boolean parameter has no canonical Boolean default", reportErrors);
  }
  return dynamicResult();
}

EvaluationResult evaluateBinary(Operation *operation, bool reportErrors) {
  EvaluationResult lhs = evaluateValue(operation->getOperand(0), reportErrors);
  EvaluationResult rhs = evaluateValue(operation->getOperand(1), reportErrors);
  if (lhs.status == EvaluationStatus::Error || rhs.status == EvaluationStatus::Error)
    return errorResult(operation, "NODAL-ANALOG-FOLD-001", "operand constant evaluation failed",
                       reportErrors);
  if (lhs.status != EvaluationStatus::Constant || rhs.status != EvaluationStatus::Constant)
    return dynamicResult();

  auto resultInformation = getAnalogNumericTypeInfo(operation->getResult(0).getType());
  if (failed(resultInformation))
    return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                       "arithmetic result has no semantic numeric type", reportErrors);

  ConstantValue result;
  result.kind = resultInformation->kind;
  result.dimension = resultInformation->dimension;
  llvm::StringRef name = operation->getName().getStringRef();

  if (name == "nodal.analog_div") {
    const bool zeroInteger = rhs.value.kind == AnalogNumericKind::Integer && rhs.value.integer &&
                             rhs.value.integer->isZero();
    const bool zeroReal = rhs.value.kind == AnalogNumericKind::Real && rhs.value.real == 0.0;
    if (zeroInteger || zeroReal)
      return errorResult(operation, "NODAL-ANALOG-DIVIDE-001", "statically known zero divisor",
                         reportErrors);
  }

  if (result.kind == AnalogNumericKind::Integer) {
    if (!lhs.value.integer || !rhs.value.integer)
      return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                         "integer result requires integer constant operands", reportErrors);
    if (name == "nodal.analog_add") {
      auto [left, right] = alignSigned(*lhs.value.integer, *rhs.value.integer);
      result.integer = left + right;
    } else if (name == "nodal.analog_sub") {
      auto [left, right] = alignSigned(*lhs.value.integer, *rhs.value.integer);
      result.integer = left - right;
    } else if (name == "nodal.analog_mul") {
      const unsigned width =
          lhs.value.integer->getBitWidth() + rhs.value.integer->getBitWidth() + 1;
      result.integer =
          lhs.value.integer->sextOrTrunc(width) * rhs.value.integer->sextOrTrunc(width);
    } else if (name == "nodal.analog_div") {
      auto [left, right] = alignSigned(*lhs.value.integer, *rhs.value.integer);
      if (!left.srem(right).isZero())
        return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                           "integer division result is not exact", reportErrors);
      result.integer = left.sdiv(right);
    } else {
      return dynamicResult();
    }
    return constantResult(std::move(result));
  }

  auto left = numericAsDouble(lhs.value);
  auto right = numericAsDouble(rhs.value);
  if (!left || !right)
    return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                       "real result requires numeric constant operands", reportErrors);
  if (name == "nodal.analog_add")
    result.real = *left + *right;
  else if (name == "nodal.analog_sub")
    result.real = *left - *right;
  else if (name == "nodal.analog_mul")
    result.real = *left * *right;
  else if (name == "nodal.analog_div")
    result.real = *left / *right;
  else
    return dynamicResult();
  if (!std::isfinite(result.real))
    return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                       "constant arithmetic produced a non-finite real result", reportErrors);
  return constantResult(std::move(result));
}

EvaluationResult evaluateCompare(Operation *operation, bool reportErrors) {
  EvaluationResult lhs = evaluateValue(operation->getOperand(0), reportErrors);
  EvaluationResult rhs = evaluateValue(operation->getOperand(1), reportErrors);
  if (lhs.status == EvaluationStatus::Error || rhs.status == EvaluationStatus::Error)
    return errorResult(operation, "NODAL-ANALOG-FOLD-001", "comparison operand evaluation failed",
                       reportErrors);
  if (lhs.status != EvaluationStatus::Constant || rhs.status != EvaluationStatus::Constant)
    return dynamicResult();

  llvm::StringRef predicate = textAttr(operation, "predicate");
  bool value = false;
  if (lhs.value.kind == AnalogNumericKind::Boolean &&
      rhs.value.kind == AnalogNumericKind::Boolean) {
    if (predicate == "eq")
      value = lhs.value.boolean == rhs.value.boolean;
    else if (predicate == "ne")
      value = lhs.value.boolean != rhs.value.boolean;
    else
      return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                         "ordering comparison cannot fold Boolean operands", reportErrors);
  } else if (lhs.value.kind == AnalogNumericKind::Integer &&
             rhs.value.kind == AnalogNumericKind::Integer && lhs.value.integer &&
             rhs.value.integer) {
    auto [left, right] = alignSigned(*lhs.value.integer, *rhs.value.integer);
    if (predicate == "eq")
      value = left == right;
    else if (predicate == "ne")
      value = left != right;
    else if (predicate == "lt")
      value = left.slt(right);
    else if (predicate == "le")
      value = left.sle(right);
    else if (predicate == "gt")
      value = left.sgt(right);
    else if (predicate == "ge")
      value = left.sge(right);
    else
      return dynamicResult();
  } else {
    auto left = numericAsDouble(lhs.value);
    auto right = numericAsDouble(rhs.value);
    if (!left || !right)
      return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                         "comparison operands are not numeric constants", reportErrors);
    if (predicate == "eq")
      value = *left == *right;
    else if (predicate == "ne")
      value = *left != *right;
    else if (predicate == "lt")
      value = *left < *right;
    else if (predicate == "le")
      value = *left <= *right;
    else if (predicate == "gt")
      value = *left > *right;
    else if (predicate == "ge")
      value = *left >= *right;
    else
      return dynamicResult();
  }

  ConstantValue result;
  result.kind = AnalogNumericKind::Boolean;
  result.dimension = "1";
  result.boolean = value;
  return constantResult(std::move(result));
}

EvaluationResult evaluateLogic(Operation *operation, bool reportErrors) {
  llvm::SmallVector<EvaluationResult, 2> operands;
  for (Value operand : operation->getOperands()) {
    operands.push_back(evaluateValue(operand, reportErrors));
    if (operands.back().status == EvaluationStatus::Error)
      return errorResult(operation, "NODAL-ANALOG-FOLD-001", "logical operand evaluation failed",
                         reportErrors);
    if (operands.back().status != EvaluationStatus::Constant)
      return dynamicResult();
  }

  llvm::StringRef operatorName = textAttr(operation, "operator_name");
  bool value = false;
  if (operatorName == "not")
    value = !operands.front().value.boolean;
  else if (operatorName == "and")
    value = operands[0].value.boolean && operands[1].value.boolean;
  else if (operatorName == "or")
    value = operands[0].value.boolean || operands[1].value.boolean;
  else if (operatorName == "xor")
    value = operands[0].value.boolean != operands[1].value.boolean;
  else
    return dynamicResult();

  ConstantValue result;
  result.kind = AnalogNumericKind::Boolean;
  result.dimension = "1";
  result.boolean = value;
  return constantResult(std::move(result));
}

EvaluationResult evaluateSelect(Operation *operation, bool reportErrors) {
  EvaluationResult condition = evaluateValue(operation->getOperand(0), reportErrors);
  EvaluationResult trueValue = evaluateValue(operation->getOperand(1), reportErrors);
  EvaluationResult falseValue = evaluateValue(operation->getOperand(2), reportErrors);
  if (condition.status == EvaluationStatus::Error || trueValue.status == EvaluationStatus::Error ||
      falseValue.status == EvaluationStatus::Error)
    return errorResult(operation, "NODAL-ANALOG-FOLD-001", "conditional operand evaluation failed",
                       reportErrors);
  if (condition.status != EvaluationStatus::Constant ||
      trueValue.status != EvaluationStatus::Constant ||
      falseValue.status != EvaluationStatus::Constant)
    return dynamicResult();

  EvaluationResult selected = condition.value.boolean ? trueValue : falseValue;
  auto resultInformation = getAnalogNumericTypeInfo(operation->getResult(0).getType());
  if (failed(resultInformation))
    return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                       "conditional result has no semantic type", reportErrors);
  if (selected.value.dimension != resultInformation->dimension)
    return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                       "conditional fold changed the result dimension", reportErrors);
  if (selected.value.kind == resultInformation->kind)
    return selected;
  if (selected.value.kind == AnalogNumericKind::Integer &&
      resultInformation->kind == AnalogNumericKind::Real && selected.value.integer) {
    auto promoted = integerAsDouble(*selected.value.integer);
    if (!promoted)
      return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                         "selected integer arm cannot be represented as a finite real",
                         reportErrors);
    selected.value.kind = AnalogNumericKind::Real;
    selected.value.integer.reset();
    selected.value.real = *promoted;
    return selected;
  }
  return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                     "conditional fold does not match the promoted result kind", reportErrors);
}

EvaluationResult evaluateValue(Value value, bool reportErrors) {
  Operation *operation = value.getDefiningOp();
  if (!operation)
    return dynamicResult();
  llvm::StringRef name = operation->getName().getStringRef();

  if (name == "nodal.real_literal") {
    auto literal = operation->getAttrOfType<FloatAttr>("value");
    auto information = getAnalogNumericTypeInfo(value.getType());
    if (!literal || failed(information) || !std::isfinite(literal.getValueAsDouble()))
      return errorResult(operation, "NODAL-ANALOG-FOLD-001", "real literal cannot be evaluated",
                         reportErrors);
    ConstantValue result;
    result.kind = AnalogNumericKind::Real;
    result.dimension = information->dimension;
    result.real = literal.getValueAsDouble();
    return constantResult(std::move(result));
  }
  if (name == "nodal.analog_integer_literal") {
    auto literal = operation->getAttrOfType<IntegerAttr>("value");
    auto information = getAnalogNumericTypeInfo(value.getType());
    if (!literal || failed(information))
      return errorResult(operation, "NODAL-ANALOG-FOLD-001", "integer literal cannot be evaluated",
                         reportErrors);
    ConstantValue result;
    result.kind = AnalogNumericKind::Integer;
    result.dimension = information->dimension;
    result.integer = literal.getValue();
    return constantResult(std::move(result));
  }
  if (name == "nodal.parameter_ref")
    return evaluateParameterReference(operation, reportErrors);
  if (name == "nodal.analog_add" || name == "nodal.analog_sub" || name == "nodal.analog_mul" ||
      name == "nodal.analog_div")
    return evaluateBinary(operation, reportErrors);
  if (name == "nodal.analog_neg") {
    EvaluationResult input = evaluateValue(operation->getOperand(0), reportErrors);
    if (input.status != EvaluationStatus::Constant)
      return input;
    if (input.value.kind == AnalogNumericKind::Integer && input.value.integer) {
      ConstantValue result = input.value;
      const unsigned width = input.value.integer->getBitWidth() + 1;
      result.integer = -input.value.integer->sextOrTrunc(width);
      return constantResult(std::move(result));
    }
    if (input.value.kind == AnalogNumericKind::Real) {
      ConstantValue result = input.value;
      result.real = -result.real;
      if (!std::isfinite(result.real))
        return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                           "constant negation produced a non-finite result", reportErrors);
      return constantResult(std::move(result));
    }
    return dynamicResult();
  }
  if (name == "nodal.analog_compare")
    return evaluateCompare(operation, reportErrors);
  if (name == "nodal.analog_logic")
    return evaluateLogic(operation, reportErrors);
  if (name == "nodal.analog_select")
    return evaluateSelect(operation, reportErrors);
  return dynamicResult();
}

constexpr llvm::StringLiteral kFoldAttributes[] = {
    "nodal.folded",       "nodal.folded_kind",       "nodal.folded_dimension",
    "nodal.folded_value", "nodal.folded_provenance",
};

bool isFoldCandidate(Operation *operation) {
  llvm::StringRef name = operation->getName().getStringRef();
  return name == "nodal.analog_add" || name == "nodal.analog_sub" || name == "nodal.analog_mul" ||
         name == "nodal.analog_div" || name == "nodal.analog_neg" ||
         name == "nodal.analog_compare" || name == "nodal.analog_logic" ||
         name == "nodal.analog_select";
}

void clearFoldAttributes(Operation *operation) {
  for (llvm::StringRef attribute : kFoldAttributes)
    operation->removeAttr(attribute);
}

LogicalResult verifyRealLiteral(Operation *operation) {
  auto value = operation->getAttrOfType<FloatAttr>("value");
  if (!value || !std::isfinite(value.getValueAsDouble()))
    return emitMappedFailure(operation, "NODAL-ANALOG-LITERAL-001", "real literal must be finite");
  if (!semanticTypeMatches(operation->getResult(0).getType(), AnalogNumericKind::Real, "1"))
    return emitMappedFailure(operation, "NODAL-ANALOG-TYPE-001",
                             "real literal requires a real dimensionless result");
  return success();
}

LogicalResult verifyIntegerLiteral(Operation *operation) {
  if (!operation->getAttrOfType<IntegerAttr>("value"))
    return emitMappedFailure(operation, "NODAL-ANALOG-TYPE-001",
                             "analog integer literal requires an integer attribute");
  if (!semanticTypeMatches(operation->getResult(0).getType(), AnalogNumericKind::Integer, "1"))
    return emitMappedFailure(operation, "NODAL-ANALOG-TYPE-001",
                             "analog integer literal requires !nodal.quantity<integer, 1>");
  return success();
}

LogicalResult verifyParameterReference(Operation *operation) {
  auto reference = operation->getAttrOfType<FlatSymbolRefAttr>("parameter");
  if (!reference)
    return emitMappedFailure(operation, "NODAL-ANALOG-PARAMETER-001",
                             "parameter reference is required");
  Operation *parameter = findParameter(operation, reference.getValue());
  if (!parameter)
    return emitMappedFailure(operation, "NODAL-ANALOG-PARAMETER-001",
                             llvm::Twine("unknown parameter '") + reference.getValue() + "'");

  llvm::StringRef kind = getParameterKind(parameter);
  if (kind == "boolean") {
    if (!operation->getResult(0).getType().isInteger(1))
      return emitMappedFailure(operation, "NODAL-ANALOG-PARAMETER-002",
                               "Boolean parameter reference must produce i1");
    return success();
  }

  auto dimension = parameterDimension(parameter);
  if (failed(dimension))
    return emitMappedFailure(operation, "NODAL-ANALOG-DIMENSION-001",
                             "parameter unit has no canonical dimension signature");
  AnalogNumericKind expectedKind = kind == "integer" ? AnalogNumericKind::Integer
                                   : kind == "real"  ? AnalogNumericKind::Real
                                                     : AnalogNumericKind::Invalid;
  if (expectedKind == AnalogNumericKind::Invalid ||
      !semanticTypeMatches(operation->getResult(0).getType(), expectedKind, *dimension))
    return emitMappedFailure(
        operation, "NODAL-ANALOG-PARAMETER-002",
        "parameter reference result kind or dimension does not match declaration");
  if (*dimension != "1" && operation->getResult(0).getType().isF64())
    return emitMappedFailure(operation, "NODAL-ANALOG-PARAMETER-002",
                             "dimensioned parameter reference cannot use legacy f64");
  return success();
}

LogicalResult verifyBinary(Operation *operation) {
  if (operation->getNumOperands() != 2 || operation->getNumResults() != 1)
    return emitMappedFailure(operation, "NODAL-ANALOG-TYPE-001",
                             "binary analog operation requires two operands and one result");
  auto lhs = getAnalogNumericTypeInfo(operation->getOperand(0).getType());
  auto rhs = getAnalogNumericTypeInfo(operation->getOperand(1).getType());
  if (failed(lhs) || failed(rhs))
    return emitMappedFailure(operation, "NODAL-ANALOG-TYPE-001",
                             "analog arithmetic operands must be quantities or legacy f64 values");
  auto promoted = promoteNumericKinds(lhs->kind, rhs->kind);
  if (failed(promoted))
    return emitMappedFailure(operation, "NODAL-ANALOG-PROMOTION-001",
                             "Boolean or digital values cannot enter analog arithmetic");

  llvm::StringRef name = operation->getName().getStringRef();
  std::string resultDimension;
  AnalogNumericKind resultKind = *promoted;
  if (name == "nodal.analog_add" || name == "nodal.analog_sub") {
    if (lhs->dimension != rhs->dimension)
      return emitMappedFailure(operation, "NODAL-ANALOG-DIMENSION-001",
                               "addition and subtraction require equal physical dimensions");
    resultDimension = lhs->dimension;
  } else if (name == "nodal.analog_mul") {
    auto dimension = combineAnalogDimensions(lhs->dimension, rhs->dimension, false);
    if (failed(dimension))
      return emitMappedFailure(operation, "NODAL-ANALOG-DIMENSION-001",
                               "multiplication could not canonicalize physical dimensions");
    resultDimension = *dimension;
  } else if (name == "nodal.analog_div") {
    auto dimension = combineAnalogDimensions(lhs->dimension, rhs->dimension, true);
    if (failed(dimension))
      return emitMappedFailure(operation, "NODAL-ANALOG-DIMENSION-001",
                               "division could not canonicalize physical dimensions");
    resultDimension = *dimension;
    if (lhs->kind == AnalogNumericKind::Integer && rhs->kind == AnalogNumericKind::Integer) {
      EvaluationResult left = evaluateValue(operation->getOperand(0), false);
      EvaluationResult right = evaluateValue(operation->getOperand(1), false);
      if (right.status == EvaluationStatus::Constant && right.value.integer &&
          right.value.integer->isZero())
        return emitMappedFailure(operation, "NODAL-ANALOG-DIVIDE-001",
                                 "statically known zero divisor");
      resultKind = left.status == EvaluationStatus::Constant &&
                           right.status == EvaluationStatus::Constant &&
                           integerDivisionIsExact(left.value, right.value)
                       ? AnalogNumericKind::Integer
                       : AnalogNumericKind::Real;
    }
  } else {
    return emitMappedFailure(operation, "NODAL-ANALOG-TYPE-001",
                             "unsupported analog binary operation");
  }

  if (!semanticTypeMatches(operation->getResult(0).getType(), resultKind, resultDimension))
    return emitMappedFailure(operation, "NODAL-ANALOG-PROMOTION-001",
                             "analog arithmetic result does not match inferred kind and dimension");
  return success();
}

LogicalResult verifyNeg(Operation *operation) {
  if (operation->getNumOperands() != 1 || operation->getNumResults() != 1)
    return emitMappedFailure(operation, "NODAL-ANALOG-TYPE-001",
                             "analog negation requires one operand and one result");
  auto input = getAnalogNumericTypeInfo(operation->getOperand(0).getType());
  if (failed(input) || input->kind == AnalogNumericKind::Boolean)
    return emitMappedFailure(operation, "NODAL-ANALOG-PROMOTION-001",
                             "analog negation requires a numeric quantity");
  if (!semanticTypeMatches(operation->getResult(0).getType(), input->kind, input->dimension))
    return emitMappedFailure(operation, "NODAL-ANALOG-TYPE-001",
                             "analog negation must preserve kind and dimension");
  return success();
}

LogicalResult verifyCompare(Operation *operation) {
  if (operation->getNumOperands() != 2 || operation->getNumResults() != 1 ||
      !operation->getResult(0).getType().isInteger(1))
    return emitMappedFailure(operation, "NODAL-ANALOG-COMPARE-001",
                             "comparison requires two operands and an i1 result");
  llvm::StringRef predicate = textAttr(operation, "predicate");
  if (predicate != "eq" && predicate != "ne" && predicate != "lt" && predicate != "le" &&
      predicate != "gt" && predicate != "ge")
    return emitMappedFailure(operation, "NODAL-ANALOG-COMPARE-001",
                             "unsupported analog comparison predicate");

  auto lhs = getAnalogNumericTypeInfo(operation->getOperand(0).getType());
  auto rhs = getAnalogNumericTypeInfo(operation->getOperand(1).getType());
  if (failed(lhs) || failed(rhs))
    return emitMappedFailure(operation, "NODAL-ANALOG-COMPARE-001",
                             "comparison operands have unsupported types");
  if (lhs->kind == AnalogNumericKind::Boolean || rhs->kind == AnalogNumericKind::Boolean) {
    if (lhs->kind != AnalogNumericKind::Boolean || rhs->kind != AnalogNumericKind::Boolean ||
        (predicate != "eq" && predicate != "ne"))
      return emitMappedFailure(operation, "NODAL-ANALOG-COMPARE-001",
                               "Boolean comparison supports only equality and inequality");
    return success();
  }
  if (lhs->dimension != rhs->dimension)
    return emitMappedFailure(operation, "NODAL-ANALOG-COMPARE-001",
                             "numeric comparison requires equal physical dimensions");
  return success();
}

LogicalResult verifyLogic(Operation *operation) {
  llvm::StringRef operatorName = textAttr(operation, "operator_name");
  const unsigned expectedArity = operatorName == "not" ? 1 : 2;
  if (operatorName != "not" && operatorName != "and" && operatorName != "or" &&
      operatorName != "xor")
    return emitMappedFailure(operation, "NODAL-ANALOG-LOGIC-001",
                             "unsupported analog logical operator");
  if (operation->getNumOperands() != expectedArity || operation->getNumResults() != 1 ||
      !operation->getResult(0).getType().isInteger(1))
    return emitMappedFailure(operation, "NODAL-ANALOG-LOGIC-001",
                             "logical operator has invalid arity or result type");
  for (Value operand : operation->getOperands()) {
    if (!operand.getType().isInteger(1))
      return emitMappedFailure(operation, "NODAL-ANALOG-LOGIC-001",
                               "logical operators accept only Boolean i1 operands");
  }
  return success();
}

LogicalResult verifySelect(Operation *operation) {
  if (operation->getNumOperands() != 3 || operation->getNumResults() != 1 ||
      !operation->getOperand(0).getType().isInteger(1))
    return emitMappedFailure(operation, "NODAL-ANALOG-SELECT-001",
                             "analog select requires an i1 condition and two arms");
  auto trueType = getAnalogNumericTypeInfo(operation->getOperand(1).getType());
  auto falseType = getAnalogNumericTypeInfo(operation->getOperand(2).getType());
  if (failed(trueType) || failed(falseType))
    return emitMappedFailure(operation, "NODAL-ANALOG-SELECT-001",
                             "conditional arms have unsupported types");
  if (trueType->kind == AnalogNumericKind::Boolean ||
      falseType->kind == AnalogNumericKind::Boolean) {
    if (trueType->kind != AnalogNumericKind::Boolean ||
        falseType->kind != AnalogNumericKind::Boolean ||
        !operation->getResult(0).getType().isInteger(1))
      return emitMappedFailure(operation, "NODAL-ANALOG-SELECT-001",
                               "Boolean conditional requires two Boolean arms and an i1 result");
    return success();
  }
  if (trueType->dimension != falseType->dimension)
    return emitMappedFailure(operation, "NODAL-ANALOG-SELECT-001",
                             "numeric conditional arms require equal physical dimensions");
  auto promoted = promoteNumericKinds(trueType->kind, falseType->kind);
  if (failed(promoted) ||
      !semanticTypeMatches(operation->getResult(0).getType(), *promoted, trueType->dimension))
    return emitMappedFailure(operation, "NODAL-ANALOG-SELECT-001",
                             "conditional result does not match promoted arm type");
  return success();
}

LogicalResult verifyDdt(Operation *operation) {
  if (operation->getNumOperands() != 1 || operation->getNumResults() != 1)
    return emitMappedFailure(operation, "NODAL-ANALOG-DDT-001",
                             "ddt requires one input and one result");
  if (operation->getOperand(0).getType().isF64() && operation->getResult(0).getType().isF64())
    return success();
  auto input = getAnalogNumericTypeInfo(operation->getOperand(0).getType());
  if (failed(input) || input->kind != AnalogNumericKind::Real)
    return emitMappedFailure(operation, "NODAL-ANALOG-DDT-001",
                             "typed ddt requires a real quantity input");
  auto dimension = combineAnalogDimensions(input->dimension, "time", true);
  if (failed(dimension) ||
      !semanticTypeMatches(operation->getResult(0).getType(), AnalogNumericKind::Real, *dimension))
    return emitMappedFailure(operation, "NODAL-ANALOG-DDT-001",
                             "ddt result must subtract one time exponent");
  return success();
}

LogicalResult verifyAccess(Operation *operation) {
  llvm::StringRef kind = textAttr(operation, "kind");
  if (kind != "potential" && kind != "flow")
    return emitMappedFailure(operation, "NODAL-ANALOG-ACCESS-001", "unsupported access kind");
  if (operation->getNumResults() != 1)
    return emitMappedFailure(operation, "NODAL-ANALOG-ACCESS-002",
                             "potential and flow access require one result");
  auto result = getAnalogNumericTypeInfo(operation->getResult(0).getType());
  if (failed(result) || result->kind != AnalogNumericKind::Real)
    return emitMappedFailure(
        operation, "NODAL-ANALOG-ACCESS-002",
        "potential and flow access must produce a real quantity or legacy f64");
  if (!result->legacyF64) {
    if (auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata")) {
      if (auto dimension = metadata.getAs<StringAttr>("dimension")) {
        if (!isCanonicalDimensionSignature(dimension.getValue()) ||
            result->dimension != dimension.getValue())
          return emitMappedFailure(
              operation, "NODAL-ANALOG-DIMENSION-001",
              "typed access result does not match declared dimension metadata");
      }
    }
  }
  return success();
}

LogicalResult verifyContribution(Operation *operation) {
  llvm::StringRef kind = textAttr(operation, "kind");
  if (kind != "potential" && kind != "flow")
    return emitMappedFailure(operation, "NODAL-ANALOG-CONTRIBUTION-001",
                             "unsupported contribution kind");
  auto branch = llvm::dyn_cast<BranchType>(operation->getOperand(0).getType());
  if (!branch || branch.getDiscipline().trim().empty())
    return emitMappedFailure(operation, "NODAL-ANALOG-CONTRIBUTION-002",
                             "branch discipline must not be empty");
  auto value = getAnalogNumericTypeInfo(operation->getOperand(1).getType());
  if (failed(value) || value->kind == AnalogNumericKind::Boolean)
    return emitMappedFailure(operation, "NODAL-ANALOG-CONTRIBUTION-003",
                             "contribution value must be a numeric quantity or legacy f64");
  return success();
}

} // namespace

bool isCanonicalDimensionSignature(llvm::StringRef signature) {
  return succeeded(parseDimension(signature, true));
}

FailureOr<std::string> combineAnalogDimensions(llvm::StringRef lhs, llvm::StringRef rhs,
                                               bool subtractRhs) {
  auto left = parseDimension(lhs, true);
  auto right = parseDimension(rhs, true);
  if (failed(left) || failed(right))
    return failure();
  DimensionMap result = *left;
  for (const auto &[atom, exponent] : *right) {
    int64_t current = result.count(atom) != 0 ? result[atom] : 0;
    int64_t updated = 0;
    if (subtractRhs) {
      if (__builtin_sub_overflow(current, exponent, &updated))
        return failure();
    } else if (__builtin_add_overflow(current, exponent, &updated)) {
      return failure();
    }
    if (updated == 0)
      result.erase(atom);
    else
      result[atom] = updated;
  }
  return formatDimension(result);
}

FailureOr<AnalogNumericTypeInfo> getAnalogNumericTypeInfo(Type type) {
  AnalogNumericTypeInfo result;
  if (type.isF64()) {
    result.kind = AnalogNumericKind::Real;
    result.dimension = "1";
    result.legacyF64 = true;
    return result;
  }
  if (type.isInteger(1)) {
    result.kind = AnalogNumericKind::Boolean;
    result.dimension = "1";
    return result;
  }
  if (auto quantity = llvm::dyn_cast<QuantityType>(type)) {
    if (!isCanonicalDimensionSignature(quantity.getDimension()))
      return failure();
    if (quantity.getKind() == "integer")
      result.kind = AnalogNumericKind::Integer;
    else if (quantity.getKind() == "real")
      result.kind = AnalogNumericKind::Real;
    else
      return failure();
    result.dimension = quantity.getDimension().str();
    return result;
  }
  return failure();
}

LogicalResult verifyAnalogNumericOperation(Operation *operation) {
  llvm::StringRef name = operation->getName().getStringRef();
  if (name == "nodal.real_literal")
    return verifyRealLiteral(operation);
  if (name == "nodal.analog_integer_literal")
    return verifyIntegerLiteral(operation);
  if (name == "nodal.parameter_ref")
    return verifyParameterReference(operation);
  if (name == "nodal.analog_add" || name == "nodal.analog_sub" || name == "nodal.analog_mul" ||
      name == "nodal.analog_div")
    return verifyBinary(operation);
  if (name == "nodal.analog_neg")
    return verifyNeg(operation);
  if (name == "nodal.analog_compare")
    return verifyCompare(operation);
  if (name == "nodal.analog_logic")
    return verifyLogic(operation);
  if (name == "nodal.analog_select")
    return verifySelect(operation);
  if (name == "nodal.analog_ddt")
    return verifyDdt(operation);
  if (name == "nodal.access")
    return verifyAccess(operation);
  if (name == "nodal.contribute")
    return verifyContribution(operation);
  return success();
}

LogicalResult verifyAnalogNumericModel(mlir::ModuleOp module) {
  LogicalResult result = success();
  module.walk([&](Operation *operation) {
    if (failed(result))
      return;
    for (Type type : operation->getOperandTypes()) {
      if (auto quantity = llvm::dyn_cast<QuantityType>(type)) {
        if (!isCanonicalDimensionSignature(quantity.getDimension())) {
          result = emitMappedFailure(operation, "NODAL-ANALOG-DIMENSION-001",
                                     "operand quantity has a non-canonical dimension");
          return;
        }
      }
    }
    for (Type type : operation->getResultTypes()) {
      if (auto quantity = llvm::dyn_cast<QuantityType>(type)) {
        if (!isCanonicalDimensionSignature(quantity.getDimension())) {
          result = emitMappedFailure(operation, "NODAL-ANALOG-DIMENSION-001",
                                     "result quantity has a non-canonical dimension");
          return;
        }
      }
    }
    result = verifyAnalogNumericOperation(operation);
  });
  return result;
}

LogicalResult foldAnalogNumericConstants(mlir::ModuleOp module) {
  if (failed(verifyAnalogNumericModel(module)))
    return failure();
  LogicalResult result = success();
  module.walk([&](Operation *operation) {
    if (failed(result))
      return;
    if (!isFoldCandidate(operation) || operation->getNumResults() != 1) {
      clearFoldAttributes(operation);
      return;
    }

    EvaluationResult evaluated = evaluateValue(operation->getResult(0), true);
    if (evaluated.status == EvaluationStatus::Error) {
      result = failure();
      return;
    }
    if (evaluated.status != EvaluationStatus::Constant) {
      clearFoldAttributes(operation);
      return;
    }

    MLIRContext *context = operation->getContext();
    operation->setAttr("nodal.folded", BoolAttr::get(context, true));
    llvm::StringRef kind =
        evaluated.value.kind == AnalogNumericKind::Integer ? llvm::StringRef("integer")
        : evaluated.value.kind == AnalogNumericKind::Real  ? llvm::StringRef("real")
                                                           : llvm::StringRef("boolean");
    operation->setAttr("nodal.folded_kind", StringAttr::get(context, kind));
    operation->setAttr("nodal.folded_dimension",
                       StringAttr::get(context, evaluated.value.dimension));
    operation->setAttr("nodal.folded_provenance", StringAttr::get(context, "increment30"));
    if (evaluated.value.kind == AnalogNumericKind::Boolean) {
      operation->setAttr("nodal.folded_value", BoolAttr::get(context, evaluated.value.boolean));
    } else if (evaluated.value.kind == AnalogNumericKind::Real) {
      operation->setAttr("nodal.folded_value",
                         FloatAttr::get(Float64Type::get(context), evaluated.value.real));
    } else if (evaluated.value.integer) {
      llvm::APInt integer = *evaluated.value.integer;
      if (integer.getBitWidth() == 0)
        integer = llvm::APInt(1, 0);
      operation->setAttr(
          "nodal.folded_value",
          IntegerAttr::get(IntegerType::get(context, integer.getBitWidth()), integer));
    } else {
      result = emitMappedFailure(operation, "NODAL-ANALOG-FOLD-001",
                                 "constant integer fold lost its exact value");
    }
  });
  return result;
}

LogicalResult verifyAnalogQuantityErasure(mlir::ModuleOp module) {
  if (failed(verifyAnalogNumericModel(module)))
    return emitMappedFailure(module.getOperation(), "NODAL-BACKEND-QUANTITY-001",
                             "quantity erasure requires a verified analog numeric model");
  return success();
}

} // namespace nodal
