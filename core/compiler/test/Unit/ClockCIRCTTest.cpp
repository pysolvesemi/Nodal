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
#include "nodal/Transforms/Passes.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/StringSet.h"
#include "llvm/Support/raw_ostream.h"
using namespace mlir;
namespace {
const std::string clockType = "!nodal.capability<\"clock\">";
const std::string resetType = "!nodal.capability<\"reset\">";
const std::string bit = "!nodal.bits<1>";
std::string meta(StringRef name) {
  return "metadata = {semantic_path = \"Fixture." + name.str() + "\"}";
}
std::string domain(StringRef name, StringRef edge, bool reset, StringRef parents = "") {
  return "\"nodal.domain\"() <{sym_name = \"" + name.str() + "\", edge = \"" + edge.str() +
         "\", reset_policy = \"" + (reset ? "async_assert_sync_release" : "none") + "\", " +
         parents.str() + "metadata = {}}> : () -> ()\n";
}
std::string port(StringRef name, StringRef type, StringRef dom, StringRef direction = "input") {
  return "\"nodal.port\"() <{sym_name = \"" + name.str() + "\", type = " + type.str() +
         ", domain = @" + dom.str() + ", direction = \"" + direction.str() + "\", " +
         meta(name.str() + "Port") + "}> : () -> ()\n%" + name.str() +
         " = \"nodal.port_value\"() <{port = @" + name.str() + ", " + meta(name) + "}> : () -> " +
         type.str() + "\n";
}
std::string literal(StringRef name, unsigned value) {
  return "%" + name.str() + " = \"nodal.const_literal\"() <{value = " + std::to_string(value) +
         " : i64, spelling = \"" + std::to_string(value) + "\", " + meta(name) + "}> : () -> " +
         bit + "\n";
}
std::string sync(StringRef name, StringRef clock, StringRef dom) {
  return "%" + name.str() + " = \"nodal.reset_sync\"(%raw, %" + clock.str() +
         ") <{destination_domain = @" + dom.str() +
         ", stages = 2 : i64, reset_polarity = \"active_high\", " + meta(name) + "}> : (" +
         resetType + ", " + clockType + ") -> " + resetType + "\n";
}
std::string binding(StringRef clock, StringRef dom, StringRef reset = "") {
  return "\"nodal.local_domain_binding\"(%" + clock.str() +
         (reset.empty() ? "" : ", %" + reset.str()) + ") <{domain = @" + dom.str() +
         ", reset_polarity = \"active_high\", metadata = {}}> : (" + clockType +
         (reset.empty() ? "" : ", " + resetType) + ") -> ()\n";
}
std::string state(StringRef name, StringRef dom, bool initialized = true) {
  return "%" + name.str() + " = \"nodal.digital_storage\"(" + (initialized ? "%zero" : "") +
         ") <{name = \"" + name.str() + "\", kind = \"register\", domain = @" + dom.str() + ", " +
         meta(name) + "}> : (" + (initialized ? bit : "") + ") -> " + bit + "\n";
}
std::string assign(StringRef target, StringRef rhs, bool next) {
  return "\"nodal.digital_assign\"(%" + target.str() + ", %" + rhs.str() + ") <{kind = \"" +
         (next ? "register_next" : "continuous") + "\", " + meta(target.str() + "Assign") +
         "}> : (" + bit + ", " + bit + ") -> ()\n";
}
std::string gate(StringRef name, StringRef clock, StringRef parent, StringRef edge,
                 StringRef enable = "enable") {
  return "%" + name.str() + " = \"nodal.clock_gate\"(%" + clock.str() + ", %" + enable.str() +
         ", %test) <{parent_domains = [@" + parent.str() + "], domain = @" + name.str() +
         ", edge = \"" + edge.str() +
         "\", contract = \"gate_v1\", control_domains = [@a, @a], "
         "obligations = {control_timing = \"inactive_phase_aperture\", startup = "
         "\"known_after_inactive_phase\"}, " +
         meta(name) + "}> : (" + clockType + ", " + bit + ", " + bit + ") -> " + clockType + "\n";
}
std::string wrap(StringRef body) {
  return "module {\"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({\n" + body.str() +
         "}) : () -> ()}\n";
}
std::string fixture(bool mux, StringRef edge = "rising", bool reset = true,
                    bool stateControl = false, bool duplicate = false) {
  std::string s =
      domain("a", edge, reset) +
      domain("g", edge, reset, mux ? "parent = @a, second_parent = @b, " : "parent = @a, ");
  if (mux)
    s += domain("b", edge, true);
  if (duplicate)
    s += domain("h", edge, reset, "parent = @a, ");
  s += port("clkA", clockType, "a") + port("enable", bit, "a") + port("test", bit, "a") +
       literal("zero", 0) + literal("one", 1);
  if (reset)
    s += port("rst", resetType, "a") +
         "%raw = \"nodal.reset_boundary\"(%rst) <{release = \"raw\", reset_polarity = "
         "\"active_high\", " +
         meta("raw") + "}> : (" + resetType + ") -> " + resetType + "\n" +
         sync("resetA", "clkA", "a");
  s += binding("clkA", "a", reset ? "resetA" : "");
  if (stateControl)
    s += state("enableState", "a", reset) + assign("enableState", "enable", true);
  if (mux) {
    s +=
        port("clkB", clockType, "b") + sync("resetB", "clkB", "b") + binding("clkB", "b", "resetB");
    s += "%g = \"nodal.clock_mux\"(%clkA, %clkB, %enable, %raw) <{parent_domains = [@a, @b], "
         "domain = @g, edge = \"" +
         edge.str() +
         "\", contract = \"glitchless_mux_v1\", "
         "control_domains = [@a], obligations = {control_sampling = \"level_coalescing\", "
         "switching = \"serialized_break_before_make\", reset_epoch = \"common_assertion\", "
         "pulse_integrity = \"except_reset_assertion\"}, " +
         meta("g") + "}> : (" + clockType + ", " + clockType + ", " + bit + ", " + resetType +
         ") -> " + clockType + "\n";
  } else {
    s += gate("g", "clkA", "a", edge, stateControl ? "enableState" : "enable");
  }
  if (reset)
    s += sync("resetG", "g", "g");
  s += binding("g", "g", reset ? "resetG" : "");
  if (duplicate) {
    s += gate("h", "clkA", "a", edge, stateControl ? "enableState" : "enable");
    if (reset)
      s += sync("resetH", "h", "h");
    s += binding("h", "h", reset ? "resetH" : "");
  }
  s += state("resultState", "g", reset) + assign("resultState", "one", true) +
       port("out", bit, "g", "output") + assign("out", "resultState", false);
  return wrap(s);
}
std::string print(Operation *op) {
  std::string text;
  llvm::raw_string_ostream stream(text);
  op->print(stream);
  return text;
}
// Interpret actual emitted combinational next-state cones, not a parallel mux
// model. Register cuts make the controller's old-state timing explicit.
uint64_t evaluate(Value value, const llvm::DenseMap<Operation *, uint64_t> &states,
                  uint64_t selection = 0) {
  if (isa<BlockArgument>(value))
    return selection;
  Operation *op = value.getDefiningOp();
  if (isa<circt::seq::FirRegOp>(op))
    return states.lookup(op);
  if (auto constant = dyn_cast<circt::hw::ConstantOp>(op))
    return constant.getValue().getZExtValue();
  auto arg = [&](unsigned i) { return evaluate(op->getOperand(i), states, selection); };
  StringRef name = op->getName().getStringRef();
  if (name == "hw.wire")
    return arg(0);
  if (name == "comb.mux")
    return arg(0) ? arg(1) : arg(2);
  if (name == "comb.xor")
    return arg(0) ^ arg(1);
  if (name == "comb.and")
    return arg(0) & arg(1);
  if (name == "comb.or")
    return arg(0) | arg(1);
  if (auto compare = dyn_cast<circt::comb::ICmpOp>(op))
    return compare.getPredicate() == circt::comb::ICmpPredicate::ne ? arg(0) != arg(1)
                                                                    : arg(0) == arg(1);
  llvm::report_fatal_error("unsupported owned clock test cone");
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
  auto report = [&](StringRef name, bool success) {
    ++cases;
    if (!success) {
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
    for (bool reset : {false, true}) {
      auto module = lower(fixture(false, edge, reset, true, true));
      report("gate-state-control-interleaved-reset-lowers", bool(module));
      if (!module)
        continue;
      unsigned gates = 0, wires = 0;
      llvm::StringSet<> symbols;
      module->walk([&](circt::seq::ClockGateOp op) {
        ++gates;
        symbols.insert(op.getInnerSymAttr().getSymName().getValue());
      });
      module->walk([&](circt::hw::WireOp op) {
        if (auto role = op->getAttrOfType<StringAttr>("nodal.clock_role");
            role && role.getValue() == "observable_output")
          ++wires;
      });
      report("gate-distinct-uninitialized-symbols-and-observable-wires",
             gates == 2 && wires == 2 && symbols.size() == 2);
      std::string before = print(*module);
      auto roundTrip = parseSourceString<mlir::ModuleOp>(before, &context);
      report("gate-target-round-trip", roundTrip && print(*roundTrip) == before);
      PassManager cse(&context);
      cse.addPass(createCSEPass());
      bool ok = succeeded(cse.run(*module));
      gates = 0;
      module->walk([&](circt::seq::ClockGateOp) { ++gates; });
      report("independent-gates-survive-cse", ok && gates == 2);
    }
    auto module = lower(fixture(true, edge));
    report("mux-interleaved-release-lowers", bool(module));
    if (!module)
      continue;
    unsigned bits = 0, count = 0, releases = 0, ordinaryMuxes = 0, assertions = 0;
    llvm::StringMap<circt::seq::FirRegOp> states;
    module->walk([&](Operation *op) {
      ordinaryMuxes += op->getName().getStringRef() == "seq.clock_mux";
      if (op->hasAttr("nodal.clock_check"))
        ++assertions;
      if (auto state = dyn_cast<circt::seq::FirRegOp>(op))
        if (auto role = op->getAttrOfType<StringAttr>("nodal.clock_role")) {
          ++count;
          bits += cast<IntegerType>(state.getResult().getType()).getWidth();
          releases += role.getValue().contains("release");
          states.try_emplace(role.getValue(), state);
        }
    });
    report("mux-exact-bounded-state-and-local-checks",
           count == 20 && bits == 22 && releases == 6 && ordinaryMuxes == 0 && assertions == 3);
    bool valid = true;
    llvm::DenseMap<Operation *, uint64_t> values;
    auto controller = states.lookup("controller"), selected = states.lookup("selected");
    auto select0 = states.lookup("select_0"), select1 = states.lookup("select_1");
    if (!controller || !selected || !select0 || !select1) {
      report("mux-controller-roles", false);
      continue;
    }
    // Two samples of initial true, then and only then the first request.
    for (unsigned edge = 0; edge != 3; ++edge) {
      uint64_t nextController = evaluate(controller.getNext(), values, 1);
      uint64_t nextSelected = evaluate(selected.getNext(), values, 1);
      uint64_t next0 = evaluate(select0.getNext(), values, 1);
      uint64_t next1 = evaluate(select1.getNext(), values, 1);
      valid &= nextController == edge + 1;
      valid &= nextSelected == unsigned(edge == 2);
      values[controller] = nextController;
      values[selected] = nextSelected;
      values[select0] = next0;
      values[select1] = next1;
    }
    report("mux-first-request-after-second-sample", valid);
    // WAIT_ON keeps the chosen request despite an opposite sampled selector.
    values[select1] = 0;
    values[controller] = 3;
    values[selected] = 1;
    valid =
        evaluate(controller.getNext(), values) == 3 && evaluate(selected.getNext(), values) == 1;
    values[states.lookup("branch1_ack_1")] = 1;
    valid &= evaluate(controller.getNext(), values) == 4;
    values[controller] = 4;
    valid &= evaluate(controller.getNext(), values) == 2;
    values[controller] = 2;
    valid &= evaluate(controller.getNext(), values) == 2;
    values[states.lookup("branch1_ack_1")] = 0;
    valid &=
        evaluate(controller.getNext(), values) == 3 && evaluate(selected.getNext(), values) == 0;
    report("mux-inflight-request-not-cancelled-before-on-then-off-ack", valid);
    std::string emitted = print(*module);
    report("mux-reset-exception-and-x-check-survive",
           StringRef(emitted).contains("except_reset_assertion") &&
               StringRef(emitted).contains("!== 1'b1") && StringRef(emitted).contains("!== 1'bx"));
  }
  llvm::outs() << "CLOCK_CIRCT_SUMMARY cases=" << cases << " failures=" << failures << '\n';
  return failures ? 1 : 0;
}
