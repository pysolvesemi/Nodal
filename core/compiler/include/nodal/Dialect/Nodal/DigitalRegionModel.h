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
bool isDigitalRegionOwner(mlir::Operation *operation);
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
