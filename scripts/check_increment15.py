#!/usr/bin/env python3
"""Run the frozen Increment 15 validator with successor-safe roadmap revision handling.

The historical contract implementation is retained verbatim in
``check_increment15_frozen.py``.  This adapter changes only the roadmap revision
policy: revision 1.19 is the minimum completed Increment 15 baseline, not a
permanent exact-version requirement.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import check_increment15_frozen as frozen

ROOT = frozen.ROOT
Problem = frozen.Problem

# Successor-contract anchors consumed by Increment 16's repository checker.
SUCCESSOR_CONTRACT_ANCHORS = (
    "- [x] **Increment 16 — ",
    "roadmap does not retain one Increment 16 kernel",
)


def roadmap_revision(root: Path) -> tuple[int, ...]:
    roadmap = root / frozen.ROADMAP
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


def check_repository(root: Path) -> list[Problem]:
    root = root.resolve()
    problems = frozen.check_repository(root)
    revision = roadmap_revision(root)
    if revision >= (1, 19):
        problems = [problem for problem in problems if problem.code != "NODAL-INC15-059"]
    return problems


def check_compile_contracts(root: Path) -> list[Problem]:
    return frozen.check_compile_contracts(root)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--compile-negative", action="store_true")
    args = parser.parse_args(argv)

    problems = check_repository(args.root)
    if not problems and args.compile_negative:
        problems.extend(check_compile_contracts(args.root.resolve()))
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"Increment 15 check failed with {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("Increment 15 check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
