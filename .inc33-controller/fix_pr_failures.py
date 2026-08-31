#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "core/scala/api/src/nodal/AnalogProceduralConstruction.scala",
    '''        module.variables.put(pending.value, variable)

  def procedure[A](body: => A): A =
''',
    '''        module.variables.put(pending.value, variable)

  private def registeredIdentity(module: ModuleState, value: AnyRef): Option[String] =
    Option(module.variables.get(value)).map(_.identity).orElse:
      module.pending
        .find(pending => pending.value eq value)
        .map(pending => s"${module.owner}.variable_${pending.declarationOrder}")

  def procedure[A](body: => A): A =
''',
    "pending variable identity helper",
)

replace_once(
    "core/scala/api/src/nodal/AnalogProceduralConstruction.scala",
    '''      val foreign = state.modules.iterator
        .filterNot(_ eq module)
        .flatMap(candidate => Option(candidate.variables.get(target)).map(_ -> candidate))
        .toVector
        .headOption
      foreign match
        case Some((value, owner)) =>
          AnalogProceduralRuntime.reject(
            AnalogProceduralRuntime.Diagnostic(
              "NODAL-ANALOG-033-009",
              s"procedural variable belongs to component '${owner.owner}', not '${module.owner}'",
              Some(value.identity)
            )
          )
''',
    '''      val foreign = state.modules.iterator
        .filterNot(_ eq module)
        .flatMap(candidate => registeredIdentity(candidate, target).map(_ -> candidate))
        .toVector
        .headOption
      foreign match
        case Some((identity, owner)) =>
          AnalogProceduralRuntime.reject(
            AnalogProceduralRuntime.Diagnostic(
              "NODAL-ANALOG-033-009",
              s"procedural variable belongs to component '${owner.owner}', not '${module.owner}'",
              Some(identity)
            )
          )
''',
    "cross-component pending-variable ownership",
)

replace_once(
    "core/scala/testkit/test/src/nodal/AnalogProceduralConstructionTests.scala",
    '''    test("child procedural source capture uses its provisional instance path"):
      val snapshot = ConstructionKernel.inspect(new PublicAnalogParentWithChild)
      val procedural = snapshot.analogProcedural.find(_.assignments.nonEmpty).get
      assert(
        procedural.owner ==
          "PublicAnalogParentWithChild.PublicAnalogNestedChild_0"
      )
      assert(
        procedural.assignments.head.identity ==
          "PublicAnalogParentWithChild.PublicAnalogNestedChild_0.statement_0"
      )
''',
    '''    test("child procedural snapshot resolves to its authored instance path"):
      val snapshot = ConstructionKernel.inspect(new PublicAnalogParentWithChild)
      val procedural = snapshot.analogProcedural.find(_.assignments.nonEmpty).get
      assert(procedural.owner == "PublicAnalogParentWithChild.child")
      assert(
        procedural.assignments.head.identity ==
          "PublicAnalogParentWithChild.child.statement_0"
      )
''',
    "final authored child path expectation",
)

replace_once(
    "core/compiler/test/CMakeLists.txt",
    '''add_test(
  NAME nodal.native.analog-procedural-source-map-roundtrip
  COMMAND nodalc
    --mlir-print-op-generic
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-procedural.mlir"
)
''',
    '''add_test(
  NAME nodal.native.analog-procedural-source-map-roundtrip
  COMMAND nodalc
    --mlir-print-op-generic
    --mlir-print-debuginfo
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-procedural.mlir"
)
''',
    "source-map debug-info printing",
)

replace_once(
    "core/compiler/test/CMakeLists.txt",
    '''foreach(_fixture IN ITEMS read order owner type dimension guard analysis nested variable-kind)
''',
    '''foreach(_fixture IN ITEMS read order owner type dimension guard analysis nested)
''',
    "negative fixture loop",
)

replace_once(
    "core/compiler/test/CMakeLists.txt",
    '''set_tests_properties(
  nodal.native.analog-procedural-rejects-variable-kind
  PROPERTIES
    PASS_REGULAR_EXPRESSION "NODAL-ANALOG-033-019"
)
''',
    '''add_test(
  NAME nodal.native.analog-procedural-rejects-variable-kind
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-procedural-invalid-variable-kind.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-033-019"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)
''',
    "diagnostic-aware negative test",
)

Path("core/compiler/test/ExpectDiagnostic.cmake").write_text(
    '''if(NOT DEFINED NODALC)
  message(FATAL_ERROR "NODALC is required")
endif()
if(NOT DEFINED FIXTURE)
  message(FATAL_ERROR "FIXTURE is required")
endif()
if(NOT DEFINED DIAGNOSTIC)
  message(FATAL_ERROR "DIAGNOSTIC is required")
endif()

execute_process(
  COMMAND "${NODALC}" "${FIXTURE}"
  RESULT_VARIABLE result
  OUTPUT_VARIABLE standard_output
  ERROR_VARIABLE standard_error
)

set(output "${standard_output}${standard_error}")
message("${output}")

if(result EQUAL 0)
  message(FATAL_ERROR "expected nodalc to reject ${FIXTURE}")
endif()

string(FIND "${output}" "${DIAGNOSTIC}" diagnostic_position)
if(diagnostic_position EQUAL -1)
  message(FATAL_ERROR "expected diagnostic ${DIAGNOSTIC}")
endif()
''',
    encoding="utf-8",
)
