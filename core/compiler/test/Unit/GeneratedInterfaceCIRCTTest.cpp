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
  return R"mlir(
module attributes {nodal.root.module = @Top, nodal.root.parameter_bindings = {}} {
"nodal.module"() <{sym_name = "Top", metadata = {}}> ({
 "nodal.domain"() <{sym_name = "core", edge = "rising", reset_policy = "none", metadata = {}}> : () -> ()
 "nodal.port"() <{sym_name = "clock", direction = "input", domain = @core, type = !nodal.capability<"clock">, metadata = {}}> : () -> ()
 "nodal.port"() <{sym_name = "input", direction = "input", domain = @core, type = !nodal.shaped<"3", !nodal.uint<8>>, metadata = {}}> : () -> ()
 "nodal.interface"() <{sym_name = "Bus", metadata = {interface_abi_version = 1 : i64, semantic_identity = "Bus"}}> ({
 "nodal.interface_role"() <{sym_name = "producer", kind = "explicit", metadata = {interface_abi_version = 1 : i64, access = [{path = "data", access = "out", leaf_role = "producer", nested_roles = [], nested_interfaces = []}]}}> : () -> ()
 "nodal.interface_member"() <{sym_name = "data", type = !nodal.uint<8>, protocol = "plain", roles = ["producer"], metadata = {interface_abi_version = 1 : i64, analog = {}}}> : () -> ()
}) : () -> ()
 "nodal.interface_instance"() <{sym_name = "first", definition = @Bus, role = "producer", metadata = {interface_abi_version = 1 : i64, dimensions = [], domain = @core, semantic_path = "Fixture.first"}}> : () -> ()
"nodal.interface_abi"() <{logical_path = "Fixture.first", layout_policy = "structural", members = [{path = "data", type = !nodal.uint<8>, role = "producer", access = "out", nested_roles = [], nested_interfaces = []}], metadata = {interface_abi_version = 1 : i64, instance = @first}}> : () -> ()
 "nodal.interface_instance"() <{sym_name = "second", definition = @Bus, role = "producer", metadata = {interface_abi_version = 1 : i64, dimensions = [], domain = @core, semantic_path = "Fixture.second"}}> : () -> ()
"nodal.interface_abi"() <{logical_path = "Fixture.second", layout_policy = "structural", members = [{path = "data", type = !nodal.uint<8>, role = "producer", access = "out", nested_roles = [], nested_interfaces = []}], metadata = {interface_abi_version = 1 : i64, instance = @second}}> : () -> ()
 "nodal.interface_instance"() <{sym_name = "alias", definition = @Bus, role = "producer", metadata = {interface_abi_version = 1 : i64, dimensions = [], domain = @core, semantic_path = "Fixture.alias"}}> : () -> ()
"nodal.interface_abi"() <{logical_path = "Fixture.alias", layout_policy = "structural", members = [{path = "data", type = !nodal.uint<8>, role = "producer", access = "out", nested_roles = [], nested_interfaces = []}], metadata = {interface_abi_version = 1 : i64, instance = @alias}}> : () -> ()
 %clock = "nodal.port_value"() <{port = @clock, metadata = {}}> : () -> !nodal.capability<"clock">
 %input = "nodal.port_value"() <{port = @input, metadata = {}}> : () -> !nodal.shaped<"3", !nodal.uint<8>>
 %zero = "nodal.const_literal"() <{spelling = "0", value = 0 : i64, metadata = {}}> : () -> i64
 %one = "nodal.const_literal"() <{spelling = "1", value = 1 : i64, metadata = {}}> : () -> i64
 %three = "nodal.const_literal"() <{spelling = "3", value = 3 : i64, metadata = {}}> : () -> i64
 %family = "nodal.generate"(%zero, %three, %one) <{region_id = "Top.family", induction = "i", induction_path = "Top.i", metadata = {maximum_trip_count = 3 : i64}}> ({
 ^bb0(%i: i64):
 "nodal.instance"() <{sym_name = "child", module = @Child, parameter_bindings = {}, domain_bindings = {required = @core}, generated_owner = "Top.family", generated_induction = "Top.i", metadata = {}}> : () -> ()
 "nodal.instance_domain_binding"(%clock) <{instance = @child, requirement = @required, actual = @core, origin = "named", metadata = {}}> : (!nodal.capability<"clock">) -> ()
 %childInput = "nodal.instance_port"() <{instance = @child, port = "input", direction = "input", metadata = {}}> : () -> !nodal.uint<8>
 %lane = "nodal.shape_index"(%input, %i) <{metadata = {}}> : (!nodal.shaped<"3", !nodal.uint<8>>, i64) -> !nodal.uint<8>
 "nodal.digital_assign"(%childInput, %lane) <{kind = "continuous", metadata = {}}> : (!nodal.uint<8>, !nodal.uint<8>) -> ()
 "nodal.generated_export"() <{target = @child, kind = "instance", member = "child", access = "read", metadata = {}}> : () -> ()
 }) : (i64, i64, i64) -> !nodal.generated_family
 "nodal.interface_boundary"(%family, %zero) <{sym_name = "selected0", instance = @child, endpoint = "endpoint", member = "child", metadata = {interface_abi_version = 1 : i64, dimensions = [], semantic_path = "Fixture.selected0", actual_types = [{path = "data", type = !nodal.uint<8>}]}}> : (!nodal.generated_family, i64) -> ()
 "nodal.interface_boundary"(%family, %one) <{sym_name = "selected1", instance = @child, endpoint = "endpoint", member = "child", metadata = {interface_abi_version = 1 : i64, dimensions = [], semantic_path = "Fixture.selected1", actual_types = [{path = "data", type = !nodal.uint<8>}]}}> : (!nodal.generated_family, i64) -> ()
 %firstValue = "nodal.member_access"() <{instance = @first, path = "data", metadata = {interface_abi_version = 1 : i64, role = "producer"}}> : () -> !nodal.uint<8>
 %secondValue = "nodal.member_access"() <{instance = @second, path = "data", metadata = {interface_abi_version = 1 : i64, role = "producer"}}> : () -> !nodal.uint<8>
 %aliasValue = "nodal.member_access"() <{instance = @alias, path = "data", metadata = {interface_abi_version = 1 : i64, role = "producer"}}> : () -> !nodal.uint<8>
 %lane0 = "nodal.member_access"() <{instance = @selected0, path = "data", metadata = {interface_abi_version = 1 : i64, role = "producer"}}> : () -> !nodal.uint<8>
 %lane1 = "nodal.member_access"() <{instance = @selected1, path = "data", metadata = {interface_abi_version = 1 : i64, role = "producer"}}> : () -> !nodal.uint<8>
 %lane0Again = "nodal.member_access"() <{instance = @selected0, path = "data", metadata = {interface_abi_version = 1 : i64, role = "producer"}}> : () -> !nodal.uint<8>
 "nodal.interface_connect"(%firstValue, %lane0) <{metadata = {interface_abi_version = 1 : i64}}> : (!nodal.uint<8>, !nodal.uint<8>) -> ()
 "nodal.interface_connect"(%secondValue, %lane1) <{metadata = {interface_abi_version = 1 : i64}}> : (!nodal.uint<8>, !nodal.uint<8>) -> ()
 "nodal.interface_connect"(%aliasValue, %lane0Again) <{metadata = {interface_abi_version = 1 : i64}}> : (!nodal.uint<8>, !nodal.uint<8>) -> ()
 "nodal.local_domain_binding"(%clock) <{domain = @core, reset_polarity = "active_high", metadata = {}}> : (!nodal.capability<"clock">) -> ()
}) : () -> ()
"nodal.module"() <{sym_name = "Child", metadata = {}}> ({
 "nodal.domain_requirement"() <{sym_name = "required", metadata = {}}> : () -> ()
 %clock = "nodal.requirement_clock"() <{requirement = @required, metadata = {}}> : () -> !nodal.capability<"clock">
 "nodal.port"() <{sym_name = "input", direction = "input", domain = @required, type = !nodal.uint<8>, metadata = {}}> : () -> ()
 %input = "nodal.port_value"() <{port = @input, metadata = {}}> : () -> !nodal.uint<8>
 "nodal.interface"() <{sym_name = "Bus", metadata = {interface_abi_version = 1 : i64, semantic_identity = "Bus"}}> ({
 "nodal.interface_role"() <{sym_name = "producer", kind = "explicit", metadata = {interface_abi_version = 1 : i64, access = [{path = "data", access = "out", leaf_role = "producer", nested_roles = [], nested_interfaces = []}]}}> : () -> ()
 "nodal.interface_member"() <{sym_name = "data", type = !nodal.uint<8>, protocol = "plain", roles = ["producer"], metadata = {interface_abi_version = 1 : i64, analog = {}}}> : () -> ()
}) : () -> ()
 "nodal.interface_instance"() <{sym_name = "endpoint", definition = @Bus, role = "producer", metadata = {interface_abi_version = 1 : i64, dimensions = [], domain = @required, semantic_path = "Fixture.endpoint"}}> : () -> ()
"nodal.interface_abi"() <{logical_path = "Fixture.endpoint", layout_policy = "structural", members = [{path = "data", type = !nodal.uint<8>, role = "producer", access = "out", nested_roles = [], nested_interfaces = []}], metadata = {interface_abi_version = 1 : i64, instance = @endpoint}}> : () -> ()
 %output = "nodal.member_access"() <{instance = @endpoint, path = "data", metadata = {interface_abi_version = 1 : i64, role = "producer"}}> : () -> !nodal.uint<8>
 "nodal.interface_drive"(%output, %input) <{metadata = {interface_abi_version = 1 : i64}}> : (!nodal.uint<8>, !nodal.uint<8>) -> ()
}) : () -> ()
}
)mlir";
}
std::string printed(Operation *op) {
  std::string text;
  llvm::raw_string_ostream stream(text);
  op->print(stream);
  return text;
}
std::string replace(std::string input, StringRef before, StringRef after) {
  auto at = input.find(before.str());
  if (at == std::string::npos)
    llvm::report_fatal_error("missing generated Interface fixture mutation");
  return input.replace(at, before.size(), after.str());
}
} // namespace
int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  std::string diagnostics;
  ScopedDiagnosticHandler handler(&context, [&](Diagnostic &diagnostic) {
    llvm::raw_string_ostream stream(diagnostics);
    diagnostic.print(stream);
    stream << '\n';
    return success();
  });
  unsigned cases = 0, failures = 0;
  auto report = [&](StringRef name, bool valid) {
    ++cases;
    if (!valid) {
      ++failures;
      llvm::errs() << "GENERATED_INTERFACE_FAIL " << name << '\n' << diagnostics;
    }
  };
  auto lower = [&](mlir::ModuleOp module) {
    PassManager manager(&context);
    auto pass = nodal::createSequentialToCIRCTPass();
    if (failed(pass->initializeOptions("preserve-constant-loops=true boundary-abi=named-fields",
                                       [&](const Twine &) { return failure(); })))
      return failure();
    manager.addPass(std::move(pass));
    return manager.run(module);
  };
  auto positive = [&](StringRef name, const std::string &input, unsigned childResults = 1) {
    diagnostics.clear();
    auto module = parseSourceString<mlir::ModuleOp>(input, ParserConfig(&context, false));
    bool valid = module && succeeded(verify(*module)) && succeeded(lower(*module));
    unsigned definitions = 0, helpers = 0, loops = 0, instances = 0, children = 0;
    if (valid) {
      module->walk([&](Operation *op) {
        definitions += isa<circt::hw::HWModuleOp>(op);
        helpers += op->hasAttr("nodal.generated_template");
        loops += op->getName().getStringRef() == "sv.generate.for";
        instances += isa<circt::hw::InstanceOp>(op);
        if (isa<circt::hw::HWModuleOp>(op) && op->getAttrOfType<StringAttr>("sym_name") == "Top")
          valid &= op->getRegion(0).front().getTerminator()->getNumOperands() == 3;
        if (auto child = dyn_cast<circt::hw::InstanceOp>(op))
          if (child.getModuleName() == "Child") {
            ++children;
            auto *owner = op->getParentOp();
            valid &=
                owner->hasAttr("nodal.generated_template") && child.getNumResults() == childResults;
            auto *output = owner->getRegion(0).front().getTerminator();
            valid &= output->getNumOperands() == 1 &&
                     output->getOperand(0) == child.getResult(childResults - 1);
          }
      });
      valid &= definitions == 3 && helpers == 1 && loops == 1 && instances == 2 && children == 1 &&
               succeeded(verify(*module));
    }
    report(name, valid);
  };
  auto negative = [&](StringRef name, const std::string &input, StringRef marker) {
    diagnostics.clear();
    auto module = parseSourceString<mlir::ModuleOp>(input, ParserConfig(&context, false));
    if (!module) {
      report(name, false);
      return;
    }
    auto before = printed(module->getOperation());
    bool rejected = failed(lower(*module));
    report(name, rejected && StringRef(diagnostics).contains(marker) &&
                     before == printed(module->getOperation()));
  };
  auto input = fixture();
  positive("selected-child-interface-distinct-lanes-share-one-original-output", input);
  auto unused = input;
  auto child = unused.find("sym_name = \"Child\"");
  auto endpoint = unused.find("\"nodal.interface_instance\"", child);
  auto end = unused.find("\"nodal.interface_drive\"", endpoint);
  auto extra = unused.substr(endpoint, end - endpoint);
  for (StringRef token : {"endpoint", "%output"}) {
    auto replacement = token == "endpoint" ? "spare" : "%spare";
    for (size_t at = extra.find(token.str()); at != std::string::npos;
         at = extra.find(token.str(), at + std::string(replacement).size()))
      extra.replace(at, token.size(), replacement);
  }
  extra +=
      R"mlir("nodal.interface_drive"(%spare, %input) <{metadata = {interface_abi_version = 1 : i64}}> : (!nodal.uint<8>, !nodal.uint<8>) -> ()
)mlir";
  unused.insert(endpoint, extra);
  positive("unobserved-child-interface-output-retains-its-physical-result-slot", unused, 2);
  negative("selected-child-interface-cannot-forge-instance",
           replace(input, "sym_name = \"selected0\", instance = @child",
                   "sym_name = \"selected0\", instance = @missing"),
           "NODAL-INTERFACE-054-001");
  negative("selected-child-interface-cannot-forge-member",
           replace(input, "instance = @selected0, path = \"data\"",
                   "instance = @selected0, path = \"missing\""),
           "NODAL-INTERFACE-054-001");
  auto writer = input.find("\"nodal.interface_drive\"");
  auto duplicate = input;
  duplicate.insert(writer, input.substr(writer, input.find('\n', writer) - writer + 1));
  negative("duplicate-original-interface-driver-is-not-hidden-by-selection", duplicate,
           "multiple authored drivers");
  // Outer InterfaceArray axes are not the payload Vec layout. Keep this
  // native-valid shape distinct and reject before any scalar slot is planned.
  std::istringstream lines(input);
  std::string line, array;
  while (std::getline(lines, line)) {
    if (StringRef(line).contains("\"nodal.interface_instance\"") ||
        StringRef(line).contains("\"nodal.interface_boundary\""))
      line = replace(line, "dimensions = []", "dimensions = [\"2\"]");
    if (!StringRef(line).contains("\"nodal.interface_member\"") &&
        !StringRef(line).contains("\"nodal.interface_abi\"") &&
        !StringRef(line).contains("\"nodal.interface_boundary\"")) {
      StringRef from = "!nodal.uint<8>";
      StringRef to = "!nodal.shaped<\"2\", !nodal.uint<8>>";
      for (size_t at = line.find(from.str()); at != std::string::npos;
           at = line.find(from.str(), at + to.size()))
        line.replace(at, from.size(), to);
    }
    array += line + "\n";
  }
  diagnostics.clear();
  auto shaped = parseSourceString<mlir::ModuleOp>(array, ParserConfig(&context, false));
  report("selected-child-interface-array-is-native-valid", shaped && succeeded(verify(*shaped)));
  negative("selected-child-interface-array-is-not-one-scalar-carrier", array,
           "selected child Interface requires original module-owned payload and scalar endpoint");
  llvm::outs() << "GENERATED_INTERFACE_SUMMARY cases=" << cases << " failures=" << failures << '\n';
  return failures != 0;
}
