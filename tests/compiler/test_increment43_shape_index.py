"""Mutation controls for static shape-index native evidence."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent / "fixtures" / "increment43"))
import run_generate_count_matrix as counts
import run_shape_index_matrix as indexing


class ShapeIndexCheckerTests(unittest.TestCase):
    def test_inventory_and_positive_preservation(self):
        cases = indexing.cases()
        self.assertEqual(len(cases), 27)
        self.assertEqual(len({case.name for case in cases}), len(cases))
        for case in cases:
            if case.code is None:
                data = counts.source(case).encode()
                self.assertTrue(indexing.valid_output(case, data, 0, data, b""), case.name)

    def test_loss_type_value_axis_and_source_mutations_reject(self):
        case = indexing.cases()[1]
        data = counts.source(case).encode()
        for old, new in [(b'"nodal.shape_index"', b'"nodal.other"'),
                         (b'value = 2 : index', b'value = 0 : index'),
                         (b'%shape, %i0, %i1', b'%shape, %i1, %i0'),
                         (b'-> f64', b'-> i64'),
                         (b'"Fixture.selected"', b'"Fixture.wrong"'),
                         (b'"structural"', b'"memory"'),
                         (b'"2,3"', b'"3,2"')]:
            with self.subTest(old=old):
                changed = data.replace(old, new)
                self.assertNotEqual(changed, data)
                self.assertFalse(indexing.valid_output(case, data, 0, changed, b""))
        renamed = data.replace(b'%i0', b'%renamed0').replace(b'%i1', b'%renamed1')
        self.assertTrue(indexing.valid_output(case, data, 0, renamed, b""))
        self.assertFalse(indexing.valid_output(case, data, 0, b"", b""))

    def test_rejection_requires_normal_exit_and_exact_diagnostic(self):
        case = next(case for case in indexing.cases() if case.code)
        data = counts.source(case).encode()
        self.assertTrue(indexing.valid_output(case, data, 1, b"", indexing.CODE.encode()))
        for code in (0, -11, 124, None):
            self.assertFalse(indexing.valid_output(case, data, code, b"", indexing.CODE.encode()))
        for diagnostic in (b"", b"NODAL-WRONG-001", indexing.CODE.encode() + b" NODAL-WRONG-001"):
            self.assertFalse(indexing.valid_output(case, data, 1, b"", diagnostic))


if __name__ == "__main__":
    unittest.main()
