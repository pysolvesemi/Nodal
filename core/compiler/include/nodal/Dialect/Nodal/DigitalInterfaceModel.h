#ifndef NODAL_DIALECT_NODAL_DIGITALINTERFACEMODEL_H
#define NODAL_DIALECT_NODAL_DIGITALINTERFACEMODEL_H

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/ADT/SmallVector.h"

namespace nodal {
/// Resolve the original endpoint's actual lexical definition, preserving regional
/// precedence and scope. This shallow query does not build a connectivity graph.
mlir::FailureOr<mlir::Operation *> resolveDigitalInterfaceDefinition(mlir::Operation *endpoint);

/// Original endpoint facts, never copied role/access claims from a projection.
struct DigitalInterfaceBoundary {
  mlir::Operation *instance = nullptr;
  mlir::Operation *module = nullptr;
  mlir::Operation *endpoint = nullptr;
  mlir::FlatSymbolRefAttr domain;
  /// Actual selected boundary operation; shared family inspector owns its prefix.
  mlir::Operation *selection = nullptr;
};
mlir::FailureOr<DigitalInterfaceBoundary>
inspectDigitalInterfaceBoundary(mlir::Operation *operation);
/// Exact local/boundary leaf identity for occurrence-graph substitution.
struct DigitalInterfaceMember {
  mlir::Operation *endpoint = nullptr;
  mlir::Operation *instance = nullptr;
  mlir::Operation *module = nullptr;
  mlir::StringAttr path;
  mlir::StringAttr access;
  /// Null for an ordinary declaration, otherwise the original selected boundary.
  mlir::Operation *selection = nullptr;
};
mlir::FailureOr<DigitalInterfaceMember> inspectDigitalInterfaceMember(mlir::Operation *operation);

/// A version marker selects the fully typed contract. Legacy F19 declarations
/// remain readable, but cannot supply capabilities to typed consumers.
bool hasDigitalInterfaceContract(mlir::Operation *operation);

/// Local version/ownership checks. Module verification owns cross-operation
/// resolution, so independent operation and pass verification share one model.
mlir::LogicalResult verifyDigitalInterfaceOperation(mlir::Operation *operation);

/// Verify actual definitions, complete role maps, member SSA, structural ABI and
/// resolved driver identity in one owning module. Types, widths and dimensions
/// use DigitalTypeModel/ShapeModel, without expanding physical bits or lanes.
/// Read-only, bounded metadata traversal; pass-through uses union-find and each
/// represented driver is counted once for all split views.
mlir::LogicalResult verifyDigitalInterfaceModel(mlir::Operation *module);

/// Immutable logical leaf facts from the complete selected role, including
/// leaves never materialized by a member-access operation. Types and ordered
/// dimensions remain structural; this query never expands physical bits/lanes.
struct DigitalInterfaceLeaf {
  mlir::Operation *declaration = nullptr;
  mlir::StringAttr path;
  mlir::Type type;
  mlir::StringAttr protocol, role, access;
  mlir::ArrayAttr nestedRoles, nestedInterfaces;
  mlir::DictionaryAttr analogMetadata, analogReferences;
  /// Authored-order representative of the existing local resolved component.
  /// Null means unrepresented, never permission to allocate another net.
  mlir::Value resolvedComponent;
};
/// An actual member SSA access, independently checked against its selected role.
/// Repeated role-qualified accesses to the same endpoint/path share canonicalValue,
/// not arbitrary connected endpoints, and retain their own
/// access rights. This is an immutable query result, not a connectivity registry.
struct DigitalInterfaceAccess {
  mlir::Value value, canonicalValue;
  mlir::Operation *projection = nullptr;
  DigitalInterfaceMember identity;
  DigitalInterfaceLeaf leaf;
};
struct DigitalInterfaceEndpoint {
  /// Parent-owned local declaration or exact child projection operation.
  mlir::Operation *operation = nullptr;
  /// Actual endpoint, which may belong to an immediate child definition.
  mlir::Operation *declaration = nullptr;
  mlir::Operation *declarationOwner = nullptr;
  /// The actual domain below is local to this owner, not declarationOwner.
  mlir::Operation *domainOwner = nullptr;
  /// Non-null only for a child boundary; declared roles are never inverted.
  mlir::Operation *instance = nullptr;
  mlir::Operation *definition = nullptr;
  mlir::Operation *selectedRole = nullptr;
  mlir::StringAttr semanticIdentity;
  mlir::FlatSymbolRefAttr domain;
  mlir::ArrayAttr dimensions;
  llvm::SmallVector<DigitalInterfaceLeaf> leaves;
  /// Retains selected occurrence provenance without copying a family/prefix registry.
  mlir::Operation *selection = nullptr;
};
/// Validate once per owning module and transactionally append all endpoints and
/// complete selected-role leaves in authored declaration order. These are
/// borrowed IR facts, valid only while the IR remains alive and unchanged.
/// Occurrence/parameter substitution and target physical adapters remain with
/// their existing owners; no parallel hierarchy or resolved registry is built.
mlir::LogicalResult
collectDigitalInterfaceEndpoints(mlir::Operation *module,
                                 llvm::SmallVectorImpl<DigitalInterfaceEndpoint> &endpoints);
mlir::LogicalResult
collectDigitalInterfaceEndpoints(mlir::Operation *module,
                                 llvm::SmallVectorImpl<DigitalInterfaceEndpoint> &endpoints,
                                 llvm::SmallVectorImpl<DigitalInterfaceAccess> *accesses);
/// Per-use independent ingress. Bulk consumers use the combined collector once.
mlir::FailureOr<DigitalInterfaceAccess> resolveDigitalInterfaceAccess(mlir::Value value);

/// Original whole authored driver, including unreachable generated templates.
/// No live dependency, selected occurrence, layout or physical slot is implied.
struct DigitalInterfaceTemplateDriver {
  mlir::Value root, source;
  mlir::Operation *effect = nullptr;
  mlir::Operation *family = nullptr;
};
/// Ordered, transactional batch from one verified Interface model. Each seed is
/// an original whole generated member and its actual final body operation.
/// Duplicate seeds retain order and are charged, but share the checked proof.
mlir::FailureOr<llvm::SmallVector<DigitalInterfaceTemplateDriver, 4>>
inspectGeneratedInterfaceTemplateDrivers(
    mlir::Operation *module, llvm::ArrayRef<std::pair<mlir::Value, mlir::Operation *>> seeds);

/// Shared query-local work accounting for complete boundary inventory plus its
/// downstream logical leaf traversal. This does not accept cached proof facts.
mlir::LogicalResult collectDigitalInterfaceEndpoints(
    mlir::Operation *module, llvm::SmallVectorImpl<DigitalInterfaceEndpoint> &endpoints,
    llvm::SmallVectorImpl<DigitalInterfaceAccess> *accesses, unsigned &work);

/// Verified implicit read edges for actual typed member/net values. Canonical
/// aliases and pass-through components share one physical identity; resolution
/// retains every authored driver without applying ordinary single-writer rules.
/// An empty source list means no local driver (an external/undriven boundary),
/// not permission to fabricate a value or execute an unimplemented backend.
struct DigitalInterfaceValueDependencies {
  mlir::Value value;
  /// Actual verified Interface instance/component domain. Null only for a
  /// standalone boundary with no declared Interface domain constraint.
  mlir::FlatSymbolRefAttr domain;
  llvm::SmallVector<std::pair<mlir::Value, mlir::Operation *>, 2> sources;
  /// Opaque external declaration contribution, never an inferred conditional driver.
  bool externalContribution = false;
};
/// A final observation of actual locally driven Interface/net values. The
/// canonical owner groups a valid/stream endpoint's forward payload and valid;
/// ready and unrelated members/endpoints remain separate. Aliases and resolved
/// pass-through views retain the dependency model's one physical identity.
/// These roots consume that same graph, including resolved data/enable edges.
struct DigitalInterfaceObservationGroup {
  /// Canonical first represented member/net, also the exact sample-group key.
  mlir::Operation *use;
  /// Observation context only, not proof of the roots' actual source domains.
  mlir::FlatSymbolRefAttr domain;
  llvm::SmallVector<mlir::Value, 2> values;
};
/// Checked geometry of one pointwise component family, not physical identity.
/// The exact original local endpoint operation owns these canonical dimension
/// tokens. Ordered outer Interface axes stay separate from the scalar payload.
/// Empty dimensions denote the existing singleton component. Every current
/// connection preserves the coordinate tuple; no broadcast/reshape is implied.
struct DigitalResolvedShape {
  mlir::Operation *owner = nullptr;
  mlir::ArrayAttr dimensions;
  /// Canonical ShapeModel proofs in the same axis order. Parameters and
  /// expressions retain their actual declarations/SSA, never sampled defaults.
  llvm::SmallVector<StaticShapeDimension, 2> axes;
};
/// Existing local resolution facts, not another net allocation or topology owner.
struct DigitalResolvedComponent {
  mlir::Value value;
  mlir::FlatSymbolRefAttr domain;
  /// Existing net aliases and read results whose provenance follows this component.
  llvm::SmallVector<mlir::Value, 2> values;
  llvm::SmallVector<mlir::Operation *, 2> drivers, splits;
  llvm::SmallVector<mlir::Operation *, 2> externalContributions;
  DigitalResolvedShape shape;
};
/// Logical slots include unaccessed Interface leaves and existing standalone nets.
/// Standalone declarations/boundaries use an explicit empty member path. A null
/// component remains unrepresented, never permission to allocate a new net.
struct DigitalResolvedEndpoint {
  mlir::Operation *operation = nullptr;
  mlir::Operation *instance = nullptr;
  mlir::Operation *declaration = nullptr;
  mlir::StringAttr path;
  mlir::FlatSymbolRefAttr domain;
  mlir::Value component;
  /// Verified local type and policy. Placement is absent for Interface members.
  mlir::Type type;
  mlir::StringAttr mode, profile, placement;
  DigitalResolvedShape shape;
};
struct DigitalResolvedTopology {
  llvm::SmallVector<DigitalResolvedComponent> components;
  llvm::SmallVector<DigitalResolvedEndpoint> endpoints;
};
/// Append dependencies and optional coherent observations transactionally after
/// one shared validation. Unaccessed or undriven external leaves do not create
/// observations. Ordering follows canonical values in authored source order;
/// no physical bits/lanes or transitive provenance sets are expanded.
mlir::LogicalResult collectDigitalInterfaceDependencies(
    mlir::Operation *module, llvm::SmallVectorImpl<DigitalInterfaceValueDependencies> &dependencies,
    llvm::SmallVectorImpl<DigitalInterfaceObservationGroup> *observations = nullptr,
    DigitalResolvedTopology *topology = nullptr);
} // namespace nodal

#endif
