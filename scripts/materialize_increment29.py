#!/usr/bin/env python3
"""Materialize Increment 29 parameter, constant-expression, range, and unit semantics."""

from __future__ import annotations

import json
from pathlib import Path
from textwrap import dedent

ROOT = Path(__file__).resolve().parents[1]


def write(relative: str, content: str) -> None:
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(dedent(content).lstrip("\n"), encoding="utf-8")


def replace_once(relative: str, old: str, new: str) -> None:
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise RuntimeError(f"expected one anchor in {relative}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# ---------------------------------------------------------------------------
# Compiler IR surface
# ---------------------------------------------------------------------------

replace_once(
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    r'''def Nodal_ParameterOp : Nodal_Op<"parameter",
    [HasParent<"ModuleOp">, Symbol]> {
  let summary = "Symbolic target-visible module parameter";
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    TypeAttr:$type,
    AnyAttr:$default_value,
    StrAttr:$variability,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}
''',
    r'''def Nodal_ParameterOp : Nodal_Op<"parameter",
    [HasParent<"ModuleOp">, Symbol]> {
  let summary = "Typed fixed, ordinary, or structural target-visible parameter";
  let description = [{
    Parameters retain a canonical real, integer, or Boolean kind; fixed versus
    symbolic variability; ordinary versus structural role; optional physical
    unit; range/exclusion constraints; and an optional structural envelope.
    `default_value` is either a legacy typed scalar or a canonical dictionary
    constant expression. General dynamic analog expression typing remains
    outside this operation.
  }];
  let arguments = (ins
    SymbolNameAttr:$sym_name,
    TypeAttr:$type,
    AnyAttr:$default_value,
    StrAttr:$variability,
    OptionalAttr<StrAttr>:$kind,
    OptionalAttr<StrAttr>:$role,
    OptionalAttr<StrAttr>:$unit,
    OptionalAttr<ArrayAttr>:$constraints,
    OptionalAttr<DictionaryAttr>:$structural_envelope,
    DictionaryAttr:$metadata
  );
  let hasVerifier = 1;
}
''',
)

write(
    "core/compiler/include/nodal/Dialect/Nodal/ParameterSemantics.h",
    r'''
#ifndef NODAL_DIALECT_NODAL_PARAMETERSEMANTICS_H
#define NODAL_DIALECT_NODAL_PARAMETERSEMANTICS_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/SmallVector.h"

#include <string>

namespace nodal {

/// Verify declaration-local parameter kind, role, unit, and variability shape.
mlir::LogicalResult verifyParameterDeclaration(mlir::Operation *parameter);

/// Verify module defaults, constant-expression dependencies, constraints,
/// structural envelopes, instance overrides, and dynamic-value separation.
mlir::LogicalResult verifyParameterSemantics(mlir::Operation *builtinModule);

/// Return a deterministic dependency-before-user declaration order.
mlir::FailureOr<llvm::SmallVector<mlir::Operation *, 8>>
parameterDeclarationOrder(mlir::Operation *module);

/// Return native Verilog-A/Verilog-AMS spelling without decimal round-trips.
mlir::FailureOr<std::string> renderParameterDefault(mlir::Operation *parameter);
mlir::FailureOr<llvm::SmallVector<std::string, 4>>
renderParameterConstraints(mlir::Operation *parameter);
mlir::FailureOr<std::string> parameterNativeType(mlir::Operation *parameter);
mlir::FailureOr<bool> parameterIsFixed(mlir::Operation *parameter);

} // namespace nodal

#endif // NODAL_DIALECT_NODAL_PARAMETERSEMANTICS_H
''',
)

write(
    "core/compiler/lib/Dialect/Nodal/ParameterSemantics.cpp",
    r'''
#include "nodal/Dialect/Nodal/ParameterSemantics.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/BuiltinTypes.h"
#include "mlir/IR/SymbolTable.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/STLExtras.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/ADT/StringSet.h"
#include "llvm/ADT/Twine.h"
#include "llvm/Support/Casting.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <charconv>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <limits>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <system_error>
#include <utility>
#include <vector>

using namespace mlir;

namespace {

enum class ParameterKind { Real, Integer, Boolean };
enum class ParameterRole { Ordinary, Structural };

struct UnitSpec {
  std::string token;
  std::string dimension;
  std::string scaleSpelling;
  long double scale = 1.0L;
};

struct EvaluatedValue {
  ParameterKind kind = ParameterKind::Integer;
  std::string dimension = "dimensionless";
  long double realValue = 0.0L;
  int64_t integerValue = 0;
  bool booleanValue = false;
  std::string rendered;
};

struct EvaluationContext {
  explicit EvaluationContext(Operation *module) : module(module) {}

  Operation *module;
  llvm::DenseMap<Operation *, EvaluatedValue> cache;
  llvm::DenseSet<Operation *> active;
};

LogicalResult fail(Operation *operation, llvm::StringRef code, const llvm::Twine &message) {
  return operation->emitOpError() << code << ": " << message;
}

template <typename T>
FailureOr<T> failValue(Operation *operation, llvm::StringRef code, const llvm::Twine &message) {
  (void)fail(operation, code, message);
  return failure();
}

bool oneOf(llvm::StringRef value, std::initializer_list<llvm::StringRef> choices) {
  return llvm::any_of(choices, [&](llvm::StringRef choice) { return value == choice; });
}

bool canonicalText(llvm::StringRef value) {
  if (value.empty() || value != value.trim())
    return false;
  return llvm::all_of(value, [](char character) {
    const unsigned char byte = static_cast<unsigned char>(character);
    return byte >= 0x20 && byte != 0x7f;
  });
}

Operation *enclosingNodalModule(Operation *operation) {
  for (Operation *current = operation; current; current = current->getParentOp()) {
    if (current->getName().getStringRef() == "nodal.module")
      return current;
  }
  return nullptr;
}

Block *moduleBody(Operation *module) {
  if (!module || module->getName().getStringRef() != "nodal.module" ||
      module->getNumRegions() != 1 || module->getRegion(0).empty())
    return nullptr;
  return &module->getRegion(0).front();
}

llvm::StringRef symbolName(Operation *operation) {
  if (auto symbol = operation->getAttrOfType<StringAttr>(SymbolTable::getSymbolAttrName()))
    return symbol.getValue();
  return {};
}

Operation *findDirectParameter(Operation *module, llvm::StringRef symbol) {
  Block *body = moduleBody(module);
  if (!body)
    return nullptr;
  for (Operation &candidate : *body) {
    if (candidate.getName().getStringRef() == "nodal.parameter" &&
        symbolName(&candidate) == symbol)
      return &candidate;
  }
  return nullptr;
}

std::optional<std::pair<std::string, long double>> prefixInfo(llvm::StringRef prefix) {
  if (prefix.empty())
    return std::make_pair(std::string("1"), 1.0L);
  if (prefix == "f")
    return std::make_pair(std::string("1e-15"), 1.0e-15L);
  if (prefix == "p")
    return std::make_pair(std::string("1e-12"), 1.0e-12L);
  if (prefix == "n")
    return std::make_pair(std::string("1e-9"), 1.0e-9L);
  if (prefix == "u")
    return std::make_pair(std::string("1e-6"), 1.0e-6L);
  if (prefix == "m")
    return std::make_pair(std::string("1e-3"), 1.0e-3L);
  if (prefix == "k")
    return std::make_pair(std::string("1e3"), 1.0e3L);
  if (prefix == "M")
    return std::make_pair(std::string("1e6"), 1.0e6L);
  if (prefix == "G")
    return std::make_pair(std::string("1e9"), 1.0e9L);
  if (prefix == "T")
    return std::make_pair(std::string("1e12"), 1.0e12L);
  return std::nullopt;
}

std::optional<UnitSpec> lookupUnit(llvm::StringRef token) {
  if (!canonicalText(token))
    return std::nullopt;
  if (token == "1")
    return UnitSpec{"1", "dimensionless", "1", 1.0L};

  static constexpr std::pair<llvm::StringLiteral, llvm::StringLiteral> bases[] = {
      {"Ohm", "resistance"}, {"Hz", "frequency"}, {"V", "voltage"},
      {"A", "current"},      {"F", "capacitance"}, {"H", "inductance"},
      {"K", "temperature"}, {"s", "time"},
  };
  for (const auto &[base, dimension] : bases) {
    if (!token.ends_with(base))
      continue;
    llvm::StringRef prefix = token.drop_back(base.size());
    auto scale = prefixInfo(prefix);
    if (!scale)
      continue;
    return UnitSpec{token.str(), dimension.str(), scale->first, scale->second};
  }
  return std::nullopt;
}

FailureOr<long double> parseReal(Operation *owner, llvm::StringRef spelling) {
  if (!canonicalText(spelling))
    return failValue<long double>(owner, "NODAL-PARAMETER-EXPRESSION-001",
                                  "real literal spelling must be canonical text");
  std::string owned = spelling.str();
  char *end = nullptr;
  errno = 0;
  const long double value = std::strtold(owned.c_str(), &end);
  if (errno == ERANGE || end != owned.c_str() + owned.size() || !std::isfinite(value))
    return failValue<long double>(owner, "NODAL-PARAMETER-EXPRESSION-001",
                                  llvm::Twine("invalid finite real literal '") + spelling + "'");
  return value;
}

FailureOr<int64_t> parseInteger(Operation *owner, llvm::StringRef spelling) {
  int64_t value = 0;
  if (!canonicalText(spelling) || spelling.getAsInteger(10, value))
    return failValue<int64_t>(owner, "NODAL-PARAMETER-EXPRESSION-001",
                              llvm::Twine("invalid signed 64-bit integer literal '") + spelling +
                                  "'");
  return value;
}

std::string formatLegacyReal(double value) {
  std::array<char, 128> buffer{};
  auto result = std::to_chars(buffer.data(), buffer.data() + buffer.size(), value,
                              std::chars_format::general,
                              std::numeric_limits<double>::max_digits10);
  if (result.ec != std::errc())
    return {};
  return std::string(buffer.data(), result.ptr);
}

std::string scaledRendering(llvm::StringRef spelling, const UnitSpec &unit) {
  if (unit.scaleSpelling == "1")
    return spelling.str();
  return (llvm::Twine("(") + spelling + " * " + unit.scaleSpelling + ")").str();
}

FailureOr<ParameterKind> declaredKind(Operation *parameter) {
  auto type = parameter->getAttrOfType<TypeAttr>("type");
  if (!type)
    return failValue<ParameterKind>(parameter, "NODAL-PARAMETER-KIND-001",
                                    "parameter type is required");

  std::optional<ParameterKind> inferred;
  if (type.getValue().isF64()) {
    inferred = ParameterKind::Real;
  } else if (auto integer = llvm::dyn_cast<IntegerType>(type.getValue())) {
    if (integer.getWidth() == 1)
      inferred = ParameterKind::Boolean;
    else if (integer.getWidth() <= 64)
      inferred = ParameterKind::Integer;
  }
  if (!inferred)
    return failValue<ParameterKind>(parameter, "NODAL-PARAMETER-KIND-001",
                                    "supported parameter types are f64, i1, and integers up to 64 bits");

  auto kind = parameter->getAttrOfType<StringAttr>("kind");
  if (!kind)
    return *inferred;
  ParameterKind explicitKind;
  if (kind.getValue() == "real")
    explicitKind = ParameterKind::Real;
  else if (kind.getValue() == "integer")
    explicitKind = ParameterKind::Integer;
  else if (kind.getValue() == "boolean")
    explicitKind = ParameterKind::Boolean;
  else
    return failValue<ParameterKind>(parameter, "NODAL-PARAMETER-KIND-001",
                                    llvm::Twine("unsupported parameter kind '") + kind.getValue() +
                                        "'");
  if (explicitKind != *inferred)
    return failValue<ParameterKind>(parameter, "NODAL-PARAMETER-KIND-001",
                                    "parameter kind does not match its declared type");
  return explicitKind;
}

FailureOr<ParameterRole> declaredRole(Operation *parameter) {
  auto role = parameter->getAttrOfType<StringAttr>("role");
  if (!role || role.getValue() == "ordinary")
    return ParameterRole::Ordinary;
  if (role.getValue() == "structural")
    return ParameterRole::Structural;
  if (role.getValue() == "dynamic")
    return failValue<ParameterRole>(parameter, "NODAL-PARAMETER-DYNAMIC-001",
                                    "dynamic values are not parameter declarations");
  return failValue<ParameterRole>(parameter, "NODAL-PARAMETER-ROLE-001",
                                  llvm::Twine("unsupported parameter role '") + role.getValue() +
                                      "'");
}

FailureOr<UnitSpec> declaredUnit(Operation *parameter) {
  llvm::StringRef token = "1";
  if (auto unit = parameter->getAttrOfType<StringAttr>("unit")) {
    token = unit.getValue();
  } else if (auto metadata = parameter->getAttrOfType<DictionaryAttr>("metadata")) {
    if (auto legacy = metadata.getAs<StringAttr>("unit"))
      token = legacy.getValue();
  }
  auto spec = lookupUnit(token);
  if (!spec)
    return failValue<UnitSpec>(parameter, "NODAL-PARAMETER-UNIT-001",
                               llvm::Twine("unsupported canonical unit '") + token + "'");
  auto kind = declaredKind(parameter);
  if (failed(kind))
    return failure();
  if (*kind != ParameterKind::Real && spec->dimension != "dimensionless")
    return failValue<UnitSpec>(parameter, "NODAL-PARAMETER-UNIT-001",
                               "integer and Boolean parameters must be dimensionless");
  return *spec;
}

FailureOr<llvm::SmallVector<std::string, 5>> structuralEffects(Operation *parameter) {
  llvm::SmallVector<std::string, 5> effects;
  auto metadata = parameter->getAttrOfType<DictionaryAttr>("metadata");
  Attribute raw = metadata ? metadata.get("structural_effects") : Attribute();
  if (!raw)
    return effects;
  auto values = llvm::dyn_cast<ArrayAttr>(raw);
  if (!values)
    return failValue<llvm::SmallVector<std::string, 5>>(
        parameter, "NODAL-PARAMETER-STRUCTURAL-001",
        "structural_effects must be an array of canonical effect names");
  llvm::StringSet<> seen;
  for (Attribute value : values) {
    auto text = llvm::dyn_cast<StringAttr>(value);
    if (!text || !oneOf(text.getValue(), {"topology", "component_count", "equation_count",
                                          "shape", "rank"}))
      return failValue<llvm::SmallVector<std::string, 5>>(
          parameter, "NODAL-PARAMETER-STRUCTURAL-001",
          "structural effects are limited to topology, component_count, equation_count, shape, and rank");
    if (!seen.insert(text.getValue()).second)
      return failValue<llvm::SmallVector<std::string, 5>>(
          parameter, "NODAL-PARAMETER-STRUCTURAL-001",
          "structural_effects must not contain duplicates");
    effects.push_back(text.getValue().str());
  }
  return effects;
}

FailureOr<EvaluatedValue> evaluateParameter(Operation *parameter, EvaluationContext &context);

FailureOr<EvaluatedValue> evaluateLegacy(Attribute attribute, ParameterKind expectedKind,
                                         const UnitSpec &unit, Operation *owner) {
  EvaluatedValue result;
  result.dimension = unit.dimension;
  if (auto value = llvm::dyn_cast<FloatAttr>(attribute)) {
    if (expectedKind != ParameterKind::Real || !std::isfinite(value.getValueAsDouble()))
      return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-DEFAULT-001",
                                       "legacy floating value requires a finite real parameter");
    std::string spelling = formatLegacyReal(value.getValueAsDouble());
    if (spelling.empty())
      return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-DEFAULT-001",
                                       "could not preserve legacy floating value");
    result.kind = ParameterKind::Real;
    result.realValue = static_cast<long double>(value.getValueAsDouble()) * unit.scale;
    result.rendered = scaledRendering(spelling, unit);
    return result;
  }
  if (auto value = llvm::dyn_cast<IntegerAttr>(attribute)) {
    if (expectedKind == ParameterKind::Boolean) {
      if (value.getInt() != 0 && value.getInt() != 1)
        return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-DEFAULT-001",
                                         "Boolean parameter value must be zero or one");
      result.kind = ParameterKind::Boolean;
      result.dimension = "dimensionless";
      result.booleanValue = value.getInt() != 0;
      result.rendered = result.booleanValue ? "1" : "0";
      return result;
    }
    if (expectedKind == ParameterKind::Integer) {
      result.kind = ParameterKind::Integer;
      result.dimension = "dimensionless";
      result.integerValue = value.getInt();
      result.rendered = std::to_string(result.integerValue);
      return result;
    }
    if (expectedKind == ParameterKind::Real) {
      result.kind = ParameterKind::Real;
      result.realValue = static_cast<long double>(value.getInt()) * unit.scale;
      result.rendered = scaledRendering(std::to_string(value.getInt()), unit);
      return result;
    }
  }
  if (auto value = llvm::dyn_cast<BoolAttr>(attribute)) {
    if (expectedKind != ParameterKind::Boolean)
      return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-DEFAULT-001",
                                       "legacy Boolean value requires a Boolean parameter");
    result.kind = ParameterKind::Boolean;
    result.dimension = "dimensionless";
    result.booleanValue = value.getValue();
    result.rendered = result.booleanValue ? "1" : "0";
    return result;
  }
  return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-DEFAULT-001",
                                   "unsupported legacy parameter value attribute");
}

long double numericValue(const EvaluatedValue &value) {
  if (value.kind == ParameterKind::Real)
    return value.realValue;
  if (value.kind == ParameterKind::Integer)
    return static_cast<long double>(value.integerValue);
  return value.booleanValue ? 1.0L : 0.0L;
}

FailureOr<EvaluatedValue> evaluateExpression(Attribute attribute, EvaluationContext &context,
                                             Operation *owner) {
  auto expression = llvm::dyn_cast<DictionaryAttr>(attribute);
  if (!expression)
    return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-EXPRESSION-001",
                                     "constant expression must be a canonical dictionary");
  auto op = expression.getAs<StringAttr>("op");
  if (!op)
    return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-EXPRESSION-001",
                                     "constant expression requires an op field");

  if (op.getValue() == "literal") {
    auto spelling = expression.getAs<StringAttr>("spelling");
    auto unit = expression.getAs<StringAttr>("unit");
    if (!spelling || !unit)
      return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-EXPRESSION-001",
                                       "literal expression requires spelling and explicit unit");
    auto spec = lookupUnit(unit.getValue());
    if (!spec)
      return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-UNIT-001",
                                       llvm::Twine("unsupported literal unit '") + unit.getValue() +
                                           "'");

    EvaluatedValue result;
    result.dimension = spec->dimension;
    if (spelling.getValue() == "true" || spelling.getValue() == "false") {
      if (spec->dimension != "dimensionless")
        return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-UNIT-001",
                                         "Boolean literals must be dimensionless");
      result.kind = ParameterKind::Boolean;
      result.booleanValue = spelling.getValue() == "true";
      result.rendered = result.booleanValue ? "1" : "0";
      return result;
    }

    const bool realLiteral = spec->dimension != "dimensionless" ||
                             spelling.getValue().contains('.') ||
                             spelling.getValue().contains_insensitive('e');
    if (realLiteral) {
      auto parsed = parseReal(owner, spelling.getValue());
      if (failed(parsed))
        return failure();
      result.kind = ParameterKind::Real;
      result.realValue = *parsed * spec->scale;
      if (!std::isfinite(result.realValue))
        return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-EXPRESSION-001",
                                         "scaled real literal is not finite");
      result.rendered = scaledRendering(spelling.getValue(), *spec);
      return result;
    }

    auto parsed = parseInteger(owner, spelling.getValue());
    if (failed(parsed))
      return failure();
    result.kind = ParameterKind::Integer;
    result.integerValue = *parsed;
    result.rendered = spelling.getValue().str();
    return result;
  }

  if (op.getValue() == "ref") {
    auto reference = expression.getAs<FlatSymbolRefAttr>("parameter");
    if (!reference)
      return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-REFERENCE-001",
                                       "parameter reference expression requires a flat symbol");
    Operation *target = findDirectParameter(context.module, reference.getValue());
    if (!target)
      return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-DYNAMIC-001",
                                       llvm::Twine("reference '") + reference.getValue() +
                                           "' is not a parameter in this module");
    auto value = evaluateParameter(target, context);
    if (failed(value))
      return failure();
    value->rendered = reference.getValue().str();
    return *value;
  }

  if (op.getValue() == "neg") {
    Attribute operand = expression.get("operand");
    if (!operand)
      return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-EXPRESSION-001",
                                       "neg expression requires one operand");
    auto value = evaluateExpression(operand, context, owner);
    if (failed(value))
      return failure();
    if (value->kind == ParameterKind::Boolean)
      return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-EXPRESSION-001",
                                       "Boolean constant expressions cannot be negated");
    if (value->kind == ParameterKind::Integer) {
      if (value->integerValue == std::numeric_limits<int64_t>::min())
        return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-EXPRESSION-001",
                                         "integer negation overflows signed 64-bit range");
      value->integerValue = -value->integerValue;
    } else {
      value->realValue = -value->realValue;
    }
    value->rendered = (llvm::Twine("(-") + value->rendered + ")").str();
    return *value;
  }

  if (!oneOf(op.getValue(), {"add", "sub", "mul", "div"}))
    return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-EXPRESSION-001",
                                     llvm::Twine("unsupported constant-expression op '") +
                                         op.getValue() + "'");
  Attribute lhsAttribute = expression.get("lhs");
  Attribute rhsAttribute = expression.get("rhs");
  if (!lhsAttribute || !rhsAttribute)
    return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-EXPRESSION-001",
                                     "binary constant expression requires lhs and rhs");
  auto lhs = evaluateExpression(lhsAttribute, context, owner);
  auto rhs = evaluateExpression(rhsAttribute, context, owner);
  if (failed(lhs) || failed(rhs))
    return failure();
  if (lhs->kind == ParameterKind::Boolean || rhs->kind == ParameterKind::Boolean)
    return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-EXPRESSION-001",
                                     "Boolean values are not arithmetic operands");

  EvaluatedValue result;
  result.kind = lhs->kind == ParameterKind::Real || rhs->kind == ParameterKind::Real
                    ? ParameterKind::Real
                    : ParameterKind::Integer;
  llvm::StringRef spelling = op.getValue() == "add"   ? "+"
                             : op.getValue() == "sub" ? "-"
                             : op.getValue() == "mul" ? "*"
                                                        : "/";
  result.rendered =
      (llvm::Twine("(") + lhs->rendered + " " + spelling + " " + rhs->rendered + ")")
          .str();

  if (op.getValue() == "add" || op.getValue() == "sub") {
    if (lhs->dimension != rhs->dimension)
      return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-UNIT-001",
                                       "addition and subtraction require identical dimensions");
    result.dimension = lhs->dimension;
  } else if (op.getValue() == "mul") {
    const bool lhsDimensionless = lhs->dimension == "dimensionless";
    const bool rhsDimensionless = rhs->dimension == "dimensionless";
    if (!lhsDimensionless && !rhsDimensionless)
      return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-UNIT-001",
                                       "Increment 29 multiplication permits at most one physical dimension");
    result.dimension = lhsDimensionless ? rhs->dimension : lhs->dimension;
  } else {
    if (numericValue(*rhs) == 0.0L)
      return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-EXPRESSION-001",
                                       "constant-expression division by zero");
    if (rhs->dimension == "dimensionless") {
      result.dimension = lhs->dimension;
    } else if (lhs->dimension == rhs->dimension) {
      result.dimension = "dimensionless";
    } else {
      return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-UNIT-001",
                                       "division requires a dimensionless denominator or matching dimensions");
    }
  }

  if (result.kind == ParameterKind::Integer) {
    const __int128 left = lhs->integerValue;
    const __int128 right = rhs->integerValue;
    __int128 computed = 0;
    if (op.getValue() == "add")
      computed = left + right;
    else if (op.getValue() == "sub")
      computed = left - right;
    else if (op.getValue() == "mul")
      computed = left * right;
    else {
      if (left % right != 0)
        return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-EXPRESSION-001",
                                         "integer division must be exact");
      computed = left / right;
    }
    if (computed < std::numeric_limits<int64_t>::min() ||
        computed > std::numeric_limits<int64_t>::max())
      return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-EXPRESSION-001",
                                       "integer constant expression overflows signed 64-bit range");
    result.integerValue = static_cast<int64_t>(computed);
  } else {
    const long double left = numericValue(*lhs);
    const long double right = numericValue(*rhs);
    if (op.getValue() == "add")
      result.realValue = left + right;
    else if (op.getValue() == "sub")
      result.realValue = left - right;
    else if (op.getValue() == "mul")
      result.realValue = left * right;
    else
      result.realValue = left / right;
    if (!std::isfinite(result.realValue))
      return failValue<EvaluatedValue>(owner, "NODAL-PARAMETER-EXPRESSION-001",
                                       "real constant expression is not finite");
  }
  return result;
}

FailureOr<EvaluatedValue> coerceToParameter(EvaluatedValue value, Operation *parameter,
                                            Operation *diagnosticOwner,
                                            llvm::StringRef mismatchCode) {
  auto kind = declaredKind(parameter);
  auto unit = declaredUnit(parameter);
  if (failed(kind) || failed(unit))
    return failure();
  if (*kind == ParameterKind::Real && value.kind == ParameterKind::Integer) {
    value.kind = ParameterKind::Real;
    value.realValue = static_cast<long double>(value.integerValue);
  }
  if (value.kind != *kind)
    return failValue<EvaluatedValue>(diagnosticOwner, mismatchCode,
                                     llvm::Twine("value kind does not match parameter '") +
                                         symbolName(parameter) + "'");
  if (value.dimension != unit->dimension)
    return failValue<EvaluatedValue>(diagnosticOwner, "NODAL-PARAMETER-UNIT-001",
                                     llvm::Twine("value dimension does not match parameter '") +
                                         symbolName(parameter) + "'");
  return value;
}

FailureOr<EvaluatedValue> evaluateParameter(Operation *parameter, EvaluationContext &context) {
  if (auto cached = context.cache.find(parameter); cached != context.cache.end())
    return cached->second;
  if (!context.active.insert(parameter).second)
    return failValue<EvaluatedValue>(parameter, "NODAL-PARAMETER-CYCLE-001",
                                     llvm::Twine("constant-expression cycle reaches parameter '") +
                                         symbolName(parameter) + "'");

  auto kind = declaredKind(parameter);
  auto unit = declaredUnit(parameter);
  Attribute raw = parameter->getAttr("default_value");
  if (failed(kind) || failed(unit) || !raw) {
    context.active.erase(parameter);
    return failure();
  }

  FailureOr<EvaluatedValue> evaluated = llvm::isa<DictionaryAttr>(raw)
                                            ? evaluateExpression(raw, context, parameter)
                                            : evaluateLegacy(raw, *kind, *unit, parameter);
  if (failed(evaluated)) {
    context.active.erase(parameter);
    return failure();
  }
  auto coerced =
      coerceToParameter(*evaluated, parameter, parameter, "NODAL-PARAMETER-DEFAULT-001");
  context.active.erase(parameter);
  if (failed(coerced))
    return failure();
  context.cache.try_emplace(parameter, *coerced);
  return *coerced;
}

int compareValues(const EvaluatedValue &lhs, const EvaluatedValue &rhs) {
  const long double left = numericValue(lhs);
  const long double right = numericValue(rhs);
  if (left < right)
    return -1;
  if (left > right)
    return 1;
  return 0;
}

FailureOr<EvaluatedValue> evaluateConstraintValue(Attribute raw, Operation *parameter,
                                                  EvaluationContext &context,
                                                  Operation *diagnosticOwner) {
  auto kind = declaredKind(parameter);
  auto unit = declaredUnit(parameter);
  if (failed(kind) || failed(unit))
    return failure();
  FailureOr<EvaluatedValue> value = llvm::isa<DictionaryAttr>(raw)
                                        ? evaluateExpression(raw, context, diagnosticOwner)
                                        : evaluateLegacy(raw, *kind, *unit, diagnosticOwner);
  if (failed(value))
    return failure();
  return coerceToParameter(*value, parameter, diagnosticOwner,
                           "NODAL-PARAMETER-CONSTRAINT-001");
}

LogicalResult validateConstraints(Operation *parameter, EvaluationContext &context,
                                  const EvaluatedValue &candidate, Operation *diagnosticOwner,
                                  llvm::StringRef violationCode,
                                  llvm::SmallVectorImpl<std::string> *renderings = nullptr) {
  auto constraints = parameter->getAttrOfType<ArrayAttr>("constraints");
  if (!constraints)
    return success();
  for (Attribute rawConstraint : constraints) {
    auto constraint = llvm::dyn_cast<DictionaryAttr>(rawConstraint);
    auto kind = constraint ? constraint.getAs<StringAttr>("kind") : StringAttr();
    if (!kind)
      return fail(diagnosticOwner, "NODAL-PARAMETER-CONSTRAINT-001",
                  "constraint must be a dictionary with a kind field");
    if (kind.getValue() == "range") {
      Attribute lowerRaw = constraint.get("lower");
      Attribute upperRaw = constraint.get("upper");
      if (!lowerRaw || !upperRaw)
        return fail(diagnosticOwner, "NODAL-PARAMETER-RANGE-001",
                    "range constraint requires lower and upper expressions");
      auto lower = evaluateConstraintValue(lowerRaw, parameter, context, diagnosticOwner);
      auto upper = evaluateConstraintValue(upperRaw, parameter, context, diagnosticOwner);
      if (failed(lower) || failed(upper))
        return failure();
      auto lowerInclusive = constraint.getAs<BoolAttr>("lower_inclusive");
      auto upperInclusive = constraint.getAs<BoolAttr>("upper_inclusive");
      const bool includeLower = !lowerInclusive || lowerInclusive.getValue();
      const bool includeUpper = !upperInclusive || upperInclusive.getValue();
      if (compareValues(*lower, *upper) > 0)
        return fail(diagnosticOwner, "NODAL-PARAMETER-RANGE-001",
                    "range lower bound exceeds its upper bound");
      const int lowerCompare = compareValues(candidate, *lower);
      const int upperCompare = compareValues(candidate, *upper);
      if (lowerCompare < 0 || (!includeLower && lowerCompare == 0) || upperCompare > 0 ||
          (!includeUpper && upperCompare == 0))
        return fail(diagnosticOwner, violationCode,
                    llvm::Twine("value violates range constraint of parameter '") +
                        symbolName(parameter) + "'");
      if (renderings) {
        const char left = includeLower ? '[' : '(';
        const char right = includeUpper ? ']' : ')';
        renderings->push_back((llvm::Twine("from ") + left + lower->rendered + ":" +
                               upper->rendered + right)
                                  .str());
      }
    } else if (kind.getValue() == "exclude") {
      Attribute valueRaw = constraint.get("value");
      if (!valueRaw)
        return fail(diagnosticOwner, "NODAL-PARAMETER-CONSTRAINT-001",
                    "exclude constraint requires a value expression");
      auto excluded = evaluateConstraintValue(valueRaw, parameter, context, diagnosticOwner);
      if (failed(excluded))
        return failure();
      if (compareValues(candidate, *excluded) == 0)
        return fail(diagnosticOwner, violationCode,
                    llvm::Twine("value matches excluded value of parameter '") +
                        symbolName(parameter) + "'");
      if (renderings)
        renderings->push_back((llvm::Twine("exclude ") + excluded->rendered).str());
    } else {
      return fail(diagnosticOwner, "NODAL-PARAMETER-CONSTRAINT-001",
                  llvm::Twine("unsupported constraint kind '") + kind.getValue() + "'");
    }
  }
  return success();
}

LogicalResult validateStructural(Operation *parameter, EvaluationContext &context,
                                 const EvaluatedValue &candidate, Operation *diagnosticOwner,
                                 llvm::StringRef violationCode) {
  auto role = declaredRole(parameter);
  auto kind = declaredKind(parameter);
  auto unit = declaredUnit(parameter);
  auto effects = structuralEffects(parameter);
  if (failed(role) || failed(kind) || failed(unit) || failed(effects))
    return failure();
  auto envelope = parameter->getAttrOfType<DictionaryAttr>("structural_envelope");

  if (*role == ParameterRole::Ordinary) {
    if (!effects->empty() || envelope)
      return fail(diagnosticOwner, "NODAL-PARAMETER-STRUCTURAL-001",
                  "ordinary parameters cannot own structural effects or envelopes");
    return success();
  }

  if (*kind == ParameterKind::Real || unit->dimension != "dimensionless")
    return fail(diagnosticOwner, "NODAL-PARAMETER-STRUCTURAL-001",
                "structural parameters must be dimensionless integer or Boolean values");
  auto variability = parameter->getAttrOfType<StringAttr>("variability");
  if (variability && variability.getValue() == "symbolic" && !effects->empty() && !envelope)
    return fail(diagnosticOwner, "NODAL-PARAMETER-ENVELOPE-001",
                "symbolic structural effects require an explicit static envelope");
  if (!envelope)
    return success();

  auto policy = envelope.getAs<StringAttr>("policy");
  if (!policy || policy.getValue() != "static_generate")
    return fail(diagnosticOwner, "NODAL-PARAMETER-ENVELOPE-001",
                "structural envelope policy must be static_generate");
  Attribute minimumRaw = envelope.get("min");
  Attribute maximumRaw = envelope.get("max");
  if (!minimumRaw || !maximumRaw)
    return fail(diagnosticOwner, "NODAL-PARAMETER-ENVELOPE-001",
                "structural envelope requires min and max expressions");
  auto minimum = evaluateConstraintValue(minimumRaw, parameter, context, diagnosticOwner);
  auto maximum = evaluateConstraintValue(maximumRaw, parameter, context, diagnosticOwner);
  if (failed(minimum) || failed(maximum))
    return failure();
  if (compareValues(*minimum, *maximum) > 0)
    return fail(diagnosticOwner, "NODAL-PARAMETER-ENVELOPE-001",
                "structural envelope minimum exceeds maximum");
  if (compareValues(candidate, *minimum) < 0 || compareValues(candidate, *maximum) > 0)
    return fail(diagnosticOwner, violationCode,
                llvm::Twine("value escapes structural envelope of parameter '") +
                    symbolName(parameter) + "'");
  return success();
}

void collectReferences(Attribute attribute, llvm::StringSet<> &references) {
  auto expression = llvm::dyn_cast<DictionaryAttr>(attribute);
  if (!expression)
    return;
  auto op = expression.getAs<StringAttr>("op");
  if (!op)
    return;
  if (op.getValue() == "ref") {
    if (auto reference = expression.getAs<FlatSymbolRefAttr>("parameter"))
      references.insert(reference.getValue());
    return;
  }
  for (llvm::StringRef field : {llvm::StringRef("operand"), llvm::StringRef("lhs"),
                                llvm::StringRef("rhs")}) {
    if (Attribute nested = expression.get(field))
      collectReferences(nested, references);
  }
}

} // namespace

LogicalResult nodal::verifyParameterDeclaration(Operation *parameter) {
  if (!parameter || parameter->getName().getStringRef() != "nodal.parameter")
    return fail(parameter, "NODAL-PARAMETER-KIND-001",
                "parameter verifier requires nodal.parameter");
  if (symbolName(parameter).empty())
    return fail(parameter, "NODAL-PARAMETER-KIND-001",
                "parameter requires a canonical symbol");
  if (!parameter->getAttr("default_value"))
    return fail(parameter, "NODAL-PARAMETER-DEFAULT-001",
                "parameter requires a default value");
  auto variability = parameter->getAttrOfType<StringAttr>("variability");
  if (!variability || !oneOf(variability.getValue(), {"fixed", "symbolic"}))
    return fail(parameter, "NODAL-PARAMETER-ROLE-001",
                "parameter variability must be fixed or symbolic");
  if (failed(declaredKind(parameter)) || failed(declaredRole(parameter)) ||
      failed(declaredUnit(parameter)))
    return failure();
  if (auto metadata = parameter->getAttrOfType<DictionaryAttr>("metadata")) {
    if (auto dynamic = metadata.getAs<BoolAttr>("dynamic_value")) {
      if (dynamic.getValue())
        return fail(parameter, "NODAL-PARAMETER-DYNAMIC-001",
                    "dynamic_value metadata cannot be attached to a parameter");
    }
  }
  if (variability.getValue() == "fixed") {
    if (auto constraints = parameter->getAttrOfType<ArrayAttr>("constraints")) {
      if (!constraints.empty())
        return fail(parameter, "NODAL-PARAMETER-CONSTRAINT-001",
                    "fixed constants cannot carry override constraints");
    }
  }
  return success();
}

LogicalResult nodal::verifyParameterSemantics(Operation *builtinOperation) {
  auto builtin = llvm::dyn_cast_or_null<mlir::ModuleOp>(builtinOperation);
  if (!builtin)
    return fail(builtinOperation, "NODAL-PARAMETER-DEFAULT-001",
                "parameter semantic verification requires a builtin module");

  llvm::StringMap<Operation *> definitions;
  for (Operation &operation : builtin.getBody()->getOperations()) {
    if (operation.getName().getStringRef() != "nodal.module")
      continue;
    if (!definitions.try_emplace(symbolName(&operation), &operation).second)
      return fail(&operation, "NODAL-PARAMETER-REFERENCE-001",
                  "duplicate module symbol prevents parameter resolution");
  }

  for (const auto &entry : definitions) {
    Operation *module = entry.getValue();
    Block *body = moduleBody(module);
    if (!body)
      continue;
    EvaluationContext context(module);
    for (Operation &operation : *body) {
      if (operation.getName().getStringRef() != "nodal.parameter")
        continue;
      if (failed(verifyParameterDeclaration(&operation)))
        return failure();
      auto value = evaluateParameter(&operation, context);
      if (failed(value))
        return failure();
      if (failed(validateConstraints(&operation, context, *value, &operation,
                                     "NODAL-PARAMETER-CONSTRAINT-001")) ||
          failed(validateStructural(&operation, context, *value, &operation,
                                    "NODAL-PARAMETER-ENVELOPE-001")))
        return failure();
    }
  }

  for (const auto &entry : definitions) {
    Operation *sourceModule = entry.getValue();
    Block *body = moduleBody(sourceModule);
    if (!body)
      continue;
    EvaluationContext sourceContext(sourceModule);
    for (Operation &operation : *body) {
      if (operation.getName().getStringRef() != "nodal.instance")
        continue;
      auto targetReference = operation.getAttrOfType<FlatSymbolRefAttr>("module");
      if (!targetReference || !definitions.contains(targetReference.getValue()))
        continue;
      Operation *targetModule = definitions[targetReference.getValue()];
      EvaluationContext targetContext(targetModule);
      auto bindings = operation.getAttrOfType<DictionaryAttr>("parameter_bindings");
      if (!bindings)
        continue;
      for (NamedAttribute binding : bindings) {
        Operation *parameter = findDirectParameter(targetModule, binding.getName().getValue());
        if (!parameter)
          return fail(&operation, "NODAL-PARAMETER-OVERRIDE-001",
                      llvm::Twine("unknown parameter override '") +
                          binding.getName().getValue() + "'");
        auto variability = parameter->getAttrOfType<StringAttr>("variability");
        if (variability && variability.getValue() == "fixed")
          return fail(&operation, "NODAL-PARAMETER-FIXED-001",
                      llvm::Twine("fixed constant '") + symbolName(parameter) +
                          "' cannot be overridden");
        auto kind = declaredKind(parameter);
        auto unit = declaredUnit(parameter);
        if (failed(kind) || failed(unit))
          return failure();
        FailureOr<EvaluatedValue> raw = llvm::isa<DictionaryAttr>(binding.getValue())
                                            ? evaluateExpression(binding.getValue(), sourceContext,
                                                                 &operation)
                                            : evaluateLegacy(binding.getValue(), *kind, *unit,
                                                             &operation);
        if (failed(raw))
          return failure();
        auto candidate = coerceToParameter(*raw, parameter, &operation,
                                           "NODAL-PARAMETER-OVERRIDE-001");
        if (failed(candidate))
          return failure();
        if (failed(validateConstraints(parameter, targetContext, *candidate, &operation,
                                       "NODAL-PARAMETER-OVERRIDE-001")) ||
            failed(validateStructural(parameter, targetContext, *candidate, &operation,
                                      "NODAL-PARAMETER-OVERRIDE-001")))
          return failure();
      }
    }
  }
  return success();
}

FailureOr<llvm::SmallVector<Operation *, 8>>
nodal::parameterDeclarationOrder(Operation *module) {
  Block *body = moduleBody(module);
  if (!body)
    return failValue<llvm::SmallVector<Operation *, 8>>(
        module, "NODAL-PARAMETER-REFERENCE-001",
        "parameter ordering requires one nodal.module body");

  std::map<std::string, Operation *> parameters;
  for (Operation &operation : *body) {
    if (operation.getName().getStringRef() == "nodal.parameter")
      parameters.emplace(symbolName(&operation).str(), &operation);
  }
  std::map<std::string, unsigned> indegree;
  std::map<std::string, std::vector<std::string>> dependents;
  for (const auto &[name, parameter] : parameters) {
    indegree[name] = 0;
    llvm::StringSet<> references;
    collectReferences(parameter->getAttr("default_value"), references);
    for (const auto &reference : references) {
      const std::string dependency = reference.getKey().str();
      if (!parameters.contains(dependency))
        return failValue<llvm::SmallVector<Operation *, 8>>(
            parameter, "NODAL-PARAMETER-REFERENCE-001",
            llvm::Twine("default references unknown parameter '") + dependency + "'");
      ++indegree[name];
      dependents[dependency].push_back(name);
    }
  }

  std::set<std::string> ready;
  for (const auto &[name, degree] : indegree) {
    if (degree == 0)
      ready.insert(name);
  }
  llvm::SmallVector<Operation *, 8> order;
  while (!ready.empty()) {
    std::string name = *ready.begin();
    ready.erase(ready.begin());
    order.push_back(parameters[name]);
    auto iterator = dependents.find(name);
    if (iterator == dependents.end())
      continue;
    std::sort(iterator->second.begin(), iterator->second.end());
    for (const std::string &dependent : iterator->second) {
      unsigned &degree = indegree[dependent];
      if (--degree == 0)
        ready.insert(dependent);
    }
  }
  if (order.size() != parameters.size())
    return failValue<llvm::SmallVector<Operation *, 8>>(
        module, "NODAL-PARAMETER-CYCLE-001",
        "parameter dependency graph contains a cycle");
  return order;
}

FailureOr<std::string> nodal::renderParameterDefault(Operation *parameter) {
  Operation *module = enclosingNodalModule(parameter);
  if (!module)
    return failValue<std::string>(parameter, "NODAL-BACKEND-PARAMETER-001",
                                  "parameter has no enclosing module");
  EvaluationContext context(module);
  auto value = evaluateParameter(parameter, context);
  if (failed(value))
    return failure();
  return value->rendered;
}

FailureOr<llvm::SmallVector<std::string, 4>>
nodal::renderParameterConstraints(Operation *parameter) {
  Operation *module = enclosingNodalModule(parameter);
  if (!module)
    return failValue<llvm::SmallVector<std::string, 4>>(
        parameter, "NODAL-BACKEND-PARAMETER-001",
        "parameter has no enclosing module");
  EvaluationContext context(module);
  auto value = evaluateParameter(parameter, context);
  if (failed(value))
    return failure();
  llvm::SmallVector<std::string, 4> renderings;
  if (failed(validateConstraints(parameter, context, *value, parameter,
                                 "NODAL-PARAMETER-CONSTRAINT-001", &renderings)))
    return failure();
  return renderings;
}

FailureOr<std::string> nodal::parameterNativeType(Operation *parameter) {
  auto kind = declaredKind(parameter);
  if (failed(kind))
    return failure();
  return *kind == ParameterKind::Real ? std::string("real") : std::string("integer");
}

FailureOr<bool> nodal::parameterIsFixed(Operation *parameter) {
  auto variability = parameter->getAttrOfType<StringAttr>("variability");
  if (!variability || !oneOf(variability.getValue(), {"fixed", "symbolic"}))
    return failValue<bool>(parameter, "NODAL-PARAMETER-ROLE-001",
                           "parameter variability must be fixed or symbolic");
  return variability.getValue() == "fixed";
}

LogicalResult nodal::ParameterOp::verify() {
  return nodal::verifyParameterDeclaration(getOperation());
}
''',
)

replace_once(
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    '#include "nodal/Dialect/Nodal/NatureDiscipline.h"\n',
    '#include "nodal/Dialect/Nodal/NatureDiscipline.h"\n#include "nodal/Dialect/Nodal/ParameterSemantics.h"\n',
)
replace_once(
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    r'''LogicalResult nodal::ParameterOp::verify() {
  const llvm::StringRef variability = textAttr(getOperation(), "variability");
  if (!oneOf(variability, {"fixed", "symbolic"}))
    return emitOpError() << "unsupported parameter variability '" << variability << "'";
  auto type = getOperation()->getAttrOfType<TypeAttr>("type");
  Attribute value = getOperation()->getAttr("default_value");
  if (!type || !value || !attributeFits(value, type.getValue()))
    return emitOpError("default_value is incompatible with parameter type");
  return success();
}

''',
    "",
)
replace_once(
    "core/compiler/lib/Dialect/Nodal/CMakeLists.txt",
    "  NatureDiscipline.cpp\n  NodalOps.cpp\n",
    "  NatureDiscipline.cpp\n  ParameterSemantics.cpp\n  NodalOps.cpp\n",
)

replace_once(
    "core/compiler/lib/Transforms/Passes.cpp",
    '#include "nodal/Dialect/Nodal/NodalTypes.h"\n',
    '#include "nodal/Dialect/Nodal/NodalTypes.h"\n#include "nodal/Dialect/Nodal/ParameterSemantics.h"\n',
)
replace_once(
    "core/compiler/lib/Transforms/Passes.cpp",
    r'''  LogicalResult result = success();
  llvm::StringMap<Operation *> definitions = collectModuleDefinitions(module, result);
''',
    r'''  if (failed(verifyParameterSemantics(module.getOperation())))
    return failure();

  LogicalResult result = success();
  llvm::StringMap<Operation *> definitions = collectModuleDefinitions(module, result);
''',
)

# ---------------------------------------------------------------------------
# Native Verilog-A/AMS rendering
# ---------------------------------------------------------------------------

replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '#include "nodal/Diagnostics/DiagnosticMapping.h"\n',
    '#include "nodal/Diagnostics/DiagnosticMapping.h"\n#include "nodal/Dialect/Nodal/ParameterSemantics.h"\n',
)
replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    r'''  llvm::sort(parameters,
             [](Operation *lhs, Operation *rhs) { return symbolName(lhs) < symbolName(rhs); });
''',
    "",
)
replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    r'''  if (failed(collectModuleState(definition, state, parameters, ports, nodes, analogs)))
    return emitMappedFailure(definition, "NODAL-BACKEND-RC-001",
                             "could not collect RC module structure");

  output << "module " << symbolName(definition);
''',
    r'''  if (failed(collectModuleState(definition, state, parameters, ports, nodes, analogs)))
    return emitMappedFailure(definition, "NODAL-BACKEND-RC-001",
                             "could not collect RC module structure");
  auto orderedParameters = parameterDeclarationOrder(definition);
  if (failed(orderedParameters))
    return emitMappedFailure(definition, "NODAL-BACKEND-PARAMETER-001",
                             "could not order parameter declarations");
  parameters.assign(orderedParameters->begin(), orderedParameters->end());

  output << "module " << symbolName(definition);
''',
)
replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    r'''  for (Operation *parameter : parameters) {
    auto type = parameter->getAttrOfType<TypeAttr>("type");
    auto value = parameter->getAttrOfType<FloatAttr>("default_value");
    if (!type || !type.getValue().isF64() || !value || !std::isfinite(value.getValueAsDouble()))
      return emitMappedFailure(parameter, "NODAL-BACKEND-RC-004",
                               "RC parameters must be finite real values");
    output << "  parameter real " << symbolName(parameter) << " = "
           << formatReal(value.getValueAsDouble()) << ";\n";
  }
''',
    r'''  for (Operation *parameter : parameters) {
    auto nativeType = parameterNativeType(parameter);
    auto defaultValue = renderParameterDefault(parameter);
    auto fixed = parameterIsFixed(parameter);
    auto constraints = renderParameterConstraints(parameter);
    if (failed(nativeType) || failed(defaultValue) || failed(fixed) || failed(constraints))
      return emitMappedFailure(parameter, "NODAL-BACKEND-PARAMETER-001",
                               "could not render typed parameter declaration");
    output << "  " << (*fixed ? "localparam " : "parameter ") << *nativeType << " "
           << symbolName(parameter) << " = " << *defaultValue;
    for (const std::string &constraint : *constraints)
      output << " " << constraint;
    output << ";\n";
  }
''',
)

