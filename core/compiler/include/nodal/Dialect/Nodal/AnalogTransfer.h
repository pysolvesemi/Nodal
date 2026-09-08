#ifndef NODAL_DIALECT_NODAL_ANALOGTRANSFER_H
#define NODAL_DIALECT_NODAL_ANALOGTRANSFER_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

namespace nodal {
mlir::LogicalResult verifyAnalogTransferOperation(mlir::Operation *operation);
} // namespace nodal

#endif
