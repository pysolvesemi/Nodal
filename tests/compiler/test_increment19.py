from __future__ import annotations

import importlib.util
import re
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_increment19.py"
SPEC = importlib.util.spec_from_file_location("check_increment19", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)

SUPPORT_FILES = (
    "core/compiler/include/nodal/Dialect/Nodal/NodalDialect.td",
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "core/compiler/include/nodal/Dialect/Nodal/CMakeLists.txt",
    "core/compiler/lib/Dialect/Nodal/CMakeLists.txt",
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/Unit/CMakeLists.txt",
    "docs/roadmap/nodal-development-todo.md",
)


class Increment19CheckerTests(unittest.TestCase):
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

    def test_accepts_later_roadmap_revision(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "docs/roadmap/nodal-development-todo.md"
        text = path.read_text(encoding="utf-8")
        match = re.search(r"^\*\*Revision:\*\* (\d+)\.(\d+)$", text, re.MULTILINE)
        if match is None:
            self.fail("expected one numeric roadmap revision")
        current = match.group(0)
        later = f"**Revision:** {match.group(1)}.{int(match.group(2)) + 1}"
        path.write_text(text.replace(current, later, 1), encoding="utf-8")
        self.assertEqual(CHECKER.check_repository(root), [])

    def test_rejects_missing_type_definition(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/include/nodal/Dialect/Nodal/NodalTypes.td"
        path.write_text(
            path.read_text(encoding="utf-8").replace("Nodal_BitsType", "RemovedBitsType"),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC19-003", self.codes(root))

    def test_rejects_missing_operation_family(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td"
        path.write_text(
            path.read_text(encoding="utf-8").replace("Nodal_FsmOp", "RemovedFsmOp"),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC19-004", self.codes(root))

    def test_rejects_temporary_supervisor(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-19-final-supervisor.yml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("temporary\n", encoding="utf-8")
        self.assertIn("NODAL-INC19-002", self.codes(root))

    def test_rejects_writable_permanent_workflow(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-19-core-mlir-model.yml"
        path.write_text(
            path.read_text(encoding="utf-8").replace("contents: read", "contents: write"),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC19-010", self.codes(root))


if __name__ == "__main__":
    unittest.main()
