from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY_ROOT / "scripts" / "check_native_compiler_bootstrap.py"
SPEC = importlib.util.spec_from_file_location("check_native_compiler_bootstrap", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)


class NativeCompilerBootstrapCheckTests(unittest.TestCase):
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

    def test_rejects_missing_native_source(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        (root / "core/compiler/tools/nodalc/nodalc.cpp").unlink()
        self.assertIn("NODAL-COMPILER-001", self.problem_codes(root))

    def test_rejects_unmanaged_circt_configuration(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "cmake/NodalToolchain.cmake"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "find_package(CIRCT REQUIRED CONFIG)", "find_package(CIRCT CONFIG)"
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-COMPILER-009", self.problem_codes(root))

    def test_rejects_missing_definition_normalization(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "cmake/NodalToolchain.cmake"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "list(REMOVE_DUPLICATES _nodal_llvm_definitions)",
                "# duplicate removal intentionally deleted",
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-COMPILER-020", self.problem_codes(root))

    def test_rejects_missing_abi_alignment(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "cmake/NodalToolchain.cmake"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "list(FILTER _nodal_llvm_definitions EXCLUDE REGEX",
                "list(FILTER _nodal_llvm_definitions INCLUDE REGEX",
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-COMPILER-020", self.problem_codes(root))

    def test_rejects_missing_circt_link(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/tools/nodalc/CMakeLists.txt"
        path.write_text(
            path.read_text(encoding="utf-8").replace("    CIRCTHW\n", ""),
            encoding="utf-8",
        )
        self.assertIn("NODAL-COMPILER-010", self.problem_codes(root))

    def test_rejects_language_semantics_in_bootstrap(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/tools/nodalc/nodalc.cpp"
        path.write_text(
            path.read_text(encoding="utf-8") + "\n// NodalDialect is deferred\n",
            encoding="utf-8",
        )
        self.assertIn("NODAL-COMPILER-014", self.problem_codes(root))

    def test_rejects_missing_native_test_target(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/test/Unit/CMakeLists.txt"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "nodal-native-unit-tests", "renamed-native-unit-tests"
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-COMPILER-012", self.problem_codes(root))

    def test_rejects_missing_abi_warning_gate(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-6-native-compiler-bootstrap.yml"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "native-build.log", "unchecked-build.log"
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-COMPILER-019", self.problem_codes(root))


if __name__ == "__main__":
    unittest.main()
