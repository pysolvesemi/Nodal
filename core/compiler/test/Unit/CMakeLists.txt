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


add_executable(nodal-conservative-connectivity-unit-tests
  ConservativeConnectivityTest.cpp
)

llvm_update_compile_flags(nodal-conservative-connectivity-unit-tests)

target_link_libraries(nodal-conservative-connectivity-unit-tests
  PRIVATE
    NodalDialect
    MLIRIR
    MLIRParser
    MLIRSupport
    LLVMSupport
)

add_test(
  NAME nodal.native.conservative-connectivity-unit
  COMMAND nodal-conservative-connectivity-unit-tests
)


add_executable(nodal-parameter-model-unit-tests
  ParameterModelTest.cpp
)

llvm_update_compile_flags(nodal-parameter-model-unit-tests)

target_link_libraries(nodal-parameter-model-unit-tests
  PRIVATE
    NodalDialect
    MLIRIR
    MLIRParser
    MLIRSupport
    LLVMSupport
)

add_test(
  NAME nodal.native.parameter-model-unit
  COMMAND nodal-parameter-model-unit-tests
)

add_executable(nodal-noise-reparse-unit-tests
  AnalogNoiseReparseTest.cpp
)
llvm_update_compile_flags(nodal-noise-reparse-unit-tests)
target_link_libraries(nodal-noise-reparse-unit-tests
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
  NAME nodal.native.noise-reparse-unit
  COMMAND nodal-noise-reparse-unit-tests
)

add_executable(nodal-transfer-reparse-unit-tests
  AnalogTransferReparseTest.cpp
)
llvm_update_compile_flags(nodal-transfer-reparse-unit-tests)
target_link_libraries(nodal-transfer-reparse-unit-tests
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
  NAME nodal.native.transfer-reparse-unit
  COMMAND nodal-transfer-reparse-unit-tests
)

add_executable(nodal-user-function-reparse-unit-tests
  AnalogUserFunctionReparseTest.cpp
)
llvm_update_compile_flags(nodal-user-function-reparse-unit-tests)
target_link_libraries(nodal-user-function-reparse-unit-tests
  PRIVATE NodalBackend NodalDialect CIRCTHW MLIRIR MLIRParser MLIRSupport LLVMSupport
)
add_test(NAME nodal.native.user-function-reparse-unit COMMAND nodal-user-function-reparse-unit-tests)

add_executable(nodal-hierarchy-order-unit-tests HierarchyOrderTest.cpp)
llvm_update_compile_flags(nodal-hierarchy-order-unit-tests)
add_test(NAME nodal.native.hierarchy-order COMMAND nodal-hierarchy-order-unit-tests)

add_executable(nodal-hierarchy-syntax-unit-tests AnalogHierarchySyntaxTest.cpp)
llvm_update_compile_flags(nodal-hierarchy-syntax-unit-tests)
add_test(NAME nodal.native.hierarchy-syntax COMMAND nodal-hierarchy-syntax-unit-tests)

add_dependencies(nodal-native-unit-tests
  nodal-hierarchy-order-unit-tests
  nodal-hierarchy-syntax-unit-tests
)

add_test(
  NAME nodal.native.hierarchy-integration
  COMMAND "${Python3_EXECUTABLE}"
    "${NODAL_REPOSITORY_ROOT}/tests/compiler/fixtures/increment42/run_hierarchy_matrix.py"
    --nodalc "$<TARGET_FILE:nodalc>"
    --work-dir "${CMAKE_BINARY_DIR}/increment42-hierarchy-evidence"
)
set_tests_properties(nodal.native.hierarchy-integration PROPERTIES TIMEOUT 180)

add_executable(nodal-parameter-integer-bounds-unit-tests ParameterIntegerBoundsTest.cpp)
llvm_update_compile_flags(nodal-parameter-integer-bounds-unit-tests)
target_link_libraries(nodal-parameter-integer-bounds-unit-tests
  PRIVATE NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport
)
add_test(NAME nodal.native.parameter-integer-bounds
  COMMAND nodal-parameter-integer-bounds-unit-tests
)
add_test(NAME nodal.native.iteration-envelope-integration
  COMMAND "${Python3_EXECUTABLE}"
    "${NODAL_REPOSITORY_ROOT}/tests/compiler/fixtures/increment43/run_integer_bounds_matrix.py"
    --nodalc "$<TARGET_FILE:nodalc>"
    --work-dir "${CMAKE_BINARY_DIR}/increment43-integer-bounds-evidence"
)
set_tests_properties(nodal.native.iteration-envelope-integration PROPERTIES TIMEOUT 60)

