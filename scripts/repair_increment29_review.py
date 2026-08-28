#!/usr/bin/env python3
# Repair Increment 29 review findings and add regression coverage.
# Retrigger marker: execute after the workflow definition is present on branch.

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(relative: str, old: str, new: str) -> None:
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{relative}: expected one anchor, found {count}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# ---------------------------------------------------------------------------
# Parameter model: retain exact wide integers and physical unit context.
# ---------------------------------------------------------------------------

replace_once(
    "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp",
    '#include "llvm/ADT/SmallVector.h"\n',
    '#include "llvm/ADT/SmallString.h"\n#include "llvm/ADT/SmallVector.h"\n',
)

replace_once(
    "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp",
    '''struct EvaluatedConstant {
  ConstantKind kind = ConstantKind::Invalid;
  int64_t integerValue = 0;
  double realValue = 0.0;
  bool booleanValue = false;
  std::string dimension;
};
''',
    '''struct EvaluatedConstant {
  ConstantKind kind = ConstantKind::Invalid;
  int64_t integerValue = 0;
  IntegerAttr exactInteger;
  bool integerIsNarrow = true;
  bool integerSigned = false;
  double realValue = 0.0;
  bool booleanValue = false;
  std::string dimension;
};
''',
)

replace_once(
    "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp",
    '''llvm::StringRef inferredKind(Type type) {
  switch (kindForType(type)) {
''',
    '''bool integerTypeIsSigned(Type type) {
  if (llvm::isa<nodal::SIntType>(type))
    return true;
  if (auto integer = llvm::dyn_cast<IntegerType>(type))
    return integer.isSigned();
  return false;
}

llvm::StringRef inferredKind(Type type) {
  switch (kindForType(type)) {
''',
)

replace_once(
    "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp",
    '''  if (result.kind == ConstantKind::Integer) {
    auto integer = llvm::dyn_cast<IntegerAttr>(value);
    if (!integer || !integer.getValue().isSignedIntN(64))
      return failure();
    result.integerValue = integer.getInt();
    return result;
  }
''',
    '''  if (result.kind == ConstantKind::Integer) {
    auto integer = llvm::dyn_cast<IntegerAttr>(value);
    if (!integer)
      return failure();
    result.exactInteger = integer;
    result.integerSigned = integerTypeIsSigned(type);
    const llvm::APInt &bits = integer.getValue();
    const bool narrow =
        result.integerSigned ? bits.isSignedIntN(64) : bits.getActiveBits() <= 63;
    result.integerIsNarrow = narrow;
    if (narrow)
      result.integerValue =
          result.integerSigned ? bits.getSExtValue()
                               : static_cast<int64_t>(bits.getZExtValue());
    return result;
  }
''',
)

replace_once(
    "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp",
    '''FailureOr<EvaluatedConstant> evaluateParameterDefault(Operation *parameter,
                                                      llvm::DenseSet<Operation *> &parameterStack) {
  if (!parameter || !parameterStack.insert(parameter).second)
    return failure();
  Operation *module = enclosingNodalModule(parameter);
  Operation *value = findParameterValue(module, symbolName(parameter));
  FailureOr<EvaluatedConstant> result = failure();
  if (value) {
    result = evaluateValue(value->getOperand(0), parameter, parameterStack);
  } else {
    auto type = parameter->getAttrOfType<TypeAttr>("type");
    Attribute defaultValue = parameter->getAttr("default_value");
    Operation *unit = resolveUnit(parameter, parameter->getAttrOfType<FlatSymbolRefAttr>("unit"));
    if (type && defaultValue)
      result = constantFromAttribute(defaultValue, type.getValue(), unit);
  }
  parameterStack.erase(parameter);
  return result;
}
''',
    '''FailureOr<EvaluatedConstant> adoptParameterUnit(EvaluatedConstant value,
                                                        Operation *parameter) {
  Operation *unit =
      resolveUnit(parameter, parameter->getAttrOfType<FlatSymbolRefAttr>("unit"));
  if (!unit || value.kind != ConstantKind::Real)
    return value;
  llvm::StringRef dimension = unitDimension(unit);
  if (value.dimension.empty()) {
    value.realValue *= unitScale(unit);
    value.dimension = dimension.str();
  } else if (value.dimension != dimension) {
    return failure();
  }
  if (!std::isfinite(value.realValue))
    return failure();
  return value;
}

FailureOr<EvaluatedConstant> evaluateParameterDefault(
    Operation *parameter, llvm::DenseSet<Operation *> &parameterStack) {
  if (!parameter || !parameterStack.insert(parameter).second)
    return failure();
  Operation *module = enclosingNodalModule(parameter);
  Operation *value = findParameterValue(module, symbolName(parameter));
  FailureOr<EvaluatedConstant> result = failure();
  if (value) {
    result = evaluateValue(value->getOperand(0), parameter, parameterStack);
  } else {
    auto type = parameter->getAttrOfType<TypeAttr>("type");
    Attribute defaultValue = parameter->getAttr("default_value");
    Operation *unit =
        resolveUnit(parameter, parameter->getAttrOfType<FlatSymbolRefAttr>("unit"));
    if (type && defaultValue)
      result = constantFromAttribute(defaultValue, type.getValue(), unit);
  }
  if (succeeded(result))
    result = adoptParameterUnit(*result, parameter);
  parameterStack.erase(parameter);
  return result;
}
''',
)

