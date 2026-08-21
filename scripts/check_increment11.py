#!/usr/bin/env python3
"""Validate the Nodal public API v0.1 design gate and freeze contract."""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_FILES = (
    "core/scala/api/src/nodal/CandidateApi.scala",
    "core/scala/api/src/nodal/CompilerApi.scala",
    "core/scala/api/public-api-v0.1.json",
    "examples/publicApiCandidates/src/prototypes/candidates/AnalogCandidates.scala",
    "examples/publicApiCandidates/src/prototypes/candidates/MixedSignalCandidates.scala",
    "examples/publicApiCandidates/src/prototypes/candidates/ExternalReuseCandidate.scala",
    "examples/externalLibrary/src/external/reuse/GainStage.scala",
    "docs/design-gates/NodalPublicApi-DG-v0.1.md",
    "docs/design-gates/NodalPublicApiCandidates-DG-v0.1.md",
    "docs/language-reference/public-api-v0.1.md",
    "docs/development/public-api-candidates.md",
    "scripts/check_increment11.py",
    "tests/api/test_increment11.py",
    ".github/workflows/increment-11-public-api-freeze.yml",
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


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    for relative in EXPECTED_FILES:
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-INC11-001", f"missing Increment 11 file: {relative}"))

    language_api = _read(
        root / "core/scala/api/src/nodal/CandidateApi.scala",
        problems,
        "NODAL-INC11-002",
    )
    compiler_api = _read(
        root / "core/scala/api/src/nodal/CompilerApi.scala",
        problems,
        "NODAL-INC11-003",
    )
    manifest = _json(
        root / "core/scala/api/public-api-v0.1.json",
        problems,
        "NODAL-INC11-004",
    )
    gate = _read(
        root / "docs/design-gates/NodalPublicApi-DG-v0.1.md",
        problems,
        "NODAL-INC11-005",
    )
    candidate_gate = _read(
        root / "docs/design-gates/NodalPublicApiCandidates-DG-v0.1.md",
        problems,
        "NODAL-INC11-006",
    )
    reference = _read(
        root / "docs/language-reference/public-api-v0.1.md",
        problems,
        "NODAL-INC11-007",
    )
    candidate_guide = _read(
        root / "docs/development/public-api-candidates.md",
        problems,
        "NODAL-INC11-008",
    )
    analog = _read(
        root / "examples/publicApiCandidates/src/prototypes/candidates/AnalogCandidates.scala",
        problems,
        "NODAL-INC11-009",
    )
    mixed = _read(
        root / "examples/publicApiCandidates/src/prototypes/candidates/MixedSignalCandidates.scala",
        problems,
        "NODAL-INC11-010",
    )
    external = _read(
        root / "examples/externalLibrary/src/external/reuse/GainStage.scala",
        problems,
        "NODAL-INC11-011",
    )
    command = _read(root / "scripts/nodal.py", problems, "NODAL-INC11-012")
    command_check = _read(
        root / "scripts/check_developer_commands.py",
        problems,
        "NODAL-INC11-013",
    )
    command_tests = _read(
        root / "tests/developer/test_developer_commands.py",
        problems,
        "NODAL-INC11-014",
    )
    workflow = _read(
        root / ".github/workflows/increment-11-public-api-freeze.yml",
        problems,
        "NODAL-INC11-015",
    )
    codeowners = _read(root / ".github/CODEOWNERS", problems, "NODAL-INC11-016")

    _require(
        language_api,
        (
            "sealed trait Data",
            "sealed trait Expr",
            "final class Param",
            "abstract class Module",
            "protected final def in[",
            "protected final def out[",
            "protected final def inout[",
            "def apply(width: Expr[Integer])",
            "def toUInt(value: Expr[Real], width: Expr[Integer])",
            "infix def <+",
            "infix def :=",
        ),
        problems,
        "NODAL-INC11-017",
        "frozen language API",
    )
    for forbidden in (
        "protected final def input[",
        "protected final def output[",
        "NodalComponent",
        "NodalModule",
        "NodalParam",
    ):
        if forbidden in language_api:
            problems.append(
                Problem("NODAL-INC11-018", f"frozen language API contains rejected form: {forbidden}")
            )

    _require(
        compiler_api,
        (
            "enum Backend:",
            "case VerilogA, VerilogAMS",
            "final case class EmitOptions",
            "final case class EmittedFile",
            "final case class Emission",
            "object Nodal:",
            "def emit(top: => Module",
            "Emission(Vector.empty)",
        ),
        problems,
        "NODAL-INC11-019",
        "frozen compiler API",
    )

    expected_manifest = {
        ("schema",): 1,
        ("api_version",): "0.1",
        ("status",): "frozen",
        ("default_import",): "import nodal.*",
        ("backends", "entry_point"): "Nodal.emit",
        ("backends", "default_profile"): "Backend.VerilogAMS",
        ("backends", "filesystem_side_effects"): False,
        ("parameterization", "native_parameterized_hdl_required"): True,
        ("parameterization", "symbolic_hierarchy_propagation"): True,
        ("parameterization", "module_specialization_by_value"): False,
        ("compatibility", "source_compatibility"): "required across 0.1.x",
    }
    for path, expected in expected_manifest.items():
        value = _nested(manifest, *path)
        if value != expected:
            problems.append(
                Problem(
                    "NODAL-INC11-020",
                    f"manifest {'.'.join(path)} is {value!r}, expected {expected!r}",
                )
            )

    module_members = manifest.get("module_members")
    if module_members != sorted(
        ["connect", "in", "inout", "instance", "node", "out", "param", "variable", "wire"]
    ):
        problems.append(
            Problem("NODAL-INC11-021", "manifest module_members do not match the frozen v0.1 set")
        )
    widths = _nested(manifest, "parameterization", "parameterized_widths")
    if widths != ["Bits(Expr[Integer])", "UInt(Expr[Integer])"]:
        problems.append(
            Problem("NODAL-INC11-022", "manifest must freeze symbolic Bits and UInt widths")
        )
    excluded = _nested(manifest, "library_author_subset", "excluded")
    for required in ("Nodal.emit", "nodal.internal.*", "nodal.bootstrap.*"):
        if not isinstance(excluded, list) or required not in excluded:
            problems.append(
                Problem("NODAL-INC11-023", f"library-author exclusions lack: {required}")
            )

    _require(
        gate,
        (
            "**Status:** Approved",
            "**Scope:** public-api",
            "Nodal public API v0.1 is frozen",
            "Native parameterized Verilog-AMS contract",
            "module is not cloned once per parameter value",
            "Nodal.emit",
            "Future libraries may use",
            "Rejected alternatives",
            "Compatibility policy",
            "Increment 12",
        ),
        problems,
        "NODAL-INC11-024",
        "public API design gate",
    )
    _require(
        candidate_gate,
        (
            "**Status:** Superseded",
            "NodalPublicApi-DG-v0.1.md",
        ),
        problems,
        "NODAL-INC11-025",
        "candidate design gate",
    )
    _require(
        reference,
        (
            "import nodal.*",
            "val input = in(Electrical)",
            "val width = param(12.integer)",
            "UInt(width)",
            ".param(_.width, width)",
            "Nodal.emit",
            "must not clone a module for each value",
        ),
        problems,
        "NODAL-INC11-026",
        "v0.1 language reference",
    )
    _require(
        candidate_guide,
        (
            "Superseded by Increment 11",
            "NodalPublicApi-DG-v0.1.md",
        ),
        problems,
        "NODAL-INC11-027",
        "candidate comparison guide",
    )

    prototype_sources = analog + "\n" + mixed + "\n" + external
    _require(
        prototype_sources,
        (
            "val input = in(",
            "val output = out(",
            "val width = param(12.integer)",
            "UInt(width)",
            "toUInt(",
        ),
        problems,
        "NODAL-INC11-028",
        "frozen source examples",
    )
    for forbidden in ("= input(", "= output("):
        if forbidden in prototype_sources:
            problems.append(
                Problem("NODAL-INC11-029", f"examples retain superseded declaration form: {forbidden}")
            )

    _require(
        command,
        ('"check_increment11.py"', '"api"'),
        problems,
        "NODAL-INC11-030",
        "unified developer command",
    )
    _require(
        command_check,
        ('check_increment11.py"',),
        problems,
        "NODAL-INC11-031",
        "developer command contract",
    )
    _require(
        command_tests,
        ("check_increment11.py", "tests/api"),
        problems,
        "NODAL-INC11-032",
        "developer command tests",
    )
    _require(
        workflow,
        (
            "increment/11-public-api-design-gate-v0-1",
            "increment-11/public-api-freeze",
            "./nodal check",
            "--base-ref origin/dev",
        ),
        problems,
        "NODAL-INC11-033",
        "Increment 11 workflow",
    )
    for owned in (
        "/core/scala/api/public-api-v0.1.json",
        "/scripts/check_increment11.py",
        "/tests/api/",
        "/docs/language-reference/public-api-v0.1.md",
    ):
        if owned not in codeowners:
            problems.append(Problem("NODAL-INC11-034", f"CODEOWNERS lacks: {owned}"))
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args(argv)
    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"Increment 11 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 11 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
