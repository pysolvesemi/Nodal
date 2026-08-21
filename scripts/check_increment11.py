#!/usr/bin/env python3
"""Validate the Increment 11 Nodal public API v0.1 freeze."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = "core/scala/api/public-api-v0.1.json"
GATE = "docs/design-gates/NodalPublicApi-DG-v0.1.md"
EXPECTED_FILES = (
    "core/scala/api/src/nodal/CandidateApi.scala",
    MANIFEST,
    GATE,
    "docs/language-reference/public-api-v0.1.md",
    "docs/development/public-api-candidates.md",
    "examples/publicApiCandidates/src/prototypes/candidates/MixedSignalCandidates.scala",
    "examples/publicApiCandidates/src/prototypes/candidates/BackendEntryPoint.scala",
    "examples/externalLibrary/src/external/reuse/GainStage.scala",
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


def _load_json(path: Path, problems: list[Problem], code: str) -> dict[str, Any]:
    try:
        value = json.loads(_read(path, problems, code))
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


def _manifest_path(value: object, *keys: str) -> object:
    current = value
    for key in keys:
        current = current.get(key) if isinstance(current, dict) else None
    return current


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    for relative in EXPECTED_FILES:
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-INC11-001", f"missing Increment 11 file: {relative}"))

    api = _read(
        root / "core/scala/api/src/nodal/CandidateApi.scala",
        problems,
        "NODAL-INC11-002",
    )
    gate = _read(root / GATE, problems, "NODAL-INC11-003")
    reference = _read(
        root / "docs/language-reference/public-api-v0.1.md",
        problems,
        "NODAL-INC11-004",
    )
    mixed = _read(
        root / "examples/publicApiCandidates/src/prototypes/candidates/MixedSignalCandidates.scala",
        problems,
        "NODAL-INC11-005",
    )
    backend_example = _read(
        root / "examples/publicApiCandidates/src/prototypes/candidates/BackendEntryPoint.scala",
        problems,
        "NODAL-INC11-006",
    )
    external = _read(
        root / "examples/externalLibrary/src/external/reuse/GainStage.scala",
        problems,
        "NODAL-INC11-007",
    )
    command = _read(root / "scripts/nodal.py", problems, "NODAL-INC11-008")
    workflow = _read(
        root / ".github/workflows/increment-11-public-api-freeze.yml",
        problems,
        "NODAL-INC11-009",
    )
    manifest = _load_json(root / MANIFEST, problems, "NODAL-INC11-010")

    expected_manifest = {
        ("schema",): 1,
        ("version",): "0.1",
        ("status",): "frozen",
        ("default_import",): "nodal.*",
        ("source_compatibility",): "0.1.x",
        ("binary_compatibility",): "not-guaranteed-before-v1",
        ("parameterized_hdl", "preserve_by_default"): True,
        ("parameterized_hdl", "specialize_modules_by_default"): False,
        ("parameterized_hdl", "scala_constructor_arguments_are_hdl_parameters"): False,
        ("backend_entry_point", "owner"): "nodal.Nodal",
    }
    for path, expected in expected_manifest.items():
        observed = _manifest_path(manifest, *path)
        if observed != expected:
            problems.append(
                Problem(
                    "NODAL-INC11-011",
                    f"manifest {'.'.join(path)} is {observed!r}, expected {expected!r}",
                )
            )

    required_public = {
        "Module",
        "Param",
        "Expr",
        "Real",
        "Integer",
        "Bool",
        "Bits",
        "UInt",
        "Electrical",
        "Backend",
        "Nodal",
        "analog",
        "initial",
        "always",
        "V",
        "I",
        "cross",
        "timer",
        "transition",
        "toUInt",
        "<+",
        ":=",
    }
    public_symbols = manifest.get("public_symbols")
    if not isinstance(public_symbols, list) or not required_public.issubset(set(public_symbols)):
        problems.append(Problem("NODAL-INC11-012", "manifest public symbol set is incomplete"))

    library_subset = manifest.get("library_author_subset")
    if not isinstance(library_subset, list):
        problems.append(Problem("NODAL-INC11-013", "library-author subset is missing"))
    else:
        subset = set(library_subset)
        if not {"Module", "Param", "Expr", "Electrical", "analog"}.issubset(subset):
            problems.append(Problem("NODAL-INC11-013", "library-author subset is incomplete"))
        if {"Backend", "Nodal"} & subset:
            problems.append(
                Problem("NODAL-INC11-014", "library-author subset includes core-only emit entry points")
            )

    _require(
        api,
        (
            "import java.nio.file.Path",
            "import scala.annotation.targetName",
            "final class Param",
            "def apply(width: Expr[Integer]): DataType[Bits]",
            "def apply(width: Expr[Integer]): DataType[UInt]",
            "def toUInt(value: Expr[Real], width: Expr[Integer])",
            "infix def **(right: Expr[Integer])",
            "enum Backend:",
            "case VerilogA, VerilogAMS",
            "object Nodal:",
            "def emit(top: => Module, backend: Backend, outputDirectory: Path): Unit",
            "def param[A <: Data](select: M => Param[A], value: Expr[A])",
            "infix def <+",
            "infix def :=",
        ),
        problems,
        "NODAL-INC11-015",
        "frozen Scala API",
    )
    for forbidden in (
        "NodalComponent",
        "NodalModule",
        "NodalParam",
        "Map[String",
        "specializeModules = true",
    ):
        if forbidden in api:
            problems.append(
                Problem("NODAL-INC11-016", f"frozen Scala API contains rejected form: {forbidden}")
            )

    _require(
        gate,
        (
            "**Status:** Approved",
            "**Scope:** public-api",
            "**Revision:** v0.1",
            "import nodal.*",
            "Parameterized Verilog-A and Verilog-AMS generation contract",
            "Param[A] is an HDL parameter reference",
            "must not be erased during elaboration",
            "specialized module for each value",
            "Bits(width: Expr[Integer])",
            "UInt(width: Expr[Integer])",
            "Nodal.emit(",
            "Backend.VerilogA",
            "Backend.VerilogAMS",
            "No Scala/JVM binary compatibility guarantee is made before v1",
            "future library-author subset",
            "Rejected alternatives",
        ),
        problems,
        "NODAL-INC11-017",
        "public API design gate",
    )

    _require(
        mixed,
        (
            "val width = param(12.integer)",
            "output(UInt(width))",
            "toUInt(V(analogInput, common) / fullScale, width)",
            ".param(_.width, width)",
            "final class ParameterizedAmsChain",
        ),
        problems,
        "NODAL-INC11-018",
        "parameterized mixed-signal examples",
    )
    if re.search(r"final class (?:Adc|Dac|MixedSignalHold)\(.*width: Int", mixed):
        problems.append(
            Problem(
                "NODAL-INC11-019",
                "parameterized HDL examples must not model width only as a Scala constructor Int",
            )
        )

    _require(
        backend_example,
        (
            "Nodal.emit(",
            "Backend.VerilogA",
            "Backend.VerilogAMS",
            "new ParameterizedAmsChain",
            "Path.of(",
        ),
        problems,
        "NODAL-INC11-020",
        "backend entry-point example",
    )

    imports = tuple(
        line.strip()
        for line in external.splitlines()
        if line.strip().startswith("import ")
    )
    if imports != ("import nodal.*",):
        problems.append(
            Problem(
                "NODAL-INC11-021",
                "external reusable module must continue to import exactly nodal.*",
            )
        )
    if "Backend" in external or "Nodal.emit" in external:
        problems.append(
            Problem(
                "NODAL-INC11-022",
                "external reusable model must not depend on core-only emission entry points",
            )
        )

    _require(
        reference,
        (
            "Nodal public API v0.1",
            "Parameterized emitted HDL",
            "val width = param(12.integer)",
            "Backend.VerilogAMS",
            "source compatibility commitment",
        ),
        problems,
        "NODAL-INC11-023",
        "v0.1 language reference",
    )
    _require(
        command,
        ('"check_increment11.py"', '"api"'),
        problems,
        "NODAL-INC11-024",
        "unified developer command",
    )
    _require(
        workflow,
        (
            "increment/11-public-api-design-gate-v0.1",
            "increment-11/public-api-freeze",
            "./nodal check",
            "--base-ref origin/dev",
        ),
        problems,
        "NODAL-INC11-025",
        "Increment 11 workflow",
    )
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
