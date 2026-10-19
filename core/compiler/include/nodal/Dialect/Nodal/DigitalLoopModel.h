#ifndef NODAL_DIALECT_NODAL_DIGITALLOOPMODEL_H
#define NODAL_DIALECT_NODAL_DIGITALLOOPMODEL_H

#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/SmallVector.h"

namespace nodal {

/// The versioned finite same-cycle profile is deliberately distinct from legacy
/// hardware_loop capability carriers. Header queries never walk loop bodies.
bool isBoundedDigitalLoop(mlir::Operation *operation);
struct DigitalLoopHeader {
  mlir::Operation *operation = nullptr;
  mlir::Operation *module = nullptr;
  mlir::Block *body = nullptr;
  mlir::BlockArgument induction;
};
mlir::FailureOr<DigitalLoopHeader> inspectDigitalLoopHeader(mlir::Operation *operation);
mlir::FailureOr<mlir::Value> digitalLoopCaptureSource(mlir::BlockArgument argument);
mlir::LogicalResult verifyDigitalLoopOperation(mlir::Operation *operation);
/// Exact lexical static/type context. Only real Integer captures are bound; the
/// induction remains absent and cannot acquire a positive width-formal domain.
mlir::FailureOr<ParameterIntegerEnvironment> digitalLoopIntegerContextAtUse(mlir::Operation *use);
mlir::LogicalResult verifyDigitalIterationIndexOperation(mlir::Operation *operation);

/// An actual affine induction-plus-static-offset DAG. The offset is checked by
/// ParameterModel, never encoded in a trusted selector attribute. Empty domains
/// retain no attained bounds; the complete DAG is still verified.
struct DigitalIterationIndex {
  mlir::Operation *loop = nullptr;
  GeneratedDomain domain;
  InstantiatedIntegerExpression offset;
  std::optional<ParameterIntegerBounds> attainedBounds;
};
mlir::FailureOr<DigitalIterationIndex> resolveDigitalIterationIndex(mlir::Value value,
                                                                    mlir::Operation *use);
/// Resolve real static literals, explicit static captures and local constant DAGs.
/// Actual-use ownership is checked before memoization. One query allows 65,536
/// edge visits and 128 active recursive frames; this is not a full DAG path-height
/// claim. The induction is never admitted as a width, shape or static formal.
mlir::FailureOr<InstantiatedIntegerExpression>
resolveDigitalLoopStaticInteger(mlir::Value value, mlir::Operation *use);

} // namespace nodal
#endif
