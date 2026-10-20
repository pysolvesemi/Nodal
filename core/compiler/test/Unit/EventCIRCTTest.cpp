#include "EventTestFixtures.h"
#include "circt/Dialect/Comb/CombDialect.h"
#include "circt/Dialect/Comb/CombOps.h"
#include "circt/Dialect/HW/HWDialect.h"
#include "circt/Dialect/HW/HWOps.h"
#include "circt/Dialect/SV/SVDialect.h"
#include "circt/Dialect/SV/SVOps.h"
#include "circt/Dialect/Seq/SeqDialect.h"
#include "circt/Dialect/Seq/SeqOps.h"
#include "circt/Dialect/Verif/VerifDialect.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "mlir/Pass/PassManager.h"
#include "mlir/Transforms/Passes.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Transforms/Passes.h"

#include "llvm/ADT/StringMap.h"
#include "llvm/Support/raw_ostream.h"

using namespace mlir;
using namespace event_test;
namespace {
std::string print(Operation *op) {
  std::string result;
  llvm::raw_string_ostream out(result);
  op->print(out);
  return result;
}
std::string select(StringRef name, StringRef first, StringRef old, StringRef type) {
  return "%" + name.str() + " = \"nodal.digital_expr\"(%seed, %" + first.str() + ", %" + old.str() +
         ") <{operator_name = \"digital_select\", metadata = {}}> : (" + bit + ", " + type.str() +
         ", " + type.str() + ") -> " + type.str() + "\n";
}
std::string pair(StringRef edge, std::string type = "!nodal.uint<8>") {
  return wrap(
      port("trigger") + port("seed") + port("first", "input", type) +
      port("second", "input", type) + port("left", "output", type) + port("right", "output", type) +
      "%oldLeft = \"nodal.port_value\"() <{port = @left, metadata = {}}> : () -> " + type + "\n" +
      process("event", edge.str(), "%left, %right", "%seed, %first, %second", 2, 3,
              type + ", " + type, bit + ", " + type + ", " + type) +
      select("nextLeft", "first", "right", type) + select("nextRight", "second", "oldLeft", type) +
      update("nextLeft", "left", "event", type) + update("nextRight", "right", "event", type));
}
} // namespace

