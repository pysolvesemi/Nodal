#ifndef NODAL_DIALECT_NODAL_CROSSINGMODEL_H
#define NODAL_DIALECT_NODAL_CROSSINGMODEL_H

#include "nodal/Dialect/Nodal/SequentialModel.h"

namespace nodal {
class DomainModel;
struct DigitalDependencyGraph;
/// Independently inspected executable header. This is not source-sampling proof;
/// verifyCrossingModel/the completed assignment graph owns that proof.
enum class CrossingKind { Level, Gray, Pulse, Handshake, Fifo };
/// Canonical endpoint roles are derived from the closed operation contract, never
/// accepted as independently mutable metadata. Domain references retain aliases.
struct CrossingOperand {
  unsigned index;
  mlir::FlatSymbolRefAttr domain;
  llvm::StringRef role;
};
struct CrossingResult {
  unsigned index;
  mlir::FlatSymbolRefAttr domain;
  llvm::StringRef role;
};
struct ExecutableCrossing {
  mlir::Operation *operation = nullptr;
  mlir::Operation *sourceDomain = nullptr;
  mlir::Operation *destinationDomain = nullptr;
  mlir::Operation *sourceBinding = nullptr;
  mlir::Operation *destinationBinding = nullptr;
  unsigned stages = 0;
  llvm::StringRef identity;
  CrossingKind kind = CrossingKind::Level;
  uint64_t payloadMaximumBits = 1;
  uint64_t stateBits = 0;
  unsigned depth = 0;
  unsigned pointerBits = 0;
  llvm::SmallVector<CrossingOperand, 3> operands;
  llvm::SmallVector<CrossingResult, 3> results;
};
mlir::FailureOr<ExecutableCrossing> inspectExecutableCrossing(mlir::Operation *operation,
                                                              const DomainModel &domains);
mlir::FailureOr<ExecutableCrossing> inspectExecutableCrossing(mlir::Operation *operation);
/// Preserves F19 descriptors. Only explicit closed executable contracts authorize stateful graph
/// cuts.
mlir::LogicalResult verifyCrossingOperation(mlir::Operation *operation);
mlir::LogicalResult verifyCrossingModel(mlir::Operation *module, SequentialModelLimits limits = {});
/// Called only after the one physical dependency graph has been constructed.
/// Never recursively invokes graph construction or a full operation verifier.
mlir::LogicalResult verifyCrossingSourceDependencies(mlir::Operation *module,
                                                     const DigitalDependencyGraph &graph,
                                                     SequentialModelLimits limits = {});
} // namespace nodal
#endif
