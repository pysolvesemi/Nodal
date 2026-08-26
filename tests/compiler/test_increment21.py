from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_increment21.py"
SPEC = importlib.util.spec_from_file_location("check_increment21", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)

SUPPORT_FILES = (
    "core/compiler/include/nodal/CMakeLists.txt",
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

    def test_rejects_missing_transactional_commit(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Transforms/Passes.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace("takeBody", "removedBodyTransfer"),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC21-004", self.codes(root))

    def test_rejects_missing_mandatory_stage(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Transforms/Passes.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "nodal-verify-effects",
                "removed-effects-stage",
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC21-004", self.codes(root))

    def test_rejects_writable_permanent_workflow(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-21-native-semantic-pipeline.yml"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "contents: read",
                "contents: write",
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC21-010", self.codes(root))

    def test_rejects_incomplete_failure_matrix(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/test/IR/semantic-pipeline-invalid.mlir"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                'nodal.target.profile = "digital"',
                'nodal.target.profile = "mixed_signal"',
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC21-008", self.codes(root))

    def test_accepts_validated_successor_state(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        manifest = root / "tests/compiler/fixtures/increment21/manifest.json"
        manifest.write_text(
            manifest.read_text(encoding="utf-8")
            .replace(
                '"status": "implemented-awaiting-evidence"',
                '"status": "validated-native-semantic-pipeline"',
            )
            .replace(
                '"pull_request": null',
                '"pull_request": 1',
            )
            .replace(
                '"dedicated_run": null',
                '"dedicated_run": 2',
            )
            .replace(
                '"core_ci_run": null',
                '"core_ci_run": 3',
            ),
            encoding="utf-8",
        )
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        text = roadmap.read_text(encoding="utf-8")
        text = text.replace("**Revision:** 1.25", "**Revision:** 1.26", 1)
        text = text.replace(
            "- [ ] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**",
            "- [x] **Increment 21 — Native parse, staged semantic verification, and pass pipeline**",
            1,
        )
        text = text.replace(
            "- [ ] **Increment 22 — Cross-layer diagnostic mapping**",
            "- [x] **Increment 22 — Cross-layer diagnostic mapping**",
            1,
        )
        roadmap.write_text(text, encoding="utf-8")
        self.assertEqual(CHECKER.check_repository(root), [])

    def test_rejects_unchecked_increment22_at_revision_126(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        text = roadmap.read_text(encoding="utf-8")
        text = text.replace("**Revision:** 1.25", "**Revision:** 1.26", 1)
        text = text.replace(
            "- [x] **Increment 22 — Cross-layer diagnostic mapping**",
            "- [ ] **Increment 22 — Cross-layer diagnostic mapping**",
            1,
        )
        roadmap.write_text(text, encoding="utf-8")
        self.assertIn("NODAL-INC21-011", self.codes(root))


if __name__ == "__main__":
    unittest.main()