int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect, circt::hw::HWDialect, circt::comb::CombDialect,
                  circt::seq::SeqDialect, circt::sv::SVDialect, circt::verif::VerifDialect>();
  MLIRContext context(registry);
  context.loadAllAvailableDialects();
  std::string diagnostics;
  ScopedDiagnosticHandler handler(&context, [&](Diagnostic &d) {
    llvm::raw_string_ostream out(diagnostics);
    d.print(out);
    out << '\n';
    return success();
  });
  unsigned cases = 0, failures = 0;
  auto report = [&](StringRef name, bool good) {
    ++cases;
    if (!good) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostics;
    }
  };
  auto lower = [&](const std::string &source) -> OwningOpRef<mlir::ModuleOp> {
    diagnostics.clear();
    auto module = parseSourceString<mlir::ModuleOp>(source, &context);
    if (!module)
      return {};
    PassManager pm(&context);
    pm.addPass(nodal::createSequentialToCIRCTPass());
    if (failed(pm.run(*module)))
      return {};
    return module;
  };
  for (StringRef edge : {"rising", "falling"}) {
    auto module =
        lower(wrap(replace(fixture(), "edge = \"rising\"", "edge = \"" + edge.str() + "\"")));
    report("frozen-bool-event-lowers", bool(module));
    if (!module)
      continue;
    unsigned states = 0, clocks = 0, inverters = 0, comments = 0;
    bool valid = true;
    module->walk([&](circt::seq::CompRegOp op) {
      ++states;
      valid &= op->getNumOperands() == 2 && op->hasAttr("inner_sym") &&
               op->getAttrOfType<StringAttr>("nodal.event_process").getValue() == "Fixture.event" &&
               op->getAttrOfType<DictionaryAttr>("nodal.event_obligations")
                       .getAs<StringAttr>("startup")
                       .getValue() == "unspecified";
      auto input = op->getOperand(0).getDefiningOp<circt::hw::ConstantOp>();
      valid &= input && input.getValue().getZExtValue() == 1;
    });
    module->walk([&](circt::seq::ToClockOp) { ++clocks; });
    module->walk([&](circt::seq::ClockInverterOp) { ++inverters; });
    module->walk([&](circt::sv::VerbatimOp op) {
      if (op->hasAttr("nodal.event_process")) {
        ++comments;
        std::string rendered = print(op);
        auto location = dyn_cast<FileLineColLoc>(op->getLoc());
        valid &= StringRef(rendered).contains("known_levels_definite_edges") &&
                 StringRef(rendered).contains("user_setup_hold") && location &&
                 location.getFilename().getValue() == "EventFixture.scala" &&
                 location.getLine() == 12;
      }
    });
    report("private-edge-resetless-uninitialized-state-and-obligations",
           valid && states == 1 && clocks == 1 && inverters == unsigned(edge == "falling") &&
               comments == 1);
    std::string rendered = print(*module);
    auto reparse = parseSourceString<mlir::ModuleOp>(rendered, &context);
    report("event-target-round-trip", reparse && print(*reparse) == rendered);
  }
  const std::string vector = "!nodal.shaped<\"4\", !nodal.bits<1>>";
  const std::string record = "!nodal.struct<\"Packet\", [{name = \"flag\", type = !nodal.bits<1>}, "
                             "{name = \"payload\", type = !nodal.uint<7>}]>";
  for (const std::string &type : {std::string("!nodal.uint<8>"), vector, record}) {
    auto module = lower(pair("falling", type));
    report("pair-whole-scalar-or-packed-select-lowers", bool(module));
    if (!module)
      continue;
    SmallVector<circt::seq::CompRegOp> states;
    module->walk([&](circt::seq::CompRegOp op) { states.push_back(op); });
    bool valid = states.size() == 2;
    if (valid) {
      auto left = states[0]->getOperand(0).getDefiningOp<circt::comb::MuxOp>();
      auto right = states[1]->getOperand(0).getDefiningOp<circt::comb::MuxOp>();
      // Inspect emitted cones: the aliases refer to actual state outputs,
      // never the other assignment's newly calculated value.
      valid &= left && right && left->getOperand(2) == states[1].getResult() &&
               right->getOperand(2) == states[0].getResult() &&
               left->getOperand(1) != right->getOperand(1);
      if (type != "!nodal.uint<8>")
        valid &= states[0]->hasAttr("nodal.packed_abi") &&
                 states[1]->getAttr("nodal.packed_abi") == states[0]->getAttr("nodal.packed_abi");
    }
    report("simultaneous-pre-event-updates-and-canonical-port-aliases", valid);
    PassManager cse(&context);
    cse.addPass(createCSEPass());
    bool good = succeeded(cse.run(*module));
    unsigned count = 0;
    module->walk([&](circt::seq::CompRegOp) { ++count; });
    report("event-physical-storage-identities-survive-cse", good && count == 2);
  }
  auto negative = [&](StringRef name, const std::string &source, StringRef expected) {
    diagnostics.clear();
    auto module = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    bool good = false;
    if (module) {
      std::string before = print(*module);
      PassManager pm(&context);
      pm.enableVerifier(false);
      pm.addPass(nodal::createSequentialToCIRCTPass());
      good = failed(pm.run(*module)) && print(*module) == before &&
             StringRef(diagnostics).contains(expected);
    }
    report(name, good);
  };
  negative("forged-edge-rejects-transactionally",
           wrap(replace(fixture(), "edge = \"rising\"", "edge = \"either\"")), "NODAL-EVENT");
  negative("descriptor-only-root-rejects-transactionally",
           replace(wrap(fixture()), "nodal.root.module = @Fixture", "nodal.root_only = true"),
           "NODAL-EVENT");
  negative("duplicate-event-writer-rejects-transactionally", wrap(fixture() + update()),
           "overlapping");
  negative("native-pure-map-target-boundary-preserves-original",
           wrap(setup(vector) + port("input", "input", vector) +
                process("event", "rising", "%output", "%input", 1, 1, vector, vector) +
                "%mapped = \"nodal.collection\"(%input) <{kind = \"map\", region_id = "
                "\"Fixture.map\", operandSegmentSizes = array<i32: 1, 0, 0>, metadata = "
                "{semantic_path = \"Fixture.map\"}}> ({\n"
                "^bb0(%element: " +
                bit +
                "):\n%value = \"nodal.digital_expr\"(%element) <{operator_name = \"bool_not\", "
                "metadata = {}}> : (" +
                bit + ") -> " + bit + "\n\"nodal.collection_yield\"(%value) <{metadata = {}}> : (" +
                bit + ") -> ()\n}) : (" + vector + ") -> " + vector + "\n" +
                update("mapped", "output", "event", vector)),
           "flat normalized scalar operations");
  // Native admission allows bounded pure projections, but this target's finite
  // whole-value ABI deliberately has no payload-index lowering.
  negative("unsupported-payload-projection-preserves-original",
           wrap(setup() + port("input", "input", vector) +
                process("event", "rising", "%output", "%input", 1, 1, bit, vector) +
                "%index = \"nodal.constant\"() <{value = 0 : index, metadata = {}}> : () -> index\n"
                "%lane = \"nodal.shape_index\"(%input, %index) <{metadata = {}}> : (" +
                vector + ", index) -> " + bit + "\n" + update("lane")),
           "NODAL-CIRCT");
  llvm::outs() << "EVENT_CIRCT_SUMMARY cases=" << cases << " failures=" << failures << '\n';
  return failures ? 1 : 0;
}
