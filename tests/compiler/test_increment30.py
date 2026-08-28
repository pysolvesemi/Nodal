from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_increment30.py"
SPEC = importlib.util.spec_from_file_location("check_increment30", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)


class Increment30CheckerTests(unittest.TestCase):
    def temporary_repository(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in CHECKER.EXPECTED_FILES:
            source = ROOT / relative
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        return temporary, root

    def codes(self, root: Path) -> set[str]:
        return {problem.code for problem in CHECKER.check_repository(root)}

    def test_repository_contract(self) -> None:
        self.assertEqual(CHECKER.check_repository(ROOT), [])

    def test_rejects_missing_quantity_type(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "tests/compiler/fixtures/increment30/manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["quantity_type"]["spelling"] = "f64"
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-INC30-007", self.codes(root))

    def test_rejects_missing_integer_to_real_promotion(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "tests/compiler/fixtures/increment30/manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["promotion"]["integer_real"] = "integer"
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-INC30-007", self.codes(root))

    def test_rejects_boolean_numeric_promotion(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "tests/compiler/fixtures/increment30/manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["promotion"]["boolean_numeric"] = "integer"
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-INC30-007", self.codes(root))

    def test_rejects_missing_dimension_algebra(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "tests/compiler/fixtures/increment30/analog-numeric-surface.json"
        surface = json.loads(path.read_text(encoding="utf-8"))
        surface["arithmetic"]["mul"]["dimensions"] = "discard"
        path.write_text(json.dumps(surface, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-INC30-008", self.codes(root))

    def test_rejects_numeric_truthiness(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "tests/compiler/fixtures/increment30/analog-numeric-surface.json"
        surface = json.loads(path.read_text(encoding="utf-8"))
        surface["logical"]["numericTruthiness"] = True
        path.write_text(json.dumps(surface, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-INC30-008", self.codes(root))

    def test_rejects_missing_folding_boundary(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "tests/compiler/fixtures/increment30/manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["folding"]["never"].remove("access")
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-INC30-007", self.codes(root))

    def test_rejects_missing_legacy_f64_compatibility(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "tests/compiler/fixtures/increment30/manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["quantity_type"]["legacy_f64"] = "rejected"
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-INC30-007", self.codes(root))

    def test_rejects_unvalidated_predecessor(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "tests/compiler/fixtures/increment29/manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["status"] = "implemented-awaiting-evidence"
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-INC30-009", self.codes(root))

    def test_rejects_writable_workflow(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-30-analog-numeric-types.yml"
        path.write_text(
            path.read_text(encoding="utf-8").replace("contents: read", "contents: write", 1),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC30-005", self.codes(root))

    def test_rejects_premature_roadmap_closure(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)

        manifest_path = root / "tests/compiler/fixtures/increment30/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "implemented-awaiting-evidence"
        manifest_path.write_text(
            json.dumps(manifest, indent=2) + "\n",
            encoding="utf-8",
        )

        roadmap_path = root / "docs/roadmap/nodal-development-todo.md"
        text = roadmap_path.read_text(encoding="utf-8")
        checked = "- [x] **Increment 30 — Analog numeric types and expression typing**"
        unchecked = "- [ ] **Increment 30 — Analog numeric types and expression typing**"
        if checked not in text:
            text = text.replace(unchecked, checked, 1)
        roadmap_path.write_text(text, encoding="utf-8")
        self.assertIn("NODAL-INC30-006", self.codes(root))

    def test_rejects_missing_native_quantity_type(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/include/nodal/Dialect/Nodal/NodalTypes.td"
        path.write_text(path.read_text(encoding="utf-8").replace("Nodal_QuantityType", "MissingQuantityType", 1), encoding="utf-8")
        self.assertIn("NODAL-INC30-010", self.codes(root))

    def test_rejects_missing_native_verifier_pass(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Transforms/Passes.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace("nodal-verify-analog-numeric", "missing-analog-verifier", 1), encoding="utf-8")
        self.assertIn("NODAL-INC30-010", self.codes(root))

    def test_rejects_missing_folding_provenance(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace("nodal.folded_provenance", "lost.folded.provenance"), encoding="utf-8")
        self.assertIn("NODAL-INC30-010", self.codes(root))

    def test_rejects_missing_backend_quantity_gate(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Backend/AnalogVerticalSlice.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace("verifyAnalogQuantityErasure", "skipQuantityVerification", 1), encoding="utf-8")
        self.assertIn("NODAL-INC30-010", self.codes(root))

    def test_rejects_missing_diagnostic_support_link(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/CMakeLists.txt"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "NodalSupport", "MissingDiagnosticSupport", 1
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC30-010", self.codes(root))

    def test_rejects_unqualified_builtin_module_definition(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "verifyAnalogNumericModel(mlir::ModuleOp module)",
                "verifyAnalogNumericModel(ModuleOp module)",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC30-010", self.codes(root))

    def test_rejects_missing_parameter_unit_scale_normalization(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/AnalogNumeric.cpp"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "FailureOr<double> parameterScale(Operation *parameter)",
                "FailureOr<double> ignoreParameterScale(Operation *parameter)",
                1,
            ),
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC30-010", self.codes(root))

    def test_rejects_temporary_parameter_scale_helper(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-30-parameter-scale-fix.yml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("temporary\n", encoding="utf-8")
        self.assertIn("NODAL-INC30-002", self.codes(root))

    def test_rejects_missing_implemented_status(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "tests/compiler/fixtures/increment30/manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["status"] = "implementation-started"
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        self.assertIn("NODAL-INC30-007", self.codes(root))


if __name__ == "__main__":
    unittest.main()
