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
  WORKING_DIRECTORY "${CMAKE_BINARY_DIR}"
  USES_TERMINAL
)
