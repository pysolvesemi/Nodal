from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY_ROOT / "scripts" / "check_increment12.py"
SPEC = importlib.util.spec_from_file_location("check_increment12", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)


class Increment12ContractTests(unittest.TestCase):
    def copy_fixture(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        relatives = set(CHECKER.EXPECTED_FILES) | {
            ".github/CODEOWNERS",
            "scripts/check_developer_commands.py",
            "scripts/nodal.py",
            "tests/developer/test_developer_commands.py",
        }
        for relative in relatives:
            source = REPOSITORY_ROOT / relative
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        return temporary, root

    def test_repository_satisfies_increment12_contract(self) -> None:
        self.assertEqual(CHECKER.check_repository(REPOSITORY_ROOT), [])

    def test_ordinary_always_cannot_return_to_public_api(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / "core/scala/api/src/nodal/CandidateApi.scala"
        path.write_text(
            path.read_text(encoding="utf-8")
            + "\ndef always(event: Event)(body: => Unit): Unit = ()\n",
            encoding="utf-8",
        )
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC12-021", codes)

    def test_manifest_must_keep_ordinary_always_disabled(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / "core/scala/api/public-api-v0.2.json"
        payload = json.loads(path.read_text(encoding="utf-8"))
        payload["clock_reset"]["ordinary_always_allowed"] = True
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC12-022", codes)

    def test_diagnostics_require_source_spans(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / "core/scala/api/clock-reset-diagnostics-v0.2.json"
        payload = json.loads(path.read_text(encoding="utf-8"))
        payload["source_location"]["required"] = False
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC12-028", codes)

    def test_negative_fixture_mode_and_code_are_frozen(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / CHECKER.FIXTURE_MANIFEST
        payload = json.loads(path.read_text(encoding="utf-8"))
        payload["negative"][2]["mode"] = "semantic-contract"
        payload["negative"][2]["code"] = "NODAL-CDC-999"
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC12-035", codes)

    def test_external_fixture_cannot_import_internal_api(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / "examples/externalLibrary/src/external/reuse/ClockedRegister.scala"
        path.write_text(
            path.read_text(encoding="utf-8") + "\nimport nodal.internal.frontend.*\n",
            encoding="utf-8",
        )
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC12-045", codes)

    def test_roadmap_increment_must_remain_closed(self) -> None:
        temporary, root = self.copy_fixture()
        self.addCleanup(temporary.cleanup)
        path = root / "docs/roadmap/nodal-development-todo.md"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "- [x] **Increment 12 — Clock/reset public API v0.2 freeze and contract fixtures**",
                "- [ ] **Increment 12 — Clock/reset public API v0.2 freeze and contract fixtures**",
            ),
            encoding="utf-8",
        )
        codes = {problem.code for problem in CHECKER.check_repository(root)}
        self.assertIn("NODAL-INC12-054", codes)


if __name__ == "__main__":
    unittest.main()
