#include "WaiverTestFixtures.h"
#include "circt/Dialect/Comb/CombDialect.h"
#include "circt/Dialect/HW/HWDialect.h"
#include "circt/Dialect/HW/HWOps.h"
#include "circt/Dialect/SV/SVDialect.h"
#include "circt/Dialect/SV/SVOps.h"
#include "circt/Dialect/Seq/SeqDialect.h"
#include "circt/Dialect/Verif/VerifDialect.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Pass/PassManager.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Transforms/Passes.h"

#include "llvm/Support/raw_ostream.h"

#include <filesystem>
#include <fstream>
using namespace mlir;
using namespace waiver_test;
namespace {
std::string print(Operation *op) {
  std::string result;
  llvm::raw_string_ostream stream(result);
  op->print(stream);
  return result;
}
LogicalResult lower(mlir::ModuleOp module) {
  PassManager pm(module.getContext());
  pm.addPass(nodal::createSequentialToCIRCTPass());
  return pm.run(module);
}
Value identityRoot(Value value) {
  while (auto wire = value.getDefiningOp<circt::hw::WireOp>())
    value = wire.getInput();
  return value;
}
} // namespace
int main(int argc, char **argv) {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect, circt::hw::HWDialect, circt::comb::CombDialect,
                  circt::seq::SeqDialect, circt::sv::SVDialect, circt::verif::VerifDialect>();
  MLIRContext context(registry);
  std::string diagnostics;
  ScopedDiagnosticHandler handler(&context, [&](Diagnostic &d) {
    llvm::raw_string_ostream stream(diagnostics);
    d.print(stream);
    stream << '\n';
    return success();
  });
  unsigned cases = 0, failures = 0;
  auto report = [&](StringRef name, bool ok) {
    ++cases;
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostics;
    }
  };
  for (const auto &[name, type] : std::vector<std::pair<std::string, std::string>>{
           {"scalar", bit},
           {"aggregate", "!nodal.shaped<\"3\", !nodal.uint<8>>"},
           {"structure", "!nodal.struct<\"Payload\", [{name = \"valid\", type = !nodal.bits<1>}, "
                         "{name = \"data\", type = !nodal.uint<7>}]>"}}) {
    diagnostics.clear();
    std::string source =
        wrap(setup(type) + waiver("waived", "input", "a", "b", type) + output("waived", type));
    // Preserve exact adversarial text in the record, never in target syntax.
    source = replace(source, "explicit external boundary responsibility",
                     "line\\0A*/ {{0}} \\E2\\80\\A8 \\C3\\A9 \\5C end");
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    if (!parsed || failed(verify(*parsed))) {
      report(name + "-source-verification", false);
      continue;
    }
    auto original = *parsed->getOps<nodal::ModuleOp>().begin();
    auto waiverOp = *original.getOps<nodal::WaiverOp>().begin();
    auto attributes = waiverOp->getAttrDictionary();
    report(name + "-lower", succeeded(lower(*parsed)));
    auto target = parsed->getOps<circt::hw::HWModuleOp>();
    if (target.empty()) {
      report(name + "-target-module", false);
      continue;
    }
    auto hw = *target.begin();
    auto terminator = dyn_cast<circt::hw::OutputOp>(hw.getBodyBlock()->getTerminator());
    Value actualInput;
    for (auto arg : hw.getBodyBlock()->getArguments())
      if (hw.getPortList()[hw.getPortIdForInputId(arg.getArgNumber())].name.getValue() == "input")
        actualInput = arg;
    report(name + "-actual-identity", actualInput && terminator &&
                                          terminator.getNumOperands() == 1 &&
                                          identityRoot(terminator.getOperand(0)) == actualInput);
    bool noState = true, retained = false, escaped = false;
    hw.walk([&](Operation *op) {
      StringRef n = op->getName().getStringRef();
      noState &= !n.starts_with("seq.") && n != "sv.reg" && n != "sv.always" &&
                 n != "sv.alwaysff" && n != "sv.initial";
      if (op->hasAttr("nodal.waiver")) {
        retained = op->getAttrOfType<DictionaryAttr>("nodal.waiver") == attributes;
        auto comment = op->getAttrOfType<StringAttr>("format_string");
        StringRef text = comment ? comment.getValue() : StringRef();
        escaped = text.starts_with("// NODAL stateless waiver:") && !text.contains('\n') &&
                  !text.contains('\r') && !text.contains("*/") && !text.contains("{{") &&
                  llvm::all_of(text, [](unsigned char c) { return c < 127; });
      }
    });
    report(name + "-no-state-reset-monitor", noState);
    report(name + "-exact-obligations-source", retained);
    report(name + "-safe-authored-text", escaped);
    if (argc == 2) {
      std::filesystem::create_directories(argv[1]);
      std::ofstream(std::string(argv[1]) + "/" + name + ".mlir") << source;
      std::ofstream(std::string(argv[1]) + "/" + name + ".circt.mlir") << print(*parsed);
    }
  }
  const std::string body = setup() + waiver() + output();
  auto invalid = parseSourceString<mlir::ModuleOp>(wrap(body), ParserConfig(&context, false));
  if (invalid) {
    auto owner = *invalid->getOps<nodal::ModuleOp>().begin();
    auto op = *owner.getOps<nodal::WaiverOp>().begin();
    op->setAttr("contract", StringAttr::get(&context, "forged"));
    std::string before = print(*invalid);
    report("malformed-rejection-transactional",
           failed(lower(*invalid)) && print(*invalid) == before);
  }
  llvm::outs() << "WAIVER_CIRCT_CASES " << cases << " FAILURES " << failures << '\n';
  return failures ? 1 : 0;
}
