add_subdirectory(Unit)

add_test(
  NAME nodal.native.nodalc-version
  COMMAND nodalc --version
)
set_tests_properties(nodal.native.nodalc-version PROPERTIES
  PASS_REGULAR_EXPRESSION "nodalc 0\\.0\\.0-dev"
)

add_custom_target(check-nodal-native
  COMMAND "${CMAKE_CTEST_COMMAND}" --output-on-failure -C "$<CONFIG>"
  DEPENDS nodalc nodal-native-unit-tests
  WORKING_DIRECTORY "${CMAKE_BINARY_DIR}"
  USES_TERMINAL
)
