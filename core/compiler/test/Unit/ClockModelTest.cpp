#include "nodal/Dialect/Nodal/ClockModel.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/Support/raw_ostream.h"
using namespace mlir;
namespace {
const std::string clockType = "!nodal.capability<\"clock\">";
const std::string resetType = "!nodal.capability<\"reset\">";
const std::string boolType = "!nodal.bits<1>";
std::string domain(std::string name, std::string policy = "none", std::string extra = "",
                   std::string edge = "rising") {
  return "\"nodal.domain\"() <{sym_name = \"" + name + "\", edge = \"" + edge +
         "\", reset_policy = \"" + policy + "\", " + extra + "metadata = {}}> : () -> ()\n";
}
std::string port(std::string name, std::string type, std::string dom = "a") {
  return "\"nodal.port\"() <{sym_name = \"" + name + "\", direction = \"input\", type = " + type +
         (dom.empty() ? ", domain = @none" : ", domain = @" + dom) +
         ", metadata = {semantic_path = \"Fixture." + name + "\"}}> : () -> ()\n%" + name +
         " = \"nodal.port_value\"() <{port = @" + name +
         ", metadata = {semantic_path = \"Fixture." + name + "\"}}> : () -> " + type + "\n";
}
std::string bind(std::string dom, std::string clock, std::string reset = "") {
  return "\"nodal.local_domain_binding\"(%" + clock + (reset.empty() ? "" : ", %" + reset) +
         ") <{domain = @" + dom + ", reset_polarity = \"active_high\", metadata = {}}> : (" +
         clockType + (reset.empty() ? "" : ", " + resetType) + ") -> ()\n";
}
std::string sync(std::string name, std::string dom, std::string clock, std::string raw = "raw") {
  return "%" + name + " = \"nodal.reset_sync\"(%" + raw + ", %" + clock +
         ") <{destination_domain = @" + dom +
         ", stages = 2 : i64, reset_polarity = \"active_high\", "
         "metadata = {}}> : (" +
         resetType + ", " + clockType + ") -> " + resetType + "\n";
}
std::string literal(std::string name = "zero", int value = 0) {
  return "%" + name + " = \"nodal.const_literal\"() <{value = " + std::to_string(value) +
         " : ui1, spelling = \"" + std::to_string(value) + "\", metadata = {}}> : () -> " +
         boolType + "\n";
}
std::string gate(std::string name = "gate", std::string dom = "g", std::string parent = "a",
                 std::string clock = "clk", std::string enable = "enable",
                 std::string control = "@a", std::string edge = "rising") {
  return "%" + name + " = \"nodal.clock_gate\"(%" + clock + ", %" + enable +
         ", %zero) <{parent_domains = [@" + parent + "], domain = @" + dom + ", edge = \"" + edge +
         "\", contract = \"gate_v1\", control_domains = [" + control +
         ", \"constant\"], "
         "obligations = {control_timing = \"inactive_phase_aperture\", startup = "
         "\"known_after_inactive_phase\"}, "
         "metadata = {semantic_path = \"Fixture." +
         name + "\"}}> : (" + clockType + ", " + boolType + ", " + boolType + ") -> " + clockType +
         "\n";
}
std::string gateBody() {
  return domain("a") + domain("g", "none", "parent = @a, ") + port("clk", clockType) +
         port("enable", boolType) + literal() + bind("a", "clk") + gate() + bind("g", "gate");
}
std::string muxBody() {
  return domain("a", "async_assert_sync_release") + domain("b", "async_assert_sync_release") +
         domain("g", "async_assert_sync_release", "parent = @a, second_parent = @b, ") +
         port("clk", clockType) + port("clk2", clockType, "b") + port("rst", resetType) +
         port("select", boolType, "b") +
         "%raw = \"nodal.reset_boundary\"(%rst) <{reset_polarity = \"active_high\", release = "
         "\"raw\", metadata = {}}> : (" +
         resetType + ") -> " + resetType + "\n" + sync("safea", "a", "clk") +
         sync("safeb", "b", "clk2") + bind("a", "clk", "safea") + bind("b", "clk2", "safeb") +
         "%mux = \"nodal.clock_mux\"(%clk, %clk2, %select, %raw) <{parent_domains = [@a, @b], "
         "domain = @g, edge = \"rising\", contract = \"glitchless_mux_v1\", control_domains = "
         "[@b], "
         "obligations = {control_sampling = \"level_coalescing\", switching = "
         "\"serialized_break_before_make\", "
         "reset_epoch = \"common_assertion\", pulse_integrity = \"except_reset_assertion\"}, "
         "metadata = {semantic_path = \"Fixture.mux\"}}> : (" +
         clockType + ", " + clockType + ", " + boolType + ", " + resetType + ") -> " + clockType +
         "\n" + sync("safeg", "g", "mux") + bind("g", "mux", "safeg");
}
std::string replace(std::string s, StringRef a, StringRef b) {
  auto at = s.find(a.str());
  if (at == std::string::npos)
    llvm::report_fatal_error("missing clock mutation: " + a);
  return s.replace(at, a.size(), b.str());
}
std::string replaceAll(std::string s, StringRef a, StringRef b) {
  size_t at = 0;
  while ((at = s.find(a.str(), at)) != std::string::npos) {
    s.replace(at, a.size(), b.str());
    at += b.size();
  }
  return s;
}
} // namespace
int main() {
  MLIRContext context;
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  context.appendDialectRegistry(registry);
  std::string diagnostic;
  ScopedDiagnosticHandler handler(&context, [&](Diagnostic &d) {
    llvm::raw_string_ostream out(diagnostic);
    d.print(out);
    out << '\n';
    return success();
  });
  unsigned count = 0, failures = 0;
  auto parse = [&](const std::string &body) {
    return parseSourceString<mlir::ModuleOp>("module {\"nodal.module\"() <{sym_name = \"Fixture\", "
                                             "metadata = {}}> ({" +
                                                 body + "}) : () -> ()}",
                                             ParserConfig(&context, false));
  };
  auto check = [&](StringRef name, std::string body, bool valid, StringRef message = "") {
    ++count;
    diagnostic.clear();
    auto module = parse(body);
    bool ok = module && succeeded(verify(*module));
    if (ok != valid || (!message.empty() && !StringRef(diagnostic).contains(message))) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostic;
    }
  };
  const auto g = gateBody(), m = muxBody();
  check("resetless-gate", g, true);
  check("falling-gate", replaceAll(g, "rising", "falling"), true);
  check("constant-gate",
        replace(replace(g, "control_domains = [@a", "control_domains = [\"constant\""),
                "%enable, %zero", "%zero, %zero"),
        true);
  check("external-gate",
        replace(replace(g, "type = " + boolType + ", domain = @a",
                        "type = " + boolType + ", domain = @none"),
                "control_domains = [@a", "control_domains = [\"external\""),
        true);
  check("wrong-gate-domain",
        domain("other") + replace(g, "control_domains = [@a", "control_domains = [@other"), false,
        "NODAL-CLOCK-057-001");
  check("forged-constant", replace(g, "control_domains = [@a", "control_domains = [\"constant\""),
        false, "NODAL-CDC-001");
  check("forged-external", replace(g, "control_domains = [@a", "control_domains = [\"external\""),
        false, "NODAL-CDC-001");
  check("forged-parent",
        domain("other") + replace(g, "parent_domains = [@a]", "parent_domains = [@other]"), false);
  check("wrong-edge",
        replace(g, "domain = @g, edge = \"rising\"", "domain = @g, edge = \"falling\""), false,
        "inherited edge");
  check("forged-gate-contract", replace(g, "gate_v1", "bare_and"), false);
  check("missing-gate-obligation",
        replace(g, "startup = \"known_after_inactive_phase\"", "startup = \"initialized\""), false);
  check("no-parent-record", replace(g, "parent = @a, ", ""), false);
  check("duplicate-gate-identity",
        g + domain("g2", "none", "parent = @a, ") +
            replace(gate("gate2", "g2"), "Fixture.gate2", "Fixture.gate") + bind("g2", "gate2"),
        false, "unique semantic identities");
  check("independent-gates",
        g + domain("g2", "none", "parent = @a, ") + gate("gate2", "g2") + bind("g2", "gate2"),
        true);
  check("nested-gates",
        g + domain("g2", "none", "parent = @g, ") +
            gate("gate2", "g2", "g", "gate", "zero", "\"constant\"") + bind("g2", "gate2"),
        true);

  const auto syncGate = domain("a", "sync") + domain("g", "async", "parent = @a, ") +
                        port("clk", clockType) + port("rst", resetType) + port("enable", boolType) +
                        literal() +
                        "%raw = \"nodal.reset_boundary\"(%rst) <{reset_polarity = \"active_high\", "
                        "release = \"raw\", "
                        "metadata = {}}> : (" +
                        resetType + ") -> " + resetType + "\n" + bind("a", "clk", "rst") + gate() +
                        sync("safe", "g", "gate") + bind("g", "gate", "safe");
  check("gate-inherits-raw-sync-parent-roots", syncGate, true);
  check("gate-cannot-reuse-parent-release", replace(syncGate, "%gate, %safe", "%gate, %rst"),
        false);
  check("foreign-semantic-identity", replace(g, "Fixture.gate", "Foreign.gate"), false,
        "canonical owned semantic identity");
  check("mux", m, true);
  check("falling-mux", replaceAll(m, "rising", "falling"), true);
  check("mux-external-select",
        replace(replace(m, "type = " + boolType + ", domain = @b",
                        "type = " + boolType + ", domain = @none"),
                "control_domains = [@b]", "control_domains = [\"external\"]"),
        true);
  check("mux-constant-select",
        literal() + replace(replace(m, "%clk2, %select", "%clk2, %zero"), "control_domains = [@b]",
                            "control_domains = [\"constant\"]"),
        true);
  check("mux-released-assertion", replace(m, "%clk2, %select, %raw", "%clk2, %select, %safea"),
        false, "actual raw reset roots");
  check("mux-parent-duplicate",
        replace(m, "parent_domains = [@a, @b]", "parent_domains = [@a, @a]"), false);
  check("mux-operand-forgery", replace(m, "%clk, %clk2, %select", "%clk, %clk, %select"), false,
        "exact actual parent binding");
  check("mux-bare-mux-contract", replace(m, "glitchless_mux_v1", "combinational"), false);
  check("mux-reset-pulse-obligation", replace(m, "except_reset_assertion", "always"), false);
  check("mux-controller-obligation", replace(m, "serialized_break_before_make", "cross_coupled"),
        false);
  check("mux-reset-output-clock", replace(m, "%raw, %mux", "%raw, %clk"), false);
  check("mux-output-parent-release", replace(m, "%mux, %safeg", "%mux, %safea"), false);
  check("mux-output-stage-count",
        replace(m, "destination_domain = @g, stages = 2", "destination_domain = @g, stages = 3"),
        false);
  check("mux-resetless-alternate", m + domain("alias") + bind("alias", "mux"), false,
        "every mux-clock consumer");
  check("mux-safe-alternate",
        m + domain("alias", "async_assert_sync_release") + sync("aliasreset", "alias", "mux") +
            bind("alias", "mux", "aliasreset"),
        true);
  check("mux-independent-output-release",
        m + domain("alias", "async_assert_sync_release") + bind("alias", "mux", "safeg"), true);
  check("mux-unrelated-alternate",
        m + domain("alias", "async_assert_sync_release") + port("unrelated", resetType, "alias") +
            "%raw2 = \"nodal.reset_boundary\"(%unrelated) <{reset_polarity = \"active_high\", "
            "release = \"raw\", metadata = {}}> : (" +
            resetType + ") -> " + resetType + "\n" + sync("aliasreset", "alias", "mux", "raw2") +
            bind("alias", "mux", "aliasreset"),
        false, "exact common assertion roots");
  check("gate-parent-cycle", replace(g, "parent = @a", "parent = @g"), false);
  check("mux-second-parent-cycle", replace(m, "second_parent = @b", "second_parent = @g"), false);

  const auto boolOr = [](std::string name, std::string a, std::string b) {
    return "%" + name + " = \"nodal.digital_expr\"(%" + a + ", %" + b +
           ") <{operator_name = \"bool_or\", metadata = {semantic_path = \"Fixture." + name +
           "\"}}> : (" + boolType + ", " + boolType + ") -> " + boolType + "\n";
  };
  const auto insertBeforeGate = [&](std::string extra) {
    return replace(g, "%gate =", extra + "%gate =");
  };
  check("gate-parent-combinational-control",
        replace(insertBeforeGate(boolOr("joined", "enable", "zero")),
                "%enable, %zero) <{parent_domains", "%joined, %zero) <{parent_domains"),
        true);
  check("gate-external-combinational-control",
        replace(replace(replace(insertBeforeGate(boolOr("joined", "enable", "zero")),
                                "type = " + boolType + ", domain = @a",
                                "type = " + boolType + ", domain = @none"),
                        "%enable, %zero) <{parent_domains", "%joined, %zero) <{parent_domains"),
                "control_domains = [@a", "control_domains = [\"external\""),
        true);
  check("gate-two-external-rejected",
        replace(replace(replace(insertBeforeGate(port("extra", boolType, "") +
                                                 boolOr("joined", "enable", "extra")),
                                "type = " + boolType + ", domain = @a",
                                "type = " + boolType + ", domain = @none"),
                        "%enable, %zero) <{parent_domains", "%joined, %zero) <{parent_domains"),
                "control_domains = [@a", "control_domains = [\"external\""),
        false, "actual source evidence");
  check("gate-mixed-domain-expression",
        domain("b") + replace(insertBeforeGate(port("other", boolType, "b") +
                                               boolOr("joined", "enable", "other")),
                              "%enable, %zero) <{parent_domains",
                              "%joined, %zero) <{parent_domains"),
        false, "NODAL-CDC-001");
  check("gate-mixed-external-expression",
        replace(insertBeforeGate(port("other", boolType, "") + boolOr("joined", "enable", "other")),
                "%enable, %zero) <{parent_domains", "%joined, %zero) <{parent_domains"),
        false, "NODAL-CDC-001");
  const std::string sampleParent =
      "%sample = \"nodal.digital_storage\"() <{name = \"sample\", kind = \"register\", domain = "
      "@g, "
      "metadata = {semantic_path = \"Fixture.sample\"}}> : () -> " +
      boolType +
      "\n"
      "\"nodal.digital_assign\"(%sample, %enable) <{kind = \"register_next\", metadata = "
      "{semantic_path = \"Fixture.write\"}}> : (" +
      boolType + ", " + boolType + ") -> ()\n";
  check("parent-to-gate-data-cdc", g + sampleParent, false, "NODAL-CDC-001");
  check(
      "external-control-cannot-claim-parent",
      replace(g, "type = " + boolType + ", domain = @a", "type = " + boolType + ", domain = @none"),
      false, "NODAL-CDC-001");
  const std::string resetBool = "%resetBool = \"nodal.reset_asserted\"(%safea) <{domain = @a, "
                                "reset_polarity = \"active_high\", "
                                "metadata = {semantic_path = \"Fixture.resetBool\"}}> : (" +
                                resetType + ") -> " + boolType + "\n";
  auto resetSelector =
      replace(replace(m, "%mux =", resetBool + "%mux ="), "%clk2, %select,", "%clk2, %resetBool,");
  check("reset-level-actual-source",
        replace(resetSelector, "control_domains = [@b]", "control_domains = [@a]"), true);
  check("reset-level-not-constant",
        replace(resetSelector, "control_domains = [@b]", "control_domains = [\"constant\"]"), false,
        "NODAL-CDC-001");
  const std::string pulse =
      "%pulse = \"nodal.crossing\"(%select) <{source_domain = @b, destination_domain = @a, "
      "kind = \"pulse\", contract = \"pulse_v1\", stages = 2 : i64, obligations = {spacing = "
      "\"returned_acknowledgement\", width = \"single_source_cycle\", reset_epoch = "
      "\"common_assertion\"}, "
      "metadata = {semantic_path = \"Fixture.pulse\"}}> : (" +
      boolType + ") -> " + boolType + "\n";
  auto pulseSelector =
      replace(replace(replace(m, "%mux =", pulse + "%mux ="), "%clk2, %select,", "%clk2, %pulse,"),
              "control_domains = [@b]", "control_domains = [@a]");
  check("pulse-cannot-masquerade-select", pulseSelector, false, "NODAL-CDC-003");
  // Query identity and the exact aggregate state budget independently of generic verify.
  auto parsed =
      parse(g + domain("g2", "none", "parent = @a, ") + gate("gate2", "g2") + bind("g2", "gate2"));
  ++count;
  if (!parsed || failed(verify(*parsed))) {
    ++failures;
  } else {
    auto module = *parsed->getOps<nodal::ModuleOp>().begin();
    SmallVector<Operation *> gates;
    for (auto gate : module.getOps<nodal::ClockGateOp>())
      gates.push_back(gate);
    auto first = nodal::resolveClock(gates[0]->getResult(0), gates[0]);
    auto second = nodal::resolveClock(gates[1]->getResult(0), gates[1]);
    if (failed(first) || failed(second) || *first == *second)
      ++failures;
    ++count;
    nodal::SequentialModelLimits limits;
    limits.maximumNormalizedOperations = 1;
    if (succeeded(nodal::verifyClockModel(module, limits)))
      ++failures;
  }
  parsed = parse(m);
  ++count;
  if (!parsed || failed(verify(*parsed)))
    ++failures;
  else {
    auto module = *parsed->getOps<nodal::ModuleOp>().begin();
    nodal::SequentialModelLimits limits;
    limits.maximumNormalizedOperations = 27;
    if (succeeded(nodal::verifyClockModel(module, limits)))
      ++failures; // 22 + three release pairs
    ++count;
    limits.maximumNormalizedOperations = 28;
    if (failed(nodal::verifyClockModel(module, limits)))
      ++failures;
  }
  llvm::outs() << "Clock model: " << count - failures << "/" << count << " passed\n";
  return failures ? 1 : 0;
}
