from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def load(name: str, relative: str):
    path = REPOSITORY_ROOT / relative
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


load("bootstrap_lint_toolchain", "scripts/bootstrap_lint_toolchain.py")
CLANG_TOOLS = load("run_clang_tools", "scripts/run_clang_tools.py")


class RunClangToolsTests(unittest.TestCase):
    def test_locked_mlir_analyzer_waiver_is_exactly_scoped(self) -> None:
        path = REPOSITORY_ROOT / "core/compiler/lib/Dialect/Nodal/NodalTypes.cpp"
        self.assertEqual(
            CLANG_TOOLS._tidy_waiver(path),
            (
                "clang-analyzer-core.StackAddressEscape",
                "locked MLIR type-registration template false positive",
            ),
        )

    def test_other_native_translation_units_keep_every_check_enabled(self) -> None:
        path = REPOSITORY_ROOT / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
        self.assertIsNone(CLANG_TOOLS._tidy_waiver(path))

    def test_paths_outside_the_repository_cannot_receive_a_waiver(self) -> None:
        self.assertIsNone(CLANG_TOOLS._tidy_waiver(Path("/tmp/NodalTypes.cpp")))


if __name__ == "__main__":
    unittest.main()
