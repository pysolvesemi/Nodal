from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY_ROOT / "scripts" / "check_increment10.py"
SPEC = importlib.util.spec_from_file_location("check_increment10", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)


class Increment10ContractTests(unittest.TestCase):
    def copy_fixture(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        relatives = (
            set(CHECKER.EXPECTED_FILES)
            | set(CHECKER.V02_COMPATIBILITY_FILES)
            | {"build.mill", "scripts/nodal.py"}
        )
        for relative in relatives:
            source = REPOSITORY_ROOT / relative
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        return temporary, root

    def test_repository_satisfies_increment10_contract(self) -> None:
        self.assertEqual(CHECKER.check_repository(REPOSITORY_ROOT), [])

    def test_external_module_cannot_import_internal_packages(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / "examples/externalLibrary/src/external/reuse/GainStage.scala"
        path.write_text(
            path.read_text(encoding="utf-8") + "\nimport nodal.internal.frontend.*\n",
            encoding="utf-8",
        )
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC10-015", codes)
        self.assertIn("NODAL-INC10-016", codes)

    def test_required_prototype_matrix_is_enforced(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = (
            root
            / "examples/publicApiCandidates/src/prototypes/candidates/MixedSignalCandidates.scala"
        )
        path.write_text(
            path.read_text(encoding="utf-8").replace("final class Dac", "final class RemovedDac"),
            encoding="utf-8",
        )
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC10-014", codes)

    def test_candidate_gate_must_not_claim_an_api_freeze(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / "docs/design-gates/NodalPublicApiCandidates-DG-v0.1.md"
        path.write_text(
            path.read_text(encoding="utf-8").replace("not an API freeze", "an API freeze"),
            encoding="utf-8",
        )
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC10-019", codes)

    def test_removed_always_requires_complete_v02_migration_contract(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        manifest = root / "core/scala/api/public-api-v0.2.json"
        manifest.write_text(
            manifest.read_text(encoding="utf-8").replace(
                '"ordinary_always_allowed": false',
                '"ordinary_always_allowed": true',
            ),
            encoding="utf-8",
        )
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC10-024", codes)


if __name__ == "__main__":
    unittest.main()