add_test(NAME nodal.native.generate-count-integration
  COMMAND "${Python3_EXECUTABLE}"
    "${NODAL_REPOSITORY_ROOT}/tests/compiler/fixtures/increment43/run_generate_count_matrix.py"
    --nodalc "$<TARGET_FILE:nodalc>"
    --work-dir "${CMAKE_BINARY_DIR}/increment43-generate-count-evidence"
)
set_tests_properties(nodal.native.generate-count-integration PROPERTIES TIMEOUT 60)

add_test(NAME nodal.native.generate-ssa-integration
  COMMAND "${Python3_EXECUTABLE}"
    "${NODAL_REPOSITORY_ROOT}/tests/compiler/fixtures/increment43/run_generate_ssa_matrix.py"
    --nodalc "$<TARGET_FILE:nodalc>"
    --work-dir "${CMAKE_BINARY_DIR}/increment43-generate-ssa-evidence"
)
set_tests_properties(nodal.native.generate-ssa-integration PROPERTIES TIMEOUT 60)

add_test(NAME nodal.native.generate-ownership-integration
  COMMAND "${Python3_EXECUTABLE}"
    "${NODAL_REPOSITORY_ROOT}/tests/compiler/fixtures/increment43/run_generate_ownership_matrix.py"
    --nodalc "$<TARGET_FILE:nodalc>"
    --work-dir "${CMAKE_BINARY_DIR}/increment43-generate-ownership-evidence"
)
set_tests_properties(nodal.native.generate-ownership-integration PROPERTIES TIMEOUT 60)

add_test(NAME nodal.native.shape-contract-integration
  COMMAND "${Python3_EXECUTABLE}"
    "${NODAL_REPOSITORY_ROOT}/tests/compiler/fixtures/increment43/run_shape_contract_matrix.py"
    --nodalc "$<TARGET_FILE:nodalc>"
    --work-dir "${CMAKE_BINARY_DIR}/increment43-shape-contract-evidence"
)
set_tests_properties(nodal.native.shape-contract-integration PROPERTIES TIMEOUT 60)

add_test(NAME nodal.native.shape-index-integration
  COMMAND "${Python3_EXECUTABLE}"
    "${NODAL_REPOSITORY_ROOT}/tests/compiler/fixtures/increment43/run_shape_index_matrix.py"
    --nodalc "$<TARGET_FILE:nodalc>"
    --work-dir "${CMAKE_BINARY_DIR}/increment43-shape-index-evidence"
)
set_tests_properties(nodal.native.shape-index-integration PROPERTIES TIMEOUT 60)

add_test(NAME nodal.native.shape-view-integration
  COMMAND "${Python3_EXECUTABLE}"
    "${NODAL_REPOSITORY_ROOT}/tests/compiler/fixtures/increment43/run_shape_view_matrix.py"
    --nodalc "$<TARGET_FILE:nodalc>"
    --work-dir "${CMAKE_BINARY_DIR}/increment43-shape-view-evidence"
)
set_tests_properties(nodal.native.shape-view-integration PROPERTIES TIMEOUT 60)

add_test(NAME nodal.native.shape-slice-integration
  COMMAND "${Python3_EXECUTABLE}"
    "${NODAL_REPOSITORY_ROOT}/tests/compiler/fixtures/increment43/run_shape_slice_matrix.py"
    --nodalc "$<TARGET_FILE:nodalc>"
    --work-dir "${CMAKE_BINARY_DIR}/increment43-shape-slice-evidence"
)
set_tests_properties(nodal.native.shape-slice-integration PROPERTIES TIMEOUT 60)

add_executable(nodal-node-array-unit-tests NodeArrayTest.cpp)
llvm_update_compile_flags(nodal-node-array-unit-tests)
target_link_libraries(nodal-node-array-unit-tests
  PRIVATE NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.node-array-unit COMMAND nodal-node-array-unit-tests)

