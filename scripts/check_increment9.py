#!/usr/bin/env python3
"""Validate Increment 9 formatting, linting, and contribution contracts."""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_FILES = (
    ".scalafmt.conf",
    ".scalafix.conf",
    ".clang-format",
    ".clang-tidy",
    ".github/change-policy.json",
    ".github/pull_request_template.md",
    "CONTRIBUTING.md",
    "toolchains/lint-lock.json",
    "scripts/bootstrap_lint_toolchain.py",
    "scripts/run_clang_tools.py",
    "scripts/check_markdown.py",
    "scripts/check_package_visibility.py",
    "scripts/check_contribution_policy.py",
    "scripts/check_increment9.py",
    "tests/lint/test_increment9.py",
    "docs/design-gates/README.md",
    "docs/development/style-and-contributions.md",
    ".github/workflows/increment-9-formatting-linting.yml",
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


def _json(path: Path, problems: list[Problem], code: str) -> dict[str, object]:
    try:
        value = json.loads(_read(path, problems, code))
    except json.JSONDecodeError as exc:
        problems.append(Problem(code, f"invalid JSON in {path}: {exc}"))
        return {}
    if not isinstance(value, dict):
        problems.append(Problem(code, f"{path} must contain a JSON object"))
        return {}
    return value


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    for relative in EXPECTED_FILES:
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-INC9-001", f"missing Increment 9 file: {relative}"))

    scalafmt = _read(root / ".scalafmt.conf", problems, "NODAL-INC9-002")
    scalafix = _read(root / ".scalafix.conf", problems, "NODAL-INC9-003")
    clang_format = _read(root / ".clang-format", problems, "NODAL-INC9-004")
    clang_tidy = _read(root / ".clang-tidy", problems, "NODAL-INC9-005")
    build = _read(root / "build.mill", problems, "NODAL-INC9-006")
    command = _read(root / "scripts/nodal.py", problems, "NODAL-INC9-007")
    workflow = _read(
        root / ".github/workflows/increment-9-formatting-linting.yml",
        problems,
        "NODAL-INC9-008",
    )
    generic_ci = _read(root / ".github/workflows/ci.yml", problems, "NODAL-INC9-009")
    contributing = _read(root / "CONTRIBUTING.md", problems, "NODAL-INC9-010")
    template = _read(
        root / ".github/pull_request_template.md",
        problems,
        "NODAL-INC9-011",
    )
    codeowners = _read(root / ".github/CODEOWNERS", problems, "NODAL-INC9-012")

    lock = _json(root / "toolchains/lint-lock.json", problems, "NODAL-INC9-013")
    expected_lock = {
        ("scala", "scalafmt_version"): "3.11.5",
        ("scala", "scalafix_version"): "0.14.7",
        ("scala", "scalafix_scala_version"): "3.8.4",
        ("native", "clang_format", "version"): "22.1.8",
        ("native", "clang_tidy", "version"): "22.1.8",
    }
    for path, expected in expected_lock.items():
        value: object = lock
        for key in path:
            value = value.get(key) if isinstance(value, dict) else None
        if value != expected:
            problems.append(
                Problem(
                    "NODAL-INC9-014",
                    f"lint lock {'.'.join(path)} is {value!r}, expected {expected!r}",
                )
            )

    required_scalafmt = ('version = "3.11.5"', "runner.dialect = scala3", "maxColumn = 100")
    required_scalafix = (
        "rules = [DisableSyntax]",
        "DisableSyntax.noNulls = true",
        "DisableSyntax.noReturns = true",
        "DisableSyntax.noThrows = true",
    )
    required_clang_format = ("BasedOnStyle: LLVM", "Standard: c++17", "ColumnLimit: 100")
    required_clang_tidy = ("clang-analyzer-core.*", "bugprone-use-after-move", "WarningsAsErrors: '*'")
    for content, fragments, code in (
        (scalafmt, required_scalafmt, "NODAL-INC9-015"),
        (scalafix, required_scalafix, "NODAL-INC9-016"),
        (clang_format, required_clang_format, "NODAL-INC9-017"),
        (clang_tidy, required_clang_tidy, "NODAL-INC9-018"),
    ):
        for fragment in fragments:
            if fragment not in content:
                problems.append(Problem(code, f"configuration lacks: {fragment}"))

    for fragment in (
        'val scalafix = "0.14.7"',
        "ch.epfl.scala:scalafix-cli_3.8.4",
        "object scalafix extends JavaModule",
        "def check():",
        "def fix():",
    ):
        if fragment not in build:
            problems.append(Problem("NODAL-INC9-019", f"build.mill lacks Scalafix integration: {fragment}"))

    for fragment in (
        '"style"',
        '"bootstrap_lint_toolchain.py"',
        '"check_markdown.py"',
        '"check_package_visibility.py"',
        '"check_contribution_policy.py"',
        '"check_increment9.py"',
        '"mill.scalalib.scalafmt/checkFormatAll"',
        '"scalafix/check"',
        '"run_clang_tools.py"',
        '"lint"',
    ):
        if fragment not in command:
            problems.append(Problem("NODAL-INC9-020", f"unified command lacks: {fragment}"))

    for content, code in ((workflow, "NODAL-INC9-021"), (generic_ci, "NODAL-INC9-022")):
        for fragment in (
            "./nodal style bootstrap",
            "--lint-toolchain",
            "--base-ref",
        ):
            if fragment not in content:
                problems.append(Problem(code, f"workflow lacks: {fragment}"))

    for section in ("## Summary", "## Validation", "## Design gate", "## Checklist"):
        if section not in template:
            problems.append(Problem("NODAL-INC9-023", f"pull-request template lacks: {section}"))
    for fragment in (
        "Increment <number> — <summary>",
        "./nodal style check",
        "approved design gate",
        "core/library boundary",
    ):
        if fragment not in contributing:
            problems.append(Problem("NODAL-INC9-024", f"CONTRIBUTING.md lacks: {fragment}"))
    for fragment in (
        "/.scalafmt.conf",
        "/.scalafix.conf",
        "/.clang-format",
        "/.clang-tidy",
        "/.github/change-policy.json",
        "/scripts/check_increment9.py",
        "/tests/lint/",
    ):
        if fragment not in codeowners:
            problems.append(Problem("NODAL-INC9-025", f"CODEOWNERS lacks: {fragment}"))
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args(argv)
    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"Increment 9 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 9 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
