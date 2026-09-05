add_mlir_library(NodalBackend
  AnalogEventBackend.cpp
  AnalogEventReparse.cpp
  AnalogVerticalSlice.cpp
  Backend.cpp

  ADDITIONAL_HEADER_DIRS
    "${NODAL_REPOSITORY_ROOT}/core/compiler/include/nodal/Backend"

  LINK_LIBS PUBLIC
    NodalDiagnostics
    NodalDialect
    NodalTransforms
    CIRCTHW
    MLIRIR
    MLIRPass
    MLIRSupport
    MLIRTranslateLib
)
