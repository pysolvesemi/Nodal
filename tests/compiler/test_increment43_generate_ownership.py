"""Preservation and rejection controls for the native ownership matrix."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent / "fixtures" / "increment43"))
import run_generate_count_matrix as counts
import run_generate_ownership_matrix as ownership


class GeneratedOwnershipCheckerTests(unittest.TestCase):
    def test_inventory_and_positive_preservation(self):
        cases = ownership.cases()
        self.assertEqual(len(cases), 31)
        self.assertEqual(len({case.name for case in cases}), len(cases))
        for case in cases:
            if case.code is None:
                data = counts.source(case).encode()
                self.assertTrue(ownership.valid_output(case, data, 0, data, b""), case.name)

    def test_node_and_module_loss_or_changed_identity_reject(self):
        case = next(case for case in ownership.cases() if case.name == "ownership_node")
        data = counts.source(case).encode()
        for old, new in [(b'"nodal.node"', b'"nodal.other"'),
                         (b'"nodal.module"', b'"nodal.other"'),
                         (b'generated_owner = "Fixture.outer"',
                          b'generated_owner = "Fixture.other"'),
                         (b'generated_induction = "Fixture.outer.index"',
                          b'generated_induction = "different"'),
                         (b'name = "tap"', b'name = "different"')]:
            with self.subTest(old=old):
                self.assertNotEqual(data.replace(old, new), data)
                self.assertFalse(ownership.valid_output(case, data, 0, data.replace(old, new), b""))

    def test_rejection_requires_real_exit_and_exact_diagnostic(self):
        case = next(case for case in ownership.cases() if case.code)
        data = counts.source(case).encode()
        self.assertTrue(ownership.valid_output(case, data, 1, b"", ownership.CODE.encode()))
        for code in (0, -11, 124, None):
            self.assertFalse(ownership.valid_output(case, data, code, b"", ownership.CODE.encode()))
        self.assertFalse(ownership.valid_output(case, data, 1, b"", b"NODAL-WRONG-001"))


if __name__ == "__main__":
    unittest.main()
