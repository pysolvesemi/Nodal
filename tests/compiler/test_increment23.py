from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_increment23.py"
SPEC = importlib.util.spec_from_file_location("check_increment23", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)


class Increment23CheckerTests(unittest.TestCase):
    def temporary_repository(
        self,
    ) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        support_files = (
            "tests/compiler/fixtures/increment24/manifest.json",
        )
        for relative in dict.fromkeys(CHECKER.EXPECTED_FILES + support_files):
            source = ROOT / relative
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        return temporary, root

    def codes(self, root: Path) -> set[str]:
        return {problem.code for problem in CHECKER.check_repository(root)}

    def test_repository_contract(self) -> None:
        self.assertEqual(CHECKER.check_repository(ROOT), [])

    def test_rejects_missing_translation_registration(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/Backend.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "nodal-to-verilog-ams",
                "removed-verilog-ams-translation",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC23-004", self.codes(root))

    def test_rejects_direct_publication_before_hooks(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/Backend.cpp"
        text = path.read_text(encoding="utf-8")
        text = text.replace(
            "  output << candidate;\n  return success();",
            "  return success();\n  output << candidate;",
            1,
        )
        path.write_text(text, encoding="utf-8")
        self.assertIn("NODAL-INC23-004", self.codes(root))

    def test_rejects_missing_target_reparse_hook(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/Backend.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "selectedHooks.reparseTarget",
                "removedHooks.reparseTarget",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC23-004", self.codes(root))

    def test_rejects_mutable_profile_layout(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/Backend.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                '"nodal.backend.shaped_layout"',
                '"nodal.backend.user_layout"',
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC23-004", self.codes(root))

    def test_rejects_missing_reserved_keyword_check(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/Backend.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace('    "input",', '    "input_removed",', 1),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC23-004", self.codes(root))

    def test_rejects_substring_terminator_counting(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/Backend.cpp"
        path.write_text(
            path.read_text(encoding="utf-8") + "\n// countOccurrences\n",
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC23-004", self.codes(root))

    def test_rejects_untyped_profile_owned_setting(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/Backend.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "Attribute raw = module->getAttr(attribute)",
                "auto raw = module->getAttrOfType<StringAttr>(attribute)",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC23-004", self.codes(root))

    def test_rejects_missing_backend_diagnostic(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/diagnostics-v0.1.json"
        catalog = json.loads(path.read_text(encoding="utf-8"))
        catalog["families"]["backend-framework"].remove(
            "NODAL-BACKEND-CAPABILITY-001"
        )
        path.write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-INC23-011", self.codes(root))

    def test_rejects_writable_permanent_workflow(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-23-backend-framework.yml"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "contents: read",
                "contents: write",
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC23-009", self.codes(root))

    def test_rejects_premature_roadmap_closure(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)

        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(
            roadmap.read_text(encoding="utf-8").replace(
                "- [ ] **Increment 23 — Backend framework and capability profiles**",
                "- [x] **Increment 23 — Backend framework and capability profiles**",
                1,
            ),
            encoding="utf-8",
        )

        manifest_path = root / "tests/compiler/fixtures/increment23/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "implemented-awaiting-evidence"
        manifest["evidence"] = {
            "pull_request": None,
            "dedicated_run": None,
            "core_ci_run": None,
        }
        manifest_path.write_text(
            json.dumps(manifest, indent=2) + "\n",
            encoding="utf-8",
        )

        self.assertIn("NODAL-INC23-010", self.codes(root))

    def test_accepts_validated_increment24_successor(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)

        manifest_path = root / "tests/compiler/fixtures/increment24/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "validated-minimal-analog-ir"
        manifest["evidence"] = {
            "pull_request": 1,
            "dedicated_run": 2,
            "core_ci_run": 3,
        }
        manifest_path.write_text(
            json.dumps(manifest, indent=2) + "\n",
            encoding="utf-8",
        )

        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(
            roadmap.read_text(encoding="utf-8").replace(
                "- [ ] **Increment 24 — Minimal analog expression and contribution IR**",
                "- [x] **Increment 24 — Minimal analog expression and contribution IR**",
                1,
            ),
            encoding="utf-8",
        )
        self.assertEqual(CHECKER.check_repository(root), [])

    def test_accepts_validated_closure_state(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)

        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(
            roadmap.read_text(encoding="utf-8")
            .replace("**Revision:** 1.26", "**Revision:** 1.27", 1)
            .replace(
                "- [ ] **Increment 23 — Backend framework and capability profiles**",
                "- [x] **Increment 23 — Backend framework and capability profiles**",
                1,
            ),
            encoding="utf-8",
        )

        manifest_path = root / "tests/compiler/fixtures/increment23/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "validated-backend-framework"
        manifest["evidence"] = {
            "pull_request": 1,
            "dedicated_run": 2,
            "core_ci_run": 3,
        }
        manifest_path.write_text(
            json.dumps(manifest, indent=2) + "\n",
            encoding="utf-8",
        )

        self.assertEqual(CHECKER.check_repository(root), [])


if __name__ == "__main__":
    unittest.main()
