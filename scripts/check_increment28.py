#!/usr/bin/env python3
"""Validate Increment 28: electrical conservative connectivity."""

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
    "core/compiler/include/nodal/Dialect/Nodal/ConservativeConnectivity.h",
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "core/compiler/lib/Dialect/Nodal/ConservativeConnectivity.cpp",
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    "core/compiler/lib/Dialect/Nodal/CMakeLists.txt",
    "core/compiler/lib/Transforms/Passes.cpp",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/Unit/CMakeLists.txt",
    "core/compiler/test/Unit/ConservativeConnectivityTest.cpp",
    "core/compiler/test/IR/electrical-connectivity.mlir",
    "core/compiler/test/IR/electrical-connectivity-invalid-discipline.mlir",
    "core/compiler/test/IR/electrical-connectivity-invalid-implicit.mlir",
    "core/compiler/test/IR/electrical-connectivity-invalid-connection.mlir",
    "core/compiler/test/IR/electrical-connectivity-invalid-reference.mlir",
    "core/compiler/test/IR/electrical-connectivity-invalid-component.mlir",
    "core/compiler/diagnostics-v0.1.json",
    "docs/design-gates/NodalElectricalConnectivity-DG-v1.0.md",
    "docs/implementation/increment28-electrical-connectivity.md",
    "tests/compiler/fixtures/increment28/manifest.json",
    "tests/compiler/test_increment28.py",
    "scripts/check_increment27.py",
    "tests/compiler/test_increment27.py",
    "scripts/check_increment28.py",
    ".github/workflows/increment-28-electrical-connectivity.yml",
    "tests/compiler/fixtures/increment29/manifest.json",
    "scripts/check_increment29.py",
    "tests/compiler/test_increment29.py",
)

TEMPORARY_FILES = (
    "scripts/materialize_increment28.py",
    "scripts/finalize_increment28.py",
    "scripts/close_increment28.py",
    ".github/workflows/increment-28-materialize.yml",
    ".github/workflows/increment-28-finalize.yml",
    ".github/workflows/increment-28-close.yml",
    "scripts/.increment28_review_fix.py",
    ".github/workflows/increment-28-review-fix.yml",
)

SOURCE_OPERATIONS = [
    "nodal.component_contract",
    "nodal.terminal",
    "nodal.node",
    "nodal.connect",
    "nodal.alias",
    "nodal.reference",
    "nodal.branch",
]

GENERATED_OPERATIONS = [
    "nodal.connection_set",
    "nodal.potential_equality",
    "nodal.reference_potential",
    "nodal.flow_conservation",
]

