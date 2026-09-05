from __future__ import annotations

import importlib.util
import json
import re
import shutil
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CURRENT_REVISION = re.search(
    r"^\*\*Revision:\*\* ([0-9]+\.[0-9]+)$",
    (ROOT / "docs/roadmap/nodal-development-todo.md").read_text(), re.M
).group(1)
CHECKER_PATH = ROOT / "scripts" / "check_increment33.py"
SPEC = importlib.util.spec_from_file_location("check_increment33", CHECKER_PATH)
assert SPEC is not None and SPEC.loader is not None
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


REQUIRED = (
    ".github/workflows/increment-33-analog-procedural-assignment.yml",
    "scripts/nodal.py",
    "core/compiler/diagnostics-v0.1.json",
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "core/compiler/include/nodal/Dialect/Nodal/NodalTypes.td",
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/IR/analog-procedural-invalid-variable-kind.mlir",
    "core/compiler/test/IR/analog-procedural-invalid-multiple.mlir",
    "core/native/include/nodal/AnalogProceduralRuntime.h",
    "core/scala/api/src/nodal/AnalogProceduralRuntime.scala",
    "core/scala/api/src/nodal/CandidateApi.scala",
    "core/scala/api/src/nodal/ContinuousTimeCandidateApi.scala",
    "core/scala/api/src/nodal/ElaborationConstructionKernel.scala",
    "core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala",
    "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala",
    "core/scala/testkit/test/src/nodal/AnalogProceduralConstructionTests.scala",
    "core/scala/testkit/test/src/nodal/internal/testkit/ScalaToMlirBridgeTests.scala",
    "docs/design-gates/NodalAnalogProceduralAssignment-DG-v0.1.md",
    "docs/implementation/increment33-analog-variables-procedural-assignment.md",
    "docs/implementation/increment33-evidence-closure.md",
    "docs/roadmap/nodal-development-todo.md",
    "examples/continuousTimeApi/src/nodal/increment33fixture/Increment33RuntimeCheck.scala",
    "tests/compiler/fixtures/increment32/manifest.json",
    "tests/compiler/fixtures/increment33/README.md",
    "tests/compiler/fixtures/increment33/analog_procedural_runtime_test.cpp",
    "tests/compiler/fixtures/increment33/manifest.json",
    "tests/compiler/fixtures/increment34/manifest.json",
    "tests/compiler/fixtures/increment35/manifest.json",
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
            manifest_path = root / "tests/compiler/fixtures/increment33/manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["status"] = "implementation-in-progress"
            manifest["validation"] = None
            manifest_path.write_text(
                json.dumps(manifest, indent=2) + "\n",
                encoding="utf-8",
            )
            path = root / "docs/roadmap/nodal-development-todo.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    f"**Revision:** {CURRENT_REVISION}",
                    "**Revision:** 1.43",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(
                root,
                "implementation state requires roadmap revision 1.43",
            )

    def test_validated_closure_requires_complete_evidence(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment33/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["validation"]["closure_validation_run"] = None
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "validated manifest lacks complete evidence")

    def test_validated_successor_requires_checked_roadmap(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "docs/roadmap/nodal-development-todo.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "- [x] **Increment 34 — Analog control flow**",
                    "- [ ] **Increment 34 — Analog control flow**",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(
                root,
                "validated Increment 34 successor evidence is inconsistent",
            )

    def test_validated_successor_requires_complete_evidence(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["validation"]["closure_validation_run"] = None
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(root, "validated Increment 34 lacks complete evidence")

    def test_open_successor_state_remains_supported(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            manifest_path = root / "tests/compiler/fixtures/increment34/manifest.json"
            document = json.loads(manifest_path.read_text(encoding="utf-8"))
            document["status"] = "implementation-in-progress"
            document["tranche"] = "34c-native-branch-sensitive-dataflow"
            document["validation"] = None
            manifest_path.write_text(
                json.dumps(document, indent=2) + "\n",
                encoding="utf-8",
            )
            increment35_path = root / "tests/compiler/fixtures/increment35/manifest.json"
            increment35 = json.loads(increment35_path.read_text(encoding="utf-8"))
            increment35["status"] = "implementation-in-progress"
            increment35["tranche"] = "35a-differential-integral-operator-contract"
            increment35["validation"] = None
            increment35_path.write_text(
                json.dumps(increment35, indent=2) + "\n",
                encoding="utf-8",
            )
            roadmap = root / "docs/roadmap/nodal-development-todo.md"
            roadmap.write_text(
                roadmap.read_text(encoding="utf-8")
                .replace(f"**Revision:** {CURRENT_REVISION}", "**Revision:** 1.44", 1)
                .replace(
                    "- [x] **Increment 34 — Analog control flow**",
                    "- [ ] **Increment 34 — Analog control flow**",
                    1,
                )
                .replace(
                    "- [x] **Increment 35 — Differential and integral operators**",
                    "- [ ] **Increment 35 — Differential and integral operators**",
                    1,
                ),
                encoding="utf-8",
            )
            CHECKER.check_repository(root)

    def test_increment35_successor_identity_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "tests/compiler/fixtures/increment35/manifest.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["validation"]["accepted_head"] = "0" * 40
            path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            self.assert_rejected(
                root,
                (
                    "Increment 35 closure candidate evidence is inconsistent"
                    if document.get("status") == "evidence-closure-candidate"
                    else "validated Increment 35 evidence is inconsistent"
                ),
            )

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

    def test_procedural_source_map_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    's"${record.identity}.read_$index"',
                    's"${record.identity}.read"',
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "source-map coverage is incomplete")

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


    def test_compound_dimension_regression_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = (
                root
                / "core/scala/testkit/test/src/nodal/AnalogProceduralConstructionTests.scala"
            )
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "public compound dimensionless assignment to a voltage variable is rejected",
                    "compound dimension regression removed",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "compound dimension regression is missing")

    def test_nested_chronology_regression_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = (
                root
                / "core/scala/testkit/test/src/nodal/internal/testkit/ScalaToMlirBridgeTests.scala"
            )
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "nested procedural scopes preserve declaration and assignment chronology",
                    "nested chronology regression removed",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "nested chronology regression is missing")


    def test_compound_read_dimension_regression_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = (
                root
                / "core/scala/testkit/test/src/nodal/AnalogProceduralConstructionTests.scala"
            )
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "public incompatible compound dimensions are rejected without read fallback",
                    "compound read-dimension regression removed",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "compound read-dimension regression is missing")

    def test_initializer_dependency_chronology_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = (
                root
                / "core/scala/testkit/test/src/nodal/internal/testkit/ScalaToMlirBridgeTests.scala"
            )
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "initializing assignments precede dependent declarations independent of provenance",
                    "initializer dependency chronology regression removed",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(
                root, "initializer dependency chronology regression is missing"
            )

    def test_recursive_dimension_mismatch_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/ElaborationConstructionKernel.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "  def compatibleAdd(other: AnalogDimension): AnalogDimension =\n"
                    "    if isUnknown || other.isUnknown then AnalogDimension.Unknown\n",
                    "  def compatibleAdd(other: AnalogDimension): AnalogDimension =\n"
                    "    if isUnknown then other\n",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "recursive dimension mismatches are not sticky")

    def test_nested_compound_dimension_regression_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = (
                root
                / "core/scala/testkit/test/src/nodal/AnalogProceduralConstructionTests.scala"
            )
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "public nested incompatible compound dimensions remain unknown",
                    "nested compound-dimension regression removed",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "nested compound-dimension regression is missing")

    def test_nodalc_bridge_boundary_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "scripts/nodal.py"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    '"NODAL_NODALC": str(nodalc)',
                    '"NODAL_NODALC_DISABLED": str(nodalc)',
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(
                root,
                "native command does not execute Scala bridge regressions against nodalc",
            )

    def test_boolean_result_metadata_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/CandidateApi.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    'resultType = Some(KernelTypeDescriptor("Bool"))',
                    "resultType = None",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "Boolean expression result metadata")

    def test_single_procedure_guard_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/AnalogProceduralRuntime.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "if procedureSeen then",
                    "if false then",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "Scala runtime is missing")

    def test_comparison_operand_dimension_validation_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/api/src/nodal/ElaborationConstructionKernel.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "inferBooleanExpressionDimension(expression)",
                    "AnalogDimension.Dimensionless",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "Boolean expression dimension inference is not delegated")

    def test_native_multiple_procedure_boundary_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/lib/Dialect/Nodal/NodalOps.cpp"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "return verifySingleTopLevelProcedurePerModule(getOperation());",
                    "return success();",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "native module boundary does not reject")

    def test_native_multiple_procedure_fixture_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/compiler/test/CMakeLists.txt"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    '"-DDIAGNOSTIC=NODAL-ANALOG-033-020"',
                    '"-DDIAGNOSTIC=NODAL-ANALOG-033-999"',
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "exact native multiple-procedure diagnostic fixture")


class AcceptedRoadmapRevisionTests(unittest.TestCase):
    def test_later_revision_preserves_accepted_predecessor(self):
        self.assertTrue(CHECKER.accepted_roadmap_revision("**Revision:** 1.47"))
        self.assertTrue(CHECKER.accepted_roadmap_revision("**Revision:** 1.100"))

    def test_missing_ambiguous_and_regressed_revisions_fail(self):
        for text in ("", "**Revision:** 1.45", "**Revision:** bad",
                     "**Revision:** 1.46\n**Revision:** 1.47"):
            with self.subTest(text=text):
                self.assertFalse(CHECKER.accepted_roadmap_revision(text))


if __name__ == "__main__":
    unittest.main()