add_executable(nodal-event-reparse-unit-tests AnalogEventReparseTest.cpp)
llvm_update_compile_flags(nodal-event-reparse-unit-tests)
target_link_libraries(nodal-event-reparse-unit-tests
  PRIVATE NodalBackend NodalDialect CIRCTHW MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.event-reparse-unit COMMAND nodal-event-reparse-unit-tests)

add_executable(nodal-storage-model-unit-tests AnalogStorageModelTest.cpp)
llvm_update_compile_flags(nodal-storage-model-unit-tests)
target_link_libraries(nodal-storage-model-unit-tests
  PRIVATE NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.storage-model-unit COMMAND nodal-storage-model-unit-tests)

add_executable(nodal-writable-array-backend-unit-tests WritableArrayBackendTest.cpp)
llvm_update_compile_flags(nodal-writable-array-backend-unit-tests)
target_link_libraries(nodal-writable-array-backend-unit-tests
  PRIVATE NodalBackend NodalDialect CIRCTHW MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.writable-array-backend-unit COMMAND nodal-writable-array-backend-unit-tests)

add_test(NAME nodal.native.writable-array-integration
  COMMAND "${Python3_EXECUTABLE}"
    "${NODAL_REPOSITORY_ROOT}/tests/compiler/fixtures/increment43/run_writable_array_matrix.py"
    --nodalc "$<TARGET_FILE:nodalc>"
    --work-dir "${CMAKE_BINARY_DIR}/increment43-writable-array-evidence"
)
set_tests_properties(nodal.native.writable-array-integration PROPERTIES
  TIMEOUT 60
  ENVIRONMENT "PYTHONDONTWRITEBYTECODE=1"
)

add_test(NAME nodal.native.typed-control-integration
  COMMAND "${Python3_EXECUTABLE}"
    "${NODAL_REPOSITORY_ROOT}/tests/compiler/fixtures/increment43/run_typed_control_matrix.py"
    --nodalc "$<TARGET_FILE:nodalc>"
    --work-dir "${CMAKE_BINARY_DIR}/increment43-typed-control-evidence"
)
set_tests_properties(nodal.native.typed-control-integration PROPERTIES
  TIMEOUT 60
  ENVIRONMENT "PYTHONDONTWRITEBYTECODE=1"
)

add_test(NAME nodal.native.boundary-array-integration
  COMMAND "${Python3_EXECUTABLE}"
    "${NODAL_REPOSITORY_ROOT}/tests/compiler/fixtures/increment43/run_boundary_array_matrix.py"
    --nodalc "$<TARGET_FILE:nodalc>"
    --work-dir "${CMAKE_BINARY_DIR}/increment43-boundary-array-evidence"
)
set_tests_properties(nodal.native.boundary-array-integration PROPERTIES
  TIMEOUT 60
  ENVIRONMENT "PYTHONDONTWRITEBYTECODE=1"
)

add_executable(nodal-boundary-array-backend-unit-tests BoundaryArrayBackendTest.cpp)
llvm_update_compile_flags(nodal-boundary-array-backend-unit-tests)
target_link_libraries(nodal-boundary-array-backend-unit-tests
  PRIVATE NodalBackend NodalDialect CIRCTHW MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.boundary-array-backend-unit COMMAND nodal-boundary-array-backend-unit-tests)

add_executable(nodal-generated-topology-backend-unit-tests GeneratedTopologyBackendTest.cpp)
llvm_update_compile_flags(nodal-generated-topology-backend-unit-tests)
target_link_libraries(nodal-generated-topology-backend-unit-tests
  PRIVATE NodalBackend NodalDialect CIRCTHW MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.generated-topology-backend-unit COMMAND nodal-generated-topology-backend-unit-tests)

add_executable(nodal-generated-persistent-backend-unit-tests GeneratedPersistentBackendTest.cpp)
llvm_update_compile_flags(nodal-generated-persistent-backend-unit-tests)
target_link_libraries(nodal-generated-persistent-backend-unit-tests
  PRIVATE NodalBackend NodalDialect CIRCTHW MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.generated-persistent-backend-unit COMMAND nodal-generated-persistent-backend-unit-tests)

