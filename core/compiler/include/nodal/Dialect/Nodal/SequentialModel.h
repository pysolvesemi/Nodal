#ifndef NODAL_DIALECT_NODAL_SEQUENTIALMODEL_H
#define NODAL_DIALECT_NODAL_SEQUENTIALMODEL_H
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/SmallVector.h"
namespace mlir {
class OpBuilder;
}
namespace nodal {
enum class SequentialResetAvailability { Absent, Present, Contextual };
/// Query-local control facts. A missing formal projection is not evidence that
/// a requirement is resetless. Material semantics remain occurrence-owned.
struct SequentialDomainControls {
  mlir::Operation *module = nullptr;
  mlir::Operation *declaration = nullptr;
  mlir::Operation *binding = nullptr;
  mlir::Value clock, reset;
  SequentialResetAvailability resetAvailability = SequentialResetAvailability::Absent;
  bool contextual() const { return resetAvailability == SequentialResetAvailability::Contextual; }
};
/// Independently check a local executable binding or an exact unresolved local
/// requirement. Formal admission is structural only and requires mandatory
/// whole-root occurrence verification before any executable publication.
mlir::FailureOr<SequentialDomainControls>
inspectSequentialDomainControls(mlir::Operation *use, mlir::Attribute reference);
/// Exact local Clock match, accepting any verified projection of the same
/// formal requirement rather than assigning identity to one projection record.
bool matchesSequentialDomainClock(mlir::Value clock, const SequentialDomainControls &controls);
/// Reuse a dominating exact formal Clock or create one at the module start.
/// Caller owns the private transaction, charges one operation conservatively,
/// and verifies the whole root before publication. Never creates Reset.
mlir::FailureOr<mlir::Value> getOrCreateSequentialDomainClock(mlir::Operation *use,
                                                              mlir::OpBuilder &builder,
                                                              mlir::DictionaryAttr metadata);
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
  unsigned occurrence = 0;
  bool observationOnly = false;
};
/// Closed clock-control provenance checked by the same physical dependency graph.
struct ClockControlSample {
  mlir::Value value;
  mlir::Attribute source; // actual domain, or string constant/external
  mlir::Operation *use;
};
mlir::LogicalResult verifyClockControlSamples(mlir::Operation *module,
                                              llvm::ArrayRef<ClockControlSample> samples,
                                              SequentialModelLimits limits = {});
struct DigitalDependencyGraph;
/// Same sampling proof against an already constructed graph. This nonrecursive
/// entry is used by crossing source checks during assignment-graph admission.
mlir::LogicalResult verifySequentialSampleDependencies(mlir::Operation *module,
                                                       llvm::ArrayRef<SequentialSample> samples,
                                                       const DigitalDependencyGraph &graph,
                                                       llvm::ArrayRef<unsigned> roots,
                                                       SequentialModelLimits limits = {},
                                                       unsigned alreadyCharged = 0);
/// Additive physical receiver contexts for an already checked selected graph.
/// IDs refer to graph.selectionContexts and qualify the original sample owner,
/// not its source root (whose context may acquire further source selections).
/// An empty companion preserves the original all-zero caller contract. Nonzero
/// contexts must exactly retain the receiver's lexical/inherited family axes.
mlir::LogicalResult verifySequentialSampleDependencies(
    mlir::Operation *module, llvm::ArrayRef<SequentialSample> samples,
    const DigitalDependencyGraph &graph, llvm::ArrayRef<unsigned> roots,
    llvm::ArrayRef<unsigned> receiverContexts, SequentialModelLimits limits,
    unsigned alreadyCharged = 0);
mlir::LogicalResult verifySequentialSamples(mlir::Operation *module,
                                            llvm::ArrayRef<SequentialSample> samples,
                                            SequentialModelLimits limits = {},
                                            unsigned alreadyCharged = 0);
mlir::FailureOr<llvm::SmallVector<SequentialSample, 8>>
collectHierarchySequentialSamples(mlir::Operation *module, SequentialModelLimits limits = {});
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
/// local bindings or mandatory closed-root hierarchy occurrences admit ordinary
/// register declarations/updates into this model.
mlir::LogicalResult verifySequentialModel(mlir::Operation *module,
                                          SequentialModelLimits limits = {});
/// Rewrite real owned priority regions to real SSA selections and clocked
/// updates. Local formal requirements retain local projection operands and no
/// material edge/reset fields; mandatory closed-root occurrence proof discharges
/// those semantics. Verify a detached whole-root clone before committing.
mlir::LogicalResult normalizeSequentialModel(mlir::Operation *module,
                                             SequentialModelLimits limits = {});
/// Internal transaction composition: normalize one definition in a caller-owned
/// private whole-root candidate without cloning or committing. The caller must
/// discard the candidate on failure and verify the entire root after all
/// definitions are normalized, before publishing any result.
mlir::LogicalResult normalizeSequentialCandidate(mlir::Operation *module,
                                                 SequentialModelLimits limits = {});
} // namespace nodal
#endif
