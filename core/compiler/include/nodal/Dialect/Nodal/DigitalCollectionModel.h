#ifndef NODAL_DIALECT_NODAL_DIGITALCOLLECTIONMODEL_H
#define NODAL_DIALECT_NODAL_DIGITALCOLLECTIONMODEL_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

#include <string>

namespace nodal {

/// Verify an SSA value at its actual lexical use. Collection bodies are isolated:
/// outer values enter only through explicit operands and matching block arguments.
/// Argument signature checks do not recurse into opcode or body verification.
/// Module values retain their existing owners; collection-local arguments and
/// intermediates cannot escape or bypass an explicit nested capture.
mlir::LogicalResult verifyCollectionValueScope(mlir::Value value, mlir::Operation *use);

/// Resolve only an actual capture argument through the verified operand segments.
/// Success with a null value denotes an element/accumulator argument. Malformed
/// headers or non-dominating/foreign capture sources fail; no supplied facts are used.
mlir::FailureOr<mlir::Value> digitalCollectionCaptureSource(mlir::BlockArgument argument);

/// Canonical source identity for an actual collection argument, or a producer's
/// semantic_path. Argument identity is derived from region_id and argument ordinal.
std::string digitalValueSemanticPath(mlir::Value value);

/// Closed map/zip_with and homogeneous fold_left/scan_left region contracts.
/// Reuses ordinary digital/enum/shape/type verification without lane expansion.
mlir::LogicalResult verifyDigitalCollectionOperation(mlir::Operation *operation);
mlir::LogicalResult verifyDigitalCollectionYield(mlir::Operation *operation);
mlir::LogicalResult verifyDigitalCollectionModel(mlir::Operation *module);

} // namespace nodal
#endif
