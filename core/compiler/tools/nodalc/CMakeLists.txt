set(LLVM_LINK_COMPONENTS
  Support
)

add_llvm_executable(nodalc
  nodalc.cpp
)

llvm_update_compile_flags(nodalc)

target_link_libraries(nodalc
  PRIVATE
    NodalDialect
    NodalSupport
    NodalTransforms
    CIRCTHW
    CIRCTSupport
    MLIRIR
    MLIROptLib
    MLIRParser
    MLIRPass
    MLIRSupport
    MLIRTransforms
)
