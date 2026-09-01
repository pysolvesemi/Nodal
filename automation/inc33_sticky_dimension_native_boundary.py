#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


kernel_path = Path("core/scala/api/src/nodal/ElaborationConstructionKernel.scala")
replace_once(
    kernel_path,
    "  def compatibleAdd(other: AnalogDimension): AnalogDimension =\n"
    "    if isZero && !other.isUnknown then other.copy(isZero = other.isZero || isZero)\n"
    "    else if other.isZero && !isUnknown then copy(isZero = isZero || other.isZero)\n"
    "    else if isUnknown then other\n"
    "    else if other.isUnknown then this\n"
    "    else if powers == other.powers then copy(isZero = isZero && other.isZero)\n"
    "    else AnalogDimension.Unknown\n",
    "  def compatibleAdd(other: AnalogDimension): AnalogDimension =\n"
    "    if isUnknown || other.isUnknown then AnalogDimension.Unknown\n"
    "    else if isZero then other.copy(isZero = other.isZero || isZero)\n"
    "    else if other.isZero then copy(isZero = isZero || other.isZero)\n"
    "    else if powers == other.powers then copy(isZero = isZero && other.isZero)\n"
    "    else AnalogDimension.Unknown\n",
    "sticky additive dimension mismatch",
)

construction_tests_path = Path(
    "core/scala/testkit/test/src/nodal/AnalogProceduralConstructionTests.scala"
)
construction_tests = construction_tests_path.read_text(encoding="utf-8")
class_anchor = "final class PublicAnalogCompoundVoltage extends Module:\n"
nested_class = """final class PublicAnalogNestedCompoundReadDimensionMismatch extends Module:
  val voltage: Variable[Real] = variable(Real, 0.0.V)

  analogProcedure:
    voltage := (voltage + 1.0.real) + voltage

"""
if "final class PublicAnalogNestedCompoundReadDimensionMismatch" not in construction_tests:
    if construction_tests.count(class_anchor) != 1:
        raise SystemExit("nested compound class anchor is not unique")
    construction_tests = construction_tests.replace(
        class_anchor, nested_class + class_anchor, 1
    )
test_anchor = '    test("public compound voltage assignment retains its physical dimension"):\n'
nested_test = """    test("nested incompatible compound dimensions remain unknown"):
      val failure = scala.util
        .Try(
          ConstructionKernel.inspect(new PublicAnalogNestedCompoundReadDimensionMismatch)
        )
        .failed
        .get
        .asInstanceOf[AnalogProceduralRuntime.Failure]
      assert(failure.diagnostic.code == "NODAL-ANALOG-033-013")

"""
if "nested incompatible compound dimensions remain unknown" not in construction_tests:
    if construction_tests.count(test_anchor) != 1:
        raise SystemExit("nested compound test anchor is not unique")
    construction_tests = construction_tests.replace(
        test_anchor, nested_test + test_anchor, 1
    )
construction_tests_path.write_text(construction_tests, encoding="utf-8")

workflow_path = Path(
    ".github/workflows/increment-33-analog-procedural-assignment.yml"
)
workflow = workflow_path.read_text(encoding="utf-8")
old_native = """      - name: Execute native compiler and verifier suite
        run: |
          ./nodal core native \\
            --toolchain "${RUNNER_TEMP}/nodal-native-toolchain" \\
            --lint-toolchain "${RUNNER_TEMP}/nodal-lint-toolchain"
"""
new_native = old_native + """          test -x out/native/release/bin/nodalc
          NODAL_NODALC="$PWD/out/native/release/bin/nodalc" \\
            ./mill core.scala.testkit.test.testOnly \\
              nodal.internal.testkit.ScalaToMlirBridgeTests
"""
if "NODAL_NODALC=\"$PWD/out/native/release/bin/nodalc\"" not in workflow:
    if workflow.count(old_native) != 1:
        raise SystemExit("native workflow step is not unique")
    workflow = workflow.replace(old_native, new_native, 1)
workflow_path.write_text(workflow, encoding="utf-8")

checker_path = Path("scripts/check_increment33.py")
checker = checker_path.read_text(encoding="utf-8")
construction_anchor = """    require(
        "public incompatible compound dimensions are rejected without read fallback"
        in construction_tests,
        "NODAL-INC33-062: compound read-dimension regression is missing",
    )
"""
additional_checks = construction_anchor + """    require(
        "nested incompatible compound dimensions remain unknown" in construction_tests,
        "NODAL-INC33-067: sticky compound-dimension regression is missing",
    )
    construction_kernel = read_text(
        root, "core/scala/api/src/nodal/ElaborationConstructionKernel.scala"
    )
    require(
        "if isUnknown || other.isUnknown then AnalogDimension.Unknown"
        in construction_kernel,
        "NODAL-INC33-068: additive dimension mismatch is not sticky",
    )
"""
if "NODAL-INC33-067" not in checker:
    if checker.count(construction_anchor) != 1:
        raise SystemExit("checker compound regression anchor is not unique")
    checker = checker.replace(construction_anchor, additional_checks, 1)
workflow_anchor = """        require("contents: read" in workflow, "NODAL-INC33-030: workflow must be read-only")
"""
workflow_checks = workflow_anchor + """        require(
            'NODAL_NODALC="$PWD/out/native/release/bin/nodalc"' in workflow
            and "nodal.internal.testkit.ScalaToMlirBridgeTests" in workflow,
            "NODAL-INC33-069: permanent workflow skips Scala-generated MLIR through nodalc",
        )
"""
if "NODAL-INC33-069" not in checker:
    if checker.count(workflow_anchor) != 1:
        raise SystemExit("checker workflow anchor is not unique")
    checker = checker.replace(workflow_anchor, workflow_checks, 1)
checker_path.write_text(checker, encoding="utf-8")

mutation_path = Path("tests/compiler/test_increment33.py")
mutations = mutation_path.read_text(encoding="utf-8")
anchor = '\nif __name__ == "__main__":\n'
new_mutations = '''
    def test_sticky_compound_dimension_regression_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = (
                root
                / "core/scala/testkit/test/src/nodal/AnalogProceduralConstructionTests.scala"
            )
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "nested incompatible compound dimensions remain unknown",
                    "sticky compound dimension regression removed",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "sticky compound-dimension regression is missing")

    def test_native_bridge_workflow_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / ".github/workflows/increment-33-analog-procedural-assignment.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    'NODAL_NODALC="$PWD/out/native/release/bin/nodalc"',
                    'NODAL_NODALC=""',
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(
                root, "permanent workflow skips Scala-generated MLIR through nodalc"
            )

'''
if "test_native_bridge_workflow_mutation_is_rejected" not in mutations:
    if mutations.count(anchor) != 1:
        raise SystemExit("mutation insertion anchor is not unique")
    mutations = mutations.replace(anchor, "\n" + new_mutations + anchor, 1)
mutation_path.write_text(mutations, encoding="utf-8")
