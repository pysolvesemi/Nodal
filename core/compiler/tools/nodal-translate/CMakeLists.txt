set(LLVM_LINK_COMPONENTS
  Support
)

add_llvm_executable(nodal-translate
  nodal-translate.cpp
)

llvm_update_compile_flags(nodal-translate)

target_link_libraries(nodal-translate
  PRIVATE
    NodalBackend
    NodalDialect
    NodalSupport
    CIRCTHW
    CIRCTSupport
    MLIRIR
    MLIRParser
    MLIRSupport
    MLIRTranslateLib
)
