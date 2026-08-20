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
