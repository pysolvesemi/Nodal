#!/usr/bin/env python3
# Apply and validate final semantic review fixes for Nodal Increment 30.

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def write(relative: str, text: str) -> None:
    (ROOT / relative).write_text(text, encoding="utf-8")


def replace_region(relative: str, start_marker: str, end_marker: str, replacement: str) -> None:
    text = read(relative)
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f"start marker not found in {relative}: {start_marker}")
    end = text.find(end_marker, start + len(start_marker))
    if end < 0:
        raise SystemExit(f"end marker not found in {relative}: {end_marker}")
    write(relative, text[:start] + replacement + text[end:])


def insert_after_line(relative: str, target: str, additions: list[str]) -> None:
    lines = read(relative).splitlines()
    matches = [index for index, line in enumerate(lines) if line == target]
    if len(matches) != 1:
        raise SystemExit(f"expected one line in {relative}, found {len(matches)}: {target}")
    index = matches[0] + 1
    lines[index:index] = additions
    write(relative, "\n".join(lines) + "\n")


def insert_before_once(relative: str, marker: str, addition: str) -> None:
    text = read(relative)
    count = text.count(marker)
    if count != 1:
        raise SystemExit(f"expected one marker in {relative}, found {count}: {marker}")
    write(relative, text.replace(marker, addition + marker, 1))


