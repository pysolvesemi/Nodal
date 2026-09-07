"""Repository closure guard; executed native/Scala tests establish behavioral evidence."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("check_increment39", ROOT / "scripts/check_increment39.py")
assert spec and spec.loader
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class NoiseContractTests(unittest.TestCase):
    def test_repository_contract(self):
        checker.check_repository(ROOT)

    def test_predecessor_evidence_is_untouched(self):
        import hashlib
        evidence = (ROOT / "docs/implementation/increment38-accepted-evidence.json").read_bytes()
        self.assertEqual(hashlib.sha256(evidence).hexdigest(),
                         "0f36a914f9ae31c5659f259fe934665478c723e14c7e22f1c08148ce37545d9c")

    def test_premature_acceptance_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = checker.REQUIRED + (checker.MANIFEST, "docs/roadmap/nodal-development-todo.md", "core/compiler/test/CMakeLists.txt")
            for path in paths:
                output = root / path
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes((ROOT / path).read_bytes())
            checker.check_repository(root)
            path = root / checker.MANIFEST
            manifest = json.loads(path.read_text())
            manifest["status"] = "validated-noise-operators"
            path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(AssertionError, "accepted-evidence"):
                checker.check_repository(root)


if __name__ == "__main__":
    unittest.main()
