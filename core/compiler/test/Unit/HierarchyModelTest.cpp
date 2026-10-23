#include "nodal/Dialect/Nodal/HierarchyModel.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Pass/PassManager.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"
#include "nodal/Dialect/Nodal/StaticPredicateModel.h"
#include "nodal/Transforms/Passes.h"

#include "llvm/Support/raw_ostream.h"

#include <chrono>
using namespace mlir;
namespace nodal {
std::unique_ptr<mlir::Pass> createNormalizeSequentialPass();
}
namespace {
const std::string clockType = "!nodal.capability<\"clock\">";
const std::string bit = "!nodal.bits<1>";
std::string module(StringRef name, const std::string &body) {
  return "\"nodal.module\"() <{sym_name = \"" + name.str() + "\", metadata = {}}> ({\n" + body +
         "}) : () -> ()\n";
}
std::string port(StringRef name, StringRef direction, StringRef domain) {
  return "\"nodal.port\"() <{sym_name = \"" + name.str() + "\", type = " + bit +
         ", direction = \"" + direction.str() + "\", domain = @" + domain.str() +
         ", metadata = {}}> : () -> ()\n%" + name.str() + " = \"nodal.port_value\"() <{port = @" +
         name.str() + ", metadata = {}}> : () -> " + bit + "\n";
}
std::string domain(StringRef name) {
  std::string n = name.str();
  return "\"nodal.domain\"() <{sym_name = \"" + n +
         "\", edge = \"rising\", reset_policy = \"none\", metadata = {}}> : () -> ()\n"
         "\"nodal.port\"() <{sym_name = \"clock_" +
         n + "\", type = " + clockType + ", direction = \"input\", domain = @" + n +
         ", metadata = {}}> : () -> ()\n"
         "%clock_" +
         n + " = \"nodal.port_value\"() <{port = @clock_" + n + ", metadata = {}}> : () -> " +
         clockType +
         "\n"
         "\"nodal.local_domain_binding\"(%clock_" +
         n + ") <{domain = @" + n + ", reset_polarity = \"active_high\", metadata = {}}> : (" +
         clockType + ") -> ()\n";
}
std::string requirement(StringRef name) {
  std::string n = name.str();
  return "\"nodal.domain_requirement\"() <{sym_name = \"" + n +
         "\", metadata = {}}> : () -> ()\n%" + n +
         "_clock = \"nodal.requirement_clock\"() <{requirement = @" + n +
         ", metadata = {}}> : () -> " + clockType + "\n";
}
std::string assign(StringRef target, StringRef value, StringRef kind = "continuous") {
  return "\"nodal.digital_assign\"(%" + target.str() + ", %" + value.str() + ") <{kind = \"" +
         kind.str() + "\", metadata = {}}> : (" + bit + ", " + bit + ") -> ()\n";
}
std::string binding(StringRef instance, StringRef requirement, StringRef actual,
                    StringRef origin = "named") {
  return "\"nodal.instance_domain_binding\"(%clock_" + actual.str() + ") <{instance = @" +
         instance.str() + ", requirement = @" + requirement.str() + ", actual = @" + actual.str() +
         ", origin = \"" + origin.str() + "\", metadata = {}}> : (" + clockType +
         ") -> () loc(\"hierarchy-source.scala\":31:7)\n";
}
std::string proxy(StringRef name, StringRef instance, StringRef portName, StringRef direction) {
  return "%" + name.str() + " = \"nodal.instance_port\"() <{instance = @" + instance.str() +
         ", port = \"" + portName.str() + "\", direction = \"" + direction.str() +
         "\", metadata = {}}> : () -> " + bit + "\n";
}
std::string instance(StringRef name, StringRef source = "a", StringRef destination = "b",
                     StringRef input = "x") {
  return "\"nodal.instance\"() <{sym_name = \"" + name.str() +
         "\", module = @Child, parameter_bindings = {}, domain_bindings = {src = @" + source.str() +
         ", dst = @" + destination.str() + "}, metadata = {}}> : () -> ()\n" +
         binding(name, "src", source) + binding(name, "dst", destination) +
         proxy(name.str() + "_in", name, "in", "input") +
         proxy(name.str() + "_out", name, "out", "output") + assign(name.str() + "_in", input);
}
std::string crossing(StringRef name, StringRef input, StringRef source, StringRef dest,
                     StringRef owner) {
  return "%" + name.str() + " = \"nodal.crossing\"(%" + input.str() + ") <{source_domain = @" +
         source.str() + ", destination_domain = @" + dest.str() +
         ", kind = \"sync\", contract = \"level_v1\", stages = 2 : i64, metadata = {semantic_path "
         "= \"" +
         owner.str() + "." + name.str() + "\"}}> : (" + bit + ") -> " + bit + "\n";
}
std::string fixture(bool siblings = false, bool reconverge = false, bool distinct = false,
                    bool forged = false, bool parentCrossing = false, bool state = true) {
  std::string top = domain("a") + domain("b") + port("x", "input", "a") + port("y", "input", "b");
  if (parentCrossing)
    top += crossing("parent_sync", "x", "a", "b", "Top");
  top += instance("left", parentCrossing ? "b" : "a", parentCrossing ? "a" : "b",
                  parentCrossing ? "parent_sync" : "x");
  if (siblings)
    top += instance("right", distinct ? "b" : "a", distinct ? "a" : "b", distinct ? "y" : "x");
  if (reconverge)
    top += "%joined = \"nodal.digital_expr\"(%left_out, %right_out) <{operator_name = \"or\", "
           "metadata = {}}> : (" +
           bit + ", " + bit + ") -> " + bit + "\n";
  std::string result = reconverge ? "joined" : "left_out";
  const std::string target = parentCrossing ? "a" : "b";
  if (state) {
    top += "%q = \"nodal.digital_storage\"() <{name = \"q\", kind = \"register\", domain = @" +
           target + ", metadata = {}}> : () -> " + bit + "\n" +
           assign("q", result, "register_next");
    result = "q";
  }
  top += port("out", "output", target) + assign("out", result);
  if (siblings && !reconverge)
    top += port("other", "output", distinct ? "a" : "b") + assign("other", "right_out");
  std::string child = requirement("src") + requirement("dst") + port("in", "input", "src") +
                      port("out", "output", "dst") + crossing("sync", "in", "src", "dst", "Child") +
                      assign("out", forged ? "in" : "sync");
  return "module attributes {nodal.root.module = @Top} {\n" + module("Top", top) +
         module("Child", child) + "}\n";
}
std::string replace(std::string source, StringRef from, StringRef to) {
  auto pos = source.find(from.str());
  if (pos == std::string::npos)
    llvm::report_fatal_error("missing hierarchy test mutation");
  return source.replace(pos, from.size(), to.str());
}

std::string generatedFamily(StringRef name, StringRef count, const std::string &body) {
  std::string n = name.str();
  auto literal = [&](StringRef suffix, StringRef value) {
    return "%" + n + "_" + suffix.str() + " = \"nodal.const_literal\"() <{value = " + value.str() +
           " : i64, spelling = \"" + value.str() + "\", metadata = {}}> : () -> i64\n";
  };
  return literal("lo", "0") + literal("hi", count) + literal("step", "1") + "%" + n +
         " = \"nodal.generate\"(%" + n + "_lo, %" + n + "_hi, %" + n +
         "_step) <{induction = \"i\", region_id = \"Top." + n + "\", induction_path = \"Top." + n +
         ".index\", metadata = {maximum_trip_count = " + count.str() +
         " : i64, semantic_path = \"Top." + n + "\"}}> ({^bb0(%" + n + "_index: i64):\n" + body +
         "}) : (i64, i64, i64) -> !nodal.generated_family\n";
}
std::string generatedChild(StringRef family) {
  std::string scope = "generated_owner = \"Top." + family.str() +
                      "\", generated_induction = \"Top." + family.str() + ".index\", ";
  return "\"nodal.instance\"() <{sym_name = \"lane\", module = @Lane, parameter_bindings = {}, "
         "domain_bindings = {req = @a}, " +
         scope + "metadata = {}}> : () -> ()\n" + binding("lane", "req", "a") +
         proxy("lane_in", "lane", "in", "input") + proxy("lane_out", "lane", "out", "output") +
         assign("lane_in", "x") +
         "%sink = \"nodal.digital_storage\"() <{name = \"sink\", kind = \"register\", "
         "domain = @a, metadata = {}}> : () -> " +
         bit + "\n" + assign("sink", "lane_out", "register_next");
}
std::string generatedFixture(StringRef count = "3", bool nested = false) {
  std::string child = requirement("req") + port("in", "input", "req") +
                      port("out", "output", "req") + assign("out", "in");
  std::string family = nested ? "outer.lanes" : "lanes";
  std::string body = generatedFamily(family, count, generatedChild(family));
  if (nested)
    body = generatedFamily("outer", "2", body);
  return "module attributes {nodal.root.module = @Top} {\n" +
         module("Top", domain("a") + domain("b") + port("x", "input", "a") + body) +
         module("Lane", child) + "}\n";
}

std::string guardedGeneratedFixture(bool rawOuter = false) {
  std::string child = generatedChild("guard.then.lanes");
  child = replace(child, "instance_domain_binding\"(%clock_a)",
                  rawOuter ? "instance_domain_binding\"(%clock_a)"
                           : "instance_domain_binding\"(%arm_clock)");
  child = replace(child, assign("lane_in", "x"), assign("lane_in", "arm_x"));
  std::string branch =
      "%guard_zero = \"nodal.const_literal\"() <{value = 0 : i64, spelling = \"0\", metadata = "
      "{semantic_path = \"Top.guard_zero\"}}> : () -> i64\n"
      "%guard_one = \"nodal.const_literal\"() <{value = 1 : i64, spelling = \"1\", metadata = "
      "{semantic_path = \"Top.guard_one\"}}> : () -> i64\n"
      "%guard = \"nodal.static_compare\"(%guard_one, %guard_zero) <{comparison = \"gt\", metadata "
      "= {semantic_path = \"Top.guard.predicate\"}}> : (i64, i64) -> !nodal.static_predicate\n"
      "\"nodal.structural_if\"(%guard, %clock_a, %x) <{contract_version = \"structural-if-v1\", "
      "region_id = \"Top.guard\", operandSegmentSizes = array<i32: 1, 2, 0>, metadata = "
      "{semantic_path = \"Top.guard\"}}> ({^bb0(%arm_clock: " +
      clockType + ", %arm_x: " + bit + "):\n" + generatedFamily("guard.then.lanes", "3", child) +
      "\"nodal.structural_yield\"() <{metadata = {semantic_path = \"Top.guard.then.yield\"}}> : () "
      "-> ()\n"
      "}, {\"nodal.structural_yield\"() <{metadata = {semantic_path = \"Top.guard.else.yield\"}}> "
      ": () -> ()}) : (!nodal.static_predicate, " +
      clockType + ", " + bit + ") -> ()\n";
  return "module attributes {nodal.root.module = @Top} {\n" +
         module("Top", domain("a") + domain("b") + port("x", "input", "a") + branch) +
         module("Lane", requirement("req") + port("in", "input", "req") +
                            port("out", "output", "req") + assign("out", "in")) +
         "}\n";
}

std::string generatedResetFixture() {
  const std::string resetType = "!nodal.capability<\"reset\">";
  const std::string resetsType = "!nodal.shaped<\"2\", " + resetType + ">";
  std::string controls =
      "\"nodal.port\"() <{sym_name = \"resets\", type = " + resetsType +
      ", direction = \"input\", domain = @a, metadata = {}}> : () -> ()\n"
      "%resets = \"nodal.port_value\"() <{port = @resets, metadata = {}}> : () -> " +
      resetsType +
      "\n"
      "%r0 = \"nodal.constant\"() <{value = 0 : index, metadata = {}}> : () -> index\n"
      "%r1 = \"nodal.constant\"() <{value = 1 : index, metadata = {}}> : () -> index\n"
      "%reset0 = \"nodal.shape_index\"(%resets, %r0) <{metadata = {}}> : (" +
      resetsType + ", index) -> " + resetType +
      "\n"
      "%reset1 = \"nodal.shape_index\"(%resets, %r1) <{metadata = {}}> : (" +
      resetsType + ", index) -> " + resetType + "\n";
  std::string actualDomain =
      replace(domain("a"), "reset_policy = \"none\"", "reset_policy = \"sync\"");
  actualDomain = replace(actualDomain, "\"nodal.local_domain_binding\"(%clock_a)",
                         controls + "\"nodal.local_domain_binding\"(%clock_a, %reset0)");
  actualDomain = replace(actualDomain, ": (" + clockType + ") -> ()",
                         ": (" + clockType + ", " + resetType + ") -> ()");
  std::string child = generatedChild("lanes");
  std::string bound = replace(binding("lane", "req", "a"), "(%clock_a)", "(%clock_a, %reset0)");
  bound = replace(bound, ": (" + clockType + ") -> ()",
                  ": (" + clockType + ", " + resetType + ") -> ()");
  child = replace(child, binding("lane", "req", "a"), bound);
  return "module attributes {nodal.root.module = @Top} {\n" +
         module("Top",
                actualDomain + port("x", "input", "a") + generatedFamily("lanes", "3", child)) +
         module("Lane", requirement("req") + port("in", "input", "req") +
                            port("out", "output", "req") + assign("out", "in")) +
         "}\n";
}

std::string conditionalGeneratedFixture(bool complete) {
  std::string effect =
      "%condition_one = \"nodal.const_literal\"() <{value = 1 : i64, spelling = \"1\", metadata = "
      "{semantic_path = \"Top.lanes.one\"}}> : () -> i64\n"
      "%condition = \"nodal.static_compare\"(%lanes_index, %condition_one) <{comparison = \"lt\", "
      "metadata = {semantic_path = \"Top.lanes.condition\"}}> : (i64, i64) -> "
      "!nodal.static_predicate\n"
      "\"nodal.structural_if\"(%condition, %lane_in, %x" +
      std::string(complete ? ", %lane_in, %x" : "") +
      ") <{contract_version = \"structural-if-v1\", region_id = \"Top.lanes.choice\", "
      "operandSegmentSizes = array<i32: 1, 2, " +
      (complete ? "2" : "0") +
      ">, metadata = {semantic_path = \"Top.lanes.choice\"}}> ({^bb0(%in_t: " + bit +
      ", %x_t: " + bit + "):\n" + assign("in_t", "x_t") +
      "\"nodal.structural_yield\"() <{metadata = {semantic_path = "
      "\"Top.lanes.choice.then.yield\"}}> : () -> ()\n}, {" +
      (complete ? "^bb0(%in_f: " + bit + ", %x_f: " + bit + "):\n" + assign("in_f", "x_f") : "") +
      "\"nodal.structural_yield\"() <{metadata = {semantic_path = "
      "\"Top.lanes.choice.else.yield\"}}> : () -> ()\n}) : (!nodal.static_predicate, " +
      bit + ", " + bit + (complete ? ", " + bit + ", " + bit : "") + ") -> ()\n";
  return replace(generatedFixture(), assign("lane_in", "x"), effect);
}

std::string crossingFamilyFixture(bool generated) {
  std::string body = instance("left");
  if (generated)
    body = replace(body, "module = @Child,",
                   "module = @Child, generated_owner = \"Top.lanes\", generated_induction = "
                   "\"Top.lanes.index\",");
  body += "%q = \"nodal.digital_storage\"() <{name = \"q\", kind = \"register\", domain = @b, "
          "metadata = {}}> : () -> " +
          bit + "\n" + assign("q", "left_out", "register_next");
  if (generated)
    body = generatedFamily("lanes", "3", body);
  std::string child = requirement("src") + requirement("dst") + port("in", "input", "src") +
                      port("out", "output", "dst") + crossing("sync", "in", "src", "dst", "Child") +
                      assign("out", "sync");
  return "module attributes {nodal.root.module = @Top} {\n" +
         module("Top", domain("a") + domain("b") + port("x", "input", "a") + body) +
         module("Child", child) + "}\n";
}
std::string resolvedFamilyFixture(bool generated) {
  const std::string net = "!nodal.resolved<\"push_pull\", " + bit + ">";
  const std::string tag = "metadata = {interface_abi_version = 1 : i64}";
  const std::string child =
      requirement("req") + port("in", "input", "req") + port("out", "output", "req") +
      "%net = \"nodal.resolved_net\"() <{name = \"net\", metadata = {interface_abi_version = 1 : "
      "i64, placement = \"top_level_pin\", profile = \"portable_boundary_only\"}}> : () -> " +
      net +
      "\n"
      "%driver = \"nodal.net_driver\"(%net) <{driver_id = \"driver\", " +
      tag + "}> : (" + net + ") -> !nodal.driver<" + bit +
      ">\n"
      "%enable = \"nodal.const_literal\"() <{value = 1 : ui1, spelling = \"1\", metadata = {}}> : "
      "() -> " +
      bit +
      "\n"
      "\"nodal.net_drive\"(%net, %driver, %in, %enable) <{mode = \"push_pull\", " +
      tag + "}> : (" + net + ", !nodal.driver<" + bit + ">, " + bit + ", " + bit +
      ") -> ()\n"
      "%write, %enabled = \"nodal.net_split\"(%net) <{" +
      tag + "}> : (" + net + ") -> (" + bit + ", " + bit + ")\n" + assign("out", "write");
  std::string body = generatedChild("lanes");
  if (generated)
    body = generatedFamily("lanes", "3", body);
  else
    body = replace(
        body, "generated_owner = \"Top.lanes\", generated_induction = \"Top.lanes.index\", ", "");
  return "module attributes {nodal.root.module = @Top} {\n" +
         module("Top", domain("a") + port("x", "input", "a") + body) + module("Lane", child) +
         "}\n";
}

} // namespace
int main() {
  MLIRContext context;
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  context.appendDialectRegistry(registry);
  std::string diagnostics;
  ScopedDiagnosticHandler handler(&context, [&](Diagnostic &d) {
    llvm::raw_string_ostream out(diagnostics);
    d.getLocation().print(out);
    out << ": ";
    d.print(out);
    out << '\n';
    return success();
  });
  unsigned count = 0, failures = 0;
  auto check = [&](StringRef name, const std::string &source, bool expected,
                   StringRef message = "") {
    diagnostics.clear();
    ++count;
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    bool ok = parsed && succeeded(verify(*parsed)) == expected;
    ok &= message.empty() || StringRef(diagnostics).contains(message);
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostics;
    }
  };

  // Inspect structural identity separately from later selected dependency routing.
  const std::string exportLane =
      "\"nodal.generated_export\"() <{member = \"child\", access = \"read\", kind = \"instance\", "
      "target = @lane, metadata = {}}> : () -> ()\n";
  const std::string selectedLane =
      "%selected = \"nodal.instance_port\"(%lanes, %lanes_step) <{member = \"child\", instance = "
      "@lane, port = \"out\", direction = \"output\", metadata = {}}> : (!nodal.generated_family, "
      "i64) -> " +
      bit + "\n";
  auto selectedFixture = [&](StringRef count = "3") {
    auto source = generatedFixture(count);
    source =
        replace(source, "}) : (i64, i64, i64) -> !nodal.generated_family",
                exportLane + "}) : (i64, i64, i64) -> !nodal.generated_family\n" + selectedLane);
    return source;
  };
  auto selectedCheck = [&](StringRef name, const std::string &source, bool expected) {
    diagnostics.clear();
    ++count;
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    Operation *selected = nullptr;
    if (parsed)
      parsed->walk([&](nodal::InstancePortOp op) {
        if (op->hasAttr("member"))
          selected = op;
      });
    auto inspected =
        selected ? nodal::inspectInstancePort(selected) : FailureOr<nodal::InstancePort>(failure());
    bool ok = parsed && (succeeded(inspected) == expected);
    if (expected && succeeded(inspected))
      ok &= inspected->selection == selected &&
            inspected->instance->getParentOp() != selected->getParentOp();
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostics;
    }
  };
  selectedCheck("selected-instance-exact-target", selectedFixture(), true);
  selectedCheck("selected-instance-foreign-symbol",
                replace(selectedFixture(), "member = \"child\", instance = @lane",
                        "member = \"child\", instance = @foreign"),
                false);
  selectedCheck("selected-instance-foreign-member",
                replace(selectedFixture(), "member = \"child\", instance = @lane",
                        "member = \"missing\", instance = @lane"),
                false);
  selectedCheck("selected-instance-missing-export", replace(selectedFixture(), exportLane, ""),
                false);
  selectedCheck("selected-instance-absent-ordinal",
                replace(selectedFixture(), "instance_port\"(%lanes, %lanes_step)",
                        "instance_port\"(%lanes, %lanes_hi)"),
                false);
  selectedCheck("selected-instance-no-access-expansion",
                replace(selectedFixture(), "member = \"child\", access = \"read\"",
                        "member = \"child\", access = \"wire\""),
                false);
  selectedCheck(
      "selected-instance-wrong-export-kind",
      replace(selectedFixture(), "kind = \"instance\", target", "kind = \"interface\", target"),
      false);
  selectedCheck("selected-instance-foreign-export-target",
                replace(selectedFixture(), "target = @lane", "target = @foreign"), false);
  selectedCheck(
      "selected-instance-wrong-direction",
      replace(selectedFixture(),
              "member = \"child\", instance = @lane, port = \"out\", direction = \"output\"",
              "member = \"child\", instance = @lane, port = \"out\", direction = \"input\""),
      false);
  selectedCheck("selected-instance-wrong-port",
                replace(selectedFixture(), "member = \"child\", instance = @lane, port = \"out\"",
                        "member = \"child\", instance = @lane, port = \"missing\""),
                false);

  auto selectedObserved = [&](bool two = false, bool same = false, bool state = false,
                              StringRef count = "3") {
    auto source = selectedFixture(count);
    std::string uses = selectedLane + port("selected_observed", "output", "a") +
                       assign("selected_observed", "selected");
    if (two) {
      auto second = replace(selectedLane, "%selected =", "%selected_second =");
      if (!same)
        second = replace(second, "%lanes, %lanes_step", "%lanes, %lanes_lo");
      uses += second + port("second_observed", "output", "a") +
              assign("second_observed", "selected_second");
    }
    source = replace(source, selectedLane, uses);
    if (state)
      source =
          replace(source, assign("out", "in"),
                  "%state = \"nodal.digital_storage\"() <{name = \"state\", kind = \"register\", "
                  "domain = @req, metadata = {}}> : () -> " +
                      bit + "\n" + assign("state", "in", "register_next") + assign("out", "state"));
    return source;
  };
  check("selected-output-retains-child-input-driver", selectedObserved(), true);
  check("selected-two-lanes-retain-independent-contexts", selectedObserved(true), true);
  check("selected-repeat-alias-keeps-one-physical-context", selectedObserved(true, true), true);
  check("selected-two-state-lanes-retain-domain-provenance", selectedObserved(true, false, true),
        true);
  check("selected-output-cannot-hide-missing-child-input",
        replace(selectedObserved(), assign("lane_in", "x"), ""), false);
  check("selected-output-cannot-erase-actual-domain",
        replace(selectedObserved(), assign("selected_observed", "selected"),
                "%foreign = \"nodal.digital_storage\"() <{name = \"foreign\", kind = \"register\", "
                "domain = @b, metadata = {}}> : () -> " +
                    bit + "\n" + assign("foreign", "selected", "register_next") +
                    assign("selected_observed", "foreign")),
        false, "NODAL-CDC-001");

  check("selected-crossing-source-requires-coherent-selection-proof",
        replace(selectedObserved(), assign("selected_observed", "selected"),
                crossing("selected_sync", "selected", "a", "b", "Top") +
                    assign("selected_observed", "selected_sync")),
        false, "selection-qualified coherent source proof");

  auto nonunitSelection = selectedObserved();
  nonunitSelection = replace(
      nonunitSelection, "%lanes_lo = \"nodal.const_literal\"() <{value = 0 : i64, spelling = \"0\"",
      "%lanes_lo = \"nodal.const_literal\"() <{value = 2 : i64, spelling = \"2\"");
  nonunitSelection = replace(
      nonunitSelection, "%lanes_hi = \"nodal.const_literal\"() <{value = 3 : i64, spelling = \"3\"",
      "%lanes_hi = \"nodal.const_literal\"() <{value = 8 : i64, spelling = \"8\"");
  nonunitSelection =
      replace(nonunitSelection,
              "%lanes_step = \"nodal.const_literal\"() <{value = 1 : i64, spelling = \"1\"",
              "%lanes_step = \"nodal.const_literal\"() <{value = 2 : i64, spelling = \"2\"");
  check("selected-nonunit-coordinate-retains-child-routing", nonunitSelection, true);

  auto selectedPhysicalRoute = [&](bool foreign) {
    const std::string shape = "!nodal.shaped<\"2\", " + bit + ">";
    auto index = [&](StringRef name, StringRef ordinal) {
      return "%" + name.str() + " = \"nodal.shape_index\"(%cells, %" + ordinal.str() +
             ") <{metadata = {}}> : (" + shape + ", i64) -> " + bit + "\n";
    };
    std::string parent =
        domain("a") + domain("b") + port("x", "input", "a") + port("y", "input", "b") +
        "%zero = \"nodal.const_literal\"() <{value = 0 : i64, spelling = \"0\", "
        "metadata = {}}> : () -> i64\n"
        "%one = \"nodal.const_literal\"() <{value = 1 : i64, spelling = \"1\", "
        "metadata = {}}> : () -> i64\n"
        "%cells = \"nodal.digital_storage\"() <{name = \"cells\", kind = \"wire\", "
        "metadata = {}}> : () -> " +
        shape + "\n" + index("first", "zero") + index("second", "one") + assign("first", "x") +
        assign("second", "y");
    std::string body =
        "\"nodal.instance\"() <{sym_name = \"lane\", module = @Lane, parameter_bindings = {}, "
        "domain_bindings = {req = @a}, generated_owner = \"Top.lanes\", generated_induction = "
        "\"Top.lanes.index\", metadata = {}}> : () -> ()\n" +
        binding("lane", "req", "a") + proxy("lane_in", "lane", "in", "input") +
        index("lane_data", "lanes_index") + assign("lane_in", "lane_data") + exportLane;
    parent += generatedFamily("lanes", "2", body);
    parent +=
        replace(selectedLane, "%lanes, %lanes_step", foreign ? "%lanes, %one" : "%lanes, %zero");
    parent += "%sample = \"nodal.digital_storage\"() <{name = \"sample\", kind = \"register\", "
              "domain = @a, metadata = {}}> : () -> " +
              bit + "\n" + assign("sample", "selected", "register_next");
    return "module attributes {nodal.root.module = @Top} {\n" + module("Top", parent) +
           module("Lane", requirement("req") + port("in", "input", "req") +
                              port("out", "output", "req") + assign("out", "in")) +
           "}\n";
  };
  check("selected-shape-read-keeps-only-actual-lane-driver", selectedPhysicalRoute(false), true);
  check("selected-shape-read-preserves-foreign-lane-domain", selectedPhysicalRoute(true), false,
        "NODAL-CDC-001");

  auto nonunitPhysicalRoute = [&](bool foreign) {
    auto source = selectedPhysicalRoute(foreign);
    // The ordinal cells deliberately have opposite domains from their actual
    // nonunit coordinates, so substituting q instead of lower + q * step fails.
    source = replace(source, assign("first", "x"), assign("first", "y"));
    source = replace(source, assign("second", "y"), assign("second", "x"));
    const std::string small = "!nodal.shaped<\"2\", " + bit + ">";
    const std::string large = "!nodal.shaped<\"8\", " + bit + ">";
    for (size_t at = 0; (at = source.find(small, at)) != std::string::npos; at += large.size())
      source.replace(at, small.size(), large);
    std::string extra;
    for (unsigned i = 2; i != 8; ++i) {
      auto number = std::to_string(i), ordinal = "coordinate" + number, cell = "cell" + number;
      extra += "%" + ordinal + " = \"nodal.const_literal\"() <{value = " + number +
               " : i64, spelling = \"" + number + "\", metadata = {}}> : () -> i64\n";
      extra += "%" + cell + " = \"nodal.shape_index\"(%cells, %" + ordinal +
               ") <{metadata = {}}> : (" + large + ", i64) -> " + bit + "\n";
      extra += assign(cell, i == 4 ? "y" : "x");
    }
    source = replace(source, "%lanes_lo =", extra + "%lanes_lo =");
    source =
        replace(source, "%lanes_lo = \"nodal.const_literal\"() <{value = 0 : i64, spelling = \"0\"",
                "%lanes_lo = \"nodal.const_literal\"() <{value = 2 : i64, spelling = \"2\"");
    source =
        replace(source, "%lanes_hi = \"nodal.const_literal\"() <{value = 2 : i64, spelling = \"2\"",
                "%lanes_hi = \"nodal.const_literal\"() <{value = 8 : i64, spelling = \"8\"");
    source = replace(source,
                     "%lanes_step = \"nodal.const_literal\"() <{value = 1 : i64, spelling = \"1\"",
                     "%lanes_step = \"nodal.const_literal\"() <{value = 2 : i64, spelling = \"2\"");
    return replace(source, "maximum_trip_count = 2 : i64", "maximum_trip_count = 3 : i64");
  };
  check("selected-nonunit-shape-keeps-coordinate-not-ordinal", nonunitPhysicalRoute(false), true);
  check("selected-nonunit-shape-preserves-foreign-coordinate", nonunitPhysicalRoute(true), false,
        "NODAL-CDC-001");

  auto selectedGuardRoute = [&](bool foreign, bool nonunit = false) {
    auto source = nonunit ? nonunitPhysicalRoute(foreign) : selectedPhysicalRoute(foreign);
    const auto begin = source.find("%lane_data =");
    const auto end = source.find("\"nodal.generated_export\"", begin);
    std::string branch =
        R"mlir(%guard_predicate = "nodal.static_compare"(%lanes_index, %zero) <{comparison = "eq", metadata = {semantic_path = "Top.lanes.guard.predicate"}}> : (i64, i64) -> !nodal.static_predicate
"nodal.structural_if"(%guard_predicate, %lane_in, %x, %lane_in, %y) <{contract_version = "structural-if-v1", region_id = "Top.lanes.guard", operandSegmentSizes = array<i32: 1, 2, 2>, metadata = {semantic_path = "Top.lanes.guard"}}> ({^bb0(%then_target: !nodal.bits<1>, %then_data: !nodal.bits<1>):
"nodal.digital_assign"(%then_target, %then_data) <{kind = "continuous", metadata = {}}> : (!nodal.bits<1>, !nodal.bits<1>) -> ()
"nodal.structural_yield"() <{metadata = {semantic_path = "Top.lanes.guard.then.yield"}}> : () -> ()
}, {^bb0(%else_target: !nodal.bits<1>, %else_data: !nodal.bits<1>):
"nodal.digital_assign"(%else_target, %else_data) <{kind = "continuous", metadata = {}}> : (!nodal.bits<1>, !nodal.bits<1>) -> ()
"nodal.structural_yield"() <{metadata = {semantic_path = "Top.lanes.guard.else.yield"}}> : () -> ()}) : (!nodal.static_predicate, !nodal.bits<1>, !nodal.bits<1>, !nodal.bits<1>, !nodal.bits<1>) -> ()
)mlir";
    if (nonunit)
      branch = replace(branch, "%lanes_index, %zero", "%lanes_index, %coordinate2");
    source.replace(begin, end - begin, branch);
    return source;
  };
  check("selected-guard-lane0-prunes-only-false-arm", selectedGuardRoute(false), true);
  check("selected-guard-lane1-retains-foreign-arm", selectedGuardRoute(true), false,
        "NODAL-CDC-001");
  check("selected-guard-nonunit-uses-actual-coordinate", selectedGuardRoute(false, true), true);
  check("selected-guard-nonunit-foreign-coordinate", selectedGuardRoute(true, true), false,
        "NODAL-CDC-001");
  auto booleanGuardRoute = [&](bool foreign) {
    auto source = selectedGuardRoute(foreign);
    source = replace(source, "comparison = \"eq\"", "comparison = \"ne\"");
    source = replace(source, "\"nodal.structural_if\"(%guard_predicate,",
                     "%not_guard = \"nodal.static_logic\"(%guard_predicate) <{operator_name = "
                     "\"not\", metadata = {semantic_path = \"Top.lanes.guard.not\"}}> : "
                     "(!nodal.static_predicate) -> !nodal.static_predicate\n"
                     "\"nodal.structural_if\"(%not_guard,");
    return source;
  };
  check("selected-guard-boolean-complement", booleanGuardRoute(false), true);
  check("selected-guard-boolean-complement-foreign", booleanGuardRoute(true), false,
        "NODAL-CDC-001");
  auto missingGuardArm = replace(selectedGuardRoute(false), assign("else_target", "else_data"), "");
  missingGuardArm = replace(missingGuardArm, "(%guard_predicate, %lane_in, %x, %lane_in, %y)",
                            "(%guard_predicate, %lane_in, %x)");
  missingGuardArm = replace(missingGuardArm, "array<i32: 1, 2, 2>", "array<i32: 1, 2, 0>");
  missingGuardArm = replace(
      missingGuardArm, "{^bb0(%else_target: !nodal.bits<1>, %else_data: !nodal.bits<1>):", "{");
  missingGuardArm = replace(missingGuardArm,
                            "(!nodal.static_predicate, !nodal.bits<1>, !nodal.bits<1>, "
                            "!nodal.bits<1>, !nodal.bits<1>) -> ()",
                            "(!nodal.static_predicate, !nodal.bits<1>, !nodal.bits<1>) -> ()");
  check("selected-guard-keeps-all-lane-driver-obligation", missingGuardArm, false,
        "undriven combinational wire region");
  check("selected-guard-retains-exact-value-capture",
        replace(selectedGuardRoute(false), assign("then_target", "then_data"),
                assign("then_target", "x")),
        false);
  check("selected-guard-rejects-out-of-domain-prefix",
        replace(selectedGuardRoute(false), "instance_port\"(%lanes, %zero)",
                "instance_port\"(%lanes, %lanes_hi)"),
        false);

  auto symbolicGuardRoute = selectedGuardRoute(false);
  const std::string selectorParameter = R"mlir(
