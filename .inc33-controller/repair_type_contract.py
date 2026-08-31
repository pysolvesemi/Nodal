#!/usr/bin/env python3
"""Complete Increment 33 type-boundary tests and workflow coverage."""

from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one replacement, found {count}")
    file.write_text(text.replace(old, new), encoding="utf-8")


cmake = "core/compiler/test/CMakeLists.txt"
replace_once(
    cmake,
    "foreach(_fixture IN ITEMS read order owner type dimension guard analysis nested)\n",
    "foreach(_fixture IN ITEMS read order owner type dimension guard analysis nested variable-kind)\n",
)
replace_once(
    cmake,
    """endforeach()

add_custom_target(check-nodal-native
""",
    """endforeach()

set_tests_properties(
  nodal.native.analog-procedural-rejects-variable-kind
  PROPERTIES
    PASS_REGULAR_EXPRESSION "NODAL-ANALOG-033-019"
)

add_custom_target(check-nodal-native
""",
)

checker = "scripts/check_increment33.py"
replace_once(
    checker,
    """    diagnostics = read_text(root, "core/compiler/diagnostics-v0.1.json")
    for token in ("Nodal_VariableType", "NODAL-ANALOG-033-019"):
""",
    """    diagnostics = read_text(root, "core/compiler/diagnostics-v0.1.json")
    compiler_tests = read_text(root, "core/compiler/test/CMakeLists.txt")
    invalid_variable_kind = read_text(
        root, "core/compiler/test/IR/analog-procedural-invalid-variable-kind.mlir"
    )
    for token in ("Nodal_VariableType", "NODAL-ANALOG-033-019"):
""",
)
replace_once(
    checker,
    """    for token in (
        "Nodal_AnalogProcedureOp",
""",
    """    require(
        '!nodal.variable<"string", "1">' in invalid_variable_kind,
        "NODAL-INC33-042: invalid variable-kind fixture does not cross the type parser boundary",
    )
    require(
        "nodal.native.analog-procedural-rejects-variable-kind" in compiler_tests
        and "NODAL-ANALOG-033-019" in compiler_tests,
        "NODAL-INC33-043: native type-boundary test does not require diagnostic 019",
    )
    for token in (
        "Nodal_AnalogProcedureOp",
""",
)

unit_tests = "tests/compiler/test_increment33.py"
replace_once(
    unit_tests,
    """REQUIRED = (
    ".github/workflows/increment-33-analog-procedural-assignment.yml",
    "core/native/include/nodal/AnalogProceduralRuntime.h",
""",
    """REQUIRED = (
    ".github/workflows/increment-33-analog-procedural-assignment.yml",
    "core/compiler/diagnostics-v0.1.json",
    "core/compiler/include/nodal/Dialect/Nodal/NodalOps.td",
    "core/compiler/include/nodal/Dialect/Nodal/NodalTypes.td",
    "core/compiler/lib/Dialect/Nodal/NodalOps.cpp",
    "core/compiler/test/CMakeLists.txt",
    "core/compiler/test/IR/analog-procedural-invalid-variable-kind.mlir",
    "core/native/include/nodal/AnalogProceduralRuntime.h",
""",
)
replace_once(
    unit_tests,
    """    "core/scala/api/src/nodal/AnalogProceduralRuntime.scala",
    "docs/design-gates/NodalAnalogProceduralAssignment-DG-v0.1.md",
""",
    """    "core/scala/api/src/nodal/AnalogProceduralRuntime.scala",
    "core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala",
    "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala",
    "docs/design-gates/NodalAnalogProceduralAssignment-DG-v0.1.md",
""",
)
replace_once(
    unit_tests,
    """

if __name__ == "__main__":
    unittest.main()
""",
    """

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
""",
)

workflow = ".github/workflows/increment-33-analog-procedural-assignment.yml"
replace_once(
    workflow,
    """    paths:
      - '.github/workflows/increment-33-analog-procedural-assignment.yml'
      - 'core/native/include/nodal/AnalogProceduralRuntime.h'
      - 'core/scala/api/src/nodal/AnalogProceduralRuntime.scala'
      - 'docs/design-gates/NodalAnalogProceduralAssignment-DG-v0.1.md'
      - 'docs/implementation/increment33-analog-variables-procedural-assignment.md'
      - 'examples/continuousTimeApi/src/nodal/increment33fixture/**'
      - 'scripts/check_increment33.py'
      - 'tests/compiler/fixtures/increment33/**'
      - 'tests/compiler/test_increment33.py'
      - 'tests/compiler/fixtures/increment32/manifest.json'
      - 'docs/roadmap/nodal-development-todo.md'
""",
    """    paths:
      - '.github/workflows/increment-33-analog-procedural-assignment.yml'
      - 'core/compiler/**'
      - 'core/native/include/nodal/AnalogProceduralRuntime.h'
      - 'core/scala/api/src/nodal/AnalogProceduralConstruction.scala'
      - 'core/scala/api/src/nodal/AnalogProceduralRuntime.scala'
      - 'core/scala/api/src/nodal/CandidateApi.scala'
      - 'core/scala/api/src/nodal/ContinuousTimeCandidateApi.scala'
      - 'core/scala/api/src/nodal/ElaborationConstructionKernel.scala'
      - 'core/scala/bridge/src/nodal/bridge/AnalogProceduralEvidence.scala'
      - 'core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala'
      - 'core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala'
      - 'core/scala/testkit/test/src/nodal/AnalogProceduralConstructionTests.scala'
      - 'core/scala/testkit/test/src/nodal/internal/testkit/ScalaToMlirBridgeTests.scala'
      - 'docs/design-gates/NodalAnalogProceduralAssignment-DG-v0.1.md'
      - 'docs/implementation/increment33-analog-variables-procedural-assignment.md'
      - 'examples/continuousTimeApi/src/nodal/increment33fixture/**'
      - 'scripts/check_increment33.py'
      - 'tests/compiler/fixtures/increment32/manifest.json'
      - 'tests/compiler/fixtures/increment33/**'
      - 'tests/compiler/test_increment33.py'
      - 'docs/roadmap/nodal-development-todo.md'
""",
)
replace_once(
    workflow,
    "    timeout-minutes: 30\n",
    "    timeout-minutes: 60\n",
)
replace_once(
    workflow,
    """      - name: Validate predecessor and Increment 33 contracts
        run: |
""",
    """      - name: Install pinned native and lint toolchains
        run: |
          ./nodal bootstrap \\
            --mode prebuilt \\
            --prefix "${RUNNER_TEMP}/nodal-native-toolchain"
          ./nodal style bootstrap \\
            --prefix "${RUNNER_TEMP}/nodal-lint-toolchain"

      - name: Validate predecessor and Increment 33 contracts
        run: |
""",
)
replace_once(
    workflow,
    """      - name: Execute Scala source-semantic witness
        run: |
""",
    """      - name: Execute Scala API and bridge suite
        run: ./nodal core scala

      - name: Execute native compiler and verifier suite
        run: |
          ./nodal core native \\
            --toolchain "${RUNNER_TEMP}/nodal-native-toolchain" \\
            --lint-toolchain "${RUNNER_TEMP}/nodal-lint-toolchain"

      - name: Execute Scala source-semantic witness
        run: |
""",
)
