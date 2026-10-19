#ifndef NODAL_DIALECT_NODAL_DIGITALSTORAGEMODEL_H
#define NODAL_DIALECT_NODAL_DIGITALSTORAGEMODEL_H

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/SmallVector.h"

namespace nodal {

/// Verify module-local storage names, ordinary assignment drivers and shared
/// combinational dependencies, including actual typed Interface/net roots,
/// independently of the number of SSA uses. MLIR symbols cannot return values, so structural
/// storage carries actual SSA identity and an explicit name instead of a symbol trait.
mlir::LogicalResult verifyDigitalStorageModel(mlir::Operation *module);

/// Shared graph for ordinary assignments and typed Interface/net values/effects.
/// ShapeModel owns physical overlap proofs; InterfaceModel owns connectivity.
/// Limits bound the actual authored SSA graph and relation queries, not lanes.
/// Registers terminate reads; register_next is not combinational execution.
/// Loop order remains distinct from generated occurrence identity. Structural
/// alternatives qualify graph nodes and coverage; their finite context/work
/// budgets are checked before expansion and never enumerate parameter settings.
struct DigitalAssignmentProofLimits {
  unsigned maximumNodes = 65536;
  unsigned maximumEdges = 262144;
  unsigned maximumRelations = 16384;
  unsigned maximumShapeWork = 4096;
  unsigned maximumGuardContexts = 4096;
  unsigned maximumGuardDepth = 128;
  unsigned maximumGuardWork = 262144;
};
mlir::LogicalResult verifyDigitalAssignmentModel(mlir::Operation *module,
                                                 DigitalAssignmentProofLimits limits = {});

/// Read-only view of the same verified physical dependency graph. State values
/// terminate reads; wire/output projections link actual overlapping writers.
/// No transitive leaf sets or second mutable driver registry are introduced.
struct DigitalDependencyNode {
  mlir::Value value;
  mlir::Operation *use;
  llvm::SmallVector<unsigned, 2> dependencies;
  /// Verified Interface instance/component source-domain constraint, when any.
  mlir::FlatSymbolRefAttr sourceDomain;
};
struct DigitalDependencyGraph {
  llvm::SmallVector<DigitalDependencyNode> nodes;
  llvm::SmallVector<unsigned> roots;
};
mlir::FailureOr<DigitalDependencyGraph> analyzeDigitalAssignmentDependencies(
    mlir::Operation *module, llvm::ArrayRef<std::pair<mlir::Value, mlir::Operation *>> seeds,
    DigitalAssignmentProofLimits limits = {});

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