replace_once(
    "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp",
    '''    if (result.kind == ConstantKind::Integer && lhs->kind == ConstantKind::Integer &&
        checkedNegate(lhs->integerValue, result.integerValue))
''',
    '''    if (result.kind == ConstantKind::Integer && lhs->kind == ConstantKind::Integer &&
        lhs->integerIsNarrow &&
        checkedNegate(lhs->integerValue, result.integerValue))
''',
)

# There are exactly two binary integer arithmetic blocks (add/sub and mul/div).
path = ROOT / "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp"
text = path.read_text(encoding="utf-8")
old_guard = '''    if (result.kind == ConstantKind::Integer && lhs->kind == ConstantKind::Integer &&
        rhs->kind == ConstantKind::Integer) {
'''
new_guard = '''    if (result.kind == ConstantKind::Integer && lhs->kind == ConstantKind::Integer &&
        rhs->kind == ConstantKind::Integer && lhs->integerIsNarrow &&
        rhs->integerIsNarrow) {
'''
if text.count(old_guard) != 2:
    raise RuntimeError(f"expected two integer binary guards, found {text.count(old_guard)}")
path.write_text(text.replace(old_guard, new_guard), encoding="utf-8")

replace_once(
    "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp",
    '''  if (name == "mod") {
    if (result.kind != ConstantKind::Integer || lhs->kind != ConstantKind::Integer ||
        rhs->kind != ConstantKind::Integer || lhs->dimension != rhs->dimension ||
        rhs->integerValue == 0 ||
''',
    '''  if (name == "mod") {
    if (result.kind != ConstantKind::Integer || lhs->kind != ConstantKind::Integer ||
        rhs->kind != ConstantKind::Integer || !lhs->integerIsNarrow ||
        !rhs->integerIsNarrow || lhs->dimension != rhs->dimension ||
        rhs->integerValue == 0 ||
''',
)

replace_once(
    "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp",
    '''    result.kind = ConstantKind::Integer;
    result.integerValue = value.integerValue;
    return result;
''',
    '''    result.kind = ConstantKind::Integer;
    result.integerValue = value.integerValue;
    result.exactInteger = value.exactInteger;
    result.integerIsNarrow = value.integerIsNarrow;
    result.integerSigned = value.integerSigned;
    return result;
''',
)

replace_once(
    "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp",
    '''  if (value.kind == ConstantKind::Integer) {
    auto integer = llvm::dyn_cast<IntegerAttr>(attribute);
    return integer && integer.getValue().isSignedIntN(64) && integer.getInt() == value.integerValue;
  }
''',
    '''  if (value.kind == ConstantKind::Integer) {
    auto integer = llvm::dyn_cast<IntegerAttr>(attribute);
    if (!integer)
      return false;
    if (value.exactInteger) {
      const unsigned width =
          std::max(integer.getValue().getBitWidth(),
                   value.exactInteger.getValue().getBitWidth());
      llvm::APInt lhs = value.integerSigned
                            ? value.exactInteger.getValue().sextOrTrunc(width)
                            : value.exactInteger.getValue().zextOrTrunc(width);
      llvm::APInt rhs = value.integerSigned
                            ? integer.getValue().sextOrTrunc(width)
                            : integer.getValue().zextOrTrunc(width);
      return lhs == rhs;
    }
    return value.integerIsNarrow && integer.getValue().isSignedIntN(64) &&
           integer.getInt() == value.integerValue;
  }
''',
)

