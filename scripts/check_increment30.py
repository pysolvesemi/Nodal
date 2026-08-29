#!/usr/bin/env python3
"""Validate the Increment 30 analog numeric typing implementation contract."""

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
    "docs/design-gates/NodalAnalogNumericTyping-DG-v1.0.md",
    "docs/implementation/increment30-analog-numeric-types.md",
    "tests/compiler/fixtures/increment30/manifest.json",
    "tests/compiler/fixtures/increment30/analog-numeric-surface.json",
    "scripts/check_increment30.py",
    "tests/compiler/test_increment30.py",
    ".github/workflows/increment-30-analog-numeric-types.yml",
    "docs/roadmap/nodal-development-todo.md",
    "tests/compiler/fixtures/increment29/manifest.json",
    "tests/compiler/fixtures/increment31/manifest.json",
    "core/compiler/include/nodal/Dialect/Nodal/AnalogNumeric.h",
    "core/compiler/include/nodal/Diagnostics/DiagnosticSupport.h",
    "core/compiler/include/nodal/Diagnostics/DiagnosticMapping.h",
    "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp",
    "core/compiler/lib/Dialect/Nodal/CMakeLists.txt",
    "core/compiler/lib/Diagnostics/DiagnosticMapping.cpp",
    "core/compiler/lib/Diagnostics/CMakeLists.txt",
    "core/compiler/lib/Support/DiagnosticSupport.cpp",
    "core/compiler/lib/Support/CMakeLists.txt",
    "core/compiler/include/nodal/Dialect/Nodal/NodalTypes.td",
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "core/compiler/lib/Dialect/Nodal/NodalTypes.cpp",
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    "core/compiler/lib/Transforms/Passes.cpp",
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    "core/compiler/diagnostics-v0.1.json",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/IR/analog-numeric-typing.mlir",
    "core/compiler/test/IR/analog-numeric-select-promotion.mlir",
    "core/compiler/test/IR/analog-numeric-parameter-scale.mlir",
    "core/compiler/test/IR/analog-numeric-backend-fold-boundary.mlir",
    "core/compiler/test/IR/analog-numeric-invalid-dimension-overflow.mlir",
    "core/compiler/test/IR/analog-numeric-backend.mlir",
    "core/compiler/test/IR/analog-numeric-invalid-type.mlir",
    "core/compiler/test/IR/analog-numeric-invalid-promotion.mlir",
    "core/compiler/test/IR/analog-numeric-invalid-dimension.mlir",
    "core/compiler/test/IR/analog-numeric-invalid-compare.mlir",
    "core/compiler/test/IR/analog-numeric-invalid-logic.mlir",
    "core/compiler/test/IR/analog-numeric-invalid-select.mlir",
    "core/compiler/test/IR/analog-numeric-invalid-divide.mlir",
)

TEMPORARY_FILES = (
    "scripts/materialize_increment30.py",
    "scripts/finalize_increment30.py",
    ".github/workflows/increment-30-materialize.yml",
    ".github/workflows/increment-30-finalize.yml",
    ".github/workflows/increment-30-final-review-fixes.yml",
    ".github/workflows/increment-30-review-fixes.yml",
    ".github/workflows/increment-30-temporary-artifact-guard.yml",
    ".github/workflows/increment-30-parameter-scale-fix.yml",
    "scripts/apply_increment30_final_review_fixes.py",
    "scripts/apply_increment30_parameter_scale_fix.py",
)

OPERATIONS = [
    "nodal.real_literal",
    "nodal.analog_integer_literal",
    "nodal.parameter_ref",
    "nodal.analog_add",
    "nodal.analog_sub",
    "nodal.analog_mul",
    "nodal.analog_div",
    "nodal.analog_neg",
    "nodal.analog_compare",
    "nodal.analog_logic",
    "nodal.analog_select",
    "nodal.analog_ddt",
    "nodal.access",
    "nodal.contribute",
]

