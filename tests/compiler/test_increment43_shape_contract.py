"""Mutation controls for static shaped-declaration native evidence."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent / "fixtures" / "increment43"))
import run_generate_count_matrix as counts
import run_shape_contract_matrix as shapes


class ShapeContractCheckerTests(unittest.TestCase):
    def test_inventory_and_positive_preservation(self):
        cases = shapes.cases()
        self.assertEqual(len(cases), 14)
        self.assertEqual(len({case.name for case in cases}), len(cases))
        for case in cases:
            if case.code is None:
                data = counts.source(case).encode()
                self.assertTrue(shapes.valid_output(case, data, 0, data, b""), case.name)

    def test_compound_path_operator_dependency_and_shape_mutations_reject(self):
        case = next(case for case in shapes.cases() if case.name == "shape_compound")
        data = counts.source(case).encode()
        for old, new in [(b'"Fixture.extent,2"', b'"Fixture.other,2"'),
                         (b'semantic_path = "Fixture.extent"',
                          b'source_path = "Fixture.extent"'),
                         (b'operator_name = "add"', b'operator_name = "sub"'),
                         (b'parameter = @lanes', b'parameter = @other'),
                         (b'classification = "structural"',
                          b'classification = "ordinary"'),
                         (b'effects = ["shape"]', b'effects = ["topology"]')]:
            with self.subTest(old=old):
                changed = data.replace(old, new, 1)
                self.assertNotEqual(changed, data)
                self.assertFalse(shapes.valid_output(case, data, 0, changed, b""))

    def test_rejection_requires_normal_exit_and_exact_diagnostic(self):
        case = next(case for case in shapes.cases() if case.code)
        data = counts.source(case).encode()
        self.assertTrue(shapes.valid_output(case, data, 1, b"", shapes.CODE.encode()))
        for code in (0, -11, 124, None):
            self.assertFalse(shapes.valid_output(case, data, code, b"", shapes.CODE.encode()))
        for diagnostic in (b"", b"NODAL-WRONG-001",
                           shapes.CODE.encode() + b" NODAL-WRONG-001"):
            self.assertFalse(shapes.valid_output(case, data, 1, b"", diagnostic))


if __name__ == "__main__":
    unittest.main()
