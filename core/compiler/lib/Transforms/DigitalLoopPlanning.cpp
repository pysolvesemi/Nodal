#include "mlir/Pass/Pass.h"
#include "mlir/Pass/PassRegistry.h"
#include "nodal/Dialect/Nodal/DigitalLoopModel.h"
#include "nodal/Dialect/Nodal/DigitalLoopPlanningModel.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
using namespace mlir;
namespace nodal {
namespace {
class PlanDigitalLoopsPass final
    : public PassWrapper<PlanDigitalLoopsPass, OperationPass<mlir::ModuleOp>> {
public:
  MLIR_DEFINE_EXPLICIT_INTERNAL_INLINE_TYPE_ID(PlanDigitalLoopsPass)
  StringRef getArgument() const final { return "nodal-plan-digital-loops"; }
  StringRef getDescription() const final {
    return "Build checked ordered unrolled/procedural digital plans";
  }
  void runOnOperation() final {
    SmallVector<std::pair<Operation *, DictionaryAttr>> reports;
    bool invalid = false;
    getOperation().walk([&](nodal::ModuleOp module) {
      if (invalid)
        return;
      if (failed(verifyDigitalLoopPlanningReport(module))) {
        invalid = true;
        return;
      }
      bool loops = false;
      for (Operation &op : module->getRegion(0).front())
        loops |= isBoundedDigitalLoop(&op);
      if (!loops)
        return;
      auto report = analyzeDigitalLoopPlans(module);
      if (failed(report)) {
        invalid = true;
        return;
      }
      reports.emplace_back(module, *report);
    });
    if (invalid) {
      signalPassFailure();
      return;
    }
    for (auto [module, report] : reports)
      module->setAttr("nodal.digital.loop_plans", report);
  }
};
} // namespace
std::unique_ptr<Pass> createPlanDigitalLoopsPass() {
  return std::make_unique<PlanDigitalLoopsPass>();
}
void registerDigitalLoopPlanningPass() {
  static PassRegistration<PlanDigitalLoopsPass> registration;
}
} // namespace nodal
