#ifndef NODAL_DIALECT_NODAL_ANALOGNUMERIC_H
#define NODAL_DIALECT_NODAL_ANALOGNUMERIC_H

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Operation.h"
#include "mlir/IR/Types.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/StringRef.h"

#include <optional>
#include <string>

namespace nodal {

enum class AnalogNumericKind {
  Invalid,
  Integer,
  Real,
  Boolean,
};

struct AnalogNumericTypeInfo {
  AnalogNumericKind kind = AnalogNumericKind::Invalid;
  std::string dimension;
  bool legacyF64 = false;
};

/// Return true only for the deterministic canonical dimension grammar frozen
/// by Increment 30.
bool isCanonicalDimensionSignature(llvm::StringRef signature);

/// Combine canonical dimensions by adding (`subtractRhs == false`) or
/// subtracting (`subtractRhs == true`) the right-hand exponent vector.
mlir::FailureOr<std::string> combineAnalogDimensions(llvm::StringRef lhs, llvm::StringRef rhs,
                                                     bool subtractRhs);

/// Classify a Nodal quantity, legacy dimensionless f64, or Boolean i1 value.
mlir::FailureOr<AnalogNumericTypeInfo> getAnalogNumericTypeInfo(mlir::Type type);

/// Evaluate only compiler-proven constants, never symbolic parameter defaults.
std::optional<double> getAnalogConstantRealValue(mlir::Value value);

/// Verify one source-semantic analog numeric operation.
mlir::LogicalResult verifyAnalogNumericOperation(mlir::Operation *operation);

/// Verify every quantity and analog expression in a builtin module.
mlir::LogicalResult verifyAnalogNumericModel(mlir::ModuleOp module);

/// Annotate pure constant expression roots without erasing authored operations.
mlir::LogicalResult foldAnalogNumericConstants(mlir::ModuleOp module);

/// Prove that every quantity can be erased to a native scalar only after the
/// mandatory semantic verifier has accepted the model.
mlir::LogicalResult verifyAnalogQuantityErasure(mlir::ModuleOp module);

} // namespace nodal

#endif // NODAL_DIALECT_NODAL_ANALOGNUMERIC_H
