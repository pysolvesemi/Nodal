#ifndef NODAL_DIALECT_NODAL_MEMORYMODEL_H
#define NODAL_DIALECT_NODAL_MEMORYMODEL_H

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/STLFunctionalExtras.h"
#include "llvm/ADT/SmallVector.h"

namespace nodal {
struct MemoryModelLimits {
  unsigned maximumOperations = 65536;
  unsigned maximumPairQueries = 16384;
  unsigned maximumGuardWork = 262144;
};
enum class MemoryMaskKind { WholeElement, Bytes };
enum class MemoryReadSelection { BeforeWrites, AfterWrites, HoldOnCollision, UnspecifiedCollision };
/// Read-only facts independently recomputed from the actual sole MemoryOp,
/// typed operands and physical binding. No serialized range/default is trusted.
struct MemoryPortFacts {
  mlir::Operation *memory;
  mlir::Operation *binding;
  mlir::Operation *domain;
  mlir::Type element;
  ParameterIntegerBounds depth;
  int64_t latency;
  MemoryMaskKind mask = MemoryMaskKind::WholeElement;
  MemoryReadSelection selection;
};
mlir::FailureOr<MemoryPortFacts> inspectMemoryPort(mlir::Operation *port);
mlir::LogicalResult verifyMemoryPort(mlir::Operation *port);
/// Checks actual source ownership, canonical dense block/root order, all pair
/// obligations and async mixed-access policy. Raw branch writes are previewed
/// with the existing sequential normalizer, never a second branch parser.
mlir::LogicalResult verifyMemoryModel(mlir::Operation *module, MemoryModelLimits limits = {});
/// Shared admission/budget preflight for the sequential normalizer. Raw-write
/// guard disjointness alone is deferred until its actual guards are normalized.
/// This is not a substitute for verifyMemoryModel on published canonical IR.
mlir::LogicalResult preflightMemoryNormalization(mlir::Operation *module,
                                                 MemoryModelLimits limits = {});

/// Bounded causal query on one canonical memory's actual ports at one edge.
/// The caller supplies pre-edge cells/SSA values and the first-stage hold values;
/// no initial contents/reset or mutable memory registry is invented. The result
/// is a sparse write set plus first-stage responses and exact delay descriptors.
/// Null attributes mean unspecified. No memory depth or latency is enumerated.
struct MemoryCellUpdate {
  int64_t address;
  mlir::Attribute value;
};
struct MemoryReadSample {
  mlir::Operation *port;
  mlir::Attribute firstStage;
  int64_t latency;
};
struct MemoryEdgeResult {
  llvm::SmallVector<MemoryCellUpdate, 4> writes;
  llvm::SmallVector<MemoryReadSample, 4> reads;
};
mlir::FailureOr<MemoryEdgeResult>
evaluateMemoryEdge(mlir::Operation *memory,
                   llvm::function_ref<mlir::FailureOr<mlir::Attribute>(mlir::Value)> value,
                   llvm::function_ref<mlir::Attribute(int64_t)> before,
                   llvm::function_ref<mlir::Attribute(mlir::Operation *)> previousFirstStage,
                   MemoryModelLimits limits = {});
/// Exact compact pipeline equation: after edge E, latency L reads the first
/// stage sampled at E-L+1. Negative history is unspecified; L never allocates
/// nodes. The provider supplies actual prior first-stage samples, including hold.
mlir::FailureOr<mlir::Attribute>
memoryResponseAtEdge(mlir::Operation *read, int64_t edge,
                     llvm::function_ref<mlir::Attribute(int64_t)> firstStageAt);
} // namespace nodal
#endif