def create(relative: str, content: str) -> None:
    path = ROOT / relative
    if path.exists():
        raise SystemExit(f"refusing to overwrite existing file: {relative}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


ANALOG = "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp"

replace_region(
    ANALOG,
    "EvaluationResult evaluateSelect(Operation *operation, bool reportErrors) {",
    "EvaluationResult evaluateValue(Value value, bool reportErrors) {",
    r'''EvaluationResult evaluateSelect(Operation *operation, bool reportErrors) {
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

''',
)

replace_region(
    ANALOG,
    "bool isFoldCandidate(Operation *operation) {",
    "LogicalResult verifyRealLiteral(Operation *operation) {",
    r'''constexpr llvm::StringLiteral kFoldAttributes[] = {
    "nodal.folded",       "nodal.folded_kind",       "nodal.folded_dimension",
    "nodal.folded_value", "nodal.folded_provenance",
};

bool isFoldCandidate(Operation *operation) {
  llvm::StringRef name = operation->getName().getStringRef();
  return name == "nodal.analog_add" || name == "nodal.analog_sub" ||
         name == "nodal.analog_mul" || name == "nodal.analog_div" ||
         name == "nodal.analog_neg" || name == "nodal.analog_compare" ||
         name == "nodal.analog_logic" || name == "nodal.analog_select";
}

void clearFoldAttributes(Operation *operation) {
  for (llvm::StringRef attribute : kFoldAttributes)
    operation->removeAttr(attribute);
}

''',
)

replace_region(
    ANALOG,
    "FailureOr<std::string> combineAnalogDimensions(",
    "FailureOr<AnalogNumericTypeInfo> getAnalogNumericTypeInfo(Type type) {",
    r'''FailureOr<std::string> combineAnalogDimensions(llvm::StringRef lhs, llvm::StringRef rhs,
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

''',
)

replace_region(
    ANALOG,
    "LogicalResult foldAnalogNumericConstants(mlir::ModuleOp module) {",
    "LogicalResult verifyAnalogQuantityErasure(mlir::ModuleOp module) {",
    r'''LogicalResult foldAnalogNumericConstants(mlir::ModuleOp module) {
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

''',
)

BACKEND = "core/compiler/lib/Backend/AnalogVerticalSlice.cpp"
replace_region(
    BACKEND,
    "std::optional<std::string> renderFoldedExpression(Operation *operation) {",
    "FailureOr<std::string> renderExpression(Value value, ModuleRenderState &state) {",
    r'''bool isFoldedExpressionCandidate(Operation *operation) {
  llvm::StringRef name = operation->getName().getStringRef();
  return name == "nodal.analog_add" || name == "nodal.analog_sub" ||
         name == "nodal.analog_mul" || name == "nodal.analog_div" ||
         name == "nodal.analog_neg" || name == "nodal.analog_compare" ||
         name == "nodal.analog_logic" || name == "nodal.analog_select";
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

''',
)

create(
    "core/compiler/test/IR/analog-numeric-select-promotion.mlir",
    r'''module {
  "nodal.module"() <{metadata = {}, sym_name = "SelectPromotion"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = true, metadata = {}, parameter_kind = "boolean", sym_name = "ENABLE", type = i1, variability = "fixed"}> : () -> ()
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %enable = "nodal.parameter_ref"() <{metadata = {}, parameter = @ENABLE}> : () -> i1
      %two = "nodal.analog_integer_literal"() <{metadata = {}, value = 2 : i64}> : () -> !nodal.quantity<"integer", "1">
      %three = "nodal.real_literal"() <{metadata = {}, value = 3.5 : f64}> : () -> !nodal.quantity<"real", "1">
      %selected = "nodal.analog_select"(%enable, %two, %three) <{metadata = {identity = "select_promotion"}}> : (i1, !nodal.quantity<"integer", "1">, !nodal.quantity<"real", "1">) -> !nodal.quantity<"real", "1">
    }) : () -> ()
  }) : () -> ()
}
''',
)

create(
    "core/compiler/test/IR/analog-numeric-backend-fold-boundary.mlir",
    r'''module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {}, sym_name = "FoldBoundary"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-input"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {identity = "p_n"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %probe = "nodal.access"(%branch) <{kind = "potential", metadata = {}}> {nodal.folded = true, nodal.folded_dimension = "1", nodal.folded_kind = "real", nodal.folded_provenance = "increment30", nodal.folded_value = 123.0 : f64} : (!nodal.branch<"electrical">) -> !nodal.quantity<"real", "1">
      "nodal.contribute"(%branch, %probe) <{kind = "potential", metadata = {}}> : (!nodal.branch<"electrical">, !nodal.quantity<"real", "1">) -> ()
    }) : () -> ()
  }) : () -> ()
}
''',
)

create(
    "core/compiler/test/IR/analog-numeric-invalid-dimension-overflow.mlir",
    r'''module {
  "nodal.unit"() <{dimension = "time^-9223372036854775808", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "ExtremeTime", symbol = "Xt"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "DimensionOverflow"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "EXTREME", type = f64, unit = @ExtremeTime, variability = "fixed"}> : () -> ()
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %one = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %extreme = "nodal.parameter_ref"() <{metadata = {}, parameter = @EXTREME}> : () -> !nodal.quantity<"real", "time^-9223372036854775808">
      %bad = "nodal.analog_div"(%one, %extreme) <{metadata = {}}> : (!nodal.quantity<"real", "1">, !nodal.quantity<"real", "time^-9223372036854775808">) -> !nodal.quantity<"real", "1">
    }) : () -> ()
  }) : () -> ()
}
''',
)

insert_before_once(
    "core/compiler/test/CMakeLists.txt",
    "add_custom_target(check-nodal-native",
    r'''add_test(
  NAME nodal.native.analog-numeric-select-promotion
  COMMAND nodalc
    "--pass-pipeline=builtin.module(nodal-fold-analog-constants,nodal-verify-analog-numeric)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-numeric-select-promotion.mlir"
)
set_tests_properties(
  nodal.native.analog-numeric-select-promotion
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]folded_kind = .real."
)

add_test(
  NAME nodal.native.analog-numeric-rejects-dimension-overflow
  COMMAND nodalc
    "--pass-pipeline=builtin.module(nodal-verify-analog-numeric)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-numeric-invalid-dimension-overflow.mlir"
)
set_tests_properties(
  nodal.native.analog-numeric-rejects-dimension-overflow
  PROPERTIES
    WILL_FAIL TRUE
)

add_test(
  NAME nodal.native.analog-numeric-backend-fold-boundary
  COMMAND nodal-translate
    --nodal-to-verilog-a
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-numeric-backend-fold-boundary.mlir"
)
set_tests_properties(
  nodal.native.analog-numeric-backend-fold-boundary
  PROPERTIES
    PASS_REGULAR_EXPRESSION "V[(]p, n[)] <[+] V[(]p, n[)]"
)

''',
)

CHECKER = "scripts/check_increment30.py"
insert_after_line(
    CHECKER,
    '    "core/compiler/test/IR/analog-numeric-typing.mlir",',
    [
        '    "core/compiler/test/IR/analog-numeric-select-promotion.mlir",',
        '    "core/compiler/test/IR/analog-numeric-backend-fold-boundary.mlir",',
        '    "core/compiler/test/IR/analog-numeric-invalid-dimension-overflow.mlir",',
    ],
)
insert_after_line(
    CHECKER,
    '    ".github/workflows/increment-30-finalize.yml",',
    [
        '    ".github/workflows/increment-30-final-review-fixes.yml",',
        '    "scripts/apply_increment30_final_review_fixes.py",',
    ],
)
insert_before_once(
    CHECKER,
    '    diagnostics = load_json(root / "core/compiler/diagnostics-v0.1.json", problems,\n',
    r'''    review_contracts = {
        "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp": (
            "__builtin_sub_overflow",
            "clearFoldAttributes",
            "conditional fold does not match the promoted result kind",
        ),
        "core/compiler/lib/Backend/AnalogVerticalSlice.cpp": (
            "isFoldedExpressionCandidate",
            "nodal.folded_provenance",
        ),
        "core/compiler/test/CMakeLists.txt": (
            "analog-numeric-select-promotion",
            "analog-numeric-backend-fold-boundary",
            "analog-numeric-rejects-dimension-overflow",
        ),
    }
    for relative, fragments in review_contracts.items():
        require(
            read(root / relative, problems, "NODAL-INC30-010"),
            fragments,
            problems,
            "NODAL-INC30-010",
            relative,
        )

''',
)

WORKFLOW = ".github/workflows/increment-30-analog-numeric-types.yml"
insert_after_line(
    WORKFLOW,
    "          grep -F 'nodal.folded_value = 6' /tmp/analog-numeric-typing.mlir",
    [
        "",
        '          "${compiler}" \\',
        '            --pass-pipeline="${pipeline}" \\',
        "            core/compiler/test/IR/analog-numeric-select-promotion.mlir \\",
        "            | tee /tmp/analog-numeric-select-promotion.mlir",
        "          grep -F 'nodal.folded_kind = \"real\"' /tmp/analog-numeric-select-promotion.mlir",
        "          grep -E 'nodal[.]folded_value = 2([.]0+e[+]00)? : f64' /tmp/analog-numeric-select-promotion.mlir",
    ],
)
insert_after_line(
    WORKFLOW,
    "          check_rejection dimension NODAL-ANALOG-DIMENSION-001",
    ["          check_rejection dimension-overflow NODAL-ANALOG-DIMENSION-001"],
)
insert_after_line(
    WORKFLOW,
    "          grep -F 'I(p, n) <+ 7;' /tmp/analog-numeric-backend.va",
    [
        "",
        '          "${translator}" \\',
        "            --nodal-to-verilog-a \\",
        "            core/compiler/test/IR/analog-numeric-backend-fold-boundary.mlir \\",
        "            | tee /tmp/analog-numeric-backend-fold-boundary.va",
        "          grep -F 'V(p, n) <+ V(p, n);' /tmp/analog-numeric-backend-fold-boundary.va",
        "          if grep -F '123' /tmp/analog-numeric-backend-fold-boundary.va; then",
        '            echo "non-foldable access accepted forged fold metadata" >&2',
        "            exit 1",
        "          fi",
    ],
)

insert_after_line(
    "docs/implementation/increment30-analog-numeric-types.md",
    "- Static zero-divisor and non-finite-fold diagnostics.",
    [
        "- Signed dimension-exponent overflow is rejected deterministically.",
        "- Conditional folding preserves promoted result kinds, including integer-to-real arms.",
        "- Fold annotations are recomputed and ignored outside the frozen pure-expression boundary.",
    ],
)

for temporary in (
    ROOT / ".github/workflows/increment-30-final-review-fixes.yml",
    ROOT / "scripts/apply_increment30_final_review_fixes.py",
):
    temporary.unlink()

print("Increment 30 final semantic review fixes applied")
