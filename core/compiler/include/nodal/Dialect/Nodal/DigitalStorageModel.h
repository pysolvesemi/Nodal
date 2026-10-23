#ifndef NODAL_DIALECT_NODAL_DIGITALSTORAGEMODEL_H
#define NODAL_DIALECT_NODAL_DIGITALSTORAGEMODEL_H

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"
#include "nodal/Dialect/Nodal/DigitalInterfaceModel.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/SmallVector.h"

#include <memory>

namespace nodal {
class HierarchyModel;

/// A query over the actual Generate/export SSA graph, never a second family registry.
struct GeneratedSelection {
  mlir::Operation *family = nullptr;
  mlir::Value ordinal;
  InstantiatedIntegerExpression coordinate;
};
struct GeneratedMember {
  mlir::Operation *family = nullptr;
  mlir::Operation *exportOperation = nullptr;
  mlir::Value storage;
  InstantiatedIntegerExpression coordinate;
  /// Ordered actual outer-to-inner selections, never sampled occurrence clones.
  llvm::SmallVector<GeneratedSelection, 2> selections;
  bool familyView = false;
  enum class Access { Read, Wire, Register } access = Access::Read;
};
mlir::LogicalResult verifyGeneratedFamilyOperation(mlir::Operation *operation);
mlir::LogicalResult verifyGeneratedExportOperation(mlir::Operation *operation);
mlir::FailureOr<GeneratedMember> inspectGeneratedMember(mlir::Operation *operation);

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
  /// Verified Interface source constraint, crossing result or waiver permission domain.
  mlir::FlatSymbolRefAttr sourceDomain;
  /// Exact coherent stateful boundary identity and inventory index. Sampled
  /// roots are provenance, NOT combinational dependencies or domainless cuts.
  mlir::Operation *crossing = nullptr;
  unsigned crossingIndex = 0;
  llvm::StringRef crossingRole;
  unsigned occurrence = 0;
  bool hierarchyBoundary = false;
  /// Stateless permission boundary only. Physical dependencies and lineage stay
  /// intact; this operation is never a physical source or synchronizer identity.
  mlir::Operation *waiver = nullptr;
  /// Actual event storage is never an ordinary domain or permission source.
  mlir::Operation *eventProcess = nullptr;
  /// Actual root event trigger classification survives aliases without becoming
  /// state or an attributed source domain. It cannot control an ordinary clock.
  bool eventTrigger = false;
  /// An exact unresolved local requirement whose reset controls this value or
  /// action. This is an obligation, never an absent Reset capability. Closed
  /// hierarchy composition resolves it independently for every occurrence.
  mlir::FlatSymbolRefAttr contextualReset;
};
struct DigitalCrossingSample {
  unsigned root;
  unsigned operandIndex;
  mlir::FlatSymbolRefAttr domain;
  llvm::StringRef role;
};
struct DigitalCrossingResult {
  mlir::FlatSymbolRefAttr domain;
  llvm::StringRef role;
};
struct DigitalCrossingDependency {
  mlir::Operation *operation;
  llvm::SmallVector<DigitalCrossingSample, 3> samples;
  llvm::SmallVector<DigitalCrossingResult, 3> results;
  unsigned occurrence = 0;
};
struct DigitalWaiverDependency {
  mlir::Operation *operation = nullptr;
  unsigned root = 0;
  unsigned occurrence = 0;
};
struct DigitalEventUpdateDependency {
  mlir::Operation *operation = nullptr;
  mlir::Operation *process = nullptr;
  unsigned root = 0;
};
struct DigitalObservationGroup {
  mlir::Operation *use = nullptr;
  llvm::SmallVector<unsigned, 2> roots;
};
struct DigitalDependencyGraph {
  llvm::SmallVector<DigitalDependencyNode> nodes;
  llvm::SmallVector<unsigned> roots;
  llvm::SmallVector<DigitalCrossingDependency> crossings;
  llvm::SmallVector<DigitalWaiverDependency> waivers;
  llvm::SmallVector<DigitalEventUpdateDependency> eventUpdates;
  /// Canonical coherent observations, never a module-wide group.
  llvm::SmallVector<DigitalObservationGroup> observations;
  std::shared_ptr<const HierarchyModel> hierarchy;
  /// Canonical local Interface facts consumed by the existing occurrence graph.
  DigitalResolvedTopology resolved;
};
mlir::FailureOr<DigitalDependencyGraph> analyzeDigitalAssignmentDependencies(
    mlir::Operation *module, llvm::ArrayRef<std::pair<mlir::Value, mlir::Operation *>> seeds,
    DigitalAssignmentProofLimits limits = {});

/// Immutable successful split facts from the existing occurrence/component proof.
/// Values stay in their original definition and occurrence; they are not parent SSA.
/// A null data value is permitted only with the verified low/high level attribute.
struct DigitalResolvedSplitBinding {
  unsigned splitOccurrence = 0, driverOccurrence = 0, componentOccurrence = 0;
  mlir::Operation *split = nullptr;
  mlir::Operation *driver = nullptr;
  mlir::Value component, data, enable;
  mlir::StringAttr level;
};
mlir::LogicalResult verifyHierarchyDependencies(
    std::shared_ptr<const HierarchyModel> hierarchy, DigitalAssignmentProofLimits limits = {},
    llvm::SmallVectorImpl<DigitalResolvedSplitBinding> *splitBindings = nullptr);

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
