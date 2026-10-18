#ifndef NODAL_DIALECT_NODAL_SHAPEMODEL_H
#define NODAL_DIALECT_NODAL_SHAPEMODEL_H

#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/SmallVector.h"

#include <optional>
#include <string>

namespace nodal {

/// One proved canonical dimension. Exactly one representation is populated:
/// a positive literal, an owned parameter declaration, or an owned expression.
struct StaticShapeDimension {
  ParameterIntegerBounds bounds;
  mlir::Operation *parameter = nullptr;
  mlir::Value expression;
  int64_t literal = 0;
};

/// Compare an exact child formal extent under its finalized instance parameter
/// environment with an owned parent extent. Physical index identity is separate.
mlir::LogicalResult verifyInstanceShapeDimension(mlir::Operation *instance,
                                                 const StaticShapeDimension &formal,
                                                 const StaticShapeDimension &parentActual);

/// Resolve a canonical dimension without substituting parameter defaults.
/// Requires positive finite i64 bounds and direct-module structural ownership.
mlir::FailureOr<StaticShapeDimension> resolveStaticShapeDimension(mlir::Operation *module,
                                                                  llvm::StringRef dimension);

/// Prove a nonempty shape's worst-case cardinality fits positive signed i64.
mlir::FailureOr<int64_t> inferStaticShapeMaximumCardinality(mlir::Operation *module,
                                                            llvm::StringRef dimensions);

/// Prove a direct-module static index using the existing literal/i64 carriers.
mlir::FailureOr<ParameterIntegerBounds> inferStaticShapeIndexBounds(mlir::Operation *module,
                                                                    mlir::Value value);

struct StaticShapeIndexIdentity {
  enum Kind { Literal, Parameter, Expression, Induction } kind;
  int64_t literal = 0;
  mlir::Operation *root = nullptr;
  bool operator==(const StaticShapeIndexIdentity &other) const {
    return kind == other.kind && literal == other.literal && root == other.root;
  }
};
mlir::FailureOr<StaticShapeIndexIdentity> resolveStaticShapeIndexIdentity(mlir::Operation *module,
                                                                          mlir::Value value);

/// A use-aware static or direct active-induction proof. An empty attained range
/// means the independently verified generated domain has no occurrences.
struct ShapeIndexProof {
  StaticShapeIndexIdentity identity;
  std::optional<ParameterIntegerBounds> attainedBounds;
};
mlir::FailureOr<ShapeIndexProof> resolveShapeIndexProof(mlir::Value value, mlir::Operation *use);
mlir::LogicalResult verifyShapeIndexInExtent(mlir::Value value, const StaticShapeDimension &extent,
                                             mlir::Operation *use);

/// Element-neutral facts derived from an actual acyclic SSA projection chain.
/// These establish shape/ordinal mapping, never storage or conservative rights.
struct StaticShapeProjection {
  mlir::Value root;
  llvm::SmallVector<mlir::Operation *, 4> projections;
  std::string canonicalKey;
  std::string canonicalScalarKey;
  bool wholeRoot = false;
  std::optional<int64_t> exactFixedCardinality;
  std::optional<int64_t> literalRootFlatOffset;
  llvm::SmallVector<std::string, 4> ancestorKeys;
  std::optional<int64_t> literalFirstOffset;
  std::optional<int64_t> literalLastOffset;
  bool contiguousLiteralRegion = false;
  std::optional<ParameterIntegerBounds> rootFlatBounds;
  llvm::SmallVector<ParameterIntegerBounds, 4> rootCoordinateBounds;
};
mlir::FailureOr<StaticShapeProjection> resolveStaticShapeProjection(mlir::Value reference,
                                                                    mlir::Operation *use);

/// Local transformation proofs shared by operation and storage-map verification.
/// These do not grant storage capability or establish initialization.
mlir::LogicalResult verifyShapeIndexMapping(mlir::Operation *operation);
mlir::LogicalResult verifyShapeViewMapping(mlir::Operation *operation);
mlir::LogicalResult verifyShapeSliceMapping(mlir::Operation *operation);

} // namespace nodal

#endif // NODAL_DIALECT_NODAL_SHAPEMODEL_H
