from __future__ import annotations

import importlib.util
import shutil
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CHECKER_PATH = ROOT / "scripts" / "check_increment33.py"
SPEC = importlib.util.spec_from_file_location("check_increment33", CHECKER_PATH)
assert SPEC is not None and SPEC.loader is not None
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


REQUIRED = (
    ".github/workflows/increment-33-analog-procedural-assignment.yml",
    "core/compiler/diagnostics-v0.1.json",
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "core/compiler/include/nodal/Dialect/Nodal/NodalTypes.td",
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/IR/analog-procedural-invalid-variable-kind.mlir",
    "core/native/include/nodal/AnalogProceduralRuntime.h",
    "core/scala/api/src/nodal/AnalogProceduralRuntime.scala",
    "core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala",
    "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala",
    "docs/design-gates/NodalAnalogProceduralAssignment-DG-v0.1.md",
    "docs/implementation/increment33-analog-variables-procedural-assignment.md",
    "docs/roadmap/nodal-development-todo.md",
    "examples/continuousTimeApi/src/nodal/increment33fixture/Increment33RuntimeCheck.scala",
    "tests/compiler/fixtures/increment32/manifest.json",
    "tests/compiler/fixtures/increment33/README.md",
    "tests/compiler/fixtures/increment33/analog_procedural_runtime_test.cpp",
    "tests/compiler/fixtures/increment33/manifest.json",
)


class Increment33ContractTests(unittest.TestCase):
    def fixture(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in REQUIRED:
            source = ROOT / relative
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        return temporary, root

    def assert_rejected(self, root: Path, fragment: str) -> None:
        with self.assertRaises(CHECKER.CheckFailure) as captured:
            CHECKER.check_repository(root)
        self.assertIn(fragment, str(captured.exception))

    def test_repository_checkpoint_passes(self) -> None:
        CHECKER.check_repository(ROOT)

    def test_premature_roadmap_closure_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "docs/roadmap/nodal-development-todo.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "- [ ] **Increment 33 — Analog variables and procedural assignment**",
                    "- [x] **Increment 33 — Analog variables and procedural assignment**",
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "must remain unchecked")

    def test_assignment_order_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = (
                root
                / "examples/continuousTimeApi/src/nodal/increment33fixture/Increment33RuntimeCheck.scala"
            )
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "snapshot.assignments.map(_.authoredOrder) == Vector(0, 1, 2, 3, 4)",
                    "snapshot.assignments.nonEmpty",
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "exact assignment order")

    def test_unvalidated_predecessor_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment32/manifest.json"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    '"validated-equation-contribution-semantics"',
                    '"implemented-awaiting-evidence"',
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "repository Increment 32 manifest is not validated")

    def test_temporary_workflow_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / ".github/workflows/_inc33_materializer.yml"
            path.write_text("permissions:\n  contents: write\n", encoding="utf-8")
            self.assert_rejected(root, "temporary/generated files remain")

    def test_last_writer_wins_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment33/manifest.json"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    '"last_writer_wins_source_model": false',
                    '"last_writer_wins_source_model": true',
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "must not be last-writer-wins")


    def test_untracked_python_cache_is_ignored(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "scripts/__pycache__/probe.cpython-312.pyc"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"runtime cache")
            CHECKER.check_repository(root)

    def test_variable_type_diagnostic_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/include/nodal/Dialect/Nodal/NodalTypes.td"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "NODAL-ANALOG-033-019",
                    "NODAL-ANALOG-033-999",
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "compiler type model is missing")


if __name__ == "__main__":
    unittest.main()
