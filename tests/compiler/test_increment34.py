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
    "core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala",
    "core/scala/testkit/test/src/nodal/internal/testkit/ScalaToMlirBridgeTests.scala",
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/IR/analog-control-flow.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-missing-else.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-missing-default.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-zero-trip.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-continue-path.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-duplicate-identity.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-order.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-guard-read.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-unreachable-reference.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-case-label.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-runtime-static-sentinel.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-else-static-sentinel.mlir",
    "core/compiler/test/IR/analog-control-flow-invalid-loop-static-sentinel.mlir",
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
            self.assert_rejected(root, "validated Increment 33 baseline")

    def test_unvalidated_predecessor_manifest_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment33/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["status"] = "implementation-in-progress"
            document["validation"] = None
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "lacks validated predecessor evidence")

    def test_predecessor_closure_identity_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["baseline"]["increment_33_closure_pr"] = 999
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "validated Increment 33 baseline")

    def test_predecessor_roadmap_regression_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "docs/roadmap/nodal-development-todo.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "- [x] **Increment 33 — Analog variables and procedural assignment**",
                    "- [ ] **Increment 33 — Analog variables and procedural assignment**",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "roadmap does not contain the validated")

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
            document["integration"]["target_lowering"] = True
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "must not be claimed complete")


    def test_structured_bridge_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    '"nodal.analog_if"',
                    '"nodal.removed_analog_if"',
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "structured Scala-to-MLIR bridge is missing")

    def test_native_control_op_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    'Nodal_Op<"analog_loop"',
                    'Nodal_Op<"removed_analog_loop"',
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "first-class native control-flow operations is missing")

    def test_native_verifier_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "LogicalResult nodal::AnalogBreakOp::verify()",
                    "LogicalResult nodal::RemovedAnalogBreakOp::verify()",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native structured control-flow verification is missing")



    def test_native_dataflow_manifest_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["integration"][
                "native_branch_sensitive_definite_assignment"
            ] = False
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(
                root,
                "completed integration 'native_branch_sensitive_definite_assignment' is not recorded",
            )

    def test_native_branch_intersection_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "body->continues.begin()",
                    "body->breaks.begin()",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native branch-sensitive definite assignment is missing")

    def test_native_global_identity_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "registerStructuredOperationIdentity",
                    "removedRegisterStructuredOperationIdentity",
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native structured verifier hardening is missing")

    def test_native_assignment_order_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "structured assignment order must be contiguous and authored",
                    "removed structured assignment order validation",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native structured verifier hardening is missing")

    def test_native_guard_read_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    '"guard_reads", input, context',
                    '"removed_guard_reads", input, context',
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native structured verifier hardening is missing")

    def test_native_case_label_canonicality_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "isCanonicalStructuredCaseLabel",
                    "removedCanonicalStructuredCaseLabel",
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native structured verifier hardening is missing")

    def test_native_hardening_manifest_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["semantics"]["native_guard_read_definite_assignment"] = False
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "native hardening semantic")

    def test_native_runtime_static_sentinel_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "staticPresent.getValue() || staticValue.getValue()",
                    "staticPresent.getValue()",
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native canonical staging sentinels is missing")

    def test_native_loop_static_sentinel_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "staticPresent.getValue() || staticCount.getInt() != 0",
                    "staticPresent.getValue()",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native canonical staging sentinels is missing")

    def test_native_staging_manifest_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["semantics"]["native_canonical_condition_sentinels"] = False
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "native staging semantic")

    def test_nested_declaration_locality_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogControlFlowRuntime.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "nested declaration must be block-local",
                    "removed nested declaration locality",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "Scala control-flow runtime is missing")

    def test_unreachable_structural_reference_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "verifyStructuredReferenceInventory",
                    "removedVerifyStructuredReferenceInventory",
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native structured verifier hardening is missing")

    def test_fresh_review_manifest_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["semantics"][
                "native_unreachable_structural_reference_validation"
            ] = False
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "native hardening semantic")

    def test_control_owner_validation_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogControlFlowConstruction.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "control-flow source owner",
                    "unvalidated source owner",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "control-flow construction bridge is missing")

    def test_pre_control_scope_alignment_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogProceduralConstruction.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "builder.lexicalScope(source, Some(builderIdentity))",
                    "builder.lexicalScope(source)",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "procedural construction integration is missing")

    def test_owner_scope_manifest_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["semantics"]["pre_control_lexical_scope_alignment"] = False
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "semantic contract")

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
