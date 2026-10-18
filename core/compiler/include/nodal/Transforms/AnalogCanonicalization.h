#ifndef NODAL_TRANSFORMS_ANALOGCANONICALIZATION_H
#define NODAL_TRANSFORMS_ANALOGCANONICALIZATION_H

#include "mlir/IR/BuiltinOps.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Support/LogicalResult.h"

#include <memory>

namespace nodal {

/// Validate the structural F32 bridge inventory without interpreting rendered
/// expressions or asserting their types. Authored residuals remain unchanged.
mlir::LogicalResult verifyAnalogSemanticInventory(mlir::ModuleOp module);

/// Clone, independently validate the original model, apply the finite F45
/// scalar rules and existing access/dead-local normalizers, then commit.
/// A failure leaves the original module unchanged. This is not a DAE optimizer.
mlir::LogicalResult canonicalizeAnalog(mlir::ModuleOp module);

std::unique_ptr<mlir::Pass> createCanonicalizeAnalogPass();
void registerAnalogCanonicalizationPass();

} // namespace nodal

#endif // NODAL_TRANSFORMS_ANALOGCANONICALIZATION_H
