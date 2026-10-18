#ifndef NODAL_DIALECT_NODAL_ANALOGEVENTS_H
#define NODAL_DIALECT_NODAL_ANALOGEVENTS_H

#include "mlir/IR/Operation.h"
#include "mlir/IR/Value.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/SmallVector.h"

#include <cstdint>
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
  // Live scalar handle for typed control/event leaves, never a captured value.
  mlir::Value storageReference;
  // Non-evaluating actual fill identity; body is held once in the derived DAG.
  mlir::Value deferredReference;
};
/// Per-owner bindings derived from actual scalar storage operands.
struct AnalogSourceStorageBinding {
  std::string path;
  mlir::Value reference;
};
/// One canonical expression site. Event arguments retain their actual primitive
/// and authored slot; controls have one root and no argument slot.
struct AnalogLiveSourceRoot {
  mlir::Operation *operation = nullptr;
  std::string attribute;
  std::optional<unsigned> argumentSlot;
  AnalogSourceExpression expression;
};
struct AnalogDeferredFillDefinition {
  mlir::Value reference;
  AnalogSourceExpression body;
};
struct AnalogLiveSourceProgram {
  mlir::Operation *consumer = nullptr;
  llvm::SmallVector<AnalogLiveSourceRoot, 1> roots;
  // Dependency-first, exactly one body per actual fill identity.
  llvm::SmallVector<AnalogDeferredFillDefinition, 2> fills;
  llvm::SmallVector<AnalogSourceStorageBinding> storageBindings;
  uint64_t eventDemandWrappers = 0;
  uint64_t eventExpressionBytes = 0;
};
/// Resolve a control, primitive event, complete event OR, or analog_on into its
/// complete consumer graph. No source body expansion or numeric evaluation.
mlir::FailureOr<AnalogLiveSourceProgram> resolveAnalogLiveSourceProgram(mlir::Operation *consumer);
mlir::LogicalResult verifyAnalogLiveFillOperation(mlir::Operation *definition);
mlir::LogicalResult verifyAnalogLiveModuleResources(mlir::Operation *module);
inline constexpr uint64_t analogLiveEventWrapperLimit = 4096;
inline constexpr uint64_t analogLiveEventByteLimit = 1024 * 1024;
inline constexpr uint64_t analogLiveModuleWrapperLimit = 65536;
inline constexpr uint64_t analogLiveModuleByteLimit = 16 * 1024 * 1024;
mlir::FailureOr<llvm::SmallVector<AnalogSourceStorageBinding>>
resolveAnalogSourceStorageBindings(mlir::Operation *operation);
mlir::FailureOr<AnalogSourceExpression> parseAnalogSourceExpression(mlir::Operation *context,
                                                                    llvm::StringRef source);
/// Actual captured scalar read binding; paths are labels, never selectors.
mlir::FailureOr<std::string> getAnalogCapturedReadPath(mlir::Value value);
mlir::FailureOr<mlir::Operation *> getAnalogCapturedReadIdentity(mlir::Value value);
/// Bind and type retained procedural scalar source expressions independently of
/// event presence. Shaped payloads retain their actual-SSA operation verifiers;
/// event placement and ownership remain in verifyAnalogEventOperation.
mlir::LogicalResult verifyAnalogProceduralExpressions(mlir::Operation *procedure);
/// Compatibility entry point; delegates to event-independent expression checks.
mlir::LogicalResult verifyAnalogEventProcedure(mlir::Operation *procedure);
mlir::FailureOr<mlir::Operation *> resolveAnalogHeldVariable(mlir::Operation *operation);
mlir::LogicalResult verifyAnalogHeldRead(mlir::Operation *operation);
bool isStaticAnalogSourceExpression(const AnalogSourceExpression &expression);
bool hasAnalogEvents(mlir::Operation *operation);
bool isAnalogEventExpression(mlir::Operation *operation);
mlir::LogicalResult verifyAnalogEventOperation(mlir::Operation *operation);
} // namespace nodal
#endif
