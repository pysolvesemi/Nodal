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
    const std::string before = print(target->getOperation());
    PassManager manager(&context);
    manager.addPass(nodal::createSequentialToCIRCTPass());
    if (succeeded(manager.run(*target)) || print(target->getOperation()) != before ||
        !StringRef(diagnostics).contains("Foundation 58")) {
      ++failures;
      llvm::errs() << "FAIL transactional-target-rejection\n" << diagnostics;
    }
    ++count;
    diagnostics.clear();
    PassManager normalization(&context);
    normalization.addPass(nodal::createNormalizeSequentialPass());
    if (succeeded(normalization.run(*target)) || print(target->getOperation()) != before ||
        !StringRef(diagnostics).contains("Foundation 58")) {
      ++failures;
      llvm::errs() << "FAIL transactional-normalization-rejection\n" << diagnostics;
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
