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

    def reopen(self, root):
        manifest = root / "tests/compiler/fixtures/increment36/manifest.json"
        document = json.loads(manifest.read_text())
        document["status"] = "implementation-in-progress"
        document["validation"] = None
        manifest.write_text(json.dumps(document))
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(roadmap.read_text().replace(
            "- [x] **Increment 36", "- [ ] **Increment 36", 1))
        implementation = root / "docs/implementation/increment36-time-waveform-operators.md"
        implementation.write_text(implementation.read_text().replace(
            "**Status:** Validated", "**Status:** Implementation in progress", 1))

    def test_open_state_remains_supported(self):
        temporary, root = self.fixture()
        with temporary:
            self.reopen(root)
            CHECKER.check_repository(root)

    def test_premature_closure_is_rejected(self):
        temporary, root = self.fixture()
        with temporary:
            self.reopen(root)
            path = root / "docs/roadmap/nodal-development-todo.md"
            path.write_text(path.read_text().replace("- [ ] **Increment 36", "- [x] **Increment 36"))
            with self.assertRaisesRegex(CHECKER.CheckFailure, "premature roadmap closure"):
                CHECKER.check_repository(root)

    def test_forged_evidence_is_rejected(self):
        temporary, root = self.fixture()
        with temporary:
            self.reopen(root)
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


    def test_validated_manifest_has_complete_accepted_identity(self):
        manifest = json.loads((ROOT / "tests/compiler/fixtures/increment36/manifest.json").read_text())
        self.assertEqual(manifest["status"], "validated-time-waveform-operators")
        self.assertEqual(manifest["validation"]["implementation_pull_request"], 118)
        self.assertEqual(len(manifest["validation"]["exact_head_workflows"]), 26)

    def test_every_accepted_field_is_required(self):
        accepted = json.loads((ROOT / "docs/implementation/increment36-accepted-evidence.json").read_text())
        for field in accepted:
            with self.subTest(field=field):
                temporary, root = self.fixture()
                with temporary:
                    manifest = root / "tests/compiler/fixtures/increment36/manifest.json"
                    document = json.loads(manifest.read_text())
                    del document["validation"][field]
                    manifest.write_text(json.dumps(document))
                    with self.assertRaisesRegex(CHECKER.CheckFailure, "closure evidence differs"):
                        CHECKER.check_repository(root)

    def test_each_workflow_run_identity_is_locked(self):
        accepted = json.loads((ROOT / "docs/implementation/increment36-accepted-evidence.json").read_text())
        for name in accepted["exact_head_workflows"]:
            with self.subTest(workflow=name):
                temporary, root = self.fixture()
                with temporary:
                    manifest = root / "tests/compiler/fixtures/increment36/manifest.json"
                    document = json.loads(manifest.read_text())
                    document["validation"]["exact_head_workflows"][name] = 1
                    manifest.write_text(json.dumps(document))
                    with self.assertRaisesRegex(CHECKER.CheckFailure, "closure evidence differs"):
                        CHECKER.check_repository(root)

    def test_altering_both_evidence_copies_is_rejected(self):
        temporary, root = self.fixture()
        with temporary:
            for relative in ("tests/compiler/fixtures/increment36/manifest.json",
                             "docs/implementation/increment36-accepted-evidence.json"):
                path = root / relative
                document = json.loads(path.read_text())
                target = document.get("validation", document)
                target["accepted_head"] = "0" * 40
                path.write_text(json.dumps(document))
            with self.assertRaisesRegex(CHECKER.CheckFailure, "evidence checksum changed"):
                CHECKER.check_repository(root)

    def test_missing_malformed_and_unsupported_states_are_rejected(self):
        for state in (None, "evidence-closure-candidate", "complete", False, {}, []):
            with self.subTest(state=state):
                temporary, root = self.fixture()
                with temporary:
                    manifest = root / "tests/compiler/fixtures/increment36/manifest.json"
                    document = json.loads(manifest.read_text())
                    document["status"] = state
                    manifest.write_text(json.dumps(document))
                    with self.assertRaisesRegex(CHECKER.CheckFailure, "unsupported"):
                        CHECKER.check_repository(root)

    def test_roadmap_closure_consistency_is_required(self):
        mutations = (
            ("- [x] **Increment 36", "- [ ] **Increment 36"),
            ("**Revision:** 1.47", "**Revision:** 1.46"),
            ("- [ ] **Increment 37", "- [x] **Increment 37"),
            ("- [x] **Increment 36 — Time and waveform operators**",
             "- [x] **Increment 36 — Time and waveform operators**\\n"
             "- [ ] **Increment 36 — Time and waveform operators**"),
        )
        for old, new in mutations:
            with self.subTest(mutation=old):
                temporary, root = self.fixture()
                with temporary:
                    path = root / "docs/roadmap/nodal-development-todo.md"
                    text = path.read_text()
                    self.assertIn(old, text)
                    path.write_text(text.replace(old, new, 1))
                    with self.assertRaises(CHECKER.CheckFailure):
                        CHECKER.check_repository(root)

    def test_human_record_must_correlate_to_accepted_identity(self):
        temporary, root = self.fixture()
        with temporary:
            evidence = json.loads((root / "docs/implementation/increment36-accepted-evidence.json").read_text())
            path = root / "docs/implementation/increment36-evidence-closure.md"
            path.write_text(path.read_text().replace(evidence["accepted_head"], "0" * 40))
            with self.assertRaisesRegex(CHECKER.CheckFailure, "closure record omits"):
                CHECKER.check_repository(root)


if __name__ == "__main__":
    unittest.main()
