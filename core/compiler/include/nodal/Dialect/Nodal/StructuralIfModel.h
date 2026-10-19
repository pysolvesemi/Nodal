#ifndef NODAL_DIALECT_NODAL_STRUCTURALIFMODEL_H
#define NODAL_DIALECT_NODAL_STRUCTURALIFMODEL_H
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/SmallVector.h"
namespace nodal {
struct StructuralIfHeader {
  mlir::Operation *operation = nullptr;
  mlir::Operation *module = nullptr;
  mlir::Block *bodies[2] = {nullptr, nullptr};
  unsigned captureOffsets[2] = {0, 0};
  unsigned captureCounts[2] = {0, 0};
};
mlir::FailureOr<StructuralIfHeader> inspectStructuralIfHeader(mlir::Operation *operation);
mlir::FailureOr<mlir::Value> structuralIfCaptureSource(mlir::BlockArgument argument);
mlir::FailureOr<llvm::SmallVector<ParameterIntegerEnvironment, 4>>
structuralIfIntegerContextsAtUse(mlir::Operation *use);
mlir::LogicalResult
verifyStructuralIfOperation(mlir::Operation *operation,
                            const ParameterIntegerEnvironment *enclosing = nullptr);
mlir::LogicalResult verifyStructuralYieldOperation(mlir::Operation *operation);
mlir::LogicalResult verifyStructuralIfModel(mlir::Operation *module);
mlir::LogicalResult verifyDigitalGenerateBody(mlir::Operation *operation);
} // namespace nodal
#endif
