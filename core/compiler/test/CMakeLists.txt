find_package(Python3 REQUIRED COMPONENTS Interpreter)

add_subdirectory(Unit)

add_test(
  NAME nodal.native.nodalc-version
  COMMAND nodalc --version
)
set_tests_properties(nodal.native.nodalc-version PROPERTIES
  PASS_REGULAR_EXPRESSION "nodalc 0[.]0[.]0-dev"
)

add_test(
  NAME nodal.native.nodal-placeholder-roundtrip
  COMMAND nodalc "${CMAKE_CURRENT_SOURCE_DIR}/IR/placeholder.mlir"
)
set_tests_properties(nodal.native.nodal-placeholder-roundtrip PROPERTIES
  PASS_REGULAR_EXPRESSION "nodal[.]placeholder"
)

add_test(
  NAME nodal.native.nodal-placeholder-generic
  COMMAND nodalc --mlir-print-op-generic
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/placeholder.mlir"
)
set_tests_properties(nodal.native.nodal-placeholder-generic PROPERTIES
  PASS_REGULAR_EXPRESSION "nodal[.]placeholder"
)

add_test(
  NAME nodal.native.nodal-placeholder-rejects-empty-label
  COMMAND nodalc "${CMAKE_CURRENT_SOURCE_DIR}/IR/placeholder-invalid.mlir"
)
set_tests_properties(
  nodal.native.nodal-placeholder-rejects-empty-label
  PROPERTIES
    WILL_FAIL TRUE
)

add_test(
  NAME nodal.native.core-model-roundtrip
  COMMAND nodalc "${CMAKE_CURRENT_SOURCE_DIR}/IR/core-model.mlir"
)
set_tests_properties(nodal.native.core-model-roundtrip PROPERTIES
  PASS_REGULAR_EXPRESSION "nodal[.]module"
)

add_test(
  NAME nodal.native.core-model-generic
  COMMAND nodalc --mlir-print-op-generic
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/core-model.mlir"
)
set_tests_properties(nodal.native.core-model-generic PROPERTIES
  PASS_REGULAR_EXPRESSION "nodal[.]fsm"
)

foreach(_fixture IN ITEMS width net enum fsm)
  add_test(
    NAME nodal.native.core-model-rejects-${_fixture}
    COMMAND nodalc
      "${CMAKE_CURRENT_SOURCE_DIR}/IR/core-model-invalid-${_fixture}.mlir"
  )
  set_tests_properties(
    nodal.native.core-model-rejects-${_fixture}
    PROPERTIES
      WILL_FAIL TRUE
  )
endforeach()

# The loop below materializes the three stable test identities required by the
# Increment 21 contract: semantic-pipeline-fast, semantic-pipeline-default,
# and semantic-pipeline-release.
foreach(_profile IN ITEMS fast default release)
  add_test(
    NAME nodal.native.semantic-pipeline-${_profile}
    COMMAND nodalc
      "--pass-pipeline=builtin.module(nodal-gate-${_profile})"
      "${CMAKE_CURRENT_SOURCE_DIR}/IR/semantic-pipeline-valid.mlir"
  )
  set_tests_properties(
    nodal.native.semantic-pipeline-${_profile}
    PROPERTIES
      PASS_REGULAR_EXPRESSION "nodal[.]pipeline[.]normalized"
  )
endforeach()

add_test(
  NAME nodal.native.semantic-pipeline-rejects-invalid-stages
  COMMAND nodalc
    --split-input-file
    "--pass-pipeline=builtin.module(nodal-gate-default)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/semantic-pipeline-invalid.mlir"
)
set_tests_properties(
  nodal.native.semantic-pipeline-rejects-invalid-stages
  PROPERTIES
    WILL_FAIL TRUE
)

