#!/usr/bin/env python3
"""Validate Increment 14 pipeline/interface/inout compile candidates and negative contracts."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path("tests/api/fixtures/increment14/manifest.json")
API = Path("core/scala/api/src/nodal/PipelineInterfaceCandidateApi.scala")
POSITIVE = Path("examples/interfacePipelineApi/src/PipelineInterfaceCandidates.scala")
EXTERNAL = Path("examples/interfacePipelineExternal/src/ReusableInterfaces.scala")
GATE = Path("docs/design-gates/NodalPipelineInterfaceCandidates-DG-v0.3.md")
ROADMAP = Path("docs/roadmap/nodal-development-todo.md")
BUILD = Path("build.mill")


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


def require(
    text: str,
    fragments: tuple[str, ...],
    problems: list[Problem],
    code: str,
) -> None:
    for fragment in fragments:
        if fragment not in text:
            problems.append(Problem(code, f"missing required candidate fragment: {fragment}"))


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    api = read(root / API, problems, "NODAL-INC14-001")
    positive = read(root / POSITIVE, problems, "NODAL-INC14-002")
    external = read(root / EXTERNAL, problems, "NODAL-INC14-003")
    gate = read(root / GATE, problems, "NODAL-INC14-004")
    roadmap = read(root / ROADMAP, problems, "NODAL-INC14-005")
    build = read(root / BUILD, problems, "NODAL-INC14-006")
    manifest_text = read(root / MANIFEST, problems, "NODAL-INC14-007")
    if problems:
        return problems

    try:
        manifest = json.loads(manifest_text)
    except json.JSONDecodeError as exc:
        return [Problem("NODAL-INC14-008", f"invalid manifest JSON: {exc}")]

    require(
        api,
        (
            "opaque type Struct",
            "trait Interface",
            "final class InterfaceType",
            "final class Role",
            "trait RoleInverse",
            "trait RoleConnection",
            "final class InterfacePort",
            "def interfaceArray",
            "final class DigitalInout",
            "def driveLow",
            "final class TriStateCarrier",
            "def padAdapter",
            "final class Terminal",
            "final class TerminalView",
            "final class AnalogSignal",
            "final case class BridgeContract",
            "object MixedSignalBridge",
            "final class Txn",
            "sealed trait Latency",
            "final case class PipelinePolicy",
            "def pipe",
            "def delay",
            "def stage",
            "def sameStage",
            "def inspectSchedule",
            "final class FixedLatencyOperator",
            "final class VariableLatencyOperator",
        ),
        problems,
        "NODAL-INC14-009",
    )
    require(
        positive,
        (
            'Struct(',
            "Reg(pixel)",
            "PixelLink.sourceRole",
            ".connectExact(",
            ".inverted",
            ".monitorView",
            "interfaceArray(",
            "RegisterBus.definition",
            "DriveMode.pushPull",
            "DriveMode.openDrain",
            ".driveLow(",
            ".highZ()",
            ".split",
            "passThrough(",
            "padAdapter(",
            "InoutPlacement.InternalResolvedNet",
            ".connectView.connectTo(",
            ".senseView.potential",
            ".contributeView.contribute(",
            "AnalogSignal.source[VoltageDimension]",
            "MixedSignalBridge.sample(",
            "ConservativeSignalBridge.senseToSignal(",
            "pipe(transaction, autoPolicy)",
            "pipe(validInput, exactPolicy)",
            "pipe(streamInput, rangedPolicy)",
            "Latency.Auto",
            "Latency.Exact",
            "Latency.Range",
            "ReadyPath.Registered",
            "ParameterEnvelope(",
            "stage(",
            "sameStage:",
            ".delay(",
            "inspectSchedule(",
            "FixedLatencyOperator",
            "VariableLatencyOperator",
        ),
        problems,
        "NODAL-INC14-010",
    )
    require(
        external,
        (
            "package external.interfacepipeline",
            "import nodal.*",
            "sealed trait RegisterBus extends Interface",
            "Interface[RegisterBus]",
            "Role[InitiatorRole]",
            "Role[TargetRole]",
            "interfacePort(",
            ".connectExact(",
            "Txn(LibraryRequest(",
            "pipe(",
            "PipelinePolicy(",
        ),
        problems,
        "NODAL-INC14-011",
    )
    for forbidden in (
        "nodal.internal",
        "nodal.frontend",
        "nodal.compiler",
        "nodal.scheduler",
        "CandidateRuntime",
    ):
        if forbidden in external:
            problems.append(
                Problem("NODAL-INC14-012", f"external fixture uses forbidden surface: {forbidden}")
            )

    require(
        gate,
        (
            "**Status:** Approved",
            "**Scope:** public-api",
            "**Approval boundary:** Compile-candidate evaluation only; public API v0.3 remains unfrozen",
            "**Freeze owner:** Increment 15",
            "ADR 0008",
            "ADR 0021",
            "Directionless values versus connectivity",
            "Named roles, protocols, and exact connection",
            "First-class digital inout",
            "Conservative AMS and directional signal flow",
            "Automatic pipeline",
            "Chisel-style strengths retained",
            "SpinalHDL-style strengths retained",
            "SystemVerilog role",
            "CIRCT/MLIR role",
            "NODAL-IFACE-014",
            "NODAL-ROLE-014",
            "NODAL-INVERT-014",
            "NODAL-MONITOR-014",
            "NODAL-INOUT-014",
            "NODAL-AMS-014",
            "NODAL-PROTOCOL-014",
        ),
        problems,
        "NODAL-INC14-013",
    )
    require(
        build,
        (
            "object interfacePipelineExternal extends NodalScalaModule",
            "object interfacePipelineApi extends NodalScalaModule",
            "def moduleDeps = Seq(core.scala.api, interfacePipelineExternal)",
        ),
        problems,
        "NODAL-INC14-014",
    )

    expected_architecture = {
        "docs/architecture/0008-automatic-pipeline-architecture.md",
        "docs/architecture/0021-unified-struct-interface-role-and-inout-architecture.md",
    }
    expected_surfaces = {
        "docs/roadmap/automatic-pipeline-api-v0.3-surface.json",
        "docs/roadmap/interface-role-inout-ams-v0.1-surface.json",
    }
    if manifest.get("increment") != 14 or manifest.get("freeze_owner") != 15:
        problems.append(Problem("NODAL-INC14-015", "manifest identity/freeze owner is invalid"))
    if set(manifest.get("architecture", [])) != expected_architecture:
        problems.append(Problem("NODAL-INC14-016", "manifest architecture set is invalid"))
    if set(manifest.get("candidate_surfaces", [])) != expected_surfaces:
        problems.append(Problem("NODAL-INC14-017", "manifest candidate surface set is invalid"))

    inert_keys = (
        "frontend_behavior_inert",
        "scheduler_behavior_inert",
        "interface_ir_behavior_inert",
        "resolution_topology_behavior_inert",
        "backend_behavior_inert",
        "simulator_behavior_inert",
    )
    for key in inert_keys:
        if manifest.get(key) is not True:
            problems.append(Problem("NODAL-INC14-018", f"manifest must keep {key} true"))

    positives = manifest.get("positive_modules")
    if positives != [
        "examples.interfacePipelineExternal.compile",
        "examples.interfacePipelineApi.compile",
    ]:
        problems.append(Problem("NODAL-INC14-019", "positive module list is invalid"))

    negatives = manifest.get("scala_type_negative")
    if not isinstance(negatives, list) or len(negatives) < 7:
        problems.append(Problem("NODAL-INC14-020", "at least seven Scala type-negative fixtures are required"))
    else:
        seen_codes: set[str] = set()
        for entry in negatives:
            if not isinstance(entry, dict):
                problems.append(Problem("NODAL-INC14-021", "negative fixture entry is not an object"))
                continue
            path = root / str(entry.get("path"))
            code = str(entry.get("code"))
            source_text = read(path, problems, "NODAL-INC14-022")
            if source_text.count(f"diagnostic-anchor: {code}") != 1:
                problems.append(
                    Problem("NODAL-INC14-023", f"negative fixture lacks unique anchor: {path}")
                )
            if code in seen_codes:
                problems.append(Problem("NODAL-INC14-024", f"duplicate diagnostic code: {code}"))
            seen_codes.add(code)

    semantic_contracts = manifest.get("semantic_contracts")
    if not isinstance(semantic_contracts, list) or len(semantic_contracts) < 20:
        problems.append(
            Problem("NODAL-INC14-025", "semantic-negative contract inventory is incomplete")
        )

    validation = manifest.get("validation")
    if not isinstance(validation, dict):
        problems.append(Problem("NODAL-INC14-026", "manifest validation section is invalid"))
        final_evidence = False
    else:
        pull_request = validation.get("pull_request")
        dedicated_run = validation.get("dedicated_workflow_run")
        final_evidence = isinstance(pull_request, int) and isinstance(dedicated_run, int)
        if not final_evidence and (pull_request is not None or dedicated_run is not None):
            problems.append(
                Problem(
                    "NODAL-INC14-026",
                    "manifest validation evidence must be either entirely pending or entirely recorded",
                )
            )

    checked_increment14 = (
        "- [x] **Increment 14 — Automatic pipeline, Interface/Role, and inout candidate prototypes and architecture comparison**"
        in roadmap
    )
    unchecked_increment14 = (
        "- [ ] **Increment 14 — Automatic pipeline, Interface/Role, and inout candidate prototypes and architecture comparison**"
        in roadmap
    )
    if final_evidence and not checked_increment14:
        problems.append(Problem("NODAL-INC14-033", "roadmap does not close Increment 14"))
    if not final_evidence and not unchecked_increment14:
        problems.append(
            Problem(
                "NODAL-INC14-034",
                "preflight roadmap must leave Increment 14 unchecked until validation evidence is recorded",
            )
        )
    increment15_lines = [
        line for line in roadmap.splitlines() if line.startswith("- [ ] **Increment 15 — ")
    ]
    if len(increment15_lines) != 1 or "public API v0.3 freeze**" not in increment15_lines[0]:
        problems.append(
            Problem(
                "NODAL-INC14-027",
                "roadmap does not leave one unchecked Increment 15 public API freeze",
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
    positive = run_mill(
        root,
        "examples.interfacePipelineExternal.compile",
        "examples.interfacePipelineApi.compile",
    )
    if positive.returncode != 0:
        return [
            Problem(
                "NODAL-INC14-028",
                "positive candidate compilation failed:\n" + positive.stdout[-12000:],
            )
        ]

    injected = root / "examples/interfacePipelineApi/src/__Increment14Negative.scala"
    if injected.exists():
        return [Problem("NODAL-INC14-029", f"refusing to overwrite {injected}")]

    try:
        for entry in manifest["scala_type_negative"]:
            source_path = root / entry["path"]
            injected.write_text(source_path.read_text(encoding="utf-8"), encoding="utf-8")
            completed = run_mill(root, "examples.interfacePipelineApi.compile")
            if completed.returncode == 0:
                problems.append(
                    Problem("NODAL-INC14-030", f"negative fixture compiled: {entry['path']}")
                )
            elif injected.name not in completed.stdout:
                problems.append(
                    Problem(
                        "NODAL-INC14-031",
                        f"failure did not identify injected fixture: {entry['path']}",
                    )
                )
            injected.unlink(missing_ok=True)
    finally:
        injected.unlink(missing_ok=True)

    restored = run_mill(root, "examples.interfacePipelineApi.compile")
    if restored.returncode != 0:
        problems.append(
            Problem(
                "NODAL-INC14-032",
                "positive module did not recover after negative fixtures:\n" + restored.stdout[-12000:],
            )
        )
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
        print(f"Increment 14 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 14 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
