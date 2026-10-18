#ifndef NODAL_DIAGNOSTICS_DIAGNOSTICSUPPORT_H
#define NODAL_DIAGNOSTICS_DIAGNOSTICSUPPORT_H

#include "mlir/IR/BuiltinOps.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/ADT/Twine.h"

#include <cstdint>
#include <string>

namespace nodal {

/// Authored, one-based source coordinates. These never come from MLIR transport
/// locations and do not promise target subexpression precision.
struct AuthoredSourceSpan {
  std::string semanticPath;
  std::string sourcePath;
  int64_t startLine;
  int64_t startColumn;
  int64_t endLine;
  int64_t endColumn;
};

/// Validated index of one root's bridge source inventory. Construct once per
/// rendering transaction; resolve only an exact operation's own semantic path.
/// Missing maps/spans are allowed, but malformed or duplicate entries fail.
class AuthoredSourceMap {
public:
  static mlir::FailureOr<AuthoredSourceMap> create(mlir::ModuleOp root);
  const AuthoredSourceSpan *resolve(mlir::Operation *operation) const;

private:
  explicit AuthoredSourceMap(mlir::ModuleOp root) : root(root) {}
  mlir::ModuleOp root;
  llvm::StringMap<AuthoredSourceSpan> spans;
};

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
mlir::LogicalResult emitMappedFailure(mlir::Operation *operation, llvm::StringRef code,
                                      const llvm::Twine &message);

/// Emit an advisory without changing acceptance or weakening mandatory errors.
/// Context uses exactly the same source-semantic mapping as mapped failures.
void emitMappedWarning(mlir::Operation *operation, llvm::StringRef code,
                       const llvm::Twine &message);

/// Emit an inventory-derived error by resolving its semantic path back to an
/// operation or the module-level Increment 20 source-map inventory.
mlir::LogicalResult emitMappedFailureForPath(mlir::ModuleOp module, llvm::StringRef semanticPath,
                                             llvm::StringRef code, const llvm::Twine &message);

} // namespace nodal

#endif // NODAL_DIAGNOSTICS_DIAGNOSTICSUPPORT_H
