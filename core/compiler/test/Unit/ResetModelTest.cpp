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
const std::string reset = "!nodal.capability<\"reset\">",
                  clockType = "!nodal.capability<\"clock\">";
std::string domain(std::string name, std::string policy = "async", std::string edge = "rising",
                   std::string extra = "") {
  return "\"nodal.domain\"() <{sym_name = \"" + name + "\", edge = \"" + edge +
         "\", reset_policy = \"" + policy + "\", " + extra + "metadata = {}}> : () -> ()\n";
}
std::string port(std::string name, std::string type, std::string dom = "core") {
  return "\"nodal.port\"() <{sym_name = \"" + name + "\", direction = \"input\", type = " + type +
         ", domain = @" + dom + ", metadata = {}}> : () -> ()\n%" + name +
         " = \"nodal.port_value\"() <{port = @" + name + ", metadata = {}}> : () -> " + type + "\n";
}
std::string boundary(std::string name = "raw", std::string pin = "rst",
                     std::string pol = "active_high", std::string release = "raw",
                     std::string extra = "") {
  return "%" + name + " = \"nodal.reset_boundary\"(%" + pin + ") <{reset_polarity = \"" + pol +
         "\", release = \"" + release + "\", " + extra + "metadata = {}}> : (" + reset + ") -> " +
         reset + "\n";
}
std::string sync(std::string name = "safe", std::string input = "raw", std::string dest = "core",
                 int stages = 2, std::string pol = "active_high") {
  return "%" + name + " = \"nodal.reset_sync\"(%" + input + ", %clk) <{destination_domain = @" +
         dest + ", stages = " + std::to_string(stages) + " : i64, reset_polarity = \"" + pol +
         "\", metadata = {}}> : (" + reset + ", " + clockType + ") -> " + reset + "\n";
}
std::string bind(std::string input = "safe", std::string dom = "core",
                 std::string pol = "active_high") {
  return "\"nodal.local_domain_binding\"(%clk, %" + input + ") <{domain = @" + dom +
         ", reset_polarity = \"" + pol + "\", metadata = {}}> : (" + clockType + ", " + reset +
         ") -> ()\n";
}
std::string combine(std::string args, int n = 2, std::string pol = "active_high") {
  std::string types = reset;
  for (int i = 1; i < n; ++i)
    types += ", " + reset;
  return "%joined = \"nodal.reset_combine\"(" + args + ") <{reset_polarity = \"" + pol +
         "\", metadata = {}}> : (" + types + ") -> " + reset + "\n";
}
std::string setup(std::string policy = "async", std::string edge = "rising") {
  return domain("core", policy, edge) + port("clk", clockType) + port("rst", reset);
}
std::string replace(std::string s, StringRef a, StringRef b) {
  auto at = s.find(a.str());
  if (at == std::string::npos)
    llvm::report_fatal_error("missing reset mutation");
  return s.replace(at, a.size(), b.str());
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
  auto check = [&](StringRef name, std::string body, bool valid, StringRef message = "") {
    ++count;
    diagnostic.clear();
    auto module = parseSourceString<mlir::ModuleOp>(
        "module {\"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({" + body +
            "}) : () -> ()}",
        ParserConfig(&context, false));
    bool ok = module && succeeded(verify(*module));
    if (ok != valid || (!message.empty() && !StringRef(diagnostic).contains(message))) {
      ++failures;
      llvm::errs() << "FAIL " << name << '\n' << diagnostic;
    }
  };
  const auto good = setup() + boundary() + sync() + bind();
  check("owned-release", good, true);
  check("raw-async-without-state-rejected", setup() + bind("rst"), false, "NODAL-RESET-057-001");
  check("raw-boundary-async-rejected", setup() + boundary() + bind("raw"), false,
        "release lacks proof");
  check("raw-sync-compatibility", setup("sync") + bind("rst"), true);
  check("synchronous-boundary",
        setup("sync") + boundary("raw", "rst", "active_high", "sync", "release_domain = @core, ") +
            bind("raw"),
        true);
  check("sync-is-not-async",
        setup() + boundary("raw", "rst", "active_high", "sync", "release_domain = @core, ") +
            bind("raw"),
        false, "synchronous assertion");
  check("sync-boundary-cannot-retarget",
        setup("sync") + domain("other", "sync", "falling") +
            port("otherClock", clockType, "other") +
            boundary("raw", "rst", "active_high", "sync", "release_domain = @other, ") +
            bind("rst") + replace(bind("raw", "other"), "%clk", "%otherClock"),
        false, "owning Sync clock and edge");
  check("external-release",
        setup() + boundary("raw", "rst", "active_high", "external", "release_domain = @core, ") +
            bind("raw"),
        true);
  check("external-release-needs-domain",
        setup() + boundary("raw", "rst", "active_high", "external") + bind("raw"), false);
  check("raw-may-not-forge-domain",
        setup() + boundary("raw", "rst", "active_high", "raw", "release_domain = @core, ") +
            bind("raw"),
        false, "raw reset");
  check("low-stage", setup() + boundary() + sync("safe", "raw", "core", 1) + bind(), false,
        "2..32");
  check("high-stage", setup() + boundary() + sync("safe", "raw", "core", 33) + bind(), false,
        "2..32");
  check("sync-preserves-polarity",
        setup() + boundary() + sync("safe", "raw", "core", 2, "active_low") +
            bind("safe", "core", "active_low"),
        false, "preserve input polarity");
  check("generated-unattributed-clock",
        domain("root", "none") + domain("core", "async", "rising", "parent = @root, ") +
            replace(port("clk", clockType), "domain = @core, ", "") + port("rst", reset) +
            boundary() + sync() + bind(),
        true);
  check("aliases-share-unattributed-clock",
        setup() + domain("other", "async") +
            replace(port("shared", clockType), "domain = @core, ", "") +
            boundary("raw", "rst", "active_high", "external", "release_domain = @core, ") +
            replace(bind("raw"), "%clk", "%shared") +
            boundary("otherSafe", "rst", "active_high", "external", "release_domain = @other, ") +
            replace(bind("otherSafe", "other"), "%clk", "%shared"),
        true);
  check("serial-cascade",
        setup() + boundary() + sync() + sync("cascade", "safe", "core", 3) + bind("cascade"), true);
  for (auto edge : {"rising", "falling"})
    for (auto pol : {"active_high", "active_low"})
      check(std::string(edge) + pol,
            setup("async_assert_sync_release", edge) + boundary("raw", "rst", pol) +
                sync("safe", "raw", "core", 2, pol) + bind("safe", "core", pol),
            true);
  check("root-polarity-conflict",
        setup() + boundary() + boundary("other", "rst", "active_low") + sync() + bind(), false,
        "contradictory polarities");
  check("independent-release-reconvergence",
        setup() + boundary() + sync() + sync("other") + combine("%safe, %other") + bind("joined"),
        false, "reconverge");
  check("same-release-duplicate-safe",
        setup() + boundary() + sync() + combine("%safe, %safe") + bind("joined"), true);
  check("mixed-independent-root-polarity",
        setup() + port("rst2", reset) + boundary() + boundary("raw2", "rst2", "active_low") +
            sync() + sync("safe2", "raw2", "core", 2, "active_low") + combine("%safe, %safe2") +
            bind("joined"),
        true);
  check("empty-composition",
        setup() +
            "%joined = \"nodal.reset_combine\"() <{reset_polarity = \"active_high\", metadata = "
            "{}}> : () -> " +
            reset + "\n" + bind("joined"),
        false, "1..32");
  check("binding-polarity-forgery",
        setup() + boundary() + sync() + bind("safe", "core", "active_low"), false, "polarity");
  check("producer-attribute-forgery",
        replace(
            good, "stages = 2 : i64, reset_polarity = \"active_high\", metadata = {}}>",
            "stages = 2 : i64, reset_polarity = \"active_high\", metadata = {}}> {unsafe = true}"),
        false, "attributes");
  check("parent-self-cycle",
        replace(good, "sym_name = \"core\"", "sym_name = \"core\", parent = @core"), false,
        "parent cycle");
  check("parent-multinode-cycle",
        domain("a", "none", "rising", "parent = @core, ") +
            replace(good, "sym_name = \"core\"", "sym_name = \"core\", parent = @a"),
        false, "parent cycle");
  check("parent-edge-mismatch",
        domain("a", "none", "falling") +
            replace(good, "sym_name = \"core\"", "sym_name = \"core\", parent = @a"),
        false, "inherited edge");
  check("release-edge-mismatch",
        domain("other", "none", "falling") + port("clk2", clockType, "other") + setup() +
            boundary("raw", "rst", "active_high", "external", "release_domain = @other, ") +
            "\"nodal.local_domain_binding\"(%clk2) <{domain = @other, reset_polarity = "
            "\"active_high\", metadata = {}}> : (" +
            clockType + ") -> ()\n" + bind("raw"),
        false, "exact destination clock and edge");
  llvm::outs() << count << " reset cases, " << failures << " failures\n";
  return failures ? 1 : 0;
}
