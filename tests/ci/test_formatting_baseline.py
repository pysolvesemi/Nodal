from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY_ROOT / "scripts" / "check_formatting_baseline.py"
SPEC = importlib.util.spec_from_file_location("check_formatting_baseline", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)


class FormattingBaselineTests(unittest.TestCase):
    def problem_codes(self, root: Path) -> set[str]:
        return {problem.code for problem in CHECKER.check_repository(root)}

    def temporary_root(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        return temporary, Path(temporary.name)

    def test_current_repository_passes(self) -> None:
        self.assertEqual(CHECKER.check_repository(REPOSITORY_ROOT), [])

    def test_rejects_crlf(self) -> None:
        temporary, root = self.temporary_root()
        self.addCleanup(temporary.cleanup)
        (root / "sample.py").write_bytes(b"print('x')\r\n")
        self.assertIn("NODAL-FMT-003", self.problem_codes(root))

    def test_rejects_missing_final_newline(self) -> None:
        temporary, root = self.temporary_root()
        self.addCleanup(temporary.cleanup)
        (root / "sample.scala").write_text("object Sample", encoding="utf-8")
        self.assertIn("NODAL-FMT-004", self.problem_codes(root))

    def test_rejects_trailing_whitespace(self) -> None:
        temporary, root = self.temporary_root()
        self.addCleanup(temporary.cleanup)
        (root / "sample.py").write_text("value = 1 \n", encoding="utf-8")
        self.assertIn("NODAL-FMT-005", self.problem_codes(root))

    def test_allows_markdown_hard_break(self) -> None:
        temporary, root = self.temporary_root()
        self.addCleanup(temporary.cleanup)
        (root / "sample.md").write_text("line  \nnext\n", encoding="utf-8")
        self.assertEqual(CHECKER.check_repository(root), [])

    def test_rejects_invalid_json(self) -> None:
        temporary, root = self.temporary_root()
        self.addCleanup(temporary.cleanup)
        (root / "sample.json").write_text('{"broken": }\n', encoding="utf-8")
        self.assertIn("NODAL-FMT-007", self.problem_codes(root))


if __name__ == "__main__":
    unittest.main()