# ---------------------------------------------------------------------------
# Native tests and fixtures
# ---------------------------------------------------------------------------

write(
    "core/compiler/test/IR/parameters-units.mlir",
    r'''
module attributes {
  nodal.target.profile = "analog",
  nodal.verify.analog_topology = true,
  nodal.verify.assignment_coverage = true,
  nodal.verify.cdc_rdc_safe = true,
  nodal.verify.clock_reset_domains = true,
  nodal.verify.combinational_acyclic = true,
  nodal.verify.construction_closed = true,
  nodal.verify.driver_coverage = true,
  nodal.verify.enum_fsm = true,
  nodal.verify.hierarchy_closed = true,
  nodal.verify.latch_free = true,
  nodal.verify.layout_storage = true,
  nodal.verify.memory_effects = true,
  nodal.verify.mixed_signal_bridges = true,
  nodal.verify.parameters_complete = true,
  nodal.verify.protocol_pipeline = true,
  nodal.verify.target_capability = true,
  nodal.verify.width_sign_shape = true
} {
  "nodal.module"() <{metadata = {}, sym_name = "ParameterizedCell"}> ({
  ^bb0:
    "nodal.parameter"() <{default_value = {op = "literal", spelling = "1.25", unit = "kOhm"}, kind = "real", metadata = {}, role = "ordinary", sym_name = "BASE_R", type = f64, unit = "Ohm", variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{constraints = [{kind = "range", lower = {op = "literal", spelling = "1.0", unit = "Ohm"}, lower_inclusive = true, upper = {op = "literal", spelling = "10.0", unit = "kOhm"}, upper_inclusive = true}], default_value = {op = "ref", parameter = @BASE_R}, kind = "real", metadata = {}, role = "ordinary", sym_name = "R", type = f64, unit = "Ohm", variability = "symbolic"}> : () -> ()
    "nodal.parameter"() <{constraints = [{kind = "range", lower = {op = "literal", spelling = "1.0", unit = "pF"}, lower_inclusive = true, upper = {op = "literal", spelling = "10.0", unit = "pF"}, upper_inclusive = true}], default_value = {op = "literal", spelling = "2.5", unit = "pF"}, kind = "real", metadata = {}, role = "ordinary", sym_name = "C", type = f64, unit = "F", variability = "symbolic"}> : () -> ()
    "nodal.parameter"() <{constraints = [{kind = "range", lower = {op = "literal", spelling = "1", unit = "1"}, lower_inclusive = true, upper = {op = "literal", spelling = "8", unit = "1"}, upper_inclusive = true}], default_value = {op = "literal", spelling = "4", unit = "1"}, kind = "integer", metadata = {structural_effects = ["component_count", "equation_count", "shape"]}, role = "structural", structural_envelope = {max = {op = "literal", spelling = "8", unit = "1"}, min = {op = "literal", spelling = "1", unit = "1"}, policy = "static_generate"}, sym_name = "STAGES", type = i64, unit = "1", variability = "symbolic"}> : () -> ()
    "nodal.parameter"() <{default_value = {op = "literal", spelling = "true", unit = "1"}, kind = "boolean", metadata = {structural_effects = ["topology"]}, role = "structural", structural_envelope = {max = {op = "literal", spelling = "true", unit = "1"}, min = {op = "literal", spelling = "false", unit = "1"}, policy = "static_generate"}, sym_name = "ENABLED", type = i1, unit = "1", variability = "symbolic"}> : () -> ()
    "nodal.parameter"() <{constraints = [{kind = "range", lower = {op = "literal", spelling = "1", unit = "1"}, upper = {op = "literal", spelling = "16", unit = "1"}}], default_value = {lhs = {op = "literal", spelling = "2", unit = "1"}, op = "add", rhs = {op = "literal", spelling = "3", unit = "1"}}, kind = "integer", metadata = {}, role = "ordinary", sym_name = "COUNT", type = i64, unit = "1", variability = "symbolic"}> : () -> ()
  }) : () -> ()
  "nodal.module"() <{metadata = {root = true}, sym_name = "ParameterTop"}> ({
  ^bb0:
    "nodal.parameter"() <{default_value = {op = "literal", spelling = "2.0", unit = "kOhm"}, kind = "real", metadata = {}, role = "ordinary", sym_name = "TOP_R", type = f64, unit = "Ohm", variability = "symbolic"}> : () -> ()
    "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @ParameterizedCell, parameter_bindings = {C = {op = "literal", spelling = "5.0", unit = "pF"}, ENABLED = {op = "literal", spelling = "false", unit = "1"}, R = {op = "ref", parameter = @TOP_R}, STAGES = {op = "literal", spelling = "6", unit = "1"}}, sym_name = "cell"}> : () -> ()
  }) : () -> ()
}
''',
)

