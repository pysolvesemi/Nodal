#!/usr/bin/env python3
"""Validate Increment 24: minimal analog expression and contribution IR."""

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
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    "core/compiler/lib/Transforms/Passes.cpp",
    "core/compiler/test/IR/analog-expression-rc.mlir",
    "core/compiler/test/IR/analog-expression-invalid-parameter.mlir",
    "core/compiler/test/IR/analog-expression-invalid-contribution.mlir",
    "core/compiler/test/Unit/AnalogExpressionTest.cpp",
    "docs/design-gates/NodalMinimalAnalogIr-DG-v1.0.md",
    "docs/implementation/increment24-minimal-analog-ir.md",
    "tests/compiler/fixtures/increment24/manifest.json",
    "tests/compiler/test_increment24.py",
    "scripts/check_increment24.py",
    ".github/workflows/increment-24-minimal-analog-ir.yml",
)

TEMPORARY_FILES = (
    "scripts/materialize_increment24.py",
    "scripts/finalize_increment24.py",
    ".github/workflows/increment-24-materialize.yml",
    ".github/workflows/increment-24-finalize.yml",
    ".github/workflows/increment-24-supervisor.yml",
)

OPS = (
    "nodal.analog",
    "nodal.real_literal",
    "nodal.parameter_ref",
    "nodal.analog_add",
    "nodal.analog_sub",
    "nodal.analog_mul",
    "nodal.analog_div",
    "nodal.analog_ddt",
    "nodal.contribute",
)

