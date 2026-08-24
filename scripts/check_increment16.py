#!/usr/bin/env python3
"""Run the frozen Increment 16 validator with successor-safe roadmap handling.

The historical Increment 16 contract is retained verbatim in
``check_increment16_frozen.py``. Revision 1.20 and the unchecked Increment 17
entry are the completed Increment 16 baseline, not permanent exact repository
state.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import check_increment16_frozen as frozen

ROOT = frozen.ROOT
Problem = frozen.Problem

SUCCESSOR_CONTRACT_ANCHORS = (
    "- [x] **Increment 17 — ",
    "roadmap does not retain one Increment 17 origin graph",
)


def roadmap_revision(root: Path) -> tuple[int, ...]:
    roadmap = root / "docs/roadmap/nodal-development-todo.md"
    try:
        lines = roadmap.read_text(encoding="utf-8").splitlines()
    except OSError:
        return ()
    revisions = [
        line.removeprefix("**Revision:** ")
        for line in lines
        if line.startswith("**Revision:** ")
    ]
    if len(revisions) != 1:
        return ()
    try:
        return tuple(int(part) for part in revisions[0].split("."))
    except ValueError:
        return ()


def validate_files(root: Path = ROOT) -> list[Problem]:
    root = root.resolve()
    problems = frozen.validate_files(root)
    roadmap_path = root / "docs/roadmap/nodal-development-todo.md"
    try:
        roadmap = roadmap_path.read_text(encoding="utf-8")
    except OSError:
        roadmap = ""
    increment17 = [
        line
        for line in roadmap.splitlines()
        if line.startswith("- [") and "**Increment 17 — " in line
    ]
    if roadmap_revision(root) >= (1, 20) and len(increment17) == 1:
        problems = [
            problem
            for problem in problems
            if problem.code not in {"NODAL-INC16-032", "NODAL-INC16-035"}
        ]
    return problems


def run_compile(root: Path, problems: list[Problem]) -> None:
    frozen.run_compile(root, problems)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compile", action="store_true")
    args = parser.parse_args()
    problems = validate_files(ROOT)
    if args.compile and not problems:
        run_compile(ROOT, problems)
    if problems:
        for problem in problems:
            print(f"{problem.code}: {problem.message}")
        print(f"Increment 16 check failed with {len(problems)} problem(s)")
        return 1
    print("Increment 16 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
