#!/usr/bin/env python3
"""Materialize Increment 21 with LLVM StringMap API compatibility."""

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
        [sys.executable, str(Path(__file__).with_name("materialize_increment21_v6.py")), str(root)],
        check=True,
    )

    path = root / "core/compiler/lib/Transforms/NodalVerification.cpp"
    text = path.read_text(encoding="utf-8")
    text = text.replace("->second", "->getValue()")
    path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
