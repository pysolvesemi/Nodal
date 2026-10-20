#ifndef NODAL_DIALECT_NODAL_EVENTMODEL_H
#define NODAL_DIALECT_NODAL_EVENTMODEL_H

#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/SmallVector.h"

namespace nodal {
struct DigitalDependencyGraph;
/// Closed root-only event declaration. Ports are canonical physical identities,
/// not port_value aliases. AssignmentQuery remains the sole driver/graph owner.
struct ExecutableEventProcess {
  mlir::Operation *operation = nullptr;
  mlir::Operation *module = nullptr;
  mlir::Value trigger;
  llvm::SmallVector<mlir::Operation *, 2> storagePorts;
  llvm::SmallVector<mlir::Operation *, 2> capturePorts;
  llvm::StringRef edge;
  uint64_t stateBits = 0;
};
mlir::FailureOr<ExecutableEventProcess> inspectEventProcess(mlir::Operation *operation);
mlir::LogicalResult verifyEventRootOwnership(mlir::Operation *module);
mlir::LogicalResult verifyEventUpdate(mlir::Operation *operation);
/// Check exact actual RHS leaves against declared captures using the existing
/// AssignmentQuery graph. Event state cuts retain their distinct provenance.
mlir::LogicalResult verifyEventSourceDependencies(mlir::Operation *module,
                                                  const DigitalDependencyGraph &graph);
} // namespace nodal
#endif
