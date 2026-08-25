#!/usr/bin/env python3
"""Apply the complete Increment 20 kernel and successor-aware checker patch."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()

    subprocess.run(
        [sys.executable, str(Path(__file__).with_name("patch_increment20_kernel.py")), str(root)],
        check=True,
    )

    path = root / "scripts/check_increment20.py"
    text = path.read_text(encoding="utf-8")
    old_definition = """    increment21_unchecked = (
        "- [ ] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**"
        in roadmap
    )
"""
    new_definition = """    increment21_unchecked = (
        "- [ ] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**"
        in roadmap
    )
    increment21_checked = (
        "- [x] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**"
        in roadmap
    )
"""
    if old_definition in text:
        text = text.replace(old_definition, new_definition, 1)
    elif new_definition not in text:
        raise RuntimeError("Increment 21 roadmap-state definition anchor is missing")

    old_check = """    if not increment21_unchecked:
        problems.append(
            Problem("NODAL-INC20-008", "Increment 21 must remain unchecked")
        )
"""
    new_check = """    if revision < (1, 25):
        if not increment21_unchecked:
            problems.append(
                Problem(
                    "NODAL-INC20-008",
                    "revision 1.24 freezes Increment 21 as unchecked",
                )
            )
    elif not (increment21_unchecked or increment21_checked):
        problems.append(
            Problem(
                "NODAL-INC20-008",
                "successor roadmap lacks an Increment 21 state",
            )
        )
"""
    if old_check in text:
        text = text.replace(old_check, new_check, 1)
    elif new_check not in text:
        raise RuntimeError("Increment 21 successor-state check anchor is missing")
    path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
