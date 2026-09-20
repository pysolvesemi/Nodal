#ifndef NODAL_DIALECT_NODAL_ANALOGUSERFUNCTIONS_H
#define NODAL_DIALECT_NODAL_ANALOGUSERFUNCTIONS_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/SmallVector.h"

namespace nodal {
mlir::LogicalResult verifyAnalogUserFunctionOperation(mlir::Operation *operation);
/// Resolve only in the directly enclosing Nodal module. Does not search ancestors.
mlir::FailureOr<mlir::Operation *> resolveAnalogUserCall(mlir::Operation *call);
/// Stable dependency-first order; rejects unknown callees and all recursion.
mlir::FailureOr<llvm::SmallVector<mlir::Operation *>>
orderedAnalogUserFunctions(mlir::Operation *module);
} // namespace nodal
#endif