replace_once(
    "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp",
    '''  if (lhs.integerValue == rhs.integerValue)
    return 0;
  return lhs.integerValue < rhs.integerValue ? -1 : 1;
}
''',
    '''  if (lhs.exactInteger || rhs.exactInteger) {
    const unsigned lhsWidth =
        lhs.exactInteger ? lhs.exactInteger.getValue().getBitWidth() : 64;
    const unsigned rhsWidth =
        rhs.exactInteger ? rhs.exactInteger.getValue().getBitWidth() : 64;
    const unsigned width = std::max(lhsWidth, rhsWidth);
    const bool signedCompare = lhs.integerSigned || rhs.integerSigned;
    llvm::APInt left =
        lhs.exactInteger
            ? (signedCompare ? lhs.exactInteger.getValue().sextOrTrunc(width)
                             : lhs.exactInteger.getValue().zextOrTrunc(width))
            : llvm::APInt(width, static_cast<uint64_t>(lhs.integerValue), true);
    llvm::APInt right =
        rhs.exactInteger
            ? (signedCompare ? rhs.exactInteger.getValue().sextOrTrunc(width)
                             : rhs.exactInteger.getValue().zextOrTrunc(width))
            : llvm::APInt(width, static_cast<uint64_t>(rhs.integerValue), true);
    if (left == right)
      return 0;
    return signedCompare ? (left.slt(right) ? -1 : 1)
                         : (left.ult(right) ? -1 : 1);
  }
  if (lhs.integerValue == rhs.integerValue)
    return 0;
  return lhs.integerValue < rhs.integerValue ? -1 : 1;
}
''',
)

replace_once(
    "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp",
    '''    if (auto integer = llvm::dyn_cast<IntegerAttr>(valueAttr)) {
      visited.erase(operation);
      return std::to_string(integer.getInt());
    }
''',
    '''    if (auto integer = llvm::dyn_cast<IntegerAttr>(valueAttr)) {
      llvm::SmallString<64> rendered;
      integer.getValue().toString(rendered, 10,
                                  integerTypeIsSigned(value.getType()));
      visited.erase(operation);
      return rendered.str().str();
    }
''',
)

replace_once(
    "core/compiler/include/nodal/Dialect/Nodal/ParameterModel.h",
    '''/// Render a compile-time expression using retained literal spellings.
mlir::FailureOr<std::string> renderParameterConstantExpression(mlir::Value value);
''',
    '''/// Render a compile-time expression using retained literal spellings.
mlir::FailureOr<std::string> renderParameterConstantExpression(mlir::Value value);

/// Render an expression in one parameter's declared unit context. A
/// dimensionless authored magnitude receives the target parameter's exact
/// native scale suffix (or an explicit scale factor for compound expressions).
mlir::FailureOr<std::string>
renderParameterConstantExpression(mlir::Value value,
                                  mlir::Operation *targetParameter);
''',
)

replace_once(
    "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp",
    '''FailureOr<std::string> nodal::renderParameterConstantExpression(Value value) {
  llvm::DenseSet<Operation *> visited;
  return renderValue(value, visited);
}
''',
    '''FailureOr<std::string> nodal::renderParameterConstantExpression(Value value) {
  llvm::DenseSet<Operation *> visited;
  return renderValue(value, visited);
}

FailureOr<std::string>
nodal::renderParameterConstantExpression(Value value,
                                         Operation *targetParameter) {
  auto rendered = renderParameterConstantExpression(value);
  if (failed(rendered) || !targetParameter)
    return rendered;

  Operation *unit = resolveUnit(
      targetParameter,
      targetParameter->getAttrOfType<FlatSymbolRefAttr>("unit"));
  if (!unit || getParameterKind(targetParameter) != "real")
    return rendered;

  llvm::DenseSet<Operation *> stack;
  auto evaluated = evaluateValue(value, targetParameter, stack);
  if (failed(evaluated))
    return failure();
  if (!evaluated->dimension.empty())
    return rendered;

  llvm::StringRef suffix = unitSuffix(unit);
  if (suffix.empty())
    return rendered;

  Operation *definition = value.getDefiningOp();
  if (definition && isNamed(definition, "nodal.const_literal") &&
      !definition->getAttrOfType<FlatSymbolRefAttr>("unit"))
    return *rendered + suffix.str();

  return (llvm::Twine("(") + *rendered + " * 1" + suffix + ")").str();
}
''',
)