write(
    "core/compiler/test/IR/parameter-native-rendering.mlir",
    r'''
module attributes {
  nodal.backend.check_profile = "default",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.profile = "verilog-a",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog",
  nodal.verify.analog_topology = true,
  nodal.verify.assignment_coverage = true,
  nodal.verify.cdc_rdc_safe = true,
  nodal.verify.clock_reset_domains = true,
  nodal.verify.combinational_acyclic = true,
  nodal.verify.construction_closed = true,
  nodal.verify.driver_coverage = true,
  nodal.verify.enum_fsm = true,
  nodal.verify.hierarchy_closed = true,
  nodal.verify.latch_free = true,
  nodal.verify.layout_storage = true,
  nodal.verify.memory_effects = true,
  nodal.verify.mixed_signal_bridges = true,
  nodal.verify.parameters_complete = true,
  nodal.verify.protocol_pipeline = true,
  nodal.verify.target_capability = true,
  nodal.verify.width_sign_shape = true
} {
  "nodal.module"() <{metadata = {root = true}, sym_name = "ParameterRendering"}> ({
  ^bb0:
    "nodal.parameter"() <{default_value = {op = "literal", spelling = "1.25", unit = "kOhm"}, kind = "real", metadata = {}, role = "ordinary", sym_name = "BASE_R", type = f64, unit = "Ohm", variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{constraints = [{kind = "range", lower = {op = "literal", spelling = "1.0", unit = "Ohm"}, lower_inclusive = true, upper = {op = "literal", spelling = "10.0", unit = "kOhm"}, upper_inclusive = true}], default_value = {op = "ref", parameter = @BASE_R}, kind = "real", metadata = {}, role = "ordinary", sym_name = "R", type = f64, unit = "Ohm", variability = "symbolic"}> : () -> ()
    "nodal.parameter"() <{constraints = [{kind = "range", lower = {op = "literal", spelling = "1", unit = "1"}, upper = {op = "literal", spelling = "8", unit = "1"}}], default_value = {op = "literal", spelling = "4", unit = "1"}, kind = "integer", metadata = {structural_effects = ["shape"]}, role = "structural", structural_envelope = {max = {op = "literal", spelling = "8", unit = "1"}, min = {op = "literal", spelling = "1", unit = "1"}, policy = "static_generate"}, sym_name = "STAGES", type = i64, unit = "1", variability = "symbolic"}> : () -> ()
    "nodal.parameter"() <{default_value = {op = "literal", spelling = "true", unit = "1"}, kind = "boolean", metadata = {}, role = "ordinary", sym_name = "CHECKS", type = i1, unit = "1", variability = "symbolic"}> : () -> ()
  }) : () -> ()
}
''',
)

