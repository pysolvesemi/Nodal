#ifndef NODAL_DIALECT_NODAL_ANALOGEQUATIONMODEL_H
#define NODAL_DIALECT_NODAL_ANALOGEQUATIONMODEL_H

#include "mlir/IR/BuiltinOps.h"
#include "mlir/Support/LogicalResult.h"

#include <string>
#include <utility>
#include <vector>

namespace nodal {

/// Finite scalar source-equation incidence, not numerical rank or a DAE solver.
struct AnalogStructuralMatch {
  mlir::Operation *origin = nullptr;
  std::string component;
  std::string analysis;
  std::vector<std::string> equations;
  std::vector<std::string> unknowns;
  std::vector<std::pair<std::string, std::string>> matched;
  std::vector<std::string> unmatchedEquations;
  std::vector<std::string> unmatchedUnknowns;
};
struct AnalogStructuralModel {
  bool present = false;
  bool complete = false;
  std::vector<std::string> unsupported;
  std::vector<AnalogStructuralMatch> components;
  std::vector<AnalogStructuralMatch> global;
};

/// Shared F32 authored-side schema and actual occurrence ownership validation.
mlir::LogicalResult verifyAnalogSemanticInventory(mlir::ModuleOp module);

mlir::LogicalResult verifyAnalogUnknownOperation(mlir::Operation *operation);
mlir::LogicalResult verifyAnalogEquationOperation(mlir::Operation *operation);
/// Independently validate typed identities, dimensions and authored inventory
/// coverage before deriving facts. Incomplete coverage never yields matches.
mlir::FailureOr<AnalogStructuralModel> analyzeAnalogEquationModel(mlir::ModuleOp module);
/// Deterministic derived report. Consumers must recompute rather than trust it.
mlir::DictionaryAttr serializeAnalogStructuralModel(mlir::MLIRContext *context,
                                                    const AnalogStructuralModel &model);

} // namespace nodal
#endif
