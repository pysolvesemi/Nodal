#ifndef NODAL_DIALECT_NODAL_HIERARCHYBOUNDARYMODEL_H
#define NODAL_DIALECT_NODAL_HIERARCHYBOUNDARYMODEL_H

#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/SmallVector.h"

namespace nodal {
/// One nominal field step, never an emitted identifier or physical bit offset.
struct HierarchyBoundaryField {
  mlir::StringAttr structure;
  mlir::StringAttr name;
};
/// Ordered dimensions attached at their actual enclosing field depth. Distinct
/// shape layers remain distinct; sibling-private dimensions are never merged.
struct HierarchyBoundaryDimensions {
  unsigned fieldDepth = 0;
  llvm::SmallVector<InstantiatedIntegerExpression, 2> extents;
};
struct HierarchyBoundaryLeaf {
  mlir::Type type;
  llvm::SmallVector<HierarchyBoundaryField, 4> path;
  llvm::SmallVector<HierarchyBoundaryDimensions, 2> dimensions;
  InstantiatedIntegerExpression width;
  /// Existing canonical enum owner, or null for a non-enum scalar.
  mlir::Operation *enumDefinition = nullptr;
};
/// Query-local logical storage view. It does not grant endpoint access, choose
/// a layout, allocate a net, or establish an occurrence/domain binding.
struct HierarchyBoundaryType {
  mlir::Type type;
  mlir::Operation *declarationOwner = nullptr;
  /// Optional exact lexical child instance. Expressions retain finalized parent
  /// actual substitution, including original all-lane induction atoms for a
  /// generated template; type/path identity stays child-owned.
  mlir::Operation *instance = nullptr;
  llvm::SmallVector<HierarchyBoundaryLeaf, 4> leaves;
};
/// Derive complete bit-pattern leaves from the existing type/shape/parameter
/// owners without expanding lanes or bits. The IR must stay alive and unchanged.
/// A generated lexical instance retains its original all-lane formal contract
/// and actual induction DAG, never a selected lane or a sampled/default width.
mlir::FailureOr<HierarchyBoundaryType>
inspectHierarchyBoundaryType(mlir::Type type, mlir::Operation *declarationOwner,
                             mlir::Operation *instance = nullptr);
/// Original direct endpoint geometry. Payload dimensions retain their authored
/// definition anchor; outer InterfaceArray axes retain the endpoint anchor.
/// All expressions are numeric all-lane facts, never selected specialization.
struct DigitalInterfaceBoundaryType {
  mlir::Operation *endpoint = nullptr, *definition = nullptr, *member = nullptr;
  HierarchyBoundaryType payload;
  llvm::SmallVector<InstantiatedIntegerExpression, 2> outerDimensions;
};
/// Independently verifies the complete original Interface inventory once, then
/// returns exact endpoint/member seeds in order. Partial results are not exposed.
/// No child-instance substitution is inferred from a declaration alone.
mlir::FailureOr<llvm::SmallVector<DigitalInterfaceBoundaryType, 4>>
inspectDigitalInterfaceBoundaryTypes(
    mlir::Operation *module, llvm::ArrayRef<std::pair<mlir::Operation *, mlir::Operation *>> seeds);
} // namespace nodal
#endif
