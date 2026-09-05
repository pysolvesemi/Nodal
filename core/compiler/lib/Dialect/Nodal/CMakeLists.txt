add_mlir_dialect_library(NodalDialect
  NodalDialect.cpp
  AnalogNumeric.cpp
  TimeWaveform.cpp
  ConservativeConnectivity.cpp
  NatureDiscipline.cpp
  ParameterModel.cpp
  PotentialFlowAccess.cpp
  NodalOps.cpp
  NodalTypes.cpp

  ADDITIONAL_HEADER_DIRS
    "${NODAL_REPOSITORY_ROOT}/core/compiler/include/nodal/Dialect/Nodal"

  DEPENDS
    MLIRNodalOpsIncGen

  LINK_LIBS PUBLIC
    NodalSupport
    MLIRBytecodeOpInterface
    MLIRIR
    MLIRPass
    MLIRSupport
)
