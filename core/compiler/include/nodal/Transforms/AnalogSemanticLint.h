#ifndef NODAL_TRANSFORMS_ANALOGSEMANTICLINT_H
#define NODAL_TRANSFORMS_ANALOGSEMANTICLINT_H

#include "mlir/IR/BuiltinOps.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/SmallVector.h"

#include <memory>
#include <string>

namespace nodal {

/// One finding about an actual source operation. The operation remains owned by
/// the caller; collecting issues neither rewrites IR nor installs annotations.
struct AnalogSemanticLintIssue {
  mlir::Operation *operation;
  std::string code;
  std::string message;
  bool isError = false;
};

/// Collect the finite F46 source-risk profile from already verified IR. Callers
/// must first run native, parameter, analog numeric and F32 inventory verification.
/// Invalid inputs remain verifier errors, never warnings. Advisories follow
/// authored IR pre-order with stable category order. Structural findings follow
/// the shared typed model's deterministic component/analysis order. The complete
/// finite algebraic profile checks equation counts and incidence matching, not
/// numerical rank, solution existence, topology expansion or residual/DAE solving.
mlir::FailureOr<llvm::SmallVector<AnalogSemanticLintIssue>>
collectAnalogSemanticLint(mlir::ModuleOp module);

/// Independently verifies input before emitting mapped advisories and structural
/// errors. Structural errors fail the pass; collecting lint never changes IR.
std::unique_ptr<mlir::Pass> createAnalogSemanticLintPass();
/// Independently verifies and recomputes a diagnostic-only derived report. A
/// well-formed defective graph may be reported; this is not target acceptance.
std::unique_ptr<mlir::Pass> createReportAnalogStructurePass();
/// Registers both the read-only lint pass and the opt-in structure report pass.
void registerAnalogSemanticLintPass();

} // namespace nodal

#endif // NODAL_TRANSFORMS_ANALOGSEMANTICLINT_H
