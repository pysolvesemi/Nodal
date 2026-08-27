from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/check_increment24.py"
SPEC = importlib.util.spec_from_file_location("check_increment24", SCRIPT)
assert SPEC and SPEC.loader
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)

SUPPORT = (
    "core/compiler/diagnostics-v0.1.json",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/Unit/CMakeLists.txt",
    "docs/roadmap/nodal-development-todo.md",
    "tests/compiler/fixtures/increment25/manifest.json",
)


class Increment24CheckerTests(unittest.TestCase):
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

    def test_rejects_missing_parameter_resolution(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace("LogicalResult nodal::ParameterRefOp::verify()", "LogicalResult nodal::MissingParameterRefOp::verify()", 1), encoding="utf-8")
        self.assertIn("NODAL-INC24-004", self.codes(root))

    def test_rejects_missing_semantic_pipeline_classification(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/lib/Transforms/Passes.cpp"
        path.write_text(path.read_text(encoding="utf-8").replace('"nodal.contribute"', '"nodal.missing_contribution"'), encoding="utf-8")
        self.assertIn("NODAL-INC24-005", self.codes(root))

    def test_rejects_missing_diagnostic_family(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / "core/compiler/diagnostics-v0.1.json"
        path.write_text(path.read_text(encoding="utf-8").replace('    "NODAL-ANALOG-PARAMETER-001",\n', "", 1), encoding="utf-8")
        self.assertIn("NODAL-INC24-011", self.codes(root))

    def test_rejects_writable_workflow(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        path = root / ".github/workflows/increment-24-minimal-analog-ir.yml"
        path.write_text(path.read_text(encoding="utf-8").replace("contents: read", "contents: write"), encoding="utf-8")
        self.assertIn("NODAL-INC24-009", self.codes(root))

    def test_rejects_premature_roadmap_closure(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        manifest_path = root / "tests/compiler/fixtures/increment24/manifest.json"
        manifest = __import__("json").loads(
            manifest_path.read_text(encoding="utf-8")
        )
        manifest["status"] = "implemented-awaiting-evidence"
        manifest["evidence"] = {}
        manifest_path.write_text(
            __import__("json").dumps(manifest, indent=2) + "\n",
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC24-010", self.codes(root))

    def test_rejects_checked_increment25_without_validated_successor(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        successor_path = root / "tests/compiler/fixtures/increment25/manifest.json"
        successor = __import__("json").loads(
            successor_path.read_text(encoding="utf-8")
        )
        successor["status"] = "implemented-awaiting-evidence"
        successor["evidence"] = {}
        successor_path.write_text(
            __import__("json").dumps(successor, indent=2) + "\n",
            encoding="utf-8",
        )
        self.assertIn("NODAL-INC24-010", self.codes(root))

    def test_accepts_validated_successor_state(self) -> None:
        temporary, root = self.temporary_repository()
        self.addCleanup(temporary.cleanup)
        manifest_path = root / "tests/compiler/fixtures/increment24/manifest.json"
        manifest = __import__("json").loads(manifest_path.read_text(encoding="utf-8"))
        manifest["status"] = "validated-minimal-analog-ir"
        manifest["evidence"] = {"pull_request": 1, "dedicated_run": 2, "core_ci_run": 3}
        manifest_path.write_text(__import__("json").dumps(manifest, indent=2) + "\n", encoding="utf-8")
        roadmap = root / "docs/roadmap/nodal-development-todo.md"
        roadmap.write_text(roadmap.read_text(encoding="utf-8").replace("**Revision:** 1.27", "**Revision:** 1.28", 1).replace("- [ ] **Increment 24 — Minimal analog expression and contribution IR**", "- [x] **Increment 24 — Minimal analog expression and contribution IR**", 1), encoding="utf-8")
        self.assertEqual(CHECKER.check_repository(root), [])


if __name__ == "__main__":
    unittest.main()
