#ifndef NODAL_DIALECT_NODAL_TARGETCAPABILITIES_H
#define NODAL_DIALECT_NODAL_TARGETCAPABILITIES_H

#include "mlir/IR/BuiltinOps.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/StringRef.h"

namespace nodal {

/// Classify this operation's semantics and actual carriers, including operands,
/// results, directly owned block arguments and recursively nested attributes and
/// types. Nested operations are classified separately by the caller's IR walk.
/// Builtin numeric types do not imply digital hardware; explicit Nodal digital
/// types do, including when nested in structural containers.
/// Interface identities alone are neutral; the module walk checks their actual
/// members, which can be conservative or signal-flow analog carriers.
bool hasDigitalTargetCapability(mlir::Operation *operation);

/// Read-only language gate over every definition and generated region in the
/// module, independent of declared design kind or root reachability. Verilog-A
/// rejects digital semantics; Verilog-AMS permits them at this boundary without
/// claiming that the backend can lower them. Unknown language profiles fail.
/// Backend lowering guards, including the source-equation boundary, remain
/// independent and authoritative after this check.
mlir::LogicalResult verifyTargetLanguageCapabilities(mlir::ModuleOp module,
                                                     llvm::StringRef languageProfile);

} // namespace nodal

#endif // NODAL_DIALECT_NODAL_TARGETCAPABILITIES_H
