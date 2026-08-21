#!/usr/bin/env python3
"""Check Markdown structure and repository-local links."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED_PARTS = {
    ".git",
    ".toolchains",
    ".validation",
    ".native-build",
    ".bsp",
    ".bloop",
    ".metals",
    ".scala-build",
    "out",
}
FENCE = re.compile(r"^ {0,3}(`{3,}|~{3,})(.*)$")
HEADING = re.compile(r"^(#{1,6})\s+\S")
LINK = re.compile(r"(?<!!)\[[^\]]*\]\(([^)]+)\)")
SCHEME = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*:")


@dataclass(frozen=True)
class Problem:
    code: str
    path: Path
    line: int
    message: str

    def __str__(self) -> str:
        return f"{self.code}: {self.path}:{self.line}: {self.message}"


def markdown_files(root: Path) -> list[Path]:
    return sorted(
        path
        for path in root.rglob("*.md")
        if path.is_file() and not any(part in EXCLUDED_PARTS for part in path.parts)
    )


def _link_target(raw: str) -> str:
    value = raw.strip()
    if value.startswith("<") and ">" in value:
        value = value[1 : value.index(">")]
    elif " " in value:
        value = value.split(" ", 1)[0]
    return unquote(value)


def check_file(path: Path, root: Path) -> list[Problem]:
    problems: list[Problem] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as exc:
        return [Problem("NODAL-MD-001", path.relative_to(root), 1, f"cannot read UTF-8 Markdown: {exc}")]

    fence_char: str | None = None
    fence_length = 0
    fence_line = 0
    previous_heading = 0
    saw_heading = False
    for number, line in enumerate(lines, start=1):
        match = FENCE.match(line)
        if match:
            marker = match.group(1)
            if fence_char is None:
                fence_char = marker[0]
                fence_length = len(marker)
                fence_line = number
            elif marker[0] == fence_char and len(marker) >= fence_length:
                fence_char = None
                fence_length = 0
                fence_line = 0
            continue
        if fence_char is not None:
            continue

        heading = HEADING.match(line)
        if heading:
            level = len(heading.group(1))
            if not saw_heading and level != 1:
                problems.append(
                    Problem(
                        "NODAL-MD-002",
                        path.relative_to(root),
                        number,
                        "the first heading must be level 1",
                    )
                )
            if previous_heading and level > previous_heading + 1:
                problems.append(
                    Problem(
                        "NODAL-MD-003",
                        path.relative_to(root),
                        number,
                        f"heading jumps from level {previous_heading} to {level}",
                    )
                )
            previous_heading = level
            saw_heading = True

        for raw_target in LINK.findall(line):
            target = _link_target(raw_target)
            if not target or target.startswith("#") or SCHEME.match(target):
                continue
            file_target = target.split("#", 1)[0].split("?", 1)[0]
            if not file_target:
                continue
            candidate = (path.parent / file_target).resolve()
            try:
                candidate.relative_to(root.resolve())
            except ValueError:
                problems.append(
                    Problem(
                        "NODAL-MD-004",
                        path.relative_to(root),
                        number,
                        f"local link escapes the repository: {target}",
                    )
                )
                continue
            if not candidate.exists():
                problems.append(
                    Problem(
                        "NODAL-MD-005",
                        path.relative_to(root),
                        number,
                        f"local link target does not exist: {target}",
                    )
                )

    if fence_char is not None:
        problems.append(
            Problem(
                "NODAL-MD-006",
                path.relative_to(root),
                fence_line,
                "fenced code block is not closed",
            )
        )
    if lines and not saw_heading:
        problems.append(
            Problem(
                "NODAL-MD-007",
                path.relative_to(root),
                1,
                "Markdown document has no heading",
            )
        )
    return problems


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    return [problem for path in markdown_files(root) for problem in check_file(path, root)]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args(argv)
    problems = check_repository(args.root)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"Markdown check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Markdown check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
