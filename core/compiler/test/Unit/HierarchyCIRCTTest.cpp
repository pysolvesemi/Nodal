#include "circt/Dialect/HW/HWOps.h"
#include "circt/Dialect/Seq/SeqOps.h"
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
using namespace mlir;
namespace {
const std::string bit = "!nodal.bits<1>", clockType = "!nodal.capability<\"clock\">",
                  resetType = "!nodal.capability<\"reset\">";
std::string owner(StringRef name, const std::string &body) {
  return "\"nodal.module\"() <{sym_name = \"" + name.str() + "\", metadata = {}}> ({\n" + body +
         "}) : () -> ()\n";
}
std::string port(StringRef name, StringRef direction, StringRef domain,
                 const std::string &type = bit) {
  return "\"nodal.port\"() <{sym_name = \"" + name.str() + "\", type = " + type +
         ", direction = \"" + direction.str() + "\", domain = @" + domain.str() +
         ", metadata = {}}> : () -> ()\n%" + name.str() + " = \"nodal.port_value\"() <{port = @" +
         name.str() + ", metadata = {}}> : () -> " + type + "\n";
}
std::string domain(StringRef name, StringRef edge = "rising", StringRef polarity = "active_high",
                   bool reset = true) {
  auto n = name.str();
  return "\"nodal.domain\"() <{sym_name = \"" + n + "\", edge = \"" + edge.str() +
         "\", reset_policy = \"" + (reset ? "sync" : "none") + "\", metadata = {}}> : () -> ()\n" +
         port("clock_" + n, "input", name, clockType) +
         (reset ? port("reset_" + n, "input", name, resetType) : "") +
         "\"nodal.local_domain_binding\"(%clock_" + n + (reset ? ", %reset_" + n : "") +
         ") <{domain = @" + n + ", reset_polarity = \"" + polarity.str() +
         "\", metadata = {}}> : (" + clockType + (reset ? ", " + resetType : "") + ") -> ()\n";
}
std::string requirement(bool projections = false) {
  return "\"nodal.domain_requirement\"() <{sym_name = \"req\", metadata = {}}> : () -> ()\n" +
         (projections
              ? "%clock_req = \"nodal.requirement_clock\"() <{requirement = @req, metadata = "
                "{}}> : () -> " +
                    clockType +
                    "\n%reset_req = \"nodal.requirement_reset\"() <{requirement = @req, metadata "
                    "= {}}> : () -> " +
                    resetType + "\n"
              : "");
}
std::string assign(StringRef target, StringRef value, StringRef kind = "continuous") {
  return "\"nodal.digital_assign\"(%" + target.str() + ", %" + value.str() + ") <{kind = \"" +
         kind.str() + "\", metadata = {}}> : (" + bit + ", " + bit + ") -> ()\n";
}
std::string instance(StringRef name, StringRef target, StringRef actual, StringRef input,
                     bool reset = true) {
  auto n = name.str();
  return "\"nodal.instance\"() <{sym_name = \"" + n + "\", module = @" + target.str() +
         ", domain_bindings = {req = @" + actual.str() +
         "}, parameter_bindings = {}, metadata = {}}> : () -> ()\n"
         "\"nodal.instance_domain_binding\"(%clock_" +
         actual.str() + (reset ? ", %reset_" + actual.str() : "") + ") <{instance = @" + n +
         ", requirement = @req, actual = @" + actual.str() +
         ", origin = \"named\", metadata = {}}> : (" + clockType + (reset ? ", " + resetType : "") +
         ") -> () loc(\"hierarchy-target.scala\":21:7)\n%" + n +
         "_input = \"nodal.instance_port\"() <{instance = @" + n +
         ", port = \"input\", direction = \"input\", metadata = {}}> : () -> " + bit + "\n%" + n +
         "_output = \"nodal.instance_port\"() <{instance = @" + n +
         ", port = \"output\", direction = \"output\", metadata = {}}> : () -> " + bit + "\n" +
         assign(n + "_input", input);
}
std::string leaf(bool initialized = true) {
  return requirement() + port("input", "input", "req") + port("output", "output", "req") +
         "%zero = \"nodal.const_literal\"() <{value = false, spelling = \"0\", metadata = "
         "{}}> : () -> " +
         bit + "\n%state = \"nodal.digital_storage\"(" + (initialized ? "%zero" : "") +
         ") <{name = \"state\", kind = \"register\", domain = @req, metadata = {}}> : (" +
         (initialized ? bit : "") + ") -> " + bit + "\n" +
         assign("state", "input", "register_next") + assign("output", "state");
}
std::string fixture(bool material = false, bool nested = false, bool resetless = false,
                    unsigned repeated = 2) {
  std::string top = domain("a") +
                    domain("b", material ? "falling" : "rising",
                           material ? "active_low" : "active_high", !resetless) +
                    port("x", "input", "a") + port("y", "input", "b");
  for (unsigned i = 0; i < repeated; ++i) {
    auto name = "child" + std::to_string(i);
    auto actual = i % 2 ? "b" : "a";
    auto target = nested && i % 2 ? "Forward" : resetless && i % 2 ? "Resetless" : "Child";
    top += instance(name, target, actual, i % 2 ? "y" : "x", !(resetless && i % 2));
    top += port("result" + std::to_string(i), "output", actual) +
           assign("result" + std::to_string(i), name + "_output");
  }
  std::string result = "module attributes {nodal.root.module = @Top} {\n" + owner("Top", top) +
                       owner("Child", leaf());
  if (nested)
    result +=
        owner("Forward",
              requirement(true) + port("input", "input", "req") + port("output", "output", "req") +
                  instance("leaf", "Child", "req", "input") + assign("output", "leaf_output"));
  if (resetless)
    result += owner("Resetless", leaf(false));
  return result + "}\n";
}
std::string replace(std::string text, StringRef from, StringRef to) {
  auto at = text.find(from.str());
  if (at == std::string::npos)
    llvm::report_fatal_error("missing hierarchy target mutation");
  return text.replace(at, from.size(), to.str());
}
std::string print(Operation *op) {
  std::string result;
  llvm::raw_string_ostream out(result);
  op->print(out);
  return result;
}
} // namespace
int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  std::string diagnostics;
  ScopedDiagnosticHandler handler(&context, [&](Diagnostic &d) {
    llvm::raw_string_ostream out(diagnostics);
    d.getLocation().print(out);
    d.print(out);
    out << '\n';
    return success();
  });
  unsigned count = 0, failures = 0;
  auto report = [&](StringRef name, bool ok) {
    ++count;
    if (!ok) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostics;
    }
  };
  auto positive = [&](StringRef name, const std::string &input, unsigned modules,
                      unsigned instances, unsigned states, unsigned falling = 0) {
    diagnostics.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(input, ParserConfig(&context, false));
    PassManager manager(&context);
    manager.addPass(nodal::createSequentialToCIRCTPass());
    bool ok = parsed && succeeded(verify(*parsed)) && succeeded(manager.run(*parsed));
    unsigned m = 0, i = 0, s = 0, f = 0;
    if (ok) {
      parsed->walk([&](circt::hw::HWModuleOp) { ++m; });
      parsed->walk([&](circt::hw::InstanceOp) { ++i; });
      parsed->walk([&](circt::seq::CompRegOp) { ++s; });
      parsed->walk([&](circt::seq::ClockInverterOp) { ++f; });
      ok = m == modules && i == instances && s == states && f == falling &&
           StringRef(print(parsed->getOperation())).contains("__nodal_req_clock");
    }
    report(name, ok);
  };
  positive("shared-definition-independent-clock-inputs", fixture(), 2, 2, 1);
  positive("material-edge-polarity-variants", fixture(true), 3, 2, 2, 1);
  positive("nested-forwarded-domain", fixture(false, true), 3, 3, 1);
  positive("resetless-distinct-state-shape", fixture(false, false, true), 3, 2, 2);
  positive("repeated-definition-not-per-occurrence-cloned", fixture(false, false, false, 32), 2, 32,
           1);
  diagnostics.clear();
  {
    auto parsed = parseSourceString<mlir::ModuleOp>(fixture(), ParserConfig(&context, false));
    PassManager manager(&context);
    manager.addPass(nodal::createSequentialToCIRCTPass());
    bool wired = parsed && succeeded(manager.run(*parsed));
    circt::hw::HWModuleOp top;
    if (wired)
      for (auto module : parsed->getOps<circt::hw::HWModuleOp>())
        if (module->getAttrOfType<StringAttr>("sym_name").getValue() == "Top")
          top = module;
    wired &= bool(top);
    if (wired) {
      auto *body = top.getBodyBlock();
      SmallVector<circt::hw::InstanceOp> instances;
      for (auto instance : body->getOps<circt::hw::InstanceOp>())
        instances.push_back(instance);
      wired = body->getNumArguments() == 6 && instances.size() == 2;
      if (wired) {
        // Both references name one child definition, but no physical input or
        // output result is shared. This checks causal wiring, not only op counts.
        wired = instances[0].getModuleName() == "Child" && instances[1].getModuleName() == "Child";
        for (unsigned i = 0; i < 2; ++i) {
          wired &= instances[i]->getNumOperands() == 3 &&
                   instances[i]->getOperand(0) == body->getArgument(4 + i) &&
                   instances[i]->getOperand(1) == body->getArgument(2 * i) &&
                   instances[i]->getOperand(2) == body->getArgument(2 * i + 1) &&
                   body->back().getOperand(i) == instances[i].getResult(0);
        }
      }
    }
    report("shared-definition-preserves-exact-independent-causal-edges", wired);
  }
  {
    auto formal = [](StringRef name) {
      auto result =
          replace(requirement(true), "sym_name = \"req\"", "sym_name = \"" + name.str() + "\"");
      result = replace(result, "%clock_req", "%clock_" + name.str());
      result = replace(result, "%reset_req", "%reset_" + name.str());
      result = replace(result, "requirement = @req", "requirement = @" + name.str());
      result = replace(result, "requirement = @req", "requirement = @" + name.str());
      return result;
    };
    std::string child =
        formal("src") + formal("dst") + port("input", "input", "src") +
        port("output", "output", "dst") +
        "%gray = \"nodal.crossing\"(%input) <{source_domain = @src, destination_domain = @dst, "
        "kind = \"gray\", contract = \"gray_v1\", stages = 2 : i64, obligations = {"
        "transition = \"one_bit_per_source_sample\", skew = \"less_than_source_period\", "
        "stability = \"stable_between_source_samples\"}, metadata = {semantic_path = "
        "\"Child.gray\"}}> : (" +
        bit + ") -> " + bit + "\n" + assign("output", "gray");
    std::string occurrence = instance("child", "Child", "a", "x");
    occurrence = replace(occurrence, "domain_bindings = {req = @a}",
                         "domain_bindings = {src = @a, dst = @b}");
    occurrence = replace(occurrence, "requirement = @req", "requirement = @src");
    occurrence += "\"nodal.instance_domain_binding\"(%clock_b, %reset_b) <{instance = @child, "
                  "requirement = @dst, actual = @b, origin = \"named\", metadata = {}}> : (" +
                  clockType + ", " + resetType + ") -> ()\n";
    auto input =
        "module attributes {nodal.root.module = @Top} {\n" +
        owner("Top", domain("a") + domain("b") + port("x", "input", "a") + occurrence +
                         port("result", "output", "b") + assign("result", "child_output")) +
        owner("Child", child) + "}\n";
    diagnostics.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(input, ParserConfig(&context, false));
    PassManager manager(&context);
    manager.addPass(nodal::createSequentialToCIRCTPass());
    bool minimal = parsed && succeeded(manager.run(*parsed));
    if (minimal) {
      auto output = print(parsed->getOperation());
      minimal = StringRef(output).contains("__nodal_src_reset") &&
                StringRef(output).contains("__nodal_dst_clock") &&
                !StringRef(output).contains("__nodal_dst_reset");
    }
    report("gray-destination-payload-does-not-demand-reset", minimal);
  }
  auto negative = [&](StringRef name, const std::string &input, StringRef diagnostic) {
    diagnostics.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(input, ParserConfig(&context, false));
    auto before = parsed ? print(parsed->getOperation()) : "";
    PassManager manager(&context);
    manager.addPass(nodal::createSequentialToCIRCTPass());
    bool ok = parsed && failed(manager.run(*parsed)) && before == print(parsed->getOperation()) &&
              StringRef(diagnostics).contains(diagnostic);
    report(name, ok);
  };
  negative("actual-clock-swap-keeps-descriptor",
           replace(fixture(), "instance_domain_binding\"(%clock_a,",
                   "instance_domain_binding\"(%clock_b,"),
           "Clock operand disagrees");
  negative("actual-reset-swap-keeps-descriptor",
           replace(fixture(), "instance_domain_binding\"(%clock_a, %reset_a)",
                   "instance_domain_binding\"(%clock_a, %reset_b)"),
           "Reset operand disagrees");
  negative("initialized-resetless-occurrence",
           replace(fixture(false, false, true), owner("Resetless", leaf(false)),
                   owner("Resetless", leaf(true))),
           "initialized child state requires");
  negative("unbounded-ordinary-parameter-remains-unsupported-atomically",
           replace(fixture(), owner("Child", leaf()),
                   owner("Child",
                         leaf() + "\"nodal.parameter\"() <{sym_name = \"N\", type = i64, "
                                  "default_value = 4 : i64, variability = \"symbolic\", metadata "
                                  "= {}}> : () -> ()\n")),
           "target Integer parameter envelope must fit signed 32-bit arithmetic");
  diagnostics.clear();
  auto normalized = parseSourceString<mlir::ModuleOp>(fixture(), ParserConfig(&context, false));
  bool ok = bool(normalized);
  if (ok) {
    for (auto module : normalized->getOps<nodal::ModuleOp>())
      ok &= succeeded(nodal::normalizeSequentialModel(module));
    unsigned formal = 0;
    normalized->walk([&](nodal::SequentialUpdateOp update) {
      if (!update->hasAttr("edge") && !update->hasAttr("reset_policy") &&
          !update->hasAttr("reset_polarity"))
        ++formal;
    });
    ok &= succeeded(verify(*normalized)) && formal == 1;
  }
  report("normalization-retains-formal-material-absence", ok);
  if (ok) {
    auto malformed = cast<mlir::ModuleOp>(normalized->clone());
    malformed.walk([&](nodal::SequentialUpdateOp update) {
      update->setAttr("edge", StringAttr::get(&context, "rising"));
    });
    diagnostics.clear();
    report("formal-update-cannot-guess-one-material-field",
           failed(verify(malformed)) && StringRef(diagnostics).contains("no guessed material"));
    malformed.erase();
  }
  llvm::outs() << count << " hierarchy target cases, " << failures << " failures\n";
  return failures ? 1 : 0;
}
