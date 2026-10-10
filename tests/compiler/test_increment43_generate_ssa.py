"""Mutation controls for the compound-bound native evidence checker."""

import sys
import unittest
from pathlib import Path


FIXTURES = Path(__file__).parent / "fixtures" / "increment43"
sys.path.insert(0, str(FIXTURES))
import run_generate_count_matrix as counts
import run_generate_ssa_matrix as ssa


class GenerateSsaCheckerTests(unittest.TestCase):
    def setUp(self):
        self.case = ssa.cases()[0]
        self.data = counts.source(self.case).encode()

    def test_original_and_ssa_renaming(self):
        self.assertTrue(ssa.valid_output(self.case, self.data, 0, self.data, b""))
        renamed = self.data.replace(b"%bound", b"%renamed")
        self.assertTrue(ssa.valid_output(self.case, self.data, 0, renamed, b""))

    def test_ordered_operands_are_not_interchangeable(self):
        changed = self.data.replace(b"(%zero, %bound, %one)", b"(%bound, %zero, %one)")
        self.assertFalse(ssa.valid_output(self.case, self.data, 0, changed, b""))

    def test_missing_operand_rejects(self):
        changed = self.data.replace(b"(%zero, %bound, %one)", b"(%zero, %bound)")
        self.assertFalse(ssa.valid_output(self.case, self.data, 0, changed, b""))

    def test_changed_dag_edges_operator_literal_and_parameter_reject(self):
        for old, new in [
            (b"(%count, %one)", b"(%count, %count)"),
            (b'operator_name = "add"', b'operator_name = "sub"'),
            (b"value = 1 : i64", b"value = 2 : i64"),
            (b"parameter = @COUNT", b"parameter = @OTHER"),
            (b'source_path = "bound"', b'source_path = "different"'),
            (b"maximum_trip_count = 5 : i64", b"maximum_trip_count = 4 : i64"),
            (b'region_id = "Fixture.outer"', b'region_id = "Wrong"'),
            (b": (i64, i64) -> i64", b": (i64, i64) -> i32"),
        ]:
            with self.subTest(old=old):
                changed = self.data.replace(old, new)
                self.assertNotEqual(changed, self.data)
                self.assertFalse(ssa.valid_output(self.case, self.data, 0, changed, b""))

    def test_missing_expression_definition_rejects(self):
        changed = b"\n".join(line for line in self.data.split(b"\n")
                              if not line.startswith(b"%bound ="))
        self.assertFalse(ssa.valid_output(self.case, self.data, 0, changed, b""))

    def test_crash_timeout_wrong_diagnostic_and_empty_output_reject(self):
        negative = next(case for case in ssa.cases() if case.code is not None)
        for exit_code in (0, -11, 124, None):
            self.assertFalse(ssa.valid_output(negative, self.data, exit_code, b"",
                                             negative.code.encode()))
        self.assertFalse(ssa.valid_output(negative, self.data, 1, b"", b"NODAL-WRONG-001"))
        self.assertFalse(ssa.valid_output(self.case, self.data, 0, b"", b""))
        self.assertTrue(ssa.valid_output(negative, self.data, 1, b"", negative.code.encode()))

    def test_fixture_inventory_and_positive_contracts(self):
        cases = ssa.cases()
        self.assertEqual(len(cases), len({case.name for case in cases}))
        self.assertEqual(len(counts.cases()), 49)
        for case in cases:
            if case.code is None:
                data = counts.source(case).encode()
                self.assertTrue(ssa.valid_output(case, data, 0, data, b""), case.name)


if __name__ == "__main__":
    unittest.main()
