"""Mutation controls for native structural slice preservation and rejection."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent / "fixtures" / "increment43"))
import run_generate_count_matrix as counts
import run_shape_slice_matrix as slices


class ShapeSliceCheckerTests(unittest.TestCase):
    def test_inventory_and_positive_preservation(self):
        cases = slices.cases()
        self.assertEqual(len(cases), 46)
        self.assertEqual(len({case.name for case in cases}), len(cases))
        for case in cases:
            if case.code is None:
                data = counts.source(case).encode()
                self.assertTrue(slices.valid_output(case, data, 0, data, b""), case.name)

    def test_identity_and_operand_mutations_reject(self):
        case = slices.cases()[0]
        data = counts.source(case).encode()
        for old, new in ((b'axis = 0', b'axis = 1'),
                         (b'"2,3"', b'"2,2"'),
                         (b'%source, %lower, %upper, %length', b'%source, %upper, %lower, %length'),
                         (b'%source, %lower, %upper, %length', b'%source, %lower, %upper, %upper'),
                         (b'access = "read_only"', b'access = "read_write"'),
                         (b'storage = "structural"', b'storage = "memory"'),
                         (b'origin = "Fixture.samples"', b'origin = "Fixture.other"'),
                         (b'input_path = "Fixture.samples"', b'input_path = "Fixture.other"'),
                         (b'semantic_path = "Fixture.result"', b'semantic_path = "Fixture.other"')):
            with self.subTest(old=old):
                changed = data.replace(old, new, 1)
                self.assertNotEqual(data, changed)
                self.assertFalse(slices.valid_output(case, data, 0, changed, b""))

    def test_symbolic_length_and_ssa_names(self):
        case = next(case for case in slices.cases() if case.name == "slice_symbolic_endpoints")
        data = counts.source(case).encode()
        renamed = data
        for old, new in ((b'%lower', b'%90'), (b'%upper', b'%91'),
                         (b'%length', b'%92'), (b'%source', b'%93'), (b'%result', b'%94')):
            renamed = renamed.replace(old, new)
        self.assertTrue(slices.valid_output(case, data, 0, renamed, b""))
        for old, new in ((b'operator_name = "sub"', b'operator_name = "add"'),
                         (b'"nodal.const_expr"(%upper, %lower)',
                          b'"nodal.const_expr"(%lower, %upper)'),
                         (b'parameter = @high', b'parameter = @low'),
                         (b'semantic_path = "Fixture.length"', b'semantic_path = "Fixture.other"')):
            changed = data.replace(old, new, 1)
            self.assertNotEqual(data, changed)
            self.assertFalse(slices.valid_output(case, data, 0, changed, b""))

    def test_rejection_requires_normal_exit_and_exact_diagnostic(self):
        case = next(case for case in slices.cases() if case.code)
        data = counts.source(case).encode()
        self.assertTrue(slices.valid_output(case, data, 1, b"", slices.CODE.encode()))
        for code in (0, -11, 124, None):
            self.assertFalse(slices.valid_output(case, data, code, b"", slices.CODE.encode()))
        for diagnostic in (b"", b"NODAL-WRONG-001", slices.CODE.encode() + b" NODAL-WRONG-001"):
            self.assertFalse(slices.valid_output(case, data, 1, b"", diagnostic))


if __name__ == "__main__":
    unittest.main()
