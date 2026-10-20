#ifndef NODAL_DIALECT_NODAL_DOMAINMODEL_H
#define NODAL_DIALECT_NODAL_DOMAINMODEL_H

#include "mlir/IR/Attributes.h"
#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/DenseMap.h"

#include <memory>

namespace nodal {
struct DomainRelationshipIndex;
/// Read-only index of one module's actual domain and physical binding records.
/// Existing IR symbols remain authoritative. Rebuild this view after changing
/// domain declarations or bindings; no facts are persisted into IR or a registry.
/// This local F56 profile follows at most one unique requirement-to-domain bind.
/// ClockModel owns exact producer identity; verified Same classes supply a
/// separate sampling permission without changing domain or reset identity.
class DomainModel {
public:
  explicit DomainModel(mlir::Operation *use);
  /// Exact unbound local formal only; malformed/duplicate local binds never defer.
  mlir::Operation *unresolvedRequirement(mlir::Attribute reference) const;

  /// Resolve an actual local DomainOp, or a uniquely bound local requirement.
  /// A descriptor domain can resolve without any executable physical binding.
  mlir::FailureOr<mlir::Operation *> resolveDomain(mlir::Attribute reference) const;
  /// Resolve and independently verify the sole actual local Clock/Reset binding.
  mlir::FailureOr<mlir::Operation *> resolveBinding(mlir::Attribute reference) const;
  /// Verify clock/parent identity only, avoiding reset producer recursion.
  mlir::FailureOr<mlir::Operation *> resolveClockBinding(mlir::Attribute reference) const;
  /// Preserve the sampling contract: expected is an actual DomainOp reference,
  /// not another requirement. Only the source reference may follow a local bind.
  bool matchesActualDomain(mlir::Attribute reference, mlir::Attribute expected) const;
  /// Ordinary data permission only. Explicit CDC attribution must continue to
  /// use matchesActualDomain: an equivalence class is not one physical source.
  bool allowsDirectSampling(mlir::Attribute reference, mlir::Attribute expected) const;
  /// Closed executable relationships, physical aliases, exact period/phase and
  /// frequency consistency. Legacy descriptors never enter this proof index.
  mlir::LogicalResult verifyRelationships() const;
  /// Descriptor/executable opt-in lookup only, NOT a physical capability proof.
  /// Retains the first authored record so the module verifier can diagnose a
  /// duplicate at its original location. Use resolveBinding for a verified query.
  mlir::Operation *findLocalBinding(mlir::Attribute actualReference) const;
  /// Unique raw record for nonrecursive clock-graph proof, not a safety proof.
  mlir::Operation *findUniqueLocalBinding(mlir::Attribute actualReference) const;

private:
  struct Binding {
    mlir::Operation *first = nullptr;
    bool duplicate = false;
  };
  mutable std::shared_ptr<DomainRelationshipIndex> relationships;
  mlir::Operation *module = nullptr;
  llvm::DenseMap<mlir::Attribute, Binding> requirements;
  llvm::DenseMap<mlir::Attribute, Binding> physicalBindings;
};

/// Independent physical producer validation, including exact owned input ports,
/// explicit edge, policy and polarity. Preserves the F56 diagnostic contract.
mlir::LogicalResult verifyClockRelationship(mlir::Operation *operation);
mlir::LogicalResult verifyLocalDomainBinding(mlir::Operation *operation);
mlir::LogicalResult verifyLocalDomainClock(mlir::Operation *operation);
} // namespace nodal
#endif