add_test(NAME nodal.native.generated-topology-integration
  COMMAND "${Python3_EXECUTABLE}"
    "${NODAL_REPOSITORY_ROOT}/tests/compiler/fixtures/increment43/run_generated_topology_matrix.py"
    --nodalc "$<TARGET_FILE:nodalc>"
    --work-dir "${CMAKE_BINARY_DIR}/increment43-generated-topology-evidence"
)
set_tests_properties(nodal.native.generated-topology-integration PROPERTIES
  TIMEOUT 60
  ENVIRONMENT "PYTHONDONTWRITEBYTECODE=1"
)

add_executable(nodal-conservative-relation-backend-unit-tests ConservativeRelationBackendTest.cpp)
llvm_update_compile_flags(nodal-conservative-relation-backend-unit-tests)
target_link_libraries(nodal-conservative-relation-backend-unit-tests
  PRIVATE NodalBackend NodalDialect CIRCTHW MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.conservative-relation-backend-unit COMMAND nodal-conservative-relation-backend-unit-tests)

add_executable(nodal-shape-region-unit-tests ShapeRegionTest.cpp)
llvm_update_compile_flags(nodal-shape-region-unit-tests)
target_link_libraries(nodal-shape-region-unit-tests
  PRIVATE NodalDialect CIRCTHW MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.shape-region-unit COMMAND nodal-shape-region-unit-tests)

add_test(NAME nodal.native.conservative-relation-integration
  COMMAND "${Python3_EXECUTABLE}"
    "${NODAL_REPOSITORY_ROOT}/tests/compiler/fixtures/increment43/run_conservative_relation_matrix.py"
    --nodalc "$<TARGET_FILE:nodalc>"
    --work-dir "${CMAKE_BINARY_DIR}/increment43-conservative-relation-evidence"
)
set_tests_properties(nodal.native.conservative-relation-integration PROPERTIES
  TIMEOUT 60
  ENVIRONMENT "PYTHONDONTWRITEBYTECODE=1"
)

add_executable(nodal-generated-probe-backend-unit-tests GeneratedProbeBackendTest.cpp)
llvm_update_compile_flags(nodal-generated-probe-backend-unit-tests)
target_link_libraries(nodal-generated-probe-backend-unit-tests
  PRIVATE NodalBackend NodalDialect CIRCTHW MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.generated-probe-backend-unit COMMAND nodal-generated-probe-backend-unit-tests)

add_test(NAME nodal.native.generated-probe-placement
  COMMAND "${Python3_EXECUTABLE}" -B
    "${NODAL_REPOSITORY_ROOT}/tests/compiler/fixtures/increment43/run_generated_probe_placement_matrix.py"
    --nodalc "$<TARGET_FILE:nodalc>"
    --output-root "${NODAL_REPOSITORY_ROOT}/.work/generated-probe-placement-native"
)
set_tests_properties(nodal.native.generated-probe-placement PROPERTIES
  TIMEOUT 60
  ENVIRONMENT "PYTHONDONTWRITEBYTECODE=1"
)

add_test(NAME nodal.native.generated-local-array-integration
  COMMAND "${Python3_EXECUTABLE}" -B
    "${NODAL_REPOSITORY_ROOT}/tests/compiler/fixtures/increment43/run_generated_local_array_matrix.py"
    --nodalc "$<TARGET_FILE:nodalc>"
    --work-dir "${NODAL_REPOSITORY_ROOT}/.work/generated-local-array-native"
)
set_tests_properties(nodal.native.generated-local-array-integration PROPERTIES
  TIMEOUT 60
  ENVIRONMENT "PYTHONDONTWRITEBYTECODE=1"
)

add_executable(nodal-live-fill-unit-tests AnalogLiveFillTest.cpp)
llvm_update_compile_flags(nodal-live-fill-unit-tests)
target_link_libraries(nodal-live-fill-unit-tests
  PRIVATE NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.live-fill-unit COMMAND nodal-live-fill-unit-tests)

add_executable(nodal-analog-live-control-plan-unit-tests AnalogLiveControlPlanTest.cpp)
llvm_update_compile_flags(nodal-analog-live-control-plan-unit-tests)
target_link_libraries(nodal-analog-live-control-plan-unit-tests
  PRIVATE NodalDialect MLIRIR MLIRSupport LLVMSupport)
add_test(NAME nodal.native.analog-live-control-plan-unit
  COMMAND nodal-analog-live-control-plan-unit-tests)

