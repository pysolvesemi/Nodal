#!/usr/bin/env python3
"""Apply closure-state compatibility repairs after the Increment 34 materializer."""

from __future__ import annotations

import argparse
from pathlib import Path


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def patch_increment34_checker(root: Path) -> None:
    path = root / "scripts/check_increment34.py"
    text = read(path)
    old = '''    require(
        "**Revision:** 1.44" in roadmap
        and "- [x] **Increment 33 — Analog variables and procedural assignment**"
        in roadmap,
        "NODAL-INC34-007: roadmap does not contain the validated Increment 33 predecessor",
    )
'''
    new = '''    expected_roadmap_revision = "1.45" if validated else "1.44"
    require(
        f"**Revision:** {expected_roadmap_revision}" in roadmap
        and "- [x] **Increment 33 — Analog variables and procedural assignment**"
        in roadmap,
        "NODAL-INC34-007: roadmap does not contain the validated Increment 33 predecessor",
    )
'''
    text = replace_once(text, old, new, "Increment 34 predecessor roadmap revision")
    write(path, text)


def patch_increment32_checker(root: Path) -> None:
    path = root / "scripts/check_increment32.py"
    text = read(path)
    old = '"**Revision:** 1.44" in roadmap'
    new = '"**Revision:** 1.44" in roadmap or "**Revision:** 1.45" in roadmap'
    if old in text and new not in text:
        text = text.replace(old, f"({new})", 1)
        write(path, text)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    patch_increment34_checker(root)
    patch_increment32_checker(root)
    print("Increment 34 closure successor-state compatibility repaired.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
