from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_increment20.py"
SPEC = importlib.util.spec_from_file_location("check_increment20", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)

SUPPORT_FILES = (
    "docs/roadmap/nodal-development-todo.md",
)


class Increment20CheckerTests(unittest.TestCase):
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

    def test_rejects_missing_serializer(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        (
            root / "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala"
        ).unlink()
        self.assertIn("NODAL-INC20-001", self.codes(root))

    def test_rejects_shell_process_implementation(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/scala/bridge/src/nodal/bridge/NativeCompilerClient.scala"
        path.write_text(
            path.read_text(encoding="utf-8") + '\n// Runtime.getRuntime.exec("bad")\n',
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC20-004", self.codes(root))

    def test_rejects_writable_permanent_workflow(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-20-scala-mlir-bridge.yml"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "contents: read",
                "contents: write",
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC20-007", self.codes(root))

    def test_accepts_successor_roadmap_revision(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        manifest = root / "tests/compiler/fixtures/increment20/manifest.json"
        manifest.write_text(
            manifest.read_text(encoding="utf-8")
            .replace(
                '"status": "implemented-awaiting-evidence"',
                '"status": "validated-scala-mlir-bridge"',
            )
            .replace(
                '"evidence": {}',
                '"evidence": {"pull_request": 1, "dedicated_run": 2, "core_ci_run": 3}',
            ),
            encoding="utf-8",
        )
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(
            roadmap.read_text(encoding="utf-8")
            .replace("**Revision:** 1.23", "**Revision:** 1.25", 1)
            .replace(
                "- [ ] **Increment 20 — Scala-to-MLIR bridge**",
                "- [x] **Increment 20 — Scala-to-MLIR bridge**",
                1,
            ),
            encoding="utf-8",
        )
        self.assertEqual(CHECKER.check_repository(root), [])


if __name__ == "__main__":
    unittest.main()
