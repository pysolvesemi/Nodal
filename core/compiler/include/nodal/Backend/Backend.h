#ifndef NODAL_BACKEND_BACKEND_H
#define NODAL_BACKEND_BACKEND_H

#include "mlir/IR/BuiltinOps.h"
#include "mlir/Support/LogicalResult.h"
#include "nodal/Transforms/Passes.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/raw_ostream.h"

namespace nodal {

enum class BackendKind {
  VerilogA,
  VerilogAMS,
};

enum class ShapedValueLayout {
  ScalarOrFlat,
  FlatPacked,
};

enum class MaterializationPolicy {
  SafeInline,
  Readable,
};

enum class NamingPolicy {
  Semantic,
};

struct BackendProfile {
  BackendKind kind;
  llvm::StringRef id;
  llvm::StringRef translation;
  ShapedValueLayout shapedValueLayout;
  MaterializationPolicy materialization;
  NamingPolicy naming;
  GateProfile defaultCheckProfile;
  bool supportsAnalog;
  bool supportsMixedSignal;
};

struct BackendConfiguration {
  const BackendProfile *profile;
  GateProfile checkProfile;
  ShapedValueLayout shapedValueLayout;
  MaterializationPolicy materialization;
  NamingPolicy naming;
};

class TargetVerificationHooks {
public:
  virtual ~TargetVerificationHooks() = default;

  virtual mlir::LogicalResult verifyTarget(llvm::StringRef candidate,
                                           const BackendConfiguration &configuration) const = 0;
  virtual mlir::LogicalResult reparseTarget(llvm::StringRef candidate,
                                            const BackendConfiguration &configuration) const = 0;
};

const BackendProfile &getBackendProfile(BackendKind kind);
llvm::StringRef stringifyBackendKind(BackendKind kind);
llvm::StringRef stringifyShapedValueLayout(ShapedValueLayout layout);
llvm::StringRef stringifyMaterializationPolicy(MaterializationPolicy policy);
llvm::StringRef stringifyNamingPolicy(NamingPolicy policy);

mlir::FailureOr<BackendConfiguration> resolveBackendConfiguration(mlir::ModuleOp module,
                                                                  BackendKind kind);

const TargetVerificationHooks &getBuiltinTargetVerificationHooks();

/// Clone, semantically verify, capability-check, render, target-verify,
/// structurally reparse, and only then publish deterministic target bytes.
mlir::LogicalResult emitBackend(mlir::ModuleOp module, BackendKind kind, llvm::raw_ostream &output,
                                const TargetVerificationHooks *hooks = nullptr);

/// Register the built-in `nodal-to-verilog-a` and
/// `nodal-to-verilog-ams` MLIR translations.
void registerNodalBackendTranslations();

} // namespace nodal

#endif // NODAL_BACKEND_BACKEND_H
