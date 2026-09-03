#!/usr/bin/env python3
"""Repair controller-generated Increment 34 v12 test and checker text."""

from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        if new in text:
            return text
        raise SystemExit(f"missing repair anchor: {label}")
    return text.replace(old, new, 1)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()

    tests = root / "tests/compiler/test_increment34.py"
    text = tests.read_text(encoding="utf-8")
    broken = 'path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")'
    fixed = 'path.write_text(json.dumps(document, indent=2) + "\\n", encoding="utf-8")'
    text = replace_once(text, broken, fixed, "generated JSON newline")
    tests.write_text(text, encoding="utf-8")

    checker = root / "scripts/check_increment34.py"
    text = checker.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '            "statically unreachable native regions",\n',
        '            "unreachable native regions",\n',
        "README structural token",
    )
    checker.write_text(text, encoding="utf-8")

    print("Increment 34 v12 controller repairs applied.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
