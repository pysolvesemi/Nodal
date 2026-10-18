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

/// F45's finite dead-declaration witness. Original operations, names, types,
/// locations, metadata and source inventory remain in the IR for review.
struct AnalogDeadLocalEvidence {
  mlir::Operation *local;
  mlir::Operation *initializer;
};

/// Verify this operation's marker, if present; also used by the dialect
/// attribute verifier for markers attached to operations from another dialect.
mlir::LogicalResult verifyAnalogDeadLocalMarker(mlir::Operation *operation);

/// Independently check every dead-local marker under root, including markers on
/// unrelated operations. Only unused immutable locals initialized directly by a
/// verified finite, identically typed Real/Integer literal qualify.
mlir::LogicalResult verifyAnalogDeadLocalMarkers(mlir::Operation *root);

/// Verify original function bodies before adding derived dead-local markers.
/// Does not erase/rewrite any operation or infer constants through expressions
/// or calls. Returns all qualifying original records in deterministic walk order,
/// including already-marked records; repeated normalization is idempotent.
mlir::FailureOr<llvm::SmallVector<AnalogDeadLocalEvidence>>
normalizeAnalogDeadLocals(mlir::Operation *root);

/// Query only: callers must verify original bodies and markers before elision.
bool isNormalizedAnalogDeadLocal(mlir::Operation *operation);
} // namespace nodal
#endif
