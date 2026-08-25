#!/usr/bin/env python3
"""Align Increment 21 structural checks with explicit MLIR pass-option access."""

from __future__ import annotations

import argparse
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    path = args.root.resolve() / "scripts/check_increment21.py"
    text = path.read_text(encoding="utf-8")
    old = '            "verifyNodalPipeline(module, target)",\n'
    new = '            "verifyNodalPipeline(module, target.getValue())",\n'
    if new not in text:
        if text.count(old) != 1:
            raise SystemExit("Increment 21 checker pass-option fragment mismatch")
        text = text.replace(old, new, 1)
    path.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
