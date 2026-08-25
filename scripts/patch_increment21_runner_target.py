#!/usr/bin/env python3
"""Let the target-capability negative fixture select its declared target profile."""

from __future__ import annotations

import argparse
from pathlib import Path

OLD = '''    for filename, stage, code in NEGATIVE:
        fixture = ir / filename
        result = execute(
            [
                nodalc,
                f"--pass-pipeline=builtin.module(nodal-verify-stage{{stage={stage} target=core}})",
                str(fixture),
            ]
        )'''

NEW = '''    for filename, stage, code in NEGATIVE:
        fixture = ir / filename
        target = "auto" if filename == "increment21-invalid-target.mlir" else "core"
        result = execute(
            [
                nodalc,
                f"--pass-pipeline=builtin.module(nodal-verify-stage{{stage={stage} target={target}}})",
                str(fixture),
            ]
        )'''


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    path = args.root.resolve() / "core/compiler/test/run_increment21_tests.py"
    text = path.read_text(encoding="utf-8")
    if NEW not in text:
        if text.count(OLD) != 1:
            raise SystemExit("Increment 21 target test-loop source mismatch")
        text = text.replace(OLD, NEW, 1)
    path.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
