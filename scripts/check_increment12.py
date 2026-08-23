#!/usr/bin/env python3
"""Validate the Nodal clock/reset public API v0.2 freeze and contract fixtures."""

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
FIXTURE_MANIFEST = "tests/api/fixtures/increment12/manifest.json"
POSITIVE_FIXTURES = (
    "examples/clockResetApi/src/contracts/clockreset/positive/DomainStateFixtures.scala",
    "examples/clockResetApi/src/contracts/clockreset/positive/HierarchyAndGeneratedFixtures.scala",
    "examples/clockResetApi/src/contracts/clockreset/positive/CrossingAndClockStructureFixtures.scala",
    "examples/clockResetApi/src/contracts/clockreset/positive/AnalogEventSeparationFixtures.scala",
    "examples/externalLibrary/src/external/reuse/ClockedRegister.scala",
)
NEGATIVE_FIXTURES = (
    (
        "tests/api/fixtures/increment12/negative/MissingDomain.scala",
        "NODAL-DOMAIN-001",
        "semantic-contract",
    ),
    (
        "tests/api/fixtures/increment12/negative/DirectAsyncSampling.scala",
        "NODAL-CDC-001",
        "semantic-contract",
    ),
    (
        "tests/api/fixtures/increment12/negative/MultiBitSync.scala",
        "NODAL-CDC-002",
        "scala-type-rejected",
    ),
    (
        "tests/api/fixtures/increment12/negative/UnsafePulseTransfer.scala",
        "NODAL-CDC-003",
        "scala-type-rejected",
    ),
    (
        "tests/api/fixtures/increment12/negative/UnreportedRelationship.scala",
        "NODAL-RELATION-001",
        "semantic-contract",
    ),
    (
        "tests/api/fixtures/increment12/negative/UnsynchronizedResetRelease.scala",
        "NODAL-RDC-001",
        "semantic-contract",
    ),
    (
        "tests/api/fixtures/increment12/negative/ResetReconvergence.scala",
        "NODAL-RDC-002",
        "semantic-contract",
    ),
    (
        "tests/api/fixtures/increment12/negative/BooleanClock.scala",
        "NODAL-CLOCK-001",
        "scala-type-rejected",
    ),
    (
        "tests/api/fixtures/increment12/negative/OrdinaryAlways.scala",
        "NODAL-MIGRATION-001",
        "scala-type-rejected",
    ),
    (
        "tests/api/fixtures/increment12/negative/LowLevelState.scala",
        "NODAL-LOWLEVEL-001",
        "semantic-contract",
    ),
    (
        "tests/api/fixtures/increment12/negative/MultipleStateDrivers.scala",
        "NODAL-STATE-001",
        "semantic-contract",
    ),
)
EXPECTED_DIAGNOSTIC_CODES = tuple(item[1] for item in NEGATIVE_FIXTURES)
TYPE_REJECTED_CODES = {
    code for _path, code, mode in NEGATIVE_FIXTURES if mode == "scala-type-rejected"
}
EXPECTED_FILES = (
    "build.mill",
    "core/scala/api/src/nodal/CandidateApi.scala",
    "core/scala/api/public-api-v0.1.json",
    "core/scala/api/public-api-v0.2.json",
    "core/scala/api/clock-reset-diagnostics-v0.2.json",
    "docs/architecture/0007-implicit-clock-reset-domains.md",
    "docs/design-gates/NodalPublicApi-DG-v0.1.md",
    "docs/design-gates/NodalClockResetApi-DG-v0.2.md",
    "docs/language-reference/clock-reset-api-v0.2.md",
    "docs/migrations/public-api-v0.1-to-v0.2.md",
    "docs/roadmap/clock-reset-api-v0.2-plan.md",
    "docs/roadmap/clock-reset-api-v0.2-surface.json",
    "docs/roadmap/nodal-development-todo.md",
    "examples/publicApiCandidates/src/prototypes/candidates/MixedSignalCandidates.scala",
    *POSITIVE_FIXTURES,
    FIXTURE_MANIFEST,
    *(path for path, _code, _mode in NEGATIVE_FIXTURES),
    "scripts/check_increment12.py",
    "tests/api/test_increment12.py",
    ".github/workflows/increment-12-clock-reset-api-freeze.yml",
)


