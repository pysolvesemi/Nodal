#!/usr/bin/env python3
"""Validate Increment 25: Scala RC through verified Verilog-A emission."""

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
    "core/scala/api/src/nodal/CandidateApi.scala",
    "core/scala/api/src/nodal/SemanticOriginKernel.scala",
    "core/scala/api/src/nodal/ElaborationConstructionKernel.scala",
    "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala",
    "core/scala/testkit/test/src/nodal/internal/testkit/RcVerticalSliceTests.scala",
    "core/compiler/include/nodal/Backend/AnalogVerticalSlice.h",
    "core/compiler/lib/Backend/AnalogVerticalSlice.cpp",
    "core/compiler/lib/Backend/Backend.cpp",
    "core/compiler/test/IR/rc-filter-vertical-slice.mlir",
    "tests/compiler/fixtures/increment25/golden/rc-filter.va",
    "docs/design-gates/NodalRcVerticalSlice-DG-v1.0.md",
    "docs/implementation/increment25-rc-vertical-slice.md",
    "tests/compiler/fixtures/increment25/manifest.json",
    "tests/compiler/test_increment25.py",
    "scripts/check_increment25.py",
    ".github/workflows/increment-25-rc-filter-vertical-slice.yml",
)

TEMPORARY_FILES = (
    "scripts/materialize_increment25.py",
    "scripts/finalize_increment25.py",
    ".github/workflows/increment-25-materialize.yml",
    ".github/workflows/increment-25-finalize.yml",
    ".github/workflows/increment-25-supervisor.yml",
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
    matches = re.findall(r"^\*\*Revision:\*\* ([0-9.]+)$", text, re.MULTILINE)
    if len(matches) != 1:
        return ()
    return tuple(int(part) for part in matches[0].split("."))


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    for relative in EXPECTED_FILES:
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-INC25-001", f"missing file: {relative}"))
    for relative in TEMPORARY_FILES:
        if (root / relative).exists():
            problems.append(Problem("NODAL-INC25-002", f"temporary closure file remains: {relative}"))

    api = read(root / "core/scala/api/src/nodal/CandidateApi.scala", problems, "NODAL-INC25-003")
    origin_kernel = read(
        root / "core/scala/api/src/nodal/SemanticOriginKernel.scala",
        problems,
        "NODAL-INC25-013",
    )
    kernel = read(root / "core/scala/api/src/nodal/ElaborationConstructionKernel.scala", problems, "NODAL-INC25-004")
    bridge = read(root / "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala", problems, "NODAL-INC25-005")
    backend = read(root / "core/compiler/lib/Backend/AnalogVerticalSlice.cpp", problems, "NODAL-INC25-006")
    scala_test = read(root / "core/scala/testkit/test/src/nodal/internal/testkit/RcVerticalSliceTests.scala", problems, "NODAL-INC25-007")
    fixture = read(root / "core/compiler/test/IR/rc-filter-vertical-slice.mlir", problems, "NODAL-INC25-008")
    golden = read(root / "tests/compiler/fixtures/increment25/golden/rc-filter.va", problems, "NODAL-INC25-009")
    gate = read(root / "docs/design-gates/NodalRcVerticalSlice-DG-v1.0.md", problems, "NODAL-INC25-010")
    workflow = read(root / ".github/workflows/increment-25-rc-filter-vertical-slice.yml", problems, "NODAL-INC25-011")
    roadmap = read(root / "docs/roadmap/nodal-development-todo.md", problems, "NODAL-INC25-012")

    require(api, ("val operation: Option[String]", "analogExpr", "analogBlock", "analogContribution", '"potential_access"', '"flow_access"', '"analog_ddt"'), problems, "NODAL-INC25-003", "Scala analog operation capture")
    require(
        origin_kernel,
        (
            "Files.walkFileTree(",
            "FileVisitResult.SKIP_SUBTREE",
            "visitFileFailed",
            "postVisitDirectory",
        ),
        problems,
        "NODAL-INC25-013",
        "source-location traversal",
    )
    if "Files.walk(root)" in origin_kernel:
        problems.append(
            Problem(
                "NODAL-INC25-013",
                "source-location traversal descends through volatile generated trees",
            )
        )
    require(kernel, ("KernelAnalogRegionSnapshot", "withAnalogRegion", "analogSnapshots()", "analogRegions: Vector[KernelAnalogRegionSnapshot]"), problems, "NODAL-INC25-004", "construction analog snapshot")
    require(bridge, ("compileToVerilogA", "renderAnalogRegion", '"nodal.analog"', '"nodal.parameter_ref"', '"nodal.contribute"', "NODAL-RC-OPERATION-001", "NODAL-RC-BRANCH-001"), problems, "NODAL-INC25-005", "Scala-to-MLIR RC lowering")
    require(backend, ("verifyBackendOperations", "renderBackendCandidate", "renderExpression", "ddt(", " <+ ", "parameter real", "reparseBackendTarget"), problems, "NODAL-INC25-006", "Verilog-A RC backend")
    require(scala_test, ("final class RcFilter", "Scala RC compiles through nodalc to exact Verilog-A", "NODAL-RC-OPERATION-001", "NODAL-RC-BRANCH-001"), problems, "NODAL-INC25-007", "Scala vertical-slice tests")
    require(fixture, ("RcFilter", "nodal.analog_ddt", "nodal.contribute", "nodal-to-verilog-a" if False else "nodal.backend.profile"), problems, "NODAL-INC25-008", "native RC fixture")
    require(golden, ("module RcFilter(n, p);", "parameter real C = 1e-12;", "I(p, n) <+ ((V(p, n) / R) + (C * ddt(V(p, n))));"), problems, "NODAL-INC25-009", "exact Verilog-A golden")
    require(gate, ("**Status:** Approved", "**Scope:** compiler-vertical-slice", "**Public API:** unchanged at 0.3", "never", "later increments"), problems, "NODAL-INC25-010", "design gate")
    require(workflow, ("increment-25/rc-filter-vertical-slice", "check_increment25.py", "NODAL_NODALC", "NODAL_TRANSLATE", "rc-filter-vertical-slice.mlir", "rc-filter.va", "permissions:\n  contents: read"), problems, "NODAL-INC25-011", "permanent workflow")
    if "contents: write" in workflow or "materialize_increment25" in workflow:
        problems.append(Problem("NODAL-INC25-011", "permanent Increment 25 workflow must be read-only"))

    manifest_path = root / "tests/compiler/fixtures/increment25/manifest.json"
    try:
        manifest = json.loads(read(manifest_path, problems, "NODAL-INC25-012"))
    except json.JSONDecodeError as exc:
        problems.append(Problem("NODAL-INC25-012", f"invalid manifest: {exc}"))
        manifest = {}
    if manifest.get("increment") != 25 or manifest.get("public_api") != "0.3":
        problems.append(Problem("NODAL-INC25-012", "manifest identity/public API mismatch"))
    expected_pipeline = [
        "scala-construction", "nodal-mlir", "nodal-gate-default",
        "nodal-to-verilog-a", "target-verify", "target-reparse",
    ]
    if manifest.get("pipeline") != expected_pipeline:
        problems.append(Problem("NODAL-INC25-012", "manifest pipeline inventory mismatch"))

    rev = revision(roadmap)
    inc24 = "- [x] **Increment 24 — Minimal analog expression and contribution IR**" in roadmap
    inc25_open = "- [ ] **Increment 25 — RC filter end-to-end vertical slice**" in roadmap
    inc25_done = "- [x] **Increment 25 — RC filter end-to-end vertical slice**" in roadmap
    status = manifest.get("status")
    evidence = manifest.get("evidence", {})
    if not inc24:
        problems.append(Problem("NODAL-INC25-012", "Increment 24 prerequisite is not closed"))
    if status == "implemented-awaiting-evidence":
        if not inc25_open or rev < (1, 30):
            problems.append(Problem("NODAL-INC25-012", "pre-evidence state must leave Increment 25 unchecked at revision 1.30 or later"))
    elif status == "validated-rc-vertical-slice":
        if not inc25_done or rev < (1, 31):
            problems.append(Problem("NODAL-INC25-012", "validated state must close Increment 25 at revision 1.31 or later"))
        for field in ("pull_request", "dedicated_run", "core_ci_run"):
            if not isinstance(evidence.get(field), int):
                problems.append(Problem("NODAL-INC25-012", f"validated manifest lacks integer evidence field: {field}"))
    else:
        problems.append(Problem("NODAL-INC25-012", f"unexpected manifest status: {status!r}"))
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"Increment 25 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 25 RC vertical-slice check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
