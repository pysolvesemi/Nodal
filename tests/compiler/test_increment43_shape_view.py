"""Mutation controls for fixed shape-view native evidence."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent / "fixtures" / "increment43"))
import run_generate_count_matrix as counts
import run_shape_view_matrix as views


class ShapeViewCheckerTests(unittest.TestCase):
    def test_inventory_and_positive_preservation(self):
        cases = views.cases()
        self.assertEqual(len(cases), 31)
        self.assertEqual(len({case.name for case in cases}), len(cases))
        for case in cases:
            if case.code is None:
                data = counts.source(case).encode()
                self.assertTrue(views.valid_output(case, data, 0, data, b""), case.name)

    def test_contract_mutations_reject(self):
        case = views.cases()[0]
        data = counts.source(case).encode()
        for old, new in [(b'"3,2"', b'"6"'),
                         (b'materialization = "view"', b'materialization = "explicit_view"'),
                         (b'materialization = "view"', b'materialization = "unknown"'),
                         (b'source_path = "Fixture.result", storage = "structural"',
                          b'source_path = "Fixture.result", storage = "memory"'),
                         (b'storage = "structural"}, observability = "source_mapped", origin = "Fixture.samples"}> : (!nodal.shaped',
                          b'storage = "structural"}, observability = "hidden", origin = "Fixture.samples"}> : (!nodal.shaped'),
                         (b'!nodal.shaped<"3,2", f64>', b'!nodal.shaped<"2,3", f64>')]:
            with self.subTest(old=old):
                changed = data.replace(old, new, 1)
                self.assertNotEqual(changed, data)
                self.assertFalse(views.valid_output(case, data, 0, changed, b""))

    def test_rejection_requires_normal_exit_and_exact_diagnostic(self):
        case = next(case for case in views.cases() if case.code)
        data = counts.source(case).encode()
        self.assertTrue(views.valid_output(case, data, 1, b"", views.CODE.encode()))
        for code in (0, -11, 124, None):
            self.assertFalse(views.valid_output(case, data, code, b"", views.CODE.encode()))
        for diagnostic in (b"", b"NODAL-WRONG-001", views.CODE.encode() + b" NODAL-WRONG-001"):
            self.assertFalse(views.valid_output(case, data, 1, b"", diagnostic))

    def test_symbolic_proof_and_origin_mutations_reject(self):
        case = next(case for case in views.cases() if case.name == "view_repeated_symbol")
        data = counts.source(case).encode()
        for old, new in [(b'"lanes,lanes,2"', b'"lanes,2"'),
                         (b'value = 4 : i64', b'value = 3 : i64'),
                         (b'classification = "structural"', b'classification = "ordinary"'),
                         (b'"Fixture.result"', b'"Fixture.other"'),
                         (b'"Fixture.samples"', b'"Fixture.other"')]:
            with self.subTest(old=old):
                changed = data.replace(old, new, 1)
                self.assertNotEqual(changed, data)
                self.assertFalse(views.valid_output(case, data, 0, changed, b""))


if __name__ == "__main__":
    unittest.main()