invalid_common = r'''
module attributes {
  nodal.target.profile = "analog",
  nodal.verify.analog_topology = true,
  nodal.verify.assignment_coverage = true,
  nodal.verify.cdc_rdc_safe = true,
  nodal.verify.clock_reset_domains = true,
  nodal.verify.combinational_acyclic = true,
  nodal.verify.construction_closed = true,
  nodal.verify.driver_coverage = true,
  nodal.verify.enum_fsm = true,
  nodal.verify.hierarchy_closed = true,
  nodal.verify.latch_free = true,
  nodal.verify.layout_storage = true,
  nodal.verify.memory_effects = true,
  nodal.verify.mixed_signal_bridges = true,
  nodal.verify.parameters_complete = true,
  nodal.verify.protocol_pipeline = true,
  nodal.verify.target_capability = true,
  nodal.verify.width_sign_shape = true
} {
BODY
}
'''

write(
    "core/compiler/test/IR/parameters-units-invalid-cycle.mlir",
    invalid_common.replace(
        "BODY",
        r'''  "nodal.module"() <{metadata = {root = true}, sym_name = "Cycle"}> ({
  ^bb0:
    "nodal.parameter"() <{default_value = {op = "ref", parameter = @B}, kind = "integer", metadata = {}, role = "ordinary", sym_name = "A", type = i64, unit = "1", variability = "symbolic"}> : () -> ()
    "nodal.parameter"() <{default_value = {op = "ref", parameter = @A}, kind = "integer", metadata = {}, role = "ordinary", sym_name = "B", type = i64, unit = "1", variability = "symbolic"}> : () -> ()
  }) : () -> ()''',
    ),
)
write(
    "core/compiler/test/IR/parameters-units-invalid-constraint.mlir",
    invalid_common.replace(
        "BODY",
        r'''  "nodal.module"() <{metadata = {root = true}, sym_name = "Constraint"}> ({
  ^bb0:
    "nodal.parameter"() <{constraints = [{kind = "range", lower = {op = "literal", spelling = "1", unit = "1"}, upper = {op = "literal", spelling = "4", unit = "1"}}], default_value = {op = "literal", spelling = "8", unit = "1"}, kind = "integer", metadata = {}, role = "ordinary", sym_name = "COUNT", type = i64, unit = "1", variability = "symbolic"}> : () -> ()
  }) : () -> ()''',
    ),
)
write(
    "core/compiler/test/IR/parameters-units-invalid-override.mlir",
    invalid_common.replace(
        "BODY",
        r'''  "nodal.module"() <{metadata = {}, sym_name = "Target"}> ({
  ^bb0:
    "nodal.parameter"() <{default_value = {op = "literal", spelling = "2", unit = "1"}, kind = "integer", metadata = {}, role = "ordinary", sym_name = "LOCKED", type = i64, unit = "1", variability = "fixed"}> : () -> ()
  }) : () -> ()
  "nodal.module"() <{metadata = {root = true}, sym_name = "Override"}> ({
  ^bb0:
    "nodal.instance"() <{domain_bindings = {}, metadata = {}, module = @Target, parameter_bindings = {LOCKED = {op = "literal", spelling = "3", unit = "1"}}, sym_name = "target"}> : () -> ()
  }) : () -> ()''',
    ),
)
write(
    "core/compiler/test/IR/parameters-units-invalid-structural.mlir",
    invalid_common.replace(
        "BODY",
        r'''  "nodal.module"() <{metadata = {root = true}, sym_name = "Structural"}> ({
  ^bb0:
    "nodal.parameter"() <{default_value = {op = "literal", spelling = "4", unit = "1"}, kind = "integer", metadata = {structural_effects = ["shape", "rank"]}, role = "structural", sym_name = "WIDTH", type = i64, unit = "1", variability = "symbolic"}> : () -> ()
  }) : () -> ()''',
    ),
)
write(
    "core/compiler/test/IR/parameters-units-invalid-unit.mlir",
    invalid_common.replace(
        "BODY",
        r'''  "nodal.module"() <{metadata = {root = true}, sym_name = "Units"}> ({
  ^bb0:
    "nodal.parameter"() <{default_value = {lhs = {op = "literal", spelling = "1.0", unit = "V"}, op = "add", rhs = {op = "literal", spelling = "1.0", unit = "A"}}, kind = "real", metadata = {}, role = "ordinary", sym_name = "BAD", type = f64, unit = "V", variability = "symbolic"}> : () -> ()
  }) : () -> ()''',
    ),
)
write(
    "core/compiler/test/IR/parameters-units-invalid-dynamic.mlir",
    invalid_common.replace(
        "BODY",
        r'''  "nodal.module"() <{metadata = {root = true}, sym_name = "Dynamic"}> ({
  ^bb0:
    "nodal.parameter"() <{default_value = {op = "ref", parameter = @runtimeValue}, kind = "integer", metadata = {}, role = "ordinary", sym_name = "COUNT", type = i64, unit = "1", variability = "symbolic"}> : () -> ()
  }) : () -> ()''',
    ),
)

