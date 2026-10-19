#include "mlir/IR/BuiltinOps.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Pass/PassRegistry.h"
#include "nodal/Dialect/Nodal/DigitalMaterializationModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/SmallVector.h"

using namespace mlir;

namespace nodal {
namespace {
class PlanDigitalMaterializationPass final
    : public PassWrapper<PlanDigitalMaterializationPass, OperationPass<mlir::ModuleOp>> {
public:
  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(PlanDigitalMaterializationPass)

  StringRef getArgument() const final { return "nodal-plan-digital-materialization"; }
  StringRef getDescription() const final {
    return "Record verified target-neutral decisions from actual digital SSA uses";
  }

  void runOnOperation() final {
    auto root = getOperation();
    llvm::SmallVector<std::pair<Operation *, DictionaryAttr>> reports;
    bool rejected = false;
    root.walk([&](nodal::ModuleOp owner) {
      if (rejected)
        return;
      // Reject stale input rather than silently replacing its purported receipt.
      if (failed(verifyDigitalMaterializationReport(owner))) {
        rejected = true;
        return;
      }
      if (!root->hasAttr("nodal.digital.quality"))
        return;
      auto report = analyzeDigitalMaterialization(owner);
      if (failed(report)) {
        rejected = true;
        return;
      }
      reports.emplace_back(owner, *report);
    });
    if (rejected) {
      signalPassFailure();
      return;
    }
    // Build all reports successfully before changing any owner.
    for (const auto &[owner, report] : reports)
      owner->setAttr("nodal.digital.materialization", report);
  }
};
} // namespace

std::unique_ptr<Pass> createPlanDigitalMaterializationPass() {
  return std::make_unique<PlanDigitalMaterializationPass>();
}

void registerDigitalMaterializationPass() {
  static PassRegistration<PlanDigitalMaterializationPass> registration;
}
} // namespace nodal
