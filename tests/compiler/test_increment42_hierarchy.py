"""Controls for the native hierarchy harness; these are not compiler qualification."""

from pathlib import Path
import importlib.util
import subprocess
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT / "tests/compiler/fixtures/increment42/run_hierarchy_matrix.py"
SPEC = importlib.util.spec_from_file_location("increment42_hierarchy_matrix", PATH)
MATRIX = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MATRIX)


class HierarchyHarnessTests(unittest.TestCase):
    def record(self, returncode, stdout=b"", stderr=b"", **expected):
        with tempfile.TemporaryDirectory() as temporary:
            matrix = MATRIX.Matrix(Path("/unused-test-compiler"), Path(temporary))
            result = subprocess.CompletedProcess([], returncode, stdout, stderr)
            with mock.patch.object(MATRIX.subprocess, "run", return_value=result):
                matrix.check("control", "module {}\n", **expected)
            return matrix.records[0]

    def test_independent_reference_includes_self_edges_and_duplicates(self):
        self.assertTrue(MATRIX.acyclic([[], []]))
        self.assertTrue(MATRIX.acyclic([[1, 1], []]))
        self.assertFalse(MATRIX.acyclic([[0]]))
        self.assertFalse(MATRIX.acyclic([[], [2], [1]]))

    def test_all_three_vertex_graphs_have_known_acyclic_count(self):
        arcs = [(a, b) for a in range(3) for b in range(3)]
        count = 0
        for bits in range(512):
            edges = [[], [], []]
            for index, (a, b) in enumerate(arcs):
                if bits & (1 << index):
                    edges[a].append(b)
            count += MATRIX.acyclic(edges)
        # 25 labeled DAGs on three vertices, independently enumerable by hand.
        self.assertEqual(count, 25)

    def test_success_requires_output(self):
        self.assertFalse(self.record(0)["passed"])
        self.assertTrue(self.record(0, b"module {}\n")["passed"])

    def test_success_cannot_drop_definitions(self):
        self.assertFalse(self.record(0, b"module {}\n", expected_modules=2)["passed"])

    def test_crash_is_not_a_diagnostic_rejection(self):
        self.assertFalse(self.record(-11, stderr=b"NODAL-VERIFY-HIERARCHY-005",
                                     code="NODAL-VERIFY-HIERARCHY-005")["passed"])

    def test_wrong_diagnostic_is_not_a_rejection(self):
        self.assertFalse(self.record(1, stderr=b"parser failure",
                                     code="NODAL-VERIFY-HIERARCHY-005")["passed"])

    def test_negative_requires_failure_not_a_printed_code(self):
        self.assertFalse(self.record(0, stderr=b"NODAL-VERIFY-HIERARCHY-005",
                                     code="NODAL-VERIFY-HIERARCHY-005")["passed"])

    def test_negative_cannot_publish_partial_ir(self):
        self.assertFalse(self.record(1, b"module {}", b"NODAL-VERIFY-HIERARCHY-005",
                                     code="NODAL-VERIFY-HIERARCHY-005")["passed"])

    def test_location_must_be_primary_error(self):
        diagnostic = b"Other.scala:1:1: error: NODAL-VERIFY-HIERARCHY-005\nHierarchy42.scala:80:7"
        self.assertFalse(self.record(1, stderr=diagnostic,
                                     code="NODAL-VERIFY-HIERARCHY-005",
                                     location="Hierarchy42.scala:80:7")["passed"])
        diagnostic = b"Hierarchy42.scala:80:7: error: NODAL-VERIFY-HIERARCHY-005"
        self.assertTrue(self.record(1, stderr=diagnostic,
                                    code="NODAL-VERIFY-HIERARCHY-005",
                                    location="Hierarchy42.scala:80:7")["passed"])

    def test_repeat_must_be_byte_identical(self):
        self.assertFalse(self.record(0, b"module {}", same_output=b"module {}\n")["passed"])

    def test_timeout_is_recorded_as_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            matrix = MATRIX.Matrix(Path("/unused-test-compiler"), Path(temporary))
            with mock.patch.object(MATRIX.subprocess, "run",
                                   side_effect=subprocess.TimeoutExpired("nodalc", 60)):
                matrix.check("timeout-control", "module {}")
            self.assertFalse(matrix.records[0]["passed"])

    def test_production_integration_and_ctest_are_not_optional(self):
        production = (ROOT / "core/compiler/lib/Transforms/Passes.cpp").read_text()
        begin = production.index("LogicalResult verifyHierarchy(")
        end = production.index("LogicalResult verifyTypes(", begin)
        hierarchy = production[begin:end]
        self.assertIn("orderHierarchy(edges)", hierarchy)
        self.assertIn("nodal.verify.hierarchy_closed", hierarchy)
        self.assertIn("NODAL-VERIFY-HIERARCHY-004", hierarchy)
        self.assertIn("NODAL-VERIFY-HIERARCHY-005", hierarchy)
        self.assertNotIn("std::function", hierarchy)
        cmake = (ROOT / "core/compiler/test/Unit/CMakeLists.txt").read_text()
        self.assertIn("nodal.native.hierarchy-integration", cmake)
        self.assertIn("run_hierarchy_matrix.py", cmake)
        self.assertIn("$<TARGET_FILE:nodalc>", cmake)


if __name__ == "__main__":
    unittest.main()
