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

/// True only for a full-rank scalar selection of a directly owned node array.
bool isNodeArraySelection(mlir::Operation *operation);

/// Independently validate actual SSA index identities and possible aliases.
mlir::LogicalResult verifyNodeArraySelections(mlir::Operation *module);

/// Merge equivalent selected endpoints and their authored source-use provenance
/// before connectivity or access grouping. Never creates scalar node declarations.
mlir::LogicalResult normalizeNodeArraySelections(mlir::ModuleOp module);

} // namespace nodal

#endif // NODAL_DIALECT_NODAL_CONSERVATIVECONNECTIVITY_H
