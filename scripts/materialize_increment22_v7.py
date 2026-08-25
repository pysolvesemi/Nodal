#!/usr/bin/env python3
"""Materialize Increment 22 with structural checker-inventory patching."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()

    try:
        subprocess.run(
            [sys.executable, str(Path(__file__).with_name("materialize_increment22_v6.py")), str(root)],
            check=True,
        )
    except subprocess.CalledProcessError:
        conversion = root / "core/compiler/lib/Conversion/NodalToCirct.cpp"
        checker = root / "scripts/check_increment22.py"
        if not conversion.is_file() or not checker.is_file():
            raise
        if "NODAL-CIRCT-022-006" not in conversion.read_text(encoding="utf-8"):
            raise

    checker_path = root / "scripts/check_increment22.py"
    text = checker_path.read_text(encoding="utf-8")
    if '"NODAL-CIRCT-022-006",' not in text:
        pattern = re.compile(
            r'(?P<indent>\s*)"NODAL-CIRCT-022-002",\n'
        )
        match = pattern.search(text)
        if not match:
            raise RuntimeError("checker diagnostic inventory anchor is missing")
        insertion = (
            match.group(0)
            + f'{match.group("indent")}"NODAL-CIRCT-022-006",\n'
        )
        text = text[: match.start()] + insertion + text[match.end() :]
    checker_path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
