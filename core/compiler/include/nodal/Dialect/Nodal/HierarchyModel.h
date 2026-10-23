#ifndef NODAL_DIALECT_NODAL_HIERARCHYMODEL_H
#define NODAL_DIALECT_NODAL_HIERARCHYMODEL_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/ArrayRef.h"
#include "llvm/ADT/SmallVector.h"

#include <memory>
#include <optional>
#include <string>

namespace nodal {
/// One original lexical Generate in the enclosing occurrence, never an enumerated lane.
struct HierarchyGeneratedScope {
  unsigned occurrence = 0;
  mlir::Operation *family = nullptr;
  GeneratedDomain domain;
};
struct HierarchyOccurrence {
  mlir::Operation *module = nullptr;
  mlir::Operation *instance = nullptr;
  unsigned parent = 0;
  std::string path;
  /// Original outer-to-inner Generate ancestry, inherited by descendant instances.
  llvm::SmallVector<HierarchyGeneratedScope, 0> generatedScopes;
};
struct ContextualValue {
  unsigned occurrence = 0;
  mlir::Value value;
  mlir::Operation *use = nullptr;
};
struct ContextualDomain {
  unsigned occurrence = 0;
  mlir::Operation *declaration = nullptr;
  bool operator==(const ContextualDomain &other) const {
    return occurrence == other.occurrence && declaration == other.declaration;
  }
};
/// Immutable view of original IR facts; never a synthesized binding operation.
struct ContextualBinding {
  ContextualDomain domain;
  mlir::Operation *record = nullptr;
  ContextualValue clock;
  std::optional<ContextualValue> reset;
  mlir::StringAttr resetPolarity;
};
struct HierarchyModelLimits {
  unsigned maximumOccurrences = 4096;
  unsigned maximumEdges = 16384;
  unsigned maximumDepth = 128;
  unsigned maximumOperations = 65536;
};
class HierarchyModel {
public:
  static mlir::FailureOr<std::shared_ptr<HierarchyModel>> create(mlir::Operation *outer,
                                                                 HierarchyModelLimits limits = {});
  ~HierarchyModel();
  llvm::ArrayRef<HierarchyOccurrence> occurrences() const;
  mlir::FailureOr<unsigned> childOccurrence(unsigned parent, mlir::Operation *instance) const;
  mlir::FailureOr<ContextualDomain> resolveDomain(unsigned occurrence,
                                                  mlir::Attribute reference) const;
  mlir::FailureOr<ContextualBinding> resolveBinding(unsigned occurrence,
                                                    mlir::Attribute reference) const;
  /// Follow formal projections only. Every crossing of a lexical scope is an
  /// exact immediate-child substitution, never a raw foreign SSA capture.
  mlir::FailureOr<ContextualValue> resolveCapability(ContextualValue value) const;
  bool allowsDirectSampling(ContextualDomain source, ContextualDomain destination) const;
  mlir::LogicalResult verifyBindings() const;

private:
  struct Impl;
  explicit HierarchyModel(std::unique_ptr<Impl> impl);
  std::unique_ptr<Impl> impl;
};
bool hasLocalHierarchyObligations(mlir::Operation *module);

/// Check one actual authored instance and its exact Module/Generate lexical owner.
/// Does not grant selected-family access or admit analog/resolved family endpoints.
mlir::LogicalResult verifyHierarchyInstanceOperation(mlir::Operation *operation);

/// Declaration-only external flavor of the existing Module identity. Pin order
/// is authored order; no executable body or independent instance graph exists.
struct ExternalModuleSignature {
  mlir::Operation *module = nullptr;
  mlir::StringAttr externalName;
  llvm::SmallVector<mlir::Operation *, 4> pins;
};
mlir::FailureOr<ExternalModuleSignature> inspectExternalModuleSignature(mlir::Operation *module);

/// Exact immediate-child definition lookup, shared by typed boundary owners.
/// This grants no member access, direction, domain or connectivity permission.
struct HierarchyInstance {
  mlir::Operation *instance = nullptr;
  mlir::Operation *module = nullptr;
};
mlir::FailureOr<HierarchyInstance> resolveHierarchyInstance(mlir::Operation *use,
                                                            mlir::Attribute reference);

/// Exact immediate-child boundary; the reference result remains parent-owned.
struct InstancePort {
  mlir::Operation *instance;
  mlir::Operation *module;
  mlir::Operation *port;
};
mlir::FailureOr<InstancePort> inspectInstancePort(mlir::Operation *operation);
mlir::LogicalResult verifyRequirementProjection(mlir::Operation *operation);
mlir::LogicalResult verifyInstanceDomainBinding(mlir::Operation *operation);
/// Mandatory admission from ModuleOp verification, not an optional pipeline pass.
mlir::LogicalResult verifyHierarchyModule(mlir::Operation *module);
} // namespace nodal
#endif
