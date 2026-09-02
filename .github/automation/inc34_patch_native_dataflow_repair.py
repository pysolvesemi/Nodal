#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()

    verifier = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
    text = verifier.read_text(encoding="utf-8")
    if "#include <iterator>" not in text:
        text = replace_once(
            text,
            "#include <optional>\n",
            "#include <iterator>\n#include <optional>\n",
            "structured dataflow iterator include",
        )
    old = '''  if (!declaration)
    return nodal::emitMappedFailure(
               operation, "NODAL-ANALOG-034-014",
               "structured procedural variable operand must resolve to an analog_variable")
        .failed()
        ? failure()
        : failure();
'''
    new = '''  if (!declaration) {
    (void)nodal::emitMappedFailure(
        operation, "NODAL-ANALOG-034-014",
        "structured procedural variable operand must resolve to an analog_variable");
    return failure();
  }
'''
    if old in text:
        text = text.replace(old, new, 1)
    elif "structured procedural variable operand must resolve" not in text:
        raise SystemExit("structured variable diagnostic was not found")
    verifier.write_text(text, encoding="utf-8")

    tests = root / "tests/compiler/test_increment34.py"
    text = tests.read_text(encoding="utf-8")
    old = '''                    "intersectStructuredStates",
                    "removedIntersectStructuredStates",
                    1,
'''
    new = '''                    "body->continues.begin()",
                    "body->breaks.begin()",
                    1,
'''
    if old in text:
        text = text.replace(old, new, 1)
    tests.write_text(text, encoding="utf-8")

    print("Increment 34 native dataflow repair applied.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
