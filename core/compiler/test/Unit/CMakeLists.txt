add_executable(nodal-native-unit-tests
  VersionTest.cpp
)

llvm_update_compile_flags(nodal-native-unit-tests)

target_link_libraries(nodal-native-unit-tests
  PRIVATE
    NodalSupport
    CIRCTSupport
    MLIRSupport
    LLVMSupport
)

add_test(
  NAME nodal.native.version-unit
  COMMAND nodal-native-unit-tests
)

add_executable(nodal-dialect-unit-tests
  DialectTest.cpp
)

llvm_update_compile_flags(nodal-dialect-unit-tests)

target_link_libraries(nodal-dialect-unit-tests
  PRIVATE
    NodalDialect
    MLIRIR
    MLIRParser
    MLIRSupport
    LLVMSupport
)

add_test(
  NAME nodal.native.dialect-unit
  COMMAND nodal-dialect-unit-tests
)
