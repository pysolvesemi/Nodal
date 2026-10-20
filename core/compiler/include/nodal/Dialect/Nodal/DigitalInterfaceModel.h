#ifndef NODAL_DIALECT_NODAL_DIGITALINTERFACEMODEL_H
#define NODAL_DIALECT_NODAL_DIGITALINTERFACEMODEL_H

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/SmallVector.h"

namespace nodal {
/// Original endpoint facts, never copied role/access claims from a projection.
struct DigitalInterfaceBoundary {
  mlir::Operation *instance = nullptr;
  mlir::Operation *module = nullptr;
  mlir::Operation *endpoint = nullptr;
  mlir::FlatSymbolRefAttr domain;
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
};
/// Validate once per owning module and transactionally append all endpoints and
/// complete selected-role leaves in authored declaration order. These are
/// borrowed IR facts, valid only while the IR remains alive and unchanged.
/// Occurrence/parameter substitution and target physical adapters remain with
/// their existing owners; no parallel hierarchy or resolved registry is built.
mlir::LogicalResult
collectDigitalInterfaceEndpoints(mlir::Operation *module,
                                 llvm::SmallVectorImpl<DigitalInterfaceEndpoint> &endpoints);

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
/// Existing local resolution facts, not another net allocation or topology owner.
struct DigitalResolvedComponent {
  mlir::Value value;
  mlir::FlatSymbolRefAttr domain;
  /// Existing net aliases and read results whose provenance follows this component.
  llvm::SmallVector<mlir::Value, 2> values;
  llvm::SmallVector<mlir::Operation *, 2> drivers, splits;
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