foreach(_fixture IN ITEMS inventory operation)
  add_test(
    NAME nodal.native.cross-layer-diagnostics-rejects-${_fixture}
    COMMAND nodalc
      --split-input-file
      "--pass-pipeline=builtin.module(nodal-verify-cross-layer-diagnostics)"
      "${CMAKE_CURRENT_SOURCE_DIR}/IR/diagnostic-mapping-${_fixture}-invalid.mlir"
  )
  set_tests_properties(
    nodal.native.cross-layer-diagnostics-rejects-${_fixture}
    PROPERTIES
      WILL_FAIL TRUE
  )
endforeach()

add_test(
  NAME nodal.native.backend-translation-registration
  COMMAND nodal-translate --help
)
set_tests_properties(
  nodal.native.backend-translation-registration
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal-to-verilog-a"
)

add_test(
  NAME nodal.native.backend-verilog-a
  COMMAND nodal-translate
    --nodal-to-verilog-a
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/backend-minimal-analog.mlir"
)
set_tests_properties(
  nodal.native.backend-verilog-a
  PROPERTIES
    PASS_REGULAR_EXPRESSION "module Alpha;"
)

add_test(
  NAME nodal.native.backend-verilog-ams
  COMMAND nodal-translate
    --nodal-to-verilog-ams
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/backend-minimal-ams.mlir"
)
set_tests_properties(
  nodal.native.backend-verilog-ams
  PROPERTIES
    PASS_REGULAR_EXPRESSION "module MixedTop;"
)

add_test(
  NAME nodal.native.backend-rejects-invalid-config
  COMMAND nodal-translate
    --nodal-to-verilog-a
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/backend-invalid-config.mlir"
)
set_tests_properties(
  nodal.native.backend-rejects-invalid-config
  PROPERTIES
    WILL_FAIL TRUE
)

add_test(
  NAME nodal.native.backend-rejects-unsupported-verilog-a
  COMMAND nodal-translate
    --nodal-to-verilog-a
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/backend-unsupported-verilog-a.mlir"
)
set_tests_properties(
  nodal.native.backend-rejects-unsupported-verilog-a
  PROPERTIES
    WILL_FAIL TRUE
)


add_test(
  NAME nodal.native.analog-expression-roundtrip
  COMMAND nodalc
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-expression-rc.mlir"
)
set_tests_properties(
  nodal.native.analog-expression-roundtrip
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]contribute"
)

add_test(
  NAME nodal.native.analog-expression-generic
  COMMAND nodalc
    --mlir-print-op-generic
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-expression-rc.mlir"
)
set_tests_properties(
  nodal.native.analog-expression-generic
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]analog_ddt"
)

foreach(_fixture IN ITEMS parameter contribution)
  add_test(
    NAME nodal.native.analog-expression-rejects-invalid-${_fixture}
    COMMAND nodalc
      "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-expression-invalid-${_fixture}.mlir"
  )
  set_tests_properties(
    nodal.native.analog-expression-rejects-invalid-${_fixture}
    PROPERTIES
      WILL_FAIL TRUE
  )
endforeach()


add_test(
  NAME nodal.native.rc-filter-vertical-slice
  COMMAND nodal-translate
    --nodal-to-verilog-a
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/rc-filter-vertical-slice.mlir"
)
set_tests_properties(
  nodal.native.rc-filter-vertical-slice
  PROPERTIES
    PASS_REGULAR_EXPRESSION "I[(]p, n[)] <[+]"
)


add_test(
  NAME nodal.native.natures-disciplines-roundtrip
  COMMAND nodalc
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/natures-disciplines.mlir"
)
set_tests_properties(
  nodal.native.natures-disciplines-roundtrip
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]discipline_import"
)

add_test(
  NAME nodal.native.natures-disciplines-generic
  COMMAND nodalc
    --mlir-print-op-generic
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/natures-disciplines.mlir"
)
set_tests_properties(
  nodal.native.natures-disciplines-generic
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]nature_import"
)

