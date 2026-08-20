#!/usr/bin/env python3
"""Validate the Increment 8 generic CI, cache, report, and branch-policy contracts."""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path

EXPECTED_FILES = (
    ".github/CODEOWNERS",
    ".github/branch-policy.json",
    ".github/workflows/ci.yml",
    ".github/workflows/dependency-report.yml",
    "scripts/check_ci_baseline.py",
    "scripts/check_formatting_baseline.py",
    "scripts/dependency_report.py",
    "scripts/nodal.py",
    "tests/ci/README.md",
    "tests/ci/test_ci_baseline.py",
    "tests/ci/test_dependency_report.py",
    "tests/ci/test_formatting_baseline.py",
    "docs/development/ci.md",
    "docs/development/branching.md",
)

ALLOWED_CACHE_PATHS = {
    "~/.cache/coursier",
    "~/.cache/nodal/mill",
    "~/.cache/nodal/downloads",
}
FORBIDDEN_CACHE_FRAGMENTS = {
    "out",
    ".validation",
    ".native-build",
    ".toolchains",
    "nodal-native-toolchain",
}


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


def _cache_blocks(workflow: str) -> list[str]:
    lines = workflow.splitlines()
    blocks: list[str] = []
    start: int | None = None
    indentation = 0
    for index, line in enumerate(lines):
        if "uses: actions/cache@" in line:
            start = index
            indentation = len(line) - len(line.lstrip())
            continue
        if start is not None:
            current_indent = len(line) - len(line.lstrip())
            if line.lstrip().startswith("- name:") and current_indent <= indentation:
                blocks.append("\n".join(lines[start:index]))
                start = None
    if start is not None:
        blocks.append("\n".join(lines[start:]))
    return blocks


