from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY_ROOT / "scripts" / "check_architecture.py"
SPEC = importlib.util.spec_from_file_location("check_architecture", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)


class ArchitectureCheckTests(unittest.TestCase):
    def problem_codes(self, root: Path) -> set[str]:
        return {problem.code for problem in CHECKER.check_repository(root)}

    def temporary_repository(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        shutil.copytree(REPOSITORY_ROOT / "core", root / "core")
        return temporary, root

    def test_current_repository_passes(self) -> None:
        self.assertEqual(CHECKER.check_repository(REPOSITORY_ROOT), [])

    def test_rejects_core_dependency_on_future_library(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        descriptor = root / "core" / "scala" / "api" / "module.toml"
        descriptor.write_text(
            descriptor.read_text(encoding="utf-8").replace(
                "dependencies = []", 'dependencies = ["libraries.models"]'
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-ARCH-007", self.problem_codes(root))

    def test_rejects_compiler_dependency_on_frontend(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        descriptor = root / "core" / "compiler" / "module.toml"
        descriptor.write_text(
            descriptor.read_text(encoding="utf-8").replace(
                "dependencies = []", 'dependencies = ["core.scala.frontend"]'
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-ARCH-008", self.problem_codes(root))

    def test_rejects_compiler_source_reference_to_frontend(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        source = root / "core" / "compiler" / "bad.cpp"
        source.write_text('const char *bad = "nodal.internal.frontend";\n', encoding="utf-8")
        self.assertIn("NODAL-ARCH-012", self.problem_codes(root))

    def test_rejects_placeholder_library_directory(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        placeholder = root / "libraries" / "models"
        placeholder.mkdir(parents=True)
        (placeholder / "README.md").write_text("placeholder\n", encoding="utf-8")
        self.assertIn("NODAL-ARCH-013", self.problem_codes(root))

    def test_detects_dependency_cycle(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        api = root / "core" / "scala" / "api" / "module.toml"
        api.write_text(
            api.read_text(encoding="utf-8").replace(
                "dependencies = []", 'dependencies = ["core.scala.frontend"]'
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-ARCH-010", self.problem_codes(root))

    def test_rejects_relaxed_architecture_policy(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        manifest = root / "core" / "modules.toml"
        manifest.write_text(
            manifest.read_text(encoding="utf-8").replace(
                "allow_core_to_libraries = false", "allow_core_to_libraries = true"
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-ARCH-014", self.problem_codes(root))

    def test_rejects_unregistered_module_descriptor(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        module = root / "core" / "scala" / "hidden"
        module.mkdir()
        (module / "module.toml").write_text(
            """schema = 1
id = "core.scala.hidden"
path = "core/scala/hidden"
kind = "scala"
role = "hidden"
visibility = "internal"
owner = "@pysolvesemi"
description = "Undeclared test module."
dependencies = []
""",
            encoding="utf-8",
        )
        self.assertIn("NODAL-ARCH-015", self.problem_codes(root))


if __name__ == "__main__":
    unittest.main()
