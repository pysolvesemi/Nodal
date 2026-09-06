#ifndef NODAL_DIALECT_NODAL_ANALOGEVENTS_H
#define NODAL_DIALECT_NODAL_ANALOGEVENTS_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

#include <optional>
#include <set>
#include <string>
#include <vector>

namespace nodal {
// Parsed source-semantic expression, never executable target text. References
// bind to declarations in the enclosing module; the backend chooses their names.
struct AnalogSourceExpression {
  std::string kind;
  std::string dimension;
  std::optional<double> constant;
  std::set<std::string> reads;
  std::string operation;
  std::string spelling;
  mlir::Operation *declaration = nullptr;
  std::vector<AnalogSourceExpression> operands;
};
mlir::FailureOr<AnalogSourceExpression> parseAnalogSourceExpression(mlir::Operation *context,
                                                                    llvm::StringRef source);
mlir::LogicalResult verifyAnalogEventProcedure(mlir::Operation *procedure);
mlir::FailureOr<mlir::Operation *> resolveAnalogHeldVariable(mlir::Operation *operation);
mlir::LogicalResult verifyAnalogHeldRead(mlir::Operation *operation);
bool isStaticAnalogSourceExpression(const AnalogSourceExpression &expression);
bool hasAnalogEvents(mlir::Operation *operation);
bool isAnalogEventExpression(mlir::Operation *operation);
mlir::LogicalResult verifyAnalogEventOperation(mlir::Operation *operation);
} // namespace nodal
#endif
