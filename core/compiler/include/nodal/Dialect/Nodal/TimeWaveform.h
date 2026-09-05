#ifndef NODAL_DIALECT_NODAL_TIMEWAVEFORM_H
#define NODAL_DIALECT_NODAL_TIMEWAVEFORM_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

namespace nodal {
bool isTimeWaveformOperation(mlir::Operation *operation);
bool isStatefulWaveformOperation(mlir::Operation *operation);
mlir::LogicalResult verifyTimeWaveformOperation(mlir::Operation *operation);
} // namespace nodal

#endif
