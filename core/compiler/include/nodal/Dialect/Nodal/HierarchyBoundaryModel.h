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
  /// Optional exact direct child instance. Expressions then retain finalized
  /// parent actual substitution, while type/path identity stays child-owned.
  mlir::Operation *instance = nullptr;
  llvm::SmallVector<HierarchyBoundaryLeaf, 4> leaves;
};
/// Derive complete bit-pattern leaves from the existing type/shape/parameter
/// owners without expanding lanes or bits. The IR must stay alive and unchanged.
/// Generated lexical instances require the subsequent admitted family view.
mlir::FailureOr<HierarchyBoundaryType>
inspectHierarchyBoundaryType(mlir::Type type, mlir::Operation *declarationOwner,
                             mlir::Operation *instance = nullptr);
} // namespace nodal
#endif
