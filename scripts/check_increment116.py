#!/usr/bin/env python3
"""Validate Increment 116 register-factory API freeze and contract fixtures."""

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
API_MANIFEST = "core/scala/api/register-factory-api-v0.1.json"
DIAGNOSTICS_MANIFEST = "core/scala/api/register-factory-diagnostics-v0.1.json"
FIXTURE_MANIFEST = "tests/api/fixtures/increment116/manifest.json"
POSITIVE_FIXTURES = (
    "examples/registerFactoryApi/src/contracts/registerfactory/positive/RegisterMapFixtures.scala",
    "examples/registerFactoryApi/src/contracts/registerfactory/positive/RegisterBlockFixtures.scala",
    "examples/registerFactoryApi/src/contracts/registerfactory/positive/TransportFixtures.scala",
    "examples/externalLibrary/src/external/registers/GpioRegisterMap.scala",
)
NEGATIVE_FIXTURES = (
    (
        "tests/api/fixtures/increment116/negative/CrossMapField.scala",
        "NODAL-REG-BIND-001",
        "scala-type-rejected",
    ),
    (
        "tests/api/fixtures/increment116/negative/MissingTransport.scala",
        "NODAL-REG-TRANSPORT-001",
        "scala-type-rejected",
    ),
    (
        "tests/api/fixtures/increment116/negative/SymbolicFixedOffset.scala",
        "NODAL-REG-PARAM-001",
        "scala-type-rejected",
    ),
    (
        "tests/api/fixtures/increment116/negative/WrongBindingType.scala",
        "NODAL-REG-BIND-002",
        "scala-type-rejected",
    ),
    (
        "tests/api/fixtures/increment116/negative/WrongCounterField.scala",
        "NODAL-REG-BIND-003",
        "scala-type-rejected",
    ),
    (
        "tests/api/fixtures/increment116/negative/DuplicateAddress.scala",
        "NODAL-REG-MAP-001",
        "semantic-contract",
    ),
    (
        "tests/api/fixtures/increment116/negative/OverlappingField.scala",
        "NODAL-REG-MAP-002",
        "semantic-contract",
    ),
    (
        "tests/api/fixtures/increment116/negative/MissingRegisterDomain.scala",
        "NODAL-REG-DOMAIN-001",
        "semantic-contract",
    ),
    (
        "tests/api/fixtures/increment116/negative/MultipleDirectTransports.scala",
        "NODAL-REG-TRANSPORT-002",
        "semantic-contract",
    ),
    (
        "tests/api/fixtures/increment116/negative/DynamicReset.scala",
        "NODAL-REG-SOURCE-001",
        "semantic-contract",
    ),
    (
        "tests/api/fixtures/increment116/negative/IllegalPolicy.scala",
        "NODAL-REG-POLICY-001",
        "semantic-contract",
    ),
    (
        "tests/api/fixtures/increment116/negative/UnsafeCrossDomainBinding.scala",
        "NODAL-REG-CDC-001",
        "semantic-contract",
    ),
)
EXPECTED_DIAGNOSTIC_CODES = tuple(code for _path, code, _mode in NEGATIVE_FIXTURES)
EXPECTED_FILES = (
    "build.mill",
    "core/scala/api/src/nodal/CandidateApi.scala",
    "core/scala/api/src/nodal/RegisterFactoryApi.scala",
    API_MANIFEST,
    DIAGNOSTICS_MANIFEST,
    "docs/architecture/0020-canonical-register-factory-and-transport-adapters.md",
    "docs/design-gates/NodalRegisterFactory-DG-v0.1.md",
    "docs/language-reference/register-factory-api-v0.1.md",
    "docs/roadmap/register-factory-v0.1-plan.md",
    "docs/roadmap/register-factory-v0.1-surface.json",
    "docs/roadmap/nodal-development-todo.md",
    *POSITIVE_FIXTURES,
    FIXTURE_MANIFEST,
    *(path for path, _code, _mode in NEGATIVE_FIXTURES),
    "scripts/check_increment116.py",
    "scripts/nodal.py",
    "scripts/check_developer_commands.py",
    "tests/api/test_increment116.py",
    "tests/developer/test_developer_commands.py",
    ".github/workflows/increment-116-register-factory-api.yml",
    ".github/CODEOWNERS",
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


def _nested(value: dict[str, Any], *keys: str) -> Any:
    current: Any = value
    for key in keys:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


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
            problems.append(Problem("NODAL-INC116-001", f"missing Increment 116 file: {relative}"))

    api = _read(
        root / "core/scala/api/src/nodal/RegisterFactoryApi.scala",
        problems,
        "NODAL-INC116-002",
    )
    manifest = _json(root / API_MANIFEST, problems, "NODAL-INC116-003")
    diagnostics = _json(root / DIAGNOSTICS_MANIFEST, problems, "NODAL-INC116-004")
    fixtures = _json(root / FIXTURE_MANIFEST, problems, "NODAL-INC116-005")
    surface = _json(
        root / "docs/roadmap/register-factory-v0.1-surface.json",
        problems,
        "NODAL-INC116-006",
    )
    gate = _read(
        root / "docs/design-gates/NodalRegisterFactory-DG-v0.1.md",
        problems,
        "NODAL-INC116-007",
    )
    reference = _read(
        root / "docs/language-reference/register-factory-api-v0.1.md",
        problems,
        "NODAL-INC116-008",
    )
    plan = _read(
        root / "docs/roadmap/register-factory-v0.1-plan.md",
        problems,
        "NODAL-INC116-009",
    )
    roadmap = _read(
        root / "docs/roadmap/nodal-development-todo.md",
        problems,
        "NODAL-INC116-010",
    )
    build = _read(root / "build.mill", problems, "NODAL-INC116-011")
    command = _read(root / "scripts/nodal.py", problems, "NODAL-INC116-012")
    command_check = _read(
        root / "scripts/check_developer_commands.py",
        problems,
        "NODAL-INC116-013",
    )
    command_tests = _read(
        root / "tests/developer/test_developer_commands.py",
        problems,
        "NODAL-INC116-014",
    )
    workflow = _read(
        root / ".github/workflows/increment-116-register-factory-api.yml",
        problems,
        "NODAL-INC116-015",
    )
    codeowners = _read(root / ".github/CODEOWNERS", problems, "NODAL-INC116-016")

    _require(
        api,
        (
            "enum AddressUnit:",
            "enum Endianness:",
            "enum SoftwareAccess:",
            "enum HardwareAccess:",
            "enum CollisionPolicy:",
            "enum IllegalAccessPolicy:",
            "enum PartialWritePolicy:",
            "enum MultiwordAccess:",
            "type RegisterOffset = Int | Long | BigInt",
            "type RegisterSize = Int | Long | BigInt",
            "type RegisterCount = Int | Expr[Integer]",
            "infix def downto",
            "type FieldBits = Int | BitRange",
            "type FieldReset[A <: Data] = Expr[A] | FieldReset.Unspecified.type",
            "abstract class RegisterMap(",
            "final class Field[A <: Data]",
            "final class Register private[nodal]",
            "def field[A <: Data](",
            "protected final def register(",
            "protected final def submap",
            "protected final def array",
            "protected final def window",
            "protected final def alias",
            "protected final def snapshot",
            "protected final def commitGroup",
            "final class RegisterAccessPort private[nodal]",
            "final case class RegisterTransportCapabilities(",
            "trait RegisterTransport[B]:",
            "final class RegisterBlock[M <: RegisterMap]",
            "def value[A <: Data](field: map.Field[A])",
            "def input[A <: Data](field: map.Field[A])",
            "def setWhen(field: map.Field[Bool]",
            "def incrementWhen(field: map.Field[UInt]",
            "def attach[B](bus: B)(using transport: RegisterTransport[B])",
        ),
        problems,
        "NODAL-INC116-017",
        "register-factory API",
    )
    for forbidden in (
        "Apb3BusInterface",
        "Axi4LiteBusInterface",
        "BusSlaveFactory",
        "RegIf",
        "newReg(",
        "createReadWrite(",
        "def field(name: String):",
        "Map[String",
        "given Conversion[",
        "scala.language.implicitConversions",
    ):
        if forbidden in api:
            problems.append(
                Problem("NODAL-INC116-018", f"public API contains rejected bus-first/untyped form: {forbidden}")
            )

    expected_manifest = {
        ("schema",): 1,
        ("api_family",): "register-factory",
        ("api_version",): "0.1",
        ("status",): "frozen",
        ("default_import",): "import nodal.*",
        ("tooling_baseline", "scala"): "3.8.4",
        ("tooling_baseline", "spinalhdl_comparison"): "1.14.2",
        ("map_construction", "published_offsets_explicit_by_default"): True,
        ("field_semantics", "axes_are_orthogonal"): True,
        ("physical_binding", "map_owned_field_handles"): True,
        ("physical_binding", "cross_map_binding_is_type_error"): True,
        ("transport_binding", "scala_mechanism"): "given-using",
        ("transport_binding", "implicit_multiple_direct_attachment"): False,
        ("generated_verilog", "fixed_abi_symbols_are_parameters"): False,
        ("generated_verilog", "hdl_parameters_only_for_explicit_nodal_architectural_variability"): True,
        ("generated_verilog", "relative_offset_decode_default"): True,
        ("implementation_status", "surface_compiles"): True,
        ("implementation_status", "scala_type_negative_fixtures_execute"): True,
        ("implementation_status", "semantic_negative_fixtures_are_contract_only"): True,
        ("implementation_status", "canonical_register_ir_implemented"): False,
        ("implementation_status", "bus_adapters_implemented"): False,
        ("implementation_status", "verilog_lowering_implemented"): False,
    }
    for path, expected in expected_manifest.items():
        value = _nested(manifest, *path)
        if value != expected:
            problems.append(
                Problem(
                    "NODAL-INC116-019",
                    f"API manifest {'.'.join(path)} is {value!r}, expected {expected!r}",
                )
            )
    if manifest.get("diagnostics_manifest") != DIAGNOSTICS_MANIFEST:
        problems.append(Problem("NODAL-INC116-020", "API manifest diagnostics link is not frozen"))
    if manifest.get("fixture_manifest") != FIXTURE_MANIFEST:
        problems.append(Problem("NODAL-INC116-021", "API manifest fixture link is not frozen"))

    expected_software = [
        "RO",
        "RW",
        "WO",
        "W1C",
        "W1S",
        "W1T",
        "W0C",
        "W0S",
        "RC",
        "RS",
        "WriteOnce",
        "Reserved",
    ]
    if _nested(manifest, "field_semantics", "software_access") != expected_software:
        problems.append(Problem("NODAL-INC116-022", "software-access inventory is not frozen"))

    if diagnostics.get("schema") != 1 or diagnostics.get("api_version") != "0.1":
        problems.append(Problem("NODAL-INC116-023", "diagnostic manifest identity is invalid"))
    if _nested(diagnostics, "source_location", "required") is not True:
        problems.append(Problem("NODAL-INC116-024", "register diagnostics must require source locations"))
    if _nested(diagnostics, "source_location", "fields") != ["path", "line", "column", "span"]:
        problems.append(Problem("NODAL-INC116-025", "diagnostic source-location fields are not frozen"))
    entries = diagnostics.get("diagnostics")
    observed_codes: list[str] = []
    if isinstance(entries, list):
        for entry in entries:
            if isinstance(entry, dict) and isinstance(entry.get("code"), str):
                observed_codes.append(entry["code"])
            if not isinstance(entry, dict) or entry.get("severity") != "error":
                problems.append(Problem("NODAL-INC116-026", "all initial register diagnostics must be errors"))
                continue
            for field in ("name", "phase", "message", "suggestion"):
                if not isinstance(entry.get(field), str) or not entry[field]:
                    problems.append(
                        Problem(
                            "NODAL-INC116-027",
                            f"diagnostic {entry.get('code')!r} lacks non-empty {field}",
                        )
                    )
    if observed_codes != list(EXPECTED_DIAGNOSTIC_CODES):
        problems.append(
            Problem(
                "NODAL-INC116-028",
                f"diagnostic codes are {observed_codes!r}, expected {EXPECTED_DIAGNOSTIC_CODES!r}",
            )
        )

    if fixtures.get("schema") != 1 or fixtures.get("api_version") != "0.1":
        problems.append(Problem("NODAL-INC116-029", "fixture manifest identity is invalid"))
    if fixtures.get("positive") != list(POSITIVE_FIXTURES):
        problems.append(Problem("NODAL-INC116-030", "positive fixture inventory does not match"))
    expected_negative = [
        {"path": path, "code": code, "mode": mode}
        for path, code, mode in NEGATIVE_FIXTURES
    ]
    if fixtures.get("negative") != expected_negative:
        problems.append(Problem("NODAL-INC116-031", "negative fixture inventory does not match"))

    for path, code, mode in NEGATIVE_FIXTURES:
        content = _read(root / path, problems, "NODAL-INC116-032")
        if content.count(f"diagnostic-anchor: {code}") != 1:
            problems.append(
                Problem(
                    "NODAL-INC116-033",
                    f"{path} must contain exactly one source anchor for {code}",
                )
            )
        if mode not in {"scala-type-rejected", "semantic-contract"}:
            problems.append(Problem("NODAL-INC116-034", f"invalid fixture mode for {path}: {mode}"))

    positive_content = "\n".join(
        _read(root / path, problems, "NODAL-INC116-035") for path in POSITIVE_FIXTURES
    )
    _require(
        positive_content,
        (
            "extends RegisterMap(",
            "register(0x00",
            ".field(",
            "15 downto 0",
            "SoftwareAccess.W1C",
            "HardwareAccess.Settable",
            "CollisionPolicy.SetDominatesClear",
            "PartialWritePolicy.RequireWholeField",
            "MultiwordAccess.SnapshotOnFirstRead",
            "reserved(offset =",
            "submap(offset =",
            "array(",
            "count = channelCount",
            "window(offset =",
            "alias(",
            "snapshot(",
            "commitGroup(",
            "RegisterBlock(UartMap)",
            "registers.value(UartMap.enable)",
            "registers.input(UartMap.busy) :=",
            "registers.setWhen(UartMap.irq",
            "registers.incrementWhen(ChannelMap.value",
            "registers.pulse(UartMap.start)",
            "registers.capture(UartMap.statusSnapshot",
            "given RegisterTransport[DemoControlBus]",
            "registers.attach(new DemoControlBus)",
        ),
        problems,
        "NODAL-INC116-036",
        "positive register fixtures",
    )

    external = _read(root / POSITIVE_FIXTURES[-1], problems, "NODAL-INC116-037")
    _require(
        external,
        (
            "package external.registers",
            "import nodal.*",
            "object GpioRegisterMap extends RegisterMap",
            "final class GpioRegisterBlock extends Module",
        ),
        problems,
        "NODAL-INC116-038",
        "external register fixture",
    )
    imports = [line.strip() for line in external.splitlines() if line.strip().startswith("import ")]
    if imports != ["import nodal.*"]:
        problems.append(
            Problem("NODAL-INC116-039", "external register fixture must import only nodal.*")
        )
    for forbidden in ("nodal.internal", "Backend", "Nodal.emit", "CandidateRuntime"):
        if forbidden in external:
            problems.append(
                Problem("NODAL-INC116-040", f"external register fixture uses excluded surface: {forbidden}")
            )

    _require(
        gate,
        (
            "**Status:** Approved",
            "**Scope:** public-api",
            "**API version:** register-factory 0.1",
            "Scala 3.8.4",
            "SpinalHDL 1.14.2",
            "immutable RegisterMap",
            "RegisterTransport[B]",
            "NODAL-REG-BIND-001",
            "NODAL-REG-CDC-001",
            "intentionally inert",
            "non-overridable generated-Verilog `localparam`s/constants",
        ),
        problems,
        "NODAL-INC116-041",
        "register-factory design gate",
    )
    _require(
        reference,
        (
            "import nodal.*",
            "object UartMap extends RegisterMap",
            "registers.input(UartMap.busy) :=",
            "given RegisterTransport[MyControlBus]",
            "Fixed ABI symbols",
            "SystemRDL 2.0",
            "nodal-registers/v1",
        ),
        problems,
        "NODAL-INC116-042",
        "register-factory language reference",
    )
    _require(
        plan,
        (
            "**Status:** Public API frozen by Increment 116; semantic implementation deferred",
            "- [x] **Increment 116 — Register factory public API candidates and design gate**",
            "NodalRegisterFactory-DG-v0.1.md",
            "register-factory-api-v0.1.json",
            "register-factory-diagnostics-v0.1.json",
        ),
        problems,
        "NODAL-INC116-043",
        "register-factory plan",
    )
    _require(
        roadmap,
        (
            "**Revision:** 1.15",
            "- [x] **Increment 116 — Register factory public API candidates and design gate**",
            "NodalRegisterFactory-DG-v0.1.md",
            "register-factory-api-v0.1.json",
            "register-factory-diagnostics-v0.1.json",
            "increment-116/register-factory-api-v0-1",
        ),
        problems,
        "NODAL-INC116-044",
        "main roadmap",
    )

    expected_surface = {
        ("status",): "api-frozen-implementation-deferred",
        ("implementation_status", "public_api_frozen"): True,
        ("implementation_status", "surface_compiles"): True,
        ("implementation_status", "scala_type_negative_fixtures_execute"): True,
        ("implementation_status", "canonical_register_ir_implemented"): False,
    }
    for path, expected in expected_surface.items():
        value = _nested(surface, *path)
        if value != expected:
            problems.append(
                Problem(
                    "NODAL-INC116-045",
                    f"roadmap surface {'.'.join(path)} is {value!r}, expected {expected!r}",
                )
            )
    roadmap_entries = surface.get("roadmap")
    if not isinstance(roadmap_entries, list) or not any(
        isinstance(entry, dict)
        and entry.get("increment") == 116
        and entry.get("status") == "complete"
        for entry in roadmap_entries
    ):
        problems.append(Problem("NODAL-INC116-046", "surface does not close Increment 116"))

    _require(
        build,
        (
            "object registerFactoryApi extends NodalScalaModule:",
            "def moduleDeps = Seq(core.scala.api, externalLibrary)",
        ),
        problems,
        "NODAL-INC116-047",
        "Mill build",
    )
    _require(
        command,
        ('"check_increment116.py", "--compile-negative"',),
        problems,
        "NODAL-INC116-048",
        "unified developer command",
    )
    _require(
        command_check,
        ('check_increment116.py"',),
        problems,
        "NODAL-INC116-049",
        "developer command contract",
    )
    _require(
        command_tests,
        ("check_increment116.py",),
        problems,
        "NODAL-INC116-050",
        "developer command tests",
    )
    _require(
        workflow,
        (
            "increment/116-register-factory-api-gate",
            "increment-116/register-factory-api-v0-1",
            "./nodal check",
            "--base-ref origin/dev",
            "register-factory-api-v0.1.json",
            "register-factory-diagnostics-v0.1.json",
        ),
        problems,
        "NODAL-INC116-051",
        "Increment 116 workflow",
    )
    for owned in (
        "/core/scala/api/src/nodal/RegisterFactoryApi.scala",
        f"/{API_MANIFEST}",
        f"/{DIAGNOSTICS_MANIFEST}",
        "/scripts/check_increment116.py",
        "/docs/design-gates/NodalRegisterFactory-DG-v0.1.md",
        "/docs/language-reference/register-factory-api-v0.1.md",
    ):
        if owned not in codeowners:
            problems.append(Problem("NODAL-INC116-052", f"CODEOWNERS lacks: {owned}"))
    return problems


def check_compile_contracts(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    fixtures = _json(root / FIXTURE_MANIFEST, problems, "NODAL-INC116-053")
    if problems:
        return problems

    wrapper = root / ("mill.bat" if os.name == "nt" else "mill")
    if not wrapper.is_file():
        return [Problem("NODAL-INC116-054", f"Mill wrapper is missing: {wrapper}")]

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
        "examples.registerFactoryApi.compile",
    )
    if positive.returncode != 0:
        problems.append(
            Problem(
                "NODAL-INC116-055",
                "positive register-factory fixtures failed to compile:\n" + positive.stdout[-10000:],
            )
        )
        return problems

    injected = root / "examples/registerFactoryApi/src/__Increment116Negative.scala"
    if injected.exists():
        return [
            Problem(
                "NODAL-INC116-056",
                f"refusing to overwrite unexpected compile fixture: {injected}",
            )
        ]

    try:
        for entry in _negative_entries(fixtures):
            if entry.get("mode") != "scala-type-rejected":
                continue
            path = root / str(entry.get("path"))
            code = str(entry.get("code"))
            source = _read(path, problems, "NODAL-INC116-057")
            if not source:
                continue
            injected.write_text(source, encoding="utf-8")
            completed = run_mill("examples.registerFactoryApi.compile")
            if completed.returncode == 0:
                problems.append(
                    Problem(
                        "NODAL-INC116-058",
                        f"type-negative fixture compiled successfully: {path} ({code})",
                    )
                )
            elif injected.name not in completed.stdout and path.stem not in completed.stdout:
                problems.append(
                    Problem(
                        "NODAL-INC116-059",
                        f"compile failure for {code} did not identify the injected fixture:\n"
                        + completed.stdout[-5000:],
                    )
                )
            injected.unlink(missing_ok=True)
    finally:
        injected.unlink(missing_ok=True)

    restored = run_mill("examples.registerFactoryApi.compile")
    if restored.returncode != 0:
        problems.append(
            Problem(
                "NODAL-INC116-060",
                "positive register module did not recover after negative compilation:\n"
                + restored.stdout[-10000:],
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
        print(f"Increment 116 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 116 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
