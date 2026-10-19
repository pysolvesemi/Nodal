#include "circt/Dialect/Comb/CombDialect.h"
#include "circt/Dialect/Comb/CombOps.h"
#include "circt/Dialect/HW/HWDialect.h"
#include "circt/Dialect/HW/HWOps.h"
#include "circt/Dialect/Seq/SeqDialect.h"
#include "circt/Dialect/Seq/SeqOps.h"
#include "circt/Dialect/Verif/VerifDialect.h"
#include "circt/Dialect/Verif/VerifOps.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Pass/PassManager.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Transforms/Passes.h"

#include "llvm/ADT/DenseMap.h"
#include "llvm/Support/Signals.h"
#include "llvm/Support/raw_ostream.h"

#include <fstream>
#include <map>
#include <string>
using namespace mlir;
namespace {
const std::string u8 = "!nodal.uint<8>", u9 = "!nodal.uint<9>", bit = "!nodal.bits<1>";
std::string metadata(std::string name) {
  return "metadata = {semantic_path = \"Fixture." + name + "\"}";
}
std::string literal(std::string name, int64_t value, std::string type = u8) {
  return "%" + name + " = \"nodal.const_literal\"() <{value = " + std::to_string(value) +
         ": i64, spelling = \"" + std::to_string(value) + "\", " + metadata(name) + "}> : () -> " +
         type + "\n";
}
std::string port(std::string name, std::string type = u8, std::string direction = "input") {
  return "\"nodal.port\"() <{sym_name = \"" + name + "\", type = " + type + ", direction = \"" +
         direction + "\", domain = @core, " + metadata(name + "Port") + "}> : () -> ()\n%" + name +
         " = \"nodal.port_value\"() <{port = @" + name + ", " + metadata(name) + "}> : () -> " +
         type + "\n";
}
std::string setup(std::string policy = "sync", bool initialized = true, std::string edge = "rising",
                  std::string polarity = "active_high") {
  std::string body = "\"nodal.domain\"() <{sym_name = \"core\", edge = \"" + edge +
                     "\", reset_policy = \"" + policy + "\", metadata = {}}> : () -> ()\n";
  body += port("clock", "!nodal.capability<\"clock\">");
  if (policy != "none")
    body += port("reset", "!nodal.capability<\"reset\">");
  body += "\"nodal.local_domain_binding\"(%clock" +
          std::string(policy == "none" ? "" : ", %reset") +
          ") <{domain = @core, reset_polarity = \"" + polarity +
          "\", metadata = {}}> : (!nodal.capability<\"clock\">" +
          (policy == "none" ? "" : ", !nodal.capability<\"reset\">") + ") -> ()\n";
  body += literal("zero", 0) + literal("one", 1) + port("enable", bit) + port("input") +
          port("result", u8, "output");
  body += "%state = \"nodal.digital_storage\"(" + std::string(initialized ? "%zero" : "") +
          ") <{name = \"state\", kind = \"register\", domain = @core, " + metadata("state") +
          "}> : (" + (initialized ? u8 : "") + ") -> " + u8 + "\n";
  return body;
}
std::string expression(std::string name, std::string opcode, std::string operands,
                       std::string types, std::string result = u8, std::string extra = "") {
  return "%" + name + " = \"nodal.digital_expr\"(" + operands + ") <{operator_name = \"" + opcode +
         "\", metadata = {semantic_path = \"Fixture." + name + "\"" + extra + "}}> : (" + types +
         ") -> " + result + "\n";
}
std::string assign(std::string target, std::string value, bool next = false,
                   std::string type = u8) {
  return "\"nodal.digital_assign\"(%" + target + ", %" + value + ") <{kind = \"" +
         (next ? "register_next" : "continuous") + "\", " + metadata(target + "Assign") + "}> : (" +
         type + ", " + type + ") -> ()\n";
}
std::string enabled() {
  return "\"nodal.behavioral_if\"(%enable, %state, %input) <{contract_version = "
         "\"behavioral-if-v1\", region_id = \"Fixture.choice\", operandSegmentSizes = array<i32: "
         "1, 2, 0>, " +
         metadata("choice") + "}> ({^bb0(%s: " + u8 + ", %v: " + u8 + "):\n" +
         assign("s", "v", true) + "}, {^bb0():}) : (" + bit + ", " + u8 + ", " + u8 + ") -> ()\n";
}
std::string owner(std::string body, std::string name = "Fixture") {
  return "\"nodal.module\"() <{sym_name = \"" + name + "\", metadata = {}}> ({\n" + body +
         "}) : () -> ()\n";
}
std::string wrap(std::string body, bool quality = false) {
  return "module" +
         std::string(quality ? " attributes {nodal.digital.quality = {version = 1 : i64, "
                               "temporaries = \"readable\", naming = \"source-preferred\"}}"
                             : "") +
         " {\n" + owner(body) + "}\n";
}
std::string print(Operation *op) {
  std::string value;
  llvm::raw_string_ostream os(value);
  op->print(os);
  return value;
}
LogicalResult lower(mlir::ModuleOp module) {
  PassManager pm(module.getContext());
  pm.addPass(nodal::createSequentialToCIRCTPass());
  return pm.run(module);
}
// Interpret the actual generated scalar graph, with Seq registers as pre-edge
// state cuts. This tests reset/hold/width semantics rather than matching text.
uint64_t evaluate(Value value, const std::map<std::string, uint64_t> &inputs, uint64_t state) {
  if (auto arg = dyn_cast<BlockArgument>(value)) {
    auto module = cast<circt::hw::HWModuleOp>(arg.getOwner()->getParentOp());
    return inputs.at(
        module.getPortList()[module.getPortIdForInputId(arg.getArgNumber())].name.getValue().str());
  }
  auto *op = value.getDefiningOp();
  if (auto reg = dyn_cast<circt::seq::CompRegOp>(op)) {
    auto found = inputs.find("state:" + reg.getNameAttr().getValue().str());
    return found == inputs.end() ? state : found->second;
  }
  if (auto literal = dyn_cast<circt::hw::ConstantOp>(op))
    return literal.getValue().getZExtValue();
  auto name = op->getName().getStringRef();
  auto arg = [&](unsigned i) { return evaluate(op->getOperand(i), inputs, state); };
  unsigned bits = cast<IntegerType>(value.getType()).getWidth();
  uint64_t mask = bits == 64 ? ~uint64_t(0) : (uint64_t(1) << bits) - 1;
  if (name == "hw.wire")
    return arg(0);
  if (name == "comb.mux")
    return arg(0) ? arg(1) : arg(2);
  if (name == "comb.xor")
    return arg(0) ^ arg(1);
  if (name == "comb.and")
    return arg(0) & arg(1);
  if (name == "comb.or") {
    uint64_t result = 0;
    for (unsigned i = 0; i < op->getNumOperands(); ++i)
      result |= arg(i);
    return result;
  }
  if (name == "comb.add")
    return (arg(0) + arg(1)) & mask;
  if (name == "comb.sub")
    return (arg(0) - arg(1)) & mask;
  if (name == "comb.mul")
    return (arg(0) * arg(1)) & mask;
  if (name == "comb.extract")
    return (arg(0) >> op->getAttrOfType<IntegerAttr>("lowBit").getInt()) & mask;
  if (name == "comb.concat") {
    uint64_t result = 0;
    for (unsigned i = 0; i < op->getNumOperands(); ++i)
      result = (result << cast<IntegerType>(op->getOperand(i).getType()).getWidth()) | arg(i);
    return result;
  }
  if (name == "comb.replicate")
    return arg(0) ? mask : 0;
  if (auto cmp = dyn_cast<circt::comb::ICmpOp>(op)) {
    using P = circt::comb::ICmpPredicate;
    unsigned w = cast<IntegerType>(op->getOperand(0).getType()).getWidth();
    int64_t a = llvm::APInt(w, arg(0)).getSExtValue(), b = llvm::APInt(w, arg(1)).getSExtValue();
    switch (cmp.getPredicate()) {
    case P::eq:
      return arg(0) == arg(1);
    case P::ne:
      return arg(0) != arg(1);
    case P::ult:
      return arg(0) < arg(1);
    case P::ule:
      return arg(0) <= arg(1);
    case P::ugt:
      return arg(0) > arg(1);
    case P::uge:
      return arg(0) >= arg(1);
    case P::slt:
      return a < b;
    case P::sle:
      return a <= b;
    case P::sgt:
      return a > b;
    case P::sge:
      return a >= b;
    default:
      break;
    }
  }
  llvm::report_fatal_error("unsupported test evaluator operation");
}
circt::seq::CompRegOp state(mlir::ModuleOp module) {
  circt::seq::CompRegOp found;
  module.walk([&](circt::seq::CompRegOp value) { found = value; });
  return found;
}
const char *flatFSMFixture =
    R"mlir(module attributes {nodal.digital.quality = {version = 1 : i64, naming = "semantic", temporaries = "inline-safe"}} { "nodal.enum"() <{sym_name = "Mode", underlying_type = !nodal.uint<3>, encoding = "custom", metadata = {abi_version = 1 : i64, semantic_type = "example.Mode", abi_hash = "cb807326542df7db5314f8d795ea4384c2b40209497084c69a41f9ccd9faf46b"}}> ({
"nodal.enum_case"() <{sym_name = "Idle", value = 0 : i64, metadata = {case_id = "example.Mode.Idle"}}> : () -> ()
"nodal.enum_case"() <{sym_name = "Run", value = 3 : i64, metadata = {case_id = "example.Mode.Run"}}> : () -> ()
"nodal.enum_case"() <{sym_name = "Stop", value = 7 : i64, metadata = {case_id = "example.Mode.Stop"}}> : () -> ()
}) : () -> ()
"nodal.module"() <{sym_name = "Fixture", metadata = {}}> ({
"nodal.domain"() <{sym_name = "core", edge = "rising", reset_policy = "sync", metadata = {}}> : () -> ()
"nodal.port"() <{sym_name = "clock", direction = "input", type = !nodal.capability<"clock">, domain = @core, metadata = {}}> : () -> ()
%clock = "nodal.port_value"() <{port = @clock, metadata = {semantic_path = "Fixture.clock"}}> : () -> !nodal.capability<"clock">
"nodal.port"() <{sym_name = "reset", direction = "input", type = !nodal.capability<"reset">, domain = @core, metadata = {}}> : () -> ()
%reset = "nodal.port_value"() <{port = @reset, metadata = {semantic_path = "Fixture.reset"}}> : () -> !nodal.capability<"reset">
"nodal.local_domain_binding"(%clock, %reset) <{domain = @core, reset_polarity = "active_high", metadata = {}}> : (!nodal.capability<"clock">, !nodal.capability<"reset">) -> ()
%initial = "nodal.const_literal"() <{value = 0 : ui2, spelling = "0", metadata = {semantic_path = "Fixture.initial"}}> : () -> !nodal.bits<2>
%state = "nodal.digital_storage"(%initial) <{name = "state", kind = "register", domain = @core, metadata = {semantic_path = "Fixture.state"}}> : (!nodal.bits<2>) -> !nodal.bits<2>
%count = "nodal.digital_storage"() <{name = "count", kind = "register", domain = @core, metadata = {semantic_path = "Fixture.count"}}> : () -> !nodal.bits<8>
"nodal.port"() <{sym_name = "go", direction = "input", type = !nodal.bits<1>, domain = @core, metadata = {}}> : () -> ()
%go = "nodal.port_value"() <{port = @go, metadata = {semantic_path = "Fixture.go"}}> : () -> !nodal.bits<1>
"nodal.port"() <{sym_name = "again", direction = "input", type = !nodal.bits<1>, domain = @core, metadata = {}}> : () -> ()
%again = "nodal.port_value"() <{port = @again, metadata = {semantic_path = "Fixture.again"}}> : () -> !nodal.bits<1>
"nodal.port"() <{sym_name = "done", direction = "input", type = !nodal.bits<1>, domain = @core, metadata = {}}> : () -> ()
%done = "nodal.port_value"() <{port = @done, metadata = {semantic_path = "Fixture.done"}}> : () -> !nodal.bits<1>
%v1 = "nodal.const_literal"() <{value = 1 : ui8, spelling = "1", metadata = {semantic_path = "Fixture.v1"}}> : () -> !nodal.bits<8>
%v2 = "nodal.const_literal"() <{value = 2 : ui8, spelling = "2", metadata = {semantic_path = "Fixture.v2"}}> : () -> !nodal.bits<8>
%v3 = "nodal.const_literal"() <{value = 3 : ui8, spelling = "3", metadata = {semantic_path = "Fixture.v3"}}> : () -> !nodal.bits<8>
%v4 = "nodal.const_literal"() <{value = 4 : ui8, spelling = "4", metadata = {semantic_path = "Fixture.v4"}}> : () -> !nodal.bits<8>
%v5 = "nodal.const_literal"() <{value = 5 : ui8, spelling = "5", metadata = {semantic_path = "Fixture.v5"}}> : () -> !nodal.bits<8>
%next, %taken = "nodal.flat_fsm"(%state, %go, %again, %done, %count, %v1, %count, %v2, %count, %v3, %count, %v4, %count, %v5) <{contract_version = "flat-fsm-v1", machine_id = "Fixture.machine", enum_ref = @Mode, domain = @core, initial_case = "example.Mode.Idle", requested_encoding = "compact", resolved_encoding = "compact", local_width = 2 : i64, case_ids = ["example.Mode.Idle", "example.Mode.Run", "example.Mode.Stop"], local_codes = [0 : ui2, 1 : ui2, 2 : ui2], illegal_policy = "error", states = [{case_id = "example.Mode.Idle", terminal = false, ordinal = 0 : i64}, {case_id = "example.Mode.Run", terminal = false, ordinal = 1 : i64}, {case_id = "example.Mode.Stop", terminal = true, ordinal = 2 : i64}], transitions = [{source_case = "example.Mode.Idle", destination_case = "example.Mode.Run", mode = "priority", ordinal = 0 : i64}, {source_case = "example.Mode.Run", destination_case = "example.Mode.Run", mode = "priority", ordinal = 1 : i64}, {source_case = "example.Mode.Run", destination_case = "example.Mode.Stop", mode = "priority", ordinal = 2 : i64}], actions = [{region_id = "Fixture.machine.action0", phase = "active", owner_case = "example.Mode.Run", ordinal = 0 : i64}, {region_id = "Fixture.machine.action1", phase = "exit", owner_case = "example.Mode.Run", ordinal = 1 : i64}, {region_id = "Fixture.machine.action2", phase = "transition", transition_ordinal = 1 : i64, ordinal = 2 : i64}, {region_id = "Fixture.machine.action3", phase = "entry", owner_case = "example.Mode.Run", ordinal = 3 : i64}, {region_id = "Fixture.machine.action4", phase = "entry", owner_case = "example.Mode.Stop", ordinal = 4 : i64}], action_capture_counts = array<i32: 2, 2, 2, 2, 2>, metadata = {semantic_path = "Fixture.machine"}}> ({^bb0(%cur0: !nodal.bits<2>, %next0: !nodal.bits<2>, %taken0: !nodal.bits<1>, %target0: !nodal.bits<8>, %value0: !nodal.bits<8>):
"nodal.digital_assign"(%target0, %value0) <{kind = "register_next", metadata = {}}> : (!nodal.bits<8>, !nodal.bits<8>) -> ()
}, {^bb0(%cur1: !nodal.bits<2>, %next1: !nodal.bits<2>, %taken1: !nodal.bits<1>, %target1: !nodal.bits<8>, %value1: !nodal.bits<8>):
"nodal.digital_assign"(%target1, %value1) <{kind = "register_next", metadata = {}}> : (!nodal.bits<8>, !nodal.bits<8>) -> ()
}, {^bb0(%cur2: !nodal.bits<2>, %next2: !nodal.bits<2>, %taken2: !nodal.bits<1>, %target2: !nodal.bits<8>, %value2: !nodal.bits<8>):
"nodal.digital_assign"(%target2, %value2) <{kind = "register_next", metadata = {}}> : (!nodal.bits<8>, !nodal.bits<8>) -> ()
}, {^bb0(%cur3: !nodal.bits<2>, %next3: !nodal.bits<2>, %taken3: !nodal.bits<1>, %target3: !nodal.bits<8>, %value3: !nodal.bits<8>):
"nodal.digital_assign"(%target3, %value3) <{kind = "register_next", metadata = {}}> : (!nodal.bits<8>, !nodal.bits<8>) -> ()
}, {^bb0(%cur4: !nodal.bits<2>, %next4: !nodal.bits<2>, %taken4: !nodal.bits<1>, %target4: !nodal.bits<8>, %value4: !nodal.bits<8>):
"nodal.digital_assign"(%target4, %value4) <{kind = "register_next", metadata = {}}> : (!nodal.bits<8>, !nodal.bits<8>) -> ()
}) : (!nodal.bits<2>, !nodal.bits<1>, !nodal.bits<1>, !nodal.bits<1>, !nodal.bits<8>, !nodal.bits<8>, !nodal.bits<8>, !nodal.bits<8>, !nodal.bits<8>, !nodal.bits<8>, !nodal.bits<8>, !nodal.bits<8>, !nodal.bits<8>, !nodal.bits<8>) -> (!nodal.bits<2>, !nodal.bits<1>)
}) : () -> () })mlir";
std::string replace(std::string value, const std::string &from, const std::string &to) {
  auto at = value.find(from);
  if (at == std::string::npos)
    llvm::report_fatal_error("test replacement missing");
  value.replace(at, from.size(), to);
  return value;
}
} // namespace
int main(int argc, char **argv) {
  llvm::sys::PrintStackTraceOnErrorSignal(argv[0]);
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect, circt::hw::HWDialect, circt::comb::CombDialect,
                  circt::seq::SeqDialect, circt::verif::VerifDialect>();
  MLIRContext context(registry);
  context.loadAllAvailableDialects();
  std::string diagnostics;
  ScopedDiagnosticHandler handler(&context, [&](Diagnostic &d) {
    llvm::raw_string_ostream os(diagnostics);
    d.print(os);
    os << '\n';
    return success();
  });
  if (argc == 3 && std::string(argv[1]) == "--lower") {
    auto module = parseSourceFile<mlir::ModuleOp>(argv[2], &context);
    if (!module || failed(lower(*module))) {
      llvm::errs() << diagnostics;
      return 1;
    }
    llvm::outs() << print(*module) << '\n';
    return 0;
  }
  unsigned cases = 0, failures = 0;
  auto report = [&](StringRef name, bool ok) {
    ++cases;
    failures += !ok;
    (ok ? llvm::outs() : llvm::errs())
        << "SEQUENTIAL_CIRCT_" << (ok ? "PASS " : "FAIL ") << name << '\n';
    if (!ok)
      llvm::errs() << diagnostics;
    diagnostics.clear();
  };
  const std::string source = wrap(setup() + enabled() + assign("result", "state"));
  auto module = parseSourceString<mlir::ModuleOp>(source, &context);
  bool ok = module && succeeded(lower(*module));
  report("actual-register-feedback-lowering", ok);
  if (ok) {
    auto reg = state(*module);
    auto result = print(*module);
    report("reset-free-compreg-no-initial", reg && !reg.getReset() && !reg.getInitialValue());
    report("synchronous-reset-hold-enable",
           evaluate(reg.getInput(), {{"reset", 1}, {"enable", 1}, {"input", 71}}, 19) == 0 &&
               evaluate(reg.getInput(), {{"reset", 0}, {"enable", 0}, {"input", 71}}, 19) == 19 &&
               evaluate(reg.getInput(), {{"reset", 0}, {"enable", 1}, {"input", 71}}, 19) == 71);
    auto parsed = parseSourceString<mlir::ModuleOp>(result, &context);
    report("target-parse-roundtrip", parsed && print(*parsed) == result);
    auto second = parseSourceString<mlir::ModuleOp>(source, &context);
    report("deterministic-replay", second && succeeded(lower(*second)) && print(*second) == result);
    if (argc == 3 && std::string(argv[1]) == "--emit") {
      std::ofstream(argv[2]) << result << '\n';
    }
  }
  auto test = [&](StringRef name, std::string body, auto check, bool quality = false) {
    auto m = parseSourceString<mlir::ModuleOp>(wrap(body, quality), &context);
    bool good = m && succeeded(lower(*m));
    report(name, good && check(*m));
  };
  test("falling-active-low",
       setup("sync", true, "falling", "active_low") + enabled() + assign("result", "state"),
       [&](mlir::ModuleOp m) {
         auto r = state(m);
         return isa<circt::seq::ClockInverterOp>(r.getClk().getDefiningOp()) &&
                evaluate(r.getInput(), {{"reset", 0}, {"enable", 1}, {"input", 71}}, 19) == 0 &&
                evaluate(r.getInput(), {{"reset", 1}, {"enable", 1}, {"input", 71}}, 19) == 71;
       });
  test("uninitialized-reset-opt-out", setup("sync", false) + enabled() + assign("result", "state"),
       [&](mlir::ModuleOp m) {
         return evaluate(state(m).getInput(), {{"reset", 1}, {"enable", 0}, {"input", 71}}, 19) ==
                    19 &&
                evaluate(state(m).getInput(), {{"reset", 1}, {"enable", 1}, {"input", 71}}, 19) ==
                    71;
       });
  test("resetless-hold", setup("none", false) + assign("result", "state"), [&](mlir::ModuleOp m) {
    auto r = state(m);
    return r.getInput() == r.getResult() && !r.getInitialValue();
  });
  test("counter-width-and-wrap",
       setup() + expression("sum", "digital_add", "%state, %one", u8 + ", " + u8, u9) +
           expression("next", "digital_wrap", "%sum", u9) + assign("state", "next", true) +
           assign("result", "state"),
       [&](mlir::ModuleOp m) {
         return evaluate(state(m).getInput(), {{"reset", 0}}, 255) == 0 &&
                evaluate(state(m).getInput(), {{"reset", 0}}, 17) == 18;
       });
  test("wire-output-physical-driver",
       setup() + "%wire = \"nodal.digital_storage\"() <{name = \"wire\", kind = \"wire\", " +
           metadata("wire") + "}> : () -> " + u8 + "\n" + assign("wire", "input") +
           assign("state", "wire", true) + assign("result", "wire"),
       [&](mlir::ModuleOp m) {
         return evaluate(state(m).getInput(), {{"reset", 0}, {"input", 29}}, 17) == 29;
       });
  auto negative = [&](StringRef name, std::string input, StringRef expected) {
    auto m = parseSourceString<mlir::ModuleOp>(input, &context);
    std::string before = m ? print(*m) : "";
    bool good =
        m && failed(lower(*m)) && print(*m) == before && StringRef(diagnostics).contains(expected);
    report(name, good);
  };
  negative("async-native-distinct-target-rejected",
           wrap(setup("async") + assign("result", "state")), "synchronous or resetless");
  negative("unsupported-sibling-is-atomic",
           "module {" + owner(setup() + enabled() + assign("result", "state")) +
               owner(setup("async") + assign("result", "state"), "Other") + "}",
           "synchronous or resetless");
  negative("symbolic-parameter-is-not-specialized",
           wrap(setup() +
                "\"nodal.parameter\"() <{sym_name = \"P\", type = i64, default_value = 4 : i64, "
                "variability = \"symbolic\", metadata = {}}> : () -> ()\n" +
                assign("result", "state")),
           "outside the finite scalar");
  negative("width-cap-is-explicit",
           wrap(setup() + port("tooWide", "!nodal.bits<4097>") + assign("result", "state")),
           "only fixed scalar");

  negative("undriven-output-is-not-invented", wrap(setup() + enabled()),
           "undriven combinational wire");
  negative("aggregate-target-profile-rejected",
           wrap(setup() + port("aggregate", "!nodal.shaped<\"2\", !nodal.uint<8>>") +
                assign("result", "state")),
           "only fixed scalar");
  negative("memory-target-profile-rejected",
           wrap(setup() +
                "\"nodal.memory\"() <{sym_name = \"memory\", element_type = !nodal.uint<8>, depth "
                "= \"16\", read_latency = 1 : i64, read_under_write = \"old_data\", ordering = "
                "\"ordered\", domain = @core, metadata = {}}> : () -> ()\n" +
                assign("result", "state")),
           "outside the finite scalar");
  {
    auto changed = parseSourceString<mlir::ModuleOp>(source, &context);
    if (changed)
      changed->walk([&](Operation *op) {
        if (op->getName().getStringRef() == "nodal.local_domain_binding")
          op->setOperand(0, op->getOperand(1));
      });
    std::string before = changed ? print(*changed) : "";
    report("malformed-actual-clock-is-atomic",
           changed && failed(lower(*changed)) && print(*changed) == before);
  }
  std::string alias = expression("alias", "digital_alias", "%input", u8, u8,
                                 ", digital_intent = {kind = \"keep\", value = \"inspection\"}");
  negative("authored-keep-needs-quality",
           wrap(setup() + alias + assign("state", "alias", true) + assign("result", "state")),
           "authored alias/materialization intent");
  test(
      "canonical-keep-materialization",
      setup() + alias + assign("state", "alias", true) + assign("result", "state"),
      [&](mlir::ModuleOp m) {
        unsigned wires = 0;
        m.walk([&](circt::hw::WireOp) { ++wires; });
        return wires >= 1;
      },
      true);

  auto wire = [&](std::string name) {
    return "%" + name + " = \"nodal.digital_storage\"() <{name = \"" + name +
           "\", kind = \"wire\", " + metadata(name) + "}> : () -> " + u8 + "\n";
  };
  test("forward-wire-chain-and-identity",
       setup() + wire("wa") + wire("wb") + expression("id", "digital_wrap", "%wa", u8) +
           assign("wa", "wb") + assign("wb", "input") + assign("state", "id", true) +
           assign("result", "wa"),
       [&](mlir::ModuleOp m) {
         return evaluate(state(m).getInput(), {{"reset", 0}, {"input", 83}}, 7) == 83;
       });
  const std::string namedIntent =
      ", digital_intent = {kind = \"named\", value = \"visible\"}, digital_source = {name = "
      "\"visible\", provenance = \"explicit\", aliases = []}";
  std::string namedAlias = expression("named", "digital_alias", "%input", u8, u8, namedIntent);
  test(
      "exact-authored-name",
      setup() + namedAlias + assign("state", "named", true) + assign("result", "state"),
      [&](mlir::ModuleOp m) {
        bool found = false;
        m.walk([&](circt::hw::WireOp w) {
          found |= w.getNameAttr() && w.getNameAttr().getValue() == "visible";
        });
        return found;
      },
      true);
  negative("duplicate-exact-name-rejected",
           wrap(setup() + namedAlias +
                    expression("duplicate", "digital_alias", "%input", u8, u8, namedIntent) +
                    assign("state", "named", true) + assign("result", "duplicate"),
                true),
           "authored exact target name collides");
  auto scalar = [&](StringRef name, std::string typeA, std::string typeB, std::string resultType,
                    std::string opcode, uint64_t a, uint64_t b, uint64_t expected) {
    std::string body = "\"nodal.domain\"() <{sym_name = \"core\", edge = \"rising\", reset_policy "
                       "= \"none\", metadata = {}}> : () -> ()\n" +
                       port("a", typeA);
    if (!typeB.empty())
      body += port("b", typeB);
    body += port("out", resultType, "output") +
            expression("value", opcode, "%a" + std::string(typeB.empty() ? "" : ", %b"),
                       typeA + (typeB.empty() ? "" : ", " + typeB), resultType) +
            assign("out", "value", false, resultType);
    test(name, body, [&](mlir::ModuleOp m) {
      auto hw = *m.getOps<circt::hw::HWModuleOp>().begin();
      return evaluate(hw.getBodyBlock()->getTerminator()->getOperand(0), {{"a", a}, {"b", b}}, 0) ==
             expected;
    });
  };
  const std::string u4 = "!nodal.uint<4>", u6 = "!nodal.uint<6>", s4 = "!nodal.sint<4>",
                    s5 = "!nodal.sint<5>", s6 = "!nodal.sint<6>", s7 = "!nodal.sint<7>",
                    s8 = "!nodal.sint<8>", b4 = "!nodal.bits<4>";
  scalar("signed-add-extends", s4, s6, s7, "digital_add", 13, 62, 123);
  scalar("unsigned-subtract-is-signed", u4, u6, s7, "digital_sub", 15, 1, 14);
  scalar("unsigned-subtract-negative-result", u4, u6, s7, "digital_sub", 0, 15, 113);
  scalar("signed-minimum-negation", s4, "", s5, "digital_neg", 8, 0, 8);
  scalar("signed-extrema-widening", s4, s6, s7, "digital_add", 8, 32, 88);
  scalar("signed-product", s4, s4, s8, "digital_mul", 13, 2, 250);
  scalar("unsigned-negation", u4, "", s5, "digital_neg", 15, 0, 17);
  scalar("signed-less-than", s4, s6, bit, "digital_lt", 13, 62, 1);
  scalar("unsigned-less-equal", u4, u6, bit, "digital_le", 15, 15, 1);
  scalar("signed-greater-than", s4, s6, bit, "digital_gt", 13, 62, 0);
  scalar("unsigned-greater-equal", u4, u6, bit, "digital_ge", 15, 15, 1);
  scalar("unequal-width-equality", u4, u6, bit, "digital_eq", 15, 15, 1);
  scalar("unequal-width-inequality", u4, u6, bit, "digital_ne", 15, 16, 1);
  scalar("signed-min", s4, s6, s6, "digital_min", 13, 62, 61);
  scalar("signed-max", s4, s6, s6, "digital_max", 13, 62, 62);
  scalar("bitwise-and", b4, b4, b4, "digital_and", 10, 3, 2);
  scalar("bitwise-or", b4, b4, b4, "digital_or", 10, 3, 11);
  scalar("bitwise-xor", b4, b4, b4, "digital_xor", 10, 3, 9);
  scalar("bitwise-not", b4, "", b4, "digital_not", 10, 0, 5);
  scalar("bool-and", bit, bit, bit, "bool_and", 1, 0, 0);
  scalar("bool-or", bit, bit, bit, "bool_or", 1, 0, 1);
  scalar("bool-not", bit, "", bit, "bool_not", 1, 0, 0);
  scalar("concatenation", b4, b4, "!nodal.bits<8>", "digital_concat", 10, 3, 163);
  scalar("sign-extension", s4, "", s8, "digital_extend", 13, 0, 253);
  scalar("signed-truncation", s8, "", s4, "digital_truncate", 253, 0, 13);
  scalar("checked-widening", u4, "", u8, "digital_resize_checked", 15, 0, 15);
  scalar("unsigned-to-signed-widens", u4, "", s5, "digital_to_signed", 15, 0, 15);
  scalar("reinterpret-signed", u4, "", s4, "digital_reinterpret_signed", 15, 0, 15);
  scalar("reinterpret-unsigned", s4, "", u4, "digital_reinterpret_unsigned", 13, 0, 13);
  test("proven-nonnegative-signed-to-unsigned",
       literal("positive", 7, s4) +
           "\"nodal.domain\"() <{sym_name = \"core\", edge = \"rising\", reset_policy = \"none\", "
           "metadata = {}}> : () -> ()\n" +
           port("out", u4, "output") +
           expression("value", "digital_to_unsigned", "%positive", s4, u4) +
           assign("out", "value", false, u4),
       [&](mlir::ModuleOp m) {
         auto hw = *m.getOps<circt::hw::HWModuleOp>().begin();
         return evaluate(hw.getBodyBlock()->getTerminator()->getOperand(0), {}, 0) == 7;
       });
  test("negative-literal-sign-extends-wide",
       literal("negative", -1, "!nodal.sint<128>") +
           "\"nodal.domain\"() <{sym_name = \"core\", edge = \"rising\", reset_policy = \"none\", "
           "metadata = {}}> : () -> ()\n" +
           port("out", "!nodal.sint<128>", "output") +
           assign("out", "negative", false, "!nodal.sint<128>"),
       [&](mlir::ModuleOp m) {
         bool found = false;
         m.walk([&](circt::hw::ConstantOp c) {
           found |= c.getValue().isAllOnes() && c.getValue().getBitWidth() == 128;
         });
         return found;
       });

  for (bool falling : {false, true}) {
    std::string edge = falling ? "falling" : "rising",
                polarity = falling ? "active_low" : "active_high";
    std::string assertion =
        "%active = \"nodal.reset_asserted\"(%reset) <{domain = @core, reset_polarity = \"" +
        polarity + "\", " + metadata("resetActive") + "}> : (!nodal.capability<\"reset\">) -> " +
        bit + "\n";
    assertion += expression("property", "bool_or", "%active, %enable", bit + ", " + bit, bit);
    assertion += "\"nodal.sequential_assert\"(%property, %clock) <{domain = @core, edge = \"" +
                 edge + "\", root_id = \"Fixture.error\", " + metadata("error") + "}> : (" + bit +
                 ", !nodal.capability<\"clock\">) -> ()\n";
    test(falling ? "actual-falling-sampled-assertion" : "actual-rising-sampled-assertion",
         setup("sync", false, edge, polarity) + assertion + assign("result", "state"),
         [&](mlir::ModuleOp m) {
           unsigned registers = 0, assertions = 0;
           bool valid = true;
           m.walk([&](circt::seq::CompRegOp r) {
             ++registers;
             valid &= !r.getInitialValue() && !r.getReset();
           });
           m.walk([&](circt::verif::ClockedAssertOp a) {
             ++assertions;
             valid &= !a.getEnable() && a.getLabelAttr().getValue() == "Fixture.error" &&
                      a.getEdge() ==
                          (falling ? circt::verif::ClockEdge::Neg : circt::verif::ClockEdge::Pos);
             valid &= evaluate(a.getProperty(),
                               {{"reset", uint64_t(falling ? 1 : 0)}, {"enable", 0}}, 0) == 0;
             valid &= evaluate(a.getProperty(),
                               {{"reset", uint64_t(falling ? 0 : 1)}, {"enable", 0}}, 0) == 1;
             valid &= isa<circt::seq::FromClockOp>(a.getClock().getDefiningOp());
           });
           return valid && registers == 1 && assertions == 1;
         });
  }

  std::string enumMetadata = "abi_version = 1 : i64, semantic_type = \"example.Mode\", abi_hash = "
                             "\"cb807326542df7db5314f8d795ea4384c2b40209497084c69a41f9ccd9faf46b\"";
  auto enumLiteral = [&](std::string name, std::string member) {
    return "%" + name +
           " = \"nodal.enum_expr\"() <{operator_name = \"enum_literal\", enum_ref = @Mode, "
           "case_ids = [\"example.Mode." +
           member + "\"], metadata = {" + enumMetadata + ", semantic_path = \"Fixture." + name +
           "\"}}> : () -> !nodal.enum<\"Mode\", 3>\n";
  };
  const std::string enumType = "!nodal.enum<\"Mode\", 3>";
  std::string observed = port("stateOut", "!nodal.bits<2>", "output") +
                         assign("stateOut", "state", false, "!nodal.bits<2>");
  observed += port("countOut", "!nodal.bits<8>", "output") +
              assign("countOut", "count", false, "!nodal.bits<8>");
  observed += port("nextOut", "!nodal.bits<2>", "output") +
              assign("nextOut", "next", false, "!nodal.bits<2>");
  observed += port("takenOut", bit, "output") + assign("takenOut", "taken", false, bit);
  observed += enumLiteral("run", "Run") + enumLiteral("stop", "Stop") +
              expression("canonical", "digital_select", "%done, %stop, %run",
                         bit + ", " + enumType + ", " + enumType, enumType);
  observed += port("enumOut", enumType, "output") + assign("enumOut", "canonical", false, enumType);
  observed += "%terminal = \"nodal.enum_expr\"(%canonical) <{operator_name = \"enum_is_one_of\", "
              "enum_ref = @Mode, case_ids = [\"example.Mode.Stop\"], metadata = {" +
              enumMetadata + ", semantic_path = \"Fixture.terminal\"}}> : (" + enumType + ") -> " +
              bit + "\n";
  observed += port("terminalOut", bit, "output") + assign("terminalOut", "terminal", false, bit);
  observed += port("enumInput", enumType);
  observed += "%valid = \"nodal.enum_expr\"(%enumInput) <{operator_name = \"enum_is_valid\", "
              "enum_ref = @Mode, case_ids = [], metadata = {" +
              enumMetadata + ", semantic_path = \"Fixture.valid\"}}> : (" + enumType + ") -> " +
              bit + "\n";
  observed += port("validOut", bit, "output") + assign("validOut", "valid", false, bit);
  observed += "%bits = \"nodal.enum_expr\"(%enumInput) <{operator_name = \"enum_as_bits\", "
              "enum_ref = @Mode, case_ids = [], metadata = {" +
              enumMetadata + ", semantic_path = \"Fixture.bits\"}}> : (" + enumType +
              ") -> !nodal.bits<3>\n";
  observed += port("bitsOut", "!nodal.bits<3>", "output") +
              assign("bitsOut", "bits", false, "!nodal.bits<3>");
  observed += "%equal = \"nodal.enum_expr\"(%enumInput, %run) <{operator_name = \"enum_eq\", "
              "enum_ref = @Mode, case_ids = [], metadata = {" +
              enumMetadata + ", semantic_path = \"Fixture.equal\"}}> : (" + enumType + ", " +
              enumType + ") -> " + bit + "\n";
  observed += port("equalOut", bit, "output") + assign("equalOut", "equal", false, bit);
  for (bool recovery : {false, true}) {
    std::string raw = replace(flatFSMFixture, "}) : () -> () }", observed + "}) : () -> () }");
    if (recovery)
      raw = replace(raw, "illegal_policy = \"error\"", "illegal_policy = \"recover_to_initial\"");
    auto m = parseSourceString<mlir::ModuleOp>(raw, &context);
    bool valid = m && succeeded(lower(*m));
    report(recovery ? "flat-fsm-recovery-lowers" : "flat-fsm-error-lowers", valid);
    if (!valid)
      continue;
    auto hw = *m->getOps<circt::hw::HWModuleOp>().begin();
    llvm::DenseMap<StringAttr, circt::seq::CompRegOp> regs;
    unsigned assertions = 0;
    m->walk([&](circt::seq::CompRegOp r) {
      regs.try_emplace(r.getNameAttr(), r);
      valid &= !r.getReset() && !r.getInitialValue();
    });
    m->walk([&](circt::verif::ClockedAssertOp a) {
      ++assertions;
      valid &= !a.getEnable() && a.getLabelAttr().getValue() == "Fixture.machine";
    });
    // Selected causal edges: entry, active hold, self-edge priority, transition
    // to terminal, reset suppression of an uninitialized action register, and
    // illegal encoding Error/recovery. Values are read from the emitted graph.
    struct Sample {
      unsigned state, go, again, done, reset, next, count, taken;
    };
    Sample samples[] = {{0, 1, 0, 0, 0, 1, 4, 1},  {1, 0, 0, 0, 0, 1, 1, 0},
                        {1, 0, 1, 1, 0, 1, 4, 1},  {1, 0, 0, 1, 0, 2, 5, 1},
                        {1, 0, 1, 1, 1, 0, 99, 0}, {3, 1, 1, 1, 0, recovery ? 0u : 3u, 99, 0}};
    for (auto sample : samples) {
      std::map<std::string, uint64_t> input{
          {"state:state", sample.state}, {"state:count", 99},   {"go", sample.go},
          {"again", sample.again},       {"done", sample.done}, {"reset", sample.reset}};
      for (auto &entry : regs) {
        auto reg = entry.second;
        valid &= evaluate(reg.getInput(), input, 0) ==
                 (entry.first.getValue() == "state" ? sample.next : sample.count);
      }
      auto *outputs = hw.getBodyBlock()->getTerminator();
      valid &= evaluate(outputs->getOperand(0), input, 0) == sample.state &&
               evaluate(outputs->getOperand(1), input, 0) == 99;
      valid &= evaluate(outputs->getOperand(2), input, 0) == sample.next &&
               evaluate(outputs->getOperand(3), input, 0) == sample.taken;
      valid &= evaluate(outputs->getOperand(4), input, 0) == (sample.done ? 7 : 3) &&
               evaluate(outputs->getOperand(5), input, 0) == sample.done;
      m->walk([&](circt::verif::ClockedAssertOp a) {
        valid &= evaluate(a.getProperty(), input, 0) == (sample.reset || sample.state < 3);
      });
    }
    bool sparseValid = true;
    auto *outputs = hw.getBodyBlock()->getTerminator();
    for (unsigned code = 0; code < 8; ++code) {
      std::map<std::string, uint64_t> input{{"enumInput", code}};
      sparseValid &=
          evaluate(outputs->getOperand(6), input, 0) == (code == 0 || code == 3 || code == 7) &&
          evaluate(outputs->getOperand(7), input, 0) == code &&
          evaluate(outputs->getOperand(8), input, 0) == (code == 3);
    }
    report(recovery ? "sparse-enum-recovery-validity" : "sparse-enum-error-validity", sparseValid);
    valid &= regs.size() == 2 && assertions == unsigned(!recovery) &&
             m->getOperation()->hasAttr("nodal.enum_abi");
    auto parsed = parseSourceString<mlir::ModuleOp>(print(*m), &context);
    valid &= parsed && print(*parsed) == print(*m);
    report(recovery ? "flat-fsm-recovery-causal-semantics" : "flat-fsm-error-causal-semantics",
           valid);
  }
  llvm::outs() << "SEQUENTIAL_CIRCT_SUMMARY cases=" << cases << " failures=" << failures << '\n';
  return failures ? 1 : 0;
}
