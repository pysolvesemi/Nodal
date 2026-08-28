#ifndef NODAL_DIALECT_NODAL_CONSERVATIVECONNECTIVITY_H
#define NODAL_DIALECT_NODAL_CONSERVATIVECONNECTIVITY_H

#include "mlir/IR/BuiltinOps.h"
#include "mlir/Support/LogicalResult.h"

namespace nodal {

/// Normalize Increment 28 conservative connectivity in every opted-in
/// nodal.module. The operation is deterministic and idempotent: previously
/// generated topology records are removed and rebuilt from source-semantic
/// terminals, nodes, connections, aliases, references, and branches.
mlir::LogicalResult materializeConservativeConnectivity(mlir::ModuleOp module);

/// Returns true when a nodal.module declares an extensible partial physical
/// component contract. Partial components retain local topology evidence but
/// do not claim a complete local equation boundary.
bool isPartialPhysicalComponent(mlir::Operation *module);

} // namespace nodal

#endif // NODAL_DIALECT_NODAL_CONSERVATIVECONNECTIVITY_H
