#ifndef NODAL_TRANSFORMS_PASSES_H
#define NODAL_TRANSFORMS_PASSES_H

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/OwningOpRef.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/StringRef.h"

namespace nodal {

enum class GateProfile {
  Fast,
  Default,
  Release,
};

llvm::StringRef stringifyGateProfile(GateProfile profile);

/// Register every mandatory verifier pass and the named gate pipelines.
void registerNodalPasses();

/// Run one clone-before-commit verification transaction on `module`.
mlir::LogicalResult runNodalPipelineTransaction(mlir::ModuleOp module,
                                                GateProfile profile);

/// Retains the last accepted normalized module when a later candidate fails.
class PipelineSession {
public:
  explicit PipelineSession(mlir::MLIRContext *context);
  ~PipelineSession();

  mlir::LogicalResult accept(mlir::ModuleOp candidate, GateProfile profile);
  bool hasAccepted() const;
  mlir::ModuleOp getAccepted() const;
  mlir::OwningOpRef<mlir::ModuleOp> cloneAccepted() const;

private:
  mlir::MLIRContext *context;
  mlir::OwningOpRef<mlir::ModuleOp> accepted;
};

} // namespace nodal

#endif // NODAL_TRANSFORMS_PASSES_H
