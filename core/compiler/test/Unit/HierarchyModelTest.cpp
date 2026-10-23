#include "nodal/Dialect/Nodal/HierarchyModel.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Pass/PassManager.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"
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
    return "%" + n + "_" + suffix.str() +
           " = \"nodal.const_literal\"() <{value = " + value.str() +
           " : i64, spelling = \"" + value.str() +
           "\", metadata = {}}> : () -> i64\n";
  };
  return literal("lo", "0") + literal("hi", count) + literal("step", "1") +
         "%" + n + " = \"nodal.generate\"(%" + n + "_lo, %" + n + "_hi, %" + n +
         "_step) <{induction = \"i\", region_id = \"Top." + n +
         "\", induction_path = \"Top." + n +
         ".index\", metadata = {maximum_trip_count = " + count.str() + " : i64, semantic_path = \"Top." + n + "\"}}> ({^bb0(%" + n +
         "_index: i64):\n" + body + "}) : (i64, i64, i64) -> !nodal.generated_family\n";
}
std::string generatedChild(StringRef family) {
  std::string scope = "generated_owner = \"Top." + family.str() +
                      "\", generated_induction = \"Top." + family.str() + ".index\", ";
  return "\"nodal.instance\"() <{sym_name = \"lane\", module = @Lane, parameter_bindings = {}, "
         "domain_bindings = {req = @a}, " + scope + "metadata = {}}> : () -> ()\n" +
         binding("lane", "req", "a") + proxy("lane_in", "lane", "in", "input") +
         proxy("lane_out", "lane", "out", "output") + assign("lane_in", "x") +
         "%sink = \"nodal.digital_storage\"() <{name = \"sink\", kind = \"register\", "
         "domain = @a, metadata = {}}> : () -> " + bit + "\n" +
         assign("sink", "lane_out", "register_next");
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
      "%guard_zero = \"nodal.const_literal\"() <{value = 0 : i64, spelling = \"0\", metadata = {semantic_path = \"Top.guard_zero\"}}> : () -> i64\n"
      "%guard_one = \"nodal.const_literal\"() <{value = 1 : i64, spelling = \"1\", metadata = {semantic_path = \"Top.guard_one\"}}> : () -> i64\n"
      "%guard = \"nodal.static_compare\"(%guard_one, %guard_zero) <{comparison = \"gt\", metadata = {semantic_path = \"Top.guard.predicate\"}}> : (i64, i64) -> !nodal.static_predicate\n"
      "\"nodal.structural_if\"(%guard, %clock_a, %x) <{contract_version = \"structural-if-v1\", region_id = \"Top.guard\", operandSegmentSizes = array<i32: 1, 2, 0>, metadata = {semantic_path = \"Top.guard\"}}> ({^bb0(%arm_clock: " + clockType + ", %arm_x: " + bit + "):\n" +
      generatedFamily("guard.then.lanes", "3", child) +
      "\"nodal.structural_yield\"() <{metadata = {semantic_path = \"Top.guard.then.yield\"}}> : () -> ()\n"
      "}, {\"nodal.structural_yield\"() <{metadata = {semantic_path = \"Top.guard.else.yield\"}}> : () -> ()}) : (!nodal.static_predicate, " + clockType + ", " + bit + ") -> ()\n";
  return "module attributes {nodal.root.module = @Top} {\n" +
         module("Top", domain("a") + domain("b") + port("x", "input", "a") + branch) +
         module("Lane", requirement("req") + port("in", "input", "req") +
                            port("out", "output", "req") + assign("out", "in")) + "}\n";
}

std::string generatedResetFixture() {
  const std::string resetType = "!nodal.capability<\"reset\">";
  const std::string resetsType = "!nodal.shaped<\"2\", " + resetType + ">";
  std::string controls =
      "\"nodal.port\"() <{sym_name = \"resets\", type = " + resetsType + ", direction = \"input\", domain = @a, metadata = {}}> : () -> ()\n"
      "%resets = \"nodal.port_value\"() <{port = @resets, metadata = {}}> : () -> " + resetsType + "\n"
      "%r0 = \"nodal.constant\"() <{value = 0 : index, metadata = {}}> : () -> index\n"
      "%r1 = \"nodal.constant\"() <{value = 1 : index, metadata = {}}> : () -> index\n"
      "%reset0 = \"nodal.shape_index\"(%resets, %r0) <{metadata = {}}> : (" + resetsType + ", index) -> " + resetType + "\n"
      "%reset1 = \"nodal.shape_index\"(%resets, %r1) <{metadata = {}}> : (" + resetsType + ", index) -> " + resetType + "\n";
  std::string actualDomain = replace(domain("a"), "reset_policy = \"none\"", "reset_policy = \"sync\"");
  actualDomain = replace(actualDomain, "\"nodal.local_domain_binding\"(%clock_a)", controls + "\"nodal.local_domain_binding\"(%clock_a, %reset0)");
  actualDomain = replace(actualDomain, ": (" + clockType + ") -> ()", ": (" + clockType + ", " + resetType + ") -> ()");
  std::string child = generatedChild("lanes");
  std::string bound = replace(binding("lane", "req", "a"), "(%clock_a)", "(%clock_a, %reset0)");
  bound = replace(bound, ": (" + clockType + ") -> ()", ": (" + clockType + ", " + resetType + ") -> ()");
  child = replace(child, binding("lane", "req", "a"), bound);
  return "module attributes {nodal.root.module = @Top} {\n" +
         module("Top", actualDomain + port("x", "input", "a") + generatedFamily("lanes", "3", child)) +
         module("Lane", requirement("req") + port("in", "input", "req") + port("out", "output", "req") + assign("out", "in")) + "}\n";
}