write(
    "core/compiler/test/Unit/ParameterSemanticsTest.cpp",
    r'''
#include "nodal/Dialect/Nodal/ParameterSemantics.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/raw_ostream.h"

namespace {

int fail(llvm::StringRef message) {
  llvm::errs() << "ParameterSemanticsTest: " << message << '\n';
  return 1;
}

constexpr llvm::StringLiteral kValid = R"mlir(
module {
  "nodal.module"() <{metadata = {}, sym_name = "Parameters"}> ({
  ^bb0:
    "nodal.parameter"() <{default_value = {op = "literal", spelling = "1.25", unit = "kOhm"}, kind = "real", metadata = {}, role = "ordinary", sym_name = "BASE_R", type = f64, unit = "Ohm", variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{constraints = [{kind = "range", lower = {op = "literal", spelling = "1.0", unit = "Ohm"}, upper = {op = "literal", spelling = "10.0", unit = "kOhm"}}], default_value = {op = "ref", parameter = @BASE_R}, kind = "real", metadata = {}, role = "ordinary", sym_name = "R", type = f64, unit = "Ohm", variability = "symbolic"}> : () -> ()
    "nodal.parameter"() <{default_value = {op = "literal", spelling = "4", unit = "1"}, kind = "integer", metadata = {structural_effects = ["shape"]}, role = "structural", structural_envelope = {max = {op = "literal", spelling = "8", unit = "1"}, min = {op = "literal", spelling = "1", unit = "1"}, policy = "static_generate"}, sym_name = "WIDTH", type = i64, unit = "1", variability = "symbolic"}> : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kCycle = R"mlir(
module {
  "nodal.module"() <{metadata = {}, sym_name = "Cycle"}> ({
  ^bb0:
    "nodal.parameter"() <{default_value = {op = "ref", parameter = @B}, kind = "integer", metadata = {}, role = "ordinary", sym_name = "A", type = i64, unit = "1", variability = "symbolic"}> : () -> ()
    "nodal.parameter"() <{default_value = {op = "ref", parameter = @A}, kind = "integer", metadata = {}, role = "ordinary", sym_name = "B", type = i64, unit = "1", variability = "symbolic"}> : () -> ()
  }) : () -> ()
}
)mlir";

} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  context.loadAllAvailableDialects();

  auto valid = mlir::parseSourceString<mlir::ModuleOp>(kValid, &context);
  if (!valid || mlir::failed(nodal::verifyParameterSemantics(valid->getOperation())))
    return fail("valid parameter model was rejected");

  mlir::Operation *definition = nullptr;
  for (mlir::Operation &operation : valid->getBody()->getOperations()) {
    if (operation.getName().getStringRef() == "nodal.module")
      definition = &operation;
  }
  auto order = nodal::parameterDeclarationOrder(definition);
  if (mlir::failed(order) || order->size() != 3 ||
      (*order)[0]->getAttrOfType<mlir::StringAttr>("sym_name").getValue() != "BASE_R" ||
      (*order)[1]->getAttrOfType<mlir::StringAttr>("sym_name").getValue() != "R")
    return fail("dependency-before-user parameter order is incorrect");

  auto rendered = nodal::renderParameterDefault((*order)[0]);
  if (mlir::failed(rendered) || *rendered != "(1.25 * 1e3)")
    return fail("unit-scaled source literal spelling was not retained");
  auto constraints = nodal::renderParameterConstraints((*order)[1]);
  if (mlir::failed(constraints) || constraints->size() != 1 ||
      constraints->front().find("from [1.0:(10.0 * 1e3)]") == std::string::npos)
    return fail("native range rendering is incorrect");

  auto cycle = mlir::parseSourceString<mlir::ModuleOp>(kCycle, &context);
  if (!cycle || mlir::succeeded(nodal::verifyParameterSemantics(cycle->getOperation())))
    return fail("parameter dependency cycle was accepted");
  return 0;
}
''',
)

