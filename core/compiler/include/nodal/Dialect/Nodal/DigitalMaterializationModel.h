#ifndef NODAL_DIALECT_NODAL_DIGITALMATERIALIZATIONMODEL_H
#define NODAL_DIALECT_NODAL_DIGITALMATERIALIZATIONMODEL_H

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

namespace nodal {

/// Validate authored intent and source aliases, never infer intent from a string
/// operand or origin.inlined. Exact alias type/scope remains with DigitalValueModel.
mlir::LogicalResult verifyDigitalMaterializationIntent(mlir::Operation *operation);

/// Compute target-neutral requirements from the already-verified owning module's
/// real SSA use graph. Requires nodal.digital.quality on its enclosing builtin
/// module. Does not modify IR, create wires, fold, duplicate or reassociate.
mlir::FailureOr<mlir::DictionaryAttr> analyzeDigitalMaterialization(mlir::Operation *module);

/// Reject misplaced facts and any stale/forged attached module report. The
/// verifier is read-only and never silently overwrites an input report.
mlir::LogicalResult verifyDigitalMaterializationReport(mlir::Operation *module);

} // namespace nodal

#endif
