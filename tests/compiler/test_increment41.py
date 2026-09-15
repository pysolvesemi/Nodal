"""Permanent function integration and acceptance-state contracts."""
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


CHECK = load("scripts/check_increment41.py", "check_increment41")
MATRIX = load("tests/compiler/fixtures/increment41/run_native_matrix.py", "matrix_increment41")


class Increment41Tests(unittest.TestCase):
    def test_repository_contract(self):
        CHECK.check_repository()

    def test_native_fixture_has_first_class_definitions_calls_and_return(self):
        text = MATRIX.fixture()
        for operation in ("nodal.analog_user_function", "nodal.analog_function_value",
                          "nodal.analog_user_call", "nodal.analog_function_return"):
            self.assertIn(operation, text)
        self.assertIn('return_type = ' + MATRIX.VOLTAGE, text)
        self.assertIn('callee = @scaleSignal', text)

    def test_calls_do_not_claim_unproved_folding_or_purity(self):
        text = (ROOT / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td").read_text()
        definition = text.split("def Nodal_AnalogUserCallOp", 1)[1].split("\ndef ", 1)[0]
        self.assertNotIn("Pure", definition)
        self.assertIn("let hasVerifier = 1", definition)

    def test_public_example_does_not_use_internal_apis(self):
        source = (ROOT / CHECK.REQUIRED[7]).read_text()
        self.assertNotIn("internal", source)
        self.assertNotIn("ConstructionKernel", source)
        self.assertIn("AnalogFunction", source)
        self.assertIn("PhysicalDimension.Voltage", source)

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
            roadmap.write_text(roadmap.read_text().replace("- [ ] **Increment 41", "- [x] **Increment 41"))
            with self.assertRaisesRegex(AssertionError, "premature roadmap"):
                CHECK.check_repository(root)


if __name__ == "__main__":
    unittest.main()
