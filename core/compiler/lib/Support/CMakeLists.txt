add_library(NodalSupport STATIC
  Version.cpp
)

add_library(Nodal::Support ALIAS NodalSupport)

llvm_update_compile_flags(NodalSupport)

target_include_directories(NodalSupport
  PUBLIC
    "${NODAL_REPOSITORY_ROOT}/core/compiler/include"
    "${CMAKE_BINARY_DIR}/include"
)

target_link_libraries(NodalSupport
  PUBLIC
    MLIRSupport
    LLVMSupport
)
