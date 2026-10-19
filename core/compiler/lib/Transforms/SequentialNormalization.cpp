#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Pass/PassRegistry.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"
using namespace mlir;
namespace nodal {
namespace {
class NormalizeSequentialPass final
    : public PassWrapper<NormalizeSequentialPass, OperationPass<mlir::ModuleOp>> {
public:
  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(NormalizeSequentialPass)
  StringRef getArgument() const final { return "nodal-normalize-sequential"; }
  StringRef getDescription() const final {
    return "Normalize owned register priority/reset and guarded ordered memory ports";
  }
  void runOnOperation() final {
    OwningOpRef<mlir::ModuleOp> candidate = llvm::cast<mlir::ModuleOp>(getOperation()->clone());
    SmallVector<nodal::ModuleOp, 8> owners;
    candidate->walk<WalkOrder::PostOrder>(
        [&](nodal::ModuleOp module) { owners.push_back(module); });
    // Normalize a stable list on a private whole-module candidate, never swap a
    // body during an active walk or publish partial success from earlier modules.
    for (nodal::ModuleOp module : owners)
      if (failed(normalizeSequentialModel(module))) {
        signalPassFailure();
        return;
      }
    if (failed(verify(*candidate))) {
      signalPassFailure();
      return;
    }
    getOperation().getBodyRegion().takeBody(candidate->getBodyRegion());
  }
};
} // namespace
std::unique_ptr<Pass> createNormalizeSequentialPass() {
  return std::make_unique<NormalizeSequentialPass>();
}
void registerSequentialNormalizationPass() {
  static PassRegistration<NormalizeSequentialPass> registration;
}
} // namespace nodal
