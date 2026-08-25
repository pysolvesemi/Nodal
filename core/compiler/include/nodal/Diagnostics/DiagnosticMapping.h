#ifndef NODAL_DIAGNOSTICS_DIAGNOSTICMAPPING_H
#define NODAL_DIAGNOSTICS_DIAGNOSTICMAPPING_H

#include "mlir/IR/BuiltinOps.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/ADT/Twine.h"

#include <memory>
#include <string>

namespace nodal {

struct DiagnosticContext {
  std::string semanticPath;
  std::string hierarchyPath;
  std::string indexPath;
  std::string sourceRange;
};

/// Collect deterministic source-semantic context from an operation and its
/// ancestors. Missing context remains empty rather than being invented.
DiagnosticContext collectDiagnosticContext(mlir::Operation *operation);

/// Emit one stable-code error with semantic, hierarchy, index, and Scala source
/// context appended in a deterministic order.
mlir::LogicalResult emitMappedFailure(mlir::Operation *operation,
                                      llvm::StringRef code,
                                      const llvm::Twine &message);

/// Emit an inventory-derived error by resolving its semantic path back to an
/// operation or the module-level Increment 20 source-map inventory.
mlir::LogicalResult emitMappedFailureForPath(mlir::ModuleOp module,
                                             llvm::StringRef semanticPath,
                                             llvm::StringRef code,
                                             const llvm::Twine &message);

/// Create/register the private Increment 22 cross-layer diagnostic verifier.
std::unique_ptr<mlir::Pass> createCrossLayerDiagnosticPass();
void registerNodalDiagnosticPasses();

} // namespace nodal

#endif // NODAL_DIAGNOSTICS_DIAGNOSTICMAPPING_H
