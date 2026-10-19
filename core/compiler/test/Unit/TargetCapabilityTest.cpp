#include "circt/Dialect/HW/HWDialect.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "mlir/Pass/PassManager.h"
#include "mlir/Pass/PassRegistry.h"
#include "nodal/Backend/AnalogVerticalSlice.h"
#include "nodal/Backend/Backend.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"
#include "nodal/Dialect/Nodal/TargetCapabilities.h"
#include "nodal/Support/BackendCapabilityRegistry.h"

#include "llvm/Support/raw_ostream.h"

#include <string>

namespace {

constexpr llvm::StringLiteral analogSource = R"mlir(
module attributes {nodal.target.profile = "analog"} {
  "nodal.module"() <{sym_name = "AnalogControl", metadata = {root = true}}> ({
    %p = "nodal.terminal"() <{name = "p", metadata = {declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{name = "n", metadata = {declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
    %b = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() <{metadata = {}}> ({
      %one = "nodal.real_literal"() <{value = 1.0 : f64, metadata = {}}> : () -> f64
      %zero = "nodal.real_literal"() <{value = 0.0 : f64, metadata = {}}> : () -> f64
      %condition = "nodal.analog_compare"(%one, %zero) <{predicate = "gt", metadata = {}}> : (f64, f64) -> i1
      %selected = "nodal.analog_select"(%condition, %one, %zero) <{metadata = {}}> : (i1, f64, f64) -> f64
      "nodal.contribute"(%b, %selected) <{kind = "flow", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral digitalConstant = R"mlir(
%bits = "nodal.constant"() <{value = 0 : i64, metadata = {}}> : () -> !nodal.uint<8> loc("capability.scala":17:3)
)mlir";

std::string definition(llvm::StringRef name, llvm::StringRef body) {
  return "\"nodal.module\"() <{sym_name = \"" + name.str() + "\", metadata = {}}> ({\n" +
         body.str() + "\n}) : () -> ()\n";
}

std::string design(llvm::StringRef definitions) {
  return "module attributes {nodal.target.profile = \"analog\"} {\n" + definitions.str() + "}\n";
}

std::string instance(llvm::StringRef name, llvm::StringRef target) {
  return "\"nodal.instance\"() <{sym_name = \"" + name.str() + "\", module = @" + target.str() +
         ", parameter_bindings = {}, domain_bindings = {}, metadata = {}}> : () -> ()\n";
}

std::string generated(llvm::StringRef body) {
  return "\"nodal.generate\"() <{induction = \"i\", lower = 0 : i64, upper = 2 : i64, "
         "step = 1 : i64, metadata = {maximum_trip_count = 2 : i64}}> ({\n" +
         body.str() + "\n}) : () -> ()\n";
}

std::string print(mlir::ModuleOp module) {
  std::string result;
  llvm::raw_string_ostream stream(result);
  module.print(stream);
  return result;
}

struct Harness {
  mlir::DialectRegistry registry;
  mlir::MLIRContext context;
  std::string diagnostic;
  unsigned diagnosticCount = 0;
  unsigned cases = 0;
  mlir::ScopedDiagnosticHandler handler;

  Harness()
      : context(), handler(&context, [&](mlir::Diagnostic &message) {
          llvm::raw_string_ostream stream(diagnostic);
          message.print(stream);
          stream << '\n';
          ++diagnosticCount;
          return mlir::success();
        }) {
    registry.insert<nodal::NodalDialect, circt::hw::HWDialect>();
    context.appendDialectRegistry(registry);
  }

  void clear() {
    diagnostic.clear();
    diagnosticCount = 0;
  }

  bool require(bool condition, llvm::StringRef label) {
    if (!condition)
      llvm::errs() << "NODAL-TARGET-CAPABILITY-TEST: " << label << '\n' << diagnostic;
    return condition;
  }

  mlir::OwningOpRef<mlir::ModuleOp> parse(llvm::StringRef source) {
    clear();
    auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    require(static_cast<bool>(module), "native witness must parse and verify");
    return module;
  }

  bool gate(mlir::ModuleOp module, llvm::StringRef profile, bool accepted,
            llvm::StringRef offendingOperation = {}) {
    clear();
    const auto before = print(module);
    const bool result = mlir::succeeded(nodal::verifyTargetLanguageCapabilities(module, profile));
    ++cases;
    if (!require(result == accepted && print(module) == before,
                 "language gate result or read-only contract changed"))
      return false;
    if (accepted)
      return require(diagnostic.empty(), "successful language gate emitted a diagnostic");
    return require(
        diagnosticCount == 1 &&
            llvm::StringRef(diagnostic).contains("NODAL-VERIFY-CAPABILITY-006") &&
            llvm::StringRef(diagnostic).contains("target language profile 'verilog-a'") &&
            llvm::StringRef(diagnostic).contains(offendingOperation),
        "digital rejection lost its one stable language diagnostic");
  }
};

bool testRegistry(Harness &test) {
  const auto *direct = nodal::lookupBackendOperation("nodal.real_literal");
  const auto *guarded = nodal::lookupBackendOperation("nodal.analog_procedure");
  const auto *digital = nodal::lookupBackendOperation("nodal.hardware_loop");
  const auto *sourceEquation = nodal::lookupBackendOperation("nodal.analog_equation");
  const auto *portFlow = nodal::lookupBackendOperation("nodal.port_flow_access");
  ++test.cases;
  return test.require(
      direct && guarded && digital && sourceEquation && portFlow &&
          direct->admission == nodal::BackendOperationAdmission::Direct &&
          guarded->admission == nodal::BackendOperationAdmission::Guarded &&
          digital->domain == nodal::BackendOperationDomain::Digital &&
          sourceEquation->admission == nodal::BackendOperationAdmission::Unsupported &&
          portFlow->admission == nodal::BackendOperationAdmission::Direct &&
          nodal::isDirectBackendOperation("nodal.real_literal") &&
          !nodal::isDirectBackendOperation("nodal.analog_procedure") &&
          !nodal::lookupBackendOperation("nodal.unregistered_future_operation") &&
          !nodal::isDirectBackendOperation("nodal.unregistered_future_operation"),
      "shared catalog lost direct, guarded, digital, or fail-closed unknown classification");
}

bool testAnalogAndBuiltinControls(Harness &test) {
  auto analog = test.parse(analogSource);
  if (!analog)
    return false;
  for (auto profile : {"verilog-a", "verilog-ams"})
    if (!test.gate(*analog, profile, true))
      return false;

  // Builtin control/index types are not digital hardware merely because they
  // are integer-like. The analog fixture also exercises a real i1 comparison.
  auto controls = test.parse(design(definition("Controls", R"mlir(
%index = "builtin.unrealized_conversion_cast"() : () -> index
%predicate = "builtin.unrealized_conversion_cast"() : () -> i1
)mlir")));
  if (!controls || !test.gate(*controls, "verilog-a", true))
    return false;

  const auto original = print(*analog);
  for (auto kind : {nodal::BackendKind::VerilogA, nodal::BackendKind::VerilogAMS}) {
    std::string previous;
    for (unsigned repeat = 0; repeat != 2; ++repeat) {
      test.clear();
      std::string output;
      llvm::raw_string_ostream stream(output);
      const auto result = nodal::emitBackend(*analog, kind, stream);
      stream.flush();
      ++test.cases;
      if (!test.require(mlir::succeeded(result) && print(*analog) == original &&
                            llvm::StringRef(output).contains("module AnalogControl") &&
                            llvm::StringRef(output).contains("<+") &&
                            (repeat == 0 || previous == output),
                        "analog VA/AMS emission lost input preservation or deterministic output"))
        return false;
      previous = output;
    }
  }
  return true;
}

bool testStaticReductionTargetBoundary(Harness &test) {
  for (auto opcode : {"ceil_log2", "choose_zero"}) {
    const bool log = llvm::StringRef(opcode) == "ceil_log2";
    const std::string body =
        "%zero = \"nodal.const_literal\"() <{metadata = {}, spelling = \"0\", value = 0 : i64}> : "
        "() -> i64\n"
        "%four = \"nodal.const_literal\"() <{metadata = {}, spelling = \"4\", value = 4 : i64}> : "
        "() -> i64\n"
        "%eight = \"nodal.const_literal\"() <{metadata = {}, spelling = \"8\", value = 8 : i64}> : "
        "() -> i64\n"
        "%value = \"nodal.const_expr\"(" +
        std::string(log ? "%four" : "%zero, %four, %eight") +
        ") <{metadata = {}, operator_name = \"" + opcode + "\"}> : (" +
        (log ? "i64" : "i64, i64, i64") +
        ") -> i64\n"
        "\"nodal.parameter\"() <{classification = \"ordinary\", default_value = " +
        (log ? "2" : "4") +
        " : i64, metadata = {}, parameter_kind = \"integer\", sym_name = \"WIDTH\", "
        "type = i64, variability = \"fixed\"}> : () -> ()\n"
        "\"nodal.parameter_value\"(%value) <{metadata = {}, parameter = @WIDTH}> : (i64) -> ()\n";
    auto module = test.parse(design(definition("StaticPrimitive", body)));
    if (!module || mlir::failed(nodal::verifyParameterModel(*module)))
      return test.require(false, "static primitive target witness must be semantically valid IR");
    mlir::Value value;
    module->walk([&](nodal::ConstExprOp op) { value = op->getResult(0); });
    if (!test.require(mlir::succeeded(nodal::renderParameterConstantExpression(value)),
                      "generic portable static rendering must remain available"))
      return false;
    for (auto kind : {nodal::BackendKind::VerilogA, nodal::BackendKind::VerilogAMS}) {
      test.clear();
      const auto &profile = nodal::getBackendProfile(kind);
      const auto admitted = nodal::verifyBackendOperations(*module, profile);
      ++test.cases;
      if (!test.require(
              mlir::failed(admitted) &&
                  llvm::StringRef(test.diagnostic).contains("NODAL-BACKEND-CAPABILITY-001") &&
                  llvm::StringRef(test.diagnostic).contains("ceil_log2/choose_zero"),
              "static primitive escaped the analog backend capability boundary"))
        return false;
      test.clear();
      const nodal::BackendConfiguration configuration{&profile, profile.defaultCheckProfile,
                                                      profile.shapedValueLayout,
                                                      profile.materialization, profile.naming};
      std::string output = "unchanged-output";
      llvm::raw_string_ostream stream(output);
      const auto rendered =
          nodal::renderBackendCandidate({&module->getBody()->front()}, configuration, stream);
      stream.flush();
      ++test.cases;
      if (!test.require(
              mlir::failed(rendered) && output == "unchanged-output" &&
                  llvm::StringRef(test.diagnostic).contains("NODAL-BACKEND-CAPABILITY-001"),
              "direct candidate rendering emitted an unsupported static primitive"))
        return false;
    }
  }
  return true;
}

bool testDigitalCarriersAndPorts(Harness &test) {
  for (auto type :
       {"!nodal.uint<8>", "!nodal.bits<1>", "!nodal.shaped<\"2\", !nodal.sint<8>>",
        "!nodal.stream<!nodal.shaped<\"2\", !nodal.uint<8>>>",
        "!nodal.resolved<\"open_drain\", !nodal.bits<1>>", "tuple<f64, !nodal.uint<8>>"}) {
    auto module = test.parse(design(definition(
        "Carrier", "%v = \"builtin.unrealized_conversion_cast\"() : () -> " + std::string(type))));
    if (!module || !test.gate(*module, "verilog-a", false, "builtin.unrealized_conversion_cast") ||
        !test.gate(*module, "verilog-ams", true))
      return false;
  }

  auto port = test.parse(design(definition("Port", R"mlir(
"nodal.port"() <{sym_name = "p", type = !nodal.uint<8>, direction = "input", domain = @sync, metadata = {}}> : () -> ()
)mlir")));
  if (!port || !test.gate(*port, "verilog-a", false, "nodal.port") ||
      !test.gate(*port, "verilog-ams", true))
    return false;

  // A zero-result operation can carry a digital type solely in a nested
  // attribute. This also guards against checking only named port/type fields.
  auto attributes = test.parse(R"mlir(
module attributes {nodal.test_payload = {nested = [(f64) -> !nodal.uint<8>]}} {}
)mlir");
  if (!attributes || !test.gate(*attributes, "verilog-a", false, "builtin.module"))
    return false;

  auto block = test.parse(R"mlir(
module {
  "nodal.module"() <{sym_name = "BlockArgument", metadata = {}}> ({
  ^bb0(%arg: !nodal.uint<8>):
    %value = "builtin.unrealized_conversion_cast"(%arg) : (!nodal.uint<8>) -> f64
  }) : () -> ()
}
)mlir");
  if (!block || !test.gate(*block, "verilog-a", false, "nodal.module"))
    return false;
  auto &operandOnly = block->getBody()->front().getRegion(0).front().front();
  ++test.cases;
  return test.require(nodal::hasDigitalTargetCapability(&operandOnly),
                      "local capability classification overlooked a digital operand");
}

bool testOperationsAndHierarchy(Harness &test) {
  auto operation = test.parse(design(definition("DigitalOperation", R"mlir(
"nodal.hardware_loop"() <{induction = "i", lower = 0 : i64, upper = 2 : i64, step = 1 : i64, effect_policy = "pure", metadata = {}}> ({
^bb0:
}) : () -> ()
)mlir")));
  if (!operation || !test.gate(*operation, "verilog-a", false, "nodal.hardware_loop") ||
      !test.gate(*operation, "verilog-ams", true))
    return false;

  auto nested = test.parse(design(definition("Generated", generated(generated(digitalConstant)))));
  if (!nested || !test.gate(*nested, "verilog-a", false, "nodal.constant") ||
      !test.gate(*nested, "verilog-ams", true))
    return false;

  const auto root = definition("Root", instance("left", "Left") + instance("right", "Right"));
  const auto left = definition("Left", instance("leaf", "Shared"));
  const auto right = definition("Right", instance("leaf", "Shared"));
  const auto leaf = definition("Shared", digitalConstant);
  std::string previous;
  for (const auto &definitions : {root + left + right + leaf, leaf + right + root + left}) {
    auto hierarchy = test.parse(design(definitions));
    if (!hierarchy || !test.gate(*hierarchy, "verilog-a", false, "nodal.constant"))
      return false;
    if (!test.require(previous.empty() || previous == test.diagnostic,
                      "reordering shared-leaf hierarchy changed its unique digital witness"))
      return false;
    previous = test.diagnostic;
    if (!test.gate(*hierarchy, "verilog-ams", true))
      return false;
  }
  return true;
}

bool testSharedHierarchyAndDeepTypeScale(Harness &test) {
  std::string instances;
  for (unsigned index = 0; index != 1000; ++index)
    instances += instance("leaf" + std::to_string(index), "Shared");
  auto module = test.parse(
      design(definition("WideRoot", instances) +
             definition("Shared", "%v = \"builtin.unrealized_conversion_cast\"() : () -> f64")));
  if (!module)
    return false;
  auto &carrier = module->getBody()->back().getRegion(0).front().front();
  mlir::Type analog = mlir::Float64Type::get(&test.context);
  mlir::Type digital = nodal::UIntType::get(&test.context, 8);
  for (unsigned depth = 0; depth != 128; ++depth) {
    analog = nodal::ShapedType::get(&test.context, "2", analog);
    digital = nodal::ShapedType::get(&test.context, "2", digital);
  }
  // Classification remains independent of canonical validity. The historical
  // 128 Vec(2) wrappers exceed F54's 128-node path and signed-i64 footprint;
  // they must still reveal their leaf capability without hierarchy expansion.
  for (mlir::Type oversized : {analog, digital}) {
    carrier.getResult(0).setType(oversized);
    test.clear();
    if (!test.require(mlir::failed(mlir::verify(*module)) &&
                          llvm::StringRef(test.diagnostic).contains("NODAL-DIGITAL-054-004") &&
                          llvm::StringRef(test.diagnostic).contains("recursive type nesting"),
                      "oversized wrappers must reject the canonical depth limit") ||
        !test.gate(*module, "verilog-a", oversized == analog))
      return false;
  }
  mlir::Type overflow = nodal::UIntType::get(&test.context, 8);
  for (unsigned depth = 0; depth != 64; ++depth)
    overflow = nodal::ShapedType::get(&test.context, "2", overflow);
  carrier.getResult(0).setType(overflow);
  test.clear();
  if (!test.require(
          mlir::failed(mlir::verify(*module)) &&
              llvm::StringRef(test.diagnostic).contains("recursive digital bit footprint"),
          "finite-depth oversized footprint must reject independently"))
    return false;
  // Deep legal singleton wrappers exercise native validity at the exact shared
  // depth budget, with no exponential logical storage footprint.
  analog = mlir::Float64Type::get(&test.context);
  digital = nodal::UIntType::get(&test.context, 8);
  for (unsigned depth = 1; depth < nodal::digitalTypeMaximumDepth; ++depth) {
    analog = nodal::ShapedType::get(&test.context, "1", analog);
    digital = nodal::ShapedType::get(&test.context, "1", digital);
  }
  carrier.getResult(0).setType(analog);
  if (!test.require(mlir::succeeded(mlir::verify(*module)),
                    "bounded deep analog wrapper witness must remain native-valid") ||
      !test.gate(*module, "verilog-a", true))
    return false;
  carrier.getResult(0).setType(digital);
  if (!test.require(mlir::succeeded(mlir::verify(*module)),
                    "bounded deep digital wrapper witness must remain native-valid"))
    return false;
  std::string previous;
  for (unsigned repeat = 0; repeat != 2; ++repeat) {
    if (!test.gate(*module, "verilog-a", false, "builtin.unrealized_conversion_cast") ||
        !test.require(previous.empty() || previous == test.diagnostic,
                      "wide shared hierarchy lost its deterministic digital witness"))
      return false;
    previous = test.diagnostic;
  }
  return test.gate(*module, "verilog-ams", true);
}

bool testBridgeAndAdversarialMetadata(Harness &test) {
  auto bridge = test.parse(design(definition("ConservativeBridge", R"mlir(
%terminal = "nodal.terminal"() <{name = "p", metadata = {declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
%flow = "nodal.bridge"(%terminal) <{kind = "conservative_to_signal_flow", source_domain = "electrical", destination_domain = "continuous", metadata = {}}> : (!nodal.terminal<"electrical">) -> f64
)mlir")));
  if (!bridge || !test.gate(*bridge, "verilog-a", true) || !test.gate(*bridge, "verilog-ams", true))
    return false;

  bridge->walk([&](nodal::BridgeOp operation) {
    operation->getResult(0).setType(nodal::UIntType::get(&test.context, 8));
  });
  if (!test.gate(*bridge, "verilog-a", false, "nodal.bridge") ||
      !test.gate(*bridge, "verilog-ams", true))
    return false;

  auto digital = test.parse(design(definition("ClaimedAnalog", digitalConstant)));
  if (!digital)
    return false;
  // Labels cannot launder a digital carrier into the analog target subset.
  for (auto claimed : {"analog", "mixed_signal", "target_neutral"}) {
    (*digital)->setAttr("nodal.target.profile", mlir::StringAttr::get(&test.context, claimed));
    (*digital)->setAttr("nodal.bridge.design_kind", mlir::StringAttr::get(&test.context, "analog"));
    if (!test.gate(*digital, "verilog-a", false, "nodal.constant"))
      return false;
  }
  (*digital)->setAttr("nodal.bridge.design_kind", mlir::UnitAttr::get(&test.context));
  if (!test.gate(*digital, "verilog-a", false, "nodal.constant"))
    return false;

  test.clear();
  const auto before = print(*bridge);
  const auto unknown = nodal::verifyTargetLanguageCapabilities(*bridge, "verilog-future");
  ++test.cases;
  return test.require(
      mlir::failed(unknown) && print(*bridge) == before && test.diagnosticCount == 1 &&
          llvm::StringRef(test.diagnostic).contains("NODAL-VERIFY-CAPABILITY-005") &&
          llvm::StringRef(test.diagnostic).contains("verilog-future"),
      "unknown target language did not fail closed without mutation");
}

bool testInterfaceMembers(Harness &test) {
  const std::string interface = R"mlir(
"nodal.interface"() <{sym_name = "AnalogBus", metadata = {}}> ({
  "nodal.interface_role"() <{sym_name = "source", kind = "source", metadata = {}}> : () -> ()
  "nodal.interface_member"() <{sym_name = "p", type = !nodal.terminal<"electrical">, protocol = "conservative", roles = ["source"], metadata = {}}> : () -> ()
  "nodal.interface_member"() <{sym_name = "value", type = f64, protocol = "signal_flow", roles = ["source"], metadata = {}}> : () -> ()
}) : () -> ()
)mlir";
  auto analog = test.parse(design(interface + definition("InterfaceCarrier", R"mlir(
%bus = "builtin.unrealized_conversion_cast"() : () -> !nodal.interface<"AnalogBus">
)mlir")));
  if (!analog || !test.gate(*analog, "verilog-a", true) || !test.gate(*analog, "verilog-ams", true))
    return false;
  // An unused definition is still in the emitted design's capability boundary.
  // Its actual member type matters even though no InterfaceType carrier is used.
  auto unused = test.parse(design(interface));
  if (!unused)
    return false;
  unused->walk([&](nodal::InterfaceMemberOp member) {
    if (member->getAttrOfType<mlir::StringAttr>("sym_name").getValue() == "value") {
      member->setAttr("type", mlir::TypeAttr::get(nodal::UIntType::get(&test.context, 8)));
      member->setAttr("protocol", mlir::StringAttr::get(&test.context, "plain"));
    }
  });
  return test.require(mlir::succeeded(mlir::verify(*unused)),
                      "digital interface member witness must remain native-valid") &&
         test.gate(*unused, "verilog-a", false, "nodal.interface_member") &&
         test.gate(*unused, "verilog-ams", true);
}

bool testNamedCapabilityPass(Harness &test) {
  auto digital = test.parse(design(definition("PassCarrier", digitalConstant)));
  auto analog = test.parse(analogSource);
  if (!digital || !analog)
    return false;
  auto run = [&](mlir::ModuleOp module, llvm::StringRef targetProfile,
                 mlir::Attribute languageProfile, llvm::StringRef expectedCode) {
    module->setAttr("nodal.target.profile", mlir::StringAttr::get(&test.context, targetProfile));
    module->setAttr("nodal.backend.profile", languageProfile);
    test.clear();
    const auto before = print(module);
    mlir::PassManager manager(&test.context);
    if (!test.require(
            mlir::succeeded(mlir::parsePassPipeline("nodal-verify-capabilities", manager)),
            "named capability pass is not registered"))
      return false;
    const bool accepted = mlir::succeeded(manager.run(module));
    ++test.cases;
    return test.require(accepted == expectedCode.empty() && print(module) == before &&
                            (expectedCode.empty()
                                 ? test.diagnostic.empty()
                                 : llvm::StringRef(test.diagnostic).contains(expectedCode)),
                        "named pass lost language integration or preceding profile diagnostic");
  };
  auto a = mlir::StringAttr::get(&test.context, "verilog-a");
  auto ams = mlir::StringAttr::get(&test.context, "verilog-ams");
  return run(*analog, "analog", a, {}) && run(*analog, "analog", ams, {}) &&
         run(*digital, "mixed_signal", a, "NODAL-VERIFY-CAPABILITY-006") &&
         run(*digital, "target_neutral", a, "NODAL-VERIFY-CAPABILITY-006") &&
         run(*digital, "mixed_signal", ams, {}) &&
         run(*digital, "analog", ams, "NODAL-VERIFY-CAPABILITY-004") &&
         run(*analog, "unknown", ams, "NODAL-VERIFY-CAPABILITY-002") &&
         run(*analog, "digital", ams, "NODAL-VERIFY-CAPABILITY-003") &&
         run(*analog, "analog", mlir::StringAttr::get(&test.context, "verilog-future"),
             "NODAL-VERIFY-CAPABILITY-005") &&
         run(*analog, "analog", mlir::UnitAttr::get(&test.context), "NODAL-VERIFY-CAPABILITY-005");
}

bool testEarlyFailureAndEquationDiagnostic(Harness &test) {
  auto digital = test.parse(design(definition("EarlyFailure", digitalConstant)));
  if (!digital)
    return false;
  // Deliberately invalidate an unrelated semantic input after parsing. The
  // language gate must reject before the semantic pipeline can diagnose it.
  digital->getBody()->front().setAttr("metadata", mlir::UnitAttr::get(&test.context));
  const auto before = print(*digital);
  test.clear();
  std::string output = "unchanged-output";
  llvm::raw_string_ostream stream(output);
  const auto result = nodal::emitBackend(*digital, nodal::BackendKind::VerilogA, stream);
  stream.flush();
  ++test.cases;
  if (!test.require(
          mlir::failed(result) && output == "unchanged-output" && print(*digital) == before &&
              test.diagnosticCount == 1 &&
              llvm::StringRef(test.diagnostic).contains("NODAL-VERIFY-CAPABILITY-006"),
          "early capability rejection mutated input/output or lost diagnostic precedence"))
    return false;

  auto equations = test.parse(analogSource);
  if (!equations)
    return false;
  equations->walk([&](nodal::AnalogOp analog) {
    analog->setAttr("source_equations", mlir::DictionaryAttr::get(&test.context));
  });
  // The equation-to-target boundary owns this diagnostic, even when the
  // language subset itself is analog. F46 owns full manifest verification.
  if (!test.gate(*equations, "verilog-a", true))
    return false;
  for (auto kind : {nodal::BackendKind::VerilogA, nodal::BackendKind::VerilogAMS}) {
    test.clear();
    const auto result = nodal::verifyBackendOperations(*equations, nodal::getBackendProfile(kind));
    ++test.cases;
    if (!test.require(
            mlir::failed(result) && test.diagnosticCount == 1 &&
                llvm::StringRef(test.diagnostic).contains("NODAL-BACKEND-EQUATION-134-001"),
            "source equations lost their dedicated backend boundary diagnostic"))
      return false;
  }
  return true;
}

} // namespace

int main() {
  nodal::registerNodalPasses();
  Harness test;
  if (!testRegistry(test) || !testAnalogAndBuiltinControls(test) ||
      !testStaticReductionTargetBoundary(test) || !testDigitalCarriersAndPorts(test) ||
      !testOperationsAndHierarchy(test) || !testSharedHierarchyAndDeepTypeScale(test) ||
      !testBridgeAndAdversarialMetadata(test) || !testInterfaceMembers(test) ||
      !testNamedCapabilityPass(test) || !testEarlyFailureAndEquationDiagnostic(test))
    return 1;
  llvm::outs() << "Foundation 47 target capabilities: " << test.cases << " focused cases passed\n";
  return 0;
}
