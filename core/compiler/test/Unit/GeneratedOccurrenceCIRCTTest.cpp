#include "circt/Dialect/HW/HWAttributes.h"
#include "circt/Dialect/HW/HWOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Pass/PassManager.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Transforms/Passes.h"

#include "llvm/Support/raw_ostream.h"

using namespace mlir;
namespace {
// Compact native counterpart of Increment58GeneratedOccurrenceTargetScalar.
// The real source/bridge and unchanged-SV behavior live in run_generated_target.py.
std::string fixture() {
  return R"mlir(
module attributes {nodal.root.module = @Top, nodal.root.parameter_bindings = {}} {
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
    "nodal.domain"() <{edge = "rising", metadata = {}, reset_policy = "sync", sym_name = "core"}> : () -> ()
    "nodal.parameter"() <{classification = "structural", default_value = 3 : i64, metadata = {}, sym_name = "LANES", type = i64, variability = "symbolic"}> : () -> ()
    %v0 = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    %v1 = "nodal.const_literal"() <{metadata = {}, spelling = "8", value = 8 : i64}> : () -> i64
    "nodal.parameter_constraint"(%v0, %v1) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @LANES, upper_inclusive = true}> : (i64, i64) -> ()
    "nodal.parameter_envelope"() <{effects = ["rank", "shape", "topology"], metadata = {}, parameter = @LANES, policy = "static_generate"}> : () -> ()
    "nodal.parameter"() <{classification = "structural", default_value = 8 : i64, metadata = {}, sym_name = "WIDTH", type = i64, variability = "symbolic"}> : () -> ()
    %v2 = "nodal.const_literal"() <{metadata = {}, spelling = "4", value = 4 : i64}> : () -> i64
    %v3 = "nodal.const_literal"() <{metadata = {}, spelling = "16", value = 16 : i64}> : () -> i64
    "nodal.parameter_constraint"(%v2, %v3) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @WIDTH, upper_inclusive = true}> : (i64, i64) -> ()
    "nodal.parameter_envelope"() <{effects = ["width"], metadata = {}, parameter = @WIDTH, policy = "static_generate"}> : () -> ()
    "nodal.port"() <{direction = "input", domain = @core, metadata = {}, sym_name = "core_clock", type = !nodal.capability<"clock">}> : () -> ()
    "nodal.port"() <{direction = "input", domain = @core, metadata = {}, sym_name = "core_reset", type = !nodal.capability<"reset">}> : () -> ()
    "nodal.port"() <{direction = "output", domain = @core, metadata = {}, sym_name = "first", type = !nodal.digital<"uint", "WIDTH">}> : () -> ()
    "nodal.port"() <{direction = "input", domain = @core, metadata = {}, sym_name = "input", type = !nodal.shaped<"LANES", !nodal.digital<"uint", "WIDTH">>}> : () -> ()
    "nodal.port"() <{direction = "output", domain = @core, metadata = {}, sym_name = "output", type = !nodal.shaped<"LANES", !nodal.digital<"uint", "WIDTH">>}> : () -> ()
    %v4 = "nodal.const_parameter_ref"() <{metadata = {}, parameter = @WIDTH}> : () -> i64
    %v5 = "nodal.port_value"() <{metadata = {}, port = @core_clock}> : () -> !nodal.capability<"clock">
    %v6 = "nodal.port_value"() <{metadata = {}, port = @core_reset}> : () -> !nodal.capability<"reset">
    %v7 = "nodal.reset_boundary"(%v6) <{reset_polarity = "active_high", metadata = {}, release = "sync", release_domain = @"core"}> : (!nodal.capability<"reset">) -> !nodal.capability<"reset">
    %v8 = "nodal.const_literal"() <{metadata = {}, spelling = "0", value = 0 : i64}> : () -> i64
    %v9 = "nodal.const_parameter_ref"() <{metadata = {}, parameter = @LANES}> : () -> i64
    %v10 = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
    %v11 = "nodal.port_value"() <{metadata = {}, port = @input}> : () -> !nodal.shaped<"LANES", !nodal.digital<"uint", "WIDTH">>
    %v12 = "nodal.port_value"() <{metadata = {}, port = @output}> : () -> !nodal.shaped<"LANES", !nodal.digital<"uint", "WIDTH">>
    %v13 = "nodal.generate"(%v8, %v9, %v10) <{induction = "i", region_id = "Top.family", induction_path = "Top.i", metadata = {maximum_trip_count = 8 : i64}}> ({
      ^bb0(%v14: i64):
      "nodal.instance"() <{domain_bindings = {required = @core}, generated_induction = "Top.i", generated_owner = "Top.family", metadata = {}, module = @Child, parameter_bindings = {}, sym_name = "child"}> : () -> ()
      "nodal.parameter_override"(%v4) <{generated_induction = "Top.i", generated_owner = "Top.family", instance = @child, metadata = {}, parameter = @WIDTH}> : (i64) -> ()
      "nodal.instance_domain_binding"(%v5, %v7) <{instance = @"child", requirement = @"required", actual = @"core", origin = "named", metadata = {}}> : (!nodal.capability<"clock">, !nodal.capability<"reset">) -> ()
      %v15 = "nodal.instance_port"() <{direction = "input", instance = @child, metadata = {}, port = "input"}> : () -> !nodal.digital<"uint", "WIDTH">
      %v16 = "nodal.digital_storage"() <{name = "generate_0_lanes_stored", kind = "wire", metadata = {}}> : () -> !nodal.digital<"uint", "WIDTH">
      %v17 = "nodal.instance_port"() <{direction = "output", instance = @child, metadata = {}, port = "output"}> : () -> !nodal.digital<"uint", "WIDTH">
      %v18 = "nodal.shape_index"(%v11, %v14) <{metadata = {}}> : (!nodal.shaped<"LANES", !nodal.digital<"uint", "WIDTH">>, i64) -> !nodal.digital<"uint", "WIDTH">
      "nodal.digital_assign"(%v15, %v18) <{kind = "continuous", metadata = {}}> : (!nodal.digital<"uint", "WIDTH">, !nodal.digital<"uint", "WIDTH">) -> ()
      "nodal.digital_assign"(%v16, %v17) <{kind = "continuous", metadata = {}}> : (!nodal.digital<"uint", "WIDTH">, !nodal.digital<"uint", "WIDTH">) -> ()
      %v19 = "nodal.shape_index"(%v12, %v14) <{metadata = {}}> : (!nodal.shaped<"LANES", !nodal.digital<"uint", "WIDTH">>, i64) -> !nodal.digital<"uint", "WIDTH">
      "nodal.digital_assign"(%v19, %v16) <{kind = "continuous", metadata = {}}> : (!nodal.digital<"uint", "WIDTH">, !nodal.digital<"uint", "WIDTH">) -> ()
      "nodal.generated_export"(%v16) <{member = "stored", access = "wire", metadata = {}}> : (!nodal.digital<"uint", "WIDTH">) -> ()
    }) : (i64, i64, i64) -> !nodal.generated_family
    %v20 = "nodal.port_value"() <{metadata = {}, port = @first}> : () -> !nodal.digital<"uint", "WIDTH">
    %v21 = "nodal.generated_member"(%v13, %v8) <{member = "stored", access = "wire", metadata = {}}> : (!nodal.generated_family, i64) -> !nodal.digital<"uint", "WIDTH">
    "nodal.digital_assign"(%v20, %v21) <{kind = "continuous", metadata = {}}> : (!nodal.digital<"uint", "WIDTH">, !nodal.digital<"uint", "WIDTH">) -> ()
    "nodal.local_domain_binding"(%v5, %v7) <{domain = @"core", reset_polarity = "active_high", metadata = {}}> : (!nodal.capability<"clock">, !nodal.capability<"reset">) -> ()
  }) : () -> ()
  
  "nodal.module"() <{metadata = {}, sym_name = "Child"}> ({
    "nodal.domain_requirement"() <{metadata = {}, sym_name = "required"}> : () -> ()
    %v22 = "nodal.requirement_clock"() <{metadata = {}, requirement = @required}> : () -> !nodal.capability<"clock">
    "nodal.parameter"() <{classification = "structural", default_value = 8 : i64, metadata = {}, sym_name = "WIDTH", type = i64, variability = "symbolic"}> : () -> ()
    %v23 = "nodal.const_literal"() <{metadata = {}, spelling = "4", value = 4 : i64}> : () -> i64
    %v24 = "nodal.const_literal"() <{metadata = {}, spelling = "16", value = 16 : i64}> : () -> i64
    "nodal.parameter_constraint"(%v23, %v24) <{constraint_kind = "range", lower_inclusive = true, metadata = {}, parameter = @WIDTH, upper_inclusive = true}> : (i64, i64) -> ()
    "nodal.parameter_envelope"() <{effects = ["width"], metadata = {}, parameter = @WIDTH, policy = "static_generate"}> : () -> ()
    "nodal.port"() <{direction = "input", domain = @required, metadata = {}, sym_name = "input", type = !nodal.digital<"uint", "WIDTH">}> : () -> ()
    "nodal.port"() <{direction = "output", domain = @required, metadata = {}, sym_name = "output", type = !nodal.digital<"uint", "WIDTH">}> : () -> ()
    %v5 = "nodal.port_value"() <{metadata = {}, port = @output}> : () -> !nodal.digital<"uint", "WIDTH">
    %v7 = "nodal.port_value"() <{metadata = {}, port = @input}> : () -> !nodal.digital<"uint", "WIDTH">
    "nodal.digital_assign"(%v5, %v7) <{kind = "continuous", metadata = {}}> : (!nodal.digital<"uint", "WIDTH">, !nodal.digital<"uint", "WIDTH">) -> ()
  }) : () -> ()
}

)mlir";
}
std::string printed(Operation *operation) {
  std::string result;
  llvm::raw_string_ostream stream(result);
  operation->print(stream);
  return result;
}
std::string replace(std::string input, StringRef from, StringRef to) {
  auto at = input.find(from.str());
  if (at == std::string::npos)
    llvm::report_fatal_error("missing generated occurrence test mutation");
  return input.replace(at, from.size(), to.str());
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
  auto report = [&](StringRef name, bool success) {
    ++cases;
    if (!success) {
      ++failures;
      llvm::errs() << "GENERATED_OCCURRENCE_FAIL " << name << '\n' << diagnostics;
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
  auto positive = [&](StringRef name, const std::string &input, unsigned childResults = 1,
                      unsigned helperResults = 1) {
    diagnostics.clear();
    auto module = parseSourceString<mlir::ModuleOp>(input, ParserConfig(&context, false));
    bool valid = module && succeeded(verify(*module)) && succeeded(lower(*module));
    unsigned definitions = 0, helpers = 0, loops = 0, instances = 0, children = 0;
    if (valid) {
      module->walk([&](Operation *operation) {
        definitions += isa<circt::hw::HWModuleOp>(operation);
        helpers += operation->hasAttr("nodal.generated_template");
        if (operation->hasAttr("nodal.generated_template"))
          valid &=
              operation->getRegion(0).front().getTerminator()->getNumOperands() == helperResults;
        loops += operation->getName().getStringRef() == "sv.generate.for";
        instances += isa<circt::hw::InstanceOp>(operation);
        if (auto instance = dyn_cast<circt::hw::InstanceOp>(operation))
          if (instance.getModuleName() == "Child") {
            ++children;
            valid &= operation->getParentOp()->hasAttr("nodal.generated_template") &&
                     instance.getNumResults() == childResults;
            auto parameters = instance.getParameters();
            valid &= parameters.size() == 1 &&
                     isa<circt::hw::ParamDeclRefAttr>(
                         cast<circt::hw::ParamDeclAttr>(parameters[0]).getValue());
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
    const auto before = printed(module->getOperation());
    bool rejected = failed(lower(*module));
    report(name, rejected && StringRef(diagnostics).contains(marker) &&
                     before == printed(module->getOperation()));
  };
  const auto input = fixture();
  positive("one-authored-child-inside-one-real-family", input);
  // An authored physical output is planned even without a parent-side view.
  // Place it before the observed output to catch result-index compression.
  auto unused = input;
  auto childStart = unused.find("sym_name = \"Child\"");
  auto outputPort = unused.find("    \"nodal.port\"() <{direction = \"output\"", childStart);
  unused.insert(outputPort, R"mlir(
    "nodal.port"() <{direction = "output", domain = @required, metadata = {}, sym_name = "spare", type = !nodal.digital<"uint", "WIDTH">}> : () -> ()
    %spare = "nodal.port_value"() <{metadata = {}, port = @spare}> : () -> !nodal.digital<"uint", "WIDTH">
)mlir");
  auto childWrite = unused.find("    \"nodal.digital_assign\"(%v5, %v7)", childStart);
  unused.insert(childWrite, R"mlir(
    "nodal.digital_assign"(%spare, %v7) <{kind = "continuous", metadata = {}}> : (!nodal.digital<"uint", "WIDTH">, !nodal.digital<"uint", "WIDTH">) -> ()
)mlir");
  positive("unobserved-authored-child-output-retains-physical-slot", unused, 2);
  auto larger =
      replace(input, "spelling = \"8\", value = 8 : i64", "spelling = \"256\", value = 256 : i64");
  larger = replace(larger, "maximum_trip_count = 8", "maximum_trip_count = 256");
  positive("256-lanes-retain-one-child-template-and-symbolic-width", larger);
  negative("forged-instance-owner",
           replace(input, "generated_owner = \"Top.family\"", "generated_owner = \"forged\""),
           "exact generated owner");
  negative("forged-instance-induction",
           replace(input, "generated_induction = \"Top.i\"", "generated_induction = \"forged\""),
           "exact generated owner");
  auto binding = input.find("      \"nodal.instance_domain_binding\"");
  auto missing = input;
  missing.erase(binding, input.find('\n', binding) - binding + 1);
  negative("missing-actual-domain-binding", missing, "unique concrete instance binding");
  auto assignment = input.find("      \"nodal.digital_assign\"(%v15");
  auto writer = input.substr(assignment, input.find('\n', assignment) - assignment + 1);
  auto duplicate = input;
  duplicate.insert(assignment, writer);
  negative("duplicate-body-local-input-writer", duplicate, "overlapping");
  auto over = replace(larger, "spelling = \"256\", value = 256 : i64",
                      "spelling = \"257\", value = 257 : i64");
  over = replace(over, "maximum_trip_count = 256", "maximum_trip_count = 257");
  negative("physical-carrier-budget-boundary", over, "NODAL-CIRCT-056-001");
  // A provably empty family still keeps checked loop, helper and authored child.
  // Its aliases are template facts, never fabricated executable dependencies.
  auto empty =
      replace(input, "nodal.generate\"(%v8, %v9, %v10)", "nodal.generate\"(%v8, %v8, %v10)");
  empty = replace(empty, "maximum_trip_count = 8", "maximum_trip_count = 0");
  for (auto line : {"      %v19 =", "      \"nodal.digital_assign\"(%v19"}) {
    auto at = empty.find(line);
    empty.erase(at, empty.find('\n', at) - at + 1);
  }
  auto selection = empty.find("    %v21 =");
  empty.replace(selection, empty.find('\n', selection) - selection,
                "    %v21 = \"nodal.const_literal\"() <{value = 0 : i64, spelling = \"0\", "
                "metadata = {}}> : () -> !nodal.digital<\"uint\", \"WIDTH\">");
  auto output = empty.find("    \"nodal.local_domain_binding\"");
  empty.insert(
      output, "    \"nodal.digital_assign\"(%v12, %v11) <{kind = \"continuous\", metadata = {}}> : "
              "(!nodal.shaped<\"LANES\", !nodal.digital<\"uint\", \"WIDTH\">>, "
              "!nodal.shaped<\"LANES\", !nodal.digital<\"uint\", \"WIDTH\">>) -> ()\n");
  // Fixed UInt8 matches the actual empty public source, keeping symbolic WIDTH
  // override validation independent of unreachable payload transport.
  for (size_t at = empty.find("!nodal.digital<\"uint\", \"WIDTH\">"); at != std::string::npos;
       at = empty.find("!nodal.digital<\"uint\", \"WIDTH\">", at + 14))
    empty.replace(at, 31, "!nodal.uint<8>");
  positive("empty-family-retains-original-child-and-helper", empty, 1, 0);
  auto emptyDuplicate = empty;
  auto emptyWrite = empty.find("      \"nodal.digital_assign\"(%v15");
  emptyDuplicate.insert(emptyWrite,
                        empty.substr(emptyWrite, empty.find('\n', emptyWrite) - emptyWrite + 1));
  negative("empty-family-does-not-hide-duplicate-template-writer", emptyDuplicate, "whole writer");
  negative("empty-family-does-not-hide-forged-instance-owner",
           replace(empty, "generated_owner = \"Top.family\"", "generated_owner = \"forged\""),
           "exact generated owner");

  // No body-local output view exists: the original Instance + logical port must
  // own the carrier, while repeated selected aliases share that one output.
  auto selected = input;
  for (auto line : {"      %v16 =", "      %v17 =", "      \"nodal.digital_assign\"(%v16",
                    "      %v19 =", "      \"nodal.digital_assign\"(%v19",
                    "      \"nodal.generated_export\""}) {
    auto at = selected.find(line);
    selected.erase(at, selected.find('\n', at) - at + 1);
  }
  auto exported = selected.find("    }) : (i64, i64, i64) -> !nodal.generated_family");
  selected.insert(exported,
                  "      \"nodal.generated_export\"() <{kind = \"instance\", target = @child, "
                  "member = \"child\", access = \"read\", metadata = {}}> : () -> ()\n");
  auto read = selected.find("    %v21 =");
  selected.replace(read, selected.find('\n', read) - read,
                   "    %v21 = \"nodal.instance_port\"(%v13, %v8) <{direction = \"output\", "
                   "instance = @child, member = \"child\", port = \"output\", metadata = {}}> : "
                   "(!nodal.generated_family, i64) -> !nodal.digital<\"uint\", \"WIDTH\">");
  auto wholeOutput = selected.find("    \"nodal.local_domain_binding\"");
  selected.insert(
      wholeOutput,
      "    \"nodal.digital_assign\"(%v12, %v11) <{kind = \"continuous\", metadata = {}}> : "
      "(!nodal.shaped<\"LANES\", !nodal.digital<\"uint\", \"WIDTH\">>, "
      "!nodal.shaped<\"LANES\", !nodal.digital<\"uint\", \"WIDTH\">>) -> ()\n");
  positive("selected-instance-output-without-body-local-view", selected);
  auto selectedUnused = selected;
  childStart = selectedUnused.find("sym_name = \"Child\"");
  outputPort = selectedUnused.find("    \"nodal.port\"() <{direction = \"output\"", childStart);
  selectedUnused.insert(outputPort, R"mlir(
    "nodal.port"() <{direction = "output", domain = @required, metadata = {}, sym_name = "spare", type = !nodal.digital<"uint", "WIDTH">}> : () -> ()
    %spare = "nodal.port_value"() <{metadata = {}, port = @spare}> : () -> !nodal.digital<"uint", "WIDTH">
)mlir");
  childWrite = selectedUnused.find("    \"nodal.digital_assign\"(%v5, %v7)", childStart);
  selectedUnused.insert(childWrite, R"mlir(
    "nodal.digital_assign"(%spare, %v7) <{kind = "continuous", metadata = {}}> : (!nodal.digital<"uint", "WIDTH">, !nodal.digital<"uint", "WIDTH">) -> ()
)mlir");
  positive("selected-output-retains-unobserved-preceding-physical-slot", selectedUnused, 2);

  auto repeatedSelected = selected;
  read = selected.find("    %v21 =");
  auto selectedRead = selected.substr(read, selected.find('\n', read) - read + 1);
  selectedRead = replace(selectedRead, "%v21 =", "%v25 =");
  repeatedSelected.insert(read, selectedRead);
  positive("independently-reconstructed-selection-shares-original-carrier", repeatedSelected);
  negative("selected-original-port-identity-cannot-be-forged",
           replace(selected, "member = \"child\", port = \"output\"",
                   "member = \"child\", port = \"missing\""),
           "instance port must retain exact child data type and input/output direction");
  negative("selected-instance-symbol-cannot-name-another-declaration",
           replace(selected, "instance = @child, member = \"child\", port = \"output\"",
                   "instance = @missing, member = \"child\", port = \"output\""),
           "selected instance port requires its exact exported instance target");
  negative("selected-ordinal-is-not-an-authored-coordinate",
           replace(selected, "instance_port\"(%v13, %v8)", "instance_port\"(%v13, %v1)"),
           "selected instance port requires its exact exported instance target");
  // Keep the capture scalar so the selected output carrier, rather than the
  // independently bounded input Vec, is the first capacity boundary.
  auto selectedLimit = selected;
  auto indexedInput = selectedLimit.find("      %v18 =");
  selectedLimit.erase(indexedInput, selectedLimit.find('\n', indexedInput) - indexedInput + 1);
  selectedLimit =
      replace(selectedLimit, "digital_assign\"(%v15, %v18)", "digital_assign\"(%v15, %v11)");
  const std::string vectorType = "!nodal.shaped<\"LANES\", !nodal.digital<\"uint\", \"WIDTH\">>";
  for (size_t at = selectedLimit.find(vectorType); at != std::string::npos;
       at = selectedLimit.find(vectorType))
    selectedLimit.replace(at, vectorType.size(), "!nodal.digital<\"uint\", \"WIDTH\">");
  selectedLimit = replace(selectedLimit, "spelling = \"8\", value = 8 : i64",
                          "spelling = \"256\", value = 256 : i64");
  selectedLimit = replace(selectedLimit, "maximum_trip_count = 8", "maximum_trip_count = 256");
  positive("selected-original-output-at-physical-carrier-limit", selectedLimit);
  auto selectedOver = replace(selectedLimit, "spelling = \"256\", value = 256 : i64",
                              "spelling = \"257\", value = 257 : i64");
  selectedOver = replace(selectedOver, "maximum_trip_count = 256", "maximum_trip_count = 257");
  negative("selected-original-output-over-physical-carrier-limit", selectedOver,
           "selected original instance output exceeds family carrier budget");
  auto selectedInput = replace(selected, "direction = \"output\", instance = @child, member",
                               "direction = \"input\", instance = @child, member");
  selectedInput = replace(selectedInput, "member = \"child\", port = \"output\"",
                          "member = \"child\", port = \"input\"");
  negative("selected-input-view-never-becomes-an-output-carrier", selectedInput,
           "selected instance target currently admits original generated outputs only");
  llvm::outs() << "GENERATED_OCCURRENCE_SUMMARY cases=" << cases << " failures=" << failures
               << '\n';
  return failures != 0;
}