foreach(_fixture IN ITEMS tolerance association import-cycle)
  add_test(
    NAME nodal.native.natures-disciplines-rejects-${_fixture}
    COMMAND nodalc
      "${CMAKE_CURRENT_SOURCE_DIR}/IR/natures-disciplines-invalid-${_fixture}.mlir"
  )
  set_tests_properties(
    nodal.native.natures-disciplines-rejects-${_fixture}
    PROPERTIES
      WILL_FAIL TRUE
  )
endforeach()


add_test(
  NAME nodal.native.electrical-connectivity-roundtrip
  COMMAND nodalc
    "--pass-pipeline=builtin.module(nodal-materialize-conservative-connectivity)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/electrical-connectivity.mlir"
)
set_tests_properties(
  nodal.native.electrical-connectivity-roundtrip
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]flow_conservation"
)

add_test(
  NAME nodal.native.electrical-connectivity-generic
  COMMAND nodalc
    --mlir-print-op-generic
    "--pass-pipeline=builtin.module(nodal-materialize-conservative-connectivity)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/electrical-connectivity.mlir"
)
set_tests_properties(
  nodal.native.electrical-connectivity-generic
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]connection_set"
)

foreach(_fixture IN ITEMS discipline implicit connection reference component)
  add_test(
    NAME nodal.native.electrical-connectivity-rejects-${_fixture}
    COMMAND nodalc
      "--pass-pipeline=builtin.module(nodal-materialize-conservative-connectivity)"
      "${CMAKE_CURRENT_SOURCE_DIR}/IR/electrical-connectivity-invalid-${_fixture}.mlir"
  )
  set_tests_properties(
    nodal.native.electrical-connectivity-rejects-${_fixture}
    PROPERTIES
      WILL_FAIL TRUE
  )
endforeach()


add_test(
  NAME nodal.native.parameters-units-roundtrip
  COMMAND nodalc
    "--pass-pipeline=builtin.module(nodal-verify-parameters)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/parameters-units.mlir"
)
set_tests_properties(
  nodal.native.parameters-units-roundtrip
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]parameter_envelope"
)

add_test(
  NAME nodal.native.parameters-units-generic
  COMMAND nodalc
    --mlir-print-op-generic
    "--pass-pipeline=builtin.module(nodal-verify-parameters)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/parameters-units.mlir"
)
set_tests_properties(
  nodal.native.parameters-units-generic
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]const_expr"
)

foreach(_fixture IN ITEMS unit constraint envelope dynamic override cycle)
  add_test(
    NAME nodal.native.parameters-units-rejects-${_fixture}
    COMMAND nodalc
      "--pass-pipeline=builtin.module(nodal-verify-parameters)"
      "${CMAKE_CURRENT_SOURCE_DIR}/IR/parameters-units-invalid-${_fixture}.mlir"
  )
  set_tests_properties(
    nodal.native.parameters-units-rejects-${_fixture}
    PROPERTIES
      WILL_FAIL TRUE
  )
endforeach()

add_test(
  NAME nodal.native.parameter-lossless-rendering
  COMMAND nodal-translate
    --nodal-to-verilog-a
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/parameter-rendering.mlir"
)
set_tests_properties(
  nodal.native.parameter-lossless-rendering
  PROPERTIES
    PASS_REGULAR_EXPRESSION "parameter real R = 1k from \\[1:10k\\] exclude 5k; // unit: Ohm"
)

add_test(
  NAME nodal.native.analog-numeric-typing
  COMMAND nodalc
    "--pass-pipeline=builtin.module(nodal-fold-analog-constants,nodal-verify-analog-numeric)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-numeric-typing.mlir"
)
set_tests_properties(
  nodal.native.analog-numeric-typing
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]folded_value"
)

foreach(_fixture IN ITEMS type promotion dimension compare logic select divide)
  add_test(
    NAME nodal.native.analog-numeric-rejects-${_fixture}
    COMMAND nodalc
      "--pass-pipeline=builtin.module(nodal-verify-analog-numeric)"
      "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-numeric-invalid-${_fixture}.mlir"
  )
  set_tests_properties(
    nodal.native.analog-numeric-rejects-${_fixture}
    PROPERTIES
      WILL_FAIL TRUE
  )
