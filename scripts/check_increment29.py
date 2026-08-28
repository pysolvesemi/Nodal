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
    "core/compiler/include/nodal/Dialect/Nodal/ParameterModel.h",
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp",
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    "core/compiler/lib/Dialect/Nodal/CMakeLists.txt",
    "core/compiler/lib/Transforms/Passes.cpp",
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/Unit/CMakeLists.txt",
    "core/compiler/test/Unit/ParameterModelTest.cpp",
    "core/compiler/test/IR/parameters-units.mlir",
    "core/compiler/test/IR/parameter-rendering.mlir",
    "core/compiler/test/IR/parameters-units-invalid-unit.mlir",
    "core/compiler/test/IR/parameters-units-invalid-constraint.mlir",
    "core/compiler/test/IR/parameters-units-invalid-envelope.mlir",
    "core/compiler/test/IR/parameters-units-invalid-dynamic.mlir",
    "core/compiler/test/IR/parameters-units-invalid-override.mlir",
    "core/compiler/test/IR/parameters-units-invalid-cycle.mlir",
    "core/compiler/diagnostics-v0.1.json",
    "docs/design-gates/NodalParameterConstantUnit-DG-v1.0.md",
    "docs/implementation/increment29-parameters-units.md",
    "tests/compiler/fixtures/increment29/manifest.json",
    "tests/compiler/test_increment29.py",
    "scripts/check_increment28.py",
    "tests/compiler/test_increment28.py",
    "scripts/check_increment29.py",
    ".github/workflows/increment-29-parameters-units.yml",
    "tests/compiler/fixtures/increment30/manifest.json",
)

TEMPORARY_FILES = (
    "scripts/materialize_increment29.py",
    "scripts/materialize_increment29_core.py",
    "scripts/materialize_increment29_contract.py",
    ".github/workflows/increment-29-materialize.yml",
    "scripts/finalize_increment29.py",
    ".github/workflows/increment-29-finalize.yml",
)

OPERATIONS = [
    "nodal.unit",
    "nodal.const_literal",
    "nodal.const_parameter_ref",
    "nodal.const_expr",
    "nodal.parameter_value",
    "nodal.parameter_constraint",
    "nodal.parameter_override",
    "nodal.parameter_envelope",
    "nodal.dynamic_value",
]

