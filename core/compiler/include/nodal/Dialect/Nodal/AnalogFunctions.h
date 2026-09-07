#ifndef NODAL_DIALECT_NODAL_ANALOGFUNCTIONS_H
#define NODAL_DIALECT_NODAL_ANALOGFUNCTIONS_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/ArrayRef.h"
#include "llvm/ADT/StringRef.h"

#include <optional>
#include <string>

namespace nodal {
inline constexpr llvm::StringLiteral analogFunctionRegistryVersion = "1";
inline constexpr llvm::StringLiteral analogFunctionSourcePrefix = "analog_function_v1_";
inline constexpr llvm::StringLiteral analogAnalysisSourcePrefix = "analog_analysis_v1_";
struct AnalogFunctionEntry {
  llvm::StringLiteral id;
  unsigned arity;
  llvm::StringLiteral dimensionRule;
  llvm::StringLiteral verilogA;
};
struct AnalogAnalysisEntry {
  llvm::StringLiteral id;
  llvm::StringLiteral verilogA;
};
const AnalogFunctionEntry *lookupAnalogFunction(llvm::StringRef id);
const AnalogFunctionEntry *lookupAnalogFunctionTarget(llvm::StringRef spelling);
const AnalogAnalysisEntry *lookupAnalogAnalysis(llvm::StringRef id);
bool isAnalogAnalysisTarget(llvm::StringRef spelling);
mlir::FailureOr<std::string> analogFunctionDimension(const AnalogFunctionEntry &entry,
                                                     llvm::ArrayRef<std::string> dimensions);
mlir::FailureOr<std::optional<double>>
evaluateAnalogFunction(const AnalogFunctionEntry &entry,
                       llvm::ArrayRef<std::optional<double>> arguments);
mlir::LogicalResult verifyAnalogFunctionOperation(mlir::Operation *operation);
} // namespace nodal
#endif
