from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_increment27.py"
SPEC = importlib.util.spec_from_file_location("check_increment27", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)

SUPPORT = ("docs/roadmap/nodal-development-todo.md",)


class Increment27CheckerTests(unittest.TestCase):
    def temporary_repository(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in dict.fromkeys(CHECKER.EXPECTED_FILES + SUPPORT):
            source = ROOT / relative
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        return temporary, root

    def codes(self, root: Path) -> set[str]:
        return {problem.code for problem in CHECKER.check_repository(root)}

    def test_repository_contract(self) -> None:
        self.assertEqual(CHECKER.check_repository(ROOT), [])

    def test_rejects_missing_compatibility_helper(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/include/nodal/Dialect/Nodal/NatureDiscipline.h"
        path.write_text(path.read_text(encoding="utf-8").replace("areDisciplinesCompatible", "missingCompatibility", 1), encoding="utf-8")
        self.assertIn("NODAL-INC27-004", self.codes(root))

    def test_rejects_missing_import_cycle_resolution(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/NatureDiscipline.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace("resolveDeclaration", "missingResolver"), encoding="utf-8")
        self.assertIn("NODAL-INC27-005", self.codes(root))

    def test_rejects_missing_diagnostic(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/diagnostics-v0.1.json"
        path.write_text(path.read_text(encoding="utf-8").replace('      "NODAL-NATURE-TOLERANCE-001",\n', "", 1), encoding="utf-8")
        self.assertIn("NODAL-INC27-014", self.codes(root))

    def test_rejects_writable_workflow(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-27-natures-disciplines.yml"
        path.write_text(path.read_text(encoding="utf-8").replace("contents: read", "contents: write", 1), encoding="utf-8")
        self.assertIn("NODAL-INC27-013", self.codes(root))

    def test_rejects_premature_roadmap_closure(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(roadmap.read_text(encoding="utf-8").replace("- [ ] **Increment 27 — Natures and disciplines**", "- [x] **Increment 27 — Natures and disciplines**", 1), encoding="utf-8")
        self.assertIn("NODAL-INC27-016", self.codes(root))

    def test_accepts_validated_state(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        manifest_path = root / "tests/compiler/fixtures/increment27/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "validated-natures-disciplines"
        manifest["evidence"] = {"pull_request": 1, "dedicated_run": 2, "core_ci_run": 3}
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(roadmap.read_text(encoding="utf-8").replace("**Revision:** 1.32", "**Revision:** 1.33", 1).replace("- [ ] **Increment 27 — Natures and disciplines**", "- [x] **Increment 27 — Natures and disciplines**", 1), encoding="utf-8")
        self.assertEqual(CHECKER.check_repository(root), [])


if __name__ == "__main__":
    unittest.main()