def _cache_paths(block: str) -> set[str]:
    lines = block.splitlines()
    paths: set[str] = set()
    in_path = False
    path_indent = 0
    for line in lines:
        stripped = line.strip()
        indent = len(line) - len(line.lstrip())
        if stripped.startswith("path:"):
            in_path = True
            path_indent = indent
            value = stripped.partition(":")[2].strip()
            if value and value != "|":
                paths.add(value)
            continue
        if in_path:
            if stripped and indent <= path_indent:
                in_path = False
                continue
            if stripped:
                paths.add(stripped)
    return paths


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []

    for relative in EXPECTED_FILES:
        if not (root / relative).is_file():
            problems.append(Problem("NODAL-CI-001", f"missing CI baseline file: {relative}"))

    core_ci = _read(
        root / ".github/workflows/ci.yml", problems, "NODAL-CI-002"
    )
    dependency = _read(
        root / ".github/workflows/dependency-report.yml",
        problems,
        "NODAL-CI-003",
    )
    command = _read(root / "scripts/nodal.py", problems, "NODAL-CI-004")
    docs = _read(root / "docs/development/ci.md", problems, "NODAL-CI-005")
    branching = _read(
        root / "docs/development/branching.md", problems, "NODAL-CI-006"
    )
    codeowners = _read(root / ".github/CODEOWNERS", problems, "NODAL-CI-007")

    required_core_ci = (
        "name: Core CI",
        "pull_request:",
        "- main",
        "- 'increment/**'",
        "workflow_dispatch:",
        "actions/checkout@v6",
        "actions/cache@v5",
        "./nodal check --contracts-only --online-toolchain",
        "./nodal core scala",
        "./nodal bootstrap",
        "./nodal core native",
        "name: required",
        "needs:",
        "CONTRACTS_RESULT",
        "SCALA_RESULT",
        "NATIVE_RESULT",
    )
    for fragment in required_core_ci:
        if fragment not in core_ci:
            problems.append(Problem("NODAL-CI-008", f"Core CI lacks: {fragment}"))

    blocks = _cache_blocks(core_ci)
    if len(blocks) != 2:
        problems.append(
            Problem("NODAL-CI-009", f"Core CI must define exactly two dependency caches, found {len(blocks)}")
        )
    observed_paths: set[str] = set()
    for block in blocks:
        paths = _cache_paths(block)
        observed_paths.update(paths)
        for path in paths:
            if path not in ALLOWED_CACHE_PATHS:
                problems.append(
                    Problem("NODAL-CI-010", f"unapproved CI cache path: {path}")
                )
            if any(fragment in path for fragment in FORBIDDEN_CACHE_FRAGMENTS):
                problems.append(
                    Problem("NODAL-CI-011", f"generated output must not be cached: {path}")
                )
    if observed_paths != ALLOWED_CACHE_PATHS:
        missing = sorted(ALLOWED_CACHE_PATHS - observed_paths)
        extra = sorted(observed_paths - ALLOWED_CACHE_PATHS)
        problems.append(
            Problem(
                "NODAL-CI-012",
                f"CI cache contract mismatch; missing={missing}, extra={extra}",
            )
        )

    required_dependency = (
        "name: Dependency report",
        "schedule:",
        "workflow_dispatch:",
        "contents: read",
        "issues: write",
        "scripts/dependency_report.py",
        "--online",
        "actions/upload-artifact@v7",
        "gh issue create",
        "gh issue edit",
        "updates_available",
    )
    for fragment in required_dependency:
        if fragment not in dependency:
            problems.append(
                Problem("NODAL-CI-013", f"dependency report workflow lacks: {fragment}")
            )
    for forbidden in (
        "contents: write",
        "git push",
        "git commit",
        "create-pull-request",
        "pull_request:",
        "update_file",
    ):
        if forbidden in dependency:
            problems.append(
                Problem(
                    "NODAL-CI-014",
                    f"dependency report must propose rather than apply changes: {forbidden}",
                )
            )

    try:
        policy = json.loads(
            _read(
                root / ".github/branch-policy.json",
                problems,
                "NODAL-CI-015",
            )
        )
    except json.JSONDecodeError as exc:
        problems.append(Problem("NODAL-CI-016", f"invalid branch policy JSON: {exc}"))
    else:
        expected_policy = {
            ("strategy",): "protected-trunk",
            ("integration_branch",): "main",
            ("release_branch",): "main",
            ("policy", "direct_push_to_main"): False,
            ("policy", "pull_request_required"): True,
            ("policy", "required_status_check"): "Core CI / required",
            ("policy", "future_increment_merge_method"): "squash",
            ("bootstrap", "head"): "increment/8-ci-baseline",
            ("bootstrap", "base"): "main",
            ("bootstrap", "merge_method"): "merge",
            ("dev_branch", "create_now"): False,
        }
        for path, expected in expected_policy.items():
            value = policy
            for key in path:
                if not isinstance(value, dict):
                    value = None
                    break
                value = value.get(key)
            if value != expected:
                problems.append(
                    Problem(
                        "NODAL-CI-017",
                        f"branch policy {'.'.join(path)} is {value!r}, expected {expected!r}",
                    )
                )

    required_command = (
        '"check_formatting_baseline.py"',
        '"check_ci_baseline.py"',
        '"ci"',
        "if args.contracts_only:",
        '"--contracts-only"',
    )
    for fragment in required_command:
        if fragment not in command:
            problems.append(
                Problem("NODAL-CI-018", f"unified command lacks CI integration: {fragment}")
            )

    for fragment in (
        "Core CI / required",
        "actions/cache@v5",
        "actions/upload-artifact@v7",
        "./nodal check --contracts-only",
        "generated build outputs",
        "report only",
    ):
        if fragment not in docs:
            problems.append(
                Problem("NODAL-CI-019", f"CI documentation lacks: {fragment}")
            )

    for fragment in (
        "protected trunk",
        "no long-lived `dev` branch",
        "increment/8-ci-baseline",
        "merge commit",
        "squash",
        "milestone tag",
    ):
        if fragment not in branching:
            problems.append(
                Problem("NODAL-CI-020", f"branching documentation lacks: {fragment}")
            )

    for fragment in (
        "/.github/workflows/",
        "/.github/branch-policy.json",
        "/scripts/check_ci_baseline.py",
        "/scripts/dependency_report.py",
        "/tests/ci/",
    ):
        if fragment not in codeowners:
            problems.append(
                Problem("NODAL-CI-021", f"CODEOWNERS lacks CI ownership: {fragment}")
            )

    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)

    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"CI baseline check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("CI baseline check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
