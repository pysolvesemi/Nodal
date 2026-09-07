#ifndef NODAL_DIALECT_NODAL_ANALOGNOISE_H
#define NODAL_DIALECT_NODAL_ANALOGNOISE_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/StringRef.h"

#include <string>

namespace nodal {
mlir::FailureOr<std::string> analogNoiseResultDimension(llvm::StringRef spectralDensity);
mlir::LogicalResult verifyAnalogNoiseOperation(mlir::Operation *operation);
} // namespace nodal
#endif