add_executable(nodal-context-unit-tests AnalogContextTest.cpp)
llvm_update_compile_flags(nodal-context-unit-tests)
target_link_libraries(nodal-context-unit-tests
  PRIVATE NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.context-unit COMMAND nodal-context-unit-tests)

add_executable(nodal-analog-context-backend-unit-tests AnalogContextBackendTest.cpp)
llvm_update_compile_flags(nodal-analog-context-backend-unit-tests)
target_link_libraries(nodal-analog-context-backend-unit-tests
  PRIVATE NodalBackend NodalDialect CIRCTHW MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.analog-context-backend-unit COMMAND nodal-analog-context-backend-unit-tests)

add_executable(nodal-analog-divisor-unit-tests AnalogDivisorTest.cpp)
llvm_update_compile_flags(nodal-analog-divisor-unit-tests)
target_link_libraries(nodal-analog-divisor-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.analog-divisor-unit COMMAND nodal-analog-divisor-unit-tests)

add_executable(nodal-analog-dead-local-unit-tests AnalogDeadLocalTest.cpp)
llvm_update_compile_flags(nodal-analog-dead-local-unit-tests)
target_link_libraries(nodal-analog-dead-local-unit-tests PRIVATE
  NodalBackend NodalDialect CIRCTHW MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.analog-dead-local-unit COMMAND nodal-analog-dead-local-unit-tests)
set_tests_properties(nodal.native.analog-divisor-unit PROPERTIES TIMEOUT 30)

add_executable(nodal-analog-canonicalization-unit-tests AnalogCanonicalizationTest.cpp)
llvm_update_compile_flags(nodal-analog-canonicalization-unit-tests)
target_link_libraries(nodal-analog-canonicalization-unit-tests PRIVATE
  NodalTransforms NodalDialect MLIRIR MLIRParser MLIRPass MLIRSupport LLVMSupport)
add_test(NAME nodal.native.analog-canonicalization-unit COMMAND nodal-analog-canonicalization-unit-tests)
set_tests_properties(nodal.native.analog-canonicalization-unit PROPERTIES TIMEOUT 30)

add_executable(nodal-analog-semantic-lint-unit-tests AnalogSemanticLintTest.cpp)
llvm_update_compile_flags(nodal-analog-semantic-lint-unit-tests)
target_link_libraries(nodal-analog-semantic-lint-unit-tests PRIVATE
  NodalTransforms NodalDialect MLIRIR MLIRParser MLIRPass MLIRSupport LLVMSupport)
add_test(NAME nodal.native.analog-semantic-lint-unit COMMAND nodal-analog-semantic-lint-unit-tests)
set_tests_properties(nodal.native.analog-semantic-lint-unit PROPERTIES TIMEOUT 30)

add_executable(nodal-parameter-nonzero-unit-tests ParameterNonzeroTest.cpp)
llvm_update_compile_flags(nodal-parameter-nonzero-unit-tests)
target_link_libraries(nodal-parameter-nonzero-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.parameter-nonzero-unit COMMAND nodal-parameter-nonzero-unit-tests)
set_tests_properties(nodal.native.parameter-nonzero-unit PROPERTIES TIMEOUT 30)

add_executable(nodal-analog-equation-model-unit-tests AnalogEquationModelTest.cpp)
llvm_update_compile_flags(nodal-analog-equation-model-unit-tests)
target_link_libraries(nodal-analog-equation-model-unit-tests PRIVATE
  NodalDialect NodalTransforms MLIRIR MLIRParser MLIRPass MLIRSupport LLVMSupport)
add_test(NAME nodal.native.analog-equation-model-unit COMMAND nodal-analog-equation-model-unit-tests)
set_tests_properties(nodal.native.analog-equation-model-unit PROPERTIES TIMEOUT 30)

add_executable(nodal-target-capability-unit-tests TargetCapabilityTest.cpp)
llvm_update_compile_flags(nodal-target-capability-unit-tests)
target_link_libraries(nodal-target-capability-unit-tests PRIVATE
  NodalBackend NodalDialect CIRCTHW MLIRIR MLIRParser MLIRPass MLIRSupport LLVMSupport)
add_test(NAME nodal.native.target-capability-unit COMMAND nodal-target-capability-unit-tests)
set_tests_properties(nodal.native.target-capability-unit PROPERTIES TIMEOUT 30)

