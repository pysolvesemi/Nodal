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

add_executable(nodal-core-model-unit-tests
  CoreModelTest.cpp
)

llvm_update_compile_flags(nodal-core-model-unit-tests)

target_link_libraries(nodal-core-model-unit-tests
  PRIVATE
    NodalDialect
    MLIRIR
    MLIRParser
    MLIRSupport
    LLVMSupport
)

add_test(
  NAME nodal.native.core-model-unit
  COMMAND nodal-core-model-unit-tests
)

add_executable(nodal-semantic-pipeline-unit-tests
  SemanticPipelineTest.cpp
)

llvm_update_compile_flags(nodal-semantic-pipeline-unit-tests)

target_link_libraries(nodal-semantic-pipeline-unit-tests
  PRIVATE
    NodalDialect
    NodalTransforms
    MLIRIR
    MLIRParser
    MLIRPass
    MLIRSupport
    LLVMSupport
)

add_test(
  NAME nodal.native.semantic-pipeline-unit
  COMMAND nodal-semantic-pipeline-unit-tests
)

add_executable(nodal-backend-unit-tests
  BackendTest.cpp
)

llvm_update_compile_flags(nodal-backend-unit-tests)

target_link_libraries(nodal-backend-unit-tests
  PRIVATE
    NodalBackend
    NodalDialect
    CIRCTHW
    MLIRIR
    MLIRParser
    MLIRSupport
    LLVMSupport
)

add_test(
  NAME nodal.native.backend-unit
  COMMAND nodal-backend-unit-tests
)

add_executable(nodal-analog-expression-unit-tests
  AnalogExpressionTest.cpp
)

llvm_update_compile_flags(nodal-analog-expression-unit-tests)

target_link_libraries(nodal-analog-expression-unit-tests
  PRIVATE
    NodalDialect
    MLIRIR
    MLIRParser
    MLIRSupport
    LLVMSupport
)

add_test(
  NAME nodal.native.analog-expression-unit
  COMMAND nodal-analog-expression-unit-tests
)

add_executable(nodal-nature-discipline-unit-tests
  NatureDisciplineTest.cpp
)

llvm_update_compile_flags(nodal-nature-discipline-unit-tests)

target_link_libraries(nodal-nature-discipline-unit-tests
  PRIVATE
    NodalDialect
    MLIRIR
    MLIRParser
    MLIRSupport
    LLVMSupport
)

add_test(
  NAME nodal.native.nature-discipline-unit
  COMMAND nodal-nature-discipline-unit-tests
)