@dataclass(frozen=True)
class Problem:
    code: str
    message: str

    def __str__(self) -> str:
        return f"{self.code}: {self.message}"


def _read(path: Path, problems: list[Problem], code: str) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        problems.append(Problem(code, f"cannot read {path}: {exc}"))
        return ""


def _json(path: Path, problems: list[Problem], code: str) -> dict[str, Any]:
    content = _read(path, problems, code)
    try:
        value = json.loads(content)
    except json.JSONDecodeError as exc:
        problems.append(Problem(code, f"invalid JSON in {path}: {exc}"))
        return {}
    if not isinstance(value, dict):
        problems.append(Problem(code, f"{path} must contain a JSON object"))
        return {}
    return value


def _require(
    content: str,
    fragments: tuple[str, ...],
    problems: list[Problem],
    code: str,
    subject: str,
) -> None:
    for fragment in fragments:
        if fragment not in content:
            problems.append(Problem(code, f"{subject} lacks: {fragment}"))


def _nested(value: dict[str, Any], *keys: str) -> Any:
    current: Any = value
    for key in keys:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


def _negative_entries(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    entries = manifest.get("negative")
    if not isinstance(entries, list):
        return []
    return [entry for entry in entries if isinstance(entry, dict)]


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    for relative in EXPECTED_FILES:
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-INC12-001", f"missing Increment 12 file: {relative}"))

    api = _read(
        root / "core/scala/api/src/nodal/CandidateApi.scala",
        problems,
        "NODAL-INC12-002",
    )
    manifest = _json(
        root / "core/scala/api/public-api-v0.2.json",
        problems,
        "NODAL-INC12-003",
    )
    diagnostics = _json(
        root / "core/scala/api/clock-reset-diagnostics-v0.2.json",
        problems,
        "NODAL-INC12-004",
    )
    fixtures = _json(root / FIXTURE_MANIFEST, problems, "NODAL-INC12-005")
    surface = _json(
        root / "docs/roadmap/clock-reset-api-v0.2-surface.json",
        problems,
        "NODAL-INC12-006",
    )
    gate = _read(
        root / "docs/design-gates/NodalClockResetApi-DG-v0.2.md",
        problems,
        "NODAL-INC12-007",
    )
    migration = _read(
        root / "docs/migrations/public-api-v0.1-to-v0.2.md",
        problems,
        "NODAL-INC12-008",
    )
    reference = _read(
        root / "docs/language-reference/clock-reset-api-v0.2.md",
        problems,
        "NODAL-INC12-009",
    )
    plan = _read(
        root / "docs/roadmap/clock-reset-api-v0.2-plan.md",
        problems,
        "NODAL-INC12-010",
    )
    roadmap = _read(
        root / "docs/roadmap/nodal-development-todo.md",
        problems,
        "NODAL-INC12-011",
    )
    build = _read(root / "build.mill", problems, "NODAL-INC12-012")
    mixed = _read(
        root
        / "examples/publicApiCandidates/src/prototypes/candidates/MixedSignalCandidates.scala",
        problems,
        "NODAL-INC12-013",
    )
    external = _read(
        root / "examples/externalLibrary/src/external/reuse/ClockedRegister.scala",
        problems,
        "NODAL-INC12-014",
    )
    command = _read(root / "scripts/nodal.py", problems, "NODAL-INC12-015")
    command_check = _read(
        root / "scripts/check_developer_commands.py",
        problems,
        "NODAL-INC12-016",
    )
    command_tests = _read(
        root / "tests/developer/test_developer_commands.py",
        problems,
        "NODAL-INC12-017",
    )
    workflow = _read(
        root / ".github/workflows/increment-12-clock-reset-api-freeze.yml",
        problems,
        "NODAL-INC12-018",
    )
    codeowners = _read(root / ".github/CODEOWNERS", problems, "NODAL-INC12-019")

    _require(
        api,
        (
            "sealed trait Clock extends Data",
            "sealed trait Reset extends Data",
            "case object Clock extends DataType[Clock]",
            "case object Reset extends DataType[Reset]",
            "enum ClockEdge:",
            "enum ResetPolarity:",
            "sealed trait ResetPolicy",
            "sealed trait ClockRelation",
            "final class ClockDomain",
            "def external(",
            "def from(",
            "def required(",
            "def generated(",
            "def domain(domain: ClockDomain)",
            "def domain(select: M => ClockDomain, domain: ClockDomain)",
            "object Reg:",
            "object RegNext:",
            "def when(condition: Expr[Bool])",
            "def elsewhen(condition: Expr[Bool])",
            "def otherwise(body: => Unit)",
            "object Cdc:",
            "def sync(bit: Expr[Bool]",
            "def gray[A <: Data]",
            "def pulse(pulse: Pulse",
            "def handshake[A <: Data]",
            "def fifo[A <: Data]",
            "def waive[A <: Data]",
            "object Rdc:",
            "object ResetController:",
            "object ClockGate:",
            "object ClockMux:",
            "object lowlevel:",
            "def process(event: Event)",
        ),
        problems,
        "NODAL-INC12-020",
        "clock/reset API",
    )
    for forbidden in (
        "def always(body:",
        "def always(event:",
        "sealed trait Clock extends Bool",
        "sealed trait Reset extends Bool",
        "setSynchronousWith",
    ):
        if forbidden in api:
            problems.append(
                Problem("NODAL-INC12-021", f"v0.2 API contains rejected form: {forbidden}")
            )

    expected_manifest = {
        ("schema",): 1,
        ("api_version",): "0.2",
        ("status",): "frozen",
        ("supersedes",): "0.1",
        ("default_import",): "import nodal.*",
        ("clock_reset", "architecture"):
            "implicit-local-domain-explicit-crossing-explicit-emitted-hdl",
        ("clock_reset", "clock_and_reset_distinct_from_bool"): True,
        ("clock_reset", "ordinary_always_allowed"): False,
        ("clock_reset", "single_domain_child_inheritance"): True,
        ("clock_reset", "string_keyed_domain_binding"): False,
        ("crossings", "single_bit_sync_only"): True,
        ("crossings", "silent_synchronizer_insertion"): False,
        ("event_semantics", "low_level_escape"): "nodal.lowlevel.process(event)",
        ("event_semantics", "low_level_library_subset"): False,
        ("implementation_status", "surface_compiles"): True,
        ("implementation_status", "semantic_negative_fixtures_are_contract_only"): True,
        ("implementation_status", "frontend_domain_semantics_implemented"): False,
        ("implementation_status", "hdl_lowering_implemented"): False,
    }
    for path, expected in expected_manifest.items():
        value = _nested(manifest, *path)
        if value != expected:
            problems.append(
                Problem(
                    "NODAL-INC12-022",
                    f"manifest {'.'.join(path)} is {value!r}, expected {expected!r}",
                )
            )

    expected_domain_constructors = [
        "ClockDomain.external",
        "ClockDomain.from",
        "ClockDomain.required",
        "ClockDomain.generated",
    ]
    if _nested(manifest, "clock_reset", "domain_constructors") != expected_domain_constructors:
        problems.append(
            Problem("NODAL-INC12-023", "manifest domain constructors do not match v0.2")
        )
    if manifest.get("removed_from_ordinary_subset") != ["always", "always(event)"]:
        problems.append(
            Problem("NODAL-INC12-024", "manifest must remove both ordinary always forms")
        )
    if _nested(manifest, "diagnostics_manifest") != (
        "core/scala/api/clock-reset-diagnostics-v0.2.json"
    ):
        problems.append(Problem("NODAL-INC12-025", "manifest diagnostics link is not frozen"))
    if _nested(manifest, "fixture_manifest") != FIXTURE_MANIFEST:
        problems.append(Problem("NODAL-INC12-026", "manifest fixture link is not frozen"))

    if diagnostics.get("schema") != 1 or diagnostics.get("api_version") != "0.2":
        problems.append(Problem("NODAL-INC12-027", "diagnostic manifest identity is invalid"))
    if _nested(diagnostics, "source_location", "required") is not True:
        problems.append(
            Problem("NODAL-INC12-028", "every clock/reset diagnostic must require a source location")
        )
    if _nested(diagnostics, "source_location", "fields") != [
        "path",
        "line",
        "column",
        "span",
    ]:
        problems.append(
            Problem("NODAL-INC12-029", "diagnostic source-location fields are not frozen")
        )
    diagnostic_entries = diagnostics.get("diagnostics")
    observed_codes: list[str] = []
    if isinstance(diagnostic_entries, list):
        for entry in diagnostic_entries:
            if isinstance(entry, dict) and isinstance(entry.get("code"), str):
                observed_codes.append(entry["code"])
            if not isinstance(entry, dict) or entry.get("severity") != "error":
                problems.append(
                    Problem("NODAL-INC12-030", "all initial clock/reset diagnostics must be errors")
                )
                continue
            for field in ("name", "phase", "message", "suggestion"):
                if not isinstance(entry.get(field), str) or not entry[field]:
                    problems.append(
                        Problem(
                            "NODAL-INC12-031",
                            f"diagnostic {entry.get('code')!r} lacks non-empty {field}",
                        )
                    )
    if observed_codes != list(EXPECTED_DIAGNOSTIC_CODES):
        problems.append(
            Problem(
                "NODAL-INC12-032",
                f"diagnostic codes are {observed_codes!r}, expected {EXPECTED_DIAGNOSTIC_CODES!r}",
            )
        )

    if fixtures.get("schema") != 1 or fixtures.get("api_version") != "0.2":
        problems.append(Problem("NODAL-INC12-033", "fixture manifest identity is invalid"))
    if fixtures.get("positive") != list(POSITIVE_FIXTURES):
        problems.append(
            Problem("NODAL-INC12-034", "positive fixture inventory does not match the freeze")
        )
    expected_negative = [
        {"path": path, "code": code, "mode": mode}
        for path, code, mode in NEGATIVE_FIXTURES
    ]
    if fixtures.get("negative") != expected_negative:
        problems.append(
            Problem("NODAL-INC12-035", "negative fixture inventory does not match the freeze")
        )

    for path, code, mode in NEGATIVE_FIXTURES:
        content = _read(root / path, problems, "NODAL-INC12-036")
        anchor = f"diagnostic-anchor: {code}"
        if content.count(anchor) != 1:
            problems.append(
                Problem(
                    "NODAL-INC12-037",
                    f"{path} must contain exactly one source anchor for {code}",
                )
            )
        if mode not in {"scala-type-rejected", "semantic-contract"}:
            problems.append(Problem("NODAL-INC12-038", f"invalid fixture mode for {path}: {mode}"))

    positive_content = "\n".join(
        _read(root / path, problems, "NODAL-INC12-039") for path in POSITIVE_FIXTURES
    )
    _require(
        positive_content,
        (
            "ClockDomain.external(",
            "ClockDomain.from(",
            "ClockDomain.required(",
            "ClockDomain.generated(",
            "Reg(0.U(8))",
            "Reg.uninitialized(UInt(8))",
            "RegNext(enable, false.B)",
            "RegNext.uninitialized(input)",
            "when(load):",
            "elsewhen(delayedEnable):",
            "otherwise:",
            ".domain(_.writeDomain, core)",
            ".domain(pixel)",
            "ResetPolicy.None",
            "ResetPolicy.Sync",
            "ResetPolicy.Async",
            "ResetPolicy.AsyncAssertSyncRelease",
            "ClockRelation.Same",
            "ClockRelation.Ratio",
            "ClockRelation.Synchronous",
            "ClockRelation.MutuallyExclusive",
            "ClockRelation.Asynchronous",
            "ClockRelation.Unknown",
            "Cdc.sync(",
            "Cdc.gray(",
            "Cdc.pulse(",
            "Cdc.handshake(",
            "Cdc.fifo(",
            "Cdc.waive(",
            "Rdc.sync(",
            "ResetController.combine(",
            "ClockGate(",
            "ClockMux.glitchless(",
            "nodal.lowlevel.process(",
            "on(cross(",
            "on(timer(",
        ),
        problems,
        "NODAL-INC12-040",
        "positive fixtures",
    )
    if "always(" in positive_content or "always:" in positive_content:
        problems.append(Problem("NODAL-INC12-041", "positive fixtures retain ordinary always"))

    analog_path = root / POSITIVE_FIXTURES[3]
    analog_content = _read(analog_path, problems, "NODAL-INC12-042")
    analog_only = analog_content.split("final class LowLevelEventFixture", 1)[0]
    for forbidden in ("ClockDomain", "in(Clock)", "in(Reset)", "Reg("):
        if forbidden in analog_only:
            problems.append(
                Problem(
                    "NODAL-INC12-043",
                    f"analog-only fixture gained unused sequential structure: {forbidden}",
                )
            )

    _require(
        external,
        (
            "package external.reuse",
            "import nodal.*",
            "final class ClockedRegister",
            "private val state = Reg(0.U(width))",
            "when(enable):",
        ),
        problems,
        "NODAL-INC12-044",
        "external-library fixture",
    )
    for forbidden in (
        "nodal.internal",
        "nodal.bootstrap",
        "nodal.lowlevel",
        "Backend",
        "Nodal.emit",
        "ClockDomain.external",
        "ClockDomain.from",
    ):
        if forbidden in external:
            problems.append(
                Problem(
                    "NODAL-INC12-045",
                    f"external-library fixture uses excluded surface: {forbidden}",
                )
            )

    if "always(" in mixed or "always:" in mixed:
        problems.append(
            Problem("NODAL-INC12-046", "migrated mixed-signal candidates retain ordinary always")
        )
    _require(
        mixed,
        ("ClockDomain.required", "Reg(0.U(width))", "analog:", "on(cross("),
        problems,
        "NODAL-INC12-047",
        "migrated mixed-signal candidates",
    )

    expected_surface = {
        ("status",): "frozen",
        ("api_version",): "0.2",
        ("roadmap_revision",): "1.12",
        ("formal_freeze_increment",): 12,
        ("ordinary_state", "ordinary_always_allowed"): False,
        ("evidence", "compile_positive"): True,
        ("evidence", "scala_type_negative"): True,
        ("evidence", "semantic_negative_contracts"): True,
        ("evidence", "frontend_behavior_inert"): True,
    }
    for path, expected in expected_surface.items():
        value = _nested(surface, *path)
        if value != expected:
            problems.append(
                Problem(
                    "NODAL-INC12-048",
                    f"surface {'.'.join(path)} is {value!r}, expected {expected!r}",
                )
            )

    _require(
        gate,
        (
            "**Status:** Approved",
            "**Scope:** public-api",
            "**API version:** 0.2",
            "Implicit local domain, explicit crossing, explicit emitted HDL",
            "Rdc.sync(reset, to = destination, stages = 2)",
            "Rdc.sync(reset, stages = 2)",
            "scala-type-rejected",
            "semantic-contract",
            "NODAL-DOMAIN-001",
            "NODAL-STATE-001",
            "frontend",
            "intentionally inert",
        ),
        problems,
        "NODAL-INC12-049",
        "clock/reset design gate",
    )
    _require(
        migration,
        (
            "NODAL-MIGRATION-001",
            "always(clock.rising)",
            "ClockDomain.external(",
            "ClockDomain.from(",
            "Reg(0.U(8))",
            "Cdc.sync(",
            "nodal.lowlevel.process",
            "NODAL-STATE-001",
        ),
        problems,
        "NODAL-INC12-050",
        "v0.1-to-v0.2 migration note",
    )
    _require(
        reference,
        (
            "import nodal.*",
            "ClockDomain.external(",
            "ClockDomain.generated(",
            "Reg.uninitialized",
            "Cdc.fifo(",
            "ClockMux.glitchless(",
            "Ordinary synchronous source does not use `always`",
        ),
        problems,
        "NODAL-INC12-051",
        "v0.2 language reference",
    )
    _require(
        plan,
        (
            "**Status:** Frozen by Increment 12",
            "### Increment 12 — Public API v0.2 freeze and contract fixtures",
            "- [x] Add compile-only candidates",
            "- [x] Freeze stable diagnostic codes and source locations.",
        ),
        problems,
        "NODAL-INC12-052",
        "clock/reset freeze plan",
    )
    if "### Increment 12 — Public API v0.2 freeze and contract fixtures\n\n- [ ]" in plan:
        problems.append(Problem("NODAL-INC12-053", "Increment 12 plan checklist is not closed"))

    _require(
        roadmap,
        (
            "**Revision:** 1.12",
            "- [x] **Increment 12 — Clock/reset public API v0.2 freeze and contract fixtures**",
            "NodalClockResetApi-DG-v0.2.md",
            "clock-reset-diagnostics-v0.2.json",
            "increment-12/clock-reset-api-v0-2",
        ),
        problems,
        "NODAL-INC12-054",
        "main roadmap",
    )
    updated_lines = [
        line.removeprefix("**Updated:** ")
        for line in roadmap.splitlines()
        if line.startswith("**Updated:** ")
    ]
    if len(updated_lines) != 1 or updated_lines[0] < "2026-08-22":
        problems.append(
            Problem(
                "NODAL-INC12-054",
                "main roadmap Updated date must be present and no earlier than 2026-08-22",
            )
        )

    _require(
        build,
        (
            "object clockResetApi extends NodalScalaModule:",
            "def moduleDeps = Seq(core.scala.api, externalLibrary)",
        ),
        problems,
        "NODAL-INC12-055",
        "Mill build",
    )
    _require(
        command,
        ('"check_increment12.py", "--compile-negative"',),
        problems,
        "NODAL-INC12-056",
        "unified developer command",
    )
    _require(
        command_check,
        ('check_increment12.py"',),
        problems,
        "NODAL-INC12-057",
        "developer command contract",
    )
    _require(
        command_tests,
        ("check_increment12.py",),
        problems,
        "NODAL-INC12-058",
        "developer command tests",
    )
    _require(
        workflow,
        (
            "increment/12-clock-reset-api-v0-2-freeze",
            "increment-12/clock-reset-api-v0-2",
            "./nodal check",
            "--base-ref origin/dev",
            "public-api-v0.2.json",
            "clock-reset-diagnostics-v0.2.json",
        ),
        problems,
        "NODAL-INC12-059",
        "Increment 12 workflow",
    )
    for owned in (
        "/core/scala/api/public-api-v0.2.json",
        "/core/scala/api/clock-reset-diagnostics-v0.2.json",
        "/scripts/check_increment12.py",
        "/docs/language-reference/clock-reset-api-v0.2.md",
        "/docs/migrations/public-api-v0.1-to-v0.2.md",
    ):
        if owned not in codeowners:
            problems.append(Problem("NODAL-INC12-060", f"CODEOWNERS lacks: {owned}"))
    return problems


def check_compile_contracts(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    manifest = _json(root / FIXTURE_MANIFEST, problems, "NODAL-INC12-061")
    if problems:
        return problems

    wrapper = root / ("mill.bat" if os.name == "nt" else "mill")
    if not wrapper.is_file():
        return [Problem("NODAL-INC12-062", f"Mill wrapper is missing: {wrapper}")]

    def run_mill(*targets: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [str(wrapper), *targets],
            cwd=root,
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )

    positive = run_mill(
        "examples.externalLibrary.compile",
        "examples.publicApiCandidates.compile",
        "examples.clockResetApi.compile",
    )
    if positive.returncode != 0:
        problems.append(
            Problem(
                "NODAL-INC12-063",
                "positive clock/reset fixtures failed to compile:\n" + positive.stdout[-8000:],
            )
        )
        return problems

    injected = root / "examples/publicApiCandidates/src/__Increment12Negative.scala"
    if injected.exists():
        return [
            Problem(
                "NODAL-INC12-064",
                f"refusing to overwrite unexpected compile fixture: {injected}",
            )
        ]

    entries = _negative_entries(manifest)
    try:
        for entry in entries:
            if entry.get("mode") != "scala-type-rejected":
                continue
            path = root / str(entry.get("path"))
            code = str(entry.get("code"))
            source = _read(path, problems, "NODAL-INC12-065")
            if not source:
                continue
            injected.write_text(source, encoding="utf-8")
            completed = run_mill("examples.publicApiCandidates.compile")
            if completed.returncode == 0:
                problems.append(
                    Problem(
                        "NODAL-INC12-066",
                        f"type-negative fixture compiled successfully: {path} ({code})",
                    )
                )
            elif injected.name not in completed.stdout and path.stem not in completed.stdout:
                problems.append(
                    Problem(
                        "NODAL-INC12-067",
                        f"compile failure for {code} did not identify the injected fixture:\n"
                        + completed.stdout[-4000:],
                    )
                )
            injected.unlink(missing_ok=True)
    finally:
        injected.unlink(missing_ok=True)

    restored = run_mill("examples.publicApiCandidates.compile")
    if restored.returncode != 0:
        problems.append(
            Problem(
                "NODAL-INC12-068",
                "positive candidate module did not recover after negative compilation:\n"
                + restored.stdout[-8000:],
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
        problems.extend(check_compile_contracts(args.root))
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"Increment 12 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 12 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
