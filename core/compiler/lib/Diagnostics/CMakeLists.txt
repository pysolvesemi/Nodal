add_mlir_library(NodalDiagnostics
  DiagnosticMapping.cpp

  ADDITIONAL_HEADER_DIRS
    "${NODAL_REPOSITORY_ROOT}/core/compiler/include/nodal/Diagnostics"

  LINK_LIBS PUBLIC
    NodalDialect
    NodalSupport
    MLIRIR
    MLIRPass
    MLIRSupport
)
