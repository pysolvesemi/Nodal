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
// Literal 2x2 counterpart: four authored parent RHS Values, no local input
// proxy, and all four original child outputs selected through both family axes.
std::string nestedSelectedInputFixture(bool reverse = false, bool strided = false) {
  const std::string bit = "!nodal.bits<1>";
  auto port = [&](StringRef name, StringRef direction, StringRef domain) {
    return "\"nodal.port\"() <{sym_name = \"" + name.str() + "\", type = " + bit +
           ", direction = \"" + direction.str() + "\", domain = @" + domain.str() +
           ", metadata = {}}> : () -> ()\n%" + name.str() + " = \"nodal.port_value\"() <{port = @" +
           name.str() + ", metadata = {}}> : () -> " + bit + "\n";
  };
  auto literal = [](StringRef name, int value) {
    return "%" + name.str() + " = \"nodal.const_literal\"() <{value = " + std::to_string(value) +
           " : i64, spelling = \"" + std::to_string(value) + "\", metadata = {}}> : () -> i64\n";
  };
  auto family = [&](StringRef name, int lower, int upper, int step, const std::string &body) {
    std::string n = name.str();
    return literal(n + "_lo", lower) + literal(n + "_hi", upper) + literal(n + "_step", step) +
           "%" + n + " = \"nodal.generate\"(%" + n + "_lo, %" + n + "_hi, %" + n +
           "_step) <{induction = \"i\", region_id = \"Top." + n + "\", induction_path = \"Top." +
           n + ".index\", metadata = {maximum_trip_count = 2 : i64, semantic_path = \"Top." + n +
           "\"}}> ({^bb0(%" + n + "_index: i64):\n" + body +
           "}) : (i64, i64, i64) -> !nodal.generated_family\n";
  };
  std::string top = R"mlir(
    "nodal.domain"() <{sym_name = "core", edge = "rising", reset_policy = "none", metadata = {}}> : () -> ()
    "nodal.port"() <{sym_name = "clock", type = !nodal.capability<"clock">, direction = "input", domain = @core, metadata = {}}> : () -> ()
    %clock = "nodal.port_value"() <{port = @clock, metadata = {}}> : () -> !nodal.capability<"clock">
    "nodal.local_domain_binding"(%clock) <{domain = @core, reset_polarity = "active_high", metadata = {}}> : (!nodal.capability<"clock">) -> ()
)mlir";
  for (StringRef name : {"x00", "x01", "x10", "x11"})
    top += port(name, "input", "core");
  std::string child = R"mlir(
    "nodal.instance"() <{sym_name = "child", module = @Child, parameter_bindings = {}, domain_bindings = {required = @core}, generated_owner = "Top.outer.inner", generated_induction = "Top.outer.inner.index", metadata = {}}> : () -> ()
    "nodal.instance_domain_binding"(%clock) <{instance = @child, requirement = @required, actual = @core, origin = "named", metadata = {}}> : (!nodal.capability<"clock">) -> ()
    "nodal.generated_export"() <{target = @child, member = "child", kind = "instance", access = "read", metadata = {}}> : () -> ()
)mlir";
  auto inner = family("outer.inner", strided ? 5 : 0, strided ? 11 : 2, strided ? 3 : 1, child);
  inner += "\"nodal.generated_export\"(%outer.inner) <{member = \"inner\", kind = \"family\", "
           "access = \"read\", metadata = {}}> : (!nodal.generated_family) -> ()\n";
  top += family("outer", strided ? 3 : 0, strided ? 7 : 2, strided ? 2 : 1, inner);
  top += literal("zero", 0) + literal("one", 1);
  for (unsigned row = 0; row < 2; ++row)
    top += "%row" + std::to_string(row) + " = \"nodal.generated_member\"(%outer, %" +
           std::string(row ? "one" : "zero") +
           ") <{member = \"inner\", kind = \"family\", "
           "access = \"read\", metadata = {}}> : (!nodal.generated_family, i64) -> "
           "!nodal.generated_family\n";
  for (unsigned i = 0; i < 4; ++i) {
    auto suffix = std::to_string(i / 2) + std::to_string(i % 2);
    for (StringRef direction : {"input", "output"})
      top += "%" + direction.str() + suffix + " = \"nodal.instance_port\"(%row" +
             std::to_string(i / 2) + ", %" + std::string(i % 2 ? "one" : "zero") +
             ") <{instance = @child, member = \"child\", port = \"" + direction.str() +
             "\", direction = \"" + direction.str() +
             "\", metadata = {}}> : (!nodal.generated_family, i64) -> " + bit + "\n";
    top += port("y" + suffix, "output", "core") + "\"nodal.digital_assign\"(%y" + suffix +
           ", %output" + suffix + ") <{kind = \"continuous\", metadata = {}}> : (" + bit + ", " +
           bit + ") -> ()\n";
  }
  for (unsigned i = 0; i < 4; ++i) {
    unsigned index = reverse ? 3 - i : i;
    auto suffix = std::to_string(index / 2) + std::to_string(index % 2);
    top += "\"nodal.digital_assign\"(%input" + suffix + ", %x" + suffix +
           ") <{kind = \"continuous\", metadata = {}}> : (" + bit + ", " + bit + ") -> ()\n";
  }
  std::string definition =
      "\"nodal.domain_requirement\"() <{sym_name = \"required\", metadata = {}}> : () -> ()\n"
      "%required_clock = \"nodal.requirement_clock\"() <{requirement = @required, metadata = {}}> "
      ": () -> !nodal.capability<\"clock\">\n" +
      port("input", "input", "required") + port("output", "output", "required") +
      "\"nodal.digital_assign\"(%output, %input) <{kind = \"continuous\", metadata = {}}> : (" +
      bit + ", " + bit + ") -> ()\n";
  return "module attributes {nodal.root.module = @Top, nodal.root.parameter_bindings = {}} {\n"
         "\"nodal.module\"() <{sym_name = \"Top\", metadata = {}}> ({\n" +
         top + "}) : () -> ()\n\"nodal.module\"() <{sym_name = \"Child\", metadata = {}}> ({\n" +
         definition + "}) : () -> ()\n}\n";
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