CODES = [
    "NODAL-UNIT-DECL-001",
    "NODAL-UNIT-SCALE-001",
    "NODAL-UNIT-SUFFIX-001",
    "NODAL-PARAMETER-KIND-001",
    "NODAL-PARAMETER-CLASS-001",
    "NODAL-PARAMETER-UNIT-001",
    "NODAL-CONSTANT-LITERAL-001",
    "NODAL-CONSTANT-EXPR-001",
    "NODAL-CONSTANT-CYCLE-001",
    "NODAL-PARAMETER-DEFAULT-001",
    "NODAL-PARAMETER-CONSTRAINT-001",
    "NODAL-PARAMETER-OVERRIDE-001",
    "NODAL-PARAMETER-ENVELOPE-001",
    "NODAL-PARAMETER-STRUCTURAL-001",
    "NODAL-DYNAMIC-VALUE-001",
    "NODAL-BACKEND-PARAMETER-001",
    "NODAL-BACKEND-PARAMETER-002",
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
    header = read(root / "core/compiler/include/nodal/Dialect/Nodal/ParameterModel.h", problems, "NODAL-INC29-004")
    source = read(root / "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp", problems, "NODAL-INC29-005")
    ops = read(root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp", problems, "NODAL-INC29-006")
    transforms = read(root / "core/compiler/lib/Transforms/Passes.cpp", problems, "NODAL-INC29-007")
    backend = read(root / "core/compiler/lib/Backend/AnalogVerticalSlice.cpp", problems, "NODAL-INC29-008")
    test_cmake = read(root / "core/compiler/test/CMakeLists.txt", problems, "NODAL-INC29-009")
    unit_cmake = read(root / "core/compiler/test/Unit/CMakeLists.txt", problems, "NODAL-INC29-010")
    unit = read(root / "core/compiler/test/Unit/ParameterModelTest.cpp", problems, "NODAL-INC29-011")
    positive = read(root / "core/compiler/test/IR/parameters-units.mlir", problems, "NODAL-INC29-012")
    cycle = read(root / "core/compiler/test/IR/parameters-units-invalid-cycle.mlir", problems, "NODAL-INC29-012")
    rendering = read(root / "core/compiler/test/IR/parameter-rendering.mlir", problems, "NODAL-INC29-013")
    gate = read(root / "docs/design-gates/NodalParameterConstantUnit-DG-v1.0.md", problems, "NODAL-INC29-014")
    implementation = read(root / "docs/implementation/increment29-parameters-units.md", problems, "NODAL-INC29-015")
    workflow = read(root / ".github/workflows/increment-29-parameters-units.yml", problems, "NODAL-INC29-016")
    catalog = read(root / "core/compiler/diagnostics-v0.1.json", problems, "NODAL-INC29-017")
    predecessor = read(root / "scripts/check_increment28.py", problems, "NODAL-INC29-018")
    predecessor_tests = read(root / "tests/compiler/test_increment28.py", problems, "NODAL-INC29-019")
    roadmap = read(root / "docs/roadmap/nodal-development-todo.md", problems, "NODAL-INC29-020")

    require(td, (
        "OptionalAttr<StrAttr>:$parameter_kind", "OptionalAttr<StrAttr>:$classification",
        "OptionalAttr<FlatSymbolRefAttr>:$unit", "def Nodal_UnitOp",
        "def Nodal_ConstLiteralOp", "def Nodal_ConstParameterRefOp", "def Nodal_ConstExprOp",
        "def Nodal_ParameterValueOp", "def Nodal_ParameterConstraintOp",
        "def Nodal_ParameterOverrideOp", "def Nodal_ParameterEnvelopeOp", "def Nodal_DynamicValueOp",
    ), problems, "NODAL-INC29-003", "TableGen parameter model")
    require(header, (
        "verifyParameterDeclaration", "verifyParameterModel", "renderParameterConstantExpression",
        "getParameterKind", "getParameterClassification", "isStructuralParameter",
    ), problems, "NODAL-INC29-004", "parameter model API")
    require(source, (
        "evaluateParameterDefault", "normalizeForParameter", "checkConstraints", "hasBoundedRange",
        "dynamic values cannot enter constant evaluation",
        "folded default does not match canonical default_value", "lossless override disagrees with canonical binding",
        "renderParameterConstantExpression", "allowedSuffix", "splitSpelling",
        "exactInteger", "adoptParameterUnit",
        "renderParameterConstantExpression(Value value,",
        "fixed parameter cannot be overridden",
    ) + tuple(CODES[:-2]), problems, "NODAL-INC29-005", "parameter model implementation")
    require(ops, ("ParameterModel.h", "verifyParameterDeclaration"), problems, "NODAL-INC29-006", "operation integration")
    require(transforms, ("ParameterModel.h", "verifyParameterModel(module)"), problems, "NODAL-INC29-007", "semantic verifier integration")
    require(backend, (
        "renderParameterConstantExpression", "parameter real", 'kind == "integer"', "nativeType", " from ",
        " exclude ", "// unit: ", "NODAL-BACKEND-PARAMETER-001", "NODAL-BACKEND-PARAMETER-002",
        "validCanonicalCommentText", "renderIntegerAttribute",
        "orderParametersByDependency", "declarationKeyword", "localparam real",
    ), problems, "NODAL-INC29-008", "native parameter renderer")
    require(test_cmake, (
        "parameters-units-roundtrip", "parameters-units-rejects-${_fixture}",
        "parameter-lossless-rendering", "nodal-parameter-model-unit-tests",
    ), problems, "NODAL-INC29-009", "native CTest integration")
    require(unit_cmake, ("nodal-parameter-model-unit-tests", "ParameterModelTest.cpp"), problems, "NODAL-INC29-010", "unit target")
    require(unit, (
        "constant expression did not preserve native spelling", "range or exclusion constraint was not enforced",
        "structural envelope was not enforced", "dynamic value entered constant evaluation",
        "cyclic constant expression was accepted",
        "bare parameter magnitude did not inherit target unit",
        "fixed parameter dictionary binding was accepted",
        "fixed parameter explicit override was accepted",
    ), problems, "NODAL-INC29-011", "native parameter tests")
    require(positive, tuple(operation for operation in OPERATIONS if operation != "nodal.const_parameter_ref") + (
        'parameter_kind = "real"', 'parameter_kind = "integer"', 'parameter_kind = "boolean"',
        'classification = "ordinary"', 'classification = "structural"',
        'constraint_kind = "range"',
        'constraint_kind = "exclude"',
        'policy = "static_generate"', 'spelling = "1k"', 'unit = @kOhm',
    ), problems, "NODAL-INC29-012", "positive parameter fixture")
    require(cycle, ("nodal.const_parameter_ref", "parameter = @A", "parameter = @B"), problems, "NODAL-INC29-012", "parameter-reference cycle fixture")
    require(rendering, (
        'nodal.backend.profile = "verilog-a"', 'spelling = "1k"', 'spelling = "10k"',
        'constraint_kind = "range"', 'constraint_kind = "exclude"',
        'sym_name = "kOhmPretty"', 'symbol = "kΩ/V"',
        'sym_name = "A_DEP"', 'sym_name = "Z_BASE"', 'sym_name = "WIDE"',
    ), problems, "NODAL-INC29-013", "rendering fixture")
    require(gate, (
        "**Status:** Approved", "**Scope:** compiler-ir and minimal native Verilog-A/Verilog-AMS rendering",
        "**Public API:** unchanged at 0.3", "ordinary` or `structural`", "forbidden from constant expressions",
        "composite-unit algebra", "Hierarchical instance emission",
    ), problems, "NODAL-INC29-014", "design gate")
    require(implementation, (
        "Losslessly spelled unit-aware literals", "range and exclusion constraints",
        "Bounded `static_generate` structural envelopes", "without changing public API", "v0.3.", "fail-closed",
    ), problems, "NODAL-INC29-015", "implementation note")
    require(workflow, (
        "increment-29/parameters-units", "check_increment29.py", "./nodal core native",
        "parameter-rendering.mlir", "NODAL-UNIT-SCALE-001", "NODAL-PARAMETER-CONSTRAINT-001",
        "NODAL-PARAMETER-ENVELOPE-001", "NODAL-DYNAMIC-VALUE-001",
        "NODAL-PARAMETER-OVERRIDE-001", "NODAL-CONSTANT-CYCLE-001",
        "permissions:\n  contents: read", "localparam integer WIDE",
        "z_base_line", "a_dep_line",
    ), problems, "NODAL-INC29-016", "permanent workflow")
    if "contents: write" in workflow or "materialize_increment29" in workflow:
        problems.append(Problem("NODAL-INC29-016", "permanent workflow must be read-only"))
    for code in CODES:
        if code not in catalog:
            problems.append(Problem("NODAL-INC29-017", f"diagnostic catalog lacks {code}"))
    require(predecessor, (
        "increment29_open", "increment29_done", "tests/compiler/fixtures/increment29/manifest.json",
        "validated-parameter-constant-unit",
    ), problems, "NODAL-INC29-018", "Increment 28 successor handling")
    require(predecessor_tests, (
        "test_accepts_validated_increment29_successor", "validated-parameter-constant-unit",
    ), problems, "NODAL-INC29-019", "Increment 28 successor tests")

    manifest_path = root / "tests/compiler/fixtures/increment29/manifest.json"
    try:
        manifest = json.loads(read(manifest_path, problems, "NODAL-INC29-020"))
    except json.JSONDecodeError as exc:
        problems.append(Problem("NODAL-INC29-020", f"invalid manifest: {exc}"))
        manifest = {}
    if manifest.get("increment") != 29 or manifest.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC29-020", "manifest identity/public API mismatch"))
    if manifest.get("operations") != OPERATIONS:
        problems.append(Problem("NODAL-INC29-020", "manifest operation inventory mismatch"))
    if manifest.get("parameter_kinds") != ["real", "integer", "boolean"]:
        problems.append(Problem("NODAL-INC29-020", "manifest parameter-kind mismatch"))
    if manifest.get("constraint_kinds") != ["range", "exclude"]:
        problems.append(Problem("NODAL-INC29-020", "manifest constraint-kind mismatch"))
    if manifest.get("diagnostics") != CODES:
        problems.append(Problem("NODAL-INC29-020", "manifest diagnostic inventory mismatch"))

    rev = revision(roadmap)
    increment28_done = "- [x] **Increment 28 — Electrical nodes, nets, and branches**" in roadmap
    increment29_open = "- [ ] **Increment 29 — Parameters, constants, ranges, and units**" in roadmap
    increment29_done = "- [x] **Increment 29 — Parameters, constants, ranges, and units**" in roadmap
    increment30_open = "- [ ] **Increment 30 — Analog numeric types and expression typing**" in roadmap
    increment30_done = "- [x] **Increment 30 — Analog numeric types and expression typing**" in roadmap
    status = manifest.get("status")
    evidence = manifest.get("evidence", {})
    if not increment28_done:
        problems.append(Problem("NODAL-INC29-020", "Increment 28 prerequisite is not closed"))
    if status == "implemented-awaiting-evidence":
        if not increment29_open or rev < (1, 36):
            problems.append(Problem("NODAL-INC29-020", "pre-evidence state must leave Increment 29 unchecked at revision 1.36 or later"))
    elif status == "validated-parameter-constant-unit":
        if not increment29_done or rev < (1, 37):
            problems.append(Problem("NODAL-INC29-020", "validated state must close Increment 29 at revision 1.37 or later"))
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(evidence.get(field), int):
                problems.append(Problem("NODAL-INC29-020", f"validated manifest lacks integer evidence field: {field}"))
    else:
        problems.append(Problem("NODAL-INC29-020", f"unexpected manifest status: {status!r}"))
    increment30_path = root / "tests/compiler/fixtures/increment30/manifest.json"
    try:
        increment30 = json.loads(read(increment30_path, problems, "NODAL-INC29-020"))
    except json.JSONDecodeError as exc:
        problems.append(
            Problem("NODAL-INC29-020", f"invalid Increment 30 manifest: {exc}")
        )
        increment30 = {}
    increment30_status = increment30.get("status")
    increment30_evidence = increment30.get("evidence", {})
    if increment30.get("increment") != 30 or increment30.get("public_api") != "0.3":
        problems.append(
            Problem("NODAL-INC29-020", "Increment 30 successor identity mismatch")
        )
    if increment30_open == increment30_done:
        problems.append(
            Problem("NODAL-INC29-020", "Increment 30 roadmap state is missing or ambiguous")
        )
    elif increment30_open:
        if increment30_status not in {
            "implementation-started",
            "implemented-awaiting-evidence",
        }:
            problems.append(
                Problem("NODAL-INC29-020", "Increment 30 pre-evidence state is inconsistent")
            )
    elif increment30_status != "validated-analog-numeric-typing" or rev < (1, 38):
        problems.append(
            Problem("NODAL-INC29-020", "validated Increment 30 state is inconsistent")
        )
    else:
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(increment30_evidence.get(field), int):
                problems.append(
                    Problem(
                        "NODAL-INC29-020",
                        f"Increment 30 lacks evidence field: {field}",
                    )
                )
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
    print("Increment 29 parameter, constant, range, and unit check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
