#!/usr/bin/env python3
"""Validate Increment 13 core-semantic compile candidates and negative contracts."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path("tests/api/fixtures/increment13/manifest.json")
API = Path("core/scala/api/src/nodal/CoreSemanticsCandidateApi.scala")
POSITIVE = Path("examples/coreSemanticsApi/src/CoreSemanticsCandidates.scala")
EXTERNAL = Path("examples/coreSemanticsExternal/src/ReusableCoreSemantics.scala")
GATE = Path("docs/design-gates/NodalCoreSemanticCandidates-DG-v0.3.md")
ROADMAP = Path("docs/roadmap/nodal-development-todo.md")


@dataclass(frozen=True)
class Problem:
    code: str
    message: str

    def __str__(self) -> str:
        return f"{self.code}: {self.message}"


def read(path: Path, problems: list[Problem], code: str) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        problems.append(Problem(code, f"cannot read {path}: {exc}"))
        return ""


def require(text: str, fragments: tuple[str, ...], problems: list[Problem], code: str) -> None:
    for fragment in fragments:
        if fragment not in text:
            problems.append(Problem(code, f"missing required candidate fragment: {fragment}"))


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    api = read(root / API, problems, "NODAL-INC13-001")
    positive = read(root / POSITIVE, problems, "NODAL-INC13-002")
    external = read(root / EXTERNAL, problems, "NODAL-INC13-003")
    gate = read(root / GATE, problems, "NODAL-INC13-004")
    roadmap = read(root / ROADMAP, problems, "NODAL-INC13-005")
    manifest_text = read(root / MANIFEST, problems, "NODAL-INC13-006")
    if problems:
        return problems

    try:
        manifest = json.loads(manifest_text)
    except json.JSONDecodeError as exc:
        return [Problem("NODAL-INC13-007", f"invalid manifest JSON: {exc}")]

    require(
        api,
        (
            "opaque type SInt",
            "type Dimension = Int | Expr[Integer]",
            "opaque type Vec",
            "def generate(",
            "def loop(",
            "enum TargetLayout",
            "final class Mem",
            "final case class ExternalContract",
            "final class Quantity",
            "enum TemporaryPolicy",
            "enum NamingPolicy",
            "enum CheckProfile",
            "trait HwEnum",
            "def enumEncoding",
            "final class FsmDefinition",
            "def fsm[",
        ),
        problems,
        "NODAL-INC13-008",
    )
    require(
        positive,
        (
            "for currentWidth <- elaborationWidths",
            "generate(width)",
            "LoopBound.Symbolic",
            "SInt(width)",
            ".reinterpretSigned",
            "Vec(SInt(8), 2, 3, width)",
            ".flatten",
            ".reshape",
            ".map(",
            ".zip(",
            ".reduce(",
            "Valid(unsignedIn)",
            "Stream(unsignedIn)",
            "Mem(",
            "ExternalOp[UInt, UInt]",
            ".volts",
            "EmitQuality(",
            "enum ControlState derives HwEnum",
            "enumEncoding(",
            "FsmDefinition[ControlState]",
            "machine.parallel",
            "machine.boundedCallStack",
        ),
        problems,
        "NODAL-INC13-009",
    )
    require(
        external,
        (
            "package external.coresemantics",
            "import nodal.*",
            "SInt(12)",
            "Vec(SInt(12), 4)",
            "FsmDefinition[LibraryMode]",
        ),
        problems,
        "NODAL-INC13-010",
    )
    for forbidden in ("nodal.internal", "nodal.frontend", "nodal.compiler", "CandidateRuntime"):
        if forbidden in external:
            problems.append(Problem("NODAL-INC13-011", f"external fixture uses forbidden surface: {forbidden}"))

    require(
        gate,
        (
            "**Status:** Approved",
            "**Scope:** public-api",
            "**Approval boundary:** Compile-candidate evaluation only; public API v0.3 remains unfrozen",
            "**Freeze owner:** Increment 15",
            "ordinary Scala values and `for` loops are elaboration-only",
            "symbolic target-visible replication uses `generate(...)`",
            "bounded same-cycle hardware iteration",
            "Chisel-style strengths retained",
            "SpinalHDL-style strengths retained",
            "CIRCT/MLIR role",
            "NODAL-NUM-013",
            "NODAL-UNIT-013",
            "NODAL-STAGE-013",
        ),
        problems,
        "NODAL-INC13-012",
    )

    if manifest.get("increment") != 13 or manifest.get("freeze_owner") != 15:
        problems.append(Problem("NODAL-INC13-013", "manifest identity/freeze owner is invalid"))
    if manifest.get("frontend_behavior_inert") is not True or manifest.get("backend_behavior_inert") is not True:
        problems.append(Problem("NODAL-INC13-014", "candidate must keep frontend/backend behavior inert"))

    negatives = manifest.get("scala_type_negative")
    if not isinstance(negatives, list) or len(negatives) < 3:
        problems.append(Problem("NODAL-INC13-015", "at least three Scala type-negative fixtures are required"))
    else:
        for entry in negatives:
            if not isinstance(entry, dict):
                problems.append(Problem("NODAL-INC13-016", "negative fixture entry is not an object"))
                continue
            path = root / str(entry.get("path"))
            code = str(entry.get("code"))
            source = read(path, problems, "NODAL-INC13-017")
            if source.count(f"diagnostic-anchor: {code}") != 1:
                problems.append(Problem("NODAL-INC13-018", f"negative fixture lacks unique anchor: {path}"))

    if "- [x] **Increment 13 — Core semantic candidate prototypes and architecture comparison**" not in roadmap:
        problems.append(Problem("NODAL-INC13-019", "roadmap does not close Increment 13"))
    increment14_lines = [
        line
        for line in roadmap.splitlines()
        if line.startswith(("- [ ] **Increment 14 — ", "- [x] **Increment 14 — "))
    ]
    if (
        len(increment14_lines) != 1
        or "candidate prototypes and architecture comparison**" not in increment14_lines[0]
    ):
        problems.append(
            Problem(
                "NODAL-INC13-025",
                "roadmap does not retain one Increment 14 candidate-prototype increment",
            )
        )
    return problems


def run_mill(root: Path, *targets: str) -> subprocess.CompletedProcess[str]:
    wrapper = root / ("mill.bat" if os.name == "nt" else "mill")
    return subprocess.run(
        [str(wrapper), *targets],
        cwd=root,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )


def check_compile_contracts(root: Path) -> list[Problem]:
    problems: list[Problem] = []
    manifest = json.loads((root / MANIFEST).read_text(encoding="utf-8"))
    positive = run_mill(root, "examples.coreSemanticsExternal.compile", "examples.coreSemanticsApi.compile")
    if positive.returncode != 0:
        return [Problem("NODAL-INC13-020", "positive candidate compilation failed:\n" + positive.stdout[-8000:])]

    injected = root / "examples/coreSemanticsApi/src/__Increment13Negative.scala"
    if injected.exists():
        return [Problem("NODAL-INC13-021", f"refusing to overwrite {injected}")]

    try:
        for entry in manifest["scala_type_negative"]:
            source_path = root / entry["path"]
            injected.write_text(source_path.read_text(encoding="utf-8"), encoding="utf-8")
            completed = run_mill(root, "examples.coreSemanticsApi.compile")
            if completed.returncode == 0:
                problems.append(Problem("NODAL-INC13-022", f"negative fixture compiled: {entry['path']}"))
            elif injected.name not in completed.stdout:
                problems.append(Problem("NODAL-INC13-023", f"failure did not identify injected fixture: {entry['path']}"))
            injected.unlink(missing_ok=True)
    finally:
        injected.unlink(missing_ok=True)

    restored = run_mill(root, "examples.coreSemanticsApi.compile")
    if restored.returncode != 0:
        problems.append(Problem("NODAL-INC13-024", "positive module did not recover after negative fixtures:\n" + restored.stdout[-8000:]))
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--compile-negative", action="store_true")
    args = parser.parse_args(argv)

    problems = check_repository(args.root)
    if not problems and args.compile_negative:
        problems.extend(check_compile_contracts(args.root.resolve()))
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"Increment 13 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 13 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
