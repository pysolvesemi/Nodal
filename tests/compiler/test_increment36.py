"""Mutation hardening for waveform scope, required integration and closure evidence."""
import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("increment36", ROOT / "scripts/check_increment36.py")
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class Increment36Tests(unittest.TestCase):
    def test_repository(self):
        CHECKER.check_repository(ROOT)

    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in CHECKER.FILES:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, destination)
        return temporary, root

    def test_rejects_each_disabled_semantic_obligation(self):
        manifest = json.loads((ROOT / "tests/compiler/fixtures/increment36/manifest.json").read_text())
        for key in manifest["semantics"]:
            with self.subTest(key=key):
                temporary, root = self.fixture()
                with temporary:
                    path = root / "tests/compiler/fixtures/increment36/manifest.json"
                    document = json.loads(path.read_text())
                    document["semantics"][key] = False
                    path.write_text(json.dumps(document))
                    with self.assertRaises(CHECKER.CheckFailure):
                        CHECKER.check_repository(root)

    def test_premature_closure_is_rejected(self):
        temporary, root = self.fixture()
        with temporary:
            path = root / "docs/roadmap/nodal-development-todo.md"
            path.write_text(path.read_text().replace("- [ ] **Increment 36", "- [x] **Increment 36"))
            with self.assertRaisesRegex(CHECKER.CheckFailure, "premature roadmap closure"):
                CHECKER.check_repository(root)

    def test_forged_evidence_is_rejected(self):
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment36/manifest.json"
            document = json.loads(path.read_text())
            document["validation"] = {"accepted_head": "0" * 40}
            path.write_text(json.dumps(document))
            with self.assertRaisesRegex(CHECKER.CheckFailure, "cannot claim closure"):
                CHECKER.check_repository(root)

    def test_missing_native_integration_is_rejected(self):
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp"
            path.write_text(path.read_text().replace("verifyTimeWaveformOperation", "missingVerifier"))
            with self.assertRaises(CHECKER.CheckFailure):
                CHECKER.check_repository(root)

    def test_negative_native_matrix_has_exact_independent_mutations(self):
        spec = importlib.util.spec_from_file_location("native36", ROOT / "tests/compiler/fixtures/increment36/run_native_matrix.py")
        assert spec and spec.loader
        matrix = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(matrix)
        original = matrix.FIXTURE.read_text()
        cases = matrix.mutations(original)
        self.assertGreaterEqual(len(cases), 25)
        self.assertEqual(len({case[0] for case in cases}), len(cases))
        self.assertTrue(all(fixture != original for _, fixture, _ in cases))
        self.assertEqual({diagnostic for _, _, diagnostic in cases},
                         {f"NODAL-ANALOG-036-{index:03d}" for index in range(1, 9)})


if __name__ == "__main__":
    unittest.main()
