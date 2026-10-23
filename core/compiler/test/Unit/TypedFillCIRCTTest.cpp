#include "circt/Dialect/HW/HWOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Pass/PassManager.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Transforms/Passes.h"

#include "llvm/Support/raw_ostream.h"

#include <sstream>
using namespace mlir;
namespace {
std::string fixture() {
  return R"mlir(module attributes {nodal.root.module = @Top, nodal.root.parameter_bindings = {}} {
"nodal.module"() <{sym_name = "Top", metadata = {}}> ({
 "nodal.parameter"() <{sym_name = "W", type = i64, default_value = 1 : i64, variability = "symbolic", classification = "structural", metadata = {}}> : () -> ()
 %min = "nodal.const_literal"() <{spelling = "1", value = 1 : i64, metadata = {}}> : () -> i64
 %max = "nodal.const_literal"() <{spelling = "8", value = 8 : i64, metadata = {}}> : () -> i64
 "nodal.parameter_constraint"(%min, %max) <{parameter = @W, constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, metadata = {}}> : (i64, i64) -> ()
 "nodal.parameter_envelope"() <{parameter = @W, effects = ["width"], policy = "static_generate", metadata = {}}> : () -> ()
 "nodal.domain"() <{sym_name = "core", edge = "rising", reset_policy = "none", metadata = {}}> : () -> ()
 "nodal.port"() <{sym_name = "clock", direction = "input", domain = @core, type = !nodal.capability<"clock">, metadata = {}}> : () -> ()
 "nodal.port"() <{sym_name = "input", direction = "input", domain = @core, type = !nodal.shaped<"3", !nodal.digital<"sint", "W">>, metadata = {}}> : () -> ()
 "nodal.interface"() <{sym_name = "Bus", metadata = {interface_abi_version = 1 : i64, semantic_identity = "Bus"}}> ({
 "nodal.interface_role"() <{sym_name = "producer", kind = "explicit", metadata = {interface_abi_version = 1 : i64, access = [{path = "data", access = "out", leaf_role = "producer", nested_roles = [], nested_interfaces = []}]}}> : () -> ()
 "nodal.interface_member"() <{sym_name = "data", type = !nodal.digital<"sint", "W">, protocol = "plain", roles = ["producer"], metadata = {interface_abi_version = 1 : i64, analog = {}}}> : () -> ()
}) : () -> ()
 "nodal.port"() <{sym_name = "first", direction = "output", domain = @core, type = !nodal.digital<"sint", "W">, metadata = {}}> : () -> ()
 "nodal.port"() <{sym_name = "second", direction = "output", domain = @core, type = !nodal.digital<"sint", "W">, metadata = {}}> : () -> ()
 "nodal.port"() <{sym_name = "alias", direction = "output", domain = @core, type = !nodal.digital<"sint", "W">, metadata = {}}> : () -> ()
 %clock = "nodal.port_value"() <{port = @clock, metadata = {}}> : () -> !nodal.capability<"clock">
 %input = "nodal.port_value"() <{port = @input, metadata = {}}> : () -> !nodal.shaped<"3", !nodal.digital<"sint", "W">>
 %zero = "nodal.const_literal"() <{spelling = "0", value = 0 : i64, metadata = {}}> : () -> i64
 %one = "nodal.const_literal"() <{spelling = "1", value = 1 : i64, metadata = {}}> : () -> i64
 %three = "nodal.const_literal"() <{spelling = "3", value = 3 : i64, metadata = {}}> : () -> i64
 %family = "nodal.generate"(%zero, %three, %one) <{region_id = "Top.family", induction = "i", induction_path = "Top.i", metadata = {maximum_trip_count = 3 : i64}}> ({
 ^bb0(%i: i64):
 %lane = "nodal.shape_index"(%input, %i) <{metadata = {}}> : (!nodal.shaped<"3", !nodal.digital<"sint", "W">>, i64) -> !nodal.digital<"sint", "W">
 "nodal.interface_instance"() <{sym_name = "endpoint", definition = @Bus, role = "producer", metadata = {interface_abi_version = 1 : i64, dimensions = [], domain = @core, semantic_path = "Fixture.endpoint"}}> : () -> ()
 "nodal.interface_abi"() <{logical_path = "Fixture.endpoint", layout_policy = "structural", members = [{path = "data", type = !nodal.digital<"sint", "W">, role = "producer", access = "out", nested_roles = [], nested_interfaces = []}], metadata = {interface_abi_version = 1 : i64, instance = @endpoint}}> : () -> ()
 %output = "nodal.member_access"() <{instance = @endpoint, path = "data", metadata = {interface_abi_version = 1 : i64, role = "producer"}}> : () -> !nodal.digital<"sint", "W">
 %fill = "nodal.const_literal"() <{spelling = "-1", value = -1 : i64, metadata = {}}> : () -> !nodal.digital<"sint", "W">
 "nodal.interface_drive"(%output, %fill) <{metadata = {interface_abi_version = 1 : i64}}> : (!nodal.digital<"sint", "W">, !nodal.digital<"sint", "W">) -> ()
 "nodal.generated_export"() <{target = @endpoint, kind = "interface", member = "endpoint", access = "read", metadata = {}}> : () -> ()
 }) : (i64, i64, i64) -> !nodal.generated_family
 "nodal.interface_boundary"(%family, %zero) <{sym_name = "selected0", endpoint = "endpoint", member = "endpoint", metadata = {interface_abi_version = 1 : i64, dimensions = [], semantic_path = "Fixture.selected0", actual_types = [{path = "data", type = !nodal.digital<"sint", "W">}]}}> : (!nodal.generated_family, i64) -> ()
 "nodal.interface_boundary"(%family, %one) <{sym_name = "selected1", endpoint = "endpoint", member = "endpoint", metadata = {interface_abi_version = 1 : i64, dimensions = [], semantic_path = "Fixture.selected1", actual_types = [{path = "data", type = !nodal.digital<"sint", "W">}]}}> : (!nodal.generated_family, i64) -> ()
 %firstValue = "nodal.port_value"() <{port = @first, metadata = {}}> : () -> !nodal.digital<"sint", "W">
 %secondValue = "nodal.port_value"() <{port = @second, metadata = {}}> : () -> !nodal.digital<"sint", "W">
 %aliasValue = "nodal.port_value"() <{port = @alias, metadata = {}}> : () -> !nodal.digital<"sint", "W">
 %lane0 = "nodal.member_access"() <{instance = @selected0, path = "data", metadata = {interface_abi_version = 1 : i64, role = "producer"}}> : () -> !nodal.digital<"sint", "W">
 %lane1 = "nodal.member_access"() <{instance = @selected1, path = "data", metadata = {interface_abi_version = 1 : i64, role = "producer"}}> : () -> !nodal.digital<"sint", "W">
 %lane0Again = "nodal.member_access"() <{instance = @selected0, path = "data", metadata = {interface_abi_version = 1 : i64, role = "producer"}}> : () -> !nodal.digital<"sint", "W">
 "nodal.digital_assign"(%firstValue, %lane0) <{kind = "continuous", metadata = {}}> : (!nodal.digital<"sint", "W">, !nodal.digital<"sint", "W">) -> ()
 "nodal.digital_assign"(%secondValue, %lane1) <{kind = "continuous", metadata = {}}> : (!nodal.digital<"sint", "W">, !nodal.digital<"sint", "W">) -> ()
 "nodal.digital_assign"(%aliasValue, %lane0Again) <{kind = "continuous", metadata = {}}> : (!nodal.digital<"sint", "W">, !nodal.digital<"sint", "W">) -> ()
 "nodal.local_domain_binding"(%clock) <{domain = @core, reset_polarity = "active_high", metadata = {}}> : (!nodal.capability<"clock">) -> ()
}) : () -> ()
}
)mlir";
}
std::string replace(std::string input, StringRef before, StringRef after) {
  auto at = input.find(before.str());
  if (at == std::string::npos)
    llvm::report_fatal_error("missing direct Interface mutation");
  return input.replace(at, before.size(), after.str());
}
std::string printed(Operation *op) {
  std::string s;
  llvm::raw_string_ostream out(s);
  op->print(out);
  return s;
}
} // namespace
int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  std::string diagnostics;
  ScopedDiagnosticHandler handler(&context, [&](Diagnostic &d) {
    llvm::raw_string_ostream s(diagnostics);
    d.print(s);
    s << '\n';
    return success();
  });
  unsigned cases = 0, failures = 0;
  auto report = [&](StringRef name, bool ok) {
    ++cases;
    if (!ok) {
      ++failures;
      llvm::errs() << "TYPED_FILL_FAIL " << name << '\n' << diagnostics;
    }
  };
  auto lower = [&](mlir::ModuleOp m) {
    PassManager pm(&context);
    auto p = nodal::createSequentialToCIRCTPass();
    if (failed(p->initializeOptions("preserve-constant-loops=true boundary-abi=named-fields",
                                    [&](const Twine &) { return failure(); })))
      return failure();
    pm.addPass(std::move(p));
    return pm.run(m);
  };
  auto positive = [&](StringRef name, const std::string &input, unsigned outputs = 3,
                      unsigned selections = 3) {
    diagnostics.clear();
    auto m = parseSourceString<mlir::ModuleOp>(input, ParserConfig(&context, false));
    bool ok = m && succeeded(verify(*m)) && succeeded(lower(*m));
    unsigned definitions = 0, helpers = 0, loops = 0, instances = 0, reads = 0;
    if (ok) {
      m->walk([&](Operation *op) {
        definitions += isa<circt::hw::HWModuleOp>(op);
        helpers += op->hasAttr("nodal.generated_template");
        loops += op->getName().getStringRef() == "sv.generate.for";
        instances += isa<circt::hw::InstanceOp>(op);
        reads += op->hasAttr("nodal.generated_selection");
        if (isa<circt::hw::HWModuleOp>(op) && op->getAttrOfType<StringAttr>("sym_name") == "Top")
          ok &= op->getRegion(0).front().getTerminator()->getNumOperands() == outputs;
      });
      ok &= definitions == 2 && helpers == 1 && loops == 1 && instances == 1 &&
            reads == selections && succeeded(verify(*m));
    }
    report(name, ok);
  };
  auto negative = [&](StringRef name, const std::string &input, StringRef marker) {
    diagnostics.clear();
    auto m = parseSourceString<mlir::ModuleOp>(input, ParserConfig(&context, false));
    if (!m) {
      report(name, false);
      return;
    }
    auto before = printed(m->getOperation());
    report(name, failed(lower(*m)) && StringRef(diagnostics).contains(marker) &&
                     before == printed(m->getOperation()));
  };
  auto input = fixture();
  positive("signed-minus-one-exact-width-including-one-bit", input);
  auto zero = replace(input, "spelling = \"-1\", value = -1", "spelling = \"0\", value = 0");
  positive("signed-zero-exact-original-width", zero);
  auto unsignedOne = input;
  for (auto at = unsignedOne.find("\"sint\""); at != std::string::npos;
       at = unsignedOne.find("\"sint\""))
    unsignedOne.replace(at, 6, "\"uint\"");
  unsignedOne =
      replace(unsignedOne, "spelling = \"-1\", value = -1", "spelling = \"1\", value = 1");
  negative("positive-one-is-not-an-all-ones-fill", unsignedOne,
           "generated symbolic scalar requires");
  negative("literal-spelling-must-match-value",
           replace(input, "spelling = \"-1\", value = -1", "spelling = \"0\", value = -1"),
           "exact fitting integer");
  negative("signed-out-of-range-unselected-width-is-not-ignored",
           replace(input, "spelling = \"-1\", value = -1", "spelling = \"-2\", value = -2"),
           "exact fitting integer");
  auto empty = replace(zero, "\"nodal.generate\"(%zero, %three, %one)",
                       "\"nodal.generate\"(%zero, %zero, %one)");
  empty = replace(empty, "maximum_trip_count = 3", "maximum_trip_count = 0");
  std::istringstream lines(empty);
  std::string line;
  empty.clear();
  while (std::getline(lines, line)) {
    if (StringRef(line).contains("direction = \"output\"") ||
        StringRef(line).contains("nodal.interface_boundary") ||
        StringRef(line).contains("Value = \"nodal.port_value") ||
        StringRef(line).contains("instance = @selected") ||
        StringRef(line).contains("nodal.digital_assign"))
      continue;
    empty += line + "\n";
  }
  positive("empty-family-retains-authored-zero-at-original-width", empty, 0, 0);
  negative("empty-family-does-not-excuse-malformed-literal",
           replace(empty,
                   "spelling = \"0\", value = 0 : i64, metadata = {}}> : () -> !nodal.digital",
                   "spelling = \"wrong\", value = 0 : i64, metadata = {}}> : () -> !nodal.digital"),
           "exact fitting integer");
  llvm::outs() << "TYPED_FILL_SUMMARY cases=" << cases << " failures=" << failures << '\n';
  return failures ? 1 : 0;
}
