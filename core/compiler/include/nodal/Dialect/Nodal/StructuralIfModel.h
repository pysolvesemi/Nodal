#ifndef NODAL_DIALECT_NODAL_STRUCTURALIFMODEL_H
#define NODAL_DIALECT_NODAL_STRUCTURALIFMODEL_H
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/SmallVector.h"
namespace nodal {
struct StructuralIfHeader {
  mlir::Operation *operation = nullptr;
  mlir::Operation *module = nullptr;
  mlir::Block *bodies[2] = {nullptr, nullptr};
  unsigned captureOffsets[2] = {0, 0};
  unsigned captureCounts[2] = {0, 0};
};
mlir::FailureOr<StructuralIfHeader> inspectStructuralIfHeader(mlir::Operation *operation);
/// Prove mutual exclusion only from distinct arms of the same actual owner.
/// Predicate equivalence, defaults and emitted names never establish this fact.
bool areOppositeStructuralArms(mlir::Operation *left, mlir::Operation *right);
/// Rebuild capture order after a rewrite using the verifier's same SSA/type-use
/// inventory. Only for a private candidate that is re-verified before publishing.
mlir::LogicalResult canonicalizeStructuralIfCaptures(mlir::Operation *operation, unsigned &work,
                                                     unsigned maximumWork);
mlir::FailureOr<mlir::Value> structuralIfCaptureSource(mlir::BlockArgument argument);
/// Recognize one exact typed family capture slot in ordinary structural ancestry.
/// This admits transport only; the storage owner independently resolves the actual
/// family/export graph at member selection.
bool isStructuralIfFamilyCapture(mlir::OpOperand &operand);
mlir::FailureOr<llvm::SmallVector<ParameterIntegerEnvironment, 4>>
structuralIfIntegerContextsAtUse(mlir::Operation *use);
mlir::LogicalResult
verifyStructuralIfOperation(mlir::Operation *operation,
                            const ParameterIntegerEnvironment *enclosing = nullptr);
mlir::LogicalResult verifyStructuralYieldOperation(mlir::Operation *operation);
mlir::LogicalResult verifyStructuralIfModel(mlir::Operation *module);
mlir::LogicalResult verifyDigitalGenerateBody(mlir::Operation *operation);
} // namespace nodal
#endif
