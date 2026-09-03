#!/usr/bin/env python3
"""Align closure-era mutation tests with roadmap revision 1.45."""

from __future__ import annotations

import argparse
from pathlib import Path


def patch_revision_mutations(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    replacements = (
        (
            '"**Revision:** 1.44", "**Revision:** 1.43", 1',
            '"**Revision:** 1.45", "**Revision:** 1.43", 1',
        ),
        (
            '"**Revision:** 1.44",\n                    "**Revision:** 1.43",',
            '"**Revision:** 1.45",\n                    "**Revision:** 1.43",',
        ),
        (
            '"**Revision:** 1.44",\n                "**Revision:** 1.43",',
            '"**Revision:** 1.45",\n                "**Revision:** 1.43",',
        ),
    )
    for old, new in replacements:
        text = text.replace(old, new)
    path.write_text(text, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    for relative in (
        "tests/compiler/test_increment32.py",
        "tests/compiler/test_increment33.py",
        "tests/compiler/test_increment34.py",
    ):
        patch_revision_mutations(root / relative)
    evidence = root / "docs/implementation/increment34-evidence-closure.md"
    text = evidence.read_text(encoding="utf-8")
    text = text.replace(
        "**Status:** Validated evidence-closure candidate",
        "**Status:** Validated evidence closure",
        1,
    )
    evidence.write_text(text, encoding="utf-8")
    print("Increment 34 closure mutation targets aligned to roadmap revision 1.45.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