endforeach()

add_test(
  NAME nodal.native.analog-numeric-backend
  COMMAND nodal-translate
    --nodal-to-verilog-a
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-numeric-backend.mlir"
)
set_tests_properties(
  nodal.native.analog-numeric-backend
  PROPERTIES
    PASS_REGULAR_EXPRESSION "I[(]p, n[)] <[+]"
)

add_test(
  NAME nodal.native.analog-numeric-select-promotion
  COMMAND nodalc
    "--pass-pipeline=builtin.module(nodal-fold-analog-constants,nodal-verify-analog-numeric)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-numeric-select-promotion.mlir"
)
set_tests_properties(
  nodal.native.analog-numeric-select-promotion
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]folded_kind = .real."
)

add_test(
  NAME nodal.native.analog-numeric-parameter-scale
  COMMAND nodalc
    "--pass-pipeline=builtin.module(nodal-fold-analog-constants,nodal-verify-analog-numeric)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-numeric-parameter-scale.mlir"
)
set_tests_properties(
  nodal.native.analog-numeric-parameter-scale
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]folded_value = 2[.]000000e[+]03"
)

add_test(
  NAME nodal.native.analog-numeric-rejects-dimension-overflow
  COMMAND nodalc
    "--pass-pipeline=builtin.module(nodal-verify-analog-numeric)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-numeric-invalid-dimension-overflow.mlir"
)
set_tests_properties(
  nodal.native.analog-numeric-rejects-dimension-overflow
  PROPERTIES
    WILL_FAIL TRUE
)

add_test(
  NAME nodal.native.analog-numeric-backend-fold-boundary
  COMMAND nodal-translate
    --nodal-to-verilog-a
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-numeric-backend-fold-boundary.mlir"
)
set_tests_properties(
  nodal.native.analog-numeric-backend-fold-boundary
  PROPERTIES
    PASS_REGULAR_EXPRESSION "V[(]p, n[)] <[+] V[(]p, n[)]"
)


add_test(
  NAME nodal.native.potential-flow-access-normalize
  COMMAND nodalc
    "--pass-pipeline=builtin.module(nodal-normalize-potential-flow-access,nodal-normalize-potential-flow-access)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/potential-flow-access.mlir"
)
set_tests_properties(
  nodal.native.potential-flow-access-normalize
  PROPERTIES
    PASS_REGULAR_EXPRESSION "form = .two-terminal."
)

add_test(
  NAME nodal.native.potential-flow-access-reference
  COMMAND nodalc
    "--pass-pipeline=builtin.module(nodal-gate-release)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/potential-flow-access.mlir"
)
set_tests_properties(
  nodal.native.potential-flow-access-reference
  PROPERTIES
    PASS_REGULAR_EXPRESSION "reference_identity = .global::customElectrical."
)

foreach(_fixture IN ITEMS form discipline nature function dimension reference port probe-kind probe-provenance)
  add_test(
    NAME nodal.native.potential-flow-access-rejects-${_fixture}
    COMMAND nodalc
      "--pass-pipeline=builtin.module(nodal-normalize-potential-flow-access)"
      "${CMAKE_CURRENT_SOURCE_DIR}/IR/potential-flow-access-invalid-${_fixture}.mlir"
  )
  set_tests_properties(
    nodal.native.potential-flow-access-rejects-${_fixture}
    PROPERTIES
      WILL_FAIL TRUE
  )
endforeach()

foreach(_render IN ITEMS discipline generic port)
  add_test(
    NAME nodal.native.potential-flow-access-backend-${_render}
    COMMAND nodal-translate
      --nodal-to-verilog-a
      "${CMAKE_CURRENT_SOURCE_DIR}/IR/potential-flow-access.mlir"
  )
