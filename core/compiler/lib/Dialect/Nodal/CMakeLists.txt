add_mlir_dialect_library(NodalDialect
  NodalDialect.cpp
  NodalOps.cpp

  ADDITIONAL_HEADER_DIRS
    "${NODAL_REPOSITORY_ROOT}/core/compiler/include/nodal/Dialect/Nodal"

  DEPENDS
    MLIRNodalOpsIncGen

  LINK_LIBS PUBLIC
    MLIRIR
    MLIRSupport
)
