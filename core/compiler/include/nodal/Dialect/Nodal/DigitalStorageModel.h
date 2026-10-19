#ifndef NODAL_DIALECT_NODAL_DIGITALSTORAGEMODEL_H
#define NODAL_DIALECT_NODAL_DIGITALSTORAGEMODEL_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

namespace nodal {

/// Verify module-local storage declaration names once, independently of the
/// number of SSA uses. MLIR symbols cannot return values, so structural storage
/// carries actual SSA identity and an explicit name instead of a symbol trait.
mlir::LogicalResult verifyDigitalStorageModel(mlir::Operation *module);

/// Verify an owned structural wire/register declaration. The actual SSA result
/// retains the recursive value type; register initialization and domain are
/// explicit operands/identity. Update/reset timing remains with the state model.
mlir::LogicalResult verifyDigitalStorageOperation(mlir::Operation *operation);

/// Verify an explicit addressable-memory declaration, retaining its exact
/// element, depth, domain and access policy without inventing a value or access
/// operation. Reuses the shared digital-type and structural-parameter proofs.
mlir::LogicalResult verifyDigitalMemoryOperation(mlir::Operation *operation);

/// Exact typed assignment intent. Writable wire/output and register targets
/// remain distinct, including shared structural projection roots. This does not
/// execute clocked updates or define process priority/reset timing.
mlir::LogicalResult verifyDigitalAssignmentOperation(mlir::Operation *operation);

} // namespace nodal

#endif
