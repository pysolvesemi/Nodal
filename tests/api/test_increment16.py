#!/usr/bin/env python3
"""Unit coverage for the Increment 16 repository contract checker."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "scripts/check_increment16.py"
SPEC = importlib.util.spec_from_file_location("check_increment16", CHECKER)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class Increment16ContractTests(unittest.TestCase):
    def test_repository_contract(self) -> None:
        problems = MODULE.validate_files(ROOT)
        self.assertEqual([], problems, "\n".join(f"{p.code}: {p.message}" for p in problems))


if __name__ == "__main__":
    unittest.main()
