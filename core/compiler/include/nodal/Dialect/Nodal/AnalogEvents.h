#ifndef NODAL_DIALECT_NODAL_ANALOGEVENTS_H
#define NODAL_DIALECT_NODAL_ANALOGEVENTS_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

namespace nodal {
bool isAnalogEventExpression(mlir::Operation *operation);
mlir::LogicalResult verifyAnalogEventOperation(mlir::Operation *operation);
} // namespace nodal
#endif
