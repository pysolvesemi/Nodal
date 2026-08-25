//===- Verification.h - Nodal staged semantic verification -----*- C++ -*-===//
//
// SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
//
//===----------------------------------------------------------------------===//

#ifndef NODAL_TRANSFORMS_VERIFICATION_H
#define NODAL_TRANSFORMS_VERIFICATION_H

#include "mlir/IR/BuiltinOps.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Support/LogicalResult.h"

#include "llvm/ADT/StringRef.h"

#include <memory>
#include <optional>
#include <string>

namespace nodal {

enum class VerificationStage {
  Construction,
  Hierarchy,
  Connectivity,
  TypeShape,
  ParameterLoop,
  EnumFsm,
  Domain,
  ProtocolPipeline,
  MemoryEffect,
  AnalogMixed,
  TargetCapability,
};

llvm::StringRef stringifyVerificationStage(VerificationStage stage);
std::optional<VerificationStage> symbolizeVerificationStage(llvm::StringRef value);

mlir::LogicalResult verifyNodalStage(mlir::ModuleOp module, VerificationStage stage,
                                     llvm::StringRef target = "core");
mlir::LogicalResult verifyNodalPipeline(mlir::ModuleOp module,
                                        llvm::StringRef target = "core");

class VerificationSession {
public:
  mlir::LogicalResult accept(mlir::ModuleOp candidate,
                             llvm::StringRef target = "core");

  const std::optional<std::string> &getAcceptedIR() const { return acceptedIR; }

private:
  std::optional<std::string> acceptedIR;
};

std::unique_ptr<mlir::Pass> createNodalVerifyStagePass();
std::unique_ptr<mlir::Pass> createNodalTransactionalGatePass();
void registerNodalPasses();

} // namespace nodal

#endif // NODAL_TRANSFORMS_VERIFICATION_H