# ---------------------------------------------------------------------------
# Backend: use parameter context, preserve wide integer text, and validate
# generated unit comments as canonical comment text rather than identifiers.
# ---------------------------------------------------------------------------

replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '#include "nodal/Dialect/Nodal/ParameterModel.h"\n',
    '#include "nodal/Dialect/Nodal/ParameterModel.h"\n#include "nodal/Dialect/Nodal/NodalTypes.h"\n',
)
replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '#include "llvm/ADT/SmallVector.h"\n',
    '#include "llvm/ADT/SmallString.h"\n#include "llvm/ADT/SmallVector.h"\n',
)

replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '''FailureOr<std::string> legacyParameterInitializer(Operation *parameter) {
''',
    '''bool parameterIntegerIsSigned(Operation *parameter) {
  auto type = parameter->getAttrOfType<TypeAttr>("type");
  if (!type)
    return false;
  if (llvm::isa<nodal::SIntType>(type.getValue()))
    return true;
  if (auto integer = llvm::dyn_cast<IntegerType>(type.getValue()))
    return integer.isSigned();
  return false;
}

FailureOr<std::string> renderIntegerAttribute(IntegerAttr integer,
                                              Operation *parameter) {
  llvm::SmallString<64> rendered;
  integer.getValue().toString(rendered, 10,
                              parameterIntegerIsSigned(parameter));
  return rendered.str().str();
}

FailureOr<std::string> legacyParameterInitializer(Operation *parameter) {
''',
)

replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '''  if (kind == "integer") {
    auto integer = llvm::dyn_cast<IntegerAttr>(value);
    if (!integer)
      return failure();
    return std::to_string(integer.getInt());
  }
''',
    '''  if (kind == "integer") {
    auto integer = llvm::dyn_cast<IntegerAttr>(value);
    if (!integer)
      return failure();
    return renderIntegerAttribute(integer, parameter);
  }
''',
)

replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '''    if (Operation *value = findParameterValue(definition, symbolName(parameter)))
      initializer = nodal::renderParameterConstantExpression(value->getOperand(0));
''',
    '''    if (Operation *value = findParameterValue(definition, symbolName(parameter)))
      initializer =
          nodal::renderParameterConstantExpression(value->getOperand(0), parameter);
''',
)

replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '''        auto lower = nodal::renderParameterConstantExpression(constraint->getOperand(0));
        auto upper = nodal::renderParameterConstantExpression(constraint->getOperand(1));
''',
    '''        auto lower =
            nodal::renderParameterConstantExpression(constraint->getOperand(0), parameter);
        auto upper =
            nodal::renderParameterConstantExpression(constraint->getOperand(1), parameter);
''',
)

replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '''        auto excluded = nodal::renderParameterConstantExpression(constraint->getOperand(0));
''',
    '''        auto excluded =
            nodal::renderParameterConstantExpression(constraint->getOperand(0), parameter);
''',
)

replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '''bool validIdentifierList(llvm::StringRef value) {
''',
    '''bool validCanonicalCommentText(llvm::StringRef value) {
  if (value.empty() || value != value.trim())
    return false;
  return llvm::all_of(value, [](char character) {
    const unsigned char byte = static_cast<unsigned char>(character);
    return byte >= 0x20 && byte != 0x7f;
  });
}

bool validIdentifierList(llvm::StringRef value) {
''',
)

replace_once(
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    '''      if (!validIdentifierList(unit))
        return false;
''',
    '''      if (!validCanonicalCommentText(unit))
        return false;
''',
)

