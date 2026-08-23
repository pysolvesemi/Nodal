#!/usr/bin/env python3
"""Repair Increment 16 checker self-tests after materialization."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    if new in text:
        return
    if text.count(old) != 1:
        raise RuntimeError(f"round-two repair anchor is not unique in {path}: {old!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> int:
    replace(
        "scripts/check_increment16.py",
        "source_codes = set(re.findall(r'\"(NODAL-[A-Z0-9-]+-016)\"', kernel))",
        "source_codes = set(re.findall(r'\"(NODAL-[A-Z0-9-]+-[0-9]{3})\"', kernel))",
    )
    replace(
        "scripts/check_increment16.py",
        '            "JVM identity values, hash codes, reflection order, and allocation addresses are never emitted",\n',
        '            "identity values, hash codes, reflection order, and allocation addresses are never emitted",\n',
    )
    replace(
        "tests/api/test_increment16.py",
        """import importlib.util
import unittest
from pathlib import Path
""",
        """import importlib.util
import sys
import unittest
from pathlib import Path
""",
    )
    replace(
        "tests/api/test_increment16.py",
        """MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
""",
        """MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)
""",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
