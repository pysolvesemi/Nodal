add_mlir_dialect_library(NodalDialect
  NodalDialect.cpp
  AnalogNumeric.cpp
  AnalogFunctions.cpp
  AnalogUserFunctions.cpp
  AnalogNoise.cpp
  AnalogTransfer.cpp
  AnalogEvents.cpp
  AnalogEquationModel.cpp
  AnalogSemanticInventory.cpp
  AnalogStorageModel.cpp
  ShapeModel.cpp
  DigitalInterfaceModel.cpp
  DigitalTypeModel.cpp
  DigitalStorageModel.cpp
  DigitalValueModel.cpp
  EnumValueModel.cpp
  TargetCapabilities.cpp
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
