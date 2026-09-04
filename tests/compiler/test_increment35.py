from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CHECKER_PATH = ROOT / "scripts" / "check_increment35.py"
SPEC = importlib.util.spec_from_file_location("check_increment35", CHECKER_PATH)
assert SPEC is not None and SPEC.loader is not None
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class Increment35ContractTests(unittest.TestCase):
    def test_repository_checkpoint_passes(self) -> None:
        CHECKER.check_repository(ROOT)

    def test_manifest_remains_open_until_evidence_closure(self) -> None:
        manifest = json.loads(
            (ROOT / "tests/compiler/fixtures/increment35/manifest.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(manifest["status"], "implementation-in-progress")
        self.assertIsNone(manifest["validation"])

    def test_all_stable_diagnostics_are_declared(self) -> None:
        manifest = json.loads(
            (ROOT / "tests/compiler/fixtures/increment35/manifest.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(
            manifest["diagnostics"],
            [f"NODAL-ANALOG-035-{index:03d}" for index in range(1, 9)],
        )


if __name__ == "__main__":
    unittest.main()
