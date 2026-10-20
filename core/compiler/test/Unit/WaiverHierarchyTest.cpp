#include "WaiverTestFixtures.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Pass/PassManager.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/WaiverModel.h"
#include "nodal/Transforms/Passes.h"

#include "llvm/Support/raw_ostream.h"
using namespace mlir;
using namespace waiver_test;
namespace {
std::string requirement(std::string name) {
  return "\"nodal.domain_requirement\"() <{sym_name = \"" + name +
         "\", metadata = {}}> : () -> ()\n%" + name +
         "_clock = \"nodal.requirement_clock\"() <{requirement = @" + name +
         ", metadata = {}}> : () -> " + clockType + "\n";
}
std::string binding(std::string instance, std::string requirement, std::string actual) {
  return "\"nodal.instance_domain_binding\"(%" + actual + "Clock) <{instance = @" + instance +
         ", requirement = @" + requirement + ", actual = @" + actual +
         ", origin = \"named\", metadata = {}}> : (" + clockType + ") -> ()\n";
}
std::string proxy(std::string instance, std::string portName, std::string direction) {
  return "%" + instance + "_" + portName + " = \"nodal.instance_port\"() <{instance = @" +
         instance + ", port = \"" + portName + "\", direction = \"" + direction +
         "\", metadata = {}}> : () -> " + bit + "\n";
}
std::string instance(std::string name, std::string input = "input", std::string source = "a") {
  return "\"nodal.instance\"() <{sym_name = \"" + name +
         "\", module = @Child, parameter_bindings = {}, domain_bindings = {src = @" + source +
         ", dst = @b}, metadata = {}}> : () -> ()\n" + binding(name, "src", source) +
         binding(name, "dst", "b") + proxy(name, "in", "input") + proxy(name, "out", "output") +
         assign(name + "_in", input);
}
std::string fixture(bool siblings = false, bool sampled = false, bool different = false) {
  std::string top = setup() + port("other", "a") + instance("left");
  if (siblings)
    top += instance("right", different ? "other" : "input") +
           expression("joined", "left_out", "right_out");
  top += output(siblings ? "joined" : "left_out");
  std::string child = requirement("src") + requirement("dst") + port("in", "src") +
                      port("out", "dst", bit, "output");
  if (sampled)
    child += replace(crossing("sync", "in", "src", "dst"), "Fixture.sync", "Child.sync");
  child += waiver("waived", sampled ? "sync" : "in", sampled ? "dst" : "src", "dst", bit,
                  "kind = \"unknown\"", "Child") +
           assign("out", "waived");
  return "module attributes {nodal.root.module = @Fixture} {\n" + module(top) +
         module(child, "Child") + "}\n";
}
std::string print(Operation *op) {
  std::string result;
  llvm::raw_string_ostream stream(result);
  op->print(stream);
  return result;
}
} // namespace
int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
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
  auto check = [&](StringRef name, const std::string &source, bool expected, StringRef text = "") {
    diagnostics.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    report(name, parsed && succeeded(verify(*parsed)) == expected &&
                     (text.empty() || StringRef(diagnostics).contains(text)));
    return parsed;
  };
  auto parsed = check("parent-requirements-resolve-actual-endpoints", fixture(), true);
  check("reused-definition-id-occurrences-distinct", fixture(true), true);
  check("same-source-waivers-not-independent-samplers", fixture(true, false), true);
  check("sibling-samplers-retain-reconvergence", fixture(true, true), false, "NODAL-CDC-057-002");
  check("sibling-samplers-unrelated-sources", fixture(true, true, true), true);
  check("wrong-actual-source-domain", replace(fixture(), port("input", "a"), port("input", "c")),
        false, "NODAL-CDC-001");
  check("wrong-actual-clock-operand",
        replace(fixture(), "instance_domain_binding\"(%aClock)",
                "instance_domain_binding\"(%cClock)"),
        false);
  check("missing-binding", replace(fixture(), binding("left", "src", "a"), ""), false);
  std::string same = replace(fixture(), "src = @a", "src = @b");
  same = replace(same, binding("left", "src", "a"), binding("left", "src", "b"));
  same = replace(same, port("input", "a"), port("input", "b"));
  check("typed-rationale-checked-after-substitution",
        replace(same, "kind = \"unknown\"", "kind = \"asynchronous\""), false);
  if (parsed) {
    auto hierarchy = nodal::HierarchyModel::create(*parsed);
    bool contextual = succeeded(hierarchy) && (*hierarchy)->occurrences().size() == 2;
    if (contextual) {
      const auto &child = (*hierarchy)->occurrences()[1];
      auto op = *cast<nodal::ModuleOp>(child.module).getOps<nodal::WaiverOp>().begin();
      auto header = nodal::inspectExecutableWaiver(op, **hierarchy, 1);
      auto source = (*hierarchy)->resolveDomain(1, op->getAttr("source_domain"));
      auto destination = (*hierarchy)->resolveDomain(1, op->getAttr("destination_domain"));
      contextual = succeeded(header) && succeeded(source) && succeeded(destination) &&
                   source->occurrence == 0 && destination->occurrence == 0 &&
                   source->declaration != destination->declaration &&
                   child.path != (*hierarchy)->occurrences()[0].path;
    }
    report("header-retains-occurrence-qualified-actual-domains", contextual);
    auto root = *parsed->getOps<nodal::ModuleOp>().begin();
    auto child = *std::next(parsed->getOps<nodal::ModuleOp>().begin());
    auto op = *child.getOps<nodal::WaiverOp>().begin();
    Value original = op->getOperand(0);
    Value foreign;
    for (auto port : root.getOps<nodal::PortValueOp>())
      if (port->getAttrOfType<FlatSymbolRefAttr>("port").getValue() == "input")
        foreign = port.getResult();
    op->setOperand(0, foreign);
    report("foreign-owned-ssa-payload-rejected", failed(nodal::verifyWaiverOperation(op)));
    op->setOperand(0, original);
    std::string before = print(*parsed);
    PassManager pm(&context);
    pm.addPass(nodal::createSequentialToCIRCTPass());
    report("f58-target-still-rejects-transactionally",
           failed(pm.run(*parsed)) && print(*parsed) == before);
  }
  llvm::outs() << "WAIVER_HIERARCHY_CASES " << cases << " FAILURES " << failures << '\n';
  return failures ? 1 : 0;
}
