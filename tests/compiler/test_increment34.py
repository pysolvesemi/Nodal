from __future__ import annotations

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CHECKER_PATH = ROOT / "scripts" / "check_increment34.py"
SPEC = importlib.util.spec_from_file_location("check_increment34", CHECKER_PATH)
assert SPEC is not None and SPEC.loader is not None
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


REQUIRED = (
    ".github/workflows/increment-34-analog-control-flow.yml",
    "core/scala/api/src/nodal/AnalogControlFlowApi.scala",
    "core/scala/api/src/nodal/AnalogControlFlowConstruction.scala",
    "core/scala/api/src/nodal/AnalogControlFlowRuntime.scala",
    "core/scala/api/src/nodal/AnalogProceduralConstruction.scala",
    "core/scala/api/src/nodal/AnalogProceduralRuntime.scala",
    "core/scala/testkit/test/src/nodal/AnalogControlFlowConstructionTests.scala",
    "docs/design-gates/NodalAnalogControlFlow-DG-v0.1.md",
    "docs/implementation/increment34-analog-control-flow.md",
    "docs/roadmap/nodal-development-todo.md",
    "examples/continuousTimeApi/src/nodal/increment34fixture/Increment34ConstructionCheck.scala",
    "examples/continuousTimeApi/src/nodal/increment34fixture/Increment34RuntimeCheck.scala",
    "scripts/check_increment34.py",
    "tests/compiler/fixtures/increment33/manifest.json",
    "tests/compiler/fixtures/increment34/README.md",
    "tests/compiler/fixtures/increment34/manifest.json",
)


class Increment34ContractTests(unittest.TestCase):
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
                    "- [ ] **Increment 34 — Analog control flow**",
                    "- [x] **Increment 34 — Analog control flow**",
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "must remain unchecked")

    def test_baseline_head_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["baseline"]["increment_33_head"] = "0" * 40
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "accepted stacked baseline")

    def test_branch_intersection_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogControlFlowRuntime.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "states.tail.foldLeft(first)(_ intersect _)",
                    "states.tail.foldLeft(first)(_ union _)",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "Scala control-flow runtime is missing")

    def test_zero_trip_loop_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogControlFlowRuntime.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "if loop.minimumIterations == 0 then exits += input",
                    "if false then exits += input",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "Scala control-flow runtime is missing")

    def test_break_legality_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogControlFlowRuntime.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "break is legal only in the nearest runtime-bounded loop",
                    "break is always legal",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "Scala control-flow runtime is missing")

    def test_continue_exit_merge_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogControlFlowRuntime.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "exits ++= body.continues",
                    "// continue exits ignored",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "Scala control-flow runtime is missing")

    def test_local_lifetime_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogControlFlowRuntime.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "flow.breaks.map(_ -- locals)",
                    "flow.breaks",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "Scala control-flow runtime is missing")

    def test_static_dynamic_read_guard_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogControlFlowRuntime.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "condition.staticValue.isEmpty || condition.reads.nonEmpty",
                    "condition.staticValue.isEmpty",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "Scala control-flow runtime is missing")

    def test_public_api_static_spelling_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogControlFlowApi.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "def analogStaticWhen",
                    "def removedAnalogStaticWhen",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "public control-flow API is missing")

    def test_builder_analysis_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogControlFlowConstruction.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "AnalogControlFlowRuntime.analyze(frozen)",
                    "AnalogControlFlowRuntime.Result(Set.empty, 0)",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "control-flow construction bridge is missing")

    def test_canonical_snapshot_field_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogProceduralRuntime.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "controlFlow: Option[AnalogControlFlowConstruction.Snapshot] = None",
                    "removedControlFlow: Option[AnalogControlFlowConstruction.Snapshot] = None",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "canonical procedural snapshot is missing")

    def test_flattening_guard_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogProceduralConstruction.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "module.variableRecords.toVector",
                    "module.recorder.snapshot.variables",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "procedural construction integration is missing")

    def test_owner_remap_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogControlFlowConstruction.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "def remapOwner(newOwner: String)",
                    "def disabledRemapOwner(newOwner: String)",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "control-flow construction bridge is missing")

    def test_public_missing_else_regression_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = (
                root
                / "core/scala/testkit/test/src/nodal/AnalogControlFlowConstructionTests.scala"
            )
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "public conditional missing else preserves the unmatched incoming path",
                    "missing-else regression removed",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "public construction tests is missing")

    def test_duplicate_case_regression_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = (
                root
                / "examples/continuousTimeApi/src/nodal/increment34fixture/Increment34RuntimeCheck.scala"
            )
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    'expect("NODAL-ANALOG-034-006")',
                    'expect("NODAL-ANALOG-034-999")',
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "source-semantic witness is missing")

    def test_public_construction_claim_is_required(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["integration"]["public_construction_kernel"] = False
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "completed integration")

    def test_canonical_snapshot_claim_is_required(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["integration"]["canonical_construction_snapshot"] = False
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "completed integration")

    def test_unfinished_integration_claim_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["integration"]["first_class_compiler_ir"] = True
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "must not be claimed complete")

    def test_write_enabled_workflow_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / ".github/workflows/increment-34-analog-control-flow.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "contents: read",
                    "contents: write",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "must remain read-only")

    def test_python_cache_is_ignored(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "scripts/__pycache__/probe.cpython-313.pyc"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"runtime cache")
            CHECKER.check_repository(root)


if __name__ == "__main__":
    unittest.main()
