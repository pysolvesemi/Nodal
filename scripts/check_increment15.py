#!/usr/bin/env python3
"""Validate Increment 15's unified Nodal public API v0.3 freeze."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
API_MANIFEST = Path("core/scala/api/public-api-v0.3.json")
DIAGNOSTICS = Path("core/scala/api/public-api-diagnostics-v0.3.json")
FIXTURES = Path("tests/api/fixtures/increment15/manifest.json")
GATE = Path("docs/design-gates/NodalCoreSemanticsPipelineApi-DG-v0.3.md")
ROADMAP = Path("docs/roadmap/nodal-development-todo.md")
COMPILER_API = Path("core/scala/api/src/nodal/CompilerApi.scala")
CORE_API = Path("core/scala/api/src/nodal/CoreSemanticsCandidateApi.scala")
INTERFACE_API = Path("core/scala/api/src/nodal/PipelineInterfaceCandidateApi.scala")
POSITIVE = Path("examples/publicApiV03/src/contracts/v03/UnifiedV03.scala")
EXTERNAL = Path("examples/publicApiV03External/src/external/v03/ReusableV03.scala")
MIGRATION = Path("examples/publicApiV03Migration/src/contracts/v03migration/V01V02Migration.scala")


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


def read_json(path: Path, problems: list[Problem], code: str) -> dict[str, Any]:
    content = read(path, problems, code)
    try:
        value = json.loads(content)
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


def nested(value: dict[str, Any], *keys: str) -> Any:
    current: Any = value
    for key in keys:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


def expected_files(fixtures: dict[str, Any]) -> list[str]:
    files = [
        str(API_MANIFEST),
        str(DIAGNOSTICS),
        str(FIXTURES),
        str(GATE),
        str(ROADMAP),
        str(COMPILER_API),
        str(CORE_API),
        str(INTERFACE_API),
        str(POSITIVE),
        str(EXTERNAL),
        str(MIGRATION),
        "docs/migrations/public-api-v0.1-to-v0.3.md",
        "docs/migrations/public-api-v0.2-to-v0.3.md",
        "docs/language-reference/public-api-v0.3.md",
        "scripts/materialize_increment15.py",
        "scripts/check_increment15.py",
        "tests/api/test_increment15.py",
        ".github/workflows/increment-15-unified-api-freeze.yml",
    ]
    for key in ("scala_type_negative", "semantic_negative"):
        entries = fixtures.get(key)
        if isinstance(entries, list):
            files.extend(str(entry.get("path")) for entry in entries if isinstance(entry, dict))
    return files


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    fixtures = read_json(root / FIXTURES, problems, "NODAL-INC15-001")
    for relative in expected_files(fixtures):
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-INC15-002", f"missing Increment 15 file: {relative}"))

    manifest = read_json(root / API_MANIFEST, problems, "NODAL-INC15-003")
    diagnostics = read_json(root / DIAGNOSTICS, problems, "NODAL-INC15-004")
    compiler = read(root / COMPILER_API, problems, "NODAL-INC15-005")
    core = read(root / CORE_API, problems, "NODAL-INC15-006")
    interface = read(root / INTERFACE_API, problems, "NODAL-INC15-007")
    positive = read(root / POSITIVE, problems, "NODAL-INC15-008")
    external = read(root / EXTERNAL, problems, "NODAL-INC15-009")
    migration_fixture = read(root / MIGRATION, problems, "NODAL-INC15-010")
    gate = read(root / GATE, problems, "NODAL-INC15-011")
    roadmap = read(root / ROADMAP, problems, "NODAL-INC15-012")
    build = read(root / "build.mill", problems, "NODAL-INC15-013")
    migration_v01 = read(
        root / "docs/migrations/public-api-v0.1-to-v0.3.md",
        problems,
        "NODAL-INC15-014",
    )
    migration_v02 = read(
        root / "docs/migrations/public-api-v0.2-to-v0.3.md",
        problems,
        "NODAL-INC15-015",
    )
    reference = read(
        root / "docs/language-reference/public-api-v0.3.md",
        problems,
        "NODAL-INC15-016",
    )
    predecessor = read(root / "scripts/check_increment14.py", problems, "NODAL-INC15-017")

    validation = fixtures.get("validation")
    final_evidence = (
        isinstance(validation, dict)
        and isinstance(validation.get("pull_request"), int)
        and isinstance(validation.get("dedicated_workflow_run"), int)
    )

    require(
        compiler,
        (
            "enum Backend:",
            "case Auto, Verilog",
            "case VerilogA, VerilogAMS",
            "enum DesignKind:",
            "case DigitalOnly, AnalogOnly, MixedSignal, Unsupported",
            "enum DigitalProfile:",
            "backend: Backend = Backend.Auto",
            "final case class SourceSpan",
            "final case class SourceMapEntry",
            "final case class InterfaceAbiEntry",
            "final case class DesignReport",
            "interfaceAbi: Vector[InterfaceAbiEntry]",
            "sourceMap: Vector[SourceMapEntry]",
            "schedules: Vector[ScheduleInspection]",
            "object Nodal:",
        ),
        problems,
        "NODAL-INC15-018",
        "compiler API",
    )
    require(
        core,
        (
            "opaque type SInt",
            "type Dimension = Int | Expr[Integer]",
            "opaque type Vec",
            "def generate(",
            "def loop(",
            "final class Mem",
            "final case class ExternalContract",
            "final class Quantity",
            "enum TemporaryPolicy",
            "enum CheckProfile",
            "trait HwEnum",
            "final class FsmDefinition",
        ),
        problems,
        "NODAL-INC15-019",
        "core semantic API",
    )
    require(
        interface,
        (
            "opaque type Struct",
            "trait Interface",
            "final class Role",
            "final class InterfacePort",
            "def interfaceArray",
            "def connectExact",
            "final class DigitalInout",
            "final class Terminal",
            "final class AnalogSignal",
            "object MixedSignalBridge",
            "final class Txn",
            "final case class PipelinePolicy",
            "def pipe",
            "def delay",
            "def inspectSchedule",
        ),
        problems,
        "NODAL-INC15-020",
        "Interface and pipeline API",
    )
    if final_evidence:
        if "opaque type Aggregate" in core or "AggregateField" in core:
            problems.append(
                Problem("NODAL-INC15-021", "final v0.3 source still exposes rejected Aggregate spelling")
            )
        existing_core_fixture = read(
            root / "examples/coreSemanticsApi/src/CoreSemanticsCandidates.scala",
            problems,
            "NODAL-INC15-022",
        )
        if "Aggregate(" in existing_core_fixture or "AggregateField(" in existing_core_fixture:
            problems.append(
                Problem("NODAL-INC15-023", "Increment 13 positive fixture was not migrated to Struct")
            )
        if "Struct(" not in existing_core_fixture:
            problems.append(
                Problem("NODAL-INC15-024", "Increment 13 positive fixture lacks selected Struct spelling")
            )

    expected_manifest = {
        ("schema",): 1,
        ("api_version",): "0.3",
        ("status",): "frozen",
        ("supersedes",): "0.2",
        ("default_import",): "import nodal.*",
        ("values_and_connectivity", "value_aggregate"): "Struct",
        ("values_and_connectivity", "connectivity_aggregate"): "Interface",
        ("values_and_connectivity", "direct_connection"): "connectExact",
        ("values_and_connectivity", "adaptation"): "explicit user-authored Module boundary",
        ("pipeline", "dynamic_values_must_enter_transaction"): True,
        ("compiler", "default_backend"): "Backend.Auto",
        ("compiler", "systemverilog_backend_public"): False,
        ("compatibility", "v0.3_default_backend"): "Backend.Auto",
        ("implementation_status", "elaboration_implemented"): False,
        ("implementation_status", "scheduler_implemented"): False,
        ("implementation_status", "interface_ir_implemented"): False,
        ("implementation_status", "digital_backend_implemented"): False,
        ("implementation_status", "ams_backend_implemented"): False,
        ("implementation_status", "simulator_implemented"): False,
        ("next_implementation_increment",): 16,
    }
    for path, expected in expected_manifest.items():
        value = nested(manifest, *path)
        if value != expected:
            problems.append(
                Problem(
                    "NODAL-INC15-025",
                    f"manifest {'.'.join(path)} is {value!r}, expected {expected!r}",
                )
            )
    removed = nested(manifest, "values_and_connectivity", "removed_candidate_symbols")
    for symbol in ("Aggregate", "AggregateField", "Flow", "via", "viewAs"):
        if not isinstance(removed, list) or symbol not in removed:
            problems.append(Problem("NODAL-INC15-026", f"manifest does not reject candidate: {symbol}"))

    if diagnostics.get("schema") != 1 or diagnostics.get("api_version") != "0.3":
        problems.append(Problem("NODAL-INC15-027", "diagnostic manifest identity is invalid"))
    if nested(diagnostics, "source_location", "required") is not True:
        problems.append(Problem("NODAL-INC15-028", "diagnostics must require source locations"))
    if nested(diagnostics, "source_location", "fields") != ["path", "line", "column", "span"]:
        problems.append(Problem("NODAL-INC15-029", "diagnostic source-location fields are invalid"))

    diagnostic_entries = diagnostics.get("diagnostics")
    diagnostic_codes: list[str] = []
    if not isinstance(diagnostic_entries, list):
        problems.append(Problem("NODAL-INC15-030", "diagnostic inventory must be a list"))
    else:
        for entry in diagnostic_entries:
            if not isinstance(entry, dict):
                problems.append(Problem("NODAL-INC15-031", "diagnostic entry is not an object"))
                continue
            code = entry.get("code")
            if isinstance(code, str):
                diagnostic_codes.append(code)
            if entry.get("severity") != "error":
                problems.append(Problem("NODAL-INC15-032", f"diagnostic {code!r} is not an error"))
            for field in ("name", "phase", "message", "suggestion"):
                if not isinstance(entry.get(field), str) or not entry[field]:
                    problems.append(Problem("NODAL-INC15-033", f"diagnostic {code!r} lacks {field}"))
    if len(diagnostic_codes) != len(set(diagnostic_codes)):
        problems.append(Problem("NODAL-INC15-034", "diagnostic codes are not unique"))

    if fixtures.get("schema") != 1 or fixtures.get("increment") != 15:
        problems.append(Problem("NODAL-INC15-035", "fixture manifest identity is invalid"))
    if fixtures.get("api_version") != "0.3" or fixtures.get("next_increment") != 16:
        problems.append(Problem("NODAL-INC15-036", "fixture manifest version/next increment is invalid"))
    for inert in (
        "frontend_behavior_inert",
        "scheduler_behavior_inert",
        "interface_ir_behavior_inert",
        "resolution_topology_behavior_inert",
        "backend_behavior_inert",
        "simulator_behavior_inert",
    ):
        if fixtures.get(inert) is not True:
            problems.append(Problem("NODAL-INC15-037", f"fixture manifest must keep {inert} true"))

    fixture_codes: list[str] = []
    for key, minimum, mode in (
        ("scala_type_negative", 10, "scala-type-rejected"),
        ("semantic_negative", 12, "semantic-contract"),
    ):
        entries = fixtures.get(key)
        if not isinstance(entries, list) or len(entries) < minimum:
            problems.append(Problem("NODAL-INC15-038", f"{key} inventory is incomplete"))
            continue
        for entry in entries:
            if not isinstance(entry, dict):
                problems.append(Problem("NODAL-INC15-039", f"{key} entry is not an object"))
                continue
            path = root / str(entry.get("path"))
            code = str(entry.get("code"))
            fixture_codes.append(code)
            if entry.get("mode") != mode:
                problems.append(Problem("NODAL-INC15-040", f"fixture {path} has wrong mode"))
            source = read(path, problems, "NODAL-INC15-041")
            if source.count(f"diagnostic-anchor: {code}") != 1:
                problems.append(Problem("NODAL-INC15-042", f"fixture lacks unique anchor: {path}"))
    if fixture_codes != diagnostic_codes:
        problems.append(
            Problem(
                "NODAL-INC15-043",
                f"fixture diagnostic order {fixture_codes!r} differs from manifest {diagnostic_codes!r}",
            )
        )

    require(
        positive,
        (
            "Struct(",
            "SInt(width)",
            "Vec(SInt(8), 2, 4, width)",
            "generate(width)",
            "LoopBound.Symbolic",
            "Mem(",
            "ExternalOp[UInt, UInt]",
            "enum UnifiedState derives HwEnum",
            "FsmDefinition[UnifiedState]",
            "LocalLink.sourceRole",
            ".connectExact(",
            ".inverted",
            "invertedPixelAccess" if False else "inverseAccess",
            "DriveMode.openDrain",
            ".driveLow(",
            "MixedSignalBridge.sample(",
            "pipe(transaction, policy)",
            "pipe(Valid(unsignedIn), policy)",
            "pipe(streamInput, policy)",
            "DesignKind.MixedSignal",
            "Backend.Auto",
            "DigitalProfile.Synthesis",
            "InterfaceAbiEntry(",
            "SourceMapEntry(",
            "schedules = Vector(schedule)",
            "ExplicitV03Adapter",
        ),
        problems,
        "NODAL-INC15-044",
        "unified positive fixture",
    )
    for forbidden_capture in ("stage(payload + b)", "payload + c"):
        if forbidden_capture in positive:
            problems.append(
                Problem("NODAL-INC15-045", f"positive pipeline captures live signal: {forbidden_capture}")
            )

    require(
        external,
        (
            "package external.v03",
            "import nodal.*",
            "sealed trait UnifiedLink extends Interface",
            "Role[SourceRole]",
            "Role[SinkRole]",
            "interfacePort(",
            ".connectExact(",
            "Txn(ExternalRequest(",
            "PipelinePolicy(latency = Latency.Exact(2))",
            "final class ExplicitV03Adapter extends Module",
        ),
        problems,
        "NODAL-INC15-046",
        "external v0.3 fixture",
    )
    for forbidden in (
        "nodal.internal",
        "nodal.frontend",
        "nodal.compiler",
        "nodal.scheduler",
        "CandidateRuntime",
        "Nodal.emit",
        "EmitOptions",
    ):
        if forbidden in external:
            problems.append(Problem("NODAL-INC15-047", f"external fixture uses excluded surface: {forbidden}"))

    require(
        migration_fixture,
        (
            "final class MigratedV01Analog extends Module",
            "analog:",
            "final class MigratedV02Clocked extends Module",
            "ClockDomain.required",
            "Reg(0.U(8))",
            "Cdc.sync",
            "EmitOptions(backend = Backend.VerilogAMS)",
            "val v03AutomaticBackend = EmitOptions()",
            "backend = Backend.Verilog",
        ),
        problems,
        "NODAL-INC15-048",
        "migration compile fixture",
    )
    require(
        migration_v01 + migration_v02,
        (
            "Backend.Auto",
            "Backend.VerilogAMS",
            "ClockDomain",
            "Struct",
            "Interface",
            "pipe",
            "ordinary synchronous always",
        ),
        problems,
        "NODAL-INC15-049",
        "migration notes",
    )
    require(
        reference,
        (
            "import nodal.*",
            "ClockDomain.required",
            "Struct",
            "Interface",
            "connectExact",
            "pipe(",
            "DigitalProfile.Synthesis",
            "InterfaceLayout.PortableFlattened",
            "Native SystemVerilog is not a",
        ),
        problems,
        "NODAL-INC15-050",
        "v0.3 language reference",
    )
    require(
        gate,
        (
            "**Status:** Approved",
            "**Scope:** public-api",
            "**API version:** 0.3",
            "Values are explicit and lossless",
            "`Struct` is the sole frozen directionless value aggregate",
            "v0.3 deliberately does not freeze",
            "Every dynamic value used by a transform must enter through its transaction",
            "Backend.Auto",
            "`Backend.SystemVerilog` is not public v0.3 API",
            "All implementation behavior remains inert",
            "Increment 16",
        ),
        problems,
        "NODAL-INC15-051",
        "unified design gate",
    )
    require(
        build,
        (
            "object publicApiV03External extends NodalScalaModule",
            "object publicApiV03 extends NodalScalaModule",
            "def moduleDeps = Seq(core.scala.api, publicApiV03External)",
            "object publicApiV03Migration extends NodalScalaModule",
        ),
        problems,
        "NODAL-INC15-052",
        "Mill build",
    )

    v01 = read_json(root / "core/scala/api/public-api-v0.1.json", problems, "NODAL-INC15-053")
    v02 = read_json(root / "core/scala/api/public-api-v0.2.json", problems, "NODAL-INC15-054")
    if v01.get("api_version") != "0.1" or v01.get("status") != "frozen":
        problems.append(Problem("NODAL-INC15-055", "v0.1 historical manifest changed identity"))
    if v02.get("api_version") != "0.2" or v02.get("status") != "frozen":
        problems.append(Problem("NODAL-INC15-056", "v0.2 historical manifest changed identity"))

    if final_evidence:
        checked = (
            "- [x] **Increment 15 — Unified core semantics, Interface/Role/inout, and automatic pipeline public API v0.3 freeze**"
            in roadmap
        )
        if not checked:
            problems.append(Problem("NODAL-INC15-057", "roadmap does not close Increment 15"))
        pull_request = validation["pull_request"]
        run_id = validation["dedicated_workflow_run"]
        if f"PR [#{pull_request}]" not in roadmap or f"[{run_id}]" not in roadmap:
            problems.append(Problem("NODAL-INC15-058", "roadmap lacks final PR/workflow evidence"))
        if "**Revision:** 1.19" not in roadmap:
            problems.append(Problem("NODAL-INC15-059", "roadmap revision is not 1.19"))
        if fixtures.get("status") != "validated-freeze":
            problems.append(Problem("NODAL-INC15-060", "fixture manifest is not finalized"))
        if 'line.startswith(("- [ ] **Increment 15 — ", "- [x] **Increment 15 — "))' not in predecessor:
            problems.append(Problem("NODAL-INC15-061", "Increment 14 checker is not successor-safe"))
    else:
        unchecked = (
            "- [ ] **Increment 15 — Unified core semantics, Interface/Role/inout, and automatic pipeline public API v0.3 freeze**"
            in roadmap
        )
        if not unchecked:
            problems.append(Problem("NODAL-INC15-062", "preflight roadmap must leave Increment 15 unchecked"))
        if not isinstance(validation, dict) or validation != {
            "pull_request": None,
            "dedicated_workflow_run": None,
        }:
            problems.append(Problem("NODAL-INC15-063", "preflight validation evidence is malformed"))
    increment16 = [
        line
        for line in roadmap.splitlines()
        if line.startswith(("- [ ] **Increment 16 — ", "- [x] **Increment 16 — "))
    ]
    if len(increment16) != 1 or "kernel" not in increment16[0].lower():
        problems.append(Problem("NODAL-INC15-064", "roadmap does not retain one Increment 16 kernel"))
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
    fixtures = json.loads((root / FIXTURES).read_text(encoding="utf-8"))
    positive = run_mill(
        root,
        "examples.publicApiV03External.compile",
        "examples.publicApiV03.compile",
        "examples.publicApiV03Migration.compile",
    )
    if positive.returncode != 0:
        return [
            Problem(
                "NODAL-INC15-065",
                "positive v0.3 compilation failed:\n" + positive.stdout[-16000:],
            )
        ]

    injected = root / "examples/publicApiV03/src/__Increment15Negative.scala"
    if injected.exists():
        return [Problem("NODAL-INC15-066", f"refusing to overwrite {injected}")]
    try:
        for entry in fixtures["scala_type_negative"]:
            source_path = root / entry["path"]
            injected.write_text(source_path.read_text(encoding="utf-8"), encoding="utf-8")
            completed = run_mill(root, "examples.publicApiV03.compile")
            if completed.returncode == 0:
                problems.append(Problem("NODAL-INC15-067", f"negative fixture compiled: {entry['path']}"))
            elif injected.name not in completed.stdout:
                problems.append(
                    Problem(
                        "NODAL-INC15-068",
                        f"failure did not identify injected fixture: {entry['path']}",
                    )
                )
            injected.unlink(missing_ok=True)
    finally:
        injected.unlink(missing_ok=True)

    restored = run_mill(root, "examples.publicApiV03.compile")
    if restored.returncode != 0:
        problems.append(
            Problem(
                "NODAL-INC15-069",
                "positive v0.3 module did not recover after negative fixtures:\n"
                + restored.stdout[-16000:],
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
        print(f"Increment 15 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 15 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
