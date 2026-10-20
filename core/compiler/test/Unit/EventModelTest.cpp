#include "EventTestFixtures.h"
#include "WaiverTestFixtures.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/Support/raw_ostream.h"

using namespace mlir;
using namespace event_test;
int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  std::string diagnostics;
  ScopedDiagnosticHandler handler(&context, [&](Diagnostic &d) {
    llvm::raw_string_ostream os(diagnostics);
    d.print(os);
    os << '\n';
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
  auto checkIR = [&](StringRef name, std::string ir, bool expected, StringRef message = "") {
    diagnostics.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(ir, ParserConfig(&context, false));
    report(name, parsed && succeeded(verify(*parsed)) == expected &&
                     (message.empty() || StringRef(diagnostics).contains(message)));
    return parsed;
  };
  auto check = [&](StringRef name, std::string body, bool expected, StringRef message = "") {
    return checkIR(name, wrap(body), expected, message);
  };
  auto base = fixture();
  check("frozen-root-bool", base, true);
  check("falling-edge", replace(base, "edge = \"rising\"", "edge = \"falling\""), true);
  check("old-state-self-hold", setup() + process() + update("output"), true);
  check("external-input-capture",
        setup() + port("data") + process("event", "rising", "%output", "%data", 1, 1, bit, bit) +
            update("data"),
        true);
  check("trigger-as-rhs-exact-capture",
        setup() + process("event", "rising", "%output", "%trigger", 1, 1, bit, bit) +
            update("trigger"),
        true);
  check("simultaneous-old-state-pair",
        setup() + port("second", "output") +
            process("event", "rising", "%output, %second", "", 2, 0, bit + ", " + bit) +
            update("second") + update("output", "second"),
        true);
  for (std::string type : {"!nodal.uint<8>", "!nodal.shaped<\"3\", !nodal.uint<8>>",
                           "!nodal.struct<\"Payload\", [{name = \"valid\", type = !nodal.bits<1>}, "
                           "{name = \"data\", type = !nodal.uint<7>}]>",
                           "!nodal.bits<4096>"})
    check("fixed-whole-aggregate-abi",
          setup(type) + port("data", "input", type) +
              process("event", "rising", "%output", "%data", 1, 1, type, type) +
              update("data", "output", "event", type),
          true);
  check("separate-process-output",
        base + port("second", "output") + process("other", "falling", "%second") +
            update("one", "second", "other"),
        true);
  check("descriptor-only", replace(base, "contract = \"root_event_v1\", ", ""), false);
  check("unknown-edge", replace(base, "edge = \"rising\"", "edge = \"either\""), false,
        "closed executable");
  check("wrong-obligation", replace(base, "known_levels_definite_edges", "any_transition"), false,
        "obligations");
  check("forged-root-metadata",
        replace(base, "semantic_path = \"Fixture.event\"",
                "semantic_path = \"Fixture.event\", root_only = true"),
        false, "typed fields");
  checkIR("missing-actual-root", replace(wrap(base), "nodal.root.module = @Fixture", ""), false,
          "actual selected");
  checkIR("forged-actual-root",
          replace(wrap(base), "nodal.root.module = @Fixture", "nodal.root.module = @Missing"),
          false, "actual selected");
  check("trigger-expression", replace(base, "(%trigger, %output)", "(%one, %output)"), false,
        "exact unannotated root scalar Bool input");
  check("trigger-output", replace(base, "(%trigger, %output)", "(%output, %output)"), false,
        "exact unannotated root scalar Bool input");
  check("attributed-trigger", replace(base, "domain = @none", "domain = @foreign"), false,
        "exact unannotated root scalar Bool input");
  check("attributed-storage",
        replace(base, "direction = \"output\", domain = @none",
                "direction = \"output\", domain = @foreign"),
        false, "unannotated");
  check("none-is-real-domain",
        "\"nodal.domain\"() <{sym_name = \"none\", edge = \"rising\", "
        "reset_policy = \"none\", metadata = {}}> : () -> ()\n" +
            base,
        false, "unannotated");
  check("trigger-internal-driver-alias",
        base +
            "%triggerAlias = \"nodal.port_value\"() <{port = @trigger, metadata = {}}> : () -> " +
            bit + "\n" + waiver_test::assign("triggerAlias", "one"),
        false, "exact writable output");
  check("capture-internal-driver",
        setup() + port("data") + literal() +
            process("event", "rising", "%output", "%data", 1, 1, bit, bit) + update("data") +
            waiver_test::assign("data", "one"),
        false, "exact writable output");
  check("missing-update", setup() + process(), false, "exactly one event update");
  check("duplicate-update", base + update(), false, "overlapping");
  check("continuous-writer-conflict",
        base +
            "\"nodal.digital_assign\"(%output, %one) <{kind = \"continuous\", metadata = {}}> : (" +
            bit + ", " + bit + ") -> ()\n",
        false, "overlapping");
  check("continuous-cannot-impersonate-event",
        replace(base, "kind = \"event_update\"", "kind = \"continuous\""), false,
        "only event_update");
  check("unresolved-process",
        replace(base, "event_process = @\"Fixture.event\"", "event_process = @\"Fixture.missing\""),
        false, "actual local event process");
  check("two-processes-same-output", base + process("other") + update("one", "output", "other"),
        false, "more than one process owner");
  check("foreign-process-capture",
        base + port("second", "output") + process("other", "rising", "%second") +
            update("output", "second", "other"),
        false, "another process");
  check("missing-capture", setup() + port("data") + process() + update("data"), false,
        "exactly match actual RHS");
  check("unused-capture",
        setup() + port("data") + literal() +
            process("event", "rising", "%output", "%data", 1, 1, bit, bit) + update(),
        false, "exactly match actual RHS");
  check("attributed-capture",
        setup() + port("data", "input", bit, "foreign") +
            process("event", "rising", "%output", "%data", 1, 1, bit, bit) + update("data"),
        false, "unannotated");
  const std::string outputAlias =
      "%other = \"nodal.port_value\"() <{port = @output, metadata = {}}> : () -> " + bit + "\n";
  check("physical-output-alias-old-state", setup() + outputAlias + process() + update("other"),
        true);
  check("physical-output-alias-writer-conflict", base + outputAlias + update("one", "other"), false,
        "overlapping");
  check("duplicate-target-alias",
        setup() + outputAlias + literal() +
            process("event", "rising", "%output, %other", "", 2, 0, bit + ", " + bit) + update(),
        false, "duplicate physical");
  check("empty-process", replace(base, "array<i32: 1, 1, 0>", "array<i32: 1, 0, 1>"), false,
        "nonempty storage");
  const std::string ordinary = waiver_test::domain("a") + waiver_test::domain("b");
  check("event-state-ordinary-sampling", ordinary + base + waiver_test::sample("output", "b"),
        false, "event-owned state");
  check("event-state-waiver-cannot-launder",
        ordinary + base + waiver_test::waiver("waived", "output") + waiver_test::sample(), false,
        "event-owned state");
  check("event-state-crossing-cannot-invent-domain",
        ordinary + base + waiver_test::crossing("crossed", "output", "a", "b"), false,
        "event-owned state");
  check("event-alias-ordinary-sampling",
        ordinary + base + waiver_test::expression("aliased", "output", "output") +
            waiver_test::sample("aliased"),
        false, "event-owned state");
  auto gate = [&](std::string enable) {
    const std::string clock = "!nodal.capability<\"clock\">";
    return "\"nodal.domain\"() <{sym_name = \"g\", edge = \"rising\", reset_policy = \"none\", "
           "parent = @a, metadata = {}}> : () -> ()\n" +
           literal("zero", 0) + "%gated = \"nodal.clock_gate\"(%aClock, %" + enable +
           ", %zero) <{parent_domains = [@a], "
           "domain = @g, edge = \"rising\", contract = \"gate_v1\", control_domains = "
           "[\"external\", \"constant\"], "
           "obligations = {control_timing = \"inactive_phase_aperture\", startup = "
           "\"known_after_inactive_phase\"}, "
           "metadata = {semantic_path = \"Fixture.gated\"}}> : (" +
           clock + ", " + bit + ", " + bit + ") -> " + clock + "\n" +
           "\"nodal.local_domain_binding\"(%gated) <{domain = @g, reset_polarity = "
           "\"active_high\", metadata = {}}> : (" +
           clock + ") -> ()\n";
  };
  check("event-state-cannot-control-clock", ordinary + base + gate("output"), false,
        "event state or trigger");
  check("event-trigger-cannot-control-clock", ordinary + base + gate("trigger"), false,
        "event state or trigger");
  check("event-trigger-alias-cannot-control-clock",
        ordinary + base + waiver_test::expression("aliased", "trigger", "trigger") +
            gate("aliased"),
        false, "event state or trigger");
  const std::string reg =
      "%old = \"nodal.digital_storage\"() <{name = \"old\", kind = \"register\", "
      "domain = @a, metadata = {}}> : () -> " +
      bit + "\n";
  check("ordinary-state-capture", ordinary + setup() + reg + process() + update("old"), false,
        "ordinary domain-owned state");
  check("waiver-hidden-capture",
        ordinary + setup() + waiver_test::port("input", "a") + waiver_test::waiver() + process() +
            update("waived"),
        false, "clocked, waived");
  check("crossing-hidden-capture",
        ordinary + setup() + waiver_test::port("input", "a") +
            waiver_test::crossing("crossed", "input", "a", "b") + process() + update("crossed"),
        false, "clocked, waived");
  check("forged-update-extra-field",
        replace(base, "Fixture.output.update\"}}> : (",
                "Fixture.output.update\"}}> {safe = true} : ("),
        false, "closed process identity");
  check("undeclared-update-target",
        setup() + port("second", "output") + literal() + process() + update("one", "second"), false,
        "declared storage owner");
  auto child = waiver_test::module("", "Child");
  checkIR("event-bearing-nonroot-library",
          "module attributes {nodal.root.module = @Child} {" + waiver_test::module(base) + child +
              "}",
          false, "actual selected");
  std::string inst = "\"nodal.instance\"() <{sym_name = \"used\", module = @Fixture, "
                     "parameter_bindings = {}, domain_bindings = {}, metadata = {}}> : () -> ()\n";
  checkIR("event-root-cannot-be-instantiated-unused",
          "module attributes {nodal.root.module = @Fixture} {" + waiver_test::module(base) +
              waiver_test::module(inst, "Unused") + "}",
          false, "cannot be instantiated");
  const std::string clock = "!nodal.capability<\"clock\">";
  const std::string consumer =
      "\"nodal.domain_requirement\"() <{sym_name = \"req\", metadata = {}}> : () -> ()\n"
      "%reqClock = \"nodal.requirement_clock\"() <{requirement = @req, metadata = {}}> : () -> " +
      clock + "\n" + port("input", "input", bit, "req") + waiver_test::sample("input", "req");
  const std::string consumeEvent =
      "\"nodal.instance\"() <{sym_name = \"child\", module = @Child, parameter_bindings = {}, "
      "domain_bindings = {req = @a}, metadata = {}}> : () -> ()\n"
      "\"nodal.instance_domain_binding\"(%aClock) <{instance = @child, requirement = @req, actual "
      "= @a, "
      "origin = \"named\", metadata = {}}> : (" +
      clock + ") -> ()\n" +
      "%childIn = \"nodal.instance_port\"() <{instance = @child, port = \"input\", direction = "
      "\"input\", metadata = {}}> : () -> " +
      bit + "\n" + waiver_test::assign("childIn", "output");
  checkIR("event-provenance-survives-child-input",
          "module attributes {nodal.root.module = @Fixture} {" +
              waiver_test::module(ordinary + base + consumeEvent) +
              waiver_test::module(consumer, "Child") + "}",
          false, "event-owned state");
  const std::string vector = "!nodal.shaped<\"4\", !nodal.bits<1>>";
  auto map = [&](std::string source, bool capture = false) {
    return "%mapped = \"nodal.collection\"(%" + source + (capture ? ", %old" : "") +
           ") <{kind = \"map\", region_id = \"Fixture.map\", operandSegmentSizes = array<i32: 1, "
           "0, " +
           (capture ? "1" : "0") + ">, metadata = {semantic_path = \"Fixture.map\"}}> ({\n" +
           "^bb0(%element: " + bit + (capture ? ", %captured: " + bit : "") + "):\n" +
           (capture ? "%value = \"nodal.digital_expr\"(%element, %captured) <{operator_name = "
                      "\"bool_and\", metadata = {}}> : (" +
                          bit + ", " + bit + ") -> " + bit + "\n"
                    : "%value = \"nodal.digital_expr\"(%element) <{operator_name = \"bool_not\", "
                      "metadata = {}}> : (" +
                          bit + ") -> " + bit + "\n") +
           "\"nodal.collection_yield\"(%value) <{metadata = {}}> : (" + bit + ") -> ()\n" +
           "}) : (" + vector + (capture ? ", " + bit : "") + ") -> " + vector + "\n";
  };
  auto mapHeader = setup(vector) + port("data", "input", vector) +
                   process("event", "rising", "%output", "%data", 1, 1, vector, vector);
  check("pure-map-retains-actual-input-capture",
        mapHeader + map("data") + update("mapped", "output", "event", vector), true);
  const std::string constantMap = replace(map("data"),
                                          "%value = \"nodal.digital_expr\"(%element) "
                                          "<{operator_name = \"bool_not\", metadata = {}}> : (" +
                                              bit + ") -> " + bit + "\n",
                                          literal("value"));
  check("pure-constant-map-has-no-input-observation",
        setup(vector) + port("data", "input", vector) +
            process("event", "rising", "%output", "", 1, 0, vector) + constantMap +
            update("mapped", "output", "event", vector),
        true);
  check("pure-constant-map-cannot-overclaim-captures",
        mapHeader + constantMap + update("mapped", "output", "event", vector), false,
        "exactly match actual RHS");
  check("pure-map-old-event-state",
        setup(vector) + process("event", "rising", "%output", "", 1, 0, vector) + map("output") +
            update("mapped", "output", "event", vector),
        true);
  check("pure-map-hidden-clocked-capture",
        ordinary + reg + mapHeader + map("data", true) +
            update("mapped", "output", "event", vector),
        false, "ordinary domain-owned state");
  check("pure-map-capture-inventory-cannot-be-omitted",
        setup(vector) + port("data", "input", vector) +
            process("event", "rising", "%output", "", 1, 0, vector) + map("data") +
            update("mapped", "output", "event", vector),
        false, "exactly match actual RHS");
  check("over-width-event-storage",
        setup("!nodal.bits<4097>") +
            process("event", "rising", "%output", "", 1, 0, "!nodal.bits<4097>") +
            update("output", "output", "event", "!nodal.bits<4097>"),
        false, "1 through 4096");
  for (unsigned count : {64u, 65u}) {
    std::string body = port("trigger"), targets, types, updates;
    for (unsigned i = 0; i < count; ++i) {
      std::string name = "wide" + std::to_string(i);
      body += port(name, "output", "!nodal.bits<4096>");
      if (i) {
        targets += ", ";
        types += ", ";
      }
      targets += "%" + name;
      types += "!nodal.bits<4096>";
      updates += update(name, name, "event", "!nodal.bits<4096>");
    }
    body += process("event", "rising", targets, "", count, 0, types) + updates;
    check("exact-state-budget-boundary", body, count == 64, count == 64 ? "" : "262144-bit");
  }
  auto alias = check("event-alias-provenance", setup() + process() + update("output"), true);
  if (alias) {
    nodal::ModuleOp owner;
    Value output;
    alias->walk([&](nodal::ModuleOp op) { owner = op; });
    alias->walk([&](nodal::PortValueOp op) {
      if (op->getAttrOfType<FlatSymbolRefAttr>("port").getValue() == "output")
        output = op.getResult();
    });
    auto graph = nodal::analyzeDigitalAssignmentDependencies(
        owner, {{output, &owner.getBody().front().back()}});
    report("canonical-event-state-cut",
           succeeded(graph) && graph->eventUpdates.size() == 1 &&
               graph->nodes[graph->roots.front()].eventProcess &&
               graph->nodes[graph->roots.front()].dependencies.empty());
    nodal::DigitalAssignmentProofLimits limits;
    limits.maximumNodes = 1;
    report("represented-graph-budget-fails-closed",
           failed(nodal::analyzeDigitalAssignmentDependencies(owner, {}, limits)));
  }
  llvm::outs() << "event model: " << cases - failures << "/" << cases << " passed\n";
  return failures ? 1 : 0;
}