replace_once(
    "core/compiler/test/Unit/CMakeLists.txt",
    r'''add_test(
  NAME nodal.native.conservative-connectivity-unit
  COMMAND nodal-conservative-connectivity-unit-tests
)
''',
    r'''add_test(
  NAME nodal.native.conservative-connectivity-unit
  COMMAND nodal-conservative-connectivity-unit-tests
)

add_executable(nodal-parameter-semantics-unit-tests
  ParameterSemanticsTest.cpp
)

llvm_update_compile_flags(nodal-parameter-semantics-unit-tests)

target_link_libraries(nodal-parameter-semantics-unit-tests
  PRIVATE
    NodalDialect
    MLIRIR
    MLIRParser
    MLIRSupport
    LLVMSupport
)

add_test(
  NAME nodal.native.parameter-semantics-unit
  COMMAND nodal-parameter-semantics-unit-tests
)
''',
)

parameter_ctests = r'''

add_test(
  NAME nodal.native.parameters-units-roundtrip
  COMMAND nodalc
    "--pass-pipeline=builtin.module(nodal-gate-default)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/parameters-units.mlir"
)
set_tests_properties(
  nodal.native.parameters-units-roundtrip
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]parameter"
)

add_test(
  NAME nodal.native.parameters-units-generic
  COMMAND nodalc
    --mlir-print-op-generic
    "--pass-pipeline=builtin.module(nodal-gate-default)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/parameters-units.mlir"
)
set_tests_properties(
  nodal.native.parameters-units-generic
  PROPERTIES
    PASS_REGULAR_EXPRESSION "structural_envelope"
)

add_test(
  NAME nodal.native.parameter-native-rendering
  COMMAND nodal-translate
    --nodal-to-verilog-a
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/parameter-native-rendering.mlir"
)
set_tests_properties(
  nodal.native.parameter-native-rendering
  PROPERTIES
    PASS_REGULAR_EXPRESSION "localparam real BASE_R = [(]1[.]25 [*] 1e3[)];"
)

foreach(_fixture IN ITEMS cycle constraint override structural unit dynamic)
  add_test(
    NAME nodal.native.parameters-units-rejects-${_fixture}
    COMMAND nodalc
      "--pass-pipeline=builtin.module(nodal-gate-default)"
      "${CMAKE_CURRENT_SOURCE_DIR}/IR/parameters-units-invalid-${_fixture}.mlir"
  )
  set_tests_properties(
    nodal.native.parameters-units-rejects-${_fixture}
    PROPERTIES
      WILL_FAIL TRUE
  )
endforeach()
'''
replace_once(
    "core/compiler/test/CMakeLists.txt",
    "add_custom_target(check-nodal-native\n",
    parameter_ctests + "\nadd_custom_target(check-nodal-native\n",
)
replace_once(
    "core/compiler/test/CMakeLists.txt",
    "    nodal-conservative-connectivity-unit-tests\n",
    "    nodal-conservative-connectivity-unit-tests\n    nodal-parameter-semantics-unit-tests\n",
)

# ---------------------------------------------------------------------------
# Diagnostics, docs, manifests, and repository contracts
# ---------------------------------------------------------------------------

diagnostic_codes = [
    "NODAL-PARAMETER-KIND-001",
    "NODAL-PARAMETER-ROLE-001",
    "NODAL-PARAMETER-DEFAULT-001",
    "NODAL-PARAMETER-EXPRESSION-001",
    "NODAL-PARAMETER-CYCLE-001",
    "NODAL-PARAMETER-REFERENCE-001",
    "NODAL-PARAMETER-UNIT-001",
    "NODAL-PARAMETER-CONSTRAINT-001",
    "NODAL-PARAMETER-RANGE-001",
    "NODAL-PARAMETER-OVERRIDE-001",
    "NODAL-PARAMETER-FIXED-001",
    "NODAL-PARAMETER-STRUCTURAL-001",
    "NODAL-PARAMETER-ENVELOPE-001",
    "NODAL-PARAMETER-DYNAMIC-001",
    "NODAL-BACKEND-PARAMETER-001",
]
catalog_path = ROOT / "core/compiler/diagnostics-v0.1.json"
catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
catalog["families"]["parameter-constant-unit"] = diagnostic_codes
for prefix in ("NODAL-PARAMETER-", "NODAL-BACKEND-PARAMETER-"):
    if prefix not in catalog["preserved_prefixes"]:
        catalog["preserved_prefixes"].append(prefix)
catalog_path.write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")

write(
    "docs/design-gates/NodalParametersUnits-DG-v1.0.md",
    r'''
# Nodal Parameters, Constants, Ranges, and Units Design Gate v1.0

**Status:** Approved  
**Scope:** compiler-ir, semantic-gates, native-verilog-a-ams-rendering  
**Public API:** unchanged at 0.3  
**Roadmap owner:** Increment 29

## Decision

Increment 29 extends `nodal.parameter` without changing the frozen Scala public
API. A parameter has one supported kind (`real`, `integer`, or `boolean`), one
variability (`fixed` or `symbolic`), one role (`ordinary` or `structural`), an
optional canonical unit, optional range/exclusion constraints, and an optional
structural envelope. A fixed declaration is a target-visible constant and is
not overridable.

## Constant-expression contract

Defaults, constraints, envelope bounds, and overrides accept either a legacy
typed scalar or a canonical dictionary expression with these operations:

- `literal(spelling, unit)`
- `ref(parameter)`
- unary `neg`
- binary `add`, `sub`, `mul`, and exact integer or finite real `div`

References are module-local, declaration order is not semantic, cycles are
rejected, and references to non-parameters are rejected as dynamic values.
Literal source spelling is retained. SI prefixes are rendered as explicit scale
factors rather than being rounded through a host floating-point printer.

## Unit boundary

The initial registry supports dimensionless values plus SI-prefixed voltage,
current, resistance, capacitance, inductance, time, frequency, and temperature.
Addition/subtraction require equal dimensions. Multiplication permits at most
one physical dimension. Division permits a dimensionless denominator or equal
dimensions. General compound-unit algebra and general analog expression typing
belong to Increment 30.

## Constraints and overrides

Symbolic parameters may carry inclusive/exclusive ranges and excluded values.
Defaults and instance overrides are checked against the same typed, unit-aware
contract. Fixed constants reject overrides. Override expressions may reference
parameters of the containing module; target constraints are evaluated against
the target module's verified parameter model.

## Structural classification

Structural parameters are dimensionless integer or Boolean values. Metadata may
declare effects on topology, component count, equation count, shape, or rank.
A symbolic parameter with such effects requires a `static_generate` envelope
with verified minimum and maximum values. Overrides outside that envelope are
rejected. Ordinary parameters cannot carry structural effects. Dynamic values
remain ordinary IR values and cannot enter parameter constant expressions.

## Native HDL rendering

Verilog-A/Verilog-AMS rendering is dependency ordered and deterministic:

- fixed real/integer/Boolean values render as `localparam real/integer`;
- symbolic values render as `parameter real/integer`;
- Boolean values render canonically as integer `0` or `1`;
- ranges/exclusions render with native `from`/`exclude` syntax;
- source decimal spelling and explicit SI scale multiplication are retained.

No module cloning, backend-specific expression text in source IR, or silent
parameter-envelope approximation is permitted.

## Deferred

General analog numeric promotion, comparison/logical typing, conditionals,
compound dimensions, and dynamic expression folding remain Increment 30.
Topology/equation construction from structural generation remains owned by its
later elaboration and continuous-time increments.
''',
)

