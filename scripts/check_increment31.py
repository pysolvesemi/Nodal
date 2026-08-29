#!/usr/bin/env python3
"""Validate the Increment 31 potential/flow access implementation contract."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
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
    "core/compiler/include/nodal/Dialect/Nodal/PotentialFlowAccess.h",
    "core/compiler/lib/Dialect/Nodal/NatureDiscipline.cpp",
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    "core/compiler/lib/Dialect/Nodal/PotentialFlowAccess.cpp",
    "core/compiler/lib/Dialect/Nodal/CMakeLists.txt",
    "core/compiler/lib/Transforms/Passes.cpp",
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    "core/compiler/diagnostics-v0.1.json",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/IR/potential-flow-access.mlir",
    "core/compiler/test/IR/potential-flow-access-invalid-form.mlir",
    "core/compiler/test/IR/potential-flow-access-invalid-discipline.mlir",
    "core/compiler/test/IR/potential-flow-access-invalid-nature.mlir",
    "core/compiler/test/IR/potential-flow-access-invalid-function.mlir",
    "core/compiler/test/IR/potential-flow-access-invalid-dimension.mlir",
    "core/compiler/test/IR/potential-flow-access-invalid-reference.mlir",
    "core/compiler/test/IR/potential-flow-access-invalid-port.mlir",
    "core/compiler/test/IR/potential-flow-access-invalid-probe-kind.mlir",
    "core/compiler/test/IR/potential-flow-access-invalid-probe-provenance.mlir",
)

TEMPORARY_FILES = (
    "scripts/materialize_increment31.py",
    "scripts/finalize_increment31.py",
    "scripts/_increment31_native_agent.py",
    "scripts/_increment31_finalize.py",
    ".github/workflows/increment-31-materialize.yml",
    ".github/workflows/increment-31-finalize.yml",
    ".github/workflows/increment-31-review-fixes.yml",
    ".github/workflows/_increment31_native_agent.yml",
    ".github/workflows/_increment31_finalize.yml",
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

FIXTURE_DIAGNOSTICS = {
    "form": "NODAL-ACCESS-FORM-001",
    "discipline": "NODAL-ACCESS-DISCIPLINE-001",
    "nature": "NODAL-ACCESS-NATURE-001",
    "function": "NODAL-ACCESS-FUNCTION-001",
    "dimension": "NODAL-ACCESS-DIMENSION-001",
    "reference": "NODAL-ACCESS-REFERENCE-001",
    "port": "NODAL-ACCESS-PORT-001",
    "probe-kind": "NODAL-PROBE-KIND-001",
    "probe-provenance": "NODAL-PROBE-PROVENANCE-001",
}


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
    try:
        inventory = subprocess.run(
            [
                "git",
                "-C",
                str(root),
                "ls-files",
                "--cached",
                "--others",
                "--exclude-standard",
                "-z",
            ],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        ).stdout.decode("utf-8")
    except (OSError, subprocess.CalledProcessError, UnicodeDecodeError) as exc:
        problems.append(
            Problem(
                "NODAL-INC31-002",
                f"cannot inventory repository artifacts: {exc}",
            )
        )
        inventory = ""
    for relative in sorted(path for path in inventory.split("\0") if path):
        path = Path(relative)
        if "__pycache__" in path.parts or path.suffix in {".pyc", ".pyo"}:
            problems.append(
                Problem(
                    "NODAL-INC31-002",
                    f"Python bytecode artifact remains: {path.as_posix()}",
                )
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
            "**Status:** Implemented — awaiting evidence",
            "Increment 31 remains unchecked",
            "resolvePotentialFlowAccessNature",
            "nodal-normalize-potential-flow-access",
            "all 83 CTest cases",
            "public Scala API remains v0.3",
            "transactional Fast, Default, and Release semantic gates",
            "Named branches remain distinct",
        ),
        problems,
        "NODAL-INC31-004",
        "Increment 31 implementation note",
    )

    require(
        workflow,
        (
            "increment-31/potential-flow-access",
            "PYTHONDONTWRITEBYTECODE: '1'",
            "check_increment31.py",
            "test_increment31.py",
            "./nodal bootstrap",
            "./nodal style bootstrap",
            "./nodal core native",
            "permissions:\n  contents: read",
            "git diff --check",
        ),
        problems,
        "NODAL-INC31-005",
        "Increment 31 workflow",
    )
    if "contents: write" in workflow or "_increment31_finalize" in workflow:
        problems.append(
            Problem("NODAL-INC31-005", "permanent workflow must remain read-only")
        )

    if manifest.get("increment") != 31 or manifest.get("public_api") != "0.3":
        problems.append(
            Problem("NODAL-INC31-007", "manifest identity/public API mismatch")
        )
    status = manifest.get("status")
    if status == "implementation-started":
        problems.append(
            Problem("NODAL-INC31-007", "native implementation is still marked as a scaffold")
        )
    if status not in {
        "implemented-awaiting-evidence",
        "validated-potential-flow-access",
    }:
        problems.append(
            Problem("NODAL-INC31-007", "unsupported Increment 31 implementation status")
        )
    if manifest.get("branch") != "increment/31-potential-flow-access":
        problems.append(Problem("NODAL-INC31-007", "manifest branch mismatch"))
    if manifest.get("operations") != OPERATIONS:
        problems.append(Problem("NODAL-INC31-007", "operation inventory mismatch"))
    if manifest.get("planned_diagnostics") != DIAGNOSTICS:
        problems.append(Problem("NODAL-INC31-007", "diagnostic inventory mismatch"))

    prerequisite = manifest.get("prerequisite", {})
    if prerequisite.get("increment") != 30:
        problems.append(
            Problem("NODAL-INC31-007", "manifest prerequisite increment mismatch")
        )
    if prerequisite.get("status") != "validated-analog-numeric-typing":
        problems.append(
            Problem("NODAL-INC31-007", "manifest prerequisite status mismatch")
        )
    if (
        prerequisite.get("dev_commit")
        != "f33bcff3285f17d228bab4c7577bafd35ab32a65"
    ):
        problems.append(
            Problem("NODAL-INC31-007", "manifest prerequisite dev head mismatch")
        )

    implementation_contract = manifest.get("implementation", {})
    expected_implementation = {
        "resolver": "resolvePotentialFlowAccessNature",
        "operation_verifier": "verifyPotentialFlowAccessOperation",
        "model_verifier": "verifyPotentialFlowAccessModel",
        "normalizer": "normalizePotentialFlowAccess",
        "normalization_pass": "nodal-normalize-potential-flow-access",
        "backend_entry": "normalizePotentialFlowAccess-before-quantity-erasure",
        "semantic_pipeline": "normalize-before-verification",
        "named_branch_isolation": "implicit-only-endpoint-coalescing",
        "positive_fixture": "core/compiler/test/IR/potential-flow-access.mlir",
        "negative_fixture_count": 9,
        "native_test_count": 83,
    }
    for key, value in expected_implementation.items():
        if implementation_contract.get(key) != value:
            problems.append(
                Problem(
                    "NODAL-INC31-007",
                    f"manifest implementation contract mismatch for {key}",
                )
            )

    if predecessor.get("increment") != 30:
        problems.append(
            Problem("NODAL-INC31-009", "Increment 30 manifest identity mismatch")
        )
    if predecessor.get("status") != "validated-analog-numeric-typing":
        problems.append(Problem("NODAL-INC31-009", "Increment 30 is not validated"))

    if surface.get("schema") != "nodal-potential-flow-access/v1":
        problems.append(Problem("NODAL-INC31-008", "surface schema mismatch"))
    access = surface.get("access", {})
    if access.get("semanticKinds") != ["potential", "flow"]:
        problems.append(
            Problem("NODAL-INC31-008", "surface semantic kinds mismatch")
        )
    if surface.get("forms", {}).get("portFlow") != "function(<port>)":
        problems.append(Problem("NODAL-INC31-008", "surface port-flow form mismatch"))
    normalization = surface.get("normalization", {})
    if normalization.get("namedBranchIsolation") is not True:
        problems.append(
            Problem(
                "NODAL-INC31-008",
                "named branches must remain isolated from endpoint-only access",
            )
        )
    probes = surface.get("probes", {})
    if probes.get("mixedSourceFreeAccess") != "reject":
        problems.append(
            Problem("NODAL-INC31-008", "mixed probe access is not rejected")
        )
    if probes.get("foldable") is not False:
        problems.append(
            Problem("NODAL-INC31-008", "access values must not be foldable")
        )

    native_contracts = {
        "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td": (
            "OptionalAttr<StrAttr>:$dimension",
            "OptionalAttr<StrAttr>:$function",
            "Nodal_TerminalAccessOp",
            "Nodal_PortFlowAccessOp",
            "Nodal_ProbeOp",
        ),
        "core/compiler/include/nodal/Dialect/Nodal/PotentialFlowAccess.h": (
            "ResolvedAccessNature",
            "resolvePotentialFlowAccessNature",
            "verifyPotentialFlowAccessOperation",
            "createNormalizePotentialFlowAccessPass",
            "normalizePotentialFlowAccess",
            "verifyPotentialFlowAccessModel",
        ),
        "core/compiler/lib/Dialect/Nodal/PotentialFlowAccess.cpp": (
            'kGeneratedBy = "increment31-potential-flow-access"',
            "resolvePotentialFlowAccessNature",
            "verifyPotentialFlowAccessOperation",
            "normalizePotentialFlowAccess",
            "verifyPotentialFlowAccessModel",
            "probeProvenanceMatches",
            "isImplicitBranchOperation",
            "findImplicitBranchGroup",
            'return "nodal-normalize-potential-flow-access"',
            '"zero-flow"',
            '"zero-potential"',
        ),
        "core/compiler/lib/Dialect/Nodal/NatureDiscipline.cpp": (
            "NODAL-ACCESS-DIMENSION-001",
            "isCanonicalDimensionSignature",
        ),
        "core/compiler/lib/Dialect/Nodal/NodalOps.cpp": (
            "TerminalAccessOp::verify",
            "PortFlowAccessOp::verify",
            "ProbeOp::verify",
            "verifyPotentialFlowAccessOperation",
        ),
        "core/compiler/lib/Dialect/Nodal/CMakeLists.txt": (
            "PotentialFlowAccess.cpp",
            "MLIRPass",
        ),
        "core/compiler/lib/Transforms/Passes.cpp": (
            "createNormalizePotentialFlowAccessPass",
            '"nodal.terminal_access"',
            '"nodal.port_flow_access"',
            '"nodal.probe"',
        ),
        "core/compiler/lib/Backend/AnalogVerticalSlice.cpp": (
            '"nodal.terminal_access"',
            '"nodal.port_flow_access"',
            'getAttrOfType<StringAttr>("function")',
            "normalizePotentialFlowAccess(module)",
            'llvm::Twine(access) + "(<" + port->second + ">)"',
        ),
        "core/compiler/test/CMakeLists.txt": (
            "potential-flow-access-normalize",
            "potential-flow-access-reference",
            "builtin.module(nodal-gate-release)",
            "form = .two-terminal.",
            "potential-flow-access-rejects-${_fixture}",
            "potential-flow-access-backend-discipline",
            "potential-flow-access-backend-generic",
            "potential-flow-access-backend-port",
        ),
    }
    for relative, fragments in native_contracts.items():
        require(
            read(root / relative, problems, "NODAL-INC31-010"),
            fragments,
            problems,
            "NODAL-INC31-010",
            relative,
        )

    backend = read(
        root / "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
        problems,
        "NODAL-INC31-010",
    )
    for legacy_direct_call in (
        'renderBranch(operation->getOperand(0), state, "V")',
        'renderBranch(operation->getOperand(0), state, "I")',
    ):
        if legacy_direct_call in backend:
            problems.append(
                Problem(
                    "NODAL-INC31-010",
                    "backend bypasses verified access-function selection",
                )
            )
    function_position = backend.find('getAttrOfType<StringAttr>("function")')
    legacy_position = backend.find('access = "V"')
    render_position = backend.find(
        "renderBranch(operation->getOperand(0), state, access)"
    )
    if min(function_position, legacy_position, render_position) < 0 or not (
        function_position < legacy_position < render_position
    ):
        problems.append(
            Problem(
                "NODAL-INC31-010",
                "legacy fallback is not isolated behind authored function resolution",
            )
        )

    positive = read(
        root / "core/compiler/test/IR/potential-flow-access.mlir",
        problems,
        "NODAL-INC31-011",
    )
    require(
        positive,
        (
            'access = "Across"',
            'access = "Through"',
            'dimension = "voltage"',
            'dimension = "current"',
            '"nodal.access"',
            '"nodal.terminal_access"',
            '"nodal.port_flow_access"',
            'function = "potential"',
            'function = "Across"',
            'function = "Through"',
            'declaration_kind = "named"',
            'name = "named_parallel"',
        ),
        problems,
        "NODAL-INC31-011",
        "positive native access fixture",
    )

    cmake = read(
        root / "core/compiler/test/CMakeLists.txt",
        problems,
        "NODAL-INC31-011",
    )
    for fixture, code in FIXTURE_DIAGNOSTICS.items():
        path = root / f"core/compiler/test/IR/potential-flow-access-invalid-{fixture}.mlir"
        text = read(path, problems, "NODAL-INC31-011")
        if not text.strip():
            problems.append(
                Problem("NODAL-INC31-011", f"empty negative fixture: {fixture}")
            )
        if fixture not in cmake:
            problems.append(
                Problem("NODAL-INC31-011", f"CTest omits negative fixture: {fixture}")
            )
        if code not in gate and code not in json.dumps(manifest):
            problems.append(
                Problem("NODAL-INC31-011", f"diagnostic is not contracted: {code}")
            )

    diagnostics = load_json(
        root / "core/compiler/diagnostics-v0.1.json",
        problems,
        "NODAL-INC31-012",
    )
    catalog = diagnostics.get("families", {}).get("potential-flow-access", [])
    for code in DIAGNOSTICS:
        if code not in catalog:
            problems.append(
                Problem("NODAL-INC31-012", f"diagnostic catalog lacks: {code}")
            )

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
    revision = roadmap_revision(roadmap)
    if revision < (1, 40):
        problems.append(
            Problem(
                "NODAL-INC31-006",
                "roadmap revision predates Increment 30 closure",
            )
        )

    evidence = manifest.get("evidence", {})
    if status == "implemented-awaiting-evidence":
        if not increment31_open or increment31_done:
            problems.append(
                Problem(
                    "NODAL-INC31-006",
                    "pre-evidence implementation must leave Increment 31 unchecked",
                )
            )
    elif status == "validated-potential-flow-access":
        if not increment31_done or revision < (1, 41):
            problems.append(
                Problem(
                    "NODAL-INC31-006",
                    "validated state must close Increment 31 at revision 1.41 or later",
                )
            )
        for field in ("pull_request", "implementation_head", "merge_commit",
                      "dedicated_run", "core_ci_run"):
            if field not in evidence:
                problems.append(
                    Problem(
                        "NODAL-INC31-006",
                        f"validated manifest lacks evidence field: {field}",
                    )
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
    print("Increment 31 potential/flow access implementation contract passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