add_executable(nodal-statement-provenance-unit-tests StatementProvenanceTest.cpp)
llvm_update_compile_flags(nodal-statement-provenance-unit-tests)
target_link_libraries(nodal-statement-provenance-unit-tests PRIVATE
  NodalBackend NodalDialect CIRCTHW MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.statement-provenance-unit COMMAND nodal-statement-provenance-unit-tests)
set_tests_properties(nodal.native.statement-provenance-unit PROPERTIES TIMEOUT 30)

add_executable(nodal-scalar-hierarchy-materialization-unit-tests ScalarHierarchyMaterializationTest.cpp)
llvm_update_compile_flags(nodal-scalar-hierarchy-materialization-unit-tests)
target_link_libraries(nodal-scalar-hierarchy-materialization-unit-tests PRIVATE
  NodalBackend NodalDialect CIRCTHW MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.scalar-hierarchy-materialization-unit
  COMMAND nodal-scalar-hierarchy-materialization-unit-tests)
set_tests_properties(nodal.native.scalar-hierarchy-materialization-unit PROPERTIES TIMEOUT 30)

add_executable(nodal-enum-encoding-unit-tests EnumEncodingTest.cpp)
llvm_update_compile_flags(nodal-enum-encoding-unit-tests)
target_link_libraries(nodal-enum-encoding-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.enum-encoding-unit COMMAND nodal-enum-encoding-unit-tests)
set_tests_properties(nodal.native.enum-encoding-unit PROPERTIES TIMEOUT 30)

add_executable(nodal-digital-type-model-unit-tests DigitalTypeModelTest.cpp)
llvm_update_compile_flags(nodal-digital-type-model-unit-tests)
target_link_libraries(nodal-digital-type-model-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.digital-type-model-unit COMMAND nodal-digital-type-model-unit-tests)
set_tests_properties(nodal.native.digital-type-model-unit PROPERTIES TIMEOUT 30)

add_executable(nodal-enum-value-model-unit-tests EnumValueModelTest.cpp)
llvm_update_compile_flags(nodal-enum-value-model-unit-tests)
target_link_libraries(nodal-enum-value-model-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.enum-value-model-unit COMMAND nodal-enum-value-model-unit-tests)
set_tests_properties(nodal.native.enum-value-model-unit PROPERTIES TIMEOUT 30)

add_executable(nodal-digital-materialization-model-unit-tests DigitalMaterializationModelTest.cpp)
llvm_update_compile_flags(nodal-digital-materialization-model-unit-tests)
target_link_libraries(nodal-digital-materialization-model-unit-tests PRIVATE
  NodalTransforms NodalDialect MLIRIR MLIRParser MLIRPass MLIRSupport LLVMSupport)
add_test(NAME nodal.native.digital-materialization-model-unit COMMAND nodal-digital-materialization-model-unit-tests)
set_tests_properties(nodal.native.digital-materialization-model-unit PROPERTIES TIMEOUT 30)

add_executable(nodal-digital-value-model-unit-tests DigitalValueModelTest.cpp)
llvm_update_compile_flags(nodal-digital-value-model-unit-tests)
target_link_libraries(nodal-digital-value-model-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.digital-value-model-unit COMMAND nodal-digital-value-model-unit-tests)
set_tests_properties(nodal.native.digital-value-model-unit PROPERTIES TIMEOUT 30)

add_executable(nodal-digital-storage-model-unit-tests DigitalStorageModelTest.cpp)
llvm_update_compile_flags(nodal-digital-storage-model-unit-tests)
target_link_libraries(nodal-digital-storage-model-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.digital-storage-model-unit COMMAND nodal-digital-storage-model-unit-tests)
set_tests_properties(nodal.native.digital-storage-model-unit PROPERTIES TIMEOUT 30)

add_executable(nodal-digital-interface-model-unit-tests DigitalInterfaceModelTest.cpp)
llvm_update_compile_flags(nodal-digital-interface-model-unit-tests)
target_link_libraries(nodal-digital-interface-model-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.digital-interface-model-unit COMMAND nodal-digital-interface-model-unit-tests)
set_tests_properties(nodal.native.digital-interface-model-unit PROPERTIES TIMEOUT 30)

