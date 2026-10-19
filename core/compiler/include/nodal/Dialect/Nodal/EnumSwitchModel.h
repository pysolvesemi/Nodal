#ifndef NODAL_DIALECT_NODAL_ENUMSWITCHMODEL_H
#define NODAL_DIALECT_NODAL_ENUMSWITCHMODEL_H
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/SmallVector.h"
namespace nodal {
struct EnumSwitchHeader {
  mlir::Operation *operation = nullptr, *module = nullptr;
  llvm::SmallVector<mlir::Block *, 4> bodies;
  llvm::SmallVector<unsigned, 4> captureOffsets, captureCounts;
};
mlir::FailureOr<EnumSwitchHeader> inspectEnumSwitchHeader(mlir::Operation *operation);
mlir::LogicalResult verifyEnumSwitchOperation(mlir::Operation *operation);
mlir::FailureOr<mlir::Value> enumSwitchCaptureSource(mlir::BlockArgument argument);
mlir::FailureOr<llvm::SmallVector<ParameterIntegerEnvironment, 4>>
enumSwitchIntegerContextsAtUse(mlir::Operation *use);
mlir::LogicalResult expandEnumSwitchModel(mlir::Operation *module, unsigned &generated,
                                          unsigned maximumGenerated);
} // namespace nodal
#endif