CODES = [
    "NODAL-COMPONENT-CONTRACT-001",
    "NODAL-CONNECTIVITY-PROVENANCE-001",
    "NODAL-TERMINAL-DISCIPLINE-001",
    "NODAL-TERMINAL-DIRECTION-001",
    "NODAL-TERMINAL-ORIENTATION-001",
    "NODAL-CONNECTION-001",
    "NODAL-CONNECTION-DISCIPLINE-001",
    "NODAL-ALIAS-001",
    "NODAL-REFERENCE-001",
    "NODAL-REFERENCE-002",
    "NODAL-BRANCH-DISCIPLINE-001",
    "NODAL-BRANCH-ORIENTATION-001",
    "NODAL-BRANCH-IMPLICIT-001",
    "NODAL-CONNECTION-SET-001",
    "NODAL-CONNECTION-POTENTIAL-001",
    "NODAL-CONNECTION-FLOW-001",
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
    try:
        return tuple(int(part) for part in values[0].split("."))
    except ValueError:
        return ()


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []

    for relative in EXPECTED_FILES:
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-INC28-001", f"missing file: {relative}"))
    for relative in TEMPORARY_FILES:
        if (root / relative).exists():
            problems.append(Problem("NODAL-INC28-002", f"temporary file remains: {relative}"))

    td = read(
        root / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
        problems,
        "NODAL-INC28-003",
    )
    header = read(
        root / "core/compiler/include/nodal/Dialect/Nodal/ConservativeConnectivity.h",
        problems,
        "NODAL-INC28-004",
    )
    source = read(
        root / "core/compiler/lib/Dialect/Nodal/ConservativeConnectivity.cpp",
        problems,
        "NODAL-INC28-005",
    )
    ops = read(
        root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
        problems,
        "NODAL-INC28-006",
    )
    transforms = read(
        root / "core/compiler/lib/Transforms/Passes.cpp",
        problems,
        "NODAL-INC28-007",
    )
    dialect_cmake = read(
        root / "core/compiler/lib/Dialect/Nodal/CMakeLists.txt",
        problems,
        "NODAL-INC28-008",
    )
    test_cmake = read(
        root / "core/compiler/test/CMakeLists.txt",
        problems,
        "NODAL-INC28-009",
    )
    unit_cmake = read(
        root / "core/compiler/test/Unit/CMakeLists.txt",
        problems,
        "NODAL-INC28-010",
    )
    unit = read(
        root / "core/compiler/test/Unit/ConservativeConnectivityTest.cpp",
        problems,
        "NODAL-INC28-011",
    )
    positive = read(
        root / "core/compiler/test/IR/electrical-connectivity.mlir",
        problems,
        "NODAL-INC28-012",
    )
    gate = read(
        root / "docs/design-gates/NodalElectricalConnectivity-DG-v1.0.md",
        problems,
        "NODAL-INC28-013",
    )
    implementation = read(
        root / "docs/implementation/increment28-electrical-connectivity.md",
        problems,
        "NODAL-INC28-014",
    )
    workflow = read(
        root / ".github/workflows/increment-28-electrical-connectivity.yml",
        problems,
        "NODAL-INC28-015",
    )
    catalog = read(
        root / "core/compiler/diagnostics-v0.1.json",
        problems,
        "NODAL-INC28-016",
    )
    predecessor = read(
        root / "scripts/check_increment27.py", problems, "NODAL-INC28-017"
    )
    predecessor_tests = read(
        root / "tests/compiler/test_increment27.py",
        problems,
        "NODAL-INC28-018",
    )
    roadmap = read(
        root / "docs/roadmap/nodal-development-todo.md",
        problems,
        "NODAL-INC28-019",
    )

    require(
        td,
        (
            "def Nodal_ComponentContractOp",
            "OptionalAttr<StrAttr>:$direction",
            "OptionalAttr<StrAttr>:$flow_orientation",
            "def Nodal_ConnectOp",
            "def Nodal_AliasOp",
            "def Nodal_ReferenceOp",
            "OptionalAttr<StrAttr>:$declaration_kind",
            "def Nodal_ConnectionSetOp",
            "def Nodal_PotentialEqualityOp",
            "def Nodal_ReferencePotentialOp",
            "def Nodal_FlowConservationOp",
        ),
        problems,
        "NODAL-INC28-003",
        "TableGen connectivity model",
    )
    require(
        header,
        ("materializeConservativeConnectivity", "isPartialPhysicalComponent"),
        problems,
        "NODAL-INC28-004",
        "connectivity API",
    )
    require(
        source,
        (
            "class UnionFind",
            "stableHash",
            "sanitizeSymbol",
            "resolveConservativeDiscipline",
            "componentKind == \"partial\"",
            "endpoint.flowOrientation == \"into_component\" ? -1 : 1",
            "sets.unite",
            "endpoints[index].operation, info.discipline, endpoints[index].discipline",
            "endpoints[index].discipline < info.discipline",
            "nodal::ConnectionSetOp::getOperationName",
            "nodal::PotentialEqualityOp::getOperationName",
            "nodal::ReferencePotentialOp::getOperationName",
            "nodal::FlowConservationOp::getOperationName",
            "materializeConservativeConnectivity",
            "OwningOpRef<mlir::ModuleOp>",
            "global::",
            "normalized connectivity operations are compiler-owned",
            "NODAL-CONNECTIVITY-PROVENANCE-001: component source paths must be canonical",
            "reference identity does not match its scope and discipline",
            "flow provenance disagrees with its oriented source term",
            "completeness must agree with its connection set ownership",
            "state.propertiesAttr = builder.getDictionaryAttr",
        ) + tuple(code for code in CODES if code != "NODAL-BRANCH-DISCIPLINE-001"),
        problems,
        "NODAL-INC28-005",
        "connectivity implementation",
    )
    if source.count("state.propertiesAttr = builder.getDictionaryAttr") != 4:
        problems.append(
            Problem(
                "NODAL-INC28-005",
                "all four generated operation kinds must initialize ODS properties",
            )
        )
    for forbidden in ("_zz", "static unsigned connection", "direction == \"output\" ?"):
        if forbidden in source:
            problems.append(
                Problem(
                    "NODAL-INC28-005",
                    f"forbidden unstable or causal fragment: {forbidden}",
                )
            )
    require(
        ops,
        ("NatureDiscipline.h", "areDisciplinesCompatible", "NODAL-BRANCH-DISCIPLINE-001"),
        problems,
        "NODAL-INC28-006",
        "branch compatibility verifier",
    )
    require(
        transforms,
        (
            "ConservativeConnectivity.h",
            "MaterializeConservativeConnectivityPass",
            "nodal-materialize-conservative-connectivity",
            "materializeConservativeConnectivity(getOperation())",
            "std::make_unique<MaterializeConservativeConnectivityPass>()",
            "PassRegistration<MaterializeConservativeConnectivityPass>",
            "nodal.connection_set",
            "nodal.flow_conservation",
            "isPartialPhysicalComponent",
        ),
        problems,
        "NODAL-INC28-007",
        "semantic pipeline integration",
    )
    require(
        dialect_cmake,
        ("ConservativeConnectivity.cpp",),
        problems,
        "NODAL-INC28-008",
        "dialect build",
    )
    require(
        test_cmake,
        (
            "electrical-connectivity-roundtrip",
            "electrical-connectivity-generic",
            "electrical-connectivity-rejects-${_fixture}",
            "nodal-conservative-connectivity-unit-tests",
        ),
        problems,
        "NODAL-INC28-009",
        "native CTest integration",
    )
    require(
        unit_cmake,
        ("nodal-conservative-connectivity-unit-tests", "ConservativeConnectivityTest.cpp"),
        problems,
        "NODAL-INC28-010",
        "unit target",
    )
    require(
        unit,
        (
            "normalized topology/equation inventory is incorrect",
            "module-local reference identity was not retained",
            "compatible discipline set did not select deterministic representative",
            "port direction incorrectly changed conservative flow orientation",
            "connectivity materialization is not deterministic and idempotent",
            "duplicate implicit branch was accepted",
            "invalid partial/concrete ownership was accepted",
        ),
        problems,
        "NODAL-INC28-011",
        "native connectivity tests",
    )
    require(
        positive,
        tuple(SOURCE_OPERATIONS)
        + (
            'direction = "input"',
            'direction = "output"',
            'direction = "inout"',
            'flow_orientation = "into_component"',
            'flow_orientation = "out_of_component"',
            'kind = "partial"',
            'kind = "concrete"',
            'scope = "global"',
            'scope = "module"',
            'declaration_kind = "named"',
            'declaration_kind = "implicit"',
            'sym_name = "electrical_equivalent"',
            '!nodal.terminal<"electrical_equivalent">',
        ),
        problems,
        "NODAL-INC28-012",
        "positive topology fixture",
    )
    require(
        gate,
        (
            "**Status:** Approved",
            "**Scope:** compiler-ir",
            "**Public API:** unchanged at 0.3",
            "Port direction never creates causal signal-flow assignment semantics",
            "spanning set of `nodal.potential_equality`",
            "signed `nodal.flow_conservation`",
            "lexicographically smallest canonical",
            "Residual DAE construction",
        ),
        problems,
        "NODAL-INC28-013",
        "design gate",
    )
    require(
        implementation,
        (
            "union-find",
            "hash-based symbols",
            "Concrete records are complete/local",
            "incomplete/extensible",
            "public API v0.3 unchanged",
            "distinct compatible discipline declarations",
            "fail-closed",
        ),
        problems,
        "NODAL-INC28-014",
        "implementation note",
    )
    require(
        workflow,
        (
            "increment-28/electrical-connectivity",
            "check_increment28.py",
            "./nodal core native",
            "electrical-connectivity.mlir",
            "NODAL-TERMINAL-DISCIPLINE-001",
            "NODAL-BRANCH-IMPLICIT-001",
            "NODAL-CONNECTION-DISCIPLINE-001",
            "NODAL-REFERENCE-002",
            "NODAL-COMPONENT-CONTRACT-001",
            "permissions:\n  contents: read",
        ),
        problems,
        "NODAL-INC28-015",
        "permanent workflow",
    )
    if "contents: write" in workflow or "materialize_increment28" in workflow:
        problems.append(Problem("NODAL-INC28-015", "permanent workflow must be read-only"))
    for code in CODES:
        if code not in catalog:
            problems.append(Problem("NODAL-INC28-016", f"diagnostic catalog lacks {code}"))
    require(
        predecessor,
        (
            "increment28_open",
            "increment28_done",
            "tests/compiler/fixtures/increment28/manifest.json",
            "validated-electrical-connectivity",
        ),
        problems,
        "NODAL-INC28-017",
        "Increment 27 successor handling",
    )
    require(
        predecessor_tests,
        ("test_accepts_validated_increment28_successor", "validated-electrical-connectivity"),
        problems,
        "NODAL-INC28-018",
        "Increment 27 successor tests",
    )

    manifest_path = root / "tests/compiler/fixtures/increment28/manifest.json"
    try:
        manifest = json.loads(read(manifest_path, problems, "NODAL-INC28-019"))
    except json.JSONDecodeError as exc:
        problems.append(Problem("NODAL-INC28-019", f"invalid manifest: {exc}"))
        manifest = {}
    if manifest.get("increment") != 28 or manifest.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC28-019", "manifest identity/public API mismatch"))
    if manifest.get("source_operations") != SOURCE_OPERATIONS:
        problems.append(Problem("NODAL-INC28-019", "manifest source-operation inventory mismatch"))
    if manifest.get("generated_operations") != GENERATED_OPERATIONS:
        problems.append(
            Problem(
                "NODAL-INC28-019",
                "manifest generated-operation inventory mismatch",
            )
        )
    if manifest.get("normalization_pass") != "nodal-materialize-conservative-connectivity":
        problems.append(Problem("NODAL-INC28-019", "manifest pass identity mismatch"))
    if manifest.get("discipline_representative") != "lexicographically-smallest-compatible-canonical-symbol":
        problems.append(
            Problem("NODAL-INC28-019", "manifest discipline representative mismatch")
        )
    if manifest.get("component_contract") != {"partial": "extensible", "concrete": "local"}:
        problems.append(Problem("NODAL-INC28-019", "manifest component ownership mismatch"))
    if manifest.get("diagnostics") != CODES:
        problems.append(Problem("NODAL-INC28-019", "manifest diagnostics mismatch"))

    rev = revision(roadmap)
    increment27_done = "- [x] **Increment 27 — Natures and disciplines**" in roadmap
    increment28_open = "- [ ] **Increment 28 — Electrical nodes, nets, and branches**" in roadmap
    increment28_done = "- [x] **Increment 28 — Electrical nodes, nets, and branches**" in roadmap
    increment29_open = (
        "- [ ] **Increment 29 — Parameters, constants, ranges, and units**"
        in roadmap
    )
    status = manifest.get("status")
    evidence = manifest.get("evidence", {})
    if not increment27_done:
        problems.append(Problem("NODAL-INC28-019", "Increment 27 prerequisite is not closed"))
    if status == "implemented-awaiting-evidence":
        if not increment28_open or rev < (1, 35):
            problems.append(
                Problem(
                    "NODAL-INC28-019",
                    "pre-evidence state must leave Increment 28 unchecked at "
                    "revision 1.35 or later",
                )
            )
    elif status == "validated-electrical-connectivity":
        if not increment28_done or rev < (1, 36):
            problems.append(
                Problem(
                    "NODAL-INC28-019",
                    "validated state must close Increment 28 at revision 1.36 or later",
                )
            )
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(evidence.get(field), int):
                problems.append(
                    Problem(
                        "NODAL-INC28-019",
                        f"validated manifest lacks integer evidence field: {field}",
                    )
                )
    else:
        problems.append(Problem("NODAL-INC28-019", f"unexpected manifest status: {status!r}"))
    increment29_done = (
        "- [x] **Increment 29 — Parameters, constants, ranges, and units**"
        in roadmap
    )
    increment30_open = (
        "- [ ] **Increment 30 — Analog numeric types and expression typing**"
        in roadmap
    )
    successor_path = root / "tests/compiler/fixtures/increment29/manifest.json"
    try:
        successor = json.loads(read(successor_path, problems, "NODAL-INC28-019"))
    except json.JSONDecodeError as exc:
        problems.append(Problem("NODAL-INC28-019", f"invalid Increment 29 manifest: {exc}"))
        successor = {}
    successor_status = successor.get("status")
    successor_evidence = successor.get("evidence", {})
    if successor.get("increment") != 29 or successor.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC28-019", "Increment 29 successor identity mismatch"))
    if successor_status == "implemented-awaiting-evidence":
        if not increment29_open or rev < (1, 36):
            problems.append(Problem("NODAL-INC28-019", "Increment 29 pre-evidence state is inconsistent"))
    elif successor_status == "validated-parameter-constant-unit":
        if not increment29_done or rev < (1, 37):
            problems.append(Problem("NODAL-INC28-019", "validated Increment 29 state is inconsistent"))
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(successor_evidence.get(field), int):
                problems.append(Problem("NODAL-INC28-019", f"Increment 29 lacks evidence field: {field}"))
    else:
        problems.append(Problem("NODAL-INC28-019", f"unexpected Increment 29 status: {successor_status!r}"))
    if not increment30_open:
        problems.append(Problem("NODAL-INC28-019", "Increment 30 must remain unchecked"))

    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"Increment 28 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 28 electrical conservative connectivity check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