std::string conditionalGeneratedFixture(bool complete) {
  std::string effect =
      "%condition_one = \"nodal.const_literal\"() <{value = 1 : i64, spelling = \"1\", metadata = {semantic_path = \"Top.lanes.one\"}}> : () -> i64\n"
      "%condition = \"nodal.static_compare\"(%lanes_index, %condition_one) <{comparison = \"lt\", metadata = {semantic_path = \"Top.lanes.condition\"}}> : (i64, i64) -> !nodal.static_predicate\n"
      "\"nodal.structural_if\"(%condition, %lane_in, %x" + std::string(complete ? ", %lane_in, %x" : "") + ") <{contract_version = \"structural-if-v1\", region_id = \"Top.lanes.choice\", operandSegmentSizes = array<i32: 1, 2, " + (complete ? "2" : "0") + ">, metadata = {semantic_path = \"Top.lanes.choice\"}}> ({^bb0(%in_t: " + bit + ", %x_t: " + bit + "):\n" +
      assign("in_t", "x_t") + "\"nodal.structural_yield\"() <{metadata = {semantic_path = \"Top.lanes.choice.then.yield\"}}> : () -> ()\n}, {" +
      (complete ? "^bb0(%in_f: " + bit + ", %x_f: " + bit + "):\n" + assign("in_f", "x_f") : "") +
      "\"nodal.structural_yield\"() <{metadata = {semantic_path = \"Top.lanes.choice.else.yield\"}}> : () -> ()\n}) : (!nodal.static_predicate, " + bit + ", " + bit + (complete ? ", " + bit + ", " + bit : "") + ") -> ()\n";
  return replace(generatedFixture(), assign("lane_in", "x"), effect);
}

