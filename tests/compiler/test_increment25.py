from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_increment25.py"
SPEC = importlib.util.spec_from_file_location("check_increment25", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)

SUPPORT_FILES = ("docs/roadmap/nodal-development-todo.md",)


class Increment25CheckerTests(unittest.TestCase):
    def temporary_repository(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in dict.fromkeys(CHECKER.EXPECTED_FILES + SUPPORT_FILES):
            source = ROOT / relative
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        return temporary, root

    def codes(self, root: Path) -> set[str]:
        return {problem.code for problem in CHECKER.check_repository(root)}

    def test_repository_contract(self) -> None:
        self.assertEqual(CHECKER.check_repository(ROOT), [])

    def test_rejects_missing_exact_golden(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        (root / "tests/compiler/fixtures/increment25/golden/rc-filter.va").unlink()
        self.assertIn("NODAL-INC25-001", self.codes(root))

    def test_rejects_bridge_that_drops_operation_identity(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                '"NODAL-RC-OPERATION-001"',
                '"NODAL-RC-REMOVED-001"',
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC25-005", self.codes(root))

    def test_rejects_writable_permanent_workflow(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-25-rc-filter-vertical-slice.yml"
        path.write_text(
            path.read_text(encoding="utf-8").replace("contents: read", "contents: write"),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC25-011", self.codes(root))

    def test_accepts_validated_successor_state(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        manifest_path = root / "tests/compiler/fixtures/increment25/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "validated-rc-vertical-slice"
        manifest["evidence"] = {
            "pull_request": 1,
            "dedicated_run": 2,
            "core_ci_run": 3,
        }
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(
            roadmap.read_text(encoding="utf-8")
            .replace("**Revision:** 1.30", "**Revision:** 1.31", 1)
            .replace(
                "- [ ] **Increment 25 — RC filter end-to-end vertical slice**",
                "- [x] **Increment 25 — RC filter end-to-end vertical slice**",
                1,
            ),
            encoding="utf-8",
        )
        self.assertEqual(CHECKER.check_repository(root), [])


if __name__ == "__main__":
    unittest.main()
