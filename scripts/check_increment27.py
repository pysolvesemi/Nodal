#!/usr/bin/env python3
"""Validate Increment 27: natures and disciplines."""

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
    "core/compiler/include/nodal/Dialect/Nodal/NatureDiscipline.h",
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "core/compiler/lib/Dialect/Nodal/NatureDiscipline.cpp",
    "core/compiler/lib/Dialect/Nodal/CMakeLists.txt",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/Unit/CMakeLists.txt",
    "core/compiler/test/Unit/NatureDisciplineTest.cpp",
    "core/compiler/test/IR/natures-disciplines.mlir",
    "core/compiler/test/IR/natures-disciplines-invalid-tolerance.mlir",
    "core/compiler/test/IR/natures-disciplines-invalid-association.mlir",
    "core/compiler/test/IR/natures-disciplines-invalid-import-cycle.mlir",
    "core/compiler/diagnostics-v0.1.json",
    "docs/design-gates/NodalNatureDiscipline-DG-v1.0.md",
    "docs/implementation/increment27-natures-disciplines.md",
    "tests/compiler/fixtures/increment27/manifest.json",
    "tests/compiler/test_increment27.py",
    "scripts/check_increment26.py",
    "tests/compiler/test_increment26.py",
    "scripts/check_increment27.py",
    ".github/workflows/increment-27-natures-disciplines.yml",
)

TEMPORARY_FILES = (
    "scripts/materialize_increment27.py",
    "scripts/finalize_increment27.py",
    "scripts/close_increment27.py",
    ".github/workflows/increment-27-materialize.yml",
    ".github/workflows/increment-27-finalize.yml",
    ".github/workflows/increment-27-close.yml",
)

OPERATIONS = [
    "nodal.nature",
    "nodal.nature_import",
    "nodal.discipline",
    "nodal.discipline_import",
]

