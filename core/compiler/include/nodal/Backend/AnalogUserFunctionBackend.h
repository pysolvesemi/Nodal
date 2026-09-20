#ifndef NODAL_BACKEND_ANALOGUSERFUNCTIONBACKEND_H
#define NODAL_BACKEND_ANALOGUSERFUNCTIONBACKEND_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/StringMap.h"
#include "llvm/Support/raw_ostream.h"

namespace nodal {
mlir::LogicalResult renderAnalogUserFunctions(mlir::Operation *module, llvm::raw_ostream &output);
/// Independent target grammar: arguments, local declarations, ordered initialization, one final
/// return, and calls to previously parsed declarations. Does not accept arbitrary HDL text.
mlir::FailureOr<size_t> reparseAnalogUserFunction(llvm::StringRef source,
                                                  llvm::StringMap<unsigned> &functions);
mlir::LogicalResult reparseAnalogUserExpression(llvm::StringRef source,
                                                const llvm::StringMap<unsigned> &functions);
} // namespace nodal
#endif