std::string crossingFamilyFixture(bool generated) {
  std::string body = instance("left");
  if (generated)
    body = replace(body, "module = @Child,", "module = @Child, generated_owner = \"Top.lanes\", generated_induction = \"Top.lanes.index\",");
  body += "%q = \"nodal.digital_storage\"() <{name = \"q\", kind = \"register\", domain = @b, metadata = {}}> : () -> " + bit + "\n" + assign("q", "left_out", "register_next");
  if (generated)
    body = generatedFamily("lanes", "3", body);
  std::string child = requirement("src") + requirement("dst") + port("in", "input", "src") + port("out", "output", "dst") + crossing("sync", "in", "src", "dst", "Child") + assign("out", "sync");
  return "module attributes {nodal.root.module = @Top} {\n" + module("Top", domain("a") + domain("b") + port("x", "input", "a") + body) + module("Child", child) + "}\n";
}
std::string resolvedFamilyFixture(bool generated) {
  const std::string net = "!nodal.resolved<\"push_pull\", " + bit + ">";
  const std::string tag = "metadata = {interface_abi_version = 1 : i64}";
  const std::string child = requirement("req") + port("in", "input", "req") + port("out", "output", "req") +
      "%net = \"nodal.resolved_net\"() <{name = \"net\", metadata = {interface_abi_version = 1 : i64, placement = \"top_level_pin\", profile = \"portable_boundary_only\"}}> : () -> " + net + "\n"
      "%driver = \"nodal.net_driver\"(%net) <{driver_id = \"driver\", " + tag + "}> : (" + net + ") -> !nodal.driver<" + bit + ">\n"
      "%enable = \"nodal.const_literal\"() <{value = 1 : ui1, spelling = \"1\", metadata = {}}> : () -> " + bit + "\n"
      "\"nodal.net_drive\"(%net, %driver, %in, %enable) <{mode = \"push_pull\", " + tag + "}> : (" + net + ", !nodal.driver<" + bit + ">, " + bit + ", " + bit + ") -> ()\n"
      "%write, %enabled = \"nodal.net_split\"(%net) <{" + tag + "}> : (" + net + ") -> (" + bit + ", " + bit + ")\n" + assign("out", "write");
  std::string body = generatedChild("lanes");
  if (generated)
    body = generatedFamily("lanes", "3", body);
  else
    body = replace(body, "generated_owner = \"Top.lanes\", generated_induction = \"Top.lanes.index\", ", "");
  return "module attributes {nodal.root.module = @Top} {\n" + module("Top", domain("a") + port("x", "input", "a") + body) + module("Lane", child) + "}\n";
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

  check("generated-child-guarded-capture", guardedGeneratedFixture(), true);
  check("generated-child-guarded-raw-outer-rejected", guardedGeneratedFixture(true), false,
        "parent-local SSA ownership");
  check("generated-child-guarded-wrong-clock-capture",
        replace(guardedGeneratedFixture(), "structural_if\"(%guard, %clock_a, %x)",
                "structural_if\"(%guard, %clock_b, %x)"), false, "actual instance Clock");
  check("generated-child-projected-reset", generatedResetFixture(), true);
  check("generated-child-wrong-projected-reset",
        replace(generatedResetFixture(), "instance_domain_binding\"(%clock_a, %reset0)",
                "instance_domain_binding\"(%clock_a, %reset1)"), false, "actual instance Reset");
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
  mutatedControl("generated-child-foreign-module-clock", generatedFixture(),
                 [&](mlir::ModuleOp outer, Operation *actual) {
                   outer.walk([&](nodal::RequirementClockOp clock) {
                     actual->setOperand(0, clock->getResult(0));
                   });
                 }, "parent-local SSA ownership");
  mutatedControl("generated-child-nondominating-clock", generatedFixture(),
                 [&](mlir::ModuleOp, Operation *actual) {
                   Operation *copy = actual->getOperand(0).getDefiningOp()->clone();
                   actual->getParentOfType<nodal::ModuleOp>()->getRegion(0).front().push_back(copy);
                   actual->setOperand(0, copy->getResult(0));
                 }, "parent-local SSA ownership");
  mutatedControl("generated-child-wrong-clock-capability-type", generatedResetFixture(),
                 [&](mlir::ModuleOp, Operation *actual) { actual->setOperand(0, actual->getOperand(1)); },
                 "requires one Clock");
  mutatedControl("generated-child-wrong-reset-capability-type", generatedResetFixture(),
                 [&](mlir::ModuleOp, Operation *actual) { actual->setOperand(1, actual->getOperand(0)); },
                 "requires one Clock");
  check("generated-child-conditional-complete-input", conditionalGeneratedFixture(true), true);
  check("generated-child-conditional-hole-input", conditionalGeneratedFixture(false), false,
        "undriven combinational wire");
  check("ordinary-child-executable-crossing-control", crossingFamilyFixture(false), true);
  check("generated-child-executable-crossing-needs-selected-provenance", crossingFamilyFixture(true), false,
        "selection-qualified lane provenance");
  check("ordinary-child-resolved-split-control", resolvedFamilyFixture(false), true);
  check("generated-child-resolved-split-needs-selected-driver-proof", resolvedFamilyFixture(true), false,
        "selection-qualified driver proof");
  check("generated-child-local-boundary", generatedFixture(), true);
  check("generated-child-empty-family", generatedFixture("0"), true);
  check("generated-child-large-retained-family", generatedFixture("1000000"), true);
  check("generated-child-nested-family", generatedFixture("3", true), true);
  check("generated-child-clock-mismatch",
        replace(generatedFixture(), "instance_domain_binding\"(%clock_a)",
                "instance_domain_binding\"(%clock_b)"), false, "actual instance Clock");
  check("generated-child-foreign-domain",
        replace(generatedFixture(), "domain_bindings = {req = @a}",
                "domain_bindings = {req = @foreign}"), false, "parent actuals");
  check("generated-child-owner-forgery",
        replace(generatedFixture(), "generated_owner = \"Top.lanes\"",
                "generated_owner = \"Top.other\""), false, "exact generated owner");
  // A symbol reference outside the actual family never denotes a selected lane.
  check("generated-child-no-implicit-parent-selection",
        replace(generatedFixture(), port("x", "input", "a"),
                port("x", "input", "a") + proxy("escaped", "lane", "out", "output")),
        false, "exact lexical immediate-child");
  check("generated-child-direction-not-weakened",
        replace(generatedFixture(), "port = \"out\", direction = \"output\"",
                "port = \"out\", direction = \"input\""), false, "exact child data type");
  check("generated-child-output-readonly",
        replace(generatedFixture(), assign("lane_in", "x"), assign("lane_out", "x")),
        false, "read-only");
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