# ---------------------------------------------------------------------------
# Native regression tests.
# ---------------------------------------------------------------------------

replace_once(
    "core/compiler/test/Unit/ParameterModelTest.cpp",
    '''  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0e3 : f64, metadata = {}, parameter_kind = "real", sym_name = "R", type = f64, unit = @Ohm, variability = "symbolic"}> : () -> ()
''',
    '''  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "RBASE", type = f64, unit = @kOhm, variability = "symbolic"}> : () -> ()
    %rbase_value = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1.0 : f64}> : () -> f64
    "nodal.parameter_value"(%rbase_value) <{metadata = {}, parameter = @RBASE}> : (f64) -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 2.0e3 : f64, metadata = {}, parameter_kind = "real", sym_name = "RTOTAL", type = f64, unit = @Ohm, variability = "symbolic"}> : () -> ()
    %rbase_ref = "nodal.const_parameter_ref"() <{metadata = {}, parameter = @RBASE}> : () -> f64
    %one_k = "nodal.const_literal"() <{metadata = {}, spelling = "1k", unit = @kOhm, value = 1.0 : f64}> : () -> f64
    %rtotal_value = "nodal.const_expr"(%rbase_ref, %one_k) <{metadata = {}, operator_name = "add"}> : (f64, f64) -> f64
    "nodal.parameter_value"(%rtotal_value) <{metadata = {}, parameter = @RTOTAL}> : (f64) -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 340282366920938463463374607431768211455 : i128, metadata = {}, parameter_kind = "integer", sym_name = "WIDE", type = !nodal.uint<128>, variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0e3 : f64, metadata = {}, parameter_kind = "real", sym_name = "R", type = f64, unit = @Ohm, variability = "symbolic"}> : () -> ()
''',
)

replace_once(
    "core/compiler/test/Unit/ParameterModelTest.cpp",
    '''  bool sawLossless = false;
  valid->walk([&](nodal::ParameterValueOp value) {
    auto parameter = value->getAttrOfType<mlir::FlatSymbolRefAttr>("parameter");
    if (!parameter || parameter.getValue() != "R")
      return;
    auto rendered = nodal::renderParameterConstantExpression(value->getOperand(0));
    sawLossless = mlir::succeeded(rendered) && *rendered == "1k";
  });
  if (!sawLossless)
    return fail("constant expression did not preserve native spelling");
''',
    '''  bool sawLossless = false;
  bool sawTargetUnit = false;
  valid->walk([&](nodal::ParameterValueOp value) {
    auto parameter = value->getAttrOfType<mlir::FlatSymbolRefAttr>("parameter");
    if (!parameter)
      return;
    if (parameter.getValue() == "R") {
      auto rendered = nodal::renderParameterConstantExpression(value->getOperand(0));
      sawLossless = mlir::succeeded(rendered) && *rendered == "1k";
    }
    if (parameter.getValue() == "RBASE") {
      mlir::Operation *declaration = nullptr;
      value->getParentOp()->walk([&](nodal::ParameterOp candidate) {
        if (candidate.getSymName() == "RBASE")
          declaration = candidate.getOperation();
      });
      auto rendered =
          nodal::renderParameterConstantExpression(value->getOperand(0), declaration);
      sawTargetUnit = mlir::succeeded(rendered) && *rendered == "1k";
    }
  });
  if (!sawLossless)
    return fail("constant expression did not preserve native spelling");
  if (!sawTargetUnit)
    return fail("bare parameter magnitude did not inherit target unit");
''',
)

replace_once(
    "core/compiler/test/IR/parameter-rendering.mlir",
    '''  "nodal.unit"() <{dimension = "resistance", metadata = {}, native_suffix = "k", scale = 1.0e3 : f64, sym_name = "kOhm", symbol = "kOhm"}> : () -> ()
  "nodal.module"() <{metadata = {root = true}, sym_name = "ParameterRendering"}> ({
''',
    '''  "nodal.unit"() <{dimension = "resistance", metadata = {}, native_suffix = "k", scale = 1.0e3 : f64, sym_name = "kOhm", symbol = "kOhm"}> : () -> ()
  "nodal.unit"() <{dimension = "resistance", metadata = {}, native_suffix = "k", scale = 1.0e3 : f64, sym_name = "kOhmPretty", symbol = "kΩ/V"}> : () -> ()
  "nodal.module"() <{metadata = {root = true}, sym_name = "ParameterRendering"}> ({
''',
)

