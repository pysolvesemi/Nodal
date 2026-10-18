#ifndef NODAL_DIALECT_NODAL_SHAPEMODEL_H
#define NODAL_DIALECT_NODAL_SHAPEMODEL_H

#include "nodal/Dialect/Nodal/ParameterModel.h"

namespace nodal {

/// One proved canonical dimension. Exactly one representation is populated:
/// a positive literal, an owned parameter declaration, or an owned expression.
struct StaticShapeDimension {
  ParameterIntegerBounds bounds;
  mlir::Operation *parameter = nullptr;
  mlir::Value expression;
  int64_t literal = 0;
};

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

} // namespace nodal

#endif // NODAL_DIALECT_NODAL_SHAPEMODEL_H