int main(int argc, char **argv) {
  const bool emitSelectedInput = argc == 2 && StringRef(argv[1]) == "--emit-selected-input";
  const bool lowerSelectedInput = argc == 3 && StringRef(argv[1]) == "--lower-selected-input";
  if (argc != 1 && !emitSelectedInput && !lowerSelectedInput) {
    llvm::errs() << "usage: " << argv[0]
                 << " [--emit-selected-input | --lower-selected-input <file>]\n";
    return 2;
  }
  bool emittedSelectedInput = false;
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
  if (lowerSelectedInput) {
    auto module = parseSourceFile<mlir::ModuleOp>(argv[2], ParserConfig(&context, false));
    if (!module || failed(verify(*module)) || failed(lower(*module))) {
      llvm::errs() << "SELECTED_INPUT_FILE_CONVERSION_FAIL\n" << diagnostics;
      return 1;
    }
    bool residualNodal = false;
    module->walk([&](Operation *operation) {
      residualNodal |= operation->getName().getDialectNamespace() == "nodal";
    });
    if (residualNodal || failed(verify(*module))) {
      llvm::errs() << "SELECTED_INPUT_FILE_CONVERSION_FAIL residual-Nodal-or-invalid-IR\n"
                   << diagnostics;
      return 1;
    }
    module->print(llvm::outs());
    llvm::outs() << '\n';
    llvm::errs() << "SELECTED_INPUT_FILE_CONVERSION_OK\n";
    return 0;
  }
  auto positive = [&](StringRef name, const std::string &input, unsigned childResults = 1,
                      unsigned helperResults = 1, bool selectedInputTransport = false,
                      bool normalizedInputSlice = false, bool interfaceInputs = false) {
    diagnostics.clear();
    auto module = parseSourceString<mlir::ModuleOp>(input, ParserConfig(&context, false));
    bool valid = module && succeeded(verify(*module)) && succeeded(lower(*module));
    unsigned definitions = 0, helpers = 0, loops = 0, instances = 0, children = 0;
    unsigned exactInputBuses = 0, exactInputSlices = 0, exactChildInputs = 0;
    unsigned interfaceBuses = 0, interfaceSlices = 0, interfaceChildInputs = 0;
    if (valid) {
      module->walk([&](Operation *operation) {
        if (selectedInputTransport && operation->getName().getStringRef() == "comb.concat" &&
            operation->getNumOperands() == 2 && operation->getResult(0).getType().isInteger(16)) {
          auto high = operation->getOperand(0).getDefiningOp();
          auto low = operation->getOperand(1).getDefiningOp();
          auto highValue = high ? high->getAttrOfType<IntegerAttr>("value") : IntegerAttr();
          auto lowValue = low ? low->getAttrOfType<IntegerAttr>("value") : IntegerAttr();
          exactInputBuses +=
              highValue && lowValue && highValue.getInt() == 34 && lowValue.getInt() == 17;
        }
        if (interfaceInputs) {
          valid &= operation->getName().getDialectNamespace() != "nodal";
          if (operation->getName().getStringRef() == "comb.concat" &&
              operation->getNumOperands() == 2) {
            auto high = operation->getOperand(0).getDefiningOp();
            auto low = operation->getOperand(1).getDefiningOp();
            auto h = high ? high->getAttrOfType<IntegerAttr>("value") : IntegerAttr();
            auto l = low ? low->getAttrOfType<IntegerAttr>("value") : IntegerAttr();
            if (h && l)
              interfaceBuses +=
                  (operation->getResult(0).getType().isInteger(16) && h.getInt() == 34 &&
                   l.getInt() == 17) ||
                  (operation->getResult(0).getType().isInteger(32) && h.getInt() == 514 &&
                   l.getInt() == 257);
          }
          if (auto instance = dyn_cast<circt::hw::InstanceOp>(operation)) {
            if (instance.getModuleName() == "Child") {
              for (Value operand : operation->getOperands())
                if (auto argument = dyn_cast<BlockArgument>(operand))
                  interfaceChildInputs += argument.getType().isInteger(8) ||
                                          argument.getType().isInteger(16);
            } else {
              for (Value operand : operation->getOperands()) {
                auto slice = operand.getDefiningOp();
                if (!slice || slice->getName().getStringRef() != "sv.verbatim.expr" ||
                    slice->getNumOperands() != 3)
                  continue;
                auto concat = slice->getOperand(0).getDefiningOp();
                if (concat && concat->getName().getStringRef() == "comb.concat")
                  interfaceSlices += (operand.getType().isInteger(8) &&
                                      concat->getResult(0).getType().isInteger(16)) ||
                                     (operand.getType().isInteger(16) &&
                                      concat->getResult(0).getType().isInteger(32));
              }
            }
          }
        }
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
            if (selectedInputTransport) {
              auto actual = dyn_cast<BlockArgument>(operation->getOperand(0));
              exactChildInputs += actual && actual.getType().isInteger(8) &&
                                  actual.getArgNumber() + 1 == actual.getOwner()->getNumArguments();
            }
            auto parameters = instance.getParameters();
            valid &= parameters.size() == 1 &&
                     isa<circt::hw::ParamDeclRefAttr>(
                         cast<circt::hw::ParamDeclAttr>(parameters[0]).getValue());
          } else if (selectedInputTransport) {
            auto slice = operation->getOperands().back().getDefiningOp();
            exactInputSlices += slice && slice->getName().getStringRef() == "sv.verbatim.expr" &&
                                slice->getNumOperands() == (normalizedInputSlice ? 5u : 3u) &&
                                slice->getOperand(0).getDefiningOp() &&
                                slice->getOperand(0).getDefiningOp()->getName().getStringRef() ==
                                    "comb.concat";
            if (normalizedInputSlice && slice && slice->getNumOperands() == 5) {
              auto lower = slice->getOperand(3).getDefiningOp();
              auto step = slice->getOperand(4).getDefiningOp();
              auto lowerValue = lower ? lower->getAttrOfType<IntegerAttr>("value") : IntegerAttr();
              auto stepValue = step ? step->getAttrOfType<IntegerAttr>("value") : IntegerAttr();
              auto format = slice->getAttrOfType<StringAttr>("format_string");
              valid &= lowerValue && stepValue && lowerValue.getInt() == 3 &&
                       stepValue.getInt() == 2 && format &&
                       format.getValue() == "{{0}}[(({{1}} - {{3}}) / {{4}}) * {{2}} +: {{2}}]";
            }
          }
      });
      if (selectedInputTransport)
        valid &= exactInputBuses == 1 && exactInputSlices == 1 && exactChildInputs == 1;
      if (interfaceInputs)
        valid &= interfaceBuses == 2 && interfaceSlices == 2 && interfaceChildInputs == 2;
      valid &= definitions == 3 && helpers == 1 && loops == 1 && instances == 2 && children == 1 &&
               succeeded(verify(*module));
    }
    report(name, valid);
    if (emitSelectedInput && !emittedSelectedInput && valid &&
        name == "selected-inputs-distinct-rhs-both-output-lanes-no-body-proxy") {
      module->print(llvm::outs());
      llvm::outs() << '\n';
      emittedSelectedInput = true;
    }
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
  // Fixed selected inputs have no body-local input proxy at all. Both lanes
  // have distinct actual RHS values and both child results reach parent pins.
  auto selectedInputs = selected;
  for (auto line : {"      %v15 =", "      %v18 =", "      \"nodal.digital_assign\"(%v15",
                    "    %v11 =", "    %v12 =", "    \"nodal.digital_assign\"(%v12"}) {
    auto at = selectedInputs.find(line);
    selectedInputs.erase(at, selectedInputs.find('\n', at) - at + 1);
  }
  for (auto name : {"input", "output"}) {
    auto at = selectedInputs.find("    \"nodal.port\"() <{direction = \"" +
                                  std::string(name) +
                                  "\", domain = @core, metadata = {}, sym_name = \"" + name + "\"");
    selectedInputs.erase(at, selectedInputs.find('\n', at) - at + 1);
  }
  const std::string symbolicScalar = "!nodal.digital<\"uint\", \"WIDTH\">";
  for (size_t at = selectedInputs.find(symbolicScalar); at != std::string::npos;
       at = selectedInputs.find(symbolicScalar))
    selectedInputs.replace(at, symbolicScalar.size(), "!nodal.uint<8>");
  selectedInputs = replace(selectedInputs, "nodal.generate\"(%v8, %v9, %v10)",
                            "nodal.generate\"(%v8, %two, %v10)");
  selectedInputs = replace(selectedInputs, "maximum_trip_count = 8", "maximum_trip_count = 2");
  auto familyStart = selectedInputs.find("    %v13 =");
  selectedInputs.insert(familyStart, R"mlir(
    %two = "nodal.const_literal"() <{metadata = {}, spelling = "2", value = 2 : i64}> : () -> i64
    %rhs0 = "nodal.const_literal"() <{metadata = {}, spelling = "17", value = 17 : i64}> : () -> !nodal.uint<8>
    %rhs1 = "nodal.const_literal"() <{metadata = {}, spelling = "34", value = 34 : i64}> : () -> !nodal.uint<8>
    "nodal.port"() <{direction = "output", domain = @core, metadata = {}, sym_name = "second", type = !nodal.uint<8>}> : () -> ()
    %second = "nodal.port_value"() <{metadata = {}, port = @second}> : () -> !nodal.uint<8>
)mlir");
  auto selectedStart = selectedInputs.find("    %v20 =");
  selectedInputs.insert(selectedStart, R"mlir(
    %input0 = "nodal.instance_port"(%v13, %v8) <{direction = "input", instance = @child, member = "child", port = "input", metadata = {}}> : (!nodal.generated_family, i64) -> !nodal.uint<8>
    %input1 = "nodal.instance_port"(%v13, %v10) <{direction = "input", instance = @child, member = "child", port = "input", metadata = {}}> : (!nodal.generated_family, i64) -> !nodal.uint<8>
    "nodal.digital_assign"(%input0, %rhs0) <{kind = "continuous", metadata = {}}> : (!nodal.uint<8>, !nodal.uint<8>) -> ()
    "nodal.digital_assign"(%input1, %rhs1) <{kind = "continuous", metadata = {}}> : (!nodal.uint<8>, !nodal.uint<8>) -> ()
    %output1 = "nodal.instance_port"(%v13, %v10) <{direction = "output", instance = @child, member = "child", port = "output", metadata = {}}> : (!nodal.generated_family, i64) -> !nodal.uint<8>
    "nodal.digital_assign"(%second, %output1) <{kind = "continuous", metadata = {}}> : (!nodal.uint<8>, !nodal.uint<8>) -> ()
)mlir");
  positive("selected-inputs-distinct-rhs-both-output-lanes-no-body-proxy", selectedInputs, 1, 1, true);
  // An exact structural capture must survive both outlining boundaries. There
  // are no observed child outputs or parent output sinks to mask an input route.
  auto guardedInputs = selectedInputs;
  for (auto prefix : {
           "    \"nodal.port\"() <{direction = \"output\", domain = @core",
           "    %second =", "    %v20 =", "    %v21 =", "    %output1 =",
           "    \"nodal.digital_assign\"(%second", "    \"nodal.digital_assign\"(%v20"}) {
    for (auto at = guardedInputs.find(prefix); at != std::string::npos;
         at = guardedInputs.find(prefix))
      guardedInputs.erase(at, guardedInputs.find('\n', at) - at + 1);
  }
  for (auto name : {"rhs0", "rhs1"}) {
    auto at = guardedInputs.find("    %" + std::string(name) + " =");
    guardedInputs.replace(
        at, guardedInputs.find('\n', at) - at + 1,
        "    \"nodal.port\"() <{direction = \"input\", domain = @core, metadata = {}, "
        "sym_name = \"" + std::string(name) + "\", type = !nodal.uint<8>}> : () -> ()\n"
        "    %" + std::string(name) + " = \"nodal.port_value\"() <{metadata = {}, port = @" +
        std::string(name) + "}> : () -> !nodal.uint<8>\n");
  }
  auto guardStart = guardedInputs.find("    %v13 =");
  auto guardEnd = guardedInputs.find("    \"nodal.local_domain_binding\"");
  auto guardedBody = guardedInputs.substr(guardStart, guardEnd - guardStart);
  for (auto name : {"v4", "v5", "v7", "v8", "v10", "two", "rhs0", "rhs1"}) {
    std::string from = "%" + std::string(name), to = "%captured_" + std::string(name);
    size_t at = 0;
    while ((at = guardedBody.find(from, at)) != std::string::npos) {
      auto end = at + from.size();
      if (end == guardedBody.size() || guardedBody[end] < '0' || guardedBody[end] > '9') {
        guardedBody.replace(at, from.size(), to);
        at += to.size();
      } else
        at = end;
    }
  }
  // Moving the family into an arm also moves every authored ownership path.
  // Rebase exact quoted identities, including the child and its override.
  const std::pair<StringRef, StringRef> guardedIdentities[] = {
      {"\"Top.family\"", "\"Top.guard.then.family\""},
      {"\"Top.i\"", "\"Top.guard.then.family.i\""}};
  for (auto identity : guardedIdentities)
    while (guardedBody.find(identity.first.str()) != std::string::npos)
      guardedBody = replace(guardedBody, identity.first, identity.second);
  const std::string guardPrefix = R"mlir(
    %guard = "nodal.static_compare"(%v9, %two) <{comparison = "eq", metadata = {semantic_path = "Top.guard.predicate"}}> : (i64, i64) -> !nodal.static_predicate
    "nodal.structural_if"(%guard, %v8, %two, %v10, %v4, %v5, %v7, %rhs0, %rhs1) <{contract_version = "structural-if-v1", region_id = "Top.guard", operandSegmentSizes = array<i32: 1, 8, 0>, metadata = {semantic_path = "Top.guard"}}> ({
    ^bb0(%captured_v8: i64, %captured_two: i64, %captured_v10: i64, %captured_v4: i64, %captured_v5: !nodal.capability<"clock">, %captured_v7: !nodal.capability<"reset">, %captured_rhs0: !nodal.uint<8>, %captured_rhs1: !nodal.uint<8>):
)mlir";
  const std::string guardSuffix = R"mlir(
      "nodal.structural_yield"() <{metadata = {semantic_path = "Top.guard.then.yield"}}> : () -> ()
    }, {
      "nodal.structural_yield"() <{metadata = {semantic_path = "Top.guard.else.yield"}}> : () -> ()
    }) : (!nodal.static_predicate, i64, i64, i64, i64, !nodal.capability<"clock">, !nodal.capability<"reset">, !nodal.uint<8>, !nodal.uint<8>) -> ()
)mlir";
  guardedInputs.replace(guardStart, guardEnd - guardStart,
                        guardPrefix + guardedBody + guardSuffix);
  auto guardedPositive = [&](StringRef name, const std::string &input, unsigned targetArm) {
    diagnostics.clear();
    auto module = parseSourceString<mlir::ModuleOp>(input, ParserConfig(&context, false));
    bool valid = module && succeeded(verify(*module)) && succeeded(lower(*module));
    circt::hw::HWModuleOp top, arm, family;
    circt::hw::InstanceOp armInstance, familyInstance, childInstance;
    Operation *branch = nullptr, *loop = nullptr, *concat = nullptr;
    unsigned modules = 0, instances = 0, branches = 0, loops = 0, concats = 0;
    if (valid) {
      module->walk([&](Operation *op) {
        valid &= op->getName().getDialectNamespace() != "nodal";
        if (auto definition = dyn_cast<circt::hw::HWModuleOp>(op)) {
          ++modules;
          if (op->getAttrOfType<StringAttr>("sym_name").getValue() == "Top")
            top = definition;
          if (op->hasAttr("nodal.generated_guard_scope"))
            arm = definition;
          if (op->hasAttr("nodal.generated_template"))
            family = definition;
        }
        if (auto instance = dyn_cast<circt::hw::InstanceOp>(op)) {
          ++instances;
          if (instance.getModuleName() == "Child")
            childInstance = instance;
          else if (op->getParentOfType<circt::hw::HWModuleOp>()->hasAttr(
                       "nodal.generated_guard_scope"))
            familyInstance = instance;
          else
            armInstance = instance;
        }
        if (op->getName().getStringRef() == "sv.generate.case") {
          branch = op;
          ++branches;
        }
        if (op->getName().getStringRef() == "sv.generate.for") {
          loop = op;
          ++loops;
        }
        if (op->getName().getStringRef() == "comb.concat") {
          concat = op;
          ++concats;
        }
      });
      valid &= modules == 4 && instances == 3 && branches == 1 && loops == 1 && concats == 1 &&
               top && arm && family && armInstance && familyInstance && childInstance;
      if (valid) {
        auto condition = branch->getAttrOfType<circt::hw::ParamDeclRefAttr>("cond");
        auto patterns = branch->getAttrOfType<ArrayAttr>("casePatterns");
        valid &= condition && condition.getName().getValue() == "LANES" && patterns && patterns.size() == 2 &&
                 isa<IntegerAttr>(patterns[0]) && cast<IntegerAttr>(patterns[0]).getInt() == 2 &&
                 branch->getParentOfType<circt::hw::HWModuleOp>() == top &&
                 armInstance->getBlock() == &branch->getRegion(targetArm).front() &&
                 armInstance.getModuleName() == arm->getAttrOfType<StringAttr>("sym_name").getValue() &&
                 familyInstance.getModuleName() == family->getAttrOfType<StringAttr>("sym_name").getValue() &&
                 familyInstance->getParentOfType<circt::hw::HWModuleOp>() == arm &&
                 childInstance->getParentOp() == family &&
                 loop->getParentOfType<circt::hw::HWModuleOp>() == arm &&
                 concat->getParentOfType<circt::hw::HWModuleOp>() == arm &&
                 concat->getNumOperands() == 2 && concat->getResult(0).getType().isInteger(16) &&
                 armInstance->getNumResults() == 0 && familyInstance->getNumResults() == 0 &&
                 familyInstance->getNumOperands() > 0 && childInstance->getNumOperands() > 0;
        branch->getRegion(1 - targetArm).walk([&](Operation *op) {
          valid &= !isa<circt::hw::InstanceOp>(op) &&
                   op->getName().getStringRef() != "sv.generate.for";
        });
        if (valid) {
          for (unsigned lane = 0; lane != 2; ++lane) {
            auto capture = dyn_cast<BlockArgument>(concat->getOperand(1 - lane));
            valid &= capture && capture.getOwner() == arm.getBodyBlock() &&
                     capture.getArgNumber() < armInstance->getNumOperands();
            if (!valid)
              break;
            valid &= top.getBodyBlock()->getNumArguments() == 4 &&
                     armInstance->getOperand(capture.getArgNumber()) ==
                         top.getBodyBlock()->getArgument(lane + 2);
          }
          auto slice = familyInstance->getOperands().back().getDefiningOp();
          auto childInput = dyn_cast<BlockArgument>(childInstance->getOperand(0));
          valid &= slice && slice->getName().getStringRef() == "sv.verbatim.expr" &&
                   slice->getNumOperands() == 3 && slice->getOperand(0) == concat->getResult(0) &&
                   slice->getOperand(1) == loop->getRegion(0).front().getArgument(0) &&
                   childInput && childInput.getOwner() == family.getBodyBlock() &&
                   childInput.getType().isInteger(8) &&
                   childInput.getArgNumber() + 1 == family.getBodyBlock()->getNumArguments();
        }
        valid &= succeeded(verify(*module));
      }
    }
    report(name, valid);
  };
  guardedPositive("guarded-selected-input-exact-captured-rhs-through-two-helpers", guardedInputs, 0);
  guardedPositive("guarded-selected-input-negated-predicate-keeps-actual-arm",
                  replace(guardedInputs, "comparison = \"eq\"", "comparison = \"ne\""), 1);
  negative("guarded-selected-input-raw-parent-rhs-is-not-authenticated-capture",
           replace(guardedInputs, "(%input0, %captured_rhs0)", "(%input0, %rhs0)"), "capture");
  auto reversedInputs = selectedInputs;
  auto firstWrite = reversedInputs.find("    \"nodal.digital_assign\"(%input0");
  auto secondWrite = reversedInputs.find("    \"nodal.digital_assign\"(%input1");
  auto firstLine =
      reversedInputs.substr(firstWrite, reversedInputs.find('\n', firstWrite) - firstWrite + 1);
  auto secondLine =
      reversedInputs.substr(secondWrite, reversedInputs.find('\n', secondWrite) - secondWrite + 1);
  reversedInputs.replace(firstWrite, firstLine.size() + secondLine.size(), secondLine + firstLine);
  positive("selected-input-writer-text-order-does-not-change-lane-order", reversedInputs, 1, 1, true);
  auto steppedInputs = selectedInputs;
  steppedInputs.insert(steppedInputs.find("    %v13 ="), R"mlir(
    %lower = "nodal.const_literal"() <{metadata = {}, spelling = "3", value = 3 : i64}> : () -> i64
    %upper = "nodal.const_literal"() <{metadata = {}, spelling = "7", value = 7 : i64}> : () -> i64
)mlir");
  steppedInputs = replace(steppedInputs, "nodal.generate\"(%v8, %two, %v10)",
                          "nodal.generate\"(%lower, %upper, %two)");
  // Selected operands are ordinals 0/1, not authored coordinates 3/5.
  // Only the loop and its physical slice translate to the authored domain.
  positive("selected-input-nonzero-domain-normalizes-lower-and-step", steppedInputs, 1, 1,
           true, true);
  auto incompleteInputs = selectedInputs;
  secondWrite = incompleteInputs.find("    \"nodal.digital_assign\"(%input1");
  incompleteInputs.erase(secondWrite, incompleteInputs.find('\n', secondWrite) - secondWrite + 1);
  negative("selected-input-incomplete-coverage-even-with-observed-output", incompleteInputs,
           "selected input");
  auto unobservedIncomplete = incompleteInputs;
  for (auto line : {"    %output1 =", "    \"nodal.digital_assign\"(%second", "    %second =",
                    "    \"nodal.port\"() <{direction = \"output\", domain = @core, metadata = {}, sym_name = \"second\""}) {
    auto at = unobservedIncomplete.find(line);
    unobservedIncomplete.erase(at, unobservedIncomplete.find('\n', at) - at + 1);
  }
  negative("selected-input-unobserved-lane-still-requires-its-writer", unobservedIncomplete,
           "selected input");
  auto selectedInput = replace(selected, "direction = \"output\", instance = @child, member",
                               "direction = \"input\", instance = @child, member");
  selectedInput = replace(selectedInput, "member = \"child\", port = \"output\"",
                          "member = \"child\", port = \"input\"");
  negative("selected-input-view-never-becomes-an-output-carrier", selectedInput,
           "selected input");
  // Two original Interface leaves remain distinct logical ports. No scalar
  // input declarations or body-local proxy values stand in for either leaf.
  auto selectedInterface = selectedInputs;
  auto eraseLine = [](std::string &text, StringRef prefix) {
    auto at = text.find(prefix.str());
    if (at == std::string::npos)
      llvm::report_fatal_error("missing selected Interface fixture line");
    text.erase(at, text.find('\n', at) - at + 1);
  };
  for (auto line : {"    %input0 =", "    %input1 =",
                    "    \"nodal.digital_assign\"(%input0",
                    "    \"nodal.digital_assign\"(%input1",
                    "    \"nodal.port\"() <{direction = \"input\", domain = @required"})
    eraseLine(selectedInterface, line);
  selectedInterface = replace(selectedInterface,
      "%v7 = \"nodal.port_value\"() <{metadata = {}, port = @input}>",
      "%v7 = \"nodal.member_access\"() <{instance = @endpoint, path = \"small\", "
      "metadata = {interface_abi_version = 1 : i64, role = \"sink\"}}>");
  auto childDefinition = selectedInterface.find("sym_name = \"Child\"");
  auto childInputRead = selectedInterface.find("    %v7 =", childDefinition);
  selectedInterface.insert(childInputRead, R"mlir(
    "nodal.interface"() <{sym_name = "Bus", metadata = {interface_abi_version = 1 : i64, semantic_identity = "SelectedInput.Bus"}}> ({
      "nodal.interface_role"() <{sym_name = "sink", kind = "explicit", metadata = {interface_abi_version = 1 : i64, access = [{path = "small", access = "in", leaf_role = "sink", nested_roles = [], nested_interfaces = []}, {path = "wide", access = "in", leaf_role = "sink", nested_roles = [], nested_interfaces = []}]}}> : () -> ()
      "nodal.interface_member"() <{sym_name = "small", type = !nodal.uint<8>, protocol = "plain", roles = ["sink"], metadata = {interface_abi_version = 1 : i64}}> : () -> ()
      "nodal.interface_member"() <{sym_name = "wide", type = !nodal.uint<16>, protocol = "plain", roles = ["sink"], metadata = {interface_abi_version = 1 : i64}}> : () -> ()
    }) : () -> ()
    "nodal.interface_instance"() <{sym_name = "endpoint", definition = @Bus, role = "sink", metadata = {interface_abi_version = 1 : i64, dimensions = [], domain = @required, semantic_path = "Child.endpoint"}}> : () -> ()
    "nodal.interface_abi"() <{logical_path = "Child.endpoint", layout_policy = "structural", members = [{path = "small", type = !nodal.uint<8>, role = "sink", access = "in", nested_roles = [], nested_interfaces = []}, {path = "wide", type = !nodal.uint<16>, role = "sink", access = "in", nested_roles = [], nested_interfaces = []}], metadata = {interface_abi_version = 1 : i64, instance = @endpoint}}> : () -> ()
    "nodal.port"() <{direction = "output", domain = @required, metadata = {}, sym_name = "wideOutput", type = !nodal.uint<16>}> : () -> ()
    %wideOut = "nodal.port_value"() <{metadata = {}, port = @wideOutput}> : () -> !nodal.uint<16>
    %wideIn = "nodal.member_access"() <{instance = @endpoint, path = "wide", metadata = {interface_abi_version = 1 : i64, role = "sink"}}> : () -> !nodal.uint<16>
    "nodal.digital_assign"(%wideOut, %wideIn) <{kind = "continuous", metadata = {}}> : (!nodal.uint<16>, !nodal.uint<16>) -> ()
)mlir");
  selectedInterface.insert(selectedInterface.find("    %v20 ="), R"mlir(
    %wideRhs0 = "nodal.const_literal"() <{metadata = {}, spelling = "257", value = 257 : i64}> : () -> !nodal.uint<16>
    %wideRhs1 = "nodal.const_literal"() <{metadata = {}, spelling = "514", value = 514 : i64}> : () -> !nodal.uint<16>
    "nodal.interface_boundary"(%v13, %v8) <{sym_name = "selected0", instance = @child, endpoint = "endpoint", member = "child", metadata = {interface_abi_version = 1 : i64, dimensions = [], semantic_path = "Top.selected0", actual_types = [{path = "small", type = !nodal.uint<8>}, {path = "wide", type = !nodal.uint<16>}]}}> : (!nodal.generated_family, i64) -> ()
    "nodal.interface_boundary"(%v13, %v10) <{sym_name = "selected1", instance = @child, endpoint = "endpoint", member = "child", metadata = {interface_abi_version = 1 : i64, dimensions = [], semantic_path = "Top.selected1", actual_types = [{path = "small", type = !nodal.uint<8>}, {path = "wide", type = !nodal.uint<16>}]}}> : (!nodal.generated_family, i64) -> ()
    %small0 = "nodal.member_access"() <{instance = @selected0, path = "small", metadata = {interface_abi_version = 1 : i64, role = "sink"}}> : () -> !nodal.uint<8>
    %small1 = "nodal.member_access"() <{instance = @selected1, path = "small", metadata = {interface_abi_version = 1 : i64, role = "sink"}}> : () -> !nodal.uint<8>
    %wide0 = "nodal.member_access"() <{instance = @selected0, path = "wide", metadata = {interface_abi_version = 1 : i64, role = "sink"}}> : () -> !nodal.uint<16>
    %wide1 = "nodal.member_access"() <{instance = @selected1, path = "wide", metadata = {interface_abi_version = 1 : i64, role = "sink"}}> : () -> !nodal.uint<16>
    "nodal.interface_drive"(%small1, %rhs1) <{metadata = {interface_abi_version = 1 : i64}}> : (!nodal.uint<8>, !nodal.uint<8>) -> ()
    "nodal.interface_drive"(%wide0, %wideRhs0) <{metadata = {interface_abi_version = 1 : i64}}> : (!nodal.uint<16>, !nodal.uint<16>) -> ()
    "nodal.interface_drive"(%small0, %rhs0) <{metadata = {interface_abi_version = 1 : i64}}> : (!nodal.uint<8>, !nodal.uint<8>) -> ()
    "nodal.interface_drive"(%wide1, %wideRhs1) <{metadata = {interface_abi_version = 1 : i64}}> : (!nodal.uint<16>, !nodal.uint<16>) -> ()
    "nodal.port"() <{direction = "output", domain = @core, metadata = {}, sym_name = "wideFirst", type = !nodal.uint<16>}> : () -> ()
    "nodal.port"() <{direction = "output", domain = @core, metadata = {}, sym_name = "wideSecond", type = !nodal.uint<16>}> : () -> ()
    %wideFirst = "nodal.port_value"() <{metadata = {}, port = @wideFirst}> : () -> !nodal.uint<16>
    %wideSecond = "nodal.port_value"() <{metadata = {}, port = @wideSecond}> : () -> !nodal.uint<16>
    %wideOutput0 = "nodal.instance_port"(%v13, %v8) <{direction = "output", instance = @child, member = "child", port = "wideOutput", metadata = {}}> : (!nodal.generated_family, i64) -> !nodal.uint<16>
    %wideOutput1 = "nodal.instance_port"(%v13, %v10) <{direction = "output", instance = @child, member = "child", port = "wideOutput", metadata = {}}> : (!nodal.generated_family, i64) -> !nodal.uint<16>
    "nodal.digital_assign"(%wideFirst, %wideOutput0) <{kind = "continuous", metadata = {}}> : (!nodal.uint<16>, !nodal.uint<16>) -> ()
    "nodal.digital_assign"(%wideSecond, %wideOutput1) <{kind = "continuous", metadata = {}}> : (!nodal.uint<16>, !nodal.uint<16>) -> ()
)mlir");
  positive("selected-interface-two-widths-two-lanes-exact-rhs", selectedInterface,
           2, 2, false, false, true);
  // Observation consumes no SSA input carrier. Both the writer's member and
  // a separate member alias must retain the exact certified boundary selection.
  const std::string observeSmall =
      "    \"nodal.interface_observe\"(%small0) <{metadata = {interface_abi_version = 1 : i64}}> : (!nodal.uint<8>) -> ()\n";
  auto observedInterface = selectedInterface;
  observedInterface.insert(observedInterface.find("    %v20 ="), observeSmall);
  positive("selected-interface-certified-input-observe", observedInterface,
           2, 2, false, false, true);
  const std::string observedAlias =
      "    %observedAlias = \"nodal.member_access\"() <{instance = @selected0, path = \"small\", metadata = {interface_abi_version = 1 : i64, role = \"sink\"}}> : () -> !nodal.uint<8>\n";
  auto aliasObservedInterface = selectedInterface;
  aliasObservedInterface.insert(aliasObservedInterface.find("    %v20 ="),
      observedAlias + replace(observeSmall, "%small0", "%observedAlias"));
  positive("selected-interface-certified-input-alias-observe", aliasObservedInterface,
           2, 2, false, false, true);
  negative("selected-interface-observe-foreign-endpoint-identity",
      replace(aliasObservedInterface, observedAlias,
              replace(observedAlias, "instance = @selected0", "instance = @foreignEndpoint")),
      "NODAL-INTERFACE-054-001: typed member access must resolve an actual fully typed local instance");
  negative("selected-interface-observe-forged-role-permission",
      replace(aliasObservedInterface, observedAlias,
              replace(observedAlias, "role = \"sink\"", "role = \"drive\"")), "role");
  auto unmatchedObservedInterface = aliasObservedInterface;
  auto boundaryAt = unmatchedObservedInterface.find("    \"nodal.interface_boundary\"(%v13, %v8)");
  auto boundaryLine = unmatchedObservedInterface.substr(boundaryAt,
      unmatchedObservedInterface.find('\n', boundaryAt) - boundaryAt + 1);
  boundaryLine = replace(boundaryLine, "sym_name = \"selected0\"",
                         "sym_name = \"observedBoundary\"");
  boundaryLine = replace(boundaryLine, "Top.selected0", "Top.observedBoundary");
  unmatchedObservedInterface.insert(boundaryAt, boundaryLine);
  unmatchedObservedInterface = replace(unmatchedObservedInterface, observedAlias,
      replace(observedAlias, "instance = @selected0", "instance = @observedBoundary"));
  negative("selected-interface-observe-duplicate-boundary-identity",
      unmatchedObservedInterface, "duplicate selected Interface must reuse its physical identity");
  auto incompleteObservedInterface = observedInterface;
  eraseLine(incompleteObservedInterface, "    \"nodal.interface_drive\"(%wide1");
  negative("selected-interface-observe-does-not-waive-complete-coverage",
      incompleteObservedInterface, "selected");
  auto missingInterfaceLane = selectedInterface;
  eraseLine(missingInterfaceLane, "    \"nodal.interface_drive\"(%wide1");
  negative("selected-interface-one-leaf-missing-lane", missingInterfaceLane, "selected");
  auto duplicateInterfaceLane = selectedInterface;
  auto effect = duplicateInterfaceLane.find("    \"nodal.interface_drive\"(%small0");
  duplicateInterfaceLane.insert(effect, duplicateInterfaceLane.substr(
      effect, duplicateInterfaceLane.find('\n', effect) - effect + 1));
  negative("selected-interface-duplicate-lane-writer", duplicateInterfaceLane,
           "multiple authored drivers");
  negative("selected-interface-wrong-original-endpoint",
           replace(selectedInterface, "endpoint = \"endpoint\", member = \"child\"",
                   "endpoint = \"missing\", member = \"child\""), "endpoint");
  auto readInterfaceInput = selectedInterface;
  readInterfaceInput = replace(readInterfaceInput,
      "\"nodal.interface_drive\"(%small0, %rhs0)",
      "\"nodal.interface_drive\"(%small0, %small1)");
  negative("selected-interface-destination-cannot-be-rhs", readInterfaceInput, "selected");
  auto nestedPositive = [&](StringRef name, const std::string &source, bool strided = false) {
    diagnostics.clear();
    auto module = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    bool valid = module && succeeded(verify(*module)) && succeeded(lower(*module));
    circt::hw::HWModuleOp top, outer, inner, child;
    Operation *outerInstance = nullptr, *innerInstance = nullptr, *childInstance = nullptr;
    Operation *outerLoop = nullptr, *innerLoop = nullptr;
    unsigned definitions = 0, helpers = 0, loops = 0, instances = 0;
    if (valid) {
      module->walk([&](Operation *op) {
        valid &= op->getName().getDialectNamespace() != "nodal";
        if (auto definition = dyn_cast<circt::hw::HWModuleOp>(op)) {
          ++definitions;
          auto family = op->getAttrOfType<StringAttr>("nodal.generated_template");
          if (family) {
            ++helpers;
            if (family.getValue() == "Top.outer")
              outer = definition;
            if (family.getValue() == "Top.outer.inner")
              inner = definition;
          } else {
            auto name = op->getAttrOfType<StringAttr>("sym_name");
            if (name && name.getValue() == "Top")
              top = definition;
            if (name && name.getValue() == "Child")
              child = definition;
          }
        }
        if (op->getName().getStringRef() == "sv.generate.for") {
          ++loops;
          auto owner = op->getParentOfType<circt::hw::HWModuleOp>();
          auto family = owner->getAttrOfType<StringAttr>("nodal.generated_template");
          if (!family)
            outerLoop = op;
          else if (family.getValue() == "Top.outer")
            innerLoop = op;
        }
        if (auto instance = dyn_cast<circt::hw::InstanceOp>(op)) {
          ++instances;
          auto owner = op->getParentOfType<circt::hw::HWModuleOp>();
          auto family = owner->getAttrOfType<StringAttr>("nodal.generated_template");
          if (instance.getModuleName() == "Child")
            childInstance = op;
          else if (!family)
            outerInstance = op;
          else if (family.getValue() == "Top.outer")
            innerInstance = op;
        }
      });
      valid &= definitions == 4 && helpers == 2 && loops == 2 && instances == 3 && top && outer &&
               inner && child && outerInstance && innerInstance && childInstance && outerLoop &&
               innerLoop;
    }
    if (valid) {
      auto integerValue = [](Value value, int64_t expected) {
        auto op = value.getDefiningOp();
        auto actual = op ? op->getAttrOfType<IntegerAttr>("value") : IntegerAttr();
        return actual && actual.getInt() == expected;
      };
      // No simulation or alternate evaluator: inspect the actual source-to-bus,
      // per-axis part-select and child-argument edges emitted by the real pass.
      auto sliceMatches = [&](Operation *slice, Value carrier, Operation *loop, unsigned width,
                              int64_t lower, int64_t step) {
        bool normalized = lower != 0 || step != 1;
        if (!slice || slice->getName().getStringRef() != "sv.verbatim.expr" ||
            slice->getNumOperands() != (normalized ? 5u : 3u) || slice->getOperand(0) != carrier ||
            slice->getOperand(1) != loop->getRegion(0).front().getArgument(0) ||
            !integerValue(slice->getOperand(2), width) ||
            (normalized && (!integerValue(slice->getOperand(3), lower) ||
                            !integerValue(slice->getOperand(4), step))))
          return false;
        auto format = slice->getAttrOfType<StringAttr>("format_string");
        return format && format.getValue() ==
                             (normalized ? "{{0}}[(({{1}} - {{3}}) / {{4}}) * {{2}} +: {{2}}]"
                                         : "{{0}}[{{1}} * {{2}} +: {{2}}]");
      };
      auto *topBody = top.getBodyBlock();
      auto *outerBody = outer.getBodyBlock();
      auto *innerBody = inner.getBodyBlock();
      valid &= topBody->getNumArguments() == 5 && outerBody->getNumArguments() >= 1 &&
               innerBody->getNumArguments() >= 1 && outerInstance->getNumResults() == 1 &&
               innerInstance->getNumResults() == 1 && childInstance->getNumResults() == 1 &&
               outerInstance->getNumOperands() >= 1 && innerInstance->getNumOperands() >= 1 &&
               childInstance->getNumOperands() >= 1;
      if (!valid) {
        report(name, false);
        return;
      }
      Value rowInput = outerBody->getArguments().back();
      Value scalarInput = innerBody->getArguments().back();
      auto *rowSlice = outerInstance->getOperands().back().getDefiningOp();
      auto *scalarSlice = innerInstance->getOperands().back().getDefiningOp();
      valid &= rowInput.getType().isInteger(2) && scalarInput.getType().isInteger(1) &&
               childInstance->getOperand(0) == scalarInput && rowSlice &&
               rowSlice->getNumOperands() == (strided ? 5u : 3u);
      Value bus = rowSlice && rowSlice->getNumOperands() ? rowSlice->getOperand(0) : Value();
      valid &= bus && bus.getType().isInteger(4);
      if (valid) {
        // Three actual left-associated concatenations, MSB first. Ordinal tuple
        // 00 is the LSB; both 00 versus 10 and 01 versus 10 remain distinguishable.
        Value concat = bus;
        for (unsigned i = 0; i < 3; ++i) {
          auto operation = concat.getDefiningOp();
          valid &= operation && operation->getName().getStringRef() == "comb.concat" &&
                   operation->getNumOperands() == 2;
          if (!valid)
            break;
          valid &= operation->getOperand(1) == topBody->getArgument(i + 1);
          concat = operation->getOperand(0);
        }
        valid &=
            concat == topBody->getArgument(4) &&
            sliceMatches(rowSlice, bus, outerLoop, 2, strided ? 3 : 0, strided ? 2 : 1) &&
            sliceMatches(scalarSlice, rowInput, innerLoop, 1, strided ? 5 : 0, strided ? 3 : 1);
      }
      valid &= innerBody->getTerminator()->getNumOperands() == 1 &&
               innerBody->getTerminator()->getOperand(0) == childInstance->getResult(0) &&
               outerBody->getTerminator()->getNumOperands() == 1 &&
               outerBody->getTerminator()->getOperand(0).getType().isInteger(2) &&
               topBody->getTerminator()->getNumOperands() == 4;
      Value outputBus;
      if (valid)
        for (unsigned i = 0; i < 4; ++i) {
          auto output = topBody->getTerminator()->getOperand(i).getDefiningOp();
          auto prefixes =
              output ? output->getAttrOfType<ArrayAttr>("nodal.generated_selection") : ArrayAttr();
          valid &= output && output->getName().getStringRef() == "sv.verbatim.expr" &&
                   output->getNumOperands() == 3 && output->getResult(0).getType().isInteger(1) &&
                   prefixes && prefixes.size() == 2;
          if (!valid)
            break;
          if (!outputBus)
            outputBus = output->getOperand(0);
          valid &= output->getOperand(0) == outputBus && outputBus.getType().isInteger(4) &&
                   integerValue(output->getOperand(1), i) && integerValue(output->getOperand(2), 1);
          for (unsigned axis = 0; axis < 2; ++axis) {
            auto prefix = dyn_cast<DictionaryAttr>(prefixes[axis]);
            auto family = prefix ? prefix.getAs<StringAttr>("family") : StringAttr();
            auto ordinal = prefix ? prefix.getAs<IntegerAttr>("ordinal") : IntegerAttr();
            auto coordinate = prefix ? prefix.getAs<IntegerAttr>("coordinate") : IntegerAttr();
            auto stride = prefix ? prefix.getAs<IntegerAttr>("stride_bits") : IntegerAttr();
            int64_t expectedOrdinal = axis ? i % 2 : i / 2;
            int64_t expectedCoordinate =
                strided ? (axis ? 5 + 3 * expectedOrdinal : 3 + 2 * expectedOrdinal)
                        : expectedOrdinal;
            valid &= family && family.getValue() == (axis ? "Top.outer.inner" : "Top.outer") &&
                     ordinal && ordinal.getInt() == expectedOrdinal && coordinate &&
                     coordinate.getInt() == expectedCoordinate && stride &&
                     stride.getInt() == (axis ? 1 : 2);
          }
        }
      auto outputConnected = [&](Value read, Operation *instance, Operation *loop, unsigned width,
                                 int64_t lower, int64_t step) {
        auto readOp = read ? read.getDefiningOp() : nullptr;
        if (!readOp || readOp->getName().getStringRef() != "sv.read_inout" ||
            readOp->getNumOperands() != 1)
          return false;
        unsigned connections = 0;
        loop->walk([&](Operation *op) {
          if (op->getName().getStringRef() != "sv.assign" || op->getNumOperands() != 2 ||
              op->getOperand(1) != instance->getResult(0))
            return;
          connections += sliceMatches(op->getOperand(0).getDefiningOp(), readOp->getOperand(0),
                                      loop, width, lower, step);
        });
        return connections == 1;
      };
      if (valid)
        valid &= outputConnected(outputBus, outerInstance, outerLoop, 2, strided ? 3 : 0,
                                 strided ? 2 : 1) &&
                 outputConnected(outerBody->getTerminator()->getOperand(0), innerInstance,
                                 innerLoop, 1, strided ? 5 : 0, strided ? 3 : 1) &&
                 succeeded(verify(*module));
    }
    report(name, valid);
  };
  const auto nestedInputs = nestedSelectedInputFixture();
  nestedPositive("nested-selected-input-two-real-loops-four-exact-rhs-output-tuples", nestedInputs);
  nestedPositive("nested-selected-input-reverse-writer-order-same-physical-routing",
                 nestedSelectedInputFixture(true));
  nestedPositive("nested-selected-input-two-strided-axes-exact-row-and-scalar-slices",
                 nestedSelectedInputFixture(false, true), true);
  const std::string nestedWriter =
      "\"nodal.digital_assign\"(%input10, %x10) <{kind = \"continuous\", metadata = {}}> "
      ": (!nodal.bits<1>, !nodal.bits<1>) -> ()\n";
  negative("nested-selected-input-missing-tuple-is-transactional",
           replace(nestedInputs, nestedWriter, ""), "selected input");
  negative("nested-selected-input-equal-count-duplicate-omission-is-transactional",
           replace(nestedInputs, "%input10, %x10", "%input00, %x10"), "overlapping");
  negative("nested-selected-input-wrong-child-is-transactional",
           replace(nestedInputs, "instance = @child, member = \"child\", port = \"input\"",
                   "instance = @missing, member = \"child\", port = \"input\""),
           "selected");
  negative("nested-selected-input-extra-outer-executable-is-transactional",
           replace(nestedInputs, "%outer.inner_lo =",
                   "%extra = \"nodal.digital_expr\"(%x00, %x01) <{operator_name = \"or\", "
                   "metadata = {}}> : (!nodal.bits<1>, !nodal.bits<1>) -> !nodal.bits<1>\n"
                   "%outer.inner_lo ="),
           "selected input");
  if (emitSelectedInput && !emittedSelectedInput)
    report("selected-input-representative-was-not-emitted", false);
  auto &summary = emitSelectedInput ? llvm::errs() : llvm::outs();
  summary << "GENERATED_OCCURRENCE_SUMMARY cases=" << cases << " failures=" << failures << '\n';
  return failures != 0;
}
