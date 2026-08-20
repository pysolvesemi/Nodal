#!/usr/bin/env python3
"""Validate Nodal's pinned Scala 3 build bootstrap without invoking Mill."""

from __future__ import annotations

import argparse
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


EXPECTED_MILL = "1.1.7"
EXPECTED_SCALA = "3.8.4"
EXPECTED_JVM = "zulu:25"
EXPECTED_UTEST = "0.9.1"
EXPECTED_SOURCES = (
    "core/scala/api/src/nodal/bootstrap/api/BootstrapApi.scala",
    "core/scala/frontend/src/nodal/bootstrap/frontend/ElaboratedModule.scala",
    "core/scala/bridge/src/nodal/bootstrap/bridge/TextualMlir.scala",
    "core/integrations/src/nodal/bootstrap/integrations/ToolStatus.scala",
    "core/scala/sim/src/nodal/bootstrap/sim/SimulationPlan.scala",
    "core/scala/cli/src/nodal/bootstrap/cli/BootstrapCli.scala",
    "core/scala/testkit/src/nodal/bootstrap/testkit/BootstrapFixture.scala",
    "core/scala/testkit/test/src/nodal/bootstrap/testkit/BootstrapSmokeTests.scala",
)


def _read(path: Path, problems: list[Problem], code: str) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        problems.append(Problem(code, f"cannot read {path}: {exc}"))
        return ""


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    build = _read(root / "build.mill", problems, "NODAL-SCALA-001")
    mill_version = _read(root / ".mill-version", problems, "NODAL-SCALA-002").strip()
    wrapper = _read(root / "mill", problems, "NODAL-SCALA-003")
    wrapper_bat = _read(root / "mill.bat", problems, "NODAL-SCALA-004")

    expected_fragments = (
        f"//| mill-version: {EXPECTED_MILL}",
        f"//| mill-jvm-version: {EXPECTED_JVM}",
        f'val scala3 = "{EXPECTED_SCALA}"',
        f'mvn"com.lihaoyi::utest:{EXPECTED_UTEST}"',
    )
    for fragment in expected_fragments:
        if fragment not in build:
            problems.append(Problem("NODAL-SCALA-005", f"build.mill lacks required pin: {fragment}"))

    if mill_version != EXPECTED_MILL:
        problems.append(
            Problem(
                "NODAL-SCALA-006",
                f".mill-version must be {EXPECTED_MILL}, found {mill_version or '<empty>'}",
            )
        )

    for name, content in (("mill", wrapper), ("mill.bat", wrapper_bat)):
        if ".mill-version" not in content or "mill-dist" not in content:
            problems.append(
                Problem("NODAL-SCALA-007", f"{name} must resolve the pinned official Mill bootstrap")
            )

    forbidden = (
        re.compile(r"CrossScalaModule"),
        re.compile(r"Cross\s*\["),
        re.compile(r'2\.1[123]\.'),
        re.compile(r"scalaVersion\s*=\s*\"2\."),
    )
    for pattern in forbidden:
        if pattern.search(build):
            problems.append(
                Problem("NODAL-SCALA-008", f"Scala 2/cross-build construct is forbidden: {pattern.pattern}")
            )

    for relative in EXPECTED_SOURCES:
        path = root / relative
        if not path.is_file():
            problems.append(Problem("NODAL-SCALA-009", f"missing bootstrap source: {relative}"))
            continue
        content = _read(path, problems, "NODAL-SCALA-010")
        if "package nodal.bootstrap" not in content:
            problems.append(
                Problem("NODAL-SCALA-011", f"bootstrap source escapes temporary namespace: {relative}")
            )

    required_modules = (
        "object integrations extends NodalScalaModule",
        "object api extends NodalScalaModule",
        "object frontend extends NodalScalaModule",
        "object bridge extends NodalScalaModule",
        "object sim extends NodalScalaModule",
        "object cli extends NodalScalaModule",
        "object testkit extends NodalScalaModule",
        "object test extends ScalaTests",
    )
    for module in required_modules:
        if module not in build:
            problems.append(Problem("NODAL-SCALA-012", f"missing Mill module declaration: {module}"))

    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)

    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"Scala bootstrap check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1

    print("Scala bootstrap check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