replace_once(
    "core/compiler/test/IR/parameter-rendering.mlir",
    '''  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = true, metadata = {}, parameter_kind = "boolean", sym_name = "ENABLE", type = i1, variability = "symbolic"}> : () -> ()
''',
    '''  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "R_BARE", type = f64, unit = @kOhmPretty, variability = "symbolic"}> : () -> ()
    %r_bare = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1.0 : f64}> : () -> f64
    "nodal.parameter_value"(%r_bare) <{metadata = {}, parameter = @R_BARE}> : (f64) -> ()
    %r_bare_low = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1.0 : f64}> : () -> f64
    %r_bare_high = "nodal.const_literal"() <{metadata = {}, spelling = "2", value = 2.0 : f64}> : () -> f64
    "nodal.parameter_constraint"(%r_bare_low, %r_bare_high) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @R_BARE, upper_inclusive = true}> : (f64, f64) -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 340282366920938463463374607431768211455 : i128, metadata = {}, parameter_kind = "integer", sym_name = "WIDE", type = !nodal.uint<128>, variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = true, metadata = {}, parameter_kind = "boolean", sym_name = "ENABLE", type = i1, variability = "symbolic"}> : () -> ()
''',
)

replace_once(
    ".github/workflows/increment-29-parameters-units.yml",
    '''          grep -F 'parameter integer ENABLE = 1;' /tmp/parameter-rendering.va
''',
    '''          grep -F 'parameter real R_BARE = 1k from [1k:2k]; // unit: kΩ/V' /tmp/parameter-rendering.va
          grep -F 'parameter integer WIDE = 340282366920938463463374607431768211455;' /tmp/parameter-rendering.va
          grep -F 'parameter integer ENABLE = 1;' /tmp/parameter-rendering.va
''',
)

# Lock the review repairs into repository and mutation contracts.
replace_once(
    "scripts/check_increment29.py",
    '''        "renderParameterConstantExpression", "allowedSuffix", "splitSpelling",
''',
    '''        "renderParameterConstantExpression", "allowedSuffix", "splitSpelling",
        "exactInteger", "adoptParameterUnit",
        "renderParameterConstantExpression(Value value,",
''',
)

replace_once(
    "scripts/check_increment29.py",
    '''        " exclude ", "// unit: ", "NODAL-BACKEND-PARAMETER-001", "NODAL-BACKEND-PARAMETER-002",
''',
    '''        " exclude ", "// unit: ", "NODAL-BACKEND-PARAMETER-001", "NODAL-BACKEND-PARAMETER-002",
        "validCanonicalCommentText", "renderIntegerAttribute",
''',
)

replace_once(
    "scripts/check_increment29.py",
    '''        "cyclic constant expression was accepted",
''',
    '''        "cyclic constant expression was accepted",
        "bare parameter magnitude did not inherit target unit",
''',
)

replace_once(
    "scripts/check_increment29.py",
    '''        'constraint_kind = "range"', 'constraint_kind = "exclude"',
''',
    '''        'constraint_kind = "range"', 'constraint_kind = "exclude"',
        'sym_name = "kOhmPretty"', 'symbol = "kΩ/V"',
''',
)

replace_once(
    "tests/compiler/test_increment29.py",
    '''    def test_rejects_missing_dynamic_value_guard(self) -> None:
''',
    '''    def test_rejects_missing_parameter_unit_adoption(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "adoptParameterUnit", "discardParameterUnit"
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC29-005", self.codes(root))

    def test_rejects_missing_wide_integer_retention(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace("exactInteger", "narrowIntegerOnly"),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC29-005", self.codes(root))

    def test_rejects_missing_canonical_unit_comment_validation(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/AnalogVerticalSlice.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "validCanonicalCommentText", "validIdentifierList"
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC29-008", self.codes(root))

    def test_rejects_missing_dynamic_value_guard(self) -> None:
''',
)

print("Increment 29 review repair materialized")
