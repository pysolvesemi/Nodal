#!/usr/bin/env python3
"""Materialize the native Increment 30 implementation, tests, and permanent gates."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def write(relative: str, content: str) -> None:
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def replace_once(relative: str, old: str, new: str) -> None:
    text = read(relative)
    if text.count(old) != 1:
        raise RuntimeError(f"{relative}: expected exactly one occurrence of replacement anchor")
    write(relative, text.replace(old, new, 1))


def replace_regex(relative: str, pattern: str, replacement: str) -> None:
    text = read(relative)
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.DOTALL)
    if count != 1:
        raise RuntimeError(f"{relative}: regex replacement matched {count} times")
    write(relative, updated)


ANALOG_NUMERIC_HEADER = r'''#ifndef NODAL_DIALECT_NODAL_ANALOGNUMERIC_H
#define NODAL_DIALECT_NODAL_ANALOGNUMERIC_H

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Operation.h"
#include "mlir/IR/Types.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/StringRef.h"

#include <string>

namespace nodal {

enum class AnalogNumericKind {
  Invalid,
  Integer,
  Real,
  Boolean,
};

struct AnalogNumericTypeInfo {
  AnalogNumericKind kind = AnalogNumericKind::Invalid;
  std::string dimension;
  bool legacyF64 = false;
};

/// Return true only for the deterministic canonical dimension grammar frozen
/// by Increment 30.
bool isCanonicalDimensionSignature(llvm::StringRef signature);

/// Combine canonical dimensions by adding (`subtractRhs == false`) or
/// subtracting (`subtractRhs == true`) the right-hand exponent vector.
mlir::FailureOr<std::string> combineAnalogDimensions(llvm::StringRef lhs,
                                                     llvm::StringRef rhs,
                                                     bool subtractRhs);

/// Classify a Nodal quantity, legacy dimensionless f64, or Boolean i1 value.
mlir::FailureOr<AnalogNumericTypeInfo> getAnalogNumericTypeInfo(mlir::Type type);

/// Verify one source-semantic analog numeric operation.
mlir::LogicalResult verifyAnalogNumericOperation(mlir::Operation *operation);

/// Verify every quantity and analog expression in a builtin module.
mlir::LogicalResult verifyAnalogNumericModel(mlir::ModuleOp module);

/// Annotate pure constant expression roots without erasing authored operations.
mlir::LogicalResult foldAnalogNumericConstants(mlir::ModuleOp module);

/// Prove that every quantity can be erased to a native scalar only after the
/// mandatory semantic verifier has accepted the model.
mlir::LogicalResult verifyAnalogQuantityErasure(mlir::ModuleOp module);

} // namespace nodal

#endif // NODAL_DIALECT_NODAL_ANALOGNUMERIC_H
'''

ANALOG_NUMERIC_CPP = r'''#include "nodal/Dialect/Nodal/AnalogNumeric.h"

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

EvaluationResult errorResult(Operation *operation, llvm::StringRef code,
                             const llvm::Twine &message, bool reportErrors) {
  if (reportErrors)
    (void)emitMappedFailure(operation, code, message);
  EvaluationResult result;
  result.status = EvaluationStatus::Error;
  return result;
}

llvm::APInt signedExtend(const llvm::APInt &value, unsigned width) {
  return value.sextOrTrunc(std::max(width, value.getBitWidth()));
}

std::pair<llvm::APInt, llvm::APInt> alignSigned(const llvm::APInt &lhs,
                                                const llvm::APInt &rhs,
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
    if (!value || !std::isfinite(value.getValueAsDouble()))
      return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                         "fixed real parameter has no finite canonical default", reportErrors);
    result.real = value.getValueAsDouble();
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
    return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                       "operand constant evaluation failed", reportErrors);
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
      return errorResult(operation, "NODAL-ANALOG-DIVIDE-001",
                         "statically known zero divisor", reportErrors);
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
      const unsigned width = lhs.value.integer->getBitWidth() + rhs.value.integer->getBitWidth() + 1;
      result.integer = lhs.value.integer->sextOrTrunc(width) * rhs.value.integer->sextOrTrunc(width);
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
    return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                       "comparison operand evaluation failed", reportErrors);
  if (lhs.status != EvaluationStatus::Constant || rhs.status != EvaluationStatus::Constant)
    return dynamicResult();

  llvm::StringRef predicate = textAttr(operation, "predicate");
  bool value = false;
  if (lhs.value.kind == AnalogNumericKind::Boolean && rhs.value.kind == AnalogNumericKind::Boolean) {
    if (predicate == "eq")
      value = lhs.value.boolean == rhs.value.boolean;
    else if (predicate == "ne")
      value = lhs.value.boolean != rhs.value.boolean;
    else
      return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                         "ordering comparison cannot fold Boolean operands", reportErrors);
  } else if (lhs.value.kind == AnalogNumericKind::Integer &&
             rhs.value.kind == AnalogNumericKind::Integer && lhs.value.integer && rhs.value.integer) {
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
      return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                         "logical operand evaluation failed", reportErrors);
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
    return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                       "conditional operand evaluation failed", reportErrors);
  if (condition.status != EvaluationStatus::Constant ||
      trueValue.status != EvaluationStatus::Constant ||
      falseValue.status != EvaluationStatus::Constant)
    return dynamicResult();
  return condition.value.boolean ? trueValue : falseValue;
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
      return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                         "real literal cannot be evaluated", reportErrors);
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
      return errorResult(operation, "NODAL-ANALOG-FOLD-001",
                         "integer literal cannot be evaluated", reportErrors);
    ConstantValue result;
    result.kind = AnalogNumericKind::Integer;
    result.dimension = information->dimension;
    result.integer = literal.getValue();
    return constantResult(std::move(result));
  }
  if (name == "nodal.parameter_ref")
    return evaluateParameterReference(operation, reportErrors);
  if (name == "nodal.analog_add" || name == "nodal.analog_sub" ||
      name == "nodal.analog_mul" || name == "nodal.analog_div")
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

bool isFoldCandidate(Operation *operation) {
  llvm::StringRef name = operation->getName().getStringRef();
  return name == "nodal.analog_add" || name == "nodal.analog_sub" ||
         name == "nodal.analog_mul" || name == "nodal.analog_div" ||
         name == "nodal.analog_neg" || name == "nodal.analog_compare" ||
         name == "nodal.analog_logic" || name == "nodal.analog_select";
}

LogicalResult verifyRealLiteral(Operation *operation) {
  auto value = operation->getAttrOfType<FloatAttr>("value");
  if (!value || !std::isfinite(value.getValueAsDouble()))
    return emitMappedFailure(operation, "NODAL-ANALOG-LITERAL-001",
                             "real literal must be finite");
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
                                                       : kind == "real" ? AnalogNumericKind::Real
                                                                        : AnalogNumericKind::Invalid;
  if (expectedKind == AnalogNumericKind::Invalid ||
      !semanticTypeMatches(operation->getResult(0).getType(), expectedKind, *dimension))
    return emitMappedFailure(operation, "NODAL-ANALOG-PARAMETER-002",
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
  if (trueType->kind == AnalogNumericKind::Boolean || falseType->kind == AnalogNumericKind::Boolean) {
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
  if (failed(promoted) || !semanticTypeMatches(operation->getResult(0).getType(), *promoted,
                                                trueType->dimension))
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
    return emitMappedFailure(operation, "NODAL-ANALOG-ACCESS-001",
                             "unsupported access kind");
  if (operation->getNumResults() != 1)
    return emitMappedFailure(operation, "NODAL-ANALOG-ACCESS-002",
                             "potential and flow access require one result");
  auto result = getAnalogNumericTypeInfo(operation->getResult(0).getType());
  if (failed(result) || result->kind != AnalogNumericKind::Real)
    return emitMappedFailure(operation, "NODAL-ANALOG-ACCESS-002",
                             "potential and flow access must produce a real quantity or legacy f64");
  if (!result->legacyF64) {
    if (auto metadata = operation->getAttrOfType<DictionaryAttr>("metadata")) {
      if (auto dimension = metadata.getAs<StringAttr>("dimension")) {
        if (!isCanonicalDimensionSignature(dimension.getValue()) ||
            result->dimension != dimension.getValue())
          return emitMappedFailure(operation, "NODAL-ANALOG-DIMENSION-001",
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
    const int64_t delta = subtractRhs ? -exponent : exponent;
    if (__builtin_add_overflow(current, delta, &updated))
      return failure();
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
  if (name == "nodal.analog_add" || name == "nodal.analog_sub" ||
      name == "nodal.analog_mul" || name == "nodal.analog_div")
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

LogicalResult verifyAnalogNumericModel(ModuleOp module) {
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

LogicalResult foldAnalogNumericConstants(ModuleOp module) {
  if (failed(verifyAnalogNumericModel(module)))
    return failure();
  LogicalResult result = success();
  module.walk([&](Operation *operation) {
    if (failed(result) || !isFoldCandidate(operation) || operation->getNumResults() != 1)
      return;
    EvaluationResult evaluated = evaluateValue(operation->getResult(0), true);
    if (evaluated.status == EvaluationStatus::Error) {
      result = failure();
      return;
    }
    constexpr llvm::StringLiteral attributes[] = {
        "nodal.folded", "nodal.folded_kind", "nodal.folded_dimension",
        "nodal.folded_value", "nodal.folded_provenance"};
    if (evaluated.status != EvaluationStatus::Constant) {
      for (llvm::StringRef attribute : attributes)
        operation->removeAttr(attribute);
      return;
    }

    MLIRContext *context = operation->getContext();
    operation->setAttr("nodal.folded", BoolAttr::get(context, true));
    llvm::StringRef kind = evaluated.value.kind == AnalogNumericKind::Integer
                               ? llvm::StringRef("integer")
                           : evaluated.value.kind == AnalogNumericKind::Real
                               ? llvm::StringRef("real")
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

LogicalResult verifyAnalogQuantityErasure(ModuleOp module) {
  if (failed(verifyAnalogNumericModel(module)))
    return emitMappedFailure(module.getOperation(), "NODAL-BACKEND-QUANTITY-001",
                             "quantity erasure requires a verified analog numeric model");
  return success();
}

} // namespace nodal
'''

write("core/compiler/include/nodal/Dialect/Nodal/AnalogNumeric.h", ANALOG_NUMERIC_HEADER)
write("core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp", ANALOG_NUMERIC_CPP)

replace_once(
    "core/compiler/include/nodal/Dialect/Nodal/NodalTypes.td",
    'def Nodal_ShapedType : Nodal_Type<"Shaped", "shaped"> {',
    r'''def Nodal_QuantityType : Nodal_Type<"Quantity", "quantity"> {
  let summary = "Target-neutral scalar analog quantity";
  let description = [{
    A quantity owns a numeric kind (`integer` or `real`) and a canonical
    physical-dimension signature. Boolean remains ordinary `i1`; target unit
    spelling and scale remain separate from this semantic type.
  }];
  let parameters = (ins
    StringRefParameter<"integer or real">:$kind,
    StringRefParameter<"canonical physical dimension signature">:$dimension
  );
  let assemblyFormat = "`<` $kind `,` $dimension `>`";
  let genVerifyDecl = 1;
}

def Nodal_ShapedType : Nodal_Type<"Shaped", "shaped"> {''',
)

replace_once(
    "core/compiler/lib/Dialect/Nodal/NodalTypes.cpp",
    '#include "nodal/Dialect/Nodal/NodalTypes.h"\n',
    '#include "nodal/Dialect/Nodal/NodalTypes.h"\n\n#include "nodal/Dialect/Nodal/AnalogNumeric.h"\n',
)
replace_once(
    "core/compiler/lib/Dialect/Nodal/NodalTypes.cpp",
    '''LogicalResult nodal::ShapedType::verify(llvm::function_ref<InFlightDiagnostic()> emitError,
                                        llvm::StringRef dimensions, Type elementType) {''',
    '''LogicalResult nodal::QuantityType::verify(llvm::function_ref<InFlightDiagnostic()> emitError,
                                          llvm::StringRef kind,
                                          llvm::StringRef dimension) {
  if (kind != "integer" && kind != "real")
    return emitError() << "NODAL-ANALOG-TYPE-001: quantity kind must be integer or real";
  if (!nodal::isCanonicalDimensionSignature(dimension))
    return emitError() << "NODAL-ANALOG-DIMENSION-001: quantity dimension must be canonical";
  return success();
}

LogicalResult nodal::ShapedType::verify(llvm::function_ref<InFlightDiagnostic()> emitError,
                                        llvm::StringRef dimensions, Type elementType) {''',
)

replace_regex(
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    r'def Nodal_RealLiteralOp : Nodal_Op<"real_literal".*?\n//===----------------------------------------------------------------------===//\n// Domain crossings and timing provenance',
    r'''def Nodal_RealLiteralOp : Nodal_Op<"real_literal", [HasParent<"AnalogOp">]> {
  let summary = "Finite real dimensionless literal in an analog expression";
  let arguments = (ins F64Attr:$value, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogIntegerLiteralOp
    : Nodal_Op<"analog_integer_literal", [HasParent<"AnalogOp">]> {
  let summary = "Exact integer dimensionless literal in an analog expression";
  let arguments = (ins APIntAttr:$value, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_ParameterRefOp : Nodal_Op<"parameter_ref", [HasParent<"AnalogOp">]> {
  let summary = "Typed reference to an enclosing scalar parameter";
  let arguments = (ins FlatSymbolRefAttr:$parameter, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

class Nodal_AnalogBinaryOp<string mnemonic, string description>
    : Nodal_Op<mnemonic, [HasParent<"AnalogOp">]> {
  let summary = description;
  let arguments = (ins AnyType:$lhs, AnyType:$rhs, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogAddOp : Nodal_AnalogBinaryOp<"analog_add", "Analog quantity addition">;
def Nodal_AnalogSubOp : Nodal_AnalogBinaryOp<"analog_sub", "Analog quantity subtraction">;
def Nodal_AnalogMulOp : Nodal_AnalogBinaryOp<"analog_mul", "Analog quantity multiplication">;
def Nodal_AnalogDivOp : Nodal_AnalogBinaryOp<"analog_div", "Analog quantity division">;

def Nodal_AnalogNegOp : Nodal_Op<"analog_neg", [HasParent<"AnalogOp">]> {
  let summary = "Analog numeric negation";
  let arguments = (ins AnyType:$input, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogCompareOp : Nodal_Op<"analog_compare", [HasParent<"AnalogOp">]> {
  let summary = "Dimension-aware analog or Boolean comparison";
  let arguments = (ins
    AnyType:$lhs,
    AnyType:$rhs,
    StrAttr:$predicate,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogLogicOp : Nodal_Op<"analog_logic", [HasParent<"AnalogOp">]> {
  let summary = "Boolean-only logical expression";
  let arguments = (ins
    Variadic<AnyType>:$operands,
    StrAttr:$operator_name,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogSelectOp : Nodal_Op<"analog_select", [HasParent<"AnalogOp">]> {
  let summary = "Typed conditional analog or Boolean expression";
  let arguments = (ins
    AnyType:$condition,
    AnyType:$true_value,
    AnyType:$false_value,
    DictionaryAttr:$metadata
  );
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_AnalogDdtOp : Nodal_Op<"analog_ddt", [HasParent<"AnalogOp">]> {
  let summary = "Continuous-time derivative of a typed analog expression";
  let arguments = (ins AnyType:$input, DictionaryAttr:$metadata);
  let results = (outs AnyType:$result);
  let hasVerifier = 1;
}

def Nodal_ContributeOp : Nodal_Op<"contribute", [HasParent<"AnalogOp">]> {
  let summary = "Potential or flow contribution to a conservative branch";
  let arguments = (ins
    Nodal_BranchType:$branch,
    AnyType:$value,
    StrAttr:$kind,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}

//===----------------------------------------------------------------------===//
// Domain crossings and timing provenance''',
)

replace_once(
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    '#include "nodal/Dialect/Nodal/NatureDiscipline.h"\n',
    '#include "nodal/Dialect/Nodal/NatureDiscipline.h"\n#include "nodal/Dialect/Nodal/AnalogNumeric.h"\n',
)
replace_regex(
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    r'\nLogicalResult verifyAnalogBinary\(Operation \*operation\) \{.*?\n\}\n',
    '\n',
)
replace_regex(
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    r'LogicalResult nodal::AccessOp::verify\(\) \{.*?\n\}\n\nLogicalResult nodal::BridgeOp::verify\(\) \{',
    r'''LogicalResult nodal::AccessOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}

LogicalResult nodal::AnalogOp::verify() {
  if (failed(requireSingleBlock(getOperation())))
    return emitOpError("NODAL-ANALOG-REGION-001: analog region requires one body block");
  for (Operation &operation : getOperation()->getRegion(0).front()) {
    if (!llvm::isa<nodal::RealLiteralOp, nodal::AnalogIntegerLiteralOp, nodal::ParameterRefOp,
                   nodal::AnalogAddOp, nodal::AnalogSubOp, nodal::AnalogMulOp,
                   nodal::AnalogDivOp, nodal::AnalogNegOp, nodal::AnalogCompareOp,
                   nodal::AnalogLogicOp, nodal::AnalogSelectOp, nodal::AnalogDdtOp,
                   nodal::AccessOp, nodal::ContributeOp>(operation))
      return operation.emitOpError(
          "NODAL-ANALOG-REGION-002: operation is not legal in the analog numeric region");
  }
  return success();
}

LogicalResult nodal::RealLiteralOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}

LogicalResult nodal::AnalogIntegerLiteralOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}

LogicalResult nodal::ParameterRefOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}

LogicalResult nodal::AnalogAddOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogSubOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogMulOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogDivOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogNegOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogCompareOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogLogicOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogSelectOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::AnalogDdtOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}
LogicalResult nodal::ContributeOp::verify() {
  return nodal::verifyAnalogNumericOperation(getOperation());
}

LogicalResult nodal::BridgeOp::verify() {''',
)

replace_once(
    "core/compiler/lib/Dialect/Nodal/CMakeLists.txt",
    "  NodalDialect.cpp\n",
    "  NodalDialect.cpp\n  AnalogNumeric.cpp\n",
)

replace_once(
    "core/compiler/lib/Transforms/Passes.cpp",
    '#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"\n',
    '#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"\n#include "nodal/Dialect/Nodal/AnalogNumeric.h"\n',
)
replace_once(
    "core/compiler/lib/Transforms/Passes.cpp",
    '          name == "nodal.real_literal" || name == "nodal.parameter_ref" ||\n          name == "nodal.analog_add" || name == "nodal.analog_sub" || name == "nodal.analog_mul" ||\n          name == "nodal.analog_div" || name == "nodal.analog_ddt" || name == "nodal.contribute")',
    '          name == "nodal.real_literal" || name == "nodal.analog_integer_literal" ||\n          name == "nodal.parameter_ref" || name == "nodal.analog_add" ||\n          name == "nodal.analog_sub" || name == "nodal.analog_mul" ||\n          name == "nodal.analog_div" || name == "nodal.analog_neg" ||\n          name == "nodal.analog_compare" || name == "nodal.analog_logic" ||\n          name == "nodal.analog_select" || name == "nodal.analog_ddt" ||\n          name == "nodal.contribute")',
)
replace_once(
    "core/compiler/lib/Transforms/Passes.cpp",
    '        name == "nodal.analog" || name == "nodal.real_literal" || name == "nodal.parameter_ref" ||\n        name == "nodal.analog_add" || name == "nodal.analog_sub" || name == "nodal.analog_mul" ||\n        name == "nodal.analog_div" || name == "nodal.analog_ddt" || name == "nodal.contribute";',
    '        name == "nodal.analog" || name == "nodal.real_literal" ||\n        name == "nodal.analog_integer_literal" || name == "nodal.parameter_ref" ||\n        name == "nodal.analog_add" || name == "nodal.analog_sub" ||\n        name == "nodal.analog_mul" || name == "nodal.analog_div" ||\n        name == "nodal.analog_neg" || name == "nodal.analog_compare" ||\n        name == "nodal.analog_logic" || name == "nodal.analog_select" ||\n        name == "nodal.analog_ddt" || name == "nodal.contribute";',
)
replace_once(
    "core/compiler/lib/Transforms/Passes.cpp",
    '''class NormalizePipelinePass final
    : public PassWrapper<NormalizePipelinePass, OperationPass<mlir::ModuleOp>> {''',
    '''class FoldAnalogConstantsPass final
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

class NormalizePipelinePass final
    : public PassWrapper<NormalizePipelinePass, OperationPass<mlir::ModuleOp>> {''',
)
replace_once(
    "core/compiler/lib/Transforms/Passes.cpp",
    '    return {"construction", "hierarchy", "types", "parameters", "domains", "capabilities"};',
    '    return {"construction", "hierarchy", "types", "parameters", "analog-numeric",\n            "domains", "capabilities"};',
)
replace_once(
    "core/compiler/lib/Transforms/Passes.cpp",
    '          "types",        "parameters", "enum-fsm",    "domains", "protocols",\n          "effects",      "analog",     "capabilities"};',
    '          "types",        "parameters", "analog-numeric", "enum-fsm", "domains",\n          "protocols",    "effects",    "analog",          "capabilities"};',
)
replace_once(
    "core/compiler/lib/Transforms/Passes.cpp",
    '  manager.addPass(std::make_unique<VerifyParametersPass>());\n',
    '  manager.addPass(std::make_unique<VerifyParametersPass>());\n  manager.addPass(std::make_unique<FoldAnalogConstantsPass>());\n  manager.addPass(std::make_unique<VerifyAnalogNumericPass>());\n',
)
replace_once(
    "core/compiler/lib/Transforms/Passes.cpp",
    '  static PassRegistration<VerifyParametersPass> parameters;\n',
    '  static PassRegistration<VerifyParametersPass> parameters;\n  static PassRegistration<FoldAnalogConstantsPass> analogFolding;\n  static PassRegistration<VerifyAnalogNumericPass> analogNumeric;\n',
)
replace_once(
    "core/compiler/lib/Transforms/Passes.cpp",
    '  (void)parameters;\n',
    '  (void)parameters;\n  (void)analogFolding;\n  (void)analogNumeric;\n',
)

replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '#include "nodal/Diagnostics/DiagnosticMapping.h"\n',
    '#include "nodal/Diagnostics/DiagnosticMapping.h"\n#include "nodal/Dialect/Nodal/AnalogNumeric.h"\n',
)
replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '    "nodal.real_literal",\n    "nodal.parameter_ref",\n',
    '    "nodal.real_literal",\n    "nodal.analog_integer_literal",\n    "nodal.parameter_ref",\n',
)
replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '    "nodal.analog_div",\n    "nodal.analog_ddt",\n',
    '    "nodal.analog_div",\n    "nodal.analog_neg",\n    "nodal.analog_compare",\n    "nodal.analog_logic",\n    "nodal.analog_select",\n    "nodal.analog_ddt",\n',
)
replace_regex(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    r'FailureOr<std::string> renderExpression\(Value value, ModuleRenderState &state\) \{.*?\n\}\n\nLogicalResult orderParametersByDependency',
    r'''std::optional<std::string> renderFoldedExpression(Operation *operation) {
  auto folded = operation->getAttrOfType<BoolAttr>("nodal.folded");
  if (!folded || !folded.getValue())
    return std::nullopt;
  Attribute value = operation->getAttr("nodal.folded_value");
  if (auto boolean = llvm::dyn_cast_or_null<BoolAttr>(value))
    return boolean.getValue() ? std::string("1") : std::string("0");
  if (auto real = llvm::dyn_cast_or_null<FloatAttr>(value)) {
    if (!std::isfinite(real.getValueAsDouble()))
      return std::nullopt;
    return formatReal(real.getValueAsDouble());
  }
  if (auto integer = llvm::dyn_cast_or_null<IntegerAttr>(value)) {
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
      llvm::StringRef spelling = operatorName.getValue() == "and"   ? "&&"
                                 : operatorName.getValue() == "or"  ? "||"
                                                                    : "^";
      rendered = (llvm::Twine("(") + *lhs + " " + spelling + " " + *rhs + ")").str();
    }
  } else if (name == "nodal.analog_select") {
    auto condition = renderExpression(operation->getOperand(0), state);
    auto trueValue = renderExpression(operation->getOperand(1), state);
    auto falseValue = renderExpression(operation->getOperand(2), state);
    if (failed(condition) || failed(trueValue) || failed(falseValue))
      return failure();
    rendered = (llvm::Twine("(") + *condition + " ? " + *trueValue + " : " + *falseValue + ")")
                   .str();
  } else {
    return failure();
  }

  state.expressions.try_emplace(value, rendered);
  return rendered;
}

LogicalResult orderParametersByDependency''',
)
replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '''LogicalResult verifyBackendOperations(ModuleOp module, const BackendProfile &profile) {
  LogicalResult result = success();''',
    '''LogicalResult verifyBackendOperations(ModuleOp module, const BackendProfile &profile) {
  if (failed(verifyAnalogQuantityErasure(module)))
    return failure();
  LogicalResult result = success();''',
)

# Diagnostics are machine-readable and are extended without disturbing prior families.
diagnostics_path = ROOT / "core/compiler/diagnostics-v0.1.json"
diagnostics = json.loads(diagnostics_path.read_text(encoding="utf-8"))
diagnostics["families"]["analog-numeric-typing"] = [
    "NODAL-ANALOG-TYPE-001",
    "NODAL-ANALOG-PROMOTION-001",
    "NODAL-ANALOG-DIMENSION-001",
    "NODAL-ANALOG-COMPARE-001",
    "NODAL-ANALOG-LOGIC-001",
    "NODAL-ANALOG-SELECT-001",
    "NODAL-ANALOG-FOLD-001",
    "NODAL-ANALOG-DIVIDE-001",
    "NODAL-BACKEND-QUANTITY-001",
]
for prefix in ("NODAL-ANALOG-TYPE-", "NODAL-ANALOG-PROMOTION-", "NODAL-ANALOG-DIMENSION-",
               "NODAL-ANALOG-COMPARE-", "NODAL-ANALOG-LOGIC-", "NODAL-ANALOG-SELECT-",
               "NODAL-ANALOG-FOLD-", "NODAL-ANALOG-DIVIDE-", "NODAL-BACKEND-QUANTITY-"):
    if prefix not in diagnostics["preserved_prefixes"]:
        diagnostics["preserved_prefixes"].append(prefix)
diagnostics_path.write_text(json.dumps(diagnostics, indent=2) + "\n", encoding="utf-8")

POSITIVE_FIXTURE = r'''module attributes {
  nodal.target.profile = "analog"
} {
  "nodal.unit"() <{dimension = "voltage", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Volt", symbol = "V"}> : () -> ()
  "nodal.unit"() <{dimension = "current", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Amp", symbol = "A"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "AnalogNumericTyping"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.5 : f64, metadata = {}, parameter_kind = "real", sym_name = "V1", type = f64, unit = @Volt, variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 2.5 : f64, metadata = {}, parameter_kind = "real", sym_name = "V2", type = f64, unit = @Volt, variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 0.25 : f64, metadata = {}, parameter_kind = "real", sym_name = "I1", type = f64, unit = @Amp, variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = true, metadata = {}, parameter_kind = "boolean", sym_name = "ENABLE", type = i1, variability = "fixed"}> : () -> ()
    "nodal.analog"() <{metadata = {typing = "increment30"}}> ({
    ^bb0:
      %v1 = "nodal.parameter_ref"() <{metadata = {}, parameter = @V1}> : () -> !nodal.quantity<"real", "voltage">
      %v2 = "nodal.parameter_ref"() <{metadata = {}, parameter = @V2}> : () -> !nodal.quantity<"real", "voltage">
      %i1 = "nodal.parameter_ref"() <{metadata = {}, parameter = @I1}> : () -> !nodal.quantity<"real", "current">
      %enable = "nodal.parameter_ref"() <{metadata = {}, parameter = @ENABLE}> : () -> i1
      %sum = "nodal.analog_add"(%v1, %v2) <{metadata = {identity = "sum"}}> : (!nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "voltage">) -> !nodal.quantity<"real", "voltage">
      %power = "nodal.analog_mul"(%sum, %i1) <{metadata = {identity = "power"}}> : (!nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "current">) -> !nodal.quantity<"real", "current*voltage">
      %ratio = "nodal.analog_div"(%sum, %i1) <{metadata = {identity = "ratio"}}> : (!nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "current">) -> !nodal.quantity<"real", "current^-1*voltage">
      %negative = "nodal.analog_neg"(%sum) <{metadata = {identity = "negative"}}> : (!nodal.quantity<"real", "voltage">) -> !nodal.quantity<"real", "voltage">
      %ordered = "nodal.analog_compare"(%v1, %v2) <{metadata = {}, predicate = "lt"}> : (!nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "voltage">) -> i1
      %condition = "nodal.analog_logic"(%ordered, %enable) <{metadata = {}, operator_name = "and"}> : (i1, i1) -> i1
      %selected = "nodal.analog_select"(%condition, %sum, %v1) <{metadata = {identity = "selected"}}> : (i1, !nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "voltage">) -> !nodal.quantity<"real", "voltage">
      %slope = "nodal.analog_ddt"(%selected) <{metadata = {identity = "slope"}}> : (!nodal.quantity<"real", "voltage">) -> !nodal.quantity<"real", "time^-1*voltage">
      %two = "nodal.analog_integer_literal"() <{metadata = {}, value = 2 : i64}> : () -> !nodal.quantity<"integer", "1">
      %four = "nodal.analog_integer_literal"() <{metadata = {}, value = 4 : i64}> : () -> !nodal.quantity<"integer", "1">
      %six = "nodal.analog_add"(%two, %four) <{metadata = {identity = "six"}}> : (!nodal.quantity<"integer", "1">, !nodal.quantity<"integer", "1">) -> !nodal.quantity<"integer", "1">
      %two_exact = "nodal.analog_div"(%four, %two) <{metadata = {identity = "two_exact"}}> : (!nodal.quantity<"integer", "1">, !nodal.quantity<"integer", "1">) -> !nodal.quantity<"integer", "1">
      %three = "nodal.real_literal"() <{metadata = {}, value = 3.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %mixed = "nodal.analog_mul"(%six, %three) <{metadata = {identity = "mixed"}}> : (!nodal.quantity<"integer", "1">, !nodal.quantity<"real", "1">) -> !nodal.quantity<"real", "1">
    }) : () -> ()
  }) : () -> ()
}
'''
write("core/compiler/test/IR/analog-numeric-typing.mlir", POSITIVE_FIXTURE)

INVALID_DIMENSION = r'''module {
  "nodal.unit"() <{dimension = "voltage", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Volt", symbol = "V"}> : () -> ()
  "nodal.unit"() <{dimension = "current", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Amp", symbol = "A"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "BadDimension"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "V", type = f64, unit = @Volt, variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "I", type = f64, unit = @Amp, variability = "fixed"}> : () -> ()
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %v = "nodal.parameter_ref"() <{metadata = {}, parameter = @V}> : () -> !nodal.quantity<"real", "voltage">
      %i = "nodal.parameter_ref"() <{metadata = {}, parameter = @I}> : () -> !nodal.quantity<"real", "current">
      %bad = "nodal.analog_add"(%v, %i) <{metadata = {}}> : (!nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "current">) -> !nodal.quantity<"real", "voltage">
    }) : () -> ()
  }) : () -> ()
}
'''
write("core/compiler/test/IR/analog-numeric-invalid-dimension.mlir", INVALID_DIMENSION)

INVALID_LOGIC = r'''module {
  "nodal.module"() <{metadata = {}, sym_name = "BadLogic"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %one = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %bad = "nodal.analog_logic"(%one) <{metadata = {}, operator_name = "not"}> : (!nodal.quantity<"real", "1">) -> i1
    }) : () -> ()
  }) : () -> ()
}
'''
write("core/compiler/test/IR/analog-numeric-invalid-logic.mlir", INVALID_LOGIC)

INVALID_SELECT = r'''module {
  "nodal.module"() <{metadata = {}, sym_name = "BadSelect"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %one = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %two = "nodal.real_literal"() <{metadata = {}, value = 2.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %bad = "nodal.analog_select"(%one, %one, %two) <{metadata = {}}> : (!nodal.quantity<"real", "1">, !nodal.quantity<"real", "1">, !nodal.quantity<"real", "1">) -> !nodal.quantity<"real", "1">
    }) : () -> ()
  }) : () -> ()
}
'''
write("core/compiler/test/IR/analog-numeric-invalid-select.mlir", INVALID_SELECT)

INVALID_DIVIDE = r'''module {
  "nodal.module"() <{metadata = {}, sym_name = "BadDivide"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %four = "nodal.analog_integer_literal"() <{metadata = {}, value = 4 : i64}> : () -> !nodal.quantity<"integer", "1">
      %zero = "nodal.analog_integer_literal"() <{metadata = {}, value = 0 : i64}> : () -> !nodal.quantity<"integer", "1">
      %bad = "nodal.analog_div"(%four, %zero) <{metadata = {}}> : (!nodal.quantity<"integer", "1">, !nodal.quantity<"integer", "1">) -> !nodal.quantity<"real", "1">
    }) : () -> ()
  }) : () -> ()
}
'''
write("core/compiler/test/IR/analog-numeric-invalid-divide.mlir", INVALID_DIVIDE)

INVALID_PROMOTION = r'''module {
  "nodal.module"() <{metadata = {}, sym_name = "BadPromotion"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = true, metadata = {}, parameter_kind = "boolean", sym_name = "B", type = i1, variability = "fixed"}> : () -> ()
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %b = "nodal.parameter_ref"() <{metadata = {}, parameter = @B}> : () -> i1
      %one = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %bad = "nodal.analog_add"(%b, %one) <{metadata = {}}> : (i1, !nodal.quantity<"real", "1">) -> !nodal.quantity<"real", "1">
    }) : () -> ()
  }) : () -> ()
}
'''
write("core/compiler/test/IR/analog-numeric-invalid-promotion.mlir", INVALID_PROMOTION)

INVALID_COMPARE = r'''module {
  "nodal.module"() <{metadata = {}, sym_name = "BadCompare"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %v = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"real", "voltage">
      %i = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"real", "current">
      %bad = "nodal.analog_compare"(%v, %i) <{metadata = {}, predicate = "lt"}> : (!nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "current">) -> i1
    }) : () -> ()
  }) : () -> ()
}
'''
write("core/compiler/test/IR/analog-numeric-invalid-compare.mlir", INVALID_COMPARE)

INVALID_TYPE = r'''module {
  "nodal.module"() <{metadata = {}, sym_name = "BadType"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %bad = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"integer", "1">
    }) : () -> ()
  }) : () -> ()
}
'''
write("core/compiler/test/IR/analog-numeric-invalid-type.mlir", INVALID_TYPE)

BACKEND_FIXTURE = r'''module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "readable",
  nodal.backend.naming = "semantic",
  nodal.backend.shaped_layout = "flat_packed",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {}, sym_name = "QuantityBackend"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-input"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {identity = "p_n"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %two = "nodal.analog_integer_literal"() <{metadata = {}, value = 2 : i64}> : () -> !nodal.quantity<"integer", "1">
      %three = "nodal.real_literal"() <{metadata = {}, value = 3.5 : f64}> : () -> !nodal.quantity<"real", "1">
      %product = "nodal.analog_mul"(%two, %three) <{metadata = {}}> : (!nodal.quantity<"integer", "1">, !nodal.quantity<"real", "1">) -> !nodal.quantity<"real", "1">
      %zero = "nodal.analog_integer_literal"() <{metadata = {}, value = 0 : i64}> : () -> !nodal.quantity<"integer", "1">
      %positive = "nodal.analog_compare"(%product, %zero) <{metadata = {}, predicate = "gt"}> : (!nodal.quantity<"real", "1">, !nodal.quantity<"integer", "1">) -> i1
      %selected = "nodal.analog_select"(%positive, %product, %three) <{metadata = {}}> : (i1, !nodal.quantity<"real", "1">, !nodal.quantity<"real", "1">) -> !nodal.quantity<"real", "1">
      "nodal.contribute"(%branch, %selected) <{kind = "flow", metadata = {}}> : (!nodal.branch<"electrical">, !nodal.quantity<"real", "1">) -> ()
    }) : () -> ()
  }) : () -> ()
}
'''
write("core/compiler/test/IR/analog-numeric-backend.mlir", BACKEND_FIXTURE)

replace_once(
    "core/compiler/test/CMakeLists.txt",
    "add_custom_target(check-nodal-native\n",
    r'''add_test(
  NAME nodal.native.analog-numeric-typing
  COMMAND nodalc
    "--pass-pipeline=builtin.module(nodal-fold-analog-constants,nodal-verify-analog-numeric)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-numeric-typing.mlir"
)
set_tests_properties(
  nodal.native.analog-numeric-typing
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]folded_value"
)

foreach(_fixture IN ITEMS type promotion dimension compare logic select divide)
  add_test(
    NAME nodal.native.analog-numeric-rejects-${_fixture}
    COMMAND nodalc
      "--pass-pipeline=builtin.module(nodal-verify-analog-numeric)"
      "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-numeric-invalid-${_fixture}.mlir"
  )
  set_tests_properties(
    nodal.native.analog-numeric-rejects-${_fixture}
    PROPERTIES
      WILL_FAIL TRUE
  )
endforeach()

add_test(
  NAME nodal.native.analog-numeric-backend
  COMMAND nodal-translate
    --nodal-to-verilog-a
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-numeric-backend.mlir"
)
set_tests_properties(
  nodal.native.analog-numeric-backend
  PROPERTIES
    PASS_REGULAR_EXPRESSION "I[(]p, n[)] <[+]"
)

add_custom_target(check-nodal-native
''',
)

# Advance the implementation manifest without closing roadmap evidence.
manifest_path = ROOT / "tests/compiler/fixtures/increment30/manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
manifest["status"] = "implemented-awaiting-evidence"
manifest["implementation"] = {
    "quantity_type": "Nodal_QuantityType",
    "type_helper": "AnalogNumericTypeInfo",
    "verify_pass": "nodal-verify-analog-numeric",
    "fold_pass": "nodal-fold-analog-constants",
    "folding_form": "provenance-retaining-annotations",
    "backend_erasure": "verifyAnalogQuantityErasure",
    "positive_fixture": "core/compiler/test/IR/analog-numeric-typing.mlir",
    "backend_fixture": "core/compiler/test/IR/analog-numeric-backend.mlir",
}
manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

IMPLEMENTATION_NOTE = r'''# Increment 30 — Analog numeric types and expression typing

Increment 30 is implemented on the fully validated Increment 29 baseline while
the public API remains v0.3 and the roadmap item stays unchecked pending CI,
review, merge, and evidence closure.

## Implemented

- Target-neutral `!nodal.quantity<"integer"|"real", "canonical-dimension">`.
- Canonical exponent algebra for multiplication, division, and typed `ddt`.
- Deterministic integer-to-real promotion without Boolean numeric truthiness.
- Quantity-aware literals, parameter references, arithmetic, negation,
  comparisons, Boolean-only logical operations, and conditional selection.
- Shared native inference and verification through
  `nodal-verify-analog-numeric`.
- Pure constant folding through `nodal-fold-analog-constants`, recorded as
  provenance-retaining annotations so authored source operations are not
  erased.
- Static zero-divisor and non-finite-fold diagnostics.
- Verified scalar erasure at the Verilog-A/Verilog-AMS backend boundary.
- Positive, negative, folding, legacy-f64, and native-backend fixtures.

Increment 31 retains ownership of final potential/flow access-function
resolution and discipline-nature result dimensions. Increment 32 retains
first-class equations and contribution interaction. Increment 35 retains the
state and operational semantics of `ddt` and `idt`.
'''
write("docs/implementation/increment30-analog-numeric-types.md", IMPLEMENTATION_NOTE)

# Strengthen the permanent repository checker for the actual implementation.
checker = read("scripts/check_increment30.py")
checker = checker.replace(
    '"""Validate the Increment 30 analog numeric typing implementation-start contract."""',
    '"""Validate the Increment 30 analog numeric typing implementation contract."""',
)
checker = checker.replace(
    '    "tests/compiler/fixtures/increment29/manifest.json",\n)',
    '''    "tests/compiler/fixtures/increment29/manifest.json",
    "core/compiler/include/nodal/Dialect/Nodal/AnalogNumeric.h",
    "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp",
    "core/compiler/include/nodal/Dialect/Nodal/NodalTypes.td",
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "core/compiler/lib/Dialect/Nodal/NodalTypes.cpp",
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    "core/compiler/lib/Transforms/Passes.cpp",
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    "core/compiler/diagnostics-v0.1.json",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/IR/analog-numeric-typing.mlir",
    "core/compiler/test/IR/analog-numeric-backend.mlir",
    "core/compiler/test/IR/analog-numeric-invalid-type.mlir",
    "core/compiler/test/IR/analog-numeric-invalid-promotion.mlir",
    "core/compiler/test/IR/analog-numeric-invalid-dimension.mlir",
    "core/compiler/test/IR/analog-numeric-invalid-compare.mlir",
    "core/compiler/test/IR/analog-numeric-invalid-logic.mlir",
    "core/compiler/test/IR/analog-numeric-invalid-select.mlir",
    "core/compiler/test/IR/analog-numeric-invalid-divide.mlir",
)''',
)
checker = checker.replace(
    '            "analog-numeric-surface.json",\n        ),',
    '''            "analog-numeric-surface.json",
            "nodal-verify-analog-numeric",
            "nodal-fold-analog-constants",
            "analog-numeric-typing.mlir",
            "analog-numeric-backend.mlir",
        ),''',
)
checker = checker.replace(
    '    if manifest.get("status") not in {',
    '''    if manifest.get("status") == "implementation-started":
        problems.append(Problem("NODAL-INC30-007", "native implementation has not been materialized"))
    if manifest.get("status") not in {''',
)
checker = checker.replace(
    '    if predecessor.get("status") != "validated-parameter-constant-unit":',
    '''    native_contracts = {
        "core/compiler/include/nodal/Dialect/Nodal/NodalTypes.td": (
            "Nodal_QuantityType", "canonical physical dimension signature"),
        "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td": (
            "Nodal_AnalogIntegerLiteralOp", "Nodal_AnalogCompareOp",
            "Nodal_AnalogLogicOp", "Nodal_AnalogSelectOp"),
        "core/compiler/include/nodal/Dialect/Nodal/AnalogNumeric.h": (
            "AnalogNumericTypeInfo", "verifyAnalogNumericModel",
            "foldAnalogNumericConstants", "verifyAnalogQuantityErasure"),
        "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp": (
            "combineAnalogDimensions", "NODAL-ANALOG-PROMOTION-001",
            "nodal.folded_provenance", "NODAL-ANALOG-DIVIDE-001"),
        "core/compiler/lib/Transforms/Passes.cpp": (
            "nodal-fold-analog-constants", "nodal-verify-analog-numeric"),
        "core/compiler/lib/Backend/AnalogVerticalSlice.cpp": (
            "verifyAnalogQuantityErasure", "nodal.analog_select",
            "renderFoldedExpression"),
        "core/compiler/test/CMakeLists.txt": (
            "nodal.native.analog-numeric-typing", "analog-numeric-rejects"),
    }
    for relative, fragments in native_contracts.items():
        require(read(root / relative, problems, "NODAL-INC30-010"), fragments, problems,
                "NODAL-INC30-010", relative)

    diagnostics = load_json(root / "core/compiler/diagnostics-v0.1.json", problems,
                            "NODAL-INC30-011")
    catalog = diagnostics.get("families", {}).get("analog-numeric-typing", [])
    for code in PLANNED_DIAGNOSTICS:
        if code not in catalog:
            problems.append(Problem("NODAL-INC30-011", f"diagnostic catalog lacks: {code}"))

    if predecessor.get("status") != "validated-parameter-constant-unit":''',
)
checker = checker.replace(
    '    print("Increment 30 analog numeric typing start contract passed")',
    '    print("Increment 30 analog numeric typing implementation contract passed")',
)
write("scripts/check_increment30.py", checker)

# Add mutation coverage that fails if implementation anchors disappear.
tests = read("tests/compiler/test_increment30.py")
insert = r'''
    def test_rejects_missing_native_quantity_type(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/include/nodal/Dialect/Nodal/NodalTypes.td"
        path.write_text(path.read_text(encoding="utf-8").replace("Nodal_QuantityType", "MissingQuantityType", 1), encoding="utf-8")
        self.assertIn("NODAL-INC30-010", self.codes(root))

    def test_rejects_missing_native_verifier_pass(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Transforms/Passes.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace("nodal-verify-analog-numeric", "missing-analog-verifier", 1), encoding="utf-8")
        self.assertIn("NODAL-INC30-010", self.codes(root))

    def test_rejects_missing_folding_provenance(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace("nodal.folded_provenance", "lost.folded.provenance", 1), encoding="utf-8")
        self.assertIn("NODAL-INC30-010", self.codes(root))

    def test_rejects_missing_backend_quantity_gate(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/AnalogVerticalSlice.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace("verifyAnalogQuantityErasure", "skipQuantityVerification", 1), encoding="utf-8")
        self.assertIn("NODAL-INC30-010", self.codes(root))

    def test_rejects_missing_implemented_status(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "tests/compiler/fixtures/increment30/manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["status"] = "implementation-started"
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-INC30-007", self.codes(root))
'''
tests = tests.replace('\n\nif __name__ == "__main__":', insert + '\n\nif __name__ == "__main__":')
write("tests/compiler/test_increment30.py", tests)

WORKFLOW = r'''name: Increment 30 Analog Numeric Types and Expression Typing

on:
  push:
    branches:
      - increment/30-analog-numeric-types
  pull_request:
    branches:
      - dev
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: increment-30-${{ github.ref }}
  cancel-in-progress: true

jobs:
  analog-numeric-types:
    name: increment-30/analog-numeric-types
    runs-on: ubuntu-24.04
    timeout-minutes: 120

    steps:
      - name: Check out repository
        uses: actions/checkout@v6
        with:
          fetch-depth: 0

      - name: Restore locked native and Scala caches
        uses: actions/cache@v5
        with:
          path: |
            ~/.cache/nodal/downloads
            ~/.cache/coursier
            ~/.cache/nodal/mill
          key: nodal-inc30-${{ runner.os }}-${{ hashFiles('build.mill', '.mill-version', 'mill', 'toolchains/lock.json', 'toolchains/checksums/*.sha256', 'toolchains/lint-lock.json') }}
          restore-keys: |
            nodal-inc30-${{ runner.os }}-
            nodal-inc29-${{ runner.os }}-
            nodal-inc28-${{ runner.os }}-
            nodal-scala-${{ runner.os }}-

      - name: Install locked native and lint toolchains
        run: |
          ./nodal bootstrap \
            --mode prebuilt \
            --prefix "${RUNNER_TEMP}/nodal-native-toolchain"
          ./nodal style bootstrap \
            --prefix "${RUNNER_TEMP}/nodal-lint-toolchain"

      - name: Validate Increment 30 implementation contracts and mutations
        env:
          PYTHONDONTWRITEBYTECODE: '1'
        run: |
          for increment in $(seq 18 30); do
            python3 "scripts/check_increment${increment}.py"
          done
          python3 scripts/check_increment30.py
          python3 tests/compiler/test_increment30.py
          python3 -m unittest discover \
            -s tests/compiler \
            -p 'test_*.py'
          ./nodal check \
            --contracts-only \
            --online-toolchain \
            --lint-toolchain "${RUNNER_TEMP}/nodal-lint-toolchain" \
            --base-ref origin/dev
          git diff --check

      - name: Build lint and test native core
        run: |
          ./nodal core native \
            --toolchain "${RUNNER_TEMP}/nodal-native-toolchain" \
            --lint-toolchain "${RUNNER_TEMP}/nodal-lint-toolchain"

      - name: Prove quantity inference folding diagnostics and backend erasure
        run: |
          set -euo pipefail
          compiler="${PWD}/out/native/release/bin/nodalc"
          translator="${PWD}/out/native/release/bin/nodal-translate"
          pipeline='builtin.module(nodal-fold-analog-constants,nodal-verify-analog-numeric)'

          "${compiler}" \
            --pass-pipeline="${pipeline}" \
            core/compiler/test/IR/analog-numeric-typing.mlir \
            | tee /tmp/analog-numeric-typing.mlir
          grep -F '!nodal.quantity<"real", "current*voltage">' /tmp/analog-numeric-typing.mlir
          grep -F '!nodal.quantity<"real", "time^-1*voltage">' /tmp/analog-numeric-typing.mlir
          grep -F 'nodal.folded_provenance = "increment30"' /tmp/analog-numeric-typing.mlir
          grep -F 'nodal.folded_value = 6' /tmp/analog-numeric-typing.mlir

          check_rejection() {
            fixture="$1"
            code="$2"
            if "${compiler}" \
                --pass-pipeline='builtin.module(nodal-verify-analog-numeric)' \
                "core/compiler/test/IR/analog-numeric-invalid-${fixture}.mlir" \
                >"/tmp/${fixture}.out" 2>"/tmp/${fixture}.err"; then
              echo "invalid ${fixture} analog numeric model was accepted" >&2
              exit 1
            fi
            grep -F "${code}" "/tmp/${fixture}.err"
          }

          check_rejection type NODAL-ANALOG-TYPE-001
          check_rejection promotion NODAL-ANALOG-PROMOTION-001
          check_rejection dimension NODAL-ANALOG-DIMENSION-001
          check_rejection compare NODAL-ANALOG-COMPARE-001
          check_rejection logic NODAL-ANALOG-LOGIC-001
          check_rejection select NODAL-ANALOG-SELECT-001
          check_rejection divide NODAL-ANALOG-DIVIDE-001

          "${translator}" \
            --nodal-to-verilog-a \
            core/compiler/test/IR/analog-numeric-backend.mlir \
            | tee /tmp/analog-numeric-backend.va
          grep -F 'I(p, n) <+ ((((2 * 3.5) > 0) ? (2 * 3.5) : 3.5));' \
            /tmp/analog-numeric-backend.va

          python3 - <<'PY'
          import json
          from pathlib import Path
          manifest = json.loads(Path("tests/compiler/fixtures/increment30/manifest.json").read_text())
          assert manifest["status"] == "implemented-awaiting-evidence"
          assert manifest["implementation"]["verify_pass"] == "nodal-verify-analog-numeric"
          assert manifest["implementation"]["fold_pass"] == "nodal-fold-analog-constants"
          roadmap = Path("docs/roadmap/nodal-development-todo.md").read_text()
          assert "- [x] **Increment 29 — Parameters, constants, ranges, and units**" in roadmap
          assert "- [ ] **Increment 30 — Analog numeric types and expression typing**" in roadmap
          PY
'''
write(".github/workflows/increment-30-analog-numeric-types.yml", WORKFLOW)

print("Increment 30 native numeric implementation materialized")
