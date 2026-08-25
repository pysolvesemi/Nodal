from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "check_increment21.py"
SPEC = importlib.util.spec_from_file_location("check_increment21", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)

SUPPORT_FILES = (
    "core/compiler/lib/CMakeLists.txt",
    "core/compiler/tools/nodalc/CMakeLists.txt",
    "core/compiler/tools/nodalc/nodalc.cpp",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/Unit/CMakeLists.txt",
    "docs/roadmap/nodal-development-todo.md",
)


class Increment21CheckerTests(unittest.TestCase):
    def temporary_repository(
        self,
    ) -> tuple[tempfile.TemporaryDirectory[str], Path]:
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

    def test_rejects_missing_stage(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Transforms/Verification.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                'return "domain";',
                'return "removed-domain";',
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC21-003", self.codes(root))

    def test_rejects_removed_transaction_rollback(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Transforms/Verification.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "module->setAttrs(originalAttributes);",
                "// rollback removed",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC21-003", self.codes(root))

    def test_rejects_unregistered_nodalc_passes(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/tools/nodalc/nodalc.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "nodal::registerNodalPasses();",
                "// registration removed",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC21-005", self.codes(root))

    def test_rejects_writable_permanent_workflow(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-21-native-verification-pipeline.yml"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "contents: read",
                "contents: write",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC21-009", self.codes(root))

    def test_accepts_successor_roadmap_revision(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        manifest = root / "tests/compiler/fixtures/increment21/manifest.json"
        text = manifest.read_text(encoding="utf-8")
        text = text.replace(
            '"status": "implemented-awaiting-evidence"',
            '"status": "validated-native-verification-pipeline"',
            1,
        ).replace('"evidence": {}', '"evidence": {"pull_request": 1, "dedicated_run": 2, "core_ci_run": 3}', 1)
        manifest.write_text(text, encoding="utf-8")
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(
            roadmap.read_text(encoding="utf-8")
            .replace("**Revision:** 1.24", "**Revision:** 1.26", 1)
            .replace(
                "- [ ] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**",
                "- [x] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**",
                1,
            ),
            encoding="utf-8",
        )
        self.assertEqual(CHECKER.check_repository(root), [])


if __name__ == "__main__":
    unittest.main()
