from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_increment29.py"
SPEC = importlib.util.spec_from_file_location("check_increment29", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)

SUPPORT = ("docs/roadmap/nodal-development-todo.md",)


class Increment29CheckerTests(unittest.TestCase):
    def temporary_repository(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in dict.fromkeys(CHECKER.EXPECTED_FILES + SUPPORT):
            source = ROOT / relative
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        return temporary, root

    def codes(self, root: Path) -> set[str]:
        return {problem.code for problem in CHECKER.check_repository(root)}

    def test_repository_contract(self) -> None:
        self.assertEqual(CHECKER.check_repository(ROOT), [])

    def test_rejects_missing_constant_evaluator(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace("evaluateParameterDefault", "missingEvaluator"), encoding="utf-8")
        self.assertIn("NODAL-INC29-005", self.codes(root))

    def test_rejects_missing_lossless_renderer(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/AnalogVerticalSlice.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace("renderParameterConstantExpression", "formatFoldedValue"), encoding="utf-8")
        self.assertIn("NODAL-INC29-008", self.codes(root))

    def test_rejects_missing_dynamic_value_guard(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace("dynamic values cannot enter constant evaluation", "dynamic values are accepted"), encoding="utf-8")
        self.assertIn("NODAL-INC29-005", self.codes(root))

    def test_rejects_missing_structural_envelope_guard(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/ParameterModel.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace("hasBoundedRange", "acceptUnboundedRange"), encoding="utf-8")
        self.assertIn("NODAL-INC29-005", self.codes(root))

    def test_rejects_missing_native_constraint_rendering(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/AnalogVerticalSlice.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace('" exclude "', '" ignored "'), encoding="utf-8")
        self.assertIn("NODAL-INC29-008", self.codes(root))

    def test_rejects_missing_diagnostic(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/diagnostics-v0.1.json"
        path.write_text(path.read_text(encoding="utf-8").replace('      "NODAL-PARAMETER-ENVELOPE-001",\n', "", 1), encoding="utf-8")
        self.assertIn("NODAL-INC29-017", self.codes(root))

    def test_rejects_writable_workflow(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-29-parameters-units.yml"
        path.write_text(path.read_text(encoding="utf-8").replace("contents: read", "contents: write", 1), encoding="utf-8")
        self.assertIn("NODAL-INC29-016", self.codes(root))

    def test_rejects_premature_roadmap_closure(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        manifest_path = root / "tests/compiler/fixtures/increment29/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "implemented-awaiting-evidence"
        manifest["evidence"] = {}
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        text = roadmap.read_text(encoding="utf-8")
        if "- [ ] **Increment 29 — Parameters, constants, ranges, and units**" in text:
            text = text.replace(
                "- [ ] **Increment 29 — Parameters, constants, ranges, and units**",
                "- [x] **Increment 29 — Parameters, constants, ranges, and units**",
                1,
            )
        roadmap.write_text(text, encoding="utf-8")
        self.assertIn("NODAL-INC29-020", self.codes(root))

    def test_accepts_validated_state(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        manifest_path = root / "tests/compiler/fixtures/increment29/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "validated-parameter-constant-unit"
        manifest["evidence"] = {"pull_request": 1, "dedicated_run": 2, "core_ci_run": 3}
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        text = roadmap.read_text(encoding="utf-8")
        text = text.replace("**Revision:** 1.36", "**Revision:** 1.37", 1)
        text = text.replace(
            "- [ ] **Increment 29 — Parameters, constants, ranges, and units**",
            "- [x] **Increment 29 — Parameters, constants, ranges, and units**",
            1,
        )
        roadmap.write_text(text, encoding="utf-8")
        self.assertEqual(CHECKER.check_repository(root), [])


if __name__ == "__main__":
    unittest.main()
