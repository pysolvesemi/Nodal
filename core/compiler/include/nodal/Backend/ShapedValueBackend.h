#ifndef NODAL_BACKEND_SHAPEDVALUEBACKEND_H
#define NODAL_BACKEND_SHAPEDVALUEBACKEND_H

#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/SmallVector.h"

namespace nodal {

// Target arithmetic is derived from the verified shape graph. It neither owns
// storage nor repeats the source shape/alias/definite-assignment analysis.
mlir::FailureOr<std::string> staticTargetArithmetic(llvm::StringRef left, char operation,
                                                    llvm::StringRef right);
struct TargetShapeExpression {
  std::string text;
  ParameterIntegerBounds bounds;
};
struct TargetShapedLayout {
  llvm::SmallVector<TargetShapeExpression> dimensions;
  TargetShapeExpression count;
};
// The portable procedural carrier uses signed 32-bit indices. Every dimension,
// stride, count and retained static-expression intermediate is checked here.
mlir::FailureOr<TargetShapedLayout> resolveTargetShapedLayout(mlir::Operation *module,
                                                              llvm::StringRef dimensions);
mlir::FailureOr<TargetShapeExpression> renderShapeStaticIndex(mlir::Operation *module,
                                                              mlir::Value value);
// Linear target program for the actual verified projection chain. Each step
// assigns the same private Integer temporary, so deep maps never duplicate a
// previous expression tree. Reshape preserves the immediate logical ordinal.
struct ShapedProjectionStep {
  mlir::Operation *projection = nullptr;
  std::string expression;
};
struct ShapedProjectionProgram {
  mlir::Value root;
  llvm::SmallVector<ShapedProjectionStep> steps;
  std::string result;
};
mlir::FailureOr<ShapedProjectionProgram> renderShapedProjectionProgram(mlir::Value value,
                                                                       llvm::StringRef ordinal,
                                                                       llvm::StringRef temporary);
} // namespace nodal
#endif
