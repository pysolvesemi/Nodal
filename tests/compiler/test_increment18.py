from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY_ROOT / "scripts" / "check_increment18.py"
SPEC = importlib.util.spec_from_file_location("check_increment18", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)

SUPPORT_FILES = (
    "core/compiler/CMakeLists.txt",
    "core/compiler/tools/nodalc/CMakeLists.txt",
    "core/compiler/tools/nodalc/nodalc.cpp",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/Unit/CMakeLists.txt",
    "docs/roadmap/nodal-development-todo.md",
    "scripts/check_native_compiler_bootstrap.py",
)


class Increment18CheckTests(unittest.TestCase):
    def problem_codes(self, root: Path) -> set[str]:
        return {problem.code for problem in CHECKER.check_repository(root)}

    def temporary_repository(
        self,
    ) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in dict.fromkeys(CHECKER.EXPECTED_FILES + SUPPORT_FILES):
            source = REPOSITORY_ROOT / relative
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        return temporary, root

    def test_current_repository_passes(self) -> None:
        self.assertEqual(CHECKER.check_repository(REPOSITORY_ROOT), [])

    def test_accepts_successor_roadmap_revision(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "docs/roadmap/nodal-development-todo.md"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "**Revision:** 1.23",
                "**Revision:** 1.24",
                1,
            ),
            encoding="utf-8",
        )
        self.assertEqual(CHECKER.check_repository(root), [])

    def test_rejects_missing_operation_tablegen(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        (
            root / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td"
        ).unlink()
        self.assertIn("NODAL-MLIR-001", self.problem_codes(root))

    def test_rejects_missing_placeholder_verifier(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "label.getValue().empty()",
                "false",
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-MLIR-005", self.problem_codes(root))

    def test_rejects_deferred_hardware_semantics(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td"
        path.write_text(
            path.read_text(encoding="utf-8") + "\n// nodal.module\n",
            encoding="utf-8",
        )
        self.assertIn("NODAL-MLIR-009", self.problem_codes(root))

    def test_rejects_writable_permanent_workflow(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-18-mlir-dialect-skeleton.yml"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "contents: read",
                "contents: write",
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-MLIR-008", self.problem_codes(root))

    def test_rejects_predecessor_checker_that_forbids_dialect(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "scripts/check_native_compiler_bootstrap.py"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "FORBIDDEN_SEMANTICS = (\n",
                'FORBIDDEN_SEMANTICS = (\n    "NodalDialect",\n',
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-MLIR-011", self.problem_codes(root))


if __name__ == "__main__":
    unittest.main()
