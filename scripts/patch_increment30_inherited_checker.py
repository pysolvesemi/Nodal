#!/usr/bin/env python3
"""Teach the Increment 24 checker about Increment 30's shared verifier ownership."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "scripts/check_increment24.py"
TEST = ROOT / "tests/compiler/test_increment24.py"

checker = CHECKER.read_text(encoding="utf-8")

old_expected = '''    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",\n    "core/compiler/lib/Transforms/Passes.cpp",'''
new_expected = '''    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",\n    "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp",\n    "core/compiler/lib/Transforms/Passes.cpp",'''
if checker.count(old_expected) != 1:
    raise RuntimeError("Increment 24 expected-file anchor is not unique")
checker = checker.replace(old_expected, new_expected, 1)

old_reads = '''    cpp = read(root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp", problems, "NODAL-INC24-004")\n    passes = read(root / "core/compiler/lib/Transforms/Passes.cpp", problems, "NODAL-INC24-005")'''
new_reads = '''    cpp = read(root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp", problems, "NODAL-INC24-004")\n    analog_numeric = read(\n        root / "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp",\n        problems,\n        "NODAL-INC24-004",\n    )\n    passes = read(root / "core/compiler/lib/Transforms/Passes.cpp", problems, "NODAL-INC24-005")'''
if checker.count(old_reads) != 1:
    raise RuntimeError("Increment 24 source-read anchor is not unique")
checker = checker.replace(old_reads, new_reads, 1)

old_require = '''    require(cpp, tuple(f"LogicalResult nodal::{name}::verify()" for name in (\n        "AnalogOp", "RealLiteralOp", "ParameterRefOp", "AnalogAddOp", "AnalogSubOp",\n        "AnalogMulOp", "AnalogDivOp", "AnalogDdtOp", "ContributeOp"\n    )) + CODES, problems, "NODAL-INC24-004", "analog verifiers")'''
new_require = '''    require(cpp, tuple(f"LogicalResult nodal::{name}::verify()" for name in (\n        "AnalogOp", "RealLiteralOp", "ParameterRefOp", "AnalogAddOp", "AnalogSubOp",\n        "AnalogMulOp", "AnalogDivOp", "AnalogDdtOp", "ContributeOp"\n    )), problems, "NODAL-INC24-004", "analog operation verifier entry points")\n    verifier_sources = cpp + "\\n" + analog_numeric\n    require(\n        verifier_sources,\n        tuple(code for code in CODES if code != "NODAL-ANALOG-ARITHMETIC-001"),\n        problems,\n        "NODAL-INC24-004",\n        "analog verifier diagnostics",\n    )\n    require(\n        analog_numeric,\n        (\n            "verifyBinary",\n            "NODAL-ANALOG-TYPE-001",\n            "NODAL-ANALOG-PROMOTION-001",\n            "NODAL-ANALOG-DIMENSION-001",\n            "NODAL-ANALOG-DIVIDE-001",\n        ),\n        problems,\n        "NODAL-INC24-004",\n        "successor analog arithmetic diagnostics",\n    )'''
if checker.count(old_require) != 1:
    raise RuntimeError("Increment 24 verifier requirement anchor is not unique")
checker = checker.replace(old_require, new_require, 1)
CHECKER.write_text(checker, encoding="utf-8")

test = TEST.read_text(encoding="utf-8")
insert = '''\n    def test_rejects_missing_shared_numeric_verifier(self) -> None:\n        temporary, root = self.temporary_repository()\n        self.addCleanup(temporary.cleanup)\n        path = root / "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp"\n        path.write_text(\n            path.read_text(encoding="utf-8").replace(\n                "NODAL-ANALOG-DIMENSION-001",\n                "NODAL-ANALOG-MISSING-DIMENSION",\n            ),\n            encoding="utf-8",\n        )\n        self.assertIn("NODAL-INC24-004", self.codes(root))\n'''
anchor = '\n\nif __name__ == "__main__":'
if insert.strip() not in test:
    if test.count(anchor) != 1:
        raise RuntimeError("Increment 24 test insertion anchor is not unique")
    test = test.replace(anchor, insert + anchor, 1)
TEST.write_text(test, encoding="utf-8")

print("Increment 24 inherited checker now follows shared analog verifier ownership")
