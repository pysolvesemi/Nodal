#ifndef NODAL_DIALECT_NODAL_POTENTIALFLOWACCESS_H
#define NODAL_DIALECT_NODAL_POTENTIALFLOWACCESS_H

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Operation.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/StringRef.h"

#include <string>

namespace nodal {

struct ResolvedAccessNature {
  mlir::Operation *discipline = nullptr;
  mlir::Operation *nature = nullptr;
  std::string canonicalDiscipline;
  std::string canonicalNature;
  std::string accessFunction;
  std::string dimension;
};

/// Resolve one potential or flow nature through discipline and nature imports.
/// Failures are reported on `scope` with an Increment 31 stable diagnostic.
mlir::FailureOr<ResolvedAccessNature> resolvePotentialFlowAccessNature(mlir::Operation *scope,
                                                                       llvm::StringRef discipline,
                                                                       llvm::StringRef kind);

/// Verify one source-semantic access or compiler-owned probe operation.
mlir::LogicalResult verifyPotentialFlowAccessOperation(mlir::Operation *operation);

/// Normalize one-terminal references and source-free probe records. The
/// transformation is deterministic and idempotent.
mlir::LogicalResult normalizePotentialFlowAccess(mlir::ModuleOp module);

/// Verify the complete normalized access/probe model without mutating it.
mlir::LogicalResult verifyPotentialFlowAccessModel(mlir::ModuleOp module);

} // namespace nodal

#endif // NODAL_DIALECT_NODAL_POTENTIALFLOWACCESS_H
