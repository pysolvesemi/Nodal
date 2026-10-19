#ifndef NODAL_DIALECT_NODAL_DIGITALCOLLECTIONMODEL_H
#define NODAL_DIALECT_NODAL_DIGITALCOLLECTIONMODEL_H

#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/ADT/SmallVector.h"

#include <memory>
#include <string>

namespace nodal {
/// Header/scope-only checks never call full type, opcode or collection verification.
mlir::LogicalResult verifyCollectionValueScope(mlir::Value value, mlir::Operation *use);
mlir::LogicalResult verifyCollectionValueScope(mlir::Value value, mlir::Operation *use,
                                               const ParameterIntegerEnvironment *environment);
mlir::FailureOr<mlir::Value> digitalCollectionCaptureSource(mlir::BlockArgument argument);
mlir::FailureOr<mlir::Value>
digitalCollectionCaptureSource(mlir::BlockArgument argument,
                               const ParameterIntegerEnvironment *environment);
mlir::LogicalResult
digitalCollectionArgumentDependencies(mlir::BlockArgument argument,
                                      llvm::SmallVectorImpl<mlir::Value> &dependencies);
std::string digitalValueSemanticPath(mlir::Value value);

/// Resolve an actual local argument/static producer by its canonical identity.
/// This never searches outer blocks or treats a runtime i64 as a static value.
mlir::FailureOr<mlir::Value>
resolveCollectionIntegerTokenValue(mlir::Operation *module, llvm::StringRef token,
                                   llvm::StringRef requiredEffect,
                                   const ParameterIntegerEnvironment &environment);
mlir::FailureOr<InstantiatedIntegerExpression>
resolveCollectionIntegerToken(mlir::Operation *module, llvm::StringRef token,
                              llvm::StringRef requiredEffect,
                              const ParameterIntegerEnvironment &environment);

/// Conditional raw-header/transfer contexts for a collection or bounded-loop use.
/// These are never a completed reduction certificate. No global state is retained.
mlir::FailureOr<llvm::SmallVector<ParameterIntegerEnvironment, 4>>
collectionIntegerContextsAtUse(mlir::Operation *use);

/// A read-only exact dependent type: complete original skeleton and scalar widths
/// in selected-leaf order for reductions (all scalar leaves for body values),
/// with exact inner dimensions in shape postorder. No expression is written into IR.
struct DigitalReductionType {
  mlir::Type skeleton;
  llvm::SmallVector<InstantiatedIntegerExpression, 4> widths;
  llvm::SmallVector<InstantiatedIntegerExpression, 4> dimensions;
  int64_t maximumBits = 0;
};
struct DigitalReductionBodyType {
  mlir::Value value;
  DigitalReductionType type;
};
class VerifiedDigitalReduction {
public:
  mlir::Operation *operation() const;
  const InstantiatedIntegerExpression &count() const;

private:
  struct Storage;
  explicit VerifiedDigitalReduction(std::shared_ptr<const Storage> storage);
  std::shared_ptr<const Storage> storage;
  friend mlir::FailureOr<VerifiedDigitalReduction> analyzeDigitalReduction(mlir::Operation *);
  friend mlir::FailureOr<DigitalReductionType>
  digitalReductionFinalType(const VerifiedDigitalReduction &);
  friend mlir::FailureOr<DigitalReductionType>
  digitalReductionTypeAtPrefix(const VerifiedDigitalReduction &, int64_t);
  friend mlir::FailureOr<DigitalReductionType>
  digitalReductionTypeAtTreeNode(const VerifiedDigitalReduction &, unsigned, int64_t);
  friend mlir::FailureOr<llvm::SmallVector<DigitalReductionBodyType, 8>>
  instantiateDigitalReductionBodyTypes(const VerifiedDigitalReduction &, int64_t, int64_t);
  friend mlir::FailureOr<llvm::SmallVector<DigitalReductionBodyType, 8>>
  instantiateDigitalReductionBodyTypesAtTreeNode(const VerifiedDigitalReduction &, unsigned,
                                                 int64_t);
};
mlir::FailureOr<VerifiedDigitalReduction> analyzeDigitalReduction(mlir::Operation *operation);
mlir::FailureOr<DigitalReductionType>
digitalReductionFinalType(const VerifiedDigitalReduction &facts);
mlir::FailureOr<DigitalReductionType>
digitalReductionTypeAtPrefix(const VerifiedDigitalReduction &facts, int64_t elements);
mlir::FailureOr<DigitalReductionType>
digitalReductionTypeAtTreeNode(const VerifiedDigitalReduction &facts, unsigned level,
                               int64_t ordinal);
mlir::FailureOr<llvm::SmallVector<DigitalReductionBodyType, 8>>
instantiateDigitalReductionBodyTypes(const VerifiedDigitalReduction &facts, int64_t leftElements,
                                     int64_t rightElements);

/// A guaranteed-present actual combine; odd carry nodes have no body. Symbolic
/// tail counts retain their exact checked expression instead of endpoint types.
mlir::FailureOr<llvm::SmallVector<DigitalReductionBodyType, 8>>
instantiateDigitalReductionBodyTypesAtTreeNode(const VerifiedDigitalReduction &facts,
                                               unsigned level, int64_t ordinal);

mlir::LogicalResult verifyDigitalCollectionOperation(mlir::Operation *operation);
mlir::LogicalResult verifyDigitalCollectionOperation(mlir::Operation *operation,
                                                     const ParameterIntegerEnvironment *enclosing);
mlir::LogicalResult verifyDigitalCollectionYield(mlir::Operation *operation);
mlir::LogicalResult verifyDigitalCollectionModel(mlir::Operation *module);
} // namespace nodal
#endif