PLANNED_DIAGNOSTICS = [
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


def read(path: Path, problems: list[Problem], code: str) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        problems.append(Problem(code, f"cannot read {path}: {exc}"))
        return ""


def require(
    text: str,
    fragments: tuple[str, ...],
    problems: list[Problem],
    code: str,
    subject: str,
) -> None:
    for fragment in fragments:
        if fragment not in text:
            problems.append(Problem(code, f"{subject} lacks: {fragment}"))


def revision(text: str) -> tuple[int, ...]:
    values = re.findall(r"^\*\*Revision:\*\* ([0-9.]+)$", text, re.MULTILINE)
    if len(values) != 1:
        return ()
    return tuple(int(part) for part in values[0].split("."))


def load_json(path: Path, problems: list[Problem], code: str) -> dict:
    try:
        value = json.loads(read(path, problems, code))
    except json.JSONDecodeError as exc:
        problems.append(Problem(code, f"invalid JSON in {path}: {exc}"))
        return {}
    if not isinstance(value, dict):
        problems.append(Problem(code, f"{path} must contain a JSON object"))
        return {}
    return value


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []

    for relative in EXPECTED_FILES:
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-INC30-001", f"missing file: {relative}"))
    for relative in TEMPORARY_FILES:
        if (root / relative).exists():
            problems.append(Problem("NODAL-INC30-002", f"temporary file remains: {relative}"))

    gate = read(
        root / "docs/design-gates/NodalAnalogNumericTyping-DG-v1.0.md",
        problems,
        "NODAL-INC30-003",
    )
    implementation = read(
        root / "docs/implementation/increment30-analog-numeric-types.md",
        problems,
        "NODAL-INC30-004",
    )
    workflow = read(
        root / ".github/workflows/increment-30-analog-numeric-types.yml",
        problems,
        "NODAL-INC30-005",
    )
    roadmap = read(
        root / "docs/roadmap/nodal-development-todo.md",
        problems,
        "NODAL-INC30-006",
    )
    manifest = load_json(
        root / "tests/compiler/fixtures/increment30/manifest.json",
        problems,
        "NODAL-INC30-007",
    )
    surface = load_json(
        root / "tests/compiler/fixtures/increment30/analog-numeric-surface.json",
        problems,
        "NODAL-INC30-008",
    )
    predecessor = load_json(
        root / "tests/compiler/fixtures/increment29/manifest.json",
        problems,
        "NODAL-INC30-009",
    )
    successor = load_json(
        root / "tests/compiler/fixtures/increment31/manifest.json",
        problems,
        "NODAL-INC30-006",
    )

    require(
        gate,
        (
            "**Status:** Approved",
            "**Public API:** unchanged at 0.3",
            "!nodal.quantity<integer|real, canonical-dimension>",
            "The only implicit numeric promotion is `integer -> real`.",
            "Boolean values are never quantities",
            "Multiplication adds exponent vectors. Division subtracts them.",
            "logical `and`, `or`, `xor`, and `not` accept only `i1`",
            "A backend may erase `!nodal.quantity` only after",
            "legacy `f64` analog value remains accepted",
        ),
        problems,
        "NODAL-INC30-003",
        "analog numeric design gate",
    )
    for code in PLANNED_DIAGNOSTICS:
        if code not in gate and code not in json.dumps(manifest, sort_keys=True):
            problems.append(Problem("NODAL-INC30-003", f"planned diagnostic is absent: {code}"))

    require(
        implementation,
        (
            "Increment 30 is implemented",
            "fully validated Increment 29",
            "Deterministic integer-to-real promotion",
            "Canonical exponent algebra",
            "Boolean-only logical operations",
            "public API remains v0.3",
            "roadmap item stays unchecked",
        ),
        problems,
        "NODAL-INC30-004",
        "implementation note",
    )

    require(
        workflow,
        (
            "increment-30/analog-numeric-types",
            "check_increment30.py",
            "test_increment30.py",
            "./nodal core native",
            "permissions:\n  contents: read",
            "implemented-awaiting-evidence",
            "tests/compiler/fixtures/increment30/manifest.json",
            "nodal-verify-analog-numeric",
            "nodal-fold-analog-constants",
            "analog-numeric-typing.mlir",
            "analog-numeric-select-promotion.mlir",
            "analog-numeric-parameter-scale.mlir",
            "analog-numeric-backend.mlir",
        ),
        problems,
        "NODAL-INC30-005",
        "Increment 30 workflow",
    )
    if "contents: write" in workflow or "materialize_increment30" in workflow:
        problems.append(Problem("NODAL-INC30-005", "workflow must remain read-only"))

    if manifest.get("increment") != 30 or manifest.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC30-007", "manifest identity/public API mismatch"))
    if manifest.get("status") == "implementation-started":
        problems.append(Problem("NODAL-INC30-007", "native implementation has not been materialized"))
    if manifest.get("status") not in {
        "implementation-started",
        "implemented-awaiting-evidence",
        "validated-analog-numeric-typing",
    }:
        problems.append(Problem("NODAL-INC30-007", "unsupported Increment 30 status"))
    if manifest.get("branch") != "increment/30-analog-numeric-types":
        problems.append(Problem("NODAL-INC30-007", "manifest branch mismatch"))
    if manifest.get("operations") != OPERATIONS:
        problems.append(Problem("NODAL-INC30-007", "manifest operation inventory mismatch"))
    if manifest.get("planned_diagnostics") != PLANNED_DIAGNOSTICS:
        problems.append(Problem("NODAL-INC30-007", "manifest diagnostic inventory mismatch"))

    quantity = manifest.get("quantity_type", {})
    if quantity.get("spelling") != "!nodal.quantity<kind, dimension>":
        problems.append(Problem("NODAL-INC30-007", "quantity type spelling mismatch"))
    if quantity.get("numeric_kinds") != ["integer", "real"]:
        problems.append(Problem("NODAL-INC30-007", "numeric kind inventory mismatch"))
    if quantity.get("boolean_type") != "i1":
        problems.append(Problem("NODAL-INC30-007", "Boolean result type must be i1"))
    if quantity.get("legacy_f64") != "real-dimensionless":
        problems.append(Problem("NODAL-INC30-007", "legacy f64 compatibility is missing"))

    promotion = manifest.get("promotion", {})
    if promotion.get("integer_real") != "real" or promotion.get("real_integer") != "real":
        problems.append(Problem("NODAL-INC30-007", "integer-to-real promotion is incomplete"))
    if promotion.get("real_to_integer") != "explicit-only":
        problems.append(Problem("NODAL-INC30-007", "implicit real-to-integer narrowing is allowed"))
    if promotion.get("boolean_numeric") != "forbidden":
        problems.append(Problem("NODAL-INC30-007", "Boolean numeric promotion is allowed"))

    dimensions = manifest.get("dimensions", {})
    if dimensions.get("mul") != "add-exponents" or dimensions.get("div") != "subtract-exponents":
        problems.append(Problem("NODAL-INC30-007", "canonical dimension algebra is incomplete"))
    if manifest.get("logical_result") != "i1":
        problems.append(Problem("NODAL-INC30-007", "logical/comparison result must be i1"))

    folding = manifest.get("folding", {})
    never = folding.get("never", [])
    for required in ("dynamic-value", "access", "ddt-or-stateful-operator", "contribution-or-equation"):
        if required not in never:
            problems.append(Problem("NODAL-INC30-007", f"folding boundary lacks {required}"))

    if surface.get("schema") != "nodal-analog-numeric-typing/v1":
        problems.append(Problem("NODAL-INC30-008", "surface schema mismatch"))
    if surface.get("quantityType") != "!nodal.quantity<kind, dimension>":
        problems.append(Problem("NODAL-INC30-008", "surface quantity type mismatch"))
    if surface.get("promotionMatrix", {}).get("integer,real") != "real":
        problems.append(Problem("NODAL-INC30-008", "surface promotion matrix is incomplete"))
    if surface.get("logical", {}).get("numericTruthiness") is not False:
        problems.append(Problem("NODAL-INC30-008", "surface permits numeric truthiness"))
    if surface.get("arithmetic", {}).get("mul", {}).get("dimensions") != "add-exponents":
        problems.append(Problem("NODAL-INC30-008", "surface multiplication dimension rule mismatch"))
    if surface.get("folding", {}).get("requiresPureConstantGraph") is not True:
        problems.append(Problem("NODAL-INC30-008", "surface folding purity boundary is missing"))
    if surface.get("folding", {}).get("parameterUnitScale") != "canonicalize-before-fold":
        problems.append(Problem("NODAL-INC30-008", "surface parameter unit-scale rule is missing"))
    if folding.get("parameter_unit_scale") != "canonicalize-before-fold":
        problems.append(Problem("NODAL-INC30-007", "manifest parameter unit-scale rule is missing"))

    native_contracts = {
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
            "nodal.folded_provenance", "NODAL-ANALOG-DIVIDE-001",
            "verifyAnalogNumericModel(mlir::ModuleOp module)",
            "foldAnalogNumericConstants(mlir::ModuleOp module)",
            "verifyAnalogQuantityErasure(mlir::ModuleOp module)"),
        "core/compiler/include/nodal/Diagnostics/DiagnosticSupport.h": (
            "DiagnosticContext", "emitMappedFailure", "emitMappedFailureForPath"),
        "core/compiler/lib/Support/DiagnosticSupport.cpp": (
            "collectDiagnosticContext", "emitMappedFailure", "sourceMapContext"),
        "core/compiler/lib/Support/CMakeLists.txt": (
            "DiagnosticSupport.cpp", "MLIRIR"),
        "core/compiler/lib/Dialect/Nodal/CMakeLists.txt": ("NodalSupport",),
        "core/compiler/lib/Diagnostics/CMakeLists.txt": (
            "NodalDialect", "NodalSupport"),
        "core/compiler/lib/Transforms/Passes.cpp": (
            "nodal-fold-analog-constants", "nodal-verify-analog-numeric",
            "createFoldAnalogConstantsPass", "createVerifyAnalogNumericPass"),
        "core/compiler/lib/Backend/AnalogVerticalSlice.cpp": (
            "verifyAnalogQuantityErasure", "nodal.analog_select",
            "renderFoldedExpression"),
        "core/compiler/test/CMakeLists.txt": (
            "nodal.native.analog-numeric-typing", "analog-numeric-rejects"),
    }
    for relative, fragments in native_contracts.items():
        require(read(root / relative, problems, "NODAL-INC30-010"), fragments, problems,
                "NODAL-INC30-010", relative)

    review_contracts = {
        "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp": (
            "__builtin_sub_overflow",
            "clearFoldAttributes",
            "FailureOr<double> parameterScale(Operation *parameter)",
            "fixed real parameter scale produced a non-finite result",
            "conditional fold does not match the promoted result kind",
        ),
        "core/compiler/lib/Backend/AnalogVerticalSlice.cpp": (
            "isFoldedExpressionCandidate",
            "nodal.folded_provenance",
        ),
        "core/compiler/test/CMakeLists.txt": (
            "analog-numeric-select-promotion",
            "analog-numeric-parameter-scale",
            "analog-numeric-backend-fold-boundary",
            "analog-numeric-rejects-dimension-overflow",
        ),
        "core/compiler/test/IR/analog-numeric-parameter-scale.mlir": (
            "unit = @kOhm",
            "parameter = @R",
            "identity = \"parameter_scale\"",
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

    diagnostics = load_json(root / "core/compiler/diagnostics-v0.1.json", problems,
                            "NODAL-INC30-011")
    catalog = diagnostics.get("families", {}).get("analog-numeric-typing", [])
    for code in PLANNED_DIAGNOSTICS:
        if code not in catalog:
            problems.append(Problem("NODAL-INC30-011", f"diagnostic catalog lacks: {code}"))

    if predecessor.get("status") != "validated-parameter-constant-unit":
        problems.append(Problem("NODAL-INC30-009", "Increment 29 prerequisite is not validated"))

    rev = revision(roadmap)
    increment29_done = "- [x] **Increment 29 — Parameters, constants, ranges, and units**" in roadmap
    increment30_open = "- [ ] **Increment 30 — Analog numeric types and expression typing**" in roadmap
    increment30_done = "- [x] **Increment 30 — Analog numeric types and expression typing**" in roadmap
    increment31_open = "- [ ] **Increment 31 — Potential and flow access functions**" in roadmap
    increment31_done = "- [x] **Increment 31 — Potential and flow access functions**" in roadmap
    status = manifest.get("status")
    evidence = manifest.get("evidence", {})
    successor_status = successor.get("status")
    successor_evidence = successor.get("evidence", {})

    if not increment29_done or rev < (1, 37):
        problems.append(Problem("NODAL-INC30-006", "validated Increment 29 baseline is absent"))
    if status in {"implementation-started", "implemented-awaiting-evidence"}:
        if not increment30_open or increment30_done:
            problems.append(Problem("NODAL-INC30-006", "pre-evidence state must leave Increment 30 unchecked"))
    elif status == "validated-analog-numeric-typing":
        if not increment30_done or rev < (1, 38):
            problems.append(Problem("NODAL-INC30-006", "validated state must close Increment 30 at revision 1.38 or later"))
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(evidence.get(field), int):
                problems.append(Problem("NODAL-INC30-006", f"validated manifest lacks integer evidence field: {field}"))

    if successor.get("increment") != 31 or successor.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC30-006", "Increment 31 successor identity mismatch"))
    if increment31_open == increment31_done:
        problems.append(Problem("NODAL-INC30-006", "Increment 31 roadmap state is missing or ambiguous"))
    elif successor_status in {"implementation-started", "implemented-awaiting-evidence"}:
        if not increment31_open:
            problems.append(Problem("NODAL-INC30-006", "Increment 31 pre-evidence state is inconsistent"))
    elif successor_status == "validated-potential-flow-access":
        if not increment31_done or rev < (1, 41):
            problems.append(Problem("NODAL-INC30-006", "validated Increment 31 state is inconsistent"))
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(successor_evidence.get(field), int):
                problems.append(
                    Problem(
                        "NODAL-INC30-006",
                        f"validated Increment 31 lacks integer evidence field: {field}",
                    )
                )
        for field in ("implementation_head", "merge_commit"):
            value = successor_evidence.get(field)
            if not isinstance(value, str) or len(value) != 40:
                problems.append(
                    Problem(
                        "NODAL-INC30-006",
                        f"validated Increment 31 lacks commit evidence field: {field}",
                    )
                )
    else:
        problems.append(Problem("NODAL-INC30-006", "unsupported Increment 31 successor status"))

    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"Increment 30 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 30 analog numeric typing implementation contract passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