"nodal.parameter"() <{sym_name = "Q", type = i64, default_value = 0 : i64, parameter_kind = "integer", classification = "structural", variability = "symbolic", metadata = {}}> : () -> ()
"nodal.parameter_constraint"(%zero, %one) <{parameter = @Q, constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, metadata = {}}> : (i64, i64) -> ()
"nodal.parameter_envelope"() <{parameter = @Q, effects = ["topology"], policy = "static_generate", metadata = {}}> : () -> ()
%ordinal_parameter = "nodal.const_parameter_ref"() <{parameter = @Q, metadata = {}}> : () -> i64
)mlir";
  symbolicGuardRoute =
      replace(symbolicGuardRoute, "%lanes_lo =", selectorParameter + "%lanes_lo =");
  symbolicGuardRoute = replace(symbolicGuardRoute, "instance_port\"(%lanes, %zero)",
                               "instance_port\"(%lanes, %ordinal_parameter)");
  check("selected-guard-unknown-coordinate-retains-both-arms", symbolicGuardRoute, false,
        "NODAL-CDC-001");

  auto nestedGuardRoute = [&](bool outerForeign) {
    auto source = selectedGuardRoute(false);
    const auto begin = source.find("%lanes_lo =");
    const std::string close = "}) : (i64, i64, i64) -> !nodal.generated_family";
    const auto end = source.find(close, begin) + close.size();
    auto inner = source.substr(begin, end - begin);
    for (size_t at = 0; (at = inner.find("Top.lanes", at)) != std::string::npos; at += 15)
      inner.replace(at, 9, "Top.outer.lanes");
    inner = replace(inner, "\"nodal.structural_if\"(%guard_predicate,",
                    "%outer_guard = \"nodal.static_compare\"(%outer_index, %zero) "
                    "<{comparison = \"eq\", metadata = {semantic_path = "
                    "\"Top.outer.lanes.guard.outer\"}}> : (i64, i64) -> !nodal.static_predicate\n"
                    "%both_guard = \"nodal.static_logic\"(%guard_predicate, %outer_guard) "
                    "<{operator_name = \"and\", metadata = {semantic_path = "
                    "\"Top.outer.lanes.guard.both\"}}> : (!nodal.static_predicate, "
                    "!nodal.static_predicate) -> !nodal.static_predicate\n"
                    "\"nodal.structural_if\"(%both_guard,");
    inner += "\n\"nodal.generated_export\"(%lanes) <{member = \"inner\", access = \"read\", "
             "kind = \"family\", metadata = {}}> : (!nodal.generated_family) -> ()\n";
    auto outer = generatedFamily("outer", "2", inner);
    outer += "%inner_view = \"nodal.generated_member\"(%outer, %" +
             std::string(outerForeign ? "one" : "zero") +
             ") <{member = \"inner\", access = \"read\", kind = \"family\", metadata = {}}> "
             ": (!nodal.generated_family, i64) -> !nodal.generated_family\n";
    source.replace(begin, end - begin, outer);
    return replace(source, "instance_port\"(%lanes, %zero)", "instance_port\"(%inner_view, %zero)");
  };
  check("selected-guard-nested-prefix-conjunction", nestedGuardRoute(false), true);
  check("selected-guard-nested-outer-coordinate-controls-arm", nestedGuardRoute(true), false,
        "NODAL-CDC-001");

  auto selectedGuardJoin = [&](bool foreign) {
    auto source = selectedPhysicalRoute(foreign);
    auto begin = source.find("%lane_data =");
    auto end = source.find("\"nodal.generated_export\"", begin);
    const std::string joined =
        R"mlir(%guard_predicate = "nodal.static_compare"(%lanes_index, %zero) <{comparison = "eq", metadata = {semantic_path = "Top.lanes.guard.predicate"}}> : (i64, i64) -> !nodal.static_predicate
%lane_data = "nodal.structural_if"(%guard_predicate, %x, %y) <{contract_version = "structural-if-v1", region_id = "Top.lanes.guard", operandSegmentSizes = array<i32: 1, 1, 1>, metadata = {semantic_path = "Top.lanes.guard"}}> ({^bb0(%then_data: !nodal.bits<1>):
"nodal.structural_yield"(%then_data) <{metadata = {semantic_path = "Top.lanes.guard.then.yield"}}> : (!nodal.bits<1>) -> ()
}, {^bb0(%else_data: !nodal.bits<1>):
"nodal.structural_yield"(%else_data) <{metadata = {semantic_path = "Top.lanes.guard.else.yield"}}> : (!nodal.bits<1>) -> ()}) : (!nodal.static_predicate, !nodal.bits<1>, !nodal.bits<1>) -> !nodal.bits<1>
)mlir";
    source.replace(begin, end - begin, joined + assign("lane_in", "lane_data"));
    return source;
  };
  check("selected-guard-pure-result-retains-arm", selectedGuardJoin(false), true);
  check("selected-guard-pure-result-retains-foreign-arm", selectedGuardJoin(true), false,
        "NODAL-CDC-001");

  auto capturedGuardRoute = [&](bool foreign) {
    auto source = selectedGuardRoute(foreign);
    auto begin = source.find("%guard_predicate =");
    auto end = source.find("\"nodal.generated_export\"", begin);
    auto branch = source.substr(begin, end - begin);
    for (const auto &[from, to] :
         std::vector<std::pair<std::string, std::string>>{{"%lanes_index", "%captured_index"},
                                                          {"%lane_in", "%captured_target"},
                                                          {"%zero", "%captured_zero"},
                                                          {"%x", "%captured_x"},
                                                          {"%y", "%captured_y"}})
      for (size_t at = 0; (at = branch.find(from, at)) != std::string::npos; at += to.size())
        branch.replace(at, from.size(), to);
    const std::string wrapper =
        R"mlir(%outer_predicate = "nodal.static_compare"(%zero, %zero) <{comparison = "eq", metadata = {semantic_path = "Top.lanes.outer.predicate"}}> : (i64, i64) -> !nodal.static_predicate
"nodal.structural_if"(%outer_predicate, %lanes_index, %zero, %lane_in, %x, %y) <{contract_version = "structural-if-v1", region_id = "Top.lanes.outer", operandSegmentSizes = array<i32: 1, 5, 0>, metadata = {semantic_path = "Top.lanes.outer"}}> ({^bb0(%captured_index: i64, %captured_zero: i64, %captured_target: !nodal.bits<1>, %captured_x: !nodal.bits<1>, %captured_y: !nodal.bits<1>):
)mlir";
    const std::string close =
        R"mlir("nodal.structural_yield"() <{metadata = {semantic_path = "Top.lanes.outer.then.yield"}}> : () -> ()
}, {
"nodal.structural_yield"() <{metadata = {semantic_path = "Top.lanes.outer.else.yield"}}> : () -> ()}) : (!nodal.static_predicate, i64, i64, !nodal.bits<1>, !nodal.bits<1>, !nodal.bits<1>) -> ()
)mlir";
    source.replace(begin, end - begin, wrapper + branch + close);
    return source;
  };
  check("selected-guard-captured-induction-retains-origin", capturedGuardRoute(false), true);
  check("selected-guard-captured-induction-foreign-lane", capturedGuardRoute(true), false,
        "NODAL-CDC-001");
  check("selected-guard-cannot-bypass-induction-capture",
        replace(capturedGuardRoute(false), "static_compare\"(%captured_index, %captured_zero)",
                "static_compare\"(%lanes_index, %captured_zero)"),
        false);

  auto selectedPredicateQuery = [&](StringRef name, unsigned mutation,
                                    std::optional<nodal::StaticPredicateActivity> expected) {
    ++count;
    diagnostics.clear();
    auto source = selectedGuardRoute(false);
    source = replace(source, "%lanes_lo =", generatedFamily("other", "2", "") + "%lanes_lo =");
    if (mutation == 8)
      source = replace(source, "%guard_predicate =",
                       "%unsafe = \"nodal.const_expr\"(%one, %lanes_index) "
                       "<{operator_name = \"div\", metadata = {}}> : (i64, i64) -> i64\n"
                       "%guard_predicate =");
    if (mutation == 8)
      source = replace(source, "static_compare\"(%lanes_index, %zero)",
                       "static_compare\"(%unsafe, %zero)");
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    Operation *condition = nullptr, *family = nullptr, *other = nullptr;
    if (parsed)
      parsed->walk([&](Operation *op) {
        if (isa<nodal::StructuralIfOp>(op))
          condition = op;
        if (isa<nodal::GenerateOp>(op)) {
          auto id = op->getAttrOfType<StringAttr>("region_id");
          if (id && id.getValue() == "Top.lanes")
            family = op;
          if (id && id.getValue() == "Top.other")
            other = op;
        }
      });
    bool ok = parsed && condition && family && other;
    if (ok) {
      Value identity = family->getRegion(0).front().getArgument(0);
      auto replacement =
          nodal::makeLiteralIntegerExpression(mutation == 1 || mutation == 8 ? 1 : 0);
      if (mutation == 2)
        replacement = nodal::makeOpaqueIntegerExpression(identity, {0, 1});
      if (mutation == 3)
        replacement = nodal::makeLiteralIntegerExpression(2);
      if (mutation == 4)
        replacement.bounds.upper = 1;
      if (mutation == 5)
        identity = other->getRegion(0).front().getArgument(0);
      SmallVector<std::pair<Value, nodal::InstantiatedIntegerExpression>, 2> substitutions{
          {identity, replacement}};
      if (mutation == 6)
        substitutions.emplace_back(identity, nodal::makeLiteralIntegerExpression(1));
      unsigned work = mutation == 7 ? 1 : 65536;
      auto facts =
          nodal::analyzeStaticPredicate(condition->getOperand(0), condition, substitutions, work);
      ok = expected ? succeeded(facts) && facts->activity == *expected : failed(facts);
      ok &= work < (mutation == 7 ? 1u : 65536u);
    }
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostics;
    }
  };
  using Activity = nodal::StaticPredicateActivity;
  selectedPredicateQuery("selected-predicate-zero-is-true", 0, Activity::AlwaysTrue);
  selectedPredicateQuery("selected-predicate-one-is-false", 1, Activity::AlwaysFalse);
  selectedPredicateQuery("selected-predicate-unknown-remains-either", 2, Activity::Either);
  selectedPredicateQuery("selected-predicate-outside-domain-refused", 3, std::nullopt);
  selectedPredicateQuery("selected-predicate-forged-bounds-refused", 4, std::nullopt);
  selectedPredicateQuery("selected-predicate-foreign-induction-refused", 5, std::nullopt);
  selectedPredicateQuery("selected-predicate-contradictory-prefix-refused", 6, std::nullopt);
  selectedPredicateQuery("selected-predicate-shared-budget-exhaustion", 7, std::nullopt);
  selectedPredicateQuery("selected-predicate-original-all-lane-divisor-proof", 8, std::nullopt);

  auto childFormalRoute = [&](bool foreign, bool invertBinding = false, bool nonunit = false) {
    auto source = selectedPhysicalRoute(foreign);
    const auto begin = source.find("%lane_data =");
    const auto end = source.find("\"nodal.generated_export\"", begin);
    std::string bindingValue = "%lanes_index";
    std::string overrideDAG;
    if (invertBinding) {
      overrideDAG = "%bound_index = \"nodal.const_expr\"(%one, %lanes_index) <{operator_name = "
                    "\"sub\", metadata = {}}> : (i64, i64) -> i64\n";
      bindingValue = "%bound_index";
    }
    source.replace(
        begin, end - begin,
        proxy("lane_other", "lane", "other", "input") + assign("lane_in", "x") +
            assign("lane_other", "y") + overrideDAG + "\"nodal.parameter_override\"(" +
            bindingValue +
            ") <{instance = @lane, parameter = @N, generated_owner = \"Top.lanes\", "
            "generated_induction = \"Top.lanes.index\", metadata = {}}> : (i64) -> ()\n");
    source = replace(source, "domain_bindings = {req = @a}",
                     "domain_bindings = {req = @a, req_other = @b}");
    source = replace(source, binding("lane", "req", "a"),
                     binding("lane", "req", "a") + binding("lane", "req_other", "b"));
    const std::string parameter = R"mlir(
%child_zero = "nodal.const_literal"() <{value = 0 : i64, spelling = "0", metadata = {}}> : () -> i64
%child_high = "nodal.const_literal"() <{value = 4 : i64, spelling = "4", metadata = {}}> : () -> i64
"nodal.parameter"() <{sym_name = "N", type = i64, default_value = 1 : i64, parameter_kind = "integer", classification = "structural", variability = "symbolic", metadata = {}}> : () -> ()
"nodal.parameter_constraint"(%child_zero, %child_high) <{parameter = @N, constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, metadata = {}}> : (i64, i64) -> ()
"nodal.parameter_envelope"() <{parameter = @N, effects = ["topology"], policy = "static_generate", metadata = {}}> : () -> ()
%child_parameter = "nodal.const_parameter_ref"() <{parameter = @N, metadata = {}}> : () -> i64
%child_predicate = "nodal.static_compare"(%child_parameter, %child_zero) <{comparison = "eq", metadata = {semantic_path = "Lane.guard.predicate"}}> : (i64, i64) -> !nodal.static_predicate
"nodal.structural_if"(%child_predicate, %out, %in, %out, %other) <{contract_version = "structural-if-v1", region_id = "Lane.guard", operandSegmentSizes = array<i32: 1, 2, 2>, metadata = {semantic_path = "Lane.guard"}}> ({^bb0(%then_output: !nodal.bits<1>, %then_source: !nodal.bits<1>):
"nodal.digital_assign"(%then_output, %then_source) <{kind = "continuous", metadata = {}}> : (!nodal.bits<1>, !nodal.bits<1>) -> ()
"nodal.structural_yield"() <{metadata = {semantic_path = "Lane.guard.then.yield"}}> : () -> ()
}, {^bb0(%else_output: !nodal.bits<1>, %else_source: !nodal.bits<1>):
"nodal.digital_assign"(%else_output, %else_source) <{kind = "continuous", metadata = {}}> : (!nodal.bits<1>, !nodal.bits<1>) -> ()
"nodal.structural_yield"() <{metadata = {semantic_path = "Lane.guard.else.yield"}}> : () -> ()}) : (!nodal.static_predicate, !nodal.bits<1>, !nodal.bits<1>, !nodal.bits<1>, !nodal.bits<1>) -> ()
)mlir";
    source = replace(source,
                     module("Lane", requirement("req") + port("in", "input", "req") +
                                        port("out", "output", "req") + assign("out", "in")),
                     module("Lane", requirement("req") + requirement("req_other") +
                                        port("in", "input", "req") +
                                        port("other", "input", "req_other") +
                                        port("out", "output", "req") + parameter));
    if (nonunit) {
      source = replace(source,
                       "%lanes_lo = \"nodal.const_literal\"() <{value = 0 : i64, spelling = \"0\"",
                       "%lanes_lo = \"nodal.const_literal\"() <{value = 2 : i64, spelling = \"2\"");
      source = replace(source,
                       "%lanes_hi = \"nodal.const_literal\"() <{value = 2 : i64, spelling = \"2\"",
                       "%lanes_hi = \"nodal.const_literal\"() <{value = 6 : i64, spelling = \"6\"");
      source = replace(
          source, "%lanes_step = \"nodal.const_literal\"() <{value = 1 : i64, spelling = \"1\"",
          "%lanes_step = \"nodal.const_literal\"() <{value = 2 : i64, spelling = \"2\"");
      source = replace(
          source, "%child_zero = \"nodal.const_literal\"() <{value = 0 : i64, spelling = \"0\"",
          "%child_zero = \"nodal.const_literal\"() <{value = 2 : i64, spelling = \"2\"");
      source = replace(source, "default_value = 1 : i64", "default_value = 2 : i64");
    }
    return source;
  };
  check("selected-child-formal-lane0-uses-actual-override-not-default", childFormalRoute(false),
        true);
  check("selected-child-formal-lane1-preserves-foreign-source", childFormalRoute(true), false,
        "NODAL-CDC-001");
  check("selected-child-formal-binding-expression-reverses-lanes", childFormalRoute(true, true),
        true);
  check("selected-child-formal-binding-expression-retains-foreign", childFormalRoute(false, true),
        false, "NODAL-CDC-001");
  check("selected-child-formal-nonunit-actual-coordinate", childFormalRoute(false, false, true),
        true);
  check("selected-child-formal-nonunit-foreign-coordinate", childFormalRoute(true, false, true),
        false, "NODAL-CDC-001");
  check("selected-child-formal-forged-generated-override-owner",
        replace(childFormalRoute(false), "parameter = @N, generated_owner = \"Top.lanes\"",
                "parameter = @N, generated_owner = \"Top.foreign\""),
        false);
  check("selected-child-formal-forged-generated-override-induction",
        replace(childFormalRoute(false),
                "parameter = @N, generated_owner = \"Top.lanes\", generated_induction = "
                "\"Top.lanes.index\"",
                "parameter = @N, generated_owner = \"Top.lanes\", generated_induction = "
                "\"Top.foreign.index\""),
        false);
  auto childUnknown = childFormalRoute(false);
  childUnknown = replace(childUnknown, "%lanes_lo =", selectorParameter + "%lanes_lo =");
  childUnknown = replace(childUnknown, "instance_port\"(%lanes, %zero)",
                         "instance_port\"(%lanes, %ordinal_parameter)");
  check("selected-child-formal-unknown-coordinate-keeps-both-arms", childUnknown, false,
        "NODAL-CDC-001");

  auto childFormalSiblings = [&](bool wrongSecondDomain) {
    auto source = childFormalRoute(false);
    auto begin = source.find("\"nodal.instance\"");
    auto end = source.find(exportLane, begin) + exportLane.size();
    auto sibling = source.substr(begin, end - begin);
    auto replaceEvery = [&](std::string &text, StringRef from, StringRef to) {
      for (size_t at = 0; (at = text.find(from.str(), at)) != std::string::npos; at += to.size())
        text.replace(at, from.size(), to.str());
    };
    replaceEvery(sibling, "sym_name = \"lane\"", "sym_name = \"sibling\"");
    replaceEvery(sibling, "instance = @lane", "instance = @sibling");
    replaceEvery(sibling, "target = @lane", "target = @sibling");
    replaceEvery(sibling, "%lane_", "%sibling_");
    replaceEvery(sibling, "member = \"child\"", "member = \"sibling\"");
    sibling = replace(
        sibling, "\"nodal.parameter_override\"(%lanes_index)",
        "%sibling_index = \"nodal.const_expr\"(%one, %lanes_index) <{operator_name = \"sub\", "
        "metadata = {}}> : (i64, i64) -> i64\n\"nodal.parameter_override\"(%sibling_index)");
    source.insert(end, sibling);
    auto selected = replace(selectedLane, "%selected =", "%selected_sibling =");
    selected = replace(selected, "%lanes_step", "%zero");
    selected = replace(selected, "member = \"child\"", "member = \"sibling\"");
    selected = replace(selected, "instance = @lane", "instance = @sibling");
    selected += "%sibling_sample = \"nodal.digital_storage\"() <{name = \"sibling_sample\", kind = "
                "\"register\", domain = @" +
                std::string(wrongSecondDomain ? "a" : "b") + ", metadata = {}}> : () -> " + bit +
                "\n" + assign("sibling_sample", "selected_sibling", "register_next");
    return replace(source, assign("sample", "selected", "register_next"),
                   assign("sample", "selected", "register_next") + selected);
  };
  check("selected-child-formal-same-prefix-siblings-keep-actual-bindings",
        childFormalSiblings(false), true);
  check("selected-child-formal-sibling-foreign-binding-is-not-definition-cache",
        childFormalSiblings(true), false, "NODAL-CDC-001");

  const std::string childOverride =
      "\"nodal.parameter_override\"(%lanes_index) <{instance = @lane, parameter = @N, "
      "generated_owner = \"Top.lanes\", generated_induction = \"Top.lanes.index\", metadata = {}}> "
      ": (i64) -> ()\n";
  check("selected-child-formal-absent-override-keeps-actual-default",
        replace(childFormalRoute(false), childOverride, ""), false, "NODAL-CDC-001");
  auto childLiteralBinding = replace(childFormalRoute(true), childOverride, "");
  childLiteralBinding =
      replace(childLiteralBinding, "parameter_bindings = {}", "parameter_bindings = {N = 0 : i64}");
  check("selected-child-formal-literal-binding-is-not-ordinal", childLiteralBinding, true);
  auto childFormalQuery = [&](StringRef name, unsigned mutation, std::optional<Activity> expected) {
    ++count;
    diagnostics.clear();
    auto source = childFormalRoute(false);
    if (mutation == 9) {
      source = replace(
          source, "%child_predicate =",
          "%child_bad = \"nodal.const_expr\"(%child_high, %child_parameter) <{operator_name = "
          "\"div\", metadata = {}}> : (i64, i64) -> i64\n%child_predicate =");
      source = replace(source, "static_compare\"(%child_parameter, %child_zero)",
                       "static_compare\"(%child_bad, %child_zero)");
    }
    if (mutation == 10)
      source = replace(source, "%lanes_lo =", generatedFamily("foreign", "2", "") + "%lanes_lo =");
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    Operation *instance = nullptr, *condition = nullptr, *family = nullptr,
              *foreignFamily = nullptr;
    if (parsed)
      parsed->walk([&](Operation *op) {
        if (isa<nodal::InstanceOp>(op))
          instance = op;
        if (isa<nodal::StructuralIfOp>(op))
          condition = op;
        if (isa<nodal::GenerateOp>(op)) {
          if (op->getAttrOfType<StringAttr>("region_id").getValue() == "Top.foreign")
            foreignFamily = op;
          else
            family = op;
        }
      });
    bool ok = parsed && instance && condition && family;
    if (ok) {
      SmallVector<std::pair<Value, nodal::InstantiatedIntegerExpression>, 2> coordinates{
          {family->getRegion(0).front().getArgument(0),
           nodal::makeLiteralIntegerExpression(mutation == 1 || mutation == 9 ? 1 : 0)}};
      if (mutation == 2)
        coordinates.front().second =
            nodal::makeOpaqueIntegerExpression(coordinates.front().first, {0, 1});
      if (mutation == 3)
        coordinates.front().second = nodal::makeLiteralIntegerExpression(2);
      if (mutation == 4)
        coordinates.push_back(coordinates.front());
      if (mutation == 5)
        coordinates.front().first = family->getOperand(0);
      if (mutation == 6)
        instance->setAttr("module", FlatSymbolRefAttr::get(&context, "Top"));
      if (mutation == 7)
        coordinates.front().second.bounds.upper = 1;
      if (mutation == 10)
        coordinates.front().first = foreignFamily->getRegion(0).front().getArgument(0);
      unsigned budget = mutation == 8 ? 2 : 65536;
      auto result = nodal::analyzeSelectedInstanceStaticPredicate(
          condition->getOperand(0), condition, instance, coordinates, budget);
      ok = expected ? succeeded(result) && result->activity == *expected : failed(result);
    }
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostics;
    }
  };
  childFormalQuery("selected-child-formal-query-true", 0, Activity::AlwaysTrue);
  childFormalQuery("selected-child-formal-query-false", 1, Activity::AlwaysFalse);
  childFormalQuery("selected-child-formal-query-unknown", 2, Activity::Either);
  childFormalQuery("selected-child-formal-query-outside-domain-refused", 3, std::nullopt);
  childFormalQuery("selected-child-formal-query-duplicate-coordinate-refused", 4, std::nullopt);
  childFormalQuery("selected-child-formal-query-foreign-coordinate-refused", 5, std::nullopt);
  childFormalQuery("selected-child-formal-query-foreign-definition-refused", 6, std::nullopt);
  childFormalQuery("selected-child-formal-query-forged-expression-bounds-refused", 7, std::nullopt);
  childFormalQuery("selected-child-formal-query-shared-budget", 8, std::nullopt);
  childFormalQuery("selected-child-formal-query-original-all-lane-invalid", 9, std::nullopt);
  childFormalQuery("selected-child-formal-query-foreign-generate-induction", 10, std::nullopt);

  auto regionalChildFormal = [&](bool foreign, unsigned variant = 0) {
    auto source = childFormalRoute(foreign);
    const auto begin = source.find("%child_predicate =");
    const auto end = source.rfind("}) : () -> ()\n");
    auto branch = source.substr(begin, end - begin);
    bool captureN = variant != 2;
    if (variant == 1) {
      branch = "%regional_sum = \"nodal.const_expr\"(%child_parameter, %child_zero) "
               "<{operator_name = \"add\", metadata = {}}> : (i64, i64) -> i64\n" +
               branch;
      branch = replace(branch, "static_compare\"(%child_parameter, %child_zero)",
                       "static_compare\"(%regional_sum, %child_zero)");
    }
    if (variant == 2) {
      branch = "%regional_parameter = \"nodal.const_parameter_ref\"() <{parameter = @N, metadata = "
               "{}}> : () -> i64\n" +
               branch;
      branch = replace(branch, "static_compare\"(%child_parameter, %child_zero)",
                       "static_compare\"(%regional_parameter, %child_zero)");
    }
    auto wrap = [&](std::string body, unsigned depth) {
      auto remap = [&](StringRef from, StringRef to) {
        for (size_t at = 0; (at = body.find(from.str(), at)) != std::string::npos; at += to.size())
          body.replace(at, from.size(), to.str());
      };
      std::string tag = "capture" + std::to_string(depth);
      remap("%child_parameter", "%" + tag + "N");
      remap("%child_zero", "%" + tag + "Zero");
      remap("%out", "%" + tag + "Output");
      remap("%in", "%" + tag + "Input");
      remap("%other", "%" + tag + "Other");
      const std::string args = !captureN ? "%child_zero, %out, %in, %other"
                               : depth   ? "%child_zero, %child_parameter, %out, %in, %other"
                                         : "%child_parameter, %child_zero, %out, %in, %other";
      const std::string integers = !captureN ? "%" + tag + "Zero: i64, "
                                   : depth   ? "%" + tag + "Zero: i64, %" + tag + "N: i64, "
                                             : "%" + tag + "N: i64, %" + tag + "Zero: i64, ";
      const std::string formals = integers + "%" + tag + "Output: " + bit + ", %" + tag +
                                  "Input: " + bit + ", %" + tag + "Other: " + bit;
      return "%wrap_pred" + std::to_string(depth) +
             " = \"nodal.static_compare\"(%child_zero, %child_zero) <{comparison = \"eq\", "
             "metadata = {semantic_path = \"Lane." +
             tag + ".predicate\"}}> : (i64, i64) -> !nodal.static_predicate\n" +
             "\"nodal.structural_if\"(%wrap_pred" + std::to_string(depth) + ", " + args +
             ") <{contract_version = \"structural-if-v1\", region_id = \"Lane." + tag +
             "\", operandSegmentSizes = array<i32: 1, " + (captureN ? "5" : "4") +
             ", 0>, metadata = {semantic_path = \"Lane." + tag + "\"}}> ({^bb0(" + formals +
             "):\n" + body + "\"nodal.structural_yield\"() <{metadata = {semantic_path = \"Lane." +
             tag +
             ".then.yield\"}}> : () -> ()\n}, {\n\"nodal.structural_yield\"() <{metadata = "
             "{semantic_path = \"Lane." +
             tag + ".else.yield\"}}> : () -> ()\n}) : (!nodal.static_predicate, " +
             (captureN ? "i64, " : "") + "i64, " + bit + ", " + bit + ", " + bit + ") -> ()\n";
    };
    branch = wrap(branch, 0);
    if (variant == 3)
      branch = wrap(branch, 1);
    source.replace(begin, end - begin, branch);
    return source;
  };
  check("selected-child-regional-captured-formal", regionalChildFormal(false), true);
  check("selected-child-regional-captured-formal-foreign", regionalChildFormal(true), false,
        "NODAL-CDC-001");
  check("selected-child-regional-local-arithmetic", regionalChildFormal(false, 1), true);
  check("selected-child-regional-local-arithmetic-foreign", regionalChildFormal(true, 1), false,
        "NODAL-CDC-001");
  check("selected-child-regional-local-parameter-reference", regionalChildFormal(false, 2), true);
  check("selected-child-regional-local-parameter-reference-foreign", regionalChildFormal(true, 2),
        false, "NODAL-CDC-001");
  check("selected-child-regional-nested-capture-chain", regionalChildFormal(false, 3), true);
  check("selected-child-regional-nested-capture-chain-foreign", regionalChildFormal(true, 3), false,
        "NODAL-CDC-001");
  check("selected-child-regional-raw-formal-cannot-bypass-capture",
        replace(regionalChildFormal(false), "static_compare\"(%capture0N, %capture0Zero)",
                "static_compare\"(%child_parameter, %capture0Zero)"),
        false);
  check("selected-child-regional-raw-local-operand-cannot-bypass-capture",
        replace(regionalChildFormal(false, 1), "const_expr\"(%capture0N, %capture0Zero)",
                "const_expr\"(%child_parameter, %capture0Zero)"),
        false);
  check("selected-child-regional-original-all-lane-divisor",
        replace(regionalChildFormal(true, 1), "operator_name = \"add\"", "operator_name = \"div\""),
        false);
  auto misorderedNestedCapture =
      replace(regionalChildFormal(false, 3), "(%wrap_pred1, %child_zero, %child_parameter, %out",
              "(%wrap_pred1, %child_parameter, %child_zero, %out");
  misorderedNestedCapture = replace(misorderedNestedCapture, "%capture1Zero: i64, %capture1N: i64",
                                    "%capture1N: i64, %capture1Zero: i64");
  check("selected-child-regional-forged-capture-order", misorderedNestedCapture, false,
        "deterministic first actual use order");
  auto regionalIntegerQuery = [&](StringRef name, unsigned mutation, bool expected,
                                  bool absent = false) {
    ++count;
    diagnostics.clear();
    auto source = regionalChildFormal(false, 1);
    if (absent) {
      std::string empty = generatedFamily(
          "empty", "0",
          "%empty_value = \"nodal.const_expr\"(%empty_index, %child_parameter) <{operator_name = "
          "\"add\", metadata = {regional_probe = \"empty\"}}> : (i64, i64) -> i64\n");
      for (size_t at = 0; (at = empty.find("Top.empty", at)) != std::string::npos; at += 10)
        empty.replace(at, 9, "Lane.empty");
      source = replace(source, "%wrap_pred0 =", empty + "%wrap_pred0 =");
    }
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    Operation *instance = nullptr, *family = nullptr, *use = nullptr;
    Value expression, raw;
    if (parsed)
      parsed->walk([&](Operation *op) {
        if (isa<nodal::InstanceOp>(op))
          instance = op;
        if (isa<nodal::GenerateOp>(op) &&
            op->getAttrOfType<StringAttr>("region_id").getValue() == "Top.lanes")
          family = op;
        if (isa<nodal::ConstExprOp>(op) && op->getParentOp() &&
            isa<nodal::StructuralIfOp>(op->getParentOp()) && !absent) {
          expression = op->getResult(0);
        }
        if (isa<nodal::StaticCompareOp>(op) &&
            op->getAttrOfType<DictionaryAttr>("metadata")
                    .getAs<StringAttr>("semantic_path")
                    .getValue() == "Lane.guard.predicate" &&
            !absent)
          use = op;
        if (absent && isa<nodal::ConstExprOp>(op) &&
            op->getAttrOfType<DictionaryAttr>("metadata").getAs<StringAttr>("regional_probe")) {
          expression = op->getOperand(0);
          use = op;
        }
        if (isa<nodal::ConstParameterRefOp>(op) && isa<nodal::ModuleOp>(op->getParentOp()))
          raw = op->getResult(0);
      });
    bool ok = parsed && instance && family && use && expression;
    if (ok) {
      if (mutation == 1)
        use = instance;
      if (mutation == 2)
        expression = raw;
      if (mutation == 3)
        instance->setAttr("module", FlatSymbolRefAttr::get(&context, "Top"));
      SmallVector<std::pair<Value, nodal::InstantiatedIntegerExpression>, 1> coordinates{
          {family->getRegion(0).front().getArgument(0), nodal::makeLiteralIntegerExpression(0)}};
      if (mutation == 5)
        use = expression.getDefiningOp();
      unsigned remaining = mutation == 4 ? 0 : 65536;
      auto result = nodal::resolveSelectedInstanceStructuralIntegerExpression(
          instance, expression, use, coordinates, remaining);
      ok = succeeded(result) == expected;
      if (expected && succeeded(result))
        ok &= absent ? !result->expression
                     : result->expression && result->expression->bounds.lower == 0 &&
                           result->expression->bounds.upper == 0;
    }
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostics;
    }
  };
  regionalIntegerQuery("selected-child-regional-query-exact-use", 0, true);
  regionalIntegerQuery("selected-child-regional-query-foreign-use", 1, false);
  regionalIntegerQuery("selected-child-regional-query-raw-capture", 2, false);
  regionalIntegerQuery("selected-child-regional-query-foreign-definition", 3, false);
  regionalIntegerQuery("selected-child-regional-query-shared-budget", 4, false);
  regionalIntegerQuery("selected-child-regional-query-definition-is-not-a-dominating-use", 5,
                       false);
  regionalIntegerQuery("selected-child-regional-query-empty-remains-absent", 0, true, true);

  auto jointChildFormal = [&](unsigned parentLane, unsigned childLane, bool nonunit = false,
                              unsigned outerLane = 2) {
    auto source = childFormalRoute(parentLane != 0);
    const auto begin = source.find("%child_predicate =");
    const auto end = source.rfind("}) : () -> ()\n");
    auto branch = source.substr(begin, end - begin);
    branch = replace(branch, "static_compare\"(%child_parameter, %child_zero)",
                     "static_compare\"(%child_parameter, %child_cells_index)");
    // Only the output actual changes: source captures and original arm identities
    // remain those of the authored child definition.
    branch = replace(branch, "(%child_predicate, %out, %in, %out, %other)",
                     "(%child_predicate, %joint_wire, %in, %joint_wire, %other)");
    if (outerLane < 2) {
      branch = replace(
          branch, "\"nodal.structural_if\"(%child_predicate,",
          "%child_outer_predicate = \"nodal.static_compare\"(%child_outer_index, %child_zero) "
          "<{comparison = \"eq\", metadata = {semantic_path = \"Lane.guard.outer\"}}> : (i64, i64) "
          "-> !nodal.static_predicate\n"
          "%joint_predicate = \"nodal.static_logic\"(%child_predicate, %child_outer_predicate) "
          "<{operator_name = \"and\", metadata = {semantic_path = \"Lane.guard.both\"}}> : "
          "(!nodal.static_predicate, !nodal.static_predicate) -> !nodal.static_predicate\n"
          "\"nodal.structural_if\"(%joint_predicate,");
    }
    auto inner = generatedFamily("child_cells", "2",
                                 "%joint_wire = \"nodal.digital_storage\"() <{name = "
                                 "\"joint_wire\", kind = \"wire\", metadata = {}}> : () -> " +
                                     bit + "\n" + branch +
                                     "\"nodal.generated_export\"(%joint_wire) <{member = "
                                     "\"value\", access = \"read\", metadata = {}}> : (" +
                                     bit + ") -> ()\n");
    if (nonunit) {
      inner =
          replace(inner, "value = 0 : i64, spelling = \"0\"", "value = 2 : i64, spelling = \"2\"");
      inner = replace(
          inner, "%child_cells_hi = \"nodal.const_literal\"() <{value = 2 : i64, spelling = \"2\"",
          "%child_cells_hi = \"nodal.const_literal\"() <{value = 6 : i64, spelling = \"6\"");
      inner =
          replace(inner, "value = 1 : i64, spelling = \"1\"", "value = 2 : i64, spelling = \"2\"");
      source = replace(source, "\"nodal.parameter_override\"(%lanes_index)",
                       "%binding_two = \"nodal.const_literal\"() <{value = 2 : i64, spelling = "
                       "\"2\", metadata = {}}> : () -> i64\n"
                       "%binding_twice = \"nodal.const_expr\"(%lanes_index, %binding_two) "
                       "<{operator_name = \"mul\", metadata = {}}> : (i64, i64) -> i64\n"
                       "%binding_nonunit = \"nodal.const_expr\"(%binding_twice, %binding_two) "
                       "<{operator_name = \"add\", metadata = {}}> : (i64, i64) -> i64\n"
                       "\"nodal.parameter_override\"(%binding_nonunit)");
    }
    auto modulePaths = [](std::string body) {
      for (size_t at = 0; (at = body.find("Top.child", at)) != std::string::npos; at += 10)
        body.replace(at, 9, "Lane.child");
      return body;
    };
    std::string selectedFamily = "%child_cells";
    if (outerLane < 2) {
      for (size_t at = 0; (at = inner.find("Top.child_cells", at)) != std::string::npos; at += 27)
        inner.replace(at, 15, "Top.child_outer.child_cells");
      inner += "\"nodal.generated_export\"(%child_cells) <{member = \"inner\", access = \"read\", "
               "kind = \"family\", metadata = {}}> : (!nodal.generated_family) -> ()\n";
      inner = generatedFamily("child_outer", "2", inner);
      inner += "%child_outer_ordinal = \"nodal.const_literal\"() <{value = " +
               std::to_string(outerLane) + " : i64, spelling = \"" + std::to_string(outerLane) +
               "\", metadata = {}}> : () -> i64\n"
               "%child_inner_view = \"nodal.generated_member\"(%child_outer, %child_outer_ordinal) "
               "<{member = \"inner\", access = \"read\", kind = \"family\", metadata = {}}> : "
               "(!nodal.generated_family, i64) -> !nodal.generated_family\n";
      selectedFamily = "%child_inner_view";
    }
    inner = modulePaths(inner);
    inner += "%child_ordinal = \"nodal.const_literal\"() <{value = " + std::to_string(childLane) +
             " : i64, spelling = \"" + std::to_string(childLane) +
             "\", metadata = {}}> : () -> i64\n"
             "%child_selected = \"nodal.generated_member\"(" +
             selectedFamily +
             ", %child_ordinal) <{member = \"value\", access = \"read\", metadata = {}}> : "
             "(!nodal.generated_family, i64) -> " +
             bit + "\n" + assign("out", "child_selected");
    // Recompute offsets after the optional actual-parent binding expansion.
    const auto currentBegin = source.find("%child_predicate =");
    const auto currentEnd = source.rfind("}) : () -> ()\n");
    source.replace(currentBegin, currentEnd - currentBegin, inner);
    return source;
  };
  check("selected-joint-parent0-child0", jointChildFormal(0, 0), true);
  check("selected-joint-parent0-child1-foreign", jointChildFormal(0, 1), false, "NODAL-CDC-001");
  check("selected-joint-parent1-child0-foreign", jointChildFormal(1, 0), false, "NODAL-CDC-001");
  check("selected-joint-parent1-child1", jointChildFormal(1, 1), true);
  check("selected-joint-nonunit-parent0-child0", jointChildFormal(0, 0, true), true);
  check("selected-joint-nonunit-parent1-child1", jointChildFormal(1, 1, true), true);
  check("selected-joint-nonunit-foreign-coordinate", jointChildFormal(1, 0, true), false,
        "NODAL-CDC-001");
  check("selected-joint-nested-prefix", jointChildFormal(1, 1, false, 0), true);
  check("selected-joint-nested-outer-controls-arm", jointChildFormal(1, 1, false, 1), false,
        "NODAL-CDC-001");
  check("selected-joint-nested-identity-bypass-rejected",
        replace(jointChildFormal(1, 1, false, 0), "region_id = \"Lane.child_outer.child_cells\"",
                "region_id = \"Lane.child_cells\""),
        false);
  auto jointBadArithmetic =
      replace(jointChildFormal(0, 0), "%child_predicate =",
              "%joint_bad = \"nodal.const_expr\"(%child_high, %child_cells_index) <{operator_name "
              "= \"div\", metadata = {}}> : (i64, i64) -> i64\n%child_predicate =");
  jointBadArithmetic =
      replace(jointBadArithmetic, "static_compare\"(%child_parameter, %child_cells_index)",
              "static_compare\"(%child_parameter, %joint_bad)");
  check("selected-joint-original-all-lane-invalid", jointBadArithmetic, false);
  check("selected-joint-child-selection-outside-domain", jointChildFormal(0, 2), false);

  auto jointPredicateQuery = [&](StringRef name, unsigned mutation,
                                 std::optional<Activity> expected) {
    ++count;
    diagnostics.clear();
    auto parsed =
        parseSourceString<mlir::ModuleOp>(jointChildFormal(0, 0), ParserConfig(&context, false));
    Operation *instance = nullptr, *condition = nullptr, *parentFamily = nullptr,
              *childFamily = nullptr;
    if (parsed)
      parsed->walk([&](Operation *op) {
        if (isa<nodal::InstanceOp>(op))
          instance = op;
        if (isa<nodal::StructuralIfOp>(op))
          condition = op;
        if (isa<nodal::GenerateOp>(op)) {
          auto id = op->getAttrOfType<StringAttr>("region_id").getValue();
          if (id == "Top.lanes")
            parentFamily = op;
          if (id == "Lane.child_cells")
            childFamily = op;
        }
      });
    bool ok = parsed && instance && condition && parentFamily && childFamily;
    if (ok) {
      SmallVector<std::pair<Value, nodal::InstantiatedIntegerExpression>, 2> parentCoordinates{
          {parentFamily->getRegion(0).front().getArgument(0),
           nodal::makeLiteralIntegerExpression(0)}};
      SmallVector<std::pair<Value, nodal::InstantiatedIntegerExpression>, 2> childCoordinates{
          {childFamily->getRegion(0).front().getArgument(0),
           nodal::makeLiteralIntegerExpression(0)}};
      if (mutation == 1)
        childCoordinates.front().second = nodal::makeLiteralIntegerExpression(1);
      if (mutation == 2)
        childCoordinates.front().second =
            nodal::makeOpaqueIntegerExpression(childCoordinates.front().first, {0, 1});
      if (mutation == 3)
        childCoordinates.front().second = nodal::makeLiteralIntegerExpression(2);
      if (mutation == 4)
        childCoordinates.push_back(childCoordinates.front());
      if (mutation == 5)
        childCoordinates.front().first = parentCoordinates.front().first;
      if (mutation == 6)
        parentCoordinates.front().first = childCoordinates.front().first;
      if (mutation == 7)
        childCoordinates.front().second.bounds.upper = 1;
      if (mutation == 9)
        instance->setAttr("module", FlatSymbolRefAttr::get(&context, "Top"));
      unsigned remaining = mutation == 8 ? 2 : 65536;
      auto result = nodal::analyzeSelectedInstanceStaticPredicate(
          condition->getOperand(0), condition, instance, parentCoordinates, childCoordinates,
          remaining);
      ok = expected ? succeeded(result) && result->activity == *expected : failed(result);
    }
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostics;
    }
  };
  jointPredicateQuery("selected-joint-query-true", 0, Activity::AlwaysTrue);
  jointPredicateQuery("selected-joint-query-false", 1, Activity::AlwaysFalse);
  jointPredicateQuery("selected-joint-query-unknown-keeps-either", 2, Activity::Either);
  jointPredicateQuery("selected-joint-query-outside-domain", 3, std::nullopt);
  jointPredicateQuery("selected-joint-query-duplicate-child-prefix", 4, std::nullopt);
  jointPredicateQuery("selected-joint-query-parent-is-not-child-identity", 5, std::nullopt);
  jointPredicateQuery("selected-joint-query-child-is-not-parent-identity", 6, std::nullopt);
  jointPredicateQuery("selected-joint-query-forged-bounds", 7, std::nullopt);
  jointPredicateQuery("selected-joint-query-shared-budget", 8, std::nullopt);
  jointPredicateQuery("selected-joint-query-foreign-definition", 9, std::nullopt);

  auto descendantFormal = [&](unsigned lane, unsigned variant = 0) {
    auto source = childFormalRoute(lane != 0);
    source =
        replace(source, "value = 4 : i64, spelling = \"4\"", "value = 1 : i64, spelling = \"1\"");
    const auto moduleStart = source.find("\"nodal.module\"() <{sym_name = \"Lane\"");
    const auto bodyStart = source.find("({\n", moduleStart) + 3;
    const auto branchStart = source.find("%child_predicate =", bodyStart);
    const auto bodyEnd = source.rfind("}) : () -> ()\n");
    const auto moduleEnd = bodyEnd + std::string("}) : () -> ()\n").size();
    auto prefix = source.substr(bodyStart, branchStart - bodyStart);
    auto leaf = source.substr(bodyStart, bodyEnd - bodyStart);
    for (size_t at = 0; (at = leaf.find("Lane.guard", at)) != std::string::npos; at += 10)
      leaf.replace(at, 10, "Leaf.guard");
    auto forward = [&](StringRef target, bool generated = false, bool peer = false) {
      std::string name = peer ? "peer" : "next";
      std::string attrs =
          generated ? "generated_owner = \"Lane.desc\", generated_induction = \"Lane.desc.index\", "
                    : "";
      std::string bound = variant == 2 ? "N = 0 : i64" : "";
      std::string body = "\"nodal.instance\"() <{sym_name = \"" + name + "\", module = @" +
                         target.str() + ", parameter_bindings = {" + bound +
                         "}, domain_bindings = {req = @" + (peer ? "req_other" : "req") +
                         ", req_other = @" + (peer ? "req" : "req_other") + "}, " + attrs +
                         "metadata = {}}> : () -> ()\n";
      for (StringRef requirement : {StringRef("req"), StringRef("req_other")}) {
        std::string actual =
            peer ? (requirement == "req" ? "req_other" : "req") : requirement.str();
        body += "\"nodal.instance_domain_binding\"(%" + actual + "_clock) <{instance = @" + name +
                ", requirement = @" + requirement.str() + ", actual = @" + actual +
                ", origin = \"inherited\", metadata = {}}> : (" + clockType + ") -> ()\n";
      }
      body += proxy(name + "_in", name, "in", "input") +
              proxy(name + "_other", name, "other", "input") +
              proxy(name + "_out", name, "out", "output") +
              assign(name + "_in", peer ? "other" : "in") +
              assign(name + "_other", peer ? "in" : "other");
      std::string binding = "%child_parameter";
      if (variant == 1 || peer) {
        body += "%" + name +
                "_inverse = \"nodal.const_expr\"(%child_high, %child_parameter) <{operator_name = "
                "\"sub\", metadata = {}}> : (i64, i64) -> i64\n";
        binding = "%" + name + "_inverse";
      }
      if (generated) {
        body += "%next_sum = \"nodal.const_expr\"(%child_parameter, %desc_index) <{operator_name = "
                "\"add\", metadata = {}}> : (i64, i64) -> i64\n"
                "%next_two = \"nodal.const_literal\"() <{value = 2 : i64, spelling = \"2\", "
                "metadata = {}}> : () -> i64\n"
                "%next_mod = \"nodal.const_expr\"(%next_sum, %next_two) <{operator_name = \"mod\", "
                "metadata = {}}> : (i64, i64) -> i64\n";
        binding = "%next_mod";
      }
      if (variant != 2 && variant != 3)
        body += "\"nodal.parameter_override\"(" + binding + ") <{instance = @" + name +
                ", parameter = @N, " + attrs + "metadata = {}}> : (i64) -> ()\n";
      return body;
    };
    std::string middle, body;
    if (variant == 4) {
      middle = module("Middle", prefix + forward("Leaf") + assign("out", "next_out"));
      body = forward("Middle") + assign("out", "next_out");
    } else if (variant == 5 || variant == 6) {
      body = generatedFamily(
          "desc", "2",
          forward("Leaf", true) +
              "\"nodal.generated_export\"() <{member = \"child\", kind = \"instance\", target = "
              "@next, access = \"read\", metadata = {}}> : () -> ()\n");
      for (size_t at = 0; (at = body.find("Top.desc", at)) != std::string::npos; at += 9)
        body.replace(at, 8, "Lane.desc");
      body += "%desc_ordinal = \"nodal.const_literal\"() <{value = " +
              std::string(variant == 6 ? "1" : "0") + " : i64, spelling = \"" +
              (variant == 6 ? "1" : "0") +
              "\", metadata = {}}> : () -> i64\n"
              "%desc_selected = \"nodal.instance_port\"(%desc, %desc_ordinal) <{member = "
              "\"child\", instance = @next, port = \"out\", direction = \"output\", metadata = "
              "{}}> : (!nodal.generated_family, i64) -> " +
              bit + "\n" + assign("out", "desc_selected");
    } else if (variant == 7) {
      body = forward("Leaf") + forward("Leaf", false, true) +
             "%joined_descendants = \"nodal.digital_expr\"(%next_out, %peer_out) <{operator_name = "
             "\"digital_or\", metadata = {}}> : (" +
             bit + ", " + bit + ") -> " + bit + "\n" + assign("out", "joined_descendants");
    } else {
      body = forward("Leaf") + assign("out", "next_out");
    }
    source.replace(moduleStart, moduleEnd - moduleStart,
                   module("Lane", prefix + body) + middle + module("Leaf", leaf));
    return source;
  };
  check("selected-descendant-two-edge-forward", descendantFormal(0), true);
  check("selected-descendant-two-edge-foreign", descendantFormal(1), false, "NODAL-CDC-001");
  check("selected-descendant-derived-binding", descendantFormal(1, 1), true);
  check("selected-descendant-derived-foreign", descendantFormal(0, 1), false, "NODAL-CDC-001");
  check("selected-descendant-literal-binding-not-ancestor-ordinal", descendantFormal(1, 2), true);
  check("selected-descendant-absent-binding-keeps-real-default", descendantFormal(0, 3), false,
        "NODAL-CDC-001");
  check("selected-descendant-three-edge-forward", descendantFormal(0, 4), true);
  check("selected-descendant-three-edge-foreign", descendantFormal(1, 4), false, "NODAL-CDC-001");
  check("selected-descendant-generated-local-override", descendantFormal(0, 5), true);
  check("selected-descendant-generated-local-foreign", descendantFormal(1, 5), false,
        "NODAL-CDC-001");
  check("selected-descendant-generated-selected-coordinate", descendantFormal(1, 6), true);
  check("selected-descendant-generated-selected-foreign", descendantFormal(0, 6), false,
        "NODAL-CDC-001");
  check("selected-descendant-siblings-retain-opposite-bindings", descendantFormal(0, 7), true);
  check("selected-descendant-siblings-retain-foreign-bindings", descendantFormal(1, 7), false,
        "NODAL-CDC-001");
  auto descendantInvalid = descendantFormal(0);
  auto leafBegin = descendantInvalid.find("\"nodal.module\"() <{sym_name = \"Leaf\"");
  auto invalidLeaf = descendantInvalid.substr(leafBegin);
  invalidLeaf = replace(invalidLeaf, "value = 1 : i64, spelling = \"1\"",
                        "value = 0 : i64, spelling = \"0\"");
  invalidLeaf = replace(invalidLeaf, "default_value = 1 : i64", "default_value = 0 : i64");
  descendantInvalid.replace(leafBegin, descendantInvalid.size() - leafBegin, invalidLeaf);
  check("selected-descendant-keeps-original-all-lane-contract", descendantInvalid, false);
  auto descendantDivision = replace(
      descendantFormal(1), "\"nodal.parameter_override\"(%child_parameter)",
      "%bad_binding = \"nodal.const_expr\"(%child_high, %child_parameter) <{operator_name = "
      "\"div\", metadata = {}}> : (i64, i64) -> i64\n\"nodal.parameter_override\"(%bad_binding)");
  check("selected-descendant-keeps-original-all-lane-arithmetic", descendantDivision, false);

  auto descendantPredicateQuery = [&](StringRef name, unsigned mutation,
                                      std::optional<Activity> expected) {
    ++count;
    diagnostics.clear();
    auto source = descendantFormal(0, mutation >= 14 ? 5 : 0);
    if (mutation >= 14) {
      const auto begin = source.find("%child_predicate =");
      const auto end = source.rfind("}) : () -> ()\n");
      auto branch = source.substr(begin, end - begin);
      branch = replace(branch, "static_compare\"(%child_parameter, %child_zero)",
                       "static_compare\"(%unreachable_index, %child_zero)");
      auto empty = generatedFamily("unreachable", "0", branch);
      for (size_t at = 0; (at = empty.find("Top.unreachable", at)) != std::string::npos; at += 16)
        empty.replace(at, 15, "Leaf.unreachable");
      source.replace(begin, end - begin, empty);
      if (mutation == 15) {
        source = replace(
            source, "%desc_hi = \"nodal.const_literal\"() <{value = 2 : i64, spelling = \"2\"",
            "%desc_hi = \"nodal.const_literal\"() <{value = 0 : i64, spelling = \"0\"");
        auto owner = source.find("region_id = \"Lane.desc\"");
        auto countAt = source.find("maximum_trip_count = 2 : i64", owner);
        source.replace(countAt, std::string("maximum_trip_count = 2 : i64").size(),
                       "maximum_trip_count = 0 : i64");
      }
    }
    if (mutation == 12)
      source = replace(source,
                       "%lanes_hi = \"nodal.const_literal\"() <{value = 2 : i64, spelling = \"2\"",
                       "%lanes_hi = \"nodal.const_literal\"() <{value = 0 : i64, spelling = \"0\"");
    if (mutation == 12)
      source = replace(source, "maximum_trip_count = 2 : i64", "maximum_trip_count = 0 : i64");
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    Operation *root = nullptr, *laneModule = nullptr, *leafModule = nullptr, *first = nullptr,
              *last = nullptr, *condition = nullptr, *family = nullptr;
    if (parsed)
      parsed->walk([&](Operation *op) {
        if (isa<nodal::ModuleOp>(op)) {
          auto name = op->getAttrOfType<StringAttr>("sym_name").getValue();
          if (name == "Top")
            root = op;
          if (name == "Lane")
            laneModule = op;
          if (name == "Leaf")
            leafModule = op;
        }
        if (isa<nodal::InstanceOp>(op)) {
          auto name = op->getAttrOfType<StringAttr>("sym_name").getValue();
          if (name == "lane")
            first = op;
          if (name == "next")
            last = op;
        }
        if (isa<nodal::StructuralIfOp>(op))
          condition = op;
        if (isa<nodal::GenerateOp>(op) &&
            op->getAttrOfType<StringAttr>("region_id").getValue() == "Top.lanes")
          family = op;
      });
    bool ok = parsed && root && laneModule && leafModule && first && last && condition && family;
    if (ok) {
      SmallVector<std::pair<Value, nodal::InstantiatedIntegerExpression>, 2> coordinates{
          {family->getRegion(0).front().getArgument(0),
           nodal::makeLiteralIntegerExpression(mutation == 1 ? 1 : 0)}};
      if (mutation == 2)
        coordinates.front().second =
            nodal::makeOpaqueIntegerExpression(coordinates.front().first, {0, 1});
      if (mutation == 9)
        coordinates.front().second = nodal::makeLiteralIntegerExpression(2);
      SmallVector<nodal::SelectedIntegerInstanceStep, 4> path{{first, coordinates}, {last, {}}};
      if (mutation == 3)
        std::reverse(path.begin(), path.end());
      if (mutation == 4)
        path.erase(path.begin());
      if (mutation == 5)
        path.pop_back();
      if (mutation == 6) {
        path.erase(path.begin());
        root = laneModule;
      }
      if (mutation == 7)
        path.push_back(path.back());
      if (mutation == 8)
        last->setAttr("module", FlatSymbolRefAttr::get(&context, "Top"));
      if (mutation == 10)
        path.back().coordinates = coordinates;
      unsigned remaining = mutation == 11 ? 1 : 65536;
      auto result = nodal::analyzeSelectedInstancePathStaticPredicate(
          condition->getOperand(0), condition, root, path, {}, remaining);
      ok = expected ? succeeded(result) && result->activity == *expected : failed(result);
    }
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostics;
    }
  };
  descendantPredicateQuery("selected-descendant-query-true", 0, Activity::AlwaysTrue);
  descendantPredicateQuery("selected-descendant-query-false", 1, Activity::AlwaysFalse);
  descendantPredicateQuery("selected-descendant-query-unknown", 2, Activity::Either);
  descendantPredicateQuery("selected-descendant-query-reversed-path", 3, std::nullopt);
  descendantPredicateQuery("selected-descendant-query-valid-truncated-suffix", 4, std::nullopt);
  descendantPredicateQuery("selected-descendant-query-omitted-final-edge", 5, std::nullopt);
  descendantPredicateQuery("selected-descendant-query-suffix-cannot-rebase-root", 6, std::nullopt);
  descendantPredicateQuery("selected-descendant-query-repeated-edge", 7, std::nullopt);
  descendantPredicateQuery("selected-descendant-query-cycle", 8, std::nullopt);
  descendantPredicateQuery("selected-descendant-query-outside-coordinate", 9, std::nullopt);
  descendantPredicateQuery("selected-descendant-query-foreign-edge-coordinate", 10, std::nullopt);
  descendantPredicateQuery("selected-descendant-query-shared-budget", 11, std::nullopt);
  descendantPredicateQuery("selected-descendant-query-empty-ancestor", 12, std::nullopt);

  descendantPredicateQuery("selected-descendant-query-local-empty-remains-absent", 14,
                           Activity::Unreachable);
  descendantPredicateQuery("selected-descendant-query-empty-ancestor-cannot-hide-in-absence", 15,
                           std::nullopt);

  auto templateWireSource = [&](StringRef lanes) {
    auto source = generatedFixture(lanes);
    source = replace(source, "kind = \"register\", domain = @a,", "kind = \"wire\",");
    return replace(source, assign("sink", "lane_out", "register_next"), assign("sink", "lane_out"));
  };
  auto authoredAssignments = [&](StringRef name, std::string source, bool expected,
                                 unsigned mutation = 0, StringRef message = "") {
    ++count;
    diagnostics.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    Operation *top = nullptr;
    SmallVector<std::pair<Value, Operation *>, 4> seeds;
    Value foreign, selectedRoot;
    if (parsed)
      parsed->walk([&](Operation *op) {
        if (isa<nodal::ModuleOp>(op) &&
            op->getAttrOfType<StringAttr>("sym_name").getValue() == "Top")
          top = op;
        if (isa<nodal::PortValueOp>(op) &&
            op->getParentOp()->getAttrOfType<StringAttr>("sym_name").getValue() == "Lane")
          foreign = op->getResult(0);
        if (isa<nodal::InstancePortOp>(op) && op->getNumOperands() == 2)
          selectedRoot = op->getResult(0);
        auto family = op->getParentOp();
        if (!isa_and_nonnull<nodal::GenerateOp>(family))
          return;
        bool input = isa<nodal::InstancePortOp>(op) &&
                     op->getAttrOfType<StringAttr>("direction").getValue() == "input";
        bool wire = isa<nodal::DigitalStorageOp>(op) &&
                    op->getAttrOfType<StringAttr>("kind").getValue() == "wire";
        if ((input || wire) && op->getNumResults() == 1)
          seeds.emplace_back(op->getResult(0), &family->getRegion(0).front().back());
        if (mutation == 5 && isa<nodal::ShapeIndexOp>(op) &&
            op->getAttrOfType<DictionaryAttr>("metadata").getAs<StringAttr>("test_partial"))
          seeds.emplace_back(op->getResult(0), &family->getRegion(0).front().back());
      });
    bool ok = parsed && top && !seeds.empty();
    if (ok) {
      if (mutation == 1)
        seeds.push_back(seeds.front());
      if (mutation == 2)
        seeds.front().second = &top->getRegion(0).front().back();
      if (mutation == 3)
        seeds.front().first = foreign;
      if (mutation == 6)
        seeds.front() = {selectedRoot, &top->getRegion(0).front().back()};
      nodal::DigitalAssignmentProofLimits limits;
      if (mutation == 4)
        limits.maximumRelations = 0;
      auto facts = nodal::inspectGeneratedTemplateAssignments(top, seeds, limits);
      ok = succeeded(facts) == expected;
      if (expected && succeeded(facts)) {
        ok &= facts->size() == seeds.size();
        for (auto [i, fact] : llvm::enumerate(*facts))
          ok &= fact.root == seeds[i].first && fact.assignment && fact.family &&
                fact.assignment->getOperand(1) == fact.source &&
                fact.family == seeds[i].second->getParentOp() &&
                fact.family->isProperAncestor(fact.assignment);
        if (mutation == 1)
          ok &= facts->front().assignment == facts->back().assignment;
      }
      ok &= message.empty() || StringRef(diagnostics).contains(message);
    }
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostics;
    }
  };
  authoredAssignments("template-authored-empty-input-and-wire", templateWireSource("0"), true);
  authoredAssignments("template-authored-nonempty-input-and-wire", templateWireSource("3"), true);
  authoredAssignments("template-authored-repeated-seed-keeps-original-identity",
                      templateWireSource("0"), true, 1);
  authoredAssignments("template-authored-selected-root-is-not-template", selectedFixture(), false,
                      6, "direct whole root and exact final use");
  auto twoTemplates = replace(
      templateWireSource("0"),
      "%lanes_lo =", generatedFamily("other", "2", generatedChild("other")) + "%lanes_lo =");
  twoTemplates = replace(twoTemplates, "kind = \"register\", domain = @a,", "kind = \"wire\",");
  twoTemplates = replace(twoTemplates, assign("sink", "lane_out", "register_next"),
                         assign("sink", "lane_out"));
  authoredAssignments("template-authored-batched-empty-and-nonempty", twoTemplates, true);
  auto aliasedTemplate =
      replace(templateWireSource("0"), assign("lane_in", "x"),
              proxy("lane_alias", "lane", "in", "input") + assign("lane_alias", "x"));
  authoredAssignments("template-authored-exact-input-proxy-alias", aliasedTemplate, true);
  authoredAssignments("template-authored-missing-writer-refused",
                      replace(templateWireSource("0"), assign("lane_in", "x"), ""), false, 0,
                      "exactly one original whole writer");
  authoredAssignments("template-authored-empty-duplicate-refused",
                      replace(templateWireSource("0"), assign("lane_in", "x"),
                              assign("lane_in", "x") + assign("lane_in", "x")),
                      false, 0, "exactly one original whole writer");
  authoredAssignments("template-authored-live-duplicate-still-refused",
                      replace(templateWireSource("3"), assign("lane_in", "x"),
                              assign("lane_in", "x") + assign("lane_in", "x")),
                      false);
  authoredAssignments("template-authored-foreign-final-use-refused", templateWireSource("0"), false,
                      2);
  authoredAssignments("template-authored-foreign-value-refused", templateWireSource("0"), false, 3);
  authoredAssignments("template-authored-finite-batch-relation-budget", templateWireSource("0"),
                      false, 4, "finite relation budget");
  auto capturedTemplate = guardedGeneratedFixture();
  capturedTemplate =
      replace(capturedTemplate, "kind = \"register\", domain = @a,", "kind = \"wire\",");
  capturedTemplate = replace(capturedTemplate, assign("sink", "lane_out", "register_next"),
                             assign("sink", "lane_out"));
  authoredAssignments("template-authored-original-outer-arm-captures", capturedTemplate, true);
  authoredAssignments("template-authored-raw-source-capture-refused",
                      replace(capturedTemplate, assign("lane_in", "arm_x"), assign("lane_in", "x")),
                      false);
  auto guardedTemplate = [&](bool impossible) {
    auto source = templateWireSource("0");
    const std::string branch =
        R"mlir(%template_predicate = "nodal.static_compare"(%lanes_lo, %lanes_lo) <{comparison = "eq", metadata = {semantic_path = "Top.lanes.guard.predicate"}}> : (i64, i64) -> !nodal.static_predicate
"nodal.structural_if"(%template_predicate, %lane_in, %x) <{contract_version = "structural-if-v1", region_id = "Top.lanes.guard", operandSegmentSizes = array<i32: 1, 2, 0>, metadata = {semantic_path = "Top.lanes.guard"}}> ({^bb0(%arm_input: !nodal.bits<1>, %arm_data: !nodal.bits<1>):
"nodal.digital_assign"(%arm_input, %arm_data) <{kind = "continuous", metadata = {}}> : (!nodal.bits<1>, !nodal.bits<1>) -> ()
"nodal.structural_yield"() <{metadata = {semantic_path = "Top.lanes.guard.then.yield"}}> : () -> ()
}, {
"nodal.structural_yield"() <{metadata = {semantic_path = "Top.lanes.guard.else.yield"}}> : () -> ()}) : (!nodal.static_predicate, !nodal.bits<1>, !nodal.bits<1>) -> ()
)mlir";
    source = replace(source, assign("lane_in", "x"), branch);
    return impossible ? replace(source, "comparison = \"eq\"", "comparison = \"ne\"") : source;
  };
  authoredAssignments("template-authored-extra-guard-cannot-be-erased", guardedTemplate(false),
                      false, 0, "exact original structural arm ancestry");
  authoredAssignments("template-authored-impossible-guard-is-not-unguarded", guardedTemplate(true),
                      false, 0, "exact original structural arm ancestry");
  auto partialTemplate = templateWireSource("0");
  const std::string templateShape = "!nodal.shaped<\"2\", " + bit + ">";
  for (size_t at = 0; (at = partialTemplate.find(bit, at)) != std::string::npos;
       at += templateShape.size())
    partialTemplate.replace(at, bit.size(), templateShape);
  const std::string partialProjection = "%partial = \"nodal.shape_index\"(%sink, %lanes_lo) "
                                        "<{metadata = {test_partial = \"yes\"}}> : (" +
                                        templateShape + ", i64) -> " + bit + "\n";
  partialTemplate = replace(partialTemplate, "}) : (i64, i64, i64) -> !nodal.generated_family",
                            partialProjection + "}) : (i64, i64, i64) -> !nodal.generated_family");
  authoredAssignments("template-authored-partial-seed-is-not-whole", partialTemplate, false, 5);
  auto partialWriter = replace(partialTemplate, partialProjection, "");
  const std::string wholeAssignment =
      "\"nodal.digital_assign\"(%sink, %lane_out) <{kind = \"continuous\", metadata = {}}> : (" +
      templateShape + ", " + templateShape + ") -> ()\n";
  partialWriter =
      replace(partialWriter, wholeAssignment,
              partialProjection +
                  "%source_part = \"nodal.shape_index\"(%lane_out, %lanes_lo) "
                  "<{metadata = {}}> : (" +
                  templateShape + ", i64) -> " + bit + "\n" + assign("partial", "source_part"));
  authoredAssignments("template-authored-empty-partial-writer-refused", partialWriter, false, 0,
                      "partial or foreign-family writers");

  auto nestedSelected = [&] {
    auto inner = generatedFamily("outer.lanes", "3", generatedChild("outer.lanes") + exportLane);
    inner += "\"nodal.generated_export\"(%outer.lanes) <{member = \"inner\", access = \"read\", "
             "kind = \"family\", metadata = {}}> : (!nodal.generated_family) -> ()\n";
    auto parent =
        domain("a") + domain("b") + port("x", "input", "a") + generatedFamily("outer", "2", inner);
    parent += "%innerView = \"nodal.generated_member\"(%outer, %outer_lo) "
              "<{member = \"inner\", access = \"read\", kind = \"family\", metadata = {}}> "
              ": (!nodal.generated_family, i64) -> !nodal.generated_family\n";
    parent += replace(selectedLane, "%lanes, %lanes_step", "%innerView, %outer_step");
    parent += port("observed", "output", "a") + assign("observed", "selected");
    return "module attributes {nodal.root.module = @Top} {\n" + module("Top", parent) +
           module("Lane", requirement("req") + port("in", "input", "req") +
                              port("out", "output", "req") + assign("out", "in")) +
           "}\n";
  };
  check("selected-nested-prefix-retains-original-child-routing", nestedSelected(), true);
  check("selected-nested-prefix-cannot-invent-outer-occurrence",
        replace(nestedSelected(), "generated_member\"(%outer, %outer_lo)",
                "generated_member\"(%outer, %outer_hi)"),
        false);
  auto guardedSelected = guardedGeneratedFixture();
  auto selectedGuardedLane =
      replace(selectedLane, "%lanes, %lanes_step", "%guard.then.lanes, %guard.then.lanes_step");
  guardedSelected = replace(guardedSelected, "}) : (i64, i64, i64) -> !nodal.generated_family",
                            exportLane + "}) : (i64, i64, i64) -> !nodal.generated_family\n" +
                                selectedGuardedLane);
  check("selected-guarded-prefix-retains-exact-captured-input", guardedSelected, true);

  check("selected-guarded-direct-proxy-cannot-bypass-owner",
        replace(guardedSelected, selectedGuardedLane, proxy("selected", "lane", "out", "output")),
        false);
  auto capturedSelection = [&] {
    const std::string predicate =
        "%choice_predicate = \"nodal.static_compare\"(%lanes_step, %lanes_lo) "
        "<{comparison = \"gt\", metadata = {semantic_path = \"Top.choice.predicate\"}}> "
        ": (i64, i64) -> !nodal.static_predicate\n";
    const std::string branch =
        "\"nodal.structural_if\"(%choice_predicate, %lanes, %lanes_step) "
        "<{contract_version = \"structural-if-v1\", region_id = \"Top.choice\", "
        "operandSegmentSizes = array<i32: 1, 2, 0>, metadata = {semantic_path = \"Top.choice\"}}> "
        "({^bb0(%arm_family: !nodal.generated_family, %arm_ordinal: i64):\n" +
        replace(selectedLane, "%lanes, %lanes_step", "%arm_family, %arm_ordinal") +
        "\"nodal.structural_yield\"() <{metadata = {semantic_path = \"Top.choice.then.yield\"}}> "
        ": () -> ()\n}, {\n"
        "\"nodal.structural_yield\"() <{metadata = {semantic_path = \"Top.choice.else.yield\"}}> "
        ": () -> ()}) : (!nodal.static_predicate, !nodal.generated_family, i64) -> ()\n";
    return replace(selectedFixture(), selectedLane, predicate + branch);
  };
  check("selected-guarded-exact-family-and-ordinal-captures", capturedSelection(), true);
  check("selected-guarded-raw-family-cannot-bypass-capture",
        replace(capturedSelection(), "instance_port\"(%arm_family, %arm_ordinal)",
                "instance_port\"(%lanes, %arm_ordinal)"),
        false);
  check("selected-guarded-raw-ordinal-cannot-bypass-capture",
        replace(capturedSelection(), "instance_port\"(%arm_family, %arm_ordinal)",
                "instance_port\"(%arm_family, %lanes_step)"),
        false);

  auto minimumSelectedEdges = [&](bool same, bool state = false,
                                  StringRef count = "3") -> unsigned {
    auto parsed = parseSourceString<mlir::ModuleOp>(selectedObserved(true, same, state, count),
                                                    ParserConfig(&context, false));
    if (!parsed)
      return 0;
    auto hierarchy = nodal::HierarchyModel::create(parsed->getOperation());
    if (failed(hierarchy))
      return 0;
    unsigned low = 1, high = 1024;
    while (low < high) {
      unsigned middle = low + (high - low) / 2;
      nodal::DigitalAssignmentProofLimits limits;
      limits.maximumEdges = middle;
      if (succeeded(nodal::verifyHierarchyDependencies(*hierarchy, limits)))
        high = middle;
      else
        low = middle + 1;
    }
    return low == 1024 ? 0 : low;
  };
  auto repeatedEdges = minimumSelectedEdges(true), distinctEdges = minimumSelectedEdges(false);
  ++count;
  if (!repeatedEdges || !distinctEdges || repeatedEdges >= distinctEdges) {
    ++failures;
    llvm::errs() << "FAIL selected-context-interning-is-semantic-not-alias-pointer "
                 << repeatedEdges << " / " << distinctEdges << '\n';
  }
  llvm::outs() << "selected contexts minimum graph edges: repeated=" << repeatedEdges
               << " distinct=" << distinctEdges << '\n';

  // This measures the existing graph edge limit, not source walk size or
  // wall-clock speed. Only ordinal equality/cardinality changes between inputs.
  auto millionEdges = minimumSelectedEdges(false, false, "1000000");
  ++count;
  if (!millionEdges || millionEdges != distinctEdges) {
    ++failures;
    llvm::errs() << "FAIL selected-million-lanes-retain-authored-context-bound " << millionEdges
                 << " / " << distinctEdges << '\n';
  }
  auto repeatedStateEdges = minimumSelectedEdges(true, true);
  auto distinctStateEdges = minimumSelectedEdges(false, true);
  ++count;
  if (!repeatedStateEdges || !distinctStateEdges || repeatedStateEdges >= distinctStateEdges) {
    ++failures;
    llvm::errs() << "FAIL selected-state-contexts-retain-original-distinct-lane-identity "
                 << repeatedStateEdges << " / " << distinctStateEdges << '\n';
  }
  llvm::outs() << "selected contexts edge budgets: million=" << millionEdges
               << " repeated-state=" << repeatedStateEdges
               << " distinct-state=" << distinctStateEdges << '\n';

  auto selectedAliases = [&](bool same) {
    auto source = selectedFixture();
    auto alias = replace(selectedLane, "%selected =", "%selected_alias =");
    if (!same)
      alias = replace(alias, "%lanes, %lanes_step", "%lanes, %lanes_lo");
    return replace(source, selectedLane, selectedLane + alias);
  };
  auto shapeRelation = [&](StringRef name, bool same, bool local, bool identity, bool coverage,
                           bool reverse, bool expected) {
    diagnostics.clear();
    ++count;
    auto parsed =
        parseSourceString<mlir::ModuleOp>(selectedAliases(same), ParserConfig(&context, false));
    SmallVector<Operation *, 2> aliases;
    Operation *direct = nullptr;
    if (parsed)
      parsed->walk([&](nodal::InstancePortOp op) {
        if (op->hasAttr("member"))
          aliases.push_back(op);
        else if (op->getAttrOfType<StringAttr>("direction").getValue() == "output")
          direct = op;
      });
    bool actual = false;
    if (aliases.size() == 2 && direct) {
      Operation *a = aliases.front(), *b = local ? direct : aliases.back();
      auto left = nodal::resolveStaticShapeProjection(a->getResult(0), &a->getBlock()->back());
      auto right = nodal::resolveStaticShapeProjection(b->getResult(0), &b->getBlock()->back());
      if (succeeded(left) && succeeded(right)) {
        Operation *use = &a->getBlock()->back();
        if (reverse)
          std::swap(left, right);
        if (identity)
          actual = nodal::proveStaticShapeProjectionIdentity(*left, *right, use) ==
                   nodal::ShapeRegionProofStatus::Proved;
        else if (coverage)
          actual = nodal::verifyStaticShapeProjectionCoverage(*left, {*right}, use) ==
                   nodal::ShapeRegionProofStatus::Proved;
        else {
          auto relation = nodal::compareStaticShapeProjections(*left, *right, use);
          actual = relation.status == nodal::ShapeRegionProofStatus::Proved &&
                   relation.overlap == nodal::ShapeRegionOverlap::Disjoint;
        }
      }
    }
    if (!parsed || actual != expected) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostics;
    }
  };
  shapeRelation("selected-different-lanes-prove-disjoint", false, false, false, false, false, true);
  shapeRelation("selected-same-lane-aliases-overlap", true, false, false, false, false, false);
  shapeRelation("selected-same-lane-aliases-have-exact-identity", true, false, true, false, false,
                true);
  shapeRelation("selected-different-lanes-not-aliases", false, false, true, false, false, false);
  shapeRelation("selected-versus-template-is-not-disjoint", true, true, false, false, false, false);
  shapeRelation("selected-versus-template-is-not-exact-identity", true, true, true, false, false,
                false);
  shapeRelation("template-writer-covers-selected-read", true, true, false, true, false, true);
  shapeRelation("selected-writer-cannot-cover-template", true, true, false, true, true, false);

  auto selectedPayloadRelation = [&](StringRef name, bool sameCell, bool identity, bool expected) {
    ++count;
    diagnostics.clear();
    auto source = selectedAliases(true);
    const std::string shaped = "!nodal.shaped<\"2\", " + bit + ">";
    for (size_t at = 0; (at = source.find(bit, at)) != std::string::npos; at += shaped.size())
      source.replace(at, bit.size(), shaped);
    std::string projections =
        "%leftCell = \"nodal.shape_index\"(%selected, %lanes_lo) <{metadata = {}}> : (" + shaped +
        ", i64) -> " + bit + "\n" + "%rightCell = \"nodal.shape_index\"(%selected_alias, %" +
        (sameCell ? "lanes_lo" : "lanes_step") + ") <{metadata = {}}> : (" + shaped + ", i64) -> " +
        bit + "\n";
    source = replace(source, "}) : () -> ()", projections + "}) : () -> ()");
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    SmallVector<Operation *, 2> cells;
    if (parsed)
      parsed->walk([&](nodal::ShapeIndexOp op) { cells.push_back(op); });
    bool actual = false;
    if (cells.size() == 2) {
      auto left = nodal::resolveStaticShapeProjection(cells[0]->getResult(0), cells[1]);
      auto right = nodal::resolveStaticShapeProjection(cells[1]->getResult(0), cells[1]);
      if (succeeded(left) && succeeded(right)) {
        if (identity)
          actual = nodal::proveStaticShapeProjectionIdentity(*left, *right, cells[1]) ==
                   nodal::ShapeRegionProofStatus::Proved;
        else {
          auto relation = nodal::compareStaticShapeProjections(*left, *right, cells[1]);
          actual = relation.status == nodal::ShapeRegionProofStatus::Proved &&
                   relation.overlap == nodal::ShapeRegionOverlap::Disjoint;
        }
      }
    }
    if (!parsed || actual != expected) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostics;
    }
  };
  selectedPayloadRelation("selected-alias-disjoint-payload-cells", false, false, true);
  selectedPayloadRelation("selected-alias-overlapping-payload-cell", true, false, false);
  selectedPayloadRelation("selected-alias-exact-payload-cell-identity", true, true, true);
  selectedPayloadRelation("selected-alias-distinct-payload-cells-not-identical", false, true,
                          false);

  auto selectedWriters = [&](bool same, bool templateWriter) {
    auto source = selectedAliases(same);
    source = replace(source,
                     "member = \"child\", instance = @lane, port = \"out\", direction = \"output\"",
                     "member = \"child\", instance = @lane, port = \"in\", direction = \"input\"");
    source = replace(source,
                     "member = \"child\", instance = @lane, port = \"out\", direction = \"output\"",
                     "member = \"child\", instance = @lane, port = \"in\", direction = \"input\"");
    if (!templateWriter)
      source = replace(source, assign("lane_in", "x"), "");
    auto alias = replace(selectedLane, "%selected =", "%selected_alias =");
    if (!same)
      alias = replace(alias, "%lanes, %lanes_step", "%lanes, %lanes_lo");
    alias = replace(alias, "port = \"out\", direction = \"output\"",
                    "port = \"in\", direction = \"input\"");
    return replace(source, alias, alias + assign("selected", "x") + assign("selected_alias", "x"));
  };
  for (auto [name, same, local, expected] :
       {std::make_tuple("selected-distinct-input-writers", false, false, true),
        std::make_tuple("selected-same-input-writers-collide", true, false, false),
        std::make_tuple("selected-input-versus-template-writer-collides", false, true, false)}) {
    diagnostics.clear();
    ++count;
    auto parsed = parseSourceString<mlir::ModuleOp>(selectedWriters(same, local),
                                                    ParserConfig(&context, false));
    auto *top = parsed ? &parsed->getBody()->front() : nullptr;
    bool actual = top && succeeded(nodal::verifyDigitalAssignmentModel(top));
    if (!parsed || actual != expected) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostics;
    }
  }

  check("generated-child-guarded-capture", guardedGeneratedFixture(), true);
  check("generated-child-guarded-raw-outer-rejected", guardedGeneratedFixture(true), false,
        "parent-local SSA ownership");
  check("generated-child-guarded-wrong-clock-capture",
        replace(guardedGeneratedFixture(), "structural_if\"(%guard, %clock_a, %x)",
                "structural_if\"(%guard, %clock_b, %x)"),
        false, "actual instance Clock");
  check("generated-child-projected-reset", generatedResetFixture(), true);
  check("generated-child-wrong-projected-reset",
        replace(generatedResetFixture(), "instance_domain_binding\"(%clock_a, %reset0)",
                "instance_domain_binding\"(%clock_a, %reset1)"),
        false, "actual instance Reset");
  auto mutatedControl = [&](StringRef name, const std::string &source, auto mutate,
                            StringRef message) {
    diagnostics.clear();
    ++count;
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    nodal::InstanceDomainBindingOp actual;
    if (parsed)
      parsed->walk([&](nodal::InstanceDomainBindingOp op) { actual = op; });
    bool ok = parsed && actual && succeeded(verify(*parsed));
    diagnostics.clear();
    if (ok) {
      mutate(*parsed, actual.getOperation());
      ok = failed(verify(*parsed)) && StringRef(diagnostics).contains(message);
    }
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostics;
    }
  };
  mutatedControl(
      "generated-child-foreign-module-clock", generatedFixture(),
      [&](mlir::ModuleOp outer, Operation *actual) {
        outer.walk(
            [&](nodal::RequirementClockOp clock) { actual->setOperand(0, clock->getResult(0)); });
      },
      "parent-local SSA ownership");
  mutatedControl(
      "generated-child-nondominating-clock", generatedFixture(),
      [&](mlir::ModuleOp, Operation *actual) {
        Operation *copy = actual->getOperand(0).getDefiningOp()->clone();
        actual->getParentOfType<nodal::ModuleOp>()->getRegion(0).front().push_back(copy);
        actual->setOperand(0, copy->getResult(0));
      },
      "parent-local SSA ownership");
  mutatedControl(
      "generated-child-wrong-clock-capability-type", generatedResetFixture(),
      [&](mlir::ModuleOp, Operation *actual) { actual->setOperand(0, actual->getOperand(1)); },
      "requires one Clock");
  mutatedControl(
      "generated-child-wrong-reset-capability-type", generatedResetFixture(),
      [&](mlir::ModuleOp, Operation *actual) { actual->setOperand(1, actual->getOperand(0)); },
      "requires one Clock");
  check("generated-child-conditional-complete-input", conditionalGeneratedFixture(true), true);
  check("generated-child-conditional-hole-input", conditionalGeneratedFixture(false), false,
        "undriven combinational wire");
  check("ordinary-child-executable-crossing-control", crossingFamilyFixture(false), true);
  check("generated-child-executable-crossing-needs-selected-provenance",
        crossingFamilyFixture(true), false, "selection-qualified lane provenance");
  check("ordinary-child-resolved-split-control", resolvedFamilyFixture(false), true);
  check("generated-child-resolved-split-needs-selected-driver-proof", resolvedFamilyFixture(true),
        false, "selection-qualified driver proof");
  check("generated-child-local-boundary", generatedFixture(), true);
  check("generated-child-empty-family", generatedFixture("0"), true);
  check("generated-child-large-retained-family", generatedFixture("1000000"), true);
  check("generated-child-nested-family", generatedFixture("3", true), true);
  check("generated-child-clock-mismatch",
        replace(generatedFixture(), "instance_domain_binding\"(%clock_a)",
                "instance_domain_binding\"(%clock_b)"),
        false, "actual instance Clock");
  check("generated-child-foreign-domain",
        replace(generatedFixture(), "domain_bindings = {req = @a}",
                "domain_bindings = {req = @foreign}"),
        false, "parent actuals");
  check("generated-child-owner-forgery",
        replace(generatedFixture(), "generated_owner = \"Top.lanes\"",
                "generated_owner = \"Top.other\""),
        false, "exact generated owner");
  // A symbol reference outside the actual family never denotes a selected lane.
  check("generated-child-no-implicit-parent-selection",
        replace(generatedFixture(), port("x", "input", "a"),
                port("x", "input", "a") + proxy("escaped", "lane", "out", "output")),
        false, "exact lexical immediate-child");
  check("generated-child-direction-not-weakened",
        replace(generatedFixture(), "port = \"out\", direction = \"output\"",
                "port = \"out\", direction = \"input\""),
        false, "exact child data type");
  check("generated-child-output-readonly",
        replace(generatedFixture(), assign("lane_in", "x"), assign("lane_out", "x")), false,
        "read-only");
  check("generated-child-missing-input-connection",
        replace(generatedFixture(), assign("lane_in", "x"), ""), false, "typed connection");
  check("generated-child-missing-physical-binding",
        replace(generatedFixture(), binding("lane", "req", "a"), ""), false,
        "unique concrete instance binding");
  for (bool nested : {false, true}) {
    diagnostics.clear();
    ++count;
    auto parsed = parseSourceString<mlir::ModuleOp>(generatedFixture("1000000", nested),
                                                    ParserConfig(&context, false));
    auto hierarchy = parsed ? nodal::HierarchyModel::create(parsed->getOperation())
                            : FailureOr<std::shared_ptr<nodal::HierarchyModel>>(failure());
    bool ok = succeeded(hierarchy) && (*hierarchy)->occurrences().size() == 2;
    if (ok) {
      auto &context = (*hierarchy)->occurrences()[1];
      ok = context.generatedScopes.size() == (nested ? 2u : 1u) &&
           context.generatedScopes.back().domain.maximumTripCount == 1000000 &&
           context.generatedScopes.back().family == context.instance->getParentOp();
      for (const auto &scope : context.generatedScopes)
        ok &= scope.occurrence == 0 && scope.domain.induction &&
              scope.domain.induction == scope.family->getRegion(0).front().getArgument(0);
    }
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL generated-context-without-lane-expansion " << nested << '\n'
                   << diagnostics;
    }
  }

  check("child-to-parent-level-boundary", fixture(), true);
  check("parent-to-child-level-boundary", fixture(false, false, false, false, true), true);
  check("reused-definition-distinct-actual-clocks", fixture(true, false, true), true);
  check("independent-outputs-not-one-observation", fixture(true, false, false, false, false, false),
        true);
  check("same-crossing-result-fanout",
        replace(fixture(), assign("out", "q"), assign("out", "left_out")), true);
  check("cross-boundary-output-reconvergence", fixture(true, true, false, false, false, false),
        false, "NODAL-CDC-057-002");
  check("cross-boundary-state-reconvergence", fixture(true, true), false, "NODAL-CDC-057-002");
  check("forged-child-output-domain", fixture(false, false, false, true), false, "NODAL-CDC-001");
  check("default-binding-origin", replace(fixture(), "origin = \"named\"", "origin = \"default\""),
        true);
  check("inherited-binding-origin",
        replace(fixture(), "origin = \"named\"", "origin = \"inherited\""), true);
  check("actual-clock-swap-keeps-dictionary",
        replace(fixture(), "instance_domain_binding\"(%clock_a)",
                "instance_domain_binding\"(%clock_b)"),
        false, "actual instance Clock operand");
  check("actual-clock-swap-source-location",
        replace(fixture(), "instance_domain_binding\"(%clock_a)",
                "instance_domain_binding\"(%clock_b)"),
        false, "hierarchy-source.scala");
  check("missing-binding", replace(fixture(), binding("left", "src", "a"), ""), false,
        "lacks a unique concrete");
  check("duplicate-binding",
        replace(fixture(), binding("left", "src", "a"),
                binding("left", "src", "a") + binding("left", "src", "a")),
        false, "one authoritative binding");
  check("wrong-instance-binding",
        replace(fixture(), "instance = @left, requirement = @src",
                "instance = @absent, requirement = @src"),
        false, "immediate child's");
  check("dictionary-disagreement",
        replace(fixture(), "domain_bindings = {src = @a", "domain_bindings = {src = @b"), false,
        "dictionary must agree");
  check("foreign-requirement-projection",
        replace(fixture(), "requirement = @src, metadata", "requirement = @foreign, metadata"),
        false, "exact local requirement");
  check("wrong-projection-capability",
        replace(fixture(),
                "%src_clock = \"nodal.requirement_clock\"() <{requirement = @src, metadata = {}}> "
                ": () -> " +
                    clockType,
                "%src_clock = \"nodal.requirement_clock\"() <{requirement = @src, metadata = {}}> "
                ": () -> !nodal.capability<\"reset\">"),
        false, "Clock/Reset type");
  check("root-selector-required", replace(fixture(), " attributes {nodal.root.module = @Top}", ""),
        false, "root.module selector");
  check("nonexistent-root-rejected",
        replace(fixture(), "nodal.root.module = @Top", "nodal.root.module = @Missing"), false,
        "root.module selector");
  check("unresolved-root-requirement",
        replace(fixture(), "nodal.root.module = @Top", "nodal.root.module = @Child"), false,
        "lacks a unique concrete");
  check("child-output-readonly",
        replace(fixture(), assign("left_in", "x"), assign("left_out", "x")), false, "read-only");
  check("instance-input-single-driver",
        replace(fixture(), assign("left_in", "x"), assign("left_in", "x") + assign("left_in", "y")),
        false);
  check("missing-parent-data-connection", replace(fixture(), assign("left_in", "x"), ""), false,
        "typed connection");
  check("wrong-instance-port-direction",
        replace(fixture(), "port = \"out\", direction = \"output\"",
                "port = \"out\", direction = \"input\""),
        false, "exact child data type");
  std::string unused = "module attributes {nodal.root.module = @Flat} {" +
                       module("Flat", domain("a")) + module("Unused", requirement("req")) + "}";
  check("unused-well-formed-template-is-not-execution", unused, true);
  check("unused-malformed-template-is-rejected",
        replace(unused, "requirement = @req", "requirement = @foreign"), false,
        "exact local requirement");
  std::string unusedCrossing =
      replace(unused, module("Unused", requirement("req")),
              module("Unused", requirement("req") + port("x", "input", "req") +
                                   crossing("sync", "x", "req", "req", "Unused")));
  check("unused-identical-formal-crossing-is-malformed", unusedCrossing, false,
        "distinct typed endpoint references");
  std::string unboundConcrete = "\"nodal.domain\"() <{sym_name = \"actual\", edge = \"rising\", "
                                "reset_policy = \"none\", metadata = {}}> : () -> ()\n";
  check("unused-mixed-crossing-keeps-concrete-proof",
        replace(unused, module("Unused", requirement("req")),
                module("Unused", requirement("req") + unboundConcrete + port("x", "input", "req") +
                                     crossing("sync", "x", "req", "actual", "Unused"))),
        false, "concrete crossing endpoint requires");
  const std::string recurse =
      "\"nodal.instance\"() <{sym_name = \"recursive\", module = @Child, parameter_bindings = {}, "
      "domain_bindings = {src = @src, dst = @dst}, metadata = {}}> : () -> ()\n";
  check("reachable-definition-recursion",
        replace(fixture(), assign("out", "sync"), assign("out", "sync") + recurse), false,
        "recursive instance definition");
  const std::string loop =
      "\"nodal.instance\"() <{sym_name = \"recursive\", module = @Loop, parameter_bindings = {}, "
      "domain_bindings = {}, metadata = {}}> : () -> ()\n";
  std::string unusedLoop = replace(unused, module("Unused", requirement("req")),
                                   module("Unused", requirement("req")) + module("Loop", loop));
  check("unused-definition-recursion", unusedLoop, false, "including unused components");
  check("unused-unresolved-instance-target",
        replace(unusedLoop, "module = @Loop", "module = @Absent"), false,
        "resolved direct definition");
  std::string badConcrete = domain("a") + domain("b") + requirement("req") +
                            port("x", "input", "a") +
                            "%q = \"nodal.digital_storage\"() <{name = \"q\", kind = \"register\", "
                            "domain = @b, metadata = {}}> : () -> " +
                            bit + "\n" + assign("q", "x", "register_next");
  check("unused-formal-does-not-defer-concrete-CDC",
        replace(unused, module("Unused", requirement("req")), module("Unused", badConcrete)), false,
        "NODAL-CDC-001");
  check("alias-input-retains-one-driver",
        replace(fixture(), assign("left_in", "x"),
                assign("left_in", "x") + proxy("alias", "left", "in", "input") +
                    assign("alias", "x")),
        false);
  check("alias-output-retains-one-crossing",
        replace(fixture(), assign("q", "left_out", "register_next"),
                proxy("alias", "left", "out", "output") + assign("q", "alias", "register_next")),
        true);
  const std::string predicate =
      "%selected = \"nodal.sequential_select\"(%in, %sync, %sync) <{metadata = {}}> : (" + bit +
      ", " + bit + ", " + bit + ") -> " + bit + "\n";
  check("child-output-retains-predicate-provenance",
        replace(fixture(), assign("out", "sync"), predicate + assign("out", "selected")), false,
        "NODAL-CDC-001");
  check("cross-boundary-combinational-cycle",
        replace(fixture(false, false, false, true), assign("left_in", "x"),
                assign("left_in", "left_out")),
        false, "combinational dependency cycle");
  std::string rootLocal = "module attributes {nodal.root.module = @Top} {" +
                          module("Top", domain("a") + requirement("req") +
                                            "\"nodal.domain_bind\"() <{requirement = @req, actual "
                                            "= @a, metadata = {}}> : () -> ()\n") +
                          "}";
  check("local-formal-root-binding", rootLocal, true);
  std::string localAliasChild =
      "module attributes {nodal.root.module = @Top} {" +
      module("Top",
             domain("a") +
                 "\"nodal.instance\"() <{sym_name = \"child\", module = @Child, parameter_bindings "
                 "= {}, domain_bindings = {req = @a}, metadata = {}}> : () -> ()\n") +
      module("Child", domain("own") + requirement("req") +
                          "\"nodal.domain_bind\"() <{requirement = @req, actual = @own, metadata = "
                          "{}}> : () -> ()\n") +
      "}";
  check("local-child-alias-cannot-replace-instance-actual", localAliasChild, false,
        "lacks a unique concrete instance binding");
  check("local-and-instance-binding-authorities-conflict",
        replace(localAliasChild,
                module("Top", domain("a") + "\"nodal.instance\"() <{sym_name = \"child\", module = "
                                            "@Child, parameter_bindings = {}, domain_bindings = "
                                            "{req = @a}, metadata = {}}> : () -> ()\n"),
                module("Top", domain("a") +
                                  "\"nodal.instance\"() <{sym_name = \"child\", module = @Child, "
                                  "parameter_bindings = {}, domain_bindings = {req = @a}, metadata "
                                  "= {}}> : () -> ()\n" +
                                  binding("child", "req", "a"))),
        false, "both local and instance binding authorities");
  check("serialized-verified-marker-is-not-proof",
        replace(replace(fixture(), binding("left", "src", "a"), ""), "nodal.root.module = @Top",
                "nodal.root.module = @Top, nodal.hierarchy.verified = true"),
        false, "lacks a unique concrete");
  auto bounded = parseSourceString<mlir::ModuleOp>(fixture(true), ParserConfig(&context, false));
  ++count;
  if (!bounded ||
      succeeded(nodal::HierarchyModel::create(bounded->getOperation(), {2, 16, 128, 65536}))) {
    ++failures;
    llvm::errs() << "FAIL occurrence-budget\n";
  }
  auto print = [](Operation *op) {
    std::string result;
    llvm::raw_string_ostream out(result);
    op->print(out);
    return result;
  };
  auto target = parseSourceString<mlir::ModuleOp>(fixture(), ParserConfig(&context, false));
  ++count;
  diagnostics.clear();
  if (!target || failed(verify(*target))) {
    ++failures;
    llvm::errs() << "FAIL target-precondition\n";
  } else {
    PassManager manager(&context);
    manager.addPass(nodal::createSequentialToCIRCTPass());
    if (failed(manager.run(*target)) ||
        !StringRef(print(target->getOperation())).contains("hw.instance") ||
        StringRef(print(target->getOperation())).contains("__nodal_src_clock") ||
        !StringRef(print(target->getOperation())).contains("__nodal_dst_clock")) {
      ++failures;
      llvm::errs() << "FAIL concrete-hierarchy-target-emission\n" << diagnostics;
    }
    ++count;
    diagnostics.clear();
    auto generic = parseSourceString<mlir::ModuleOp>(fixture(), ParserConfig(&context, false));
    PassManager normalization(&context);
    normalization.addPass(nodal::createNormalizeSequentialPass());
    if (!generic || failed(normalization.run(*generic)) || failed(verify(*generic))) {
      ++failures;
      llvm::errs() << "FAIL generic-hierarchy-normalization\n" << diagnostics;
    }
  }
  // Source edge is a proof fact, not a physical input to a level synchronizer.
  // Changing it across occurrences must not split an otherwise shared body.
  {
    std::string input = fixture(true, false, false, false, false, false);
    input = replace(input, domain("a"),
                    replace(domain("c"), "edge = \"rising\"", "edge = \"falling\"") +
                        port("z", "input", "c") + domain("a"));
    input = replace(input, instance("right"), instance("right", "c", "b", "z"));
    auto unusedSourceEdge = parseSourceString<mlir::ModuleOp>(input, ParserConfig(&context, false));
    ++count;
    diagnostics.clear();
    PassManager manager(&context);
    manager.addPass(nodal::createSequentialToCIRCTPass());
    bool ok = unusedSourceEdge && succeeded(manager.run(*unusedSourceEdge));
    if (ok) {
      auto output = print(unusedSourceEdge->getOperation());
      ok = !StringRef(output).contains("__domain_") &&
           !StringRef(output).contains("__nodal_src_clock") &&
           StringRef(output).contains("__nodal_dst_clock");
    }
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL level-source-material-does-not-split-target\n" << diagnostics;
    }
  }
  // A raw child SSA handle must not become an alternative to instance_port.
  auto capture = parseSourceString<mlir::ModuleOp>(fixture(), ParserConfig(&context, false));
  ++count;
  if (!capture) {
    ++failures;
    llvm::errs() << "FAIL raw-capture-parse\n";
  } else {
    nodal::DigitalAssignOp connection;
    nodal::CrossingOp crossing;
    capture->walk([&](nodal::DigitalAssignOp op) {
      if (llvm::isa_and_nonnull<nodal::InstancePortOp>(op->getOperand(0).getDefiningOp()))
        connection = op;
    });
    capture->walk([&](nodal::CrossingOp op) { crossing = op; });
    if (!connection || !crossing) {
      ++failures;
      llvm::errs() << "FAIL raw-capture-fixture\n";
    } else {
      connection->setOperand(1, crossing->getResult(0));
      diagnostics.clear();
      if (succeeded(verify(*capture))) {
        ++failures;
        llvm::errs() << "FAIL raw-child-capture\n";
      }
    }
  }
  std::string many = fixture();
  std::string extra;
  for (unsigned i = 0; i < 32; ++i) {
    std::string name = "peer" + std::to_string(i);
    extra += instance(name) + port(name + "_observed", "output", "b") +
             assign(name + "_observed", name + "_out");
  }
  many = replace(many, instance("left"), instance("left") + extra);
  auto scaleStart = std::chrono::steady_clock::now();
  check("repeated-definition-33-independent-occurrences", many, true);
  llvm::outs() << "33 repeated child occurrences: "
               << std::chrono::duration_cast<std::chrono::microseconds>(
                      std::chrono::steady_clock::now() - scaleStart)
                      .count()
               << " us\n";
  std::string nested = "module attributes {nodal.root.module = @Top} {";
  auto forward = [&](StringRef target, StringRef actual, StringRef clock) {
    return "\"nodal.instance\"() <{sym_name = \"next\", module = @" + target.str() +
           ", parameter_bindings = {}, domain_bindings = {req = @" + actual.str() +
           "}, metadata = {}}> : () -> ()\n"
           "\"nodal.instance_domain_binding\"(%" +
           clock.str() + ") <{instance = @next, requirement = @req, actual = @" + actual.str() +
           ", origin = \"inherited\", metadata = {}}> : (" + clockType + ") -> ()\n";
  };
  nested += module("Top", domain("a") + forward("Layer0", "a", "clock_a"));
  for (unsigned i = 0; i < 32; ++i)
    nested +=
        module("Layer" + std::to_string(i),
               requirement("req") +
                   (i == 31 ? "" : forward("Layer" + std::to_string(i + 1), "req", "req_clock")));
  nested += "}";
  scaleStart = std::chrono::steady_clock::now();
  check("32-nested-forwarded-capabilities", nested, true);
  llvm::outs() << "32 nested child occurrences: "
               << std::chrono::duration_cast<std::chrono::microseconds>(
                      std::chrono::steady_clock::now() - scaleStart)
                      .count()
               << " us\n";
  auto start = std::chrono::steady_clock::now();
  for (unsigned i = 0; i < 16; ++i)
    check("repeated-context-proof", fixture(true, false, true), true);
  llvm::outs() << "16 repeated two-occurrence checks: "
               << std::chrono::duration_cast<std::chrono::microseconds>(
                      std::chrono::steady_clock::now() - start)
                      .count()
               << " us\n";
  llvm::outs() << count << " hierarchy data cases, " << failures << " failures\n";
  return failures ? 1 : 0;
}
