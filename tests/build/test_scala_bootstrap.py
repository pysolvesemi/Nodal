from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY_ROOT / "scripts" / "check_scala_bootstrap.py"
SPEC = importlib.util.spec_from_file_location("check_scala_bootstrap", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)


class ScalaBootstrapCheckTests(unittest.TestCase):
    def problem_codes(self, root: Path) -> set[str]:
        return {problem.code for problem in CHECKER.check_repository(root)}

    def temporary_repository(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for path in ("build.mill", ".mill-version", "mill", "mill.bat"):
            shutil.copy2(REPOSITORY_ROOT / path, root / path)
        shutil.copytree(REPOSITORY_ROOT / "core", root / "core")
        return temporary, root

    def test_current_repository_passes(self) -> None:
        self.assertEqual(CHECKER.check_repository(REPOSITORY_ROOT), [])

    def test_rejects_scala_version_drift(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        build = root / "build.mill"
        build.write_text(
            build.read_text(encoding="utf-8").replace('val scala3 = "3.8.4"', 'val scala3 = "3.8.3"'),
            encoding="utf-8",
        )
        self.assertIn("NODAL-SCALA-005", self.problem_codes(root))

    def test_rejects_mill_version_drift(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        (root / ".mill-version").write_text("1.1.6\n", encoding="utf-8")
        self.assertIn("NODAL-SCALA-006", self.problem_codes(root))

    def test_rejects_scala_2_cross_build(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        build = root / "build.mill"
        build.write_text(build.read_text(encoding="utf-8") + "\nobject legacy extends CrossScalaModule\n", encoding="utf-8")
        self.assertIn("NODAL-SCALA-008", self.problem_codes(root))

    def test_rejects_source_outside_bootstrap_namespace(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        source = root / CHECKER.EXPECTED_SOURCES[0]
        source.write_text(source.read_text(encoding="utf-8").replace("package nodal.bootstrap", "package nodal.public"), encoding="utf-8")
        self.assertIn("NODAL-SCALA-011", self.problem_codes(root))


if __name__ == "__main__":
    unittest.main()
