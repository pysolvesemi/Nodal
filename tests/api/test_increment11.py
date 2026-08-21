from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY_ROOT / "scripts" / "check_increment11.py"
SPEC = importlib.util.spec_from_file_location("check_increment11", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)


class Increment11ContractTests(unittest.TestCase):
    def copy_fixture(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        relatives = set(CHECKER.EXPECTED_FILES) | {"scripts/nodal.py"}
        for relative in relatives:
            source = REPOSITORY_ROOT / relative
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        return temporary, root

    def test_repository_satisfies_increment11_contract(self) -> None:
        self.assertEqual(CHECKER.check_repository(REPOSITORY_ROOT), [])

    def test_manifest_requires_parameter_preservation(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / CHECKER.MANIFEST
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["parameterized_hdl"]["preserve_by_default"] = False
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC11-011", codes)

    def test_library_subset_rejects_core_emit_entry_points(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / CHECKER.MANIFEST
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["library_author_subset"].extend(("Backend", "Nodal"))
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC11-014", codes)

    def test_parameterized_width_cannot_revert_to_constructor_only_int(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = (
            root
            / "examples/publicApiCandidates/src/prototypes/candidates/MixedSignalCandidates.scala"
        )
        text = path.read_text(encoding="utf-8")
        text = text.replace(
            "final class Adc extends Module:\n  val width = param(12.integer)",
            "final class Adc(val width: Int = 12) extends Module:",
        )
        path.write_text(text, encoding="utf-8")
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC11-018", codes)
        self.assertIn("NODAL-INC11-019", codes)

    def test_gate_must_forbid_parameter_erasure(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / CHECKER.GATE
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "must not be erased during elaboration",
                "may be erased during elaboration",
            ),
            encoding="utf-8",
        )
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC11-017", codes)

    def test_external_model_cannot_use_core_only_backend_entry(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / "examples/externalLibrary/src/external/reuse/GainStage.scala"
        path.write_text(
            path.read_text(encoding="utf-8") + "\nval backend = Backend.VerilogAMS\n",
            encoding="utf-8",
        )
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC11-022", codes)


if __name__ == "__main__":
    unittest.main()
