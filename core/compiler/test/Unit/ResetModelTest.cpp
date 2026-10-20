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
  const std::string metadata = "metadata = {semantic_path = \"Fixture." + name + "\"}";
  return "\"nodal.port\"() <{sym_name = \"" + name + "\", direction = \"input\", type = " + type +
         ", domain = @" + dom + ", " + metadata + "}> : () -> ()\n%" + name +
         " = \"nodal.port_value\"() <{port = @" + name + ", " + metadata + "}> : () -> " + type +
         "\n";
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
std::string shaped(std::string type, std::string dimensions = "2") {
  return "!nodal.shaped<\"" + dimensions + "\", " + type + ">";
}
std::string index(std::string name, std::string input, std::string type, std::string dimensions,
                  std::string positions) {
  std::string operands = "%" + input, types = shaped(type, dimensions);
  for (char position : positions) {
    operands += ", %i" + std::string(1, position);
    types += ", index";
  }
  return "%" + name + " = \"nodal.shape_index\"(" + operands + ") <{metadata = {}}> : (" + types +
         ") -> " + type + "\n";
}
std::string indices() {
  std::string result;
  for (int i = 0; i != 4; ++i)
    result += "%i" + std::to_string(i) + " = \"nodal.constant\"() <{value = " + std::to_string(i) +
              " : index, metadata = {}}> : () -> index\n";
  return result;
}
std::string view(std::string name, std::string input, std::string type, std::string from,
                 std::string to) {
  return "%" + name + " = \"nodal.shape_view\"(%" + input + ") <{dimensions = \"" + to +
         "\", origin = \"Fixture." + input +
         "\", materialization = \"view\", observability = \"source_mapped\", metadata = "
         "{storage = \"structural\"}}> : (" +
         shaped(type, from) + ") -> " + shaped(type, to) + "\n";
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
  const auto projected = domain("core") + port("clocks", shaped(clockType)) +
                         port("resets", shaped(reset)) + indices() +
                         index("clk", "clocks", clockType, "2", "0") +
                         index("rst", "resets", reset, "2", "1");
  check("projected-owned-release", projected + boundary() + sync() + bind(), true);
  check("projected-unattributed-clock-input",
        replace(projected, "type = " + shaped(clockType) + ", domain = @core",
                "type = " + shaped(clockType)) +
            boundary() + sync() + bind(),
        true);
  check("projected-unattributed-clock-aliases",
        domain("other") +
            replace(projected, "type = " + shaped(clockType) + ", domain = @core",
                    "type = " + shaped(clockType)) +
            boundary("raw", "rst", "active_high", "external", "release_domain = @core, ") +
            boundary("otherSafe", "rst", "active_high", "external", "release_domain = @other, ") +
            bind("raw") + bind("otherSafe", "other"),
        true);
  check("projected-raw-async-still-rejected", projected + bind("rst"), false,
        "NODAL-RESET-057-001");
  check("projected-raw-sync",
        replace(projected, "reset_policy = \"async\"", "reset_policy = \"sync\"") + bind("rst"),
        true);
  check("projected-distinct-mixed-root-polarity",
        projected + index("rst2", "resets", reset, "2", "0") + boundary() +
            boundary("raw2", "rst2", "active_low") + sync() +
            sync("safe2", "raw2", "core", 2, "active_low") + combine("%safe, %safe2") +
            bind("joined"),
        true);
  check("projected-same-root-polarity-conflict",
        projected + index("rst2", "resets", reset, "2", "1") + boundary() +
            boundary("raw2", "rst2", "active_low") + sync() + bind(),
        false, "contradictory polarities");
  check("projected-same-root-independent-release-reconverges",
        projected + index("rst2", "resets", reset, "2", "1") + boundary() +
            boundary("raw2", "rst2") + sync() + sync("safe2", "raw2") + combine("%safe, %safe2") +
            bind("joined"),
        false, "reconverge");
  const auto matrix = domain("core") + port("clocks", shaped(clockType, "2,2")) +
                      port("resets", shaped(reset, "2,2")) + indices() +
                      index("clk", "clocks", clockType, "2,2", "10") +
                      index("rst", "resets", reset, "2,2", "11") +
                      view("flatClock", "clocks", clockType, "2,2", "4") +
                      view("flatReset", "resets", reset, "2,2", "4");
  check("matrix-view-identical-physical-clock",
        matrix + index("clockAlias", "flatClock", clockType, "4", "2") + boundary() +
            replace(sync(), "%clk", "%clockAlias") + bind(),
        true);
  check("matrix-view-different-physical-clock-rejected",
        matrix + index("clockAlias", "flatClock", clockType, "4", "3") + boundary() +
            replace(sync(), "%clk", "%clockAlias") + bind(),
        false, "exact destination binding");
  check("matrix-view-identical-reset-root",
        matrix + index("resetAlias", "flatReset", reset, "4", "3") + boundary() +
            boundary("raw2", "resetAlias", "active_low") + sync() + bind(),
        false, "contradictory polarities");
  check("matrix-view-distinct-reset-root",
        matrix + index("resetAlias", "flatReset", reset, "4", "2") + boundary() +
            boundary("raw2", "resetAlias", "active_low") + sync() +
            sync("safe2", "raw2", "core", 2, "active_low") + combine("%safe, %safe2") +
            bind("joined"),
        true);
  const auto column =
      "%s1 = \"nodal.const_literal\"() <{value = 1 : i64, spelling = \"1\", metadata = {}}> : () "
      "-> i64\n"
      "%s2 = \"nodal.const_literal\"() <{value = 2 : i64, spelling = \"2\", metadata = {}}> : () "
      "-> i64\n"
      "%column = \"nodal.shape_slice\"(%resets, %s1, %s2, %s1) <{axis = 1 : i64, "
      "dimensions = \"2,1\", origin = \"Fixture.resets\", observability = \"source_mapped\", "
      "metadata = {storage = \"structural\", access = \"read_only\", input_path = "
      "\"Fixture.resets\", semantic_path = \"Fixture.column\"}}> : (" +
      shaped(reset, "2,2") + ", i64, i64, i64) -> " + shaped(reset, "2,1") + "\n";
  check("matrix-nonleading-slice-preserves-same-reset-element",
        matrix + column + index("resetAlias", "column", reset, "2,1", "10") + boundary() +
            boundary("raw2", "resetAlias", "active_low") + sync() + bind(),
        false, "contradictory polarities");
  check("matrix-nonleading-slice-distinguishes-reset-elements",
        matrix + column + index("resetAlias", "column", reset, "2,1", "00") + boundary() +
            boundary("raw2", "resetAlias", "active_low") + sync() +
            sync("safe2", "raw2", "core", 2, "active_low") + combine("%safe, %safe2") +
            bind("joined"),
        true);
  check("projected-port-value-alias-preserves-root-identity",
        projected +
            "%resetPortsAlias = \"nodal.port_value\"() <{port = @resets, metadata = {}}> "
            ": () -> " +
            shaped(reset) + "\n" + index("resetAlias", "resetPortsAlias", reset, "2", "1") +
            boundary() + boundary("raw2", "resetAlias", "active_low") + sync() + bind(),
        false, "contradictory polarities");
  check("projected-foreign-attributed-clock-rejected",
        domain("foreign", "none") +
            replace(projected, "type = " + shaped(clockType) + ", domain = @core",
                    "type = " + shaped(clockType) + ", domain = @foreign") +
            boundary() + sync() + bind(),
        false, "actual local input port and domain");
  check("projected-parent-attributed-clock",
        domain("parent", "none") +
            replace(
                replace(projected, "sym_name = \"core\"", "sym_name = \"core\", parent = @parent"),
                "type = " + shaped(clockType) + ", domain = @core",
                "type = " + shaped(clockType) + ", domain = @parent") +
            boundary() + sync() + bind(),
        true);
  check("projected-clock-out-of-range",
        replace(projected, "(%clocks, %i0)", "(%clocks, %i2)") + boundary() + sync() + bind(),
        false);
  check("projected-reset-out-of-range",
        replace(projected, "(%resets, %i1)", "(%resets, %i2)") + boundary() + sync() + bind(),
        false);
  check("projected-reset-output-is-not-input",
        replace(projected, "sym_name = \"resets\", direction = \"input\"",
                "sym_name = \"resets\", direction = \"output\"") +
            boundary() + sync() + bind(),
        false);
  check("projected-forged-root-type",
        replace(projected, "port = @clocks", "port = @resets") + boundary() + sync() + bind(),
        false);
  check("projected-dynamic-clock-selector",
        replace(projected, "%i0 = \"nodal.constant\"() <{value = 0 : index, metadata = {}}>",
                "%i0 = \"builtin.unrealized_conversion_cast\"()") +
            boundary() + sync() + bind(),
        false);
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
