#include "nodal/Backend/ScalarHierarchyMaterialization.h"

#include "circt/Dialect/HW/HWDialect.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Backend/Backend.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/PotentialFlowAccess.h"

#include "llvm/Support/raw_ostream.h"

#include <chrono>
#include <string>

namespace {
const std::string terminals = R"mlir(
%p = "nodal.terminal"() <{name = "p", direction = "inout", metadata = {allow_floating = true, declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
%n = "nodal.terminal"() <{name = "n", direction = "inout", metadata = {allow_floating = true, declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
)mlir";
std::string parameter(llvm::StringRef name, llvm::StringRef value) {
  return "\"nodal.parameter\"() <{sym_name = \"" + name.str() +
         "\", type = f64, default_value = " + value.str() +
         " : f64, variability = \"symbolic\", classification = \"ordinary\", metadata = {}}> : () "
         "-> ()\n";
}
std::string instance(llvm::StringRef id, llvm::StringRef child, llvm::StringRef actual,
                     llvm::StringRef sourcePath) {
  const bool symbolic = actual == "gain";
  std::string result = "\"nodal.instance\"() <{sym_name = \"" + id.str() + "\", module = @" +
                       child.str() + ", parameter_bindings = {" +
                       (symbolic ? "" : "gain = " + actual.str() + " : f64") +
                       "}, domain_bindings = {}, metadata = {child_path = \"" + sourcePath.str() +
                       "\", semantic_path = \"" + sourcePath.str() + "_instance\"}}> : () -> ()\n";
  if (symbolic)
    result += "%" + id.str() +
              "_actual = \"nodal.const_parameter_ref\"() <{parameter = @gain, metadata = {}}> : () "
              "-> f64\n"
              "\"nodal.parameter_override\"(%" +
              id.str() + "_actual) <{instance = @" + id.str() +
              ", parameter = @gain, metadata = {}}> : (f64) -> ()\n";
  for (const auto *port : {"p", "n"}) {
    result +=
        "%" + id.str() + "_" + port + " = \"nodal.instance_terminal\"() <{instance = @" + id.str() +
        ", port = \"" + port + "\", name = \"" + id.str() + "." + port +
        "\", direction = \"inout\", flow_orientation = \"out_of_component\", source_path = \"" +
        sourcePath.str() + "." + port +
        "\", metadata = {}}> : () -> !nodal.terminal<\"electrical\">\n"
        "\"nodal.connect\"(%" +
        id.str() + "_" + port + ", %" + port + ") <{connection_id = \"" + id.str() + "_" + port +
        "\", source_path = \"" + sourcePath.str() + "." + port +
        "\", metadata = {}}> : (!nodal.terminal<\"electrical\">, !nodal.terminal<\"electrical\">) "
        "-> ()\n";
  }
  return result;
}
std::string module(llvm::StringRef name, llvm::StringRef path, const std::string &body) {
  return "\"nodal.module\"() <{sym_name = \"" + name.str() + "\", metadata = {semantic_path = \"" +
         path.str() + "\"}}> ({\n" + body + "}) : () -> ()\n";
}
std::string model(unsigned copies = 3, bool initializeRoot = false) {
  std::string top = terminals + parameter("gain", "4.0");
  if (initializeRoot)
    top += R"mlir(
"nodal.analog"() <{metadata = {}}> ({
  "nodal.analog_procedure"() <{owner = "Top", metadata = {}}> ({
    %held = "nodal.analog_variable"() <{identity = "Top.held", owner = "Top", declaration_order = 0 : i64, initialized = true, initializer_value = "0.0 V", initializer_kind = "real", initializer_dimension = "voltage", initializer_reads = [], metadata = {}}> : () -> !nodal.variable<"real", "voltage">
    %initial = "nodal.analog_initial_step"() <{owner = "Top", event_id = "Top.initial", name = "", contract = "increment37", arguments = [], analyses = [], event_reads = [], metadata = {}}> : () -> !nodal.analog_event
    "nodal.analog_on"(%initial) <{owner = "Top", statement_id = "Top.initialize", metadata = {}}> ({
      "nodal.analog_assign"(%held) <{owner = "Top", statement_id = "Top.assign", authored_order = 0 : i64, value_kind = "real", value_dimension = "voltage", guard_present = false, guard_kind = "", guard_dimension = "", guard_value = "", guard_reads = [], analyses = ["dc", "transient"], metadata = {value = "1.0 V"}}> : (!nodal.variable<"real", "voltage">) -> ()
    }) : (!nodal.analog_event) -> ()
  }) : () -> ()
}) : () -> ()
)mlir";
  for (unsigned i = 0; i < copies; ++i)
    top += instance("child" + std::to_string(i), "Branch",
                    i == 0   ? "gain"
                    : i == 1 ? "3.0"
                             : "5.0",
                    "Top.child" + std::to_string(i));
  std::string leaf = terminals + parameter("gain", "2.0") + R"mlir(
%branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
"nodal.analog_user_function"() <{sym_name = "identitySignal", return_type = !nodal.quantity<"real", "1">, contract_version = "1", metadata = {}}> ({
  %input = "nodal.analog_function_value"() <{kind = "input", name = "signal", argument_index = 0 : i64, metadata = {}}> : () -> !nodal.quantity<"real", "1">
  "nodal.analog_function_return"(%input) <{metadata = {}}> : (!nodal.quantity<"real", "1">) -> ()
}) : () -> ()
"nodal.analog"() <{metadata = {semantic_path = "Top.child0.leaf.analog"}}> ({
  %gain = "nodal.parameter_ref"() <{parameter = @gain, metadata = {}}> : () -> f64
  %value = "nodal.analog_user_call"(%gain) <{callee = @identitySignal, contract_version = "1", metadata = {}}> : (f64) -> f64
  "nodal.contribute"(%branch, %value) <{kind = "flow", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
}) : () -> ()
)mlir";
  return std::string("module attributes {nodal.target.profile = \"analog\", nodal.root.module = "
                     "@Top, nodal.root.parameter_bindings = {gain = 4.0 : f64}} {\n") +
         R"mlir(
"nodal.nature"() <{sym_name = "Voltage", access = "V", units = "V", dimension = "voltage", abstol = 1.0e-6 : f64, metadata = {}}> : () -> ()
"nodal.nature"() <{sym_name = "Current", access = "I", units = "A", dimension = "current", abstol = 1.0e-12 : f64, metadata = {}}> : () -> ()
"nodal.discipline"() <{sym_name = "electrical", domain = "continuous", potential = @Voltage, flow = @Current, metadata = {}}> : () -> ()
)mlir" + module("Top", "Top", top) +
         module("Branch", "Top.child0",
                terminals + parameter("gain", "2.0") +
                    instance("leaf", "Leaf", "gain", "Top.child0.leaf")) +
         module("Leaf", "Top.child0.leaf", leaf) + "}\n";
}
std::string print(mlir::Operation *op) {
  std::string value;
  llvm::raw_string_ostream stream(value);
  op->print(stream);
  return value;
}
bool replace(std::string &text, llvm::StringRef before, llvm::StringRef after) {
  auto position = text.find(before.str());
  if (position == std::string::npos)
    return false;
  text.replace(position, before.size(), after.str());
  return true;
}
} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect, circt::hw::HWDialect>();
  mlir::MLIRContext context(registry);
  std::string diagnostic;
  mlir::ScopedDiagnosticHandler diagnostics(&context, [&](mlir::Diagnostic &message) {
    llvm::raw_string_ostream stream(diagnostic);
    message.print(stream);
    return mlir::success();
  });
  auto fail = [&](llvm::StringRef message) {
    llvm::errs() << message << ": " << diagnostic << '\n';
    return 1;
  };
  auto source = mlir::parseSourceString<mlir::ModuleOp>(model(), &context);
  if (!source)
    return fail("could not parse behavior-bearing hierarchy");
  const std::string original = print(source->getOperation());
  std::string previous;
  for (unsigned repetition = 0; repetition < 2; ++repetition) {
    std::string output;
    llvm::raw_string_ostream stream(output);
    if (mlir::failed(nodal::emitBackend(*source, nodal::BackendKind::VerilogA, stream, nullptr,
                                        nodal::HierarchyEmission::MaterializedScalar)))
      return fail("materialized hierarchy failed");
    stream.flush();
    for (const auto *expected :
         {"module Top", "parameter real gain = 4", "localparam real nodal_h1_gain = gain",
          "localparam real nodal_h3_gain = 3", "localparam real nodal_h5_gain = 5",
          "nodal_h2_identitySignal", "nodal_h4_identitySignal", "nodal_h6_identitySignal",
          "nodal.hierarchy_correspondence"})
      if (output.find(expected) == std::string::npos)
        return fail(std::string("missing materialized identity: ") + expected);
    if (output.find("module Branch") != std::string::npos ||
        output.find("module Leaf") != std::string::npos ||
        print(source->getOperation()) != original || (repetition && output != previous))
      return fail(
          "materialization changed source, retained named instances, or was nondeterministic");
    previous = output;
  }
  std::string named;
  llvm::raw_string_ostream namedStream(named);
  if (mlir::failed(nodal::emitBackend(*source, nodal::BackendKind::VerilogA, namedStream)) ||
      named.find("module Branch") == std::string::npos ||
      named.find("Branch #(.gain(gain))") == std::string::npos)
    return fail("default named hierarchy changed");
  auto initialized = mlir::parseSourceString<mlir::ModuleOp>(model(3, true), &context);
  std::string initializedOutput;
  llvm::raw_string_ostream initializedStream(initializedOutput);
  if (!initialized ||
      mlir::failed(nodal::emitBackend(*initialized, nodal::BackendKind::VerilogA, initializedStream,
                                      nullptr, nodal::HierarchyEmission::MaterializedScalar)) ||
      initializedOutput.find("@(initial_step)") == std::string::npos ||
      initializedOutput.find("= 1.0;") == std::string::npos)
    return fail("root-only initialization was not preserved");
  auto provenanceSource = model();
  replace(provenanceSource, "sym_name = \"Leaf\", metadata = {semantic_path = \"Top.child0.leaf\"}",
          "sym_name = \"Leaf\", metadata = {semantic_path = \"Leaf\"}");
  replace(provenanceSource, "contract_version = \"1\", metadata = {}",
          "contract_version = \"1\", metadata = {semantic_path = \"Leaf.identitySignal\", "
          "source_file = \"Leaf.scala\", source_path = \"Leaf.scala\", authored_text = "
          "\"Leaf.arbitrary text\"}");
  auto provenanceModule = mlir::parseSourceString<mlir::ModuleOp>(provenanceSource, &context);
  if (!provenanceModule ||
      mlir::failed(
          nodal::runNodalPipelineTransaction(*provenanceModule, nodal::GateProfile::Default)) ||
      mlir::failed(nodal::materializeScalarElectricalHierarchy(*provenanceModule)) ||
      mlir::failed(
          nodal::runNodalPipelineTransaction(*provenanceModule, nodal::GateProfile::Default)))
    return fail("provenance witness failed materialized verification");
  unsigned provenanceCopies = 0;
  bool preservedSource = true;
  provenanceModule->walk([&](mlir::Operation *op) {
    if (op->getName().getStringRef() != "nodal.analog_user_function")
      return;
    ++provenanceCopies;
    auto metadata = op->getAttrOfType<mlir::DictionaryAttr>("metadata");
    preservedSource &=
        metadata.getAs<mlir::StringAttr>("source_file").getValue() == "Leaf.scala" &&
        metadata.getAs<mlir::StringAttr>("source_path").getValue() == "Leaf.scala" &&
        metadata.getAs<mlir::StringAttr>("authored_text").getValue() == "Leaf.arbitrary text" &&
        metadata.getAs<mlir::StringAttr>("semantic_path").getValue().starts_with("Top.child");
  });
  if (provenanceCopies != 3 || !preservedSource)
    return fail("occurrence remapping corrupted source filenames or authored text");
  auto causalSource = model(2);
  replace(
      causalSource, "%branch = \"nodal.branch\"(%p, %n)",
      R"mlir(%reference = "nodal.node"() <{name = "reference", metadata = {}}> : () -> !nodal.terminal<"electrical">
%input_branch = "nodal.branch"(%p, %reference) <{metadata = {semantic_path = "Top.child0.leaf.input", identity = "Top.child0.leaf.input"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
%branch = "nodal.branch"(%n, %reference))mlir");
  replace(
      causalSource,
      R"mlir("nodal.contribute"(%branch, %value) <{kind = "flow", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ())mlir",
      R"mlir(%voltage = "nodal.access"(%input_branch) <{kind = "potential", function = "V", metadata = {semantic_path = "Top.child0.leaf.read"}}> : (!nodal.branch<"electrical">) -> !nodal.quantity<"real", "voltage">
"nodal.contribute"(%branch, %voltage) <{kind = "potential", metadata = {}}> : (!nodal.branch<"electrical">, !nodal.quantity<"real", "voltage">) -> ())mlir");
  auto causal = mlir::parseSourceString<mlir::ModuleOp>(causalSource, &context);
  if (!causal ||
      mlir::failed(nodal::runNodalPipelineTransaction(*causal, nodal::GateProfile::Default)))
    return fail("causal child did not normalize its source-free input probe");
  unsigned sourceProbes = 0;
  causal->walk([&](nodal::ProbeOp) { ++sourceProbes; });
  unsigned negatives = 0;
  for (bool forgeOwnership : {false, true}) {
    mlir::OwningOpRef<mlir::ModuleOp> forged(llvm::cast<mlir::ModuleOp>(causal->clone()));
    forged->walk([&](nodal::ProbeOp probe) {
      if (forgeOwnership)
        probe->setAttr("metadata", mlir::DictionaryAttr::get(&context));
      else
        probe->setAttr("provenance",
                       mlir::ArrayAttr::get(&context, {mlir::StringAttr::get(&context, "forged")}));
    });
    diagnostic.clear();
    const auto before = print(forged->getOperation());
    std::string forgedOutput;
    llvm::raw_string_ostream forgedStream(forgedOutput);
    if (mlir::succeeded(nodal::materializeScalarElectricalHierarchy(*forged)) ||
        print(forged->getOperation()) != before ||
        mlir::succeeded(nodal::emitBackend(*forged, nodal::BackendKind::VerilogA, forgedStream,
                                           nullptr,
                                           nodal::HierarchyEmission::MaterializedScalar)) ||
        !forgedOutput.empty() || diagnostic.find("NODAL-PROBE-PROVENANCE-001") == std::string::npos)
      return fail("forged probe support or ownership escaped preflight validation");
    ++negatives;
  }
  diagnostic.clear();
  if (sourceProbes != 1 || mlir::failed(nodal::materializeScalarElectricalHierarchy(*causal)) ||
      mlir::failed(nodal::runNodalPipelineTransaction(*causal, nodal::GateProfile::Default)) ||
      mlir::failed(nodal::verifyPotentialFlowAccessModel(*causal)))
    return fail("causal hierarchy failed canonical probe recomputation");
  unsigned materializedProbes = 0;
  bool causalProvenance = true;
  causal->walk([&](nodal::ProbeOp probe) {
    ++materializedProbes;
    auto provenance = probe->getAttrOfType<mlir::ArrayAttr>("provenance");
    causalProvenance &=
        provenance.size() == 2 &&
        llvm::cast<mlir::StringAttr>(provenance[0]).getValue() ==
            (materializedProbes == 1 ? "Top.child0.leaf.input" : "Top.child1.leaf.input") &&
        llvm::cast<mlir::StringAttr>(provenance[1]).getValue() ==
            (materializedProbes == 1 ? "Top.child0.leaf.read" : "Top.child1.leaf.read");
  });
  std::string causalOutput;
  llvm::raw_string_ostream causalStream(causalOutput);
  if (materializedProbes != 2 || !causalProvenance ||
      mlir::failed(nodal::emitBackend(*causal, nodal::BackendKind::VerilogA, causalStream)) ||
      causalOutput.find("V(n, nodal_h2_reference) <+ V(p, nodal_h2_reference)") ==
          std::string::npos ||
      causalOutput.find("V(n, nodal_h4_reference) <+ V(p, nodal_h4_reference)") ==
          std::string::npos)
    return fail("causal input/output SSA or occurrence probe provenance was not preserved");
  auto mergedSource = model(2);
  replace(
      mergedSource, "\"nodal.instance\"() <{sym_name = \"child0\"",
      R"mlir(%root_input = "nodal.branch"(%p, %n) <{metadata = {semantic_path = "Top.input"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
"nodal.analog"() <{metadata = {}}> ({
  %root_voltage = "nodal.access"(%root_input) <{kind = "potential", function = "V", metadata = {semantic_path = "Top.read"}}> : (!nodal.branch<"electrical">) -> !nodal.quantity<"real", "voltage">
}) : () -> ()
"nodal.instance"() <{sym_name = "child0")mlir");
  auto merged = mlir::parseSourceString<mlir::ModuleOp>(mergedSource, &context);
  if (!merged ||
      mlir::failed(nodal::runNodalPipelineTransaction(*merged, nodal::GateProfile::Default)))
    return fail("root source-free probe witness failed normalization");
  unsigned mergedProbes = 0;
  merged->walk([&](nodal::ProbeOp) { ++mergedProbes; });
  if (mergedProbes != 1 || mlir::failed(nodal::materializeScalarElectricalHierarchy(*merged)) ||
      mlir::failed(nodal::runNodalPipelineTransaction(*merged, nodal::GateProfile::Default)))
    return fail("root source-free probe failed canonical support merge");
  mergedProbes = 0;
  merged->walk([&](nodal::ProbeOp) { ++mergedProbes; });
  if (mergedProbes != 0)
    return fail("stale root probe survived its materialized child contribution");
  auto rejected = [&](const std::string &input, llvm::StringRef code) {
    diagnostic.clear();
    auto candidate = mlir::parseSourceString<mlir::ModuleOp>(input, &context);
    if (!candidate)
      return false;
    std::string before = print(candidate->getOperation()), output;
    llvm::raw_string_ostream stream(output);
    bool failed =
        mlir::failed(nodal::emitBackend(*candidate, nodal::BackendKind::VerilogA, stream, nullptr,
                                        nodal::HierarchyEmission::MaterializedScalar));
    ++negatives;
    return failed && output.empty() && print(candidate->getOperation()) == before &&
           diagnostic.find(code.str()) != std::string::npos;
  };
  if (!rejected(model(500), "MATERIALIZE-002"))
    return fail("pre-expansion occurrence budget did not reject transactionally");
  auto deep = model(1);
  replace(deep, "module = @Leaf", "module = @Depth0");
  std::string levels;
  for (unsigned level = 0; level < 62; ++level) {
    std::string current = "Depth" + std::to_string(level);
    std::string next = level == 61 ? "Leaf" : "Depth" + std::to_string(level + 1);
    levels += module(current, current,
                     terminals + parameter("gain", "2.0") +
                         instance("next", next, "gain", current + ".next"));
  }
  deep.insert(deep.rfind('}'), levels);
  if (!rejected(deep, "MATERIALIZE-002"))
    return fail("pre-expansion depth budget did not reject transactionally");
  auto manyOperations = model(64);
  const std::string contribution =
      R"mlir("nodal.contribute"(%branch, %value) <{kind = "flow", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ())mlir";
  std::string contributions;
  for (unsigned index = 0; index < 1600; ++index)
    contributions += contribution + "\n";
  replace(manyOperations, contribution, contributions);
  if (!rejected(manyOperations, "MATERIALIZE-002"))
    return fail("pre-expansion operation budget did not reject transactionally");
  auto representative = mlir::parseSourceString<mlir::ModuleOp>(model(64), &context);
  std::string representativeOutput;
  llvm::raw_string_ostream representativeStream(representativeOutput);
  const auto representativeStart = std::chrono::steady_clock::now();
  if (!representative ||
      mlir::failed(nodal::emitBackend(*representative, nodal::BackendKind::VerilogA,
                                      representativeStream, nullptr,
                                      nodal::HierarchyEmission::MaterializedScalar)) ||
      representativeOutput.find("nodal_h128_identitySignal") == std::string::npos)
    return fail("representative 129-occurrence materialization failed");
  llvm::outs() << "representative hierarchy: 129 occurrences, " << representativeOutput.size()
               << " emitted bytes, "
               << std::chrono::duration_cast<std::chrono::microseconds>(
                      std::chrono::steady_clock::now() - representativeStart)
                      .count()
               << " us emission elapsed\n";
  auto rootActual = model();
  replace(rootActual, "nodal.root.parameter_bindings = {gain = 4.0 : f64}",
          "nodal.root.parameter_bindings = {gain = 6.0 : f64}");
  if (!rejected(rootActual, "NODAL-BACKEND-HIERARCHY-010"))
    return fail("non-default root actual was silently specialized");
  auto joinedAnchors = model();
  replace(
      joinedAnchors, "\"nodal.instance\"() <{sym_name = \"child1\"",
      R"mlir("nodal.connect"(%p, %n) <{connection_id = "joined", source_path = "Top.joined", metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> ()
"nodal.instance"() <{sym_name = "child1")mlir");
  if (!rejected(joinedAnchors, "MATERIALIZE-001"))
    return fail("multiple physical anchors were silently merged");
  auto equation = model();
  replace(
      equation, "nodal.target.profile =",
      R"mlir(nodal.bridge.analog_semantics = [{kind = "equation", identity = "Top.equation", owner = "Top", continuity = "unspecified", source_file = "Hierarchy.scala", source_line = 1 : i64, source_column = 1 : i64, guard = "", analyses = ["dc"], authored_left = "1.0", authored_right = "1.0", dimension = "1", residual_convention = "lhs-minus-rhs-equals-zero", causally_oriented = false, divided = false}], nodal.target.profile =)mlir");
  if (!rejected(equation, "MATERIALIZE-001"))
    return fail("metadata-only equation was silently materialized");
  auto unsupported = model();
  replace(unsupported, "  %gain = \"nodal.parameter_ref\"",
          "  %clock = \"nodal.analog_context\"() <{query_kind = \"temperature\", registry_version "
          "= \"1\", metadata = {}}> : () -> !nodal.quantity<\"real\", \"temperature\">\n  %gain = "
          "\"nodal.parameter_ref\"");
  if (!rejected(unsupported, "MATERIALIZE-001"))
    return fail("unsupported child operation was admitted");
  auto reservedName = model();
  replace(reservedName, "sym_name = \"identitySignal\"", "sym_name = \"always\"");
  replace(reservedName, "callee = @identitySignal", "callee = @always");
  if (!rejected(reservedName, "MATERIALIZE-001"))
    return fail("materialized prefix legalized a reserved source identifier");
  std::string malformed =
      R"mlir(module attributes {nodal.target.profile = "analog", nodal.backend.hierarchy_materialization = "scalar-v1", nodal.backend.hierarchy_correspondence = [{kind = "terminal", source = "Top\0Aendmodule", target = "p"}]} {)mlir" +
      module("Top", "Top", terminals) + "}";
  diagnostic.clear();
  auto malformedModule = mlir::parseSourceString<mlir::ModuleOp>(malformed, &context);
  std::string malformedOutput;
  llvm::raw_string_ostream malformedStream(malformedOutput);
  if (!malformedModule ||
      mlir::succeeded(
          nodal::emitBackend(*malformedModule, nodal::BackendKind::VerilogA, malformedStream)) ||
      !malformedOutput.empty() || diagnostic.find("MATERIALIZE-003") == std::string::npos)
    return fail("malformed correspondence escaped transactional validation");
  ++negatives;
  llvm::outs() << "scalar hierarchy: repeated/nested symbolic/literal functions, default "
                  "compatibility, determinism, and "
               << negatives << " transactional negatives passed\n";
  return 0;
}