write(
    "docs/implementation/increment29-parameters-units.md",
    r'''
# Increment 29 — Parameters, constants, ranges, and units

Increment 29 implements a cycle-safe, target-neutral parameter model on top of
the closed Increment 28 conservative-connectivity baseline.

## Implemented

- real, integer, and Boolean parameter kinds;
- fixed constants versus overridable symbolic parameters;
- ordinary versus structural roles;
- canonical dictionary constant expressions with literal, reference, negation,
  addition, subtraction, multiplication, and division;
- canonical SI-prefixed unit literals with dimension checking;
- range and exclusion constraints shared by defaults and overrides;
- fixed-constant override rejection;
- structural-effect inventories and static-generation envelopes;
- dynamic-value separation and cycle-safe module-local dependency folding;
- deterministic dependency-before-user declaration ordering;
- source-spelling-preserving Verilog-A/Verilog-AMS `localparam`/`parameter`,
  `from`, and `exclude` rendering.

## Compatibility

Legacy typed scalar defaults and legacy `metadata.unit` remain accepted. The
public API v0.3 is unchanged. Existing symbolic real RC parameters continue to
render as native `parameter real` declarations.

## Fail-closed boundaries

Unknown units, malformed expressions, non-parameter references, cycles,
incompatible dimensions, violated constraints, fixed overrides, unsupported
structural effects, missing envelopes, and out-of-envelope overrides fail with
stable diagnostics. General analog expression typing and compound-unit algebra
remain deferred to Increment 30.
''',
)

manifest = {
    "increment": 29,
    "title": "Parameters, constants, ranges, and units",
    "public_api": "0.3",
    "status": "implemented-awaiting-evidence",
    "parameter_kinds": ["real", "integer", "boolean"],
    "variability": ["fixed", "symbolic"],
    "roles": ["ordinary", "structural"],
    "expression_ops": ["literal", "ref", "neg", "add", "sub", "mul", "div"],
    "constraints": ["range", "exclude"],
    "unit_dimensions": [
        "dimensionless",
        "voltage",
        "current",
        "resistance",
        "capacitance",
        "inductance",
        "time",
        "frequency",
        "temperature",
    ],
    "structural_effects": [
        "topology",
        "component_count",
        "equation_count",
        "shape",
        "rank",
    ],
    "structural_policy": "static_generate-envelope",
    "native_rendering": {
        "fixed": "localparam",
        "symbolic": "parameter",
        "boolean": "integer-0-or-1",
        "literal_spelling": "retained-with-explicit-si-scale",
    },
    "diagnostics": diagnostic_codes,
    "deferred": [
        "general analog numeric promotion",
        "compound-unit algebra",
        "dynamic expression typing",
        "structural topology materialization",
    ],
    "evidence": {},
}
write(
    "tests/compiler/fixtures/increment29/manifest.json",
    json.dumps(manifest, indent=2) + "\n",
)

# Make Increment 28's successor handling accept pre-evidence and validated 29.
replace_once(
    "scripts/check_increment28.py",
    '    ".github/workflows/increment-28-electrical-connectivity.yml",\n)',
    '    ".github/workflows/increment-28-electrical-connectivity.yml",\n    "tests/compiler/fixtures/increment29/manifest.json",\n)',
)
replace_once(
    "scripts/check_increment28.py",
    r'''    increment29_open = (
        "- [ ] **Increment 29 — Parameters, constants, ranges, and units**"
        in roadmap
    )
''',
    r'''    increment29_open = (
        "- [ ] **Increment 29 — Parameters, constants, ranges, and units**"
        in roadmap
    )
    increment29_done = (
        "- [x] **Increment 29 — Parameters, constants, ranges, and units**"
        in roadmap
    )
    increment30_open = (
        "- [ ] **Increment 30 — Analog numeric types and expression typing**"
        in roadmap
    )
''',
)
replace_once(
    "scripts/check_increment28.py",
    r'''    if not increment29_open:
        problems.append(Problem("NODAL-INC28-019", "Increment 29 must remain unchecked"))

    return problems
''',
    r'''    successor_path = root / "tests/compiler/fixtures/increment29/manifest.json"
    try:
        successor = json.loads(read(successor_path, problems, "NODAL-INC28-020"))
    except json.JSONDecodeError as exc:
        problems.append(Problem("NODAL-INC28-020", f"invalid Increment 29 manifest: {exc}"))
        successor = {}
    if successor.get("increment") != 29 or successor.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC28-020", "Increment 29 successor identity mismatch"))
    successor_status = successor.get("status")
    successor_evidence = successor.get("evidence", {})
    if successor_status == "implemented-awaiting-evidence":
        if not increment29_open or rev < (1, 36):
            problems.append(
                Problem(
                    "NODAL-INC28-020",
                    "pre-evidence Increment 29 must remain unchecked at revision 1.36 or later",
                )
            )
    elif successor_status == "validated-parameters-units":
        if not increment29_done or rev < (1, 37):
            problems.append(
                Problem(
                    "NODAL-INC28-020",
                    "validated Increment 29 must be checked at revision 1.37 or later",
                )
            )
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(successor_evidence.get(field), int):
                problems.append(
                    Problem(
                        "NODAL-INC28-020",
                        f"validated Increment 29 lacks integer evidence field: {field}",
                    )
                )
    else:
        problems.append(
            Problem("NODAL-INC28-020", f"unexpected Increment 29 status: {successor_status!r}")
        )
    if not increment30_open:
        problems.append(Problem("NODAL-INC28-020", "Increment 30 must remain unchecked"))

    return problems
''',
)

replace_once(
    "tests/compiler/test_increment28.py",
    '\n\nif __name__ == "__main__":\n',
    r'''

    def test_accepts_validated_increment29_successor(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        manifest_path = root / "tests/compiler/fixtures/increment29/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "validated-parameters-units"
        manifest["evidence"] = {
            "pull_request": 1,
            "dedicated_run": 2,
            "core_ci_run": 3,
        }
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        text = roadmap.read_text(encoding="utf-8")
        text = text.replace("**Revision:** 1.36", "**Revision:** 1.37", 1)
        text = text.replace(
            "- [ ] **Increment 29 — Parameters, constants, ranges, and units**",
            "- [x] **Increment 29 — Parameters, constants, ranges, and units**",
            1,
        )
        roadmap.write_text(text, encoding="utf-8")
        self.assertEqual(CHECKER.check_repository(root), [])


if __name__ == "__main__":
''',
)

write(
    "scripts/check_increment29.py",
    r'''
#!/usr/bin/env python3
"""Validate Increment 29: parameters, constants, ranges, and units."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Problem:
    code: str
    message: str

    def __str__(self) -> str:
        return f"{self.code}: {self.message}"


EXPECTED_FILES = (
    "core/compiler/include/nodal/Dialect/Nodal/ParameterSemantics.h",
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "core/compiler/lib/Dialect/Nodal/ParameterSemantics.cpp",
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    "core/compiler/lib/Dialect/Nodal/CMakeLists.txt",
    "core/compiler/lib/Transforms/Passes.cpp",
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/Unit/CMakeLists.txt",
    "core/compiler/test/Unit/ParameterSemanticsTest.cpp",
    "core/compiler/test/IR/parameters-units.mlir",
    "core/compiler/test/IR/parameter-native-rendering.mlir",
    "core/compiler/test/IR/parameters-units-invalid-cycle.mlir",
    "core/compiler/test/IR/parameters-units-invalid-constraint.mlir",
    "core/compiler/test/IR/parameters-units-invalid-override.mlir",
    "core/compiler/test/IR/parameters-units-invalid-structural.mlir",
    "core/compiler/test/IR/parameters-units-invalid-unit.mlir",
    "core/compiler/test/IR/parameters-units-invalid-dynamic.mlir",
    "core/compiler/diagnostics-v0.1.json",
    "docs/design-gates/NodalParametersUnits-DG-v1.0.md",
    "docs/implementation/increment29-parameters-units.md",
    "tests/compiler/fixtures/increment29/manifest.json",
    "tests/compiler/test_increment29.py",
    "scripts/check_increment28.py",
    "tests/compiler/test_increment28.py",
    "scripts/check_increment29.py",
    ".github/workflows/increment-29-parameters-units.yml",
)

TEMPORARY_FILES = (
    "scripts/materialize_increment29.py",
    "scripts/finalize_increment29.py",
    "scripts/close_increment29.py",
    ".github/workflows/increment-29-materialize.yml",
    ".github/workflows/increment-29-finalize.yml",
    ".github/workflows/increment-29-close.yml",
)

CODES = [
    "NODAL-PARAMETER-KIND-001",
    "NODAL-PARAMETER-ROLE-001",
    "NODAL-PARAMETER-DEFAULT-001",
    "NODAL-PARAMETER-EXPRESSION-001",
    "NODAL-PARAMETER-CYCLE-001",
    "NODAL-PARAMETER-REFERENCE-001",
    "NODAL-PARAMETER-UNIT-001",
    "NODAL-PARAMETER-CONSTRAINT-001",
    "NODAL-PARAMETER-RANGE-001",
    "NODAL-PARAMETER-OVERRIDE-001",
    "NODAL-PARAMETER-FIXED-001",
    "NODAL-PARAMETER-STRUCTURAL-001",
    "NODAL-PARAMETER-ENVELOPE-001",
    "NODAL-PARAMETER-DYNAMIC-001",
    "NODAL-BACKEND-PARAMETER-001",
]


def read(path: Path, problems: list[Problem], code: str) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        problems.append(Problem(code, f"cannot read {path}: {exc}"))
        return ""


def require(text: str, fragments: tuple[str, ...], problems: list[Problem], code: str, subject: str) -> None:
    for fragment in fragments:
        if fragment not in text:
            problems.append(Problem(code, f"{subject} lacks: {fragment}"))


def revision(text: str) -> tuple[int, ...]:
    values = re.findall(r"^\*\*Revision:\*\* ([0-9.]+)$", text, re.MULTILINE)
    if len(values) != 1:
        return ()
    return tuple(int(part) for part in values[0].split("."))


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    for relative in EXPECTED_FILES:
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-INC29-001", f"missing file: {relative}"))
    for relative in TEMPORARY_FILES:
        if (root / relative).exists():
            problems.append(Problem("NODAL-INC29-002", f"temporary file remains: {relative}"))

    td = read(root / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td", problems, "NODAL-INC29-003")
    header = read(root / "core/compiler/include/nodal/Dialect/Nodal/ParameterSemantics.h", problems, "NODAL-INC29-004")
    source = read(root / "core/compiler/lib/Dialect/Nodal/ParameterSemantics.cpp", problems, "NODAL-INC29-005")
    transforms = read(root / "core/compiler/lib/Transforms/Passes.cpp", problems, "NODAL-INC29-006")
    backend = read(root / "core/compiler/lib/Backend/AnalogVerticalSlice.cpp", problems, "NODAL-INC29-007")
    positive = read(root / "core/compiler/test/IR/parameters-units.mlir", problems, "NODAL-INC29-008")
    unit = read(root / "core/compiler/test/Unit/ParameterSemanticsTest.cpp", problems, "NODAL-INC29-009")
    gate = read(root / "docs/design-gates/NodalParametersUnits-DG-v1.0.md", problems, "NODAL-INC29-010")
    implementation = read(root / "docs/implementation/increment29-parameters-units.md", problems, "NODAL-INC29-011")
    workflow = read(root / ".github/workflows/increment-29-parameters-units.yml", problems, "NODAL-INC29-012")
    catalog = read(root / "core/compiler/diagnostics-v0.1.json", problems, "NODAL-INC29-013")
    predecessor = read(root / "scripts/check_increment28.py", problems, "NODAL-INC29-014")
    predecessor_tests = read(root / "tests/compiler/test_increment28.py", problems, "NODAL-INC29-015")
    roadmap = read(root / "docs/roadmap/nodal-development-todo.md", problems, "NODAL-INC29-016")

    require(td, (
        "OptionalAttr<StrAttr>:$kind", "OptionalAttr<StrAttr>:$role",
        "OptionalAttr<StrAttr>:$unit", "OptionalAttr<ArrayAttr>:$constraints",
        "OptionalAttr<DictionaryAttr>:$structural_envelope",
    ), problems, "NODAL-INC29-003", "parameter ODS contract")
    require(header, (
        "verifyParameterSemantics", "parameterDeclarationOrder",
        "renderParameterDefault", "renderParameterConstraints", "parameterNativeType",
    ), problems, "NODAL-INC29-004", "parameter semantic API")
    require(source, (
        "enum class ParameterKind", "enum class ParameterRole", "lookupUnit",
        "evaluateExpression", "evaluateParameter", "NODAL-PARAMETER-CYCLE-001",
        "structural_effects", "static_generate", "fixed constant",
        "parameter dependency graph contains a cycle", "scaledRendering",
        "std::chars_format::general", "parameterDeclarationOrder",
        "renderParameterConstraints",
    ) + tuple(CODES[:-1]), problems, "NODAL-INC29-005", "parameter implementation")
    for forbidden in ("std::stod", "std::stof", "direction == \"output\"", "_zz"):
        if forbidden in source:
            problems.append(Problem("NODAL-INC29-005", f"forbidden unstable fragment: {forbidden}"))
    require(transforms, (
        "ParameterSemantics.h", "verifyParameterSemantics(module.getOperation())",
    ), problems, "NODAL-INC29-006", "semantic-pipeline integration")
    require(backend, (
        "ParameterSemantics.h", "parameterDeclarationOrder", "parameterNativeType",
        "renderParameterDefault", "renderParameterConstraints", "localparam ",
        "NODAL-BACKEND-PARAMETER-001",
    ), problems, "NODAL-INC29-007", "native parameter rendering")
    require(positive, (
        'kind = "real"', 'kind = "integer"', 'kind = "boolean"',
        'role = "ordinary"', 'role = "structural"', 'variability = "fixed"',
        'variability = "symbolic"', 'unit = "kOhm"', 'unit = "pF"',
        'kind = "range"', 'policy = "static_generate"',
        'structural_effects = ["component_count", "equation_count", "shape"]',
        'parameter_bindings = {', 'op = "ref"', 'op = "add"',
    ), problems, "NODAL-INC29-008", "positive fixture")
    require(unit, (
        "dependency-before-user parameter order is incorrect",
        "unit-scaled source literal spelling was not retained",
        "native range rendering is incorrect",
        "parameter dependency cycle was accepted",
    ), problems, "NODAL-INC29-009", "native unit coverage")
    require(gate, (
        "**Status:** Approved", "**Scope:** compiler-ir", "**Public API:** unchanged at 0.3",
        "dynamic values are not parameter declarations", "static_generate",
        "General compound-unit algebra", "localparam real/integer",
    ), problems, "NODAL-INC29-010", "design gate")
    require(implementation, (
        "cycle-safe", "range and exclusion", "structural-effect",
        "source-spelling-preserving", "public API v0.3 is unchanged", "fail",
    ), problems, "NODAL-INC29-011", "implementation note")
    require(workflow, (
        "increment-29/parameters-units", "check_increment29.py", "./nodal core native",
        "parameter-native-rendering.mlir", "NODAL-PARAMETER-CYCLE-001",
        "NODAL-PARAMETER-CONSTRAINT-001", "NODAL-PARAMETER-FIXED-001",
        "NODAL-PARAMETER-ENVELOPE-001", "NODAL-PARAMETER-UNIT-001",
        "permissions:\n  contents: read",
    ), problems, "NODAL-INC29-012", "permanent workflow")
    if "contents: write" in workflow or "materialize_increment29" in workflow:
        problems.append(Problem("NODAL-INC29-012", "permanent workflow must be read-only"))
    for code in CODES:
        if code not in catalog:
            problems.append(Problem("NODAL-INC29-013", f"diagnostic catalog lacks {code}"))
    require(predecessor, (
        "tests/compiler/fixtures/increment29/manifest.json",
        "validated-parameters-units", "increment29_done", "increment30_open",
    ), problems, "NODAL-INC29-014", "Increment 28 successor handling")
    require(predecessor_tests, (
        "test_accepts_validated_increment29_successor", "validated-parameters-units",
    ), problems, "NODAL-INC29-015", "Increment 28 successor tests")

    manifest_path = root / "tests/compiler/fixtures/increment29/manifest.json"
    try:
        manifest = json.loads(read(manifest_path, problems, "NODAL-INC29-016"))
    except json.JSONDecodeError as exc:
        problems.append(Problem("NODAL-INC29-016", f"invalid manifest: {exc}"))
        manifest = {}
    if manifest.get("increment") != 29 or manifest.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC29-016", "manifest identity/public API mismatch"))
    if manifest.get("parameter_kinds") != ["real", "integer", "boolean"]:
        problems.append(Problem("NODAL-INC29-016", "manifest parameter-kind inventory mismatch"))
    if manifest.get("expression_ops") != ["literal", "ref", "neg", "add", "sub", "mul", "div"]:
        problems.append(Problem("NODAL-INC29-016", "manifest expression inventory mismatch"))
    if manifest.get("diagnostics") != CODES:
        problems.append(Problem("NODAL-INC29-016", "manifest diagnostic inventory mismatch"))

    rev = revision(roadmap)
    increment28_done = "- [x] **Increment 28 — Electrical nodes, nets, and branches**" in roadmap
    increment29_open = "- [ ] **Increment 29 — Parameters, constants, ranges, and units**" in roadmap
    increment29_done = "- [x] **Increment 29 — Parameters, constants, ranges, and units**" in roadmap
    increment30_open = "- [ ] **Increment 30 — Analog numeric types and expression typing**" in roadmap
    status = manifest.get("status")
    evidence = manifest.get("evidence", {})
    if not increment28_done:
        problems.append(Problem("NODAL-INC29-016", "Increment 28 prerequisite is not closed"))
    if status == "implemented-awaiting-evidence":
        if not increment29_open or rev < (1, 36):
            problems.append(Problem("NODAL-INC29-016", "pre-evidence state must leave Increment 29 unchecked at revision 1.36 or later"))
    elif status == "validated-parameters-units":
        if not increment29_done or rev < (1, 37):
            problems.append(Problem("NODAL-INC29-016", "validated state must close Increment 29 at revision 1.37 or later"))
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(evidence.get(field), int):
                problems.append(Problem("NODAL-INC29-016", f"validated manifest lacks integer evidence field: {field}"))
    else:
        problems.append(Problem("NODAL-INC29-016", f"unexpected manifest status: {status!r}"))
    if not increment30_open:
        problems.append(Problem("NODAL-INC29-016", "Increment 30 must remain unchecked"))
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"Increment 29 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 29 parameters, constants, ranges, and units check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
''',
)