endforeach()
set_tests_properties(
  nodal.native.potential-flow-access-backend-discipline
  PROPERTIES
    PASS_REGULAR_EXPRESSION "Across[(]named_parallel[)]"
)
set_tests_properties(
  nodal.native.potential-flow-access-backend-generic
  PROPERTIES
    PASS_REGULAR_EXPRESSION "potential[(]p, n[)]"
)
set_tests_properties(
  nodal.native.potential-flow-access-backend-port
  PROPERTIES
    PASS_REGULAR_EXPRESSION "Through[(]<p>[)]"
)

add_test(
  NAME nodal.native.analog-procedural-roundtrip
  COMMAND nodalc
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-procedural.mlir"
)
set_tests_properties(
  nodal.native.analog-procedural-roundtrip
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]analog_assign"
)

add_test(
  NAME nodal.native.analog-procedural-source-map-roundtrip
  COMMAND nodalc
    --mlir-print-op-generic
    --mlir-print-debuginfo
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-procedural.mlir"
)
set_tests_properties(
  nodal.native.analog-procedural-source-map-roundtrip
  PROPERTIES
    PASS_REGULAR_EXPRESSION "AnalogProcedural[.]scala"
)

foreach(_fixture IN ITEMS read order owner type dimension guard analysis nested)
  add_test(
    NAME nodal.native.analog-procedural-rejects-${_fixture}
    COMMAND nodalc
      "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-procedural-invalid-${_fixture}.mlir"
  )
  set_tests_properties(
    nodal.native.analog-procedural-rejects-${_fixture}
    PROPERTIES
      WILL_FAIL TRUE
  )
endforeach()

