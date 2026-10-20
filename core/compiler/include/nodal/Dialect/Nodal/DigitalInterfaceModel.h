#ifndef NODAL_DIALECT_NODAL_DIGITALINTERFACEMODEL_H
#define NODAL_DIALECT_NODAL_DIGITALINTERFACEMODEL_H

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/SmallVector.h"

namespace nodal {
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
/// Append dependencies and optional coherent observations transactionally after
/// one shared validation. Unaccessed or undriven external leaves do not create
/// observations. Ordering follows canonical values in authored source order;
/// no physical bits/lanes or transitive provenance sets are expanded.
mlir::LogicalResult collectDigitalInterfaceDependencies(
    mlir::Operation *module, llvm::SmallVectorImpl<DigitalInterfaceValueDependencies> &dependencies,
    llvm::SmallVectorImpl<DigitalInterfaceObservationGroup> *observations = nullptr);
} // namespace nodal

#endif
