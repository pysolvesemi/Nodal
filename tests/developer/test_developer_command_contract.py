from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY_ROOT / "scripts" / "check_developer_commands.py"
SPEC = importlib.util.spec_from_file_location("check_developer_commands", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)


class DeveloperCommandContractTests(unittest.TestCase):
    def problem_codes(self, root: Path) -> set[str]:
        return {problem.code for problem in CHECKER.check_repository(root)}

    def temporary_repository(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in CHECKER.EXPECTED_FILES:
            source = REPOSITORY_ROOT / relative
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        return temporary, root

    def test_current_repository_passes(self) -> None:
        self.assertEqual(CHECKER.check_repository(REPOSITORY_ROOT), [])

    def test_rejects_missing_posix_wrapper(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        (root / "nodal").unlink()
        self.assertIn("NODAL-DEV-CHECK-001", self.problem_codes(root))

    def test_rejects_nonstandard_python_dependency(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "scripts/nodal.py"
        path.write_text("import requests\n" + path.read_text(encoding="utf-8"), encoding="utf-8")
        self.assertIn("NODAL-DEV-CHECK-005", self.problem_codes(root))

    def test_rejects_workflow_bypassing_unified_command(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-7-unified-developer-commands.yml"
        path.write_text(path.read_text(encoding="utf-8") + "\n# ./mill __.compile\n", encoding="utf-8")
        self.assertIn("NODAL-DEV-CHECK-016", self.problem_codes(root))

    def test_rejects_core_library_filesystem_dependency(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "scripts/nodal.py"
        path.write_text(path.read_text(encoding="utf-8") + '\n# root / "libraries"\n', encoding="utf-8")
        self.assertIn("NODAL-DEV-CHECK-013", self.problem_codes(root))

    def test_rejects_missing_library_parser(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "scripts/nodal.py"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                '"library",\n        help="reserved namespace',
                '"future-library",\n        help="reserved namespace',
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-DEV-CHECK-012", self.problem_codes(root))


if __name__ == "__main__":
    unittest.main()
