#!/usr/bin/env python3
"""Validate the Increment 31 potential/flow access starting contract."""

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
    "docs/design-gates/NodalPotentialFlowAccess-DG-v1.0.md",
    "docs/implementation/increment31-potential-flow-access.md",
    "tests/compiler/fixtures/increment31/manifest.json",
    "tests/compiler/fixtures/increment31/access-surface.json",
    "scripts/check_increment31.py",
    "tests/compiler/test_increment31.py",
    ".github/workflows/increment-31-potential-flow-access.yml",
    "docs/roadmap/nodal-development-todo.md",
    "tests/compiler/fixtures/increment30/manifest.json",
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "core/compiler/include/nodal/Dialect/Nodal/NodalTypes.td",
    "core/compiler/include/nodal/Dialect/Nodal/NatureDiscipline.h",
    "core/compiler/lib/Dialect/Nodal/NatureDiscipline.cpp",
    "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp",
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
)

TEMPORARY_FILES = (
    "scripts/materialize_increment31.py",
    "scripts/finalize_increment31.py",
    ".github/workflows/increment-31-materialize.yml",
    ".github/workflows/increment-31-finalize.yml",
    ".github/workflows/increment-31-review-fixes.yml",
)

OPERATIONS = {
    "canonical_branch_access": "nodal.access",
    "terminal_access": "nodal.terminal_access",
    "port_flow_access": "nodal.port_flow_access",
    "probe_record": "nodal.probe",
}

DIAGNOSTICS = [
    "NODAL-ACCESS-FORM-001",
    "NODAL-ACCESS-DISCIPLINE-001",
    "NODAL-ACCESS-NATURE-001",
    "NODAL-ACCESS-FUNCTION-001",
    "NODAL-ACCESS-DIMENSION-001",
    "NODAL-ACCESS-REFERENCE-001",
    "NODAL-ACCESS-PORT-001",
    "NODAL-PROBE-KIND-001",
    "NODAL-PROBE-PROVENANCE-001",
]


def read(path: Path, problems: list[Problem], code: str) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        problems.append(Problem(code, f"cannot read {path}: {exc}"))
        return ""


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


