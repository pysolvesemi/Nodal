#!/usr/bin/env python3
"""Validate Increment 10 compile-only public API candidate prototypes."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_FILES = (
    "core/scala/api/src/nodal/CandidateApi.scala",
    "examples/externalLibrary/src/external/reuse/GainStage.scala",
    "examples/publicApiCandidates/src/prototypes/candidates/AnalogCandidates.scala",
    "examples/publicApiCandidates/src/prototypes/candidates/MixedSignalCandidates.scala",
    "examples/publicApiCandidates/src/prototypes/candidates/ExternalReuseCandidate.scala",
    "docs/design-gates/NodalPublicApiCandidates-DG-v0.1.md",
    "docs/development/public-api-candidates.md",
    "scripts/check_increment10.py",
    "tests/api/test_increment10.py",
    ".github/workflows/increment-10-public-api-candidates.yml",
)
V02_COMPATIBILITY_FILES = (
    "core/scala/api/public-api-v0.2.json",
    "docs/design-gates/NodalClockResetApi-DG-v0.2.md",
    "docs/migrations/public-api-v0.1-to-v0.2.md",
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


def _check_ordinary_always_compatibility(
    root: Path,
    api: str,
    problems: list[Problem],
) -> None:
    """Accept Increment 10's candidate `always` or its approved v0.2 migration."""

    if "def always(" in api:
        return

    for relative in V02_COMPATIBILITY_FILES:
        if not (root / relative).is_file():
            problems.append(
                Problem(
                    "NODAL-INC10-023",
                    "ordinary always was removed without the complete v0.2 compatibility contract: "
                    + relative,
                )
            )

    manifest = _read(
        root / "core/scala/api/public-api-v0.2.json",
        problems,
        "NODAL-INC10-024",
    )
    gate = _read(
        root / "docs/design-gates/NodalClockResetApi-DG-v0.2.md",
        problems,
        "NODAL-INC10-024",
    )
    migration = _read(
        root / "docs/migrations/public-api-v0.1-to-v0.2.md",
        problems,
        "NODAL-INC10-024",
    )
    _require(
        manifest,
        (
            '"api_version": "0.2"',
            '"removed_from_ordinary_subset"',
            '"always"',
            '"ordinary_always_allowed": false',
            '"v0.1_ordinary_always"',
        ),
        problems,
        "NODAL-INC10-024",
        "v0.2 public API compatibility manifest",
    )
    _require(
        gate,
        (
            "**Status:** Approved",
            "**Scope:** public-api",
            "ordinary synchronous state",
            "NODAL-MIGRATION-001",
        ),
        problems,
        "NODAL-INC10-024",
        "v0.2 clock/reset design gate",
    )
    _require(
        migration,
        (
            "v0.1",
            "v0.2",
            "always(clock.rising)",
            "ClockDomain",
            "Reg",
            "NODAL-MIGRATION-001",
        ),
        problems,
        "NODAL-INC10-024",
        "v0.1-to-v0.2 migration note",
    )


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    for relative in EXPECTED_FILES:
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-INC10-001", f"missing Increment 10 file: {relative}"))

    api = _read(
        root / "core/scala/api/src/nodal/CandidateApi.scala",
        problems,
        "NODAL-INC10-002",
    )
    analog = _read(
        root / "examples/publicApiCandidates/src/prototypes/candidates/AnalogCandidates.scala",
        problems,
        "NODAL-INC10-003",
    )
    mixed = _read(
        root / "examples/publicApiCandidates/src/prototypes/candidates/MixedSignalCandidates.scala",
        problems,
        "NODAL-INC10-004",
    )
    external = _read(
        root / "examples/externalLibrary/src/external/reuse/GainStage.scala",
        problems,
        "NODAL-INC10-005",
    )
    external_consumer = _read(
        root
        / "examples/publicApiCandidates/src/prototypes/candidates/ExternalReuseCandidate.scala",
        problems,
        "NODAL-INC10-006",
    )
    build = _read(root / "build.mill", problems, "NODAL-INC10-007")
    gate = _read(
        root / "docs/design-gates/NodalPublicApiCandidates-DG-v0.1.md",
        problems,
        "NODAL-INC10-008",
    )
    guide = _read(
        root / "docs/development/public-api-candidates.md",
        problems,
        "NODAL-INC10-009",
    )
    command = _read(root / "scripts/nodal.py", problems, "NODAL-INC10-010")
    workflow = _read(
        root / ".github/workflows/increment-10-public-api-candidates.yml",
        problems,
        "NODAL-INC10-011",
    )

    _require(
        api,
        (
            "sealed trait Expr",
            "sealed trait Real",
            "sealed trait Integer",
            "sealed trait Bool",
            "sealed trait Bits",
            "sealed trait UInt",
            "final class Param",
            "abstract class Module",
            "case object Electrical",
            "def nature(",
            "def discipline(",
            "def analog(",
            "def initial(",
            "def V[",
            "def I[",
            "def ddt(",
            "def idt(",
            "def cross(",
            "def timer(",
            "def transition(",
            "infix def <+",
            "infix def :=",
            "def param[A <: Data](select:",
        ),
        problems,
        "NODAL-INC10-012",
        "candidate API",
    )
    _check_ordinary_always_compatibility(root, api, problems)

    for forbidden in ("NodalComponent", "NodalModule", "NodalParam", "nodal.internal"):
        if forbidden in api:
            problems.append(
                Problem("NODAL-INC10-013", f"candidate API contains forbidden spelling: {forbidden}")
            )

    prototype_sources = analog + "\n" + mixed + "\n" + external_consumer
    _require(
        prototype_sources,
        (
            "final class Resistor",
            "final class RcFilter",
            "final class Comparator",
            "final class Adc",
            "final class Dac",
            "final class HierarchyAndOverride",
            ".param(_.resistance",
            "cross(",
            "timer(",
            "final class MixedSignalHold",
            "final class ExternalReuseCandidate",
        ),
        problems,
        "NODAL-INC10-014",
        "prototype matrix",
    )

    imports = tuple(
        line.strip()
        for line in external.splitlines()
        if line.strip().startswith("import ")
    )
    if imports != ("import nodal.*",):
        problems.append(
            Problem(
                "NODAL-INC10-015",
                "external reusable module must import exactly the proposed public nodal.* surface",
            )
        )
    if re.search(r"\bnodal\.(?:internal|bootstrap)\b", external):
        problems.append(
            Problem("NODAL-INC10-016", "external reusable module imports a core internal package")
        )

    _require(
        build,
        (
            "object examples extends Module",
            "object externalLibrary extends NodalScalaModule",
            "def moduleDeps = Seq(core.scala.api)",
            "object publicApiCandidates extends NodalScalaModule",
            "def moduleDeps = Seq(core.scala.api, externalLibrary)",
            '"examples"',
        ),
        problems,
        "NODAL-INC10-017",
        "build.mill",
    )
    external_module = re.search(
        r"object externalLibrary.*?(?=\n\s*object publicApiCandidates)",
        build,
        flags=re.DOTALL,
    )
    if external_module is not None and "frontend" in external_module.group(0):
        problems.append(
            Problem("NODAL-INC10-018", "external reusable module must not depend on frontend")
        )

    _require(
        gate,
        (
            "**Status:** Superseded",
            "**Scope:** public-api",
            "not an API freeze",
            "Increment 11",
            "external reusable module",
            "`Module`",
            "`Param`",
            "`<+`",
        ),
        problems,
        "NODAL-INC10-019",
        "candidate design gate",
    )
    _require(
        guide,
        (
            "compile",
            "do not elaborate hardware",
            "examples.externalLibrary",
            "examples.publicApiCandidates",
            "Candidate comparison",
        ),
        problems,
        "NODAL-INC10-020",
        "candidate guide",
    )
    _require(
        command,
        ('"check_increment10.py"', '"api"'),
        problems,
        "NODAL-INC10-021",
        "unified developer command",
    )
    _require(
        workflow,
        (
            "increment/10-public-api-candidate-prototypes",
            "increment-10/public-api-candidates",
            "./nodal check",
            "--base-ref origin/dev",
        ),
        problems,
        "NODAL-INC10-022",
        "Increment 10 workflow",
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
        print(f"Increment 10 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 10 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
