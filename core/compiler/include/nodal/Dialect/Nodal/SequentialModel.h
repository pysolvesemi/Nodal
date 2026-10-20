#ifndef NODAL_DIALECT_NODAL_SEQUENTIALMODEL_H
#define NODAL_DIALECT_NODAL_SEQUENTIALMODEL_H
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/SmallVector.h"
namespace nodal {
/// Finite actual-IR budgets, independent of parameter values or physical lanes.
struct SequentialModelLimits {
  unsigned maximumOperations = 65536;
  unsigned maximumDepth = 128;
  unsigned maximumCaptures = 8192;
  unsigned maximumNormalizedOperations = 262144;
};
/// Shared narrow physical sampling proof. Memory and registers use the same
/// dependency graph and DomainModel requirement/binding proof owner.
struct SequentialSample {
  mlir::Value value;
  mlir::Attribute domain;
  mlir::Operation *use;
  /// Crossings require an actual attributed source and reject every unannotated
  /// boundary. Constants may accompany a source but cannot establish one.
  bool requireAttributedSource = false;
  /// Values and controlling guards consumed by one actual target update share
  /// this owner identity. Independent register/memory effects remain separate.
  mlir::Operation *group = nullptr;
  /// A non-pulse crossing cannot erase an actual pulse-producing boundary.
  /// Ordinary same-domain consumers may still sample the pulse as a Bool.
  bool rejectPulseSources = false;
  /// Strict sampling may accept a neutral constant without weakening rejection
  /// of unannotated/foreign physical boundaries (e.g. FIFO ready or valid).
  bool allowDomainlessSource = false;
  /// At least one strict sample in the same (group, domain) must establish an
  /// actual source. FIFO payload and valid establish their source jointly.
  bool requireAttributedGroup = false;
};
struct DigitalDependencyGraph;
/// Same sampling proof against an already constructed graph. This nonrecursive
/// entry is used by crossing source checks during assignment-graph admission.
mlir::LogicalResult verifySequentialSampleDependencies(mlir::Operation *module,
                                                       llvm::ArrayRef<SequentialSample> samples,
                                                       const DigitalDependencyGraph &graph,
                                                       llvm::ArrayRef<unsigned> roots,
                                                       SequentialModelLimits limits = {},
                                                       unsigned alreadyCharged = 0);
mlir::LogicalResult verifySequentialSamples(mlir::Operation *module,
                                            llvm::ArrayRef<SequentialSample> samples,
                                            SequentialModelLimits limits = {},
                                            unsigned alreadyCharged = 0);
struct BehavioralIfHeader {
  mlir::Operation *operation = nullptr;
  mlir::Operation *module = nullptr;
  mlir::Block *bodies[2] = {nullptr, nullptr};
  unsigned captureOffsets[2] = {0, 0};
  unsigned captureCounts[2] = {0, 0};
};
mlir::FailureOr<BehavioralIfHeader> inspectBehavioralIfHeader(mlir::Operation *operation);
mlir::FailureOr<mlir::Value> behavioralCaptureSource(mlir::BlockArgument argument);
mlir::FailureOr<llvm::SmallVector<ParameterIntegerEnvironment, 4>>
behavioralIntegerContextsAtUse(mlir::Operation *use);
mlir::LogicalResult verifyBehavioralPredicate(mlir::Operation *operation);
mlir::LogicalResult verifyBehavioralIfOperation(mlir::Operation *operation);
mlir::LogicalResult verifySequentialBody(mlir::Block *body);
mlir::LogicalResult verifyResetAsserted(mlir::Operation *operation);
mlir::LogicalResult verifySequentialAssertion(mlir::Operation *operation);
mlir::LogicalResult verifySequentialSelect(mlir::Operation *operation);
mlir::LogicalResult verifySequentialUpdate(mlir::Operation *operation);
/// F19 and unbound F54 descriptor-only intent remains non-executable. Actual
/// local bindings opt ordinary register declarations/updates into this model.
mlir::LogicalResult verifySequentialModel(mlir::Operation *module,
                                          SequentialModelLimits limits = {});
/// Rewrite real owned priority regions to real SSA selections and clocked
/// updates. Verify and build on a detached clone before committing any mutation.
mlir::LogicalResult normalizeSequentialModel(mlir::Operation *module,
                                             SequentialModelLimits limits = {});
} // namespace nodal
#endif