def roadmap_revision(text: str) -> tuple[int, ...]:
    values = re.findall(r"^\*\*Revision:\*\* ([0-9.]+)$", text, re.MULTILINE)
    if len(values) != 1:
        return ()
    return tuple(int(part) for part in values[0].split("."))


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []

    for relative in EXPECTED_FILES:
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-INC31-001", f"missing file: {relative}"))
    for relative in TEMPORARY_FILES:
        if (root / relative).exists():
            problems.append(
                Problem("NODAL-INC31-002", f"temporary file remains: {relative}")
            )

    gate = read(
        root / "docs/design-gates/NodalPotentialFlowAccess-DG-v1.0.md",
        problems,
        "NODAL-INC31-003",
    )
    implementation = read(
        root / "docs/implementation/increment31-potential-flow-access.md",
        problems,
        "NODAL-INC31-004",
    )
    workflow = read(
        root / ".github/workflows/increment-31-potential-flow-access.yml",
        problems,
        "NODAL-INC31-005",
    )
    roadmap = read(
        root / "docs/roadmap/nodal-development-todo.md",
        problems,
        "NODAL-INC31-006",
    )
    manifest = load_json(
        root / "tests/compiler/fixtures/increment31/manifest.json",
        problems,
        "NODAL-INC31-007",
    )
    surface = load_json(
        root / "tests/compiler/fixtures/increment31/access-surface.json",
        problems,
        "NODAL-INC31-008",
    )
    predecessor = load_json(
        root / "tests/compiler/fixtures/increment30/manifest.json",
        problems,
        "NODAL-INC31-009",
    )

    require(
        gate,
        (
            "**Status:** Approved",
            "**Public API:** unchanged at 0.3",
            "canonical nature",
            "`nodal.terminal_access`",
            "`nodal.port_flow_access`",
            "`nodal.probe`",
            "function(<port>)",
            "source-free branch",
            "backend hard-coding of `V` or `I` is prohibited",
        ),
        problems,
        "NODAL-INC31-003",
        "potential/flow access design gate",
    )
    for code in DIAGNOSTICS:
        if code not in gate and code not in json.dumps(manifest, sort_keys=True):
            problems.append(
                Problem("NODAL-INC31-003", f"planned diagnostic is absent: {code}")
            )

    require(
        implementation,
        (
            "**Status:** Started",
            "fully validated Increment 30",
            "nature-driven potential and flow access resolution",
            "one-terminal and two-terminal",
            "roadmap item remains unchecked",
        ),
        problems,
        "NODAL-INC31-004",
        "Increment 31 implementation note",
    )

    require(
        workflow,
        (
            "increment-31/potential-flow-access",
            "check_increment31.py",
            "test_increment31.py",
            "permissions:\n  contents: read",
            "git diff --check",
        ),
        problems,
        "NODAL-INC31-005",
        "Increment 31 workflow",
    )
    if "contents: write" in workflow:
        problems.append(Problem("NODAL-INC31-005", "workflow must remain read-only"))

    increment30_done = (
        "- [x] **Increment 30 — Analog numeric types and expression typing**"
        in roadmap
    )
    increment31_open = (
        "- [ ] **Increment 31 — Potential and flow access functions**" in roadmap
    )
    increment31_done = (
        "- [x] **Increment 31 — Potential and flow access functions**" in roadmap
    )
    if not increment30_done:
        problems.append(Problem("NODAL-INC31-006", "Increment 30 must be complete"))
    if not increment31_open or increment31_done:
        problems.append(
            Problem("NODAL-INC31-006", "Increment 31 must remain unchecked while starting")
        )
    if roadmap_revision(roadmap) < (1, 40):
        problems.append(
            Problem(
                "NODAL-INC31-006",
                "roadmap revision predates Increment 30 closure",
            )
        )

    if manifest.get("increment") != 31 or manifest.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC31-007", "manifest identity/public API mismatch"))
    if manifest.get("status") != "implementation-started":
        problems.append(
            Problem(
                "NODAL-INC31-007",
                "Increment 31 must start as implementation-started",
            )
        )
    if manifest.get("branch") != "increment/31-potential-flow-access":
        problems.append(Problem("NODAL-INC31-007", "manifest branch mismatch"))
    if manifest.get("operations") != OPERATIONS:
        problems.append(Problem("NODAL-INC31-007", "operation inventory mismatch"))
    if manifest.get("planned_diagnostics") != DIAGNOSTICS:
        problems.append(Problem("NODAL-INC31-007", "diagnostic inventory mismatch"))

    prerequisite = manifest.get("prerequisite", {})
    if prerequisite.get("increment") != 30:
        problems.append(Problem("NODAL-INC31-007", "manifest prerequisite increment mismatch"))
    if prerequisite.get("status") != "validated-analog-numeric-typing":
        problems.append(Problem("NODAL-INC31-007", "manifest prerequisite status mismatch"))
    if prerequisite.get("dev_commit") != "f33bcff3285f17d228bab4c7577bafd35ab32a65":
        problems.append(Problem("NODAL-INC31-007", "manifest prerequisite dev head mismatch"))

    if predecessor.get("increment") != 30:
        problems.append(Problem("NODAL-INC31-009", "Increment 30 manifest identity mismatch"))
    if predecessor.get("status") != "validated-analog-numeric-typing":
        problems.append(Problem("NODAL-INC31-009", "Increment 30 is not validated"))

    if surface.get("schema") != "nodal-potential-flow-access/v1":
        problems.append(Problem("NODAL-INC31-008", "surface schema mismatch"))
    access = surface.get("access", {})
    if access.get("semanticKinds") != ["potential", "flow"]:
        problems.append(Problem("NODAL-INC31-008", "surface semantic kinds mismatch"))
    if surface.get("forms", {}).get("portFlow") != "function(<port>)":
        problems.append(Problem("NODAL-INC31-008", "surface port-flow form mismatch"))
    probes = surface.get("probes", {})
    if probes.get("mixedSourceFreeAccess") != "reject":
        problems.append(Problem("NODAL-INC31-008", "mixed probe access is not rejected"))
    if probes.get("foldable") is not False:
        problems.append(Problem("NODAL-INC31-008", "access values must not be foldable"))

    baseline_contracts = {
        "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td": (
            "Nodal_NatureOp",
            "Nodal_DisciplineOp",
            "Nodal_TerminalOp",
            "Nodal_BranchOp",
            "Nodal_AccessOp",
        ),
        "core/compiler/include/nodal/Dialect/Nodal/NodalTypes.td": (
            "Nodal_QuantityType",
            "Nodal_TerminalType",
            "Nodal_BranchType",
        ),
        "core/compiler/include/nodal/Dialect/Nodal/NatureDiscipline.h": (
            "resolveNatureDeclaration",
            "resolveDisciplineDeclaration",
        ),
        "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp": (
            "verifyAccess",
            "NODAL-ANALOG-ACCESS-001",
        ),
        "core/compiler/lib/Backend/AnalogVerticalSlice.cpp": (
            'name == "nodal.access"',
            'renderBranch(operation->getOperand(0), state, "V")',
            'renderBranch(operation->getOperand(0), state, "I")',
        ),
    }
    for relative, fragments in baseline_contracts.items():
        text = read(root / relative, problems, "NODAL-INC31-010")
        require(
            text,
            fragments,
            problems,
            "NODAL-INC31-010",
            f"Increment 31 baseline {relative}",
        )

    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root",
    )
    args = parser.parse_args(argv)
    problems = check_repository(args.root)
    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1
    print("Increment 31 potential/flow access starting contract passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
