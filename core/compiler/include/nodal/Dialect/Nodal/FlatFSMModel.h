#ifndef NODAL_DIALECT_NODAL_FLATFSMMODEL_H
#define NODAL_DIALECT_NODAL_FLATFSMMODEL_H
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/SmallVector.h"
namespace nodal {
struct FlatFSMHeader {
  mlir::Operation *operation = nullptr, *module = nullptr;
  unsigned guards = 0;
  llvm::SmallVector<mlir::Block *, 4> bodies;
  llvm::SmallVector<unsigned, 4> captureOffsets, captureCounts;
};
mlir::FailureOr<FlatFSMHeader> inspectFlatFSMHeader(mlir::Operation *operation);
mlir::LogicalResult verifyFlatFSMOperation(mlir::Operation *operation);
mlir::FailureOr<mlir::Value> flatFSMCaptureSource(mlir::BlockArgument argument);
mlir::LogicalResult flatFSMArgumentDependencies(mlir::BlockArgument argument,
                                                llvm::SmallVectorImpl<mlir::Value> &dependencies);
mlir::LogicalResult flatFSMResultDependencies(mlir::Value result,
                                              llvm::SmallVectorImpl<mlir::Value> &dependencies);
mlir::FailureOr<llvm::SmallVector<ParameterIntegerEnvironment, 4>>
flatFSMIntegerContextsAtUse(mlir::Operation *use);
/// Mutates only the caller's detached candidate; SequentialModel commits.
mlir::LogicalResult expandFlatFSMModel(mlir::Operation *module, unsigned &generated,
                                       unsigned maximumGenerated);
} // namespace nodal
#endif
