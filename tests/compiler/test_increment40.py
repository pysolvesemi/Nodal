"""Permanent transfer integration and acceptance-state contracts."""
from __future__ import annotations
import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CHECK = load("scripts/check_increment40.py", "check_increment40")
MATRIX = load("tests/compiler/fixtures/increment40/run_native_matrix.py", "matrix_increment40")


class Increment40Tests(unittest.TestCase):
    def test_repository_contract(self):
        CHECK.check_repository()

    def test_native_fixture_carries_state_and_order(self):
        for kind, order in [("laplace_nd", "ascending_s"), ("zi_nd", "ascending_z_inverse")]:
            text = MATRIX.transfer(kind)
            self.assertIn(f'coefficient_order = "{order}"', text)
            self.assertIn('state_id = "TransferTop.filter.state"', text)
            self.assertIn('operator_id = "TransferTop.filter"', text)
        self.assertIn('numerator_size = 0 : i64', MATRIX.transfer(numerator=[]))

    def test_state_has_no_pure_trait(self):
        text = (ROOT / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td").read_text()
        definition = text.split("def Nodal_AnalogTransferOp", 1)[1].split("\ndef ", 1)[0]
        self.assertNotIn("Pure", definition)
        self.assertIn("let hasVerifier = 1", definition)

    def test_public_example_does_not_use_internal_apis(self):
        source = (ROOT / CHECK.REQUIRED[7]).read_text()
        self.assertNotIn("internal", source)
        self.assertNotIn("ConstructionKernel", source)
        self.assertIn("laplaceNd", source)
        self.assertIn("ziNd", source)

    def test_unqualified_claims_cannot_close_increment(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paths = (*CHECK.REQUIRED, CHECK.MANIFEST, "docs/roadmap/nodal-development-todo.md",
                     "core/compiler/test/CMakeLists.txt", "core/compiler/test/Unit/CMakeLists.txt")
            for path in paths:
                (root / path).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / path, root / path)
            CHECK.check_repository(root)
            manifest = root / CHECK.MANIFEST
            original = manifest.read_text()
            data = json.loads(original)
            data["status"] = "complete"
            manifest.write_text(json.dumps(data))
            with self.assertRaisesRegex(AssertionError, "accepted-evidence"):
                CHECK.check_repository(root)
            manifest.write_text(original)
            roadmap = root / "docs/roadmap/nodal-development-todo.md"
            roadmap.write_text(roadmap.read_text().replace("- [ ] **Increment 40", "- [x] **Increment 40"))
            with self.assertRaisesRegex(AssertionError, "premature roadmap"):
                CHECK.check_repository(root)


if __name__ == "__main__":
    unittest.main()
