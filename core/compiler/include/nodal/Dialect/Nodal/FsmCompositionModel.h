#ifndef NODAL_DIALECT_NODAL_FSMCOMPOSITIONMODEL_H
#define NODAL_DIALECT_NODAL_FSMCOMPOSITIONMODEL_H
#include "mlir/IR/Value.h"

#include "llvm/ADT/SmallVector.h"
namespace nodal {
/// Closed local forms only. Whole-module ownership is verified independently.
mlir::LogicalResult verifyFsmCompositionOperation(mlir::Operation *operation);
/// Canonical result roles after independent closed local admission.
llvm::StringRef fsmCompositionResultRole(mlir::Value result);
/// Identity-only operands never create combinational dependency edges.
mlir::LogicalResult
fsmCompositionResultDependencies(mlir::Value result,
                                 llvm::SmallVectorImpl<mlir::Value> &dependencies);
/// One bounded query-local index over actual FSM storage and control operands.
mlir::LogicalResult verifyFsmCompositionModel(mlir::Operation *module, unsigned maximumWork = 65536,
                                              unsigned maximumDepth = 128);
/// Mutates only the existing caller-owned detached sequential candidate.
mlir::LogicalResult expandFsmCompositionModel(mlir::Operation *module, unsigned &generated,
                                              unsigned maximumGenerated);
} // namespace nodal
#endif