CODES = [
    "NODAL-NATURE-DECL-001",
    "NODAL-NATURE-UNITS-001",
    "NODAL-NATURE-ACCESS-001",
    "NODAL-NATURE-ACCESS-002",
    "NODAL-NATURE-TOLERANCE-001",
    "NODAL-NATURE-IMPORT-001",
    "NODAL-NATURE-IMPORT-002",
    "NODAL-DISCIPLINE-DECL-001",
    "NODAL-DISCIPLINE-DOMAIN-001",
    "NODAL-DISCIPLINE-POTENTIAL-001",
    "NODAL-DISCIPLINE-FLOW-001",
    "NODAL-DISCIPLINE-IMPORT-001",
    "NODAL-DISCIPLINE-IMPORT-002",
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
            problems.append(Problem("NODAL-INC27-001", f"missing file: {relative}"))
    for relative in TEMPORARY_FILES:
        if (root / relative).exists():
            problems.append(Problem("NODAL-INC27-002", f"temporary file remains: {relative}"))

    td = read(root / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td", problems, "NODAL-INC27-003")
    header = read(root / "core/compiler/include/nodal/Dialect/Nodal/NatureDiscipline.h", problems, "NODAL-INC27-004")
    source = read(root / "core/compiler/lib/Dialect/Nodal/NatureDiscipline.cpp", problems, "NODAL-INC27-005")
    dialect_cmake = read(root / "core/compiler/lib/Dialect/Nodal/CMakeLists.txt", problems, "NODAL-INC27-006")
    test_cmake = read(root / "core/compiler/test/CMakeLists.txt", problems, "NODAL-INC27-007")
    unit_cmake = read(root / "core/compiler/test/Unit/CMakeLists.txt", problems, "NODAL-INC27-008")
    unit = read(root / "core/compiler/test/Unit/NatureDisciplineTest.cpp", problems, "NODAL-INC27-009")
    positive = read(root / "core/compiler/test/IR/natures-disciplines.mlir", problems, "NODAL-INC27-010")
    gate = read(root / "docs/design-gates/NodalNatureDiscipline-DG-v1.0.md", problems, "NODAL-INC27-011")
    implementation = read(root / "docs/implementation/increment27-natures-disciplines.md", problems, "NODAL-INC27-012")
    workflow = read(root / ".github/workflows/increment-27-natures-disciplines.yml", problems, "NODAL-INC27-013")
    catalog = read(root / "core/compiler/diagnostics-v0.1.json", problems, "NODAL-INC27-014")
    predecessor = read(root / "scripts/check_increment26.py", problems, "NODAL-INC27-015")
    roadmap = read(root / "docs/roadmap/nodal-development-todo.md", problems, "NODAL-INC27-016")

    require(td, (
        "def Nodal_NatureOp", "StrAttr:$units", "StrAttr:$access", "F64Attr:$abstol",
        "def Nodal_NatureImportOp", "def Nodal_DisciplineOp", "StrAttr:$domain",
        "FlatSymbolRefAttr:$potential", "OptionalAttr<FlatSymbolRefAttr>:$flow",
        "def Nodal_DisciplineImportOp",
    ), problems, "NODAL-INC27-003", "TableGen declarations")
    require(header, (
        "resolveNatureDeclaration", "resolveDisciplineDeclaration",
        "areDisciplinesCompatible",
    ), problems, "NODAL-INC27-004", "compatibility API")
    require(source, (
        "resolveDeclaration", "isSha256", "NatureOp::verify()",
        "NatureImportOp::verify()", "DisciplineOp::verify()",
        "DisciplineImportOp::verify()", "areDisciplinesCompatible",
        "canonicalLeftPotential", "canonicalLeftFlow",
    ) + tuple(CODES), problems, "NODAL-INC27-005", "nature/discipline implementation")
    require(dialect_cmake, ("NatureDiscipline.cpp",), problems, "NODAL-INC27-006", "dialect build")
    require(test_cmake, (
        "natures-disciplines-roundtrip", "natures-disciplines-generic",
        "natures-disciplines-rejects-${_fixture}",
        "nodal-nature-discipline-unit-tests",
    ), problems, "NODAL-INC27-007", "native CTest integration")
    require(unit_cmake, ("nodal-nature-discipline-unit-tests", "NatureDisciplineTest.cpp"), problems, "NODAL-INC27-008", "unit target")
    require(unit, (
        "canonical discipline compatibility is incorrect",
        "non-positive nature tolerance was accepted",
        "unknown flow nature was accepted", "cyclic nature import was accepted",
    ), problems, "NODAL-INC27-009", "native compatibility tests")
    require(positive, tuple(OPERATIONS) + (
        'domain = "continuous"', "flow = @Current", "potential = @VoltageImported",
        "definition_hash", 'nodal.target.profile = "analog"',
    ), problems, "NODAL-INC27-010", "positive fixture")
    require(gate, (
        "**Status:** Approved", "**Scope:** compiler-ir", "**Public API:** unchanged at 0.3",
        "Nature identity is nominal", "canonical potential natures match",
        "Increment 28", "Increment 31", "fail-closed",
    ), problems, "NODAL-INC27-011", "design gate")
    require(implementation, tuple(OPERATIONS) + (
        "Compatibility compares canonical domain", "public API v0.3 unchanged",
        "remains fail-closed",
    ), problems, "NODAL-INC27-012", "implementation note")
    require(workflow, (
        "increment-27/natures-disciplines", "check_increment27.py",
        "./nodal core native", "natures-disciplines.mlir",
        "NODAL-NATURE-TOLERANCE-001", "NODAL-DISCIPLINE-FLOW-001",
        "NODAL-NATURE-IMPORT-001", "permissions:\n  contents: read",
    ), problems, "NODAL-INC27-013", "permanent workflow")
    if "contents: write" in workflow or "materialize_increment27" in workflow:
        problems.append(Problem("NODAL-INC27-013", "permanent workflow must be read-only"))
    for code in CODES:
        if code not in catalog:
            problems.append(Problem("NODAL-INC27-014", f"diagnostic catalog lacks {code}"))
    require(predecessor, (
        "increment27_done", "validated-natures-disciplines",
        "tests/compiler/fixtures/increment27/manifest.json",
    ), problems, "NODAL-INC27-015", "Increment 26 successor handling")

    manifest_path = root / "tests/compiler/fixtures/increment27/manifest.json"
    try:
        manifest = json.loads(read(manifest_path, problems, "NODAL-INC27-016"))
    except json.JSONDecodeError as exc:
        problems.append(Problem("NODAL-INC27-016", f"invalid manifest: {exc}"))
        manifest = {}
    if manifest.get("increment") != 27 or manifest.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC27-016", "manifest identity/public API mismatch"))
    if manifest.get("operations") != OPERATIONS:
        problems.append(Problem("NODAL-INC27-016", "manifest operation inventory mismatch"))
    if manifest.get("nature_fields") != ["units", "access", "abstol"]:
        problems.append(Problem("NODAL-INC27-016", "manifest nature fields mismatch"))
    if manifest.get("discipline_fields") != ["domain", "potential", "flow"]:
        problems.append(Problem("NODAL-INC27-016", "manifest discipline fields mismatch"))
    if manifest.get("diagnostics") != CODES:
        problems.append(Problem("NODAL-INC27-016", "manifest diagnostics mismatch"))

    rev = revision(roadmap)
    increment26_done = "- [x] **Increment 26 — Deterministic output and reproducibility contract**" in roadmap
    increment27_open = "- [ ] **Increment 27 — Natures and disciplines**" in roadmap
    increment27_done = "- [x] **Increment 27 — Natures and disciplines**" in roadmap
    increment28_open = "- [ ] **Increment 28 — Electrical nodes, nets, and branches**" in roadmap
    status = manifest.get("status")
    evidence = manifest.get("evidence", {})
    if not increment26_done:
        problems.append(Problem("NODAL-INC27-016", "Increment 26 prerequisite is not closed"))
    if status == "implemented-awaiting-evidence":
        if not increment27_open or rev < (1, 32):
            problems.append(Problem("NODAL-INC27-016", "pre-evidence state must leave Increment 27 unchecked at revision 1.32 or later"))
    elif status == "validated-natures-disciplines":
        if not increment27_done or rev < (1, 33):
            problems.append(Problem("NODAL-INC27-016", "validated state must close Increment 27 at revision 1.33 or later"))
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(evidence.get(field), int):
                problems.append(Problem("NODAL-INC27-016", f"validated manifest lacks integer evidence field: {field}"))
    else:
        problems.append(Problem("NODAL-INC27-016", f"unexpected manifest status: {status!r}"))
    if not increment28_open:
        problems.append(Problem("NODAL-INC27-016", "Increment 28 must remain unchecked"))
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"Increment 27 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 27 natures and disciplines check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