add_test(
  NAME nodal.native.analog-procedural-rejects-variable-kind
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-procedural-invalid-variable-kind.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-033-019"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)

add_test(
  NAME nodal.native.analog-procedural-rejects-multiple
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-procedural-invalid-multiple.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-033-020"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)


add_test(
  NAME nodal.native.analog-control-flow-roundtrip
  COMMAND nodalc
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow.mlir"
)
set_tests_properties(
  nodal.native.analog-control-flow-roundtrip
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]analog_loop"
)

add_test(
  NAME nodal.native.analog-control-flow-source-map-roundtrip
  COMMAND nodalc
    --mlir-print-op-generic
    --mlir-print-debuginfo
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow.mlir"
)
set_tests_properties(
  nodal.native.analog-control-flow-source-map-roundtrip
  PROPERTIES
    PASS_REGULAR_EXPRESSION "AnalogControlFlow[.]scala"
)

foreach(_fixture IN ITEMS break loop condition case)
  add_test(
    NAME nodal.native.analog-control-flow-rejects-${_fixture}
    COMMAND "${CMAKE_COMMAND}"
      "-DNODALC=$<TARGET_FILE:nodalc>"
      "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-${_fixture}.mlir"
      "-DDIAGNOSTIC=NODAL-ANALOG-034-"
      -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
  )
endforeach()


foreach(_fixture IN ITEMS missing-else missing-default zero-trip continue-path)
  add_test(
    NAME nodal.native.analog-control-flow-rejects-${_fixture}
    COMMAND "${CMAKE_COMMAND}"
      "-DNODALC=$<TARGET_FILE:nodalc>"
      "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-${_fixture}.mlir"
      "-DDIAGNOSTIC=NODAL-ANALOG-034-004"
      -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
  )
endforeach()

add_test(
  NAME nodal.native.analog-control-flow-rejects-duplicate-identity
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-duplicate-identity.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-034-001"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)

add_test(
  NAME nodal.native.analog-control-flow-rejects-order
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-order.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-034-014"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)

add_test(
  NAME nodal.native.analog-control-flow-rejects-guard-read
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-guard-read.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-034-004"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)

add_test(
  NAME nodal.native.analog-control-flow-rejects-unreachable-reference
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-unreachable-reference.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-034-014"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)

add_test(
  NAME nodal.native.analog-control-flow-rejects-forward-reference
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-forward-reference.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-034-014"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)

add_test(
  NAME nodal.native.analog-control-flow-rejects-owner
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-owner.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-033-009"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)

add_test(
  NAME nodal.native.analog-control-flow-rejects-case-label
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-case-label.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-034-007"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)

foreach(_fixture IN ITEMS runtime-static-sentinel else-static-sentinel)
  add_test(
    NAME nodal.native.analog-control-flow-rejects-${_fixture}
    COMMAND "${CMAKE_COMMAND}"
      "-DNODALC=$<TARGET_FILE:nodalc>"
      "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-${_fixture}.mlir"
      "-DDIAGNOSTIC=NODAL-ANALOG-034-003"
      -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
  )
endforeach()

add_test(
  NAME nodal.native.analog-control-flow-rejects-loop-static-sentinel
  COMMAND "${CMAKE_COMMAND}"
    "-DNODALC=$<TARGET_FILE:nodalc>"
    "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-control-flow-invalid-loop-static-sentinel.mlir"
    "-DDIAGNOSTIC=NODAL-ANALOG-034-008"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
)

add_test(
  NAME nodal.native.analog-differential-integral-roundtrip
  COMMAND nodalc
    "--pass-pipeline=builtin.module(nodal-fold-analog-constants,nodal-verify-analog-numeric)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-differential-integral.mlir"
)
set_tests_properties(
  nodal.native.analog-differential-integral-roundtrip
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]simplification_rule"
)

add_test(
  NAME nodal.native.analog-differential-integral-retains-idt
  COMMAND nodalc
    "--pass-pipeline=builtin.module(nodal-fold-analog-constants,nodal-verify-analog-numeric)"
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-differential-integral.mlir"
)
set_tests_properties(
  nodal.native.analog-differential-integral-retains-idt
  PROPERTIES
    PASS_REGULAR_EXPRESSION "nodal[.]analog_idt"
)

add_test(
  NAME nodal.native.analog-differential-integral-backend
  COMMAND nodal-translate
    --nodal-to-verilog-a
    "${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-differential-integral-backend.mlir"
)
set_tests_properties(
  nodal.native.analog-differential-integral-backend
  PROPERTIES
    PASS_REGULAR_EXPRESSION "idt[(]"
)

foreach(_fixture IN ITEMS context initial state simplification idt-fold)
  add_test(
    NAME nodal.native.analog-differential-integral-rejects-${_fixture}
    COMMAND "${CMAKE_COMMAND}"
      "-DNODALC=$<TARGET_FILE:nodalc>"
      "-DFIXTURE=${CMAKE_CURRENT_SOURCE_DIR}/IR/analog-differential-integral-invalid-${_fixture}.mlir"
      "-DDIAGNOSTIC=NODAL-ANALOG-035-"
      -P "${CMAKE_CURRENT_SOURCE_DIR}/ExpectDiagnostic.cmake"
  )
endforeach()

add_test(
  NAME nodal.native.analog-time-waveform-matrix
  COMMAND "${Python3_EXECUTABLE}"
    "${NODAL_REPOSITORY_ROOT}/tests/compiler/fixtures/increment36/run_native_matrix.py"
    --nodalc "$<TARGET_FILE:nodalc>"
    --translate "$<TARGET_FILE:nodal-translate>"
)

add_custom_target(check-nodal-native
  COMMAND "${CMAKE_CTEST_COMMAND}" --output-on-failure -C "$<CONFIG>"
  DEPENDS
    nodal-backend-unit-tests
    nodal-translate
    nodalc
    nodal-native-unit-tests
    nodal-dialect-unit-tests
    nodal-core-model-unit-tests
    nodal-semantic-pipeline-unit-tests
    nodal-analog-expression-unit-tests
    nodal-nature-discipline-unit-tests
    nodal-conservative-connectivity-unit-tests
    nodal-parameter-model-unit-tests
  WORKING_DIRECTORY "${CMAKE_BINARY_DIR}"
  USES_TERMINAL
)
