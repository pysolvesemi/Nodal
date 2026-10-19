#ifndef NODAL_DIALECT_NODAL_DIGITALINTERFACEMODEL_H
#define NODAL_DIALECT_NODAL_DIGITALINTERFACEMODEL_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

namespace nodal {
/// A version marker selects the fully typed contract. Legacy F19 declarations
/// remain readable, but cannot supply capabilities to typed consumers.
bool hasDigitalInterfaceContract(mlir::Operation *operation);

/// Local version/ownership checks. Module verification owns cross-operation
/// resolution, so independent operation and pass verification share one model.
mlir::LogicalResult verifyDigitalInterfaceOperation(mlir::Operation *operation);

/// Verify actual definitions, complete role maps, member SSA, structural ABI and
/// resolved driver identity in one owning module. Types, widths and dimensions
/// use DigitalTypeModel/ShapeModel, without expanding physical bits or lanes.
/// Read-only, bounded metadata traversal; pass-through uses union-find and each
/// represented driver is counted once for all split views.
mlir::LogicalResult verifyDigitalInterfaceModel(mlir::Operation *module);
} // namespace nodal

#endif
