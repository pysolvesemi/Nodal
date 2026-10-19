#ifndef NODAL_DIALECT_NODAL_DIGITALLOOPPLANNINGMODEL_H
#define NODAL_DIALECT_NODAL_DIGITALLOOPPLANNINGMODEL_H
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/OwningOpRef.h"
#include "mlir/Support/LogicalResult.h"
namespace nodal {
/// Read-only ordered target-neutral plans, recomputed from actual verified SSA.
/// The versioned policy bounds expansion, not the language's finite envelope.
mlir::FailureOr<mlir::DictionaryAttr> analyzeDigitalLoopPlans(mlir::Operation *module);
mlir::LogicalResult verifyDigitalLoopPlanningReport(mlir::Operation *module);
/// Materialize a checked small invariant candidate into a private whole-design
/// clone. The input is unchanged; all normal type/shape/assignment verifiers run.
mlir::FailureOr<mlir::OwningOpRef<mlir::ModuleOp>>
materializeDigitalLoopUnrolled(mlir::Operation *loop);
} // namespace nodal
#endif