write(
    "tests/compiler/test_increment29.py",
    r'''
from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_increment29.py"
SPEC = importlib.util.spec_from_file_location("check_increment29", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)

SUPPORT = ("docs/roadmap/nodal-development-todo.md",)


class Increment29CheckerTests(unittest.TestCase):
    def temporary_repository(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in dict.fromkeys(CHECKER.EXPECTED_FILES + SUPPORT):
            source = ROOT / relative
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        return temporary, root

    def codes(self, root: Path) -> set[str]:
        return {problem.code for problem in CHECKER.check_repository(root)}

    def test_repository_contract(self) -> None:
        self.assertEqual(CHECKER.check_repository(ROOT), [])

    def test_rejects_missing_cycle_detection(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/ParameterSemantics.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace("constant-expression cycle reaches parameter", "cycle check removed", 1), encoding="utf-8")
        self.assertIn("NODAL-INC29-005", self.codes(root))

    def test_rejects_missing_unit_registry(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/ParameterSemantics.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace("lookupUnit", "lookupUnknown", 1), encoding="utf-8")
        self.assertIn("NODAL-INC29-005", self.codes(root))

    def test_rejects_weakened_fixed_override(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/ParameterSemantics.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace("fixed constant", "overridable constant", 1), encoding="utf-8")
        self.assertIn("NODAL-INC29-005", self.codes(root))

    def test_rejects_missing_structural_envelope(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/ParameterSemantics.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace("static_generate", "dynamic_generate", 1), encoding="utf-8")
        self.assertIn("NODAL-INC29-005", self.codes(root))

    def test_rejects_backend_round_trip_rendering(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/AnalogVerticalSlice.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace("renderParameterDefault", "formatReal", 1), encoding="utf-8")
        self.assertIn("NODAL-INC29-007", self.codes(root))

    def test_rejects_writable_workflow(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-29-parameters-units.yml"
        path.write_text(path.read_text(encoding="utf-8").replace("contents: read", "contents: write", 1), encoding="utf-8")
        self.assertIn("NODAL-INC29-012", self.codes(root))

    def test_rejects_premature_roadmap_closure(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(roadmap.read_text(encoding="utf-8").replace(
            "- [ ] **Increment 29 — Parameters, constants, ranges, and units**",
            "- [x] **Increment 29 — Parameters, constants, ranges, and units**", 1), encoding="utf-8")
        self.assertIn("NODAL-INC29-016", self.codes(root))

    def test_accepts_validated_state(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        manifest_path = root / "tests/compiler/fixtures/increment29/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "validated-parameters-units"
        manifest["evidence"] = {"pull_request": 1, "dedicated_run": 2, "core_ci_run": 3}
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(roadmap.read_text(encoding="utf-8").replace("**Revision:** 1.36", "**Revision:** 1.37", 1).replace(
            "- [ ] **Increment 29 — Parameters, constants, ranges, and units**",
            "- [x] **Increment 29 — Parameters, constants, ranges, and units**", 1), encoding="utf-8")
        self.assertEqual(CHECKER.check_repository(root), [])


if __name__ == "__main__":
    unittest.main()
''',
)

write(
    ".github/workflows/increment-29-parameters-units.yml",
    r'''
name: Increment 29 Parameters Constants Ranges and Units

on:
  push:
    branches:
      - increment/29-parameters-units
  pull_request:
    branches:
      - dev
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: increment-29-${{ github.ref }}
  cancel-in-progress: true

jobs:
  parameters-units:
    name: increment-29/parameters-units
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
          key: nodal-inc29-${{ runner.os }}-${{ hashFiles('build.mill', '.mill-version', 'mill', 'toolchains/lock.json', 'toolchains/checksums/*.sha256', 'toolchains/lint-lock.json') }}
          restore-keys: |
            nodal-inc29-${{ runner.os }}-
            nodal-inc28-${{ runner.os }}-
            nodal-inc27-${{ runner.os }}-
            nodal-scala-${{ runner.os }}-

      - name: Install locked native and lint toolchains
        run: |
          ./nodal bootstrap \
            --mode prebuilt \
            --prefix "${RUNNER_TEMP}/nodal-native-toolchain"
          ./nodal style bootstrap \
            --prefix "${RUNNER_TEMP}/nodal-lint-toolchain"

      - name: Validate contracts and mutation tests
        env:
          PYTHONDONTWRITEBYTECODE: '1'
        run: |
          for increment in $(seq 18 29); do
            python3 "scripts/check_increment${increment}.py"
          done
          python3 scripts/check_increment29.py
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

      - name: Prove folding rendering and stable rejections
        run: |
          set -euo pipefail
          compiler="${PWD}/out/native/release/bin/nodalc"
          translator="${PWD}/out/native/release/bin/nodal-translate"
          pipeline='builtin.module(nodal-gate-default)'

          "${compiler}" --pass-pipeline="${pipeline}" \
            core/compiler/test/IR/parameters-units.mlir \
            | tee /tmp/parameters-units.mlir
          grep -F 'structural_envelope' /tmp/parameters-units.mlir
          grep -F 'parameter_bindings' /tmp/parameters-units.mlir

          "${translator}" --nodal-to-verilog-a \
            core/compiler/test/IR/parameter-native-rendering.mlir \
            | tee /tmp/parameter-native-rendering.va
          grep -F 'localparam real BASE_R = (1.25 * 1e3);' /tmp/parameter-native-rendering.va
          grep -F 'parameter real R = BASE_R from [1.0:(10.0 * 1e3)];' /tmp/parameter-native-rendering.va
          grep -F 'parameter integer STAGES = 4 from [1:8];' /tmp/parameter-native-rendering.va
          grep -F 'parameter integer CHECKS = 1;' /tmp/parameter-native-rendering.va

          check_rejection() {
            fixture="$1"
            code="$2"
            if "${compiler}" --pass-pipeline="${pipeline}" \
                "core/compiler/test/IR/parameters-units-invalid-${fixture}.mlir" \
                >"/tmp/${fixture}.out" 2>"/tmp/${fixture}.err"; then
              echo "invalid ${fixture} parameter model was accepted" >&2
              exit 1
            fi
            grep -F "${code}" "/tmp/${fixture}.err"
          }

          check_rejection cycle NODAL-PARAMETER-CYCLE-001
          check_rejection constraint NODAL-PARAMETER-CONSTRAINT-001
          check_rejection override NODAL-PARAMETER-FIXED-001
          check_rejection structural NODAL-PARAMETER-ENVELOPE-001
          check_rejection unit NODAL-PARAMETER-UNIT-001
          check_rejection dynamic NODAL-PARAMETER-DYNAMIC-001
''',
)

print("Increment 29 materialization complete")
