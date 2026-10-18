#ifndef NODAL_DIALECT_NODAL_CONSERVATIVECONNECTIVITY_H
#define NODAL_DIALECT_NODAL_CONSERVATIVECONNECTIVITY_H

#include "mlir/IR/BuiltinOps.h"
#include "mlir/Support/LogicalResult.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

namespace nodal {

/// Normalize Increment 28 conservative connectivity in every opted-in
/// nodal.module. The operation is deterministic and idempotent: previously
/// generated topology records are removed and rebuilt from source-semantic
/// terminals, nodes, connections, aliases, references, and branches.
mlir::LogicalResult materializeConservativeConnectivity(mlir::ModuleOp module);

/// Returns true when a nodal.module declares an extensible partial physical
/// component contract. Partial components retain local topology evidence but
/// do not claim a complete local equation boundary.
bool isPartialPhysicalComponent(mlir::Operation *module);

/// Actual conservative capability over the shared element-neutral map.
struct ConservativeShape {
  mlir::Operation *declaration = nullptr;
  StaticShapeProjection projection;
};
mlir::FailureOr<ConservativeShape> resolveConservativeShape(mlir::Value value,
                                                            mlir::Operation *use);
mlir::FailureOr<ConservativeShape> resolveConservativeSelection(mlir::Value value,
                                                                mlir::Operation *use);

/// An actual endpoint occurrence. Child incidence is separate from its physical
/// root/map, and the full child root type is checked before any projection.
struct ConservativeEndpoint {
  mlir::Value value;
  ConservativeShape shape;
  mlir::Operation *instance = nullptr;
  mlir::Operation *port = nullptr;
  mlir::Operation *declarationScope = nullptr;
  mlir::Operation *useScope = nullptr;
};
mlir::FailureOr<ConservativeEndpoint> resolveConservativeEndpoint(mlir::Value value,
                                                                  mlir::Operation *use);

/// Both maps consume the same logical ordinal. Whole bindings, partial joins
/// and scalar array selections share this one relation representation.
struct ConservativeRelation {
  mlir::Operation *operation = nullptr;
  ConservativeEndpoint left, right;
};
bool isConservativeRelation(mlir::Operation *operation);
mlir::FailureOr<ConservativeRelation> resolveConservativeRelation(mlir::Operation *operation);
mlir::LogicalResult verifyConservativeInstanceArray(mlir::Operation *operation);
mlir::LogicalResult verifyConservativeArrayBindings(mlir::Operation *module);

/// Query actual scalar identity through the same scoped endpoint relations.
/// Unknown or exhausted proof never establishes distinct physical nodes.
enum class ConservativeAlias { Equal, Distinct, Inert, Unknown, ResourceLimit, Invalid };
ConservativeAlias compareConservativeEndpoints(mlir::Value left, mlir::Value right,
                                               mlir::Operation *use);

/// Compare independently valid source occurrences without pretending that a
/// sibling's SSA value is available at the other use. Only common structural
/// ancestors supply exact relations; other generated wiring remains a family.
ConservativeAlias compareConservativeEndpointOccurrences(mlir::Value left, mlir::Operation *leftUse,
                                                         mlir::Value right,
                                                         mlir::Operation *rightUse);

/// Derived single-occurrence relations for one immediate Module/Generate scope.
/// No generated endpoint is inserted into the module-level connectivity graph.
struct ConservativeScalarGroup {
  llvm::SmallVector<mlir::Value, 4> members;
  llvm::SmallVector<mlir::Operation *, 4> connections;
};
struct ConservativeStructuralScope {
  mlir::Operation *module = nullptr;
  mlir::Operation *scope = nullptr;
  llvm::SmallVector<mlir::Operation *, 4> instances;
  llvm::SmallVector<mlir::Operation *, 4> nodes;
  llvm::SmallVector<mlir::Operation *, 4> nodeArrays;
  llvm::SmallVector<mlir::Operation *, 4> childScopes;
  llvm::SmallVector<ConservativeScalarGroup, 2> scalarGroups;
  llvm::SmallVector<ConservativeRelation, 2> relations;
};
mlir::FailureOr<ConservativeStructuralScope>
resolveConservativeStructuralScope(mlir::Operation *scope);

/// Port incidence is retained independently of physical connectivity aliases.
struct ConservativePortIncidence {
  mlir::Operation *declaration = nullptr;
  mlir::Value reference;
  std::optional<StaticShapeProjection> projection;
};
mlir::FailureOr<ConservativePortIncidence> resolveConservativePortIncidence(mlir::Value value,
                                                                            mlir::Operation *use);

/// A scalar terminal selection candidate, independently verified by the resolver.
bool isNodeArraySelection(mlir::Operation *operation);

/// Independently validate actual SSA index identities and possible aliases.
mlir::LogicalResult verifyNodeArraySelections(mlir::Operation *module);

/// Merge equivalent selected endpoints and their authored source-use provenance
/// before connectivity or access grouping. Never creates scalar node declarations.
mlir::LogicalResult normalizeNodeArraySelections(mlir::ModuleOp module);

} // namespace nodal

#endif // NODAL_DIALECT_NODAL_CONSERVATIVECONNECTIVITY_H
