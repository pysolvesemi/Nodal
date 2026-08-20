#!/usr/bin/env python3
"""Enforce the dependency-free text-formatting baseline used by Core CI."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

GENERATED_PARTS = {
    ".git",
    ".toolchains",
    ".native-build",
    ".validation",
    ".bsp",
    ".bloop",
    ".metals",
    ".scala-build",
    "out",
}
TEXT_SUFFIXES = {
    ".bat",
    ".cmake",
    ".cpp",
    ".h",
    ".json",
    ".md",
    ".py",
    ".scala",
    ".sh",
    ".toml",
    ".txt",
    ".yaml",
    ".yml",
}
TEXT_NAMES = {
    ".gitignore",
    ".mill-version",
    "CMakeLists.txt",
    "CODEOWNERS",
    "mill",
    "nodal",
}
INDENTATION_SENSITIVE = {
    ".cmake",
    ".json",
    ".py",
    ".scala",
    ".toml",
    ".yaml",
    ".yml",
}


@dataclass(frozen=True)
class Problem:
    code: str
    message: str

    def __str__(self) -> str:
        return f"{self.code}: {self.message}"


def _tracked_paths(root: Path) -> list[Path]:
    if (root / ".git").exists():
        try:
            completed = subprocess.run(
                ["git", "ls-files", "-z"],
                cwd=root,
                check=True,
                capture_output=True,
            )
        except (OSError, subprocess.CalledProcessError):
            pass
        else:
            return [
                root / value.decode("utf-8")
                for value in completed.stdout.split(b"\0")
                if value
            ]
    return [path for path in root.rglob("*") if path.is_file()]


def _is_candidate(root: Path, path: Path) -> bool:
    try:
        relative = path.resolve().relative_to(root.resolve())
    except ValueError:
        return False
    if any(part in GENERATED_PARTS for part in relative.parts):
        return False
    return path.suffix.lower() in TEXT_SUFFIXES or path.name in TEXT_NAMES


def _trailing_whitespace_allowed(path: Path, line: str) -> bool:
    return path.suffix.lower() == ".md" and line.endswith("  ") and not line.endswith("   ")


def check_file(path: Path, root: Path) -> list[Problem]:
    relative = path.resolve().relative_to(root.resolve())
    try:
        payload = path.read_bytes()
        text = payload.decode("utf-8")
    except OSError as exc:
        return [Problem("NODAL-FMT-001", f"cannot read {relative}: {exc}")]
    except UnicodeDecodeError as exc:
        return [Problem("NODAL-FMT-002", f"{relative} is not UTF-8: {exc}")]

    problems: list[Problem] = []
    if b"\r" in payload:
        problems.append(Problem("NODAL-FMT-003", f"{relative} must use LF line endings"))
    if text and not text.endswith("\n"):
        problems.append(Problem("NODAL-FMT-004", f"{relative} lacks a final newline"))

    for number, line in enumerate(text.splitlines(), start=1):
        if line.endswith((" ", "\t")) and not _trailing_whitespace_allowed(path, line):
            problems.append(
                Problem(
                    "NODAL-FMT-005",
                    f"{relative}:{number} has trailing whitespace",
                )
            )
        if path.suffix.lower() in INDENTATION_SENSITIVE:
            leading = line[: len(line) - len(line.lstrip(" \t"))]
            if "\t" in leading:
                problems.append(
                    Problem(
                        "NODAL-FMT-006",
                        f"{relative}:{number} uses a tab for indentation",
                    )
                )

    if path.suffix.lower() == ".json":
        try:
            json.loads(text)
        except json.JSONDecodeError as exc:
            problems.append(
                Problem("NODAL-FMT-007", f"{relative} contains invalid JSON: {exc}")
            )
    return problems


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems: list[Problem] = []
    for path in sorted(_tracked_paths(root)):
        if _is_candidate(root, path):
            problems.extend(check_file(path, root))
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)

    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(
            f"formatting baseline failed with {len(problems)} problem(s)",
            file=sys.stderr,
        )
        return 1
    print("formatting baseline passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
