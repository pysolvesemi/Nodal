#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/SymbolTable.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/ClockModel.h"
#include "nodal/Dialect/Nodal/DomainModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ResetModel.h"

#include "llvm/Support/raw_ostream.h"
using namespace mlir;
namespace {
const std::string clockType = "!nodal.capability<\"clock\">";
const std::string resetType = "!nodal.capability<\"reset\">";
std::string module(StringRef name, const std::string &body) {
  return "\"nodal.module\"() <{sym_name = \"" + name.str() + "\", metadata = {}}> ({\n" + body +
         "}) : () -> ()\n";
}
std::string domain(StringRef name, StringRef policy) {
  return "\"nodal.domain\"() <{sym_name = \"" + name.str() +
         "\", edge = \"rising\", reset_policy = \"" + policy.str() +
         "\", metadata = {}}> : () -> ()\n";
}
std::string port(StringRef name, const std::string &type, StringRef owner, StringRef dom) {
  return "\"nodal.port\"() <{sym_name = \"" + name.str() +
         "\", direction = \"input\", type = " + type + ", domain = @" + dom.str() +
         ", metadata = {semantic_path = \"" + owner.str() + "." + name.str() +
         "\"}}> : () -> ()\n%" + name.str() + " = \"nodal.port_value\"() <{port = @" + name.str() +
         ", metadata = {}}> : () -> " + type + "\n";
}
std::string requirement(StringRef name) {
  return "\"nodal.domain_requirement\"() <{sym_name = \"" + name.str() +
         "\", metadata = {}}> : () -> ()\n";
}
std::string formal(StringRef name, StringRef kind, StringRef req = "req") {
  return "%" + name.str() + " = \"nodal.requirement_" + kind.str() + "\"() <{requirement = @" +
         req.str() + ", metadata = {}}> : () -> !nodal.capability<\"" + kind.str() + "\">\n";
}
std::string sync(StringRef name, StringRef input, StringRef clock, StringRef dest,
                 StringRef polarity = "active_high") {
  return "%" + name.str() + " = \"nodal.reset_sync\"(%" + input.str() + ", %" + clock.str() +
         ") <{destination_domain = @" + dest.str() + ", stages = 2 : i64, reset_polarity = \"" +
         polarity.str() + "\", metadata = {}}> : (" + resetType + ", " + clockType + ") -> " +
         resetType + "\n";
}
std::string instance(StringRef name, StringRef child, StringRef bindings) {
  return "\"nodal.instance\"() <{sym_name = \"" + name.str() + "\", module = @" + child.str() +
         ", parameter_bindings = {}, domain_bindings = {" + bindings.str() +
         "}, metadata = {}}> : () -> ()\n";
}
std::string bindInstance(StringRef instance, StringRef req, StringRef actual, StringRef clock,
                         StringRef reset = "") {
  return "\"nodal.instance_domain_binding\"(%" + clock.str() +
         (reset.empty() ? "" : ", %" + reset.str()) + ") <{instance = @" + instance.str() +
         ", requirement = @" + req.str() + ", actual = @" + actual.str() +
         ", origin = \"named\", metadata = {}}> : (" + clockType +
         (reset.empty() ? "" : ", " + resetType) + ") -> ()\n";
}
std::string local(StringRef name, StringRef policy) {
  const std::string n = name.str();
  std::string body = domain(name, policy) + port("c" + n, clockType, "Top", name);
  if (policy != "none") {
    body += port("r" + n, resetType, "Top", name);
    if (policy != "sync")
      body += "%raw" + n + " = \"nodal.reset_boundary\"(%r" + n +
              ") <{reset_polarity = \"active_high\", release = \"raw\", metadata = {}}> : (" +
              resetType + ") -> " + resetType + "\n" + sync("safe" + n, "raw" + n, "c" + n, name);
  }
  const std::string reset = policy == "none" ? "" : (policy == "sync" ? "r" : "safe") + n;
  return body + "\"nodal.local_domain_binding\"(%c" + n + (reset.empty() ? "" : ", %" + reset) +
         ") <{domain = @" + n + ", reset_polarity = \"active_high\", metadata = {}}> : (" +
         clockType + (reset.empty() ? "" : ", " + resetType) + ") -> ()\n";
}
std::string childLocalClock() {
  return domain("local", "none") + port("localClock", clockType, "Child", "local") +
         "\"nodal.local_domain_binding\"(%localClock) <{domain = @local, reset_polarity = "
         "\"active_high\", metadata = {}}> : (" +
         clockType + ") -> ()\n" +
         "\"nodal.domain\"() <{sym_name = \"gated\", edge = \"rising\", reset_policy = "
         "\"none\", parent = @local, metadata = {}}> : () -> ()\n"
         "%zero = \"nodal.const_literal\"() <{value = 0 : ui1, spelling = \"0\", metadata = {}}> "
         ": () -> !nodal.bits<1>\n"
         "%gate = \"nodal.clock_gate\"(%localClock, %zero, %zero) <{parent_domains = [@local], "
         "domain = @gated, edge = \"rising\", contract = \"gate_v1\", control_domains = "
         "[\"constant\", \"constant\"], obligations = {control_timing = "
         "\"inactive_phase_aperture\", "
         "startup = \"known_after_inactive_phase\"}, metadata = {semantic_path = \"Child.gate\"}}> "
         ": (" +
         clockType + ", !nodal.bits<1>, !nodal.bits<1>) -> " + clockType + "\n" +
         "\"nodal.local_domain_binding\"(%gate) <{domain = @gated, reset_polarity = "
         "\"active_high\", "
         "metadata = {}}> : (" +
         clockType + ") -> ()\n";
}
std::string fixture(StringRef policy = "async", StringRef receivingClock = "requiredClock",
                    StringRef outputPolarity = "active_high") {
  const bool reset = policy != "none";
  const std::string a = !reset ? "" : policy == "sync" ? "ra" : "safea";
  const std::string b = !reset ? "" : policy == "sync" ? "rb" : "safeb";
  std::string top = local("a", policy) + local("b", policy);
  for (auto name : {"left", "right"})
    top += instance(name, "Child", "req = @a, other = @b") +
           bindInstance(name, "req", "a", "ca", a) + bindInstance(name, "other", "b", "cb", b);
  const std::string child =
      childLocalClock() + requirement("req") + requirement("other") +
      formal("requiredClock", "clock") + formal("otherClock", "clock", "other") +
      formal("requiredReset", "reset") +
      sync("childRelease", "requiredReset", receivingClock, "req", outputPolarity) +
      instance("leaf", "Leaf", "req = @req") +
      bindInstance("leaf", "req", "req", "requiredClock", reset ? "requiredReset" : "");
  const std::string leaf = requirement("req") + formal("leafClock", "clock") +
                           formal("leafReset", "reset") +
                           sync("leafRelease", "leafReset", "leafClock", "req");
  const std::string source = "module attributes {nodal.root.module = @Top} {\n" +
                             module("Top", top) + module("Child", child) + module("Leaf", leaf) +
                             "}\n";
  std::string located;
  unsigned lineNumber = 1;
  for (size_t start = 0, end; start < source.size(); start = end + 1, ++lineNumber) {
    end = source.find('\n', start);
    std::string line = source.substr(start, end - start);
    if (line.find(" -> ") != std::string::npos)
      line += " loc(\"HierarchyControl.ndl\":" + std::to_string(lineNumber) + ":1)";
    located += line + "\n";
  }
  return located;
}
std::string replace(std::string source, StringRef from, StringRef to) {
  auto at = source.find(from.str());
  if (at == std::string::npos)
    llvm::report_fatal_error("missing hierarchy control mutation");
  return source.replace(at, from.size(), to.str());
}
Operation *operation(Operation *module, StringRef name, unsigned ordinal = 0) {
  for (Operation &op : module->getRegion(0).front())
    if (op.getName().getStringRef() == name && ordinal-- == 0)
      return &op;
  return nullptr;
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
    d.getLocation().print(out);
    out << ": ";
    d.print(out);
    out << '\n';
    return success();
  });
  unsigned count = 0, failures = 0;
  auto check = [&](StringRef name, bool condition) {
    ++count;
    if (!condition) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostic;
    }
    diagnostic.clear();
  };
  auto parse = [&](const std::string &source) {
    return parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
  };
  auto verifyCase = [&](StringRef name, const std::string &source, bool expected,
                        StringRef message = "") {
    auto parsed = parse(source);
    bool accepted = parsed && succeeded(verify(*parsed));
    check(name, parsed && accepted == expected &&
                    (message.empty() || StringRef(diagnostic).contains(message)) &&
                    (expected || StringRef(diagnostic).contains("HierarchyControl.ndl")));
  };
  verifyCase("two-boundary-formal-reset-forwarding", fixture(), true);
  verifyCase("raw-sync-reset-forwarding", fixture("sync"), true);
  verifyCase("required-reset-cannot-be-absent", fixture("none"), false,
             "required capability is absent");
  verifyCase("wrong-contextual-receiving-clock", fixture("async", "otherClock"), false,
             "exact destination binding");
  auto wrongClock = parse(fixture("async", "otherClock"));
  auto *wrongChild = wrongClock ? SymbolTable::lookupSymbolIn(*wrongClock, "Child") : nullptr;
  check("formal-clock-equality-remains-a-contextual-obligation",
        wrongChild &&
            succeeded(nodal::verifyResetProducer(operation(wrongChild, "nodal.reset_sync"))));
  std::string sharedClock = fixture("async", "otherClock");
  for (unsigned i = 0; i != 2; ++i) {
    sharedClock = replace(sharedClock, "req = @a, other = @b", "req = @a, other = @a");
    sharedClock = replace(sharedClock, "(%cb, %safeb) <{instance", "(%ca, %safea) <{instance");
    sharedClock = replace(sharedClock, "requirement = @other, actual = @b",
                          "requirement = @other, actual = @a");
  }
  verifyCase("distinct-formals-may-resolve-one-actual-clock", sharedClock, true);
  verifyCase("actual-input-polarity-is-not-invented",
             fixture("async", "requiredClock", "active_low"), false, "preserve input polarity");
  verifyCase("wrong-actual-clock-operand",
             replace(fixture(), "\"nodal.instance_domain_binding\"(%ca, %safea)",
                     "\"nodal.instance_domain_binding\"(%cb, %safea)"),
             false, "actual instance Clock operand disagrees");
  verifyCase("wrong-actual-reset-operand",
             replace(fixture(), "\"nodal.instance_domain_binding\"(%ca, %safea)",
                     "\"nodal.instance_domain_binding\"(%ca, %safeb)"),
             false, "actual instance Reset operand disagrees");
  verifyCase(
      "wrong-root-release-clock",
      replace(fixture(), "destination_domain = @a, stages", "destination_domain = @b, stages"),
      false, "exact destination binding");
  verifyCase("nonexistent-closed-root",
             replace(fixture(), "nodal.root.module = @Top", "nodal.root.module = @Missing"), false,
             "nodal.root.module selector");
  verifyCase("wrong-instance-binding",
             replace(fixture(), "instance = @left, requirement = @req",
                     "instance = @missing, requirement = @req"),
             false, "immediate child's exact requirement");
  std::string missing = fixture();
  auto bindingAt = missing.find("instance = @left, requirement = @req");
  auto bindingStart = missing.rfind('\n', bindingAt) + 1;
  auto bindingEnd = missing.find('\n', bindingAt) + 1;
  std::string duplicate = missing;
  duplicate.insert(bindingEnd, missing.substr(bindingStart, bindingEnd - bindingStart));
  missing.erase(bindingStart, bindingEnd - bindingStart);
  verifyCase("missing-instance-binding", missing, false, "reachable requirement lacks");
  verifyCase("duplicate-instance-binding", duplicate, false, "one authoritative binding");
  verifyCase("ordinary-unresolved-domain-cannot-defer",
             replace(fixture(), "destination_domain = @req, stages",
                     "destination_domain = @missing, stages"),
             false, "exact destination binding");
  auto parsed = parse(fixture());
  if (!parsed) {
    check("parse-control-fixture", false);
    return 1;
  }
  auto hierarchy = nodal::HierarchyModel::create(parsed->getOperation());
  if (failed(hierarchy)) {
    check("construct-control-occurrences", false);
    return 1;
  }
  unsigned left = 0, right = 0, leaf = 0;
  auto occurrences = (*hierarchy)->occurrences();
  for (unsigned i = 0; i != occurrences.size(); ++i) {
    auto instance = occurrences[i].instance;
    auto name = instance ? instance->getAttrOfType<StringAttr>("sym_name") : StringAttr();
    if (name && name.getValue() == "left")
      left = i;
    if (name && name.getValue() == "right")
      right = i;
  }
  for (unsigned i = 0; i != occurrences.size(); ++i)
    if (occurrences[i].parent == left && occurrences[i].instance)
      leaf = i;
  const bool distinct = left != 0 && right != 0 && left != right && leaf != 0;
  check("distinct-sibling-occurrences", distinct);
  if (!distinct)
    return 1;
  Operation *child = occurrences[left].module;
  Operation *release = operation(child, "nodal.reset_sync");
  Operation *clock = operation(child, "nodal.requirement_clock");
  auto leftReset = nodal::inspectReset({left, release->getResult(0), release}, **hierarchy);
  auto rightReset = nodal::inspectReset({right, release->getResult(0), release}, **hierarchy);
  check("sibling-reset-chains-are-not-one-release",
        succeeded(leftReset) && succeeded(rightReset) && leftReset->roots.size() == 1 &&
            rightReset->roots.size() == 1 &&
            leftReset->roots[0].physicalOccurrence == rightReset->roots[0].physicalOccurrence &&
            leftReset->roots[0].physical == rightReset->roots[0].physical &&
            leftReset->roots[0].release == rightReset->roots[0].release &&
            leftReset->roots[0].releaseOccurrence != rightReset->roots[0].releaseOccurrence);
  check("formal-clocks-retain-one-actual-root",
        nodal::sameClock({left, clock->getResult(0), clock}, {right, clock->getResult(0), clock},
                         **hierarchy));
  Operation *leafReset = operation(occurrences[leaf].module, "nodal.requirement_reset");
  auto forwarded = nodal::inspectReset({leaf, leafReset->getResult(0), leafReset}, **hierarchy);
  check("two-boundary-reset-retains-root-release-and-clock",
        succeeded(forwarded) && forwarded->roots.size() == 1 &&
            forwarded->roots[0].physicalOccurrence == 0 &&
            forwarded->roots[0].releaseOccurrence == 0 &&
            forwarded->roots[0].clockOccurrence == 0 &&
            forwarded->roots[0].edge.getValue() == "rising" &&
            forwarded->polarity.getValue() == "active_high");
  auto actual = (*hierarchy)->resolveBinding(leaf, FlatSymbolRefAttr::get(&context, "req"));
  check("forwarded-binding-retains-original-record",
        succeeded(actual) && actual->record->getParentOp() == occurrences[0].module &&
            succeeded(nodal::verifyContextualResetForBinding(*actual, **hierarchy)));
  Operation *rootClock = operation(occurrences[0].module, "nodal.port_value");
  check("foreign-contextual-clock-projection-rejected",
        failed(nodal::resolveClock({left, rootClock->getResult(0), clock}, **hierarchy)));
  Value input = release->getOperand(0);
  release->setOperand(0, leafReset->getResult(0));
  check("foreign-reset-projection-rejected", failed(nodal::verifyResetProducer(release)));
  release->setOperand(0, input);
  // Without an occurrence, structural verification accepts only the exact
  // formal shape. The public proof query must never return an empty safe root.
  check("local-formal-reset-shape-is-valid", succeeded(nodal::verifyResetProducer(release)));
  check("flat-query-does-not-return-unresolved-proof",
        failed(nodal::inspectReset(release->getResult(0), release, nodal::DomainModel(child))));
  Operation *localClock = operation(child, "nodal.port_value");
  Operation *gate = operation(child, "nodal.clock_gate");
  auto leftPhysical = nodal::resolveClock({left, localClock->getResult(0), gate}, **hierarchy);
  auto rightPhysical = nodal::resolveClock({right, localClock->getResult(0), gate}, **hierarchy);
  check("sibling-physical-clocks-retain-occurrence-identity",
        succeeded(leftPhysical) && succeeded(rightPhysical) &&
            leftPhysical->physical == rightPhysical->physical &&
            !(*leftPhysical == *rightPhysical));
  check("same-occurrence-physical-clock-remains-identical",
        nodal::sameClock({left, localClock->getResult(0), gate},
                         {left, localClock->getResult(0), gate}, **hierarchy));
  auto leftGate = nodal::resolveClock({left, gate->getResult(0), gate}, **hierarchy);
  auto rightGate = nodal::resolveClock({right, gate->getResult(0), gate}, **hierarchy);
  check("sibling-owned-clock-producers-retain-occurrence-identity",
        succeeded(leftGate) && succeeded(rightGate) && leftGate->producer == rightGate->producer &&
            !(*leftGate == *rightGate));
  llvm::outs() << count << " hierarchy control cases, " << failures << " failures\n";
  return failures ? 1 : 0;
}