add_executable(nodal-digital-collection-model-unit-tests DigitalCollectionModelTest.cpp)
llvm_update_compile_flags(nodal-digital-collection-model-unit-tests)
target_link_libraries(nodal-digital-collection-model-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.digital-collection-model-unit COMMAND nodal-digital-collection-model-unit-tests)
set_tests_properties(nodal.native.digital-collection-model-unit PROPERTIES TIMEOUT 30)

add_executable(nodal-digital-reduction-model-unit-tests DigitalReductionModelTest.cpp)
llvm_update_compile_flags(nodal-digital-reduction-model-unit-tests)
target_link_libraries(nodal-digital-reduction-model-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.digital-reduction-model-unit COMMAND nodal-digital-reduction-model-unit-tests)
set_tests_properties(nodal.native.digital-reduction-model-unit PROPERTIES TIMEOUT 60)

add_executable(nodal-digital-reduction-context-unit-tests DigitalReductionContextTest.cpp)
llvm_update_compile_flags(nodal-digital-reduction-context-unit-tests)
target_link_libraries(nodal-digital-reduction-context-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.digital-reduction-context-unit COMMAND nodal-digital-reduction-context-unit-tests)
set_tests_properties(nodal.native.digital-reduction-context-unit PROPERTIES TIMEOUT 60)

add_executable(nodal-digital-loop-model-unit-tests DigitalLoopModelTest.cpp)
llvm_update_compile_flags(nodal-digital-loop-model-unit-tests)
target_link_libraries(nodal-digital-loop-model-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.digital-loop-model-unit COMMAND nodal-digital-loop-model-unit-tests)
set_tests_properties(nodal.native.digital-loop-model-unit PROPERTIES TIMEOUT 60)

add_executable(nodal-digital-loop-planning-unit-tests DigitalLoopPlanningModelTest.cpp)
llvm_update_compile_flags(nodal-digital-loop-planning-unit-tests)
target_link_libraries(nodal-digital-loop-planning-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.digital-loop-planning-unit COMMAND nodal-digital-loop-planning-unit-tests)
set_tests_properties(nodal.native.digital-loop-planning-unit PROPERTIES TIMEOUT 60)

add_executable(nodal-shape-iteration-image-unit-tests ShapeIterationImageTest.cpp)
llvm_update_compile_flags(nodal-shape-iteration-image-unit-tests)
target_link_libraries(nodal-shape-iteration-image-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.shape-iteration-image-unit COMMAND nodal-shape-iteration-image-unit-tests)
set_tests_properties(nodal.native.shape-iteration-image-unit PROPERTIES TIMEOUT 60)

add_executable(nodal-digital-assignment-guard-unit-tests DigitalAssignmentGuardTest.cpp)
llvm_update_compile_flags(nodal-digital-assignment-guard-unit-tests)
target_link_libraries(nodal-digital-assignment-guard-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.digital-assignment-guard-unit COMMAND nodal-digital-assignment-guard-unit-tests)
set_tests_properties(nodal.native.digital-assignment-guard-unit PROPERTIES TIMEOUT 60)

add_executable(nodal-structural-if-model-unit-tests StructuralIfModelTest.cpp)
llvm_update_compile_flags(nodal-structural-if-model-unit-tests)
target_link_libraries(nodal-structural-if-model-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.structural-if-model-unit COMMAND nodal-structural-if-model-unit-tests)
set_tests_properties(nodal.native.structural-if-model-unit PROPERTIES TIMEOUT 60)

add_executable(nodal-sequential-model-unit-tests SequentialModelTest.cpp)
llvm_update_compile_flags(nodal-sequential-model-unit-tests)
target_link_libraries(nodal-sequential-model-unit-tests PRIVATE
  NodalTransforms NodalDialect MLIRIR MLIRParser MLIRPass MLIRSupport LLVMSupport)
add_test(NAME nodal.native.sequential-model-unit COMMAND nodal-sequential-model-unit-tests)
set_tests_properties(nodal.native.sequential-model-unit PROPERTIES TIMEOUT 60)

add_executable(nodal-flat-fsm-model-unit-tests FlatFSMModelTest.cpp)
llvm_update_compile_flags(nodal-flat-fsm-model-unit-tests)
target_link_libraries(nodal-flat-fsm-model-unit-tests PRIVATE
  NodalTransforms NodalDialect MLIRIR MLIRParser MLIRPass MLIRSupport LLVMSupport)
