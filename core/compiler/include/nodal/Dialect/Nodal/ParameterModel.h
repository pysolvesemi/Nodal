#ifndef NODAL_DIALECT_NODAL_PARAMETERMODEL_H
#define NODAL_DIALECT_NODAL_PARAMETERMODEL_H

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Operation.h"
#include "mlir/IR/Value.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/StringRef.h"

#include <string>

namespace nodal {

/// Verify one parameter declaration, including its explicit kind,
/// ordinary/structural classification, and optional physical unit.
mlir::LogicalResult verifyParameterDeclaration(mlir::Operation *operation);

/// Verify cross-operation constant folding, constraints, overrides,
/// structural envelopes, and dynamic-value exclusion for the whole design.
mlir::LogicalResult verifyParameterModel(mlir::ModuleOp module);

/// Render a compile-time expression using retained literal spellings.
mlir::FailureOr<std::string> renderParameterConstantExpression(mlir::Value value);

/// Render an expression in one parameter's declared unit context. A
/// dimensionless authored magnitude receives the target parameter's exact
/// native scale suffix (or an explicit scale factor for compound expressions).
mlir::FailureOr<std::string> renderParameterConstantExpression(mlir::Value value,
                                                               mlir::Operation *targetParameter);

/// Return the explicit or legacy-inferred parameter kind.
llvm::StringRef getParameterKind(mlir::Operation *parameter);

/// Return the explicit or legacy-inferred parameter classification.
llvm::StringRef getParameterClassification(mlir::Operation *parameter);

/// True only for explicitly classified structural parameters.
bool isStructuralParameter(mlir::Operation *parameter);

/// Return the display symbol of a declared parameter unit, or an empty string.
std::string getParameterUnitSymbol(mlir::Operation *parameter);

/// Return the exact native Verilog scale suffix of the parameter unit.
std::string getParameterUnitNativeSuffix(mlir::Operation *parameter);

} // namespace nodal

#endif // NODAL_DIALECT_NODAL_PARAMETERMODEL_H