CODES = (
    "NODAL-ANALOG-REGION-001",
    "NODAL-ANALOG-REGION-002",
    "NODAL-ANALOG-LITERAL-001",
    "NODAL-ANALOG-PARAMETER-001",
    "NODAL-ANALOG-PARAMETER-002",
    "NODAL-ANALOG-ARITHMETIC-001",
    "NODAL-ANALOG-DDT-001",
    "NODAL-ANALOG-ACCESS-001",
    "NODAL-ANALOG-ACCESS-002",
    "NODAL-ANALOG-CONTRIBUTION-001",
)


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
            problems.append(Problem("NODAL-INC24-001", f"missing file: {relative}"))
    for relative in TEMPORARY_FILES:
        if (root / relative).exists():
            problems.append(Problem("NODAL-INC24-002", f"temporary closure file remains: {relative}"))

    td = read(root / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td", problems, "NODAL-INC24-003")
    cpp = read(root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp", problems, "NODAL-INC24-004")
    passes = read(root / "core/compiler/lib/Transforms/Passes.cpp", problems, "NODAL-INC24-005")
    fixture = read(root / "core/compiler/test/IR/analog-expression-rc.mlir", problems, "NODAL-INC24-006")
    unit = read(root / "core/compiler/test/Unit/AnalogExpressionTest.cpp", problems, "NODAL-INC24-007")
    gate = read(root / "docs/design-gates/NodalMinimalAnalogIr-DG-v1.0.md", problems, "NODAL-INC24-008")
    workflow = read(root / ".github/workflows/increment-24-minimal-analog-ir.yml", problems, "NODAL-INC24-009")
    roadmap = read(root / "docs/roadmap/nodal-development-todo.md", problems, "NODAL-INC24-010")
    catalog = read(root / "core/compiler/diagnostics-v0.1.json", problems, "NODAL-INC24-011")

    require(td, tuple(f'def Nodal_{name}' for name in (
        "AnalogOp", "RealLiteralOp", "ParameterRefOp", "AnalogAddOp", "AnalogSubOp",
        "AnalogMulOp", "AnalogDivOp", "AnalogDdtOp", "ContributeOp"
    )) + ('HasParent<"AnalogOp">', 'F64Attr:$value', 'Nodal_BranchType:$branch'), problems, "NODAL-INC24-003", "TableGen analog IR")
    require(cpp, tuple(f"LogicalResult nodal::{name}::verify()" for name in (
        "AnalogOp", "RealLiteralOp", "ParameterRefOp", "AnalogAddOp", "AnalogSubOp",
        "AnalogMulOp", "AnalogDivOp", "AnalogDdtOp", "ContributeOp"
    )) + CODES, problems, "NODAL-INC24-004", "analog verifiers")
    require(passes, OPS, problems, "NODAL-INC24-005", "semantic pipeline analog classification")
    require(fixture, OPS + ("I = V/R + C*ddt(V)", 'nodal.target.profile = "analog"'), problems, "NODAL-INC24-006", "RC equation fixture")
    require(unit, ("typed analog region/contribution inventory is incorrect", "unknown analog parameter reference was accepted", "invalid analog contribution kind was accepted"), problems, "NODAL-INC24-007", "native analog unit test")
    require(gate, ("**Status:** Approved", "**Scope:** compiler-ir", "**Public API:** unchanged at 0.3", "Increment 25"), problems, "NODAL-INC24-008", "design gate")
    require(workflow, ("increment-24/minimal-analog-ir", "check_increment24.py", "analog-expression-rc.mlir", "NODAL-ANALOG-PARAMETER-001", "NODAL-ANALOG-CONTRIBUTION-001", "./nodal core native", "permissions:\n  contents: read"), problems, "NODAL-INC24-009", "permanent workflow")
    if "contents: write" in workflow or "materialize_increment24" in workflow:
        problems.append(Problem("NODAL-INC24-009", "permanent Increment 24 workflow must be read-only"))
    for code in CODES:
        if code not in catalog:
            problems.append(Problem("NODAL-INC24-011", f"diagnostic catalog lacks code: {code}"))

    manifest_path = root / "tests/compiler/fixtures/increment24/manifest.json"
    try:
        manifest = json.loads(read(manifest_path, problems, "NODAL-INC24-010"))
    except json.JSONDecodeError as exc:
        problems.append(Problem("NODAL-INC24-010", f"invalid manifest: {exc}"))
        manifest = {}
    if manifest.get("increment") != 24 or manifest.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC24-010", "manifest identity/public API mismatch"))
    if manifest.get("expression_operations") != list(OPS[1:8]):
        problems.append(Problem("NODAL-INC24-010", "manifest expression inventory mismatch"))
    if manifest.get("contribution_operation") != "nodal.contribute":
        problems.append(Problem("NODAL-INC24-010", "manifest contribution identity mismatch"))

    rev = revision(roadmap)
    inc23 = "- [x] **Increment 23 — Backend framework and capability profiles**" in roadmap
    inc24_open = "- [ ] **Increment 24 — Minimal analog expression and contribution IR**" in roadmap
    inc24_done = "- [x] **Increment 24 — Minimal analog expression and contribution IR**" in roadmap
    inc25_open = "- [ ] **Increment 25 — RC filter end-to-end vertical slice**" in roadmap
    status = manifest.get("status")
    evidence = manifest.get("evidence", {})
    if not inc23:
        problems.append(Problem("NODAL-INC24-010", "Increment 23 prerequisite is not closed"))
    if status == "implemented-awaiting-evidence":
        if not inc24_open or rev < (1, 27):
            problems.append(Problem("NODAL-INC24-010", "pre-evidence state must leave Increment 24 unchecked at revision 1.27 or later"))
    elif status == "validated-minimal-analog-ir":
        if not inc24_done or rev < (1, 28):
            problems.append(Problem("NODAL-INC24-010", "validated state must close Increment 24 at revision 1.28 or later"))
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(evidence.get(field), int):
                problems.append(Problem("NODAL-INC24-010", f"validated manifest lacks integer evidence field: {field}"))
    else:
        problems.append(Problem("NODAL-INC24-010", f"unexpected manifest status: {status!r}"))
    if not inc25_open:
        problems.append(Problem("NODAL-INC24-010", "Increment 25 must remain unchecked"))
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"Increment 24 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 24 minimal analog IR check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
