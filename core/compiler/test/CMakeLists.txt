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

add_custom_target(check-nodal-native
  COMMAND "${CMAKE_CTEST_COMMAND}" --output-on-failure -C "$<CONFIG>"
  DEPENDS
    nodalc
    nodal-native-unit-tests
    nodal-dialect-unit-tests
  WORKING_DIRECTORY "${CMAKE_BINARY_DIR}"
  USES_TERMINAL
)
