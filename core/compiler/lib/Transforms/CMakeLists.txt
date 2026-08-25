add_mlir_library(NodalTransforms
  Passes.cpp

  ADDITIONAL_HEADER_DIRS
    "${NODAL_REPOSITORY_ROOT}/core/compiler/include/nodal/Transforms"

  LINK_LIBS PUBLIC
    NodalDiagnostics
    NodalDialect
    MLIRIR
    MLIRParser
    MLIRPass
    MLIRSupport
    MLIRTransforms
)
