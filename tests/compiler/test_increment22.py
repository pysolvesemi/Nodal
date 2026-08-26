from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_increment22.py"
SPEC = importlib.util.spec_from_file_location("check_increment22", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)

SUPPORT_FILES = (
    "core/compiler/lib/Transforms/Passes.cpp",
    "core/scala/bridge/src/nodal/bridge/NativeCompilerClient.scala",
    "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala",
    "docs/roadmap/nodal-development-todo.md",
)


class Increment22CheckerTests(unittest.TestCase):
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

    def test_rejects_unmapped_native_verifier(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Transforms/Passes.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "return emitMappedFailure(operation, code, message);",
                'operation->emitError() << code << message; return failure();',
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC22-005", self.codes(root))

    def test_rejects_missing_diagnostic_family(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/diagnostics-v0.1.json"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                '      "NODAL-AMS-BRIDGE-001"\n',
                "",
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC22-010", self.codes(root))

    def test_rejects_writable_permanent_workflow(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-22-cross-layer-diagnostics.yml"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "contents: read",
                "contents: write",
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC22-007", self.codes(root))

    def test_rejects_invented_inventory_hierarchy(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Diagnostics/DiagnosticMapping.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "  context.semanticPath = path.str();\n  context.indexPath",
                "  context.semanticPath = path.str();\n  context.hierarchyPath = path.str();\n  context.indexPath",
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC22-004", self.codes(root))

    def test_rejects_missing_ancestor_source_lookup(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Diagnostics/DiagnosticMapping.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "current && !file",
                "current && false",
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC22-004", self.codes(root))

    def test_rejects_unsanitized_staged_input_path(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/scala/bridge/src/nodal/bridge/NativeDiagnosticMapper.scala"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "<bridge-input>",
                "<temporary-input>",
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC22-006", self.codes(root))

    def test_rejects_premature_roadmap_closure(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(
            roadmap.read_text(encoding="utf-8").replace(
                "- [ ] **Increment 22 — Cross-layer diagnostic mapping**",
                "- [x] **Increment 22 — Cross-layer diagnostic mapping**",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC22-009", self.codes(root))


if __name__ == "__main__":
    unittest.main()