add_test(NAME nodal.native.flat-fsm-model-unit COMMAND nodal-flat-fsm-model-unit-tests)
set_tests_properties(nodal.native.flat-fsm-model-unit PROPERTIES TIMEOUT 60)

add_executable(nodal-sequential-circt-unit-tests SequentialCIRCTTest.cpp)
llvm_update_compile_flags(nodal-sequential-circt-unit-tests)
target_link_libraries(nodal-sequential-circt-unit-tests PRIVATE
  NodalTransforms NodalDialect CIRCTHW CIRCTComb CIRCTSeq CIRCTVerif CIRCTSupport
  MLIRIR MLIRParser MLIRPass MLIRSupport LLVMSupport)
add_test(NAME nodal.native.sequential-circt-unit COMMAND nodal-sequential-circt-unit-tests)
set_tests_properties(nodal.native.sequential-circt-unit PROPERTIES TIMEOUT 60)

add_executable(nodal-memory-model-unit-tests MemoryModelTest.cpp)
llvm_update_compile_flags(nodal-memory-model-unit-tests)
target_link_libraries(nodal-memory-model-unit-tests PRIVATE
  NodalTransforms NodalDialect MLIRIR MLIRParser MLIRPass MLIRSupport LLVMSupport)
add_test(NAME nodal.native.memory-model-unit COMMAND nodal-memory-model-unit-tests)
set_tests_properties(nodal.native.memory-model-unit PROPERTIES TIMEOUT 60)

add_executable(nodal-domain-model-unit-tests DomainModelTest.cpp)
llvm_update_compile_flags(nodal-domain-model-unit-tests)
target_link_libraries(nodal-domain-model-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.domain-model-unit COMMAND nodal-domain-model-unit-tests)
set_tests_properties(nodal.native.domain-model-unit PROPERTIES TIMEOUT 30)

add_executable(nodal-crossing-model-unit-tests CrossingModelTest.cpp)
llvm_update_compile_flags(nodal-crossing-model-unit-tests)
target_link_libraries(nodal-crossing-model-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.crossing-model-unit COMMAND nodal-crossing-model-unit-tests)
set_tests_properties(nodal.native.crossing-model-unit PROPERTIES TIMEOUT 60)

add_executable(nodal-clock-model-unit-tests ClockModelTest.cpp)
llvm_update_compile_flags(nodal-clock-model-unit-tests)
target_link_libraries(nodal-clock-model-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.clock-model-unit COMMAND nodal-clock-model-unit-tests)
set_tests_properties(nodal.native.clock-model-unit PROPERTIES TIMEOUT 60)

add_executable(nodal-reset-model-unit-tests ResetModelTest.cpp)
llvm_update_compile_flags(nodal-reset-model-unit-tests)
target_link_libraries(nodal-reset-model-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.reset-model-unit COMMAND nodal-reset-model-unit-tests)
set_tests_properties(nodal.native.reset-model-unit PROPERTIES TIMEOUT 30)

add_executable(nodal-clock-circt-unit-tests ClockCIRCTTest.cpp)
llvm_update_compile_flags(nodal-clock-circt-unit-tests)
target_link_libraries(nodal-clock-circt-unit-tests PRIVATE
  NodalTransforms NodalDialect CIRCTHW CIRCTComb CIRCTSeq CIRCTSV CIRCTVerif CIRCTSupport
  MLIRIR MLIRParser MLIRPass MLIRSupport LLVMSupport)
add_test(NAME nodal.native.clock-circt-unit COMMAND nodal-clock-circt-unit-tests)
set_tests_properties(nodal.native.clock-circt-unit PROPERTIES TIMEOUT 60)

add_executable(nodal-clock-relationship-model-unit-tests ClockRelationshipModelTest.cpp)
llvm_update_compile_flags(nodal-clock-relationship-model-unit-tests)
target_link_libraries(nodal-clock-relationship-model-unit-tests PRIVATE
  NodalDialect MLIRIR MLIRParser MLIRSupport LLVMSupport)
add_test(NAME nodal.native.clock-relationship-model-unit COMMAND nodal-clock-relationship-model-unit-tests)
set_tests_properties(nodal.native.clock-relationship-model-unit PROPERTIES TIMEOUT 60)
