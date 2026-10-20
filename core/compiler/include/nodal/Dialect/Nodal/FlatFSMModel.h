#ifndef NODAL_DIALECT_NODAL_FLATFSMMODEL_H
#define NODAL_DIALECT_NODAL_FLATFSMMODEL_H
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/SmallVector.h"
namespace nodal {
struct FlatFSMHeader {
  mlir::Operation *operation = nullptr, *module = nullptr;
  unsigned guards = 0, guardOffset = 1;
  bool controlled = false, composed = false;
  mlir::Value currentLocal() const;
  mlir::Value currentStatus() const;
  mlir::Value restart() const;
  mlir::Value cancel() const;
  mlir::Value enable() const;
  mlir::Value next() const;
  mlir::Value taken() const;
  mlir::Value entered() const;
  mlir::Value exited() const;
  mlir::Value establishment() const;
  unsigned privateStateCount() const { return controlled ? 2 : 1; }
  llvm::SmallVector<mlir::Block *, 4> bodies;
  llvm::SmallVector<unsigned, 4> captureOffsets, captureCounts;
};
enum class FlatFSMLifecycle { Dormant = 0, Running = 1, Completed = 2, Cancelled = 3 };
/// One fully admitted local graph, reused by query-local composition topology indexes.
struct FlatFSMGraphSummary {
  FlatFSMHeader header;
  llvm::SmallVector<mlir::StringAttr> caseIds;
  llvm::SmallVector<llvm::APInt> localCodes;
  llvm::SmallVector<bool> terminal;
  unsigned initial = 0, width = 0;
};
mlir::FailureOr<FlatFSMGraphSummary> inspectFlatFSMGraphSummary(mlir::Operation *operation);
mlir::FailureOr<FlatFSMHeader> inspectFlatFSMHeader(mlir::Operation *operation);
mlir::LogicalResult verifyFlatFSMOperation(mlir::Operation *operation);
/// Canonical distinct result roles of an independently admitted v1/v2/v3 header.
llvm::StringRef flatFSMResultRole(mlir::Value result);
mlir::FailureOr<mlir::Value> flatFSMCaptureSource(mlir::BlockArgument argument);
mlir::LogicalResult flatFSMArgumentDependencies(mlir::BlockArgument argument,
                                                llvm::SmallVectorImpl<mlir::Value> &dependencies);
mlir::LogicalResult flatFSMResultDependencies(mlir::Value result,
                                              llvm::SmallVectorImpl<mlir::Value> &dependencies);
mlir::FailureOr<llvm::SmallVector<ParameterIntegerEnvironment, 4>>
flatFSMIntegerContextsAtUse(mlir::Operation *use);
/// Actual implicit phase controls for each action body, using the same phase
/// owner as expansion. Charges retained dependency roots before growing output.
mlir::LogicalResult flatFSMActionControlDependencies(
    mlir::Operation *operation,
    llvm::SmallVectorImpl<llvm::SmallVector<mlir::Value, 4>> &dependencies, unsigned &work,
    unsigned maximumWork);
/// Mutates only the caller's detached candidate; SequentialModel commits.
mlir::LogicalResult expandFlatFSMModel(mlir::Operation *module, unsigned &generated,
                                       unsigned maximumGenerated);
} // namespace nodal
#endif
