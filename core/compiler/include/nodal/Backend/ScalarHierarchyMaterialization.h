#ifndef NODAL_BACKEND_SCALARHIERARCHYMATERIALIZATION_H
#define NODAL_BACKEND_SCALARHIERARCHYMATERIALIZATION_H

#include "mlir/IR/BuiltinOps.h"
#include "mlir/Support/LogicalResult.h"

namespace nodal {

/// Materialize one already verified, finite scalar electrical root graph.
/// Preflight rejects unsupported semantics and expansion budgets before cloning
/// occurrences. The caller owns the transaction and must independently verify
/// the resulting IR before rendering or publishing it.
mlir::LogicalResult materializeScalarElectricalHierarchy(mlir::ModuleOp module);

} // namespace nodal

#endif
