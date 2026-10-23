#ifndef NODAL_DIALECT_NODAL_DIGITALREGIONMODEL_H
#define NODAL_DIALECT_NODAL_DIGITALREGIONMODEL_H
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/SmallVector.h"
namespace nodal {
/// Neutral lexical owner. Inspection is raw-header-only; it never verifies a body.
enum class DigitalRegionKind {
  Collection,
  BoundedLoop,
  StructuralArm,
  BehavioralArm,
  FlatFSMAction,
  EnumSwitchArm,
  Generate
};
struct DigitalRegionHeader {
  mlir::Operation *operation = nullptr;
  mlir::Operation *module = nullptr;
  mlir::Block *body = nullptr;
  DigitalRegionKind kind;
  unsigned ordinal = 0;
  bool isolated = false;
};
mlir::FailureOr<DigitalRegionHeader> inspectDigitalRegion(mlir::Block *body);
/// Same canonical header inspection with bounded caller-owned work.
mlir::FailureOr<DigitalRegionHeader> inspectDigitalRegion(mlir::Block *body,
                                                          unsigned &remainingWork);
mlir::LogicalResult verifyDigitalValueScope(mlir::Value value, mlir::Operation *use,
                                            unsigned &remainingWork);
mlir::FailureOr<mlir::Value> digitalRegionCaptureSource(mlir::BlockArgument argument,
                                                        unsigned &remainingWork);
mlir::FailureOr<mlir::Value> lookupDigitalRegionToken(mlir::Block *body, llvm::StringRef token,
                                                      unsigned &remainingWork);
bool isDigitalRegionOwner(mlir::Operation *operation);
/// Raw unique token lookup in one actual block. Empty Value means absent;
/// failure means malformed/ambiguous. Scope and arithmetic remain caller proofs.
mlir::FailureOr<mlir::Value> lookupDigitalRegionToken(mlir::Block *body, llvm::StringRef token,
                                                      unsigned *work = nullptr);
/// Exact structural-only ancestry containing a real Generate. This is not a
/// generic Integer environment binding and grants no capture bypass.
bool isGeneratedTypeUse(mlir::Operation *use);
/// Same actual ancestry query charged to caller work.
bool isGeneratedTypeUse(mlir::Operation *use, unsigned &remainingWork);
mlir::LogicalResult
verifyDigitalValueScope(mlir::Value value, mlir::Operation *use,
                        const ParameterIntegerEnvironment *environment = nullptr);
mlir::FailureOr<mlir::Value>
digitalRegionCaptureSource(mlir::BlockArgument argument,
                           const ParameterIntegerEnvironment *environment = nullptr);
mlir::LogicalResult
digitalRegionArgumentDependencies(mlir::BlockArgument argument,
                                  llvm::SmallVectorImpl<mlir::Value> &dependencies);
std::string digitalRegionSemanticPath(mlir::Block *body);
std::string digitalValueSemanticPath(mlir::Value value);
mlir::FailureOr<llvm::SmallVector<ParameterIntegerEnvironment, 4>>
digitalIntegerContextsAtUse(mlir::Operation *use);
mlir::FailureOr<ParameterIntegerEnvironment>
digitalGenerateIntegerContextAtUse(mlir::Operation *use);
} // namespace nodal
#endif
