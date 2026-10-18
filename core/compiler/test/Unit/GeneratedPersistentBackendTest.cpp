#include "circt/Dialect/HW/HWDialect.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Backend/AnalogEventBackend.h"
#include "nodal/Backend/AnalogVerticalSlice.h"
#include "nodal/Backend/Backend.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/raw_ostream.h"

#include <cstdint>
#include <fstream>
#include <string>
#include <tuple>
#include <vector>

namespace {
const std::string variable = "!nodal.variable<\"real\", \"1\">";
const std::string quantity = "!nodal.quantity<\"real\", \"1\">";
const std::string terminal = "!nodal.terminal<\"electrical\">";
const std::string scope = "Arrays.gen";

bool replace(std::string &text, const std::string &before, const std::string &after) {
  const auto position = text.find(before);
  if (position == std::string::npos)
    return false;
  text.replace(position, before.size(), after);
  return true;
}
bool contains(const std::string &text, llvm::StringRef expected) {
  return llvm::StringRef(text).contains(expected);
}
bool before(const std::string &text, const std::string &first, const std::string &second) {
  const auto left = text.find(first), right = text.find(second);
  return left != std::string::npos && right != std::string::npos && left < right;
}
std::string shaped(const std::string &dimensions, bool storage = true) {
  return "!nodal.shaped<\"" + dimensions + "\", " + (storage ? variable : quantity) + ">";
}
std::string metadata(const std::string &name) {
  return "metadata = {semantic_path = \"Arrays." + name + "\"}";
}
std::string literal(const std::string &name, int64_t value) {
  const auto number = std::to_string(value);
  return "%" + name + " = \"nodal.const_literal\"() <{value = " + number + " : i64, spelling = \"" +
         number + "\", metadata = {}}> : () -> i64\n";
}
std::string fill(const std::string &name, const std::string &dimensions,
                 const std::string &value = "2.5", const std::string &captures = "",
                 const std::string &types = "") {
  return "%" + name + " = \"nodal.analog_array_fill\"(" + captures +
         ") <{owner = \"Arrays\", value = \"" + value + "\", " + metadata(name) + "}> : (" + types +
         ") -> " + shaped(dimensions, false) + "\n";
}
std::string declaration(const std::string &name, const std::string &dimensions,
                        const std::string &initializer = "") {
  return "%" + name + " = \"nodal.analog_variable\"(" +
         (initializer.empty() ? "" : "%" + initializer) + ") <{identity = \"Arrays." + name +
         "\", owner = \"Arrays\", declaration_order = 0 : i64, initialized = " +
         (initializer.empty() ? "false" : "true") +
         ", initializer_value = \"\", initializer_kind = \"\", initializer_dimension = \"\", "
         "initializer_reads = [], " +
         metadata(name) + "}> : (" + (initializer.empty() ? "" : shaped(dimensions, false)) +
         ") -> " + shaped(dimensions) + "\n";
}
std::string read(const std::string &name, const std::string &input,
                 const std::string &dimensions = "") {
  return "%" + name + " = \"nodal.analog_variable_read\"(%" + input + ") <{read_id = \"Arrays." +
         name + "\", owner = \"Arrays\", " + metadata(name) + "}> : (" +
         (dimensions.empty() ? variable : shaped(dimensions)) + ") -> " +
         (dimensions.empty() ? quantity : shaped(dimensions, false)) + "\n";
}
std::string index(const std::string &name, const std::string &input, const std::string &dimensions,
                  const std::string &indices, const std::string &indexTypes,
                  const std::string &resultDimensions = "", bool storage = true) {
  return "%" + name + " = \"nodal.shape_index\"(%" + input + ", " + indices +
         ") <{owner = \"Arrays\", metadata = {semantic_path = \"Arrays." + name +
         "\", input_path = \"Arrays." + input + "\", storage = \"" +
         (storage ? "procedural" : "structural") + "\", access = \"" +
         (storage ? "read_write" : "read_only") + "\"}}> : (" + shaped(dimensions, storage) + ", " +
         indexTypes + ") -> " +
         (resultDimensions.empty() ? (storage ? variable : quantity)
                                   : shaped(resultDimensions, storage)) +
         "\n";
}
std::string scalarWrite(const std::string &name, const std::string &target,
                        const std::string &value) {
  return "\"nodal.analog_assign\"(%" + target + ") <{statement_id = \"Arrays." + name +
         "\", owner = \"Arrays\", authored_order = 0 : i64, value_kind = \"real\", "
         "value_dimension = \"1\", analyses = [\"dc\", \"transient\"], guard_present = false, "
         "guard_value = \"\", guard_kind = \"\", guard_dimension = \"\", guard_reads = [], "
         "metadata = {semantic_path = \"Arrays." +
         name + "\", value = \"" + value + "\"}}> : (" + variable + ") -> ()\n";
}
std::string lexical(const std::string &body) {
  return "\"nodal.analog_scope\"() <{scope_id = \"Arrays.localScope\", owner = \"Arrays\", " +
         metadata("localScope") + "}> ({\n" + body + "}) : () -> ()\n";
}
std::string event(const std::string &body = "") {
  return R"mlir(%initial = "nodal.analog_initial_step"() <{event_id = "Arrays.initial", owner = "Arrays", contract = "increment37", name = "", arguments = [], analyses = [], event_reads = [], metadata = {}}> : () -> !nodal.analog_event
"nodal.analog_on"(%initial) <{statement_id = "Arrays.on", owner = "Arrays", metadata = {}}> ({
)mlir" + (body.empty() ? "^bb0:\n" : body) +
         "}) : (!nodal.analog_event) -> ()\n";
}
std::string owned(std::string text, const std::string &owner = scope) {
  const std::string attributes =
      "generated_owner = \"" + owner + "\", generated_induction = \"" + owner + ".index\", ";
  size_t position = 0;
  while ((position = text.find("<{", position)) != std::string::npos) {
    text.insert(position + 2, attributes);
    position += 2 + attributes.size();
  }
  return text;
}
std::string procedure(std::string body, const std::string &owner = scope) {
  for (const auto &attribute : {"declaration_order", "authored_order"}) {
    const std::string token = std::string(attribute) + " = 0 : i64";
    size_t position = 0;
    unsigned order = 0;
    while ((position = body.find(token, position)) != std::string::npos) {
      const auto replacement = std::string(attribute) + " = " + std::to_string(order++) + " : i64";
      body.replace(position, token.size(), replacement);
      position += replacement.size();
    }
  }
  return owned("\"nodal.analog\"() <{metadata = {}}> ({\n"
               "\"nodal.analog_procedure\"() <{owner = \"Arrays\", metadata = {}}> ({\n" +
                   body + "}) : () -> ()\n}) : () -> ()\n",
               owner);
}
std::string generate(const std::string &body, const std::string &owner = scope,
                     const std::string &induction = "i", int64_t lower = 0, int64_t upper = 2,
                     int64_t step = 1) {
  const int64_t count = upper <= lower ? 0 : (upper - lower - 1) / step + 1;
  // No unused induction argument and no dummy topology: declaration needs alone
  // must select the structural occurrence renderer.
  return "\"nodal.generate\"() <{induction = \"" + induction +
         "\", lower = " + std::to_string(lower) + " : i64, upper = " + std::to_string(upper) +
         " : i64, step = " + std::to_string(step) + " : i64, region_id = \"" + owner +
         "\", induction_path = \"" + owner +
         ".index\", metadata = {maximum_trip_count = " + std::to_string(count) + " : i64}}> ({\n" +
         body + "}) : () -> ()\n";
}
std::string compoundGenerate(const std::string &body, unsigned maximum = 3) {
  return "\"nodal.generate\"(%c1, %upper, %c2) <{induction = \"i\", region_id = \"Arrays.gen\", "
         "induction_path = \"Arrays.gen.index\", metadata = {maximum_trip_count = " +
         std::to_string(maximum) + " : i64}}> ({\n" + body + "}) : (i64, i64, i64) -> ()\n";
}
std::string module(const std::string &name, const std::string &body) {
  return "\"nodal.module\"() <{sym_name = \"" + name + "\", metadata = {semantic_path = \"" + name +
         "\"}}> ({\n" + body + "}) : () -> ()\n";
}
std::string wrap(const std::string &body, const std::string &moduleBody = "",
                 const std::string &definitions = "") {
  return "module attributes {nodal.target.profile = \"analog\"} {\n" + definitions +
         module("Arrays", literal("c0", 0) + literal("c1", 1) + literal("c2", 2) +
                              literal("c3", 3) + literal("c4", 4) + literal("c8", 8) + moduleBody +
                              body) +
         "}\n";
}
std::string root(const std::string &name = "values", const std::string &dimensions = "2,3",
                 const std::string &value = "2.5") {
  return fill(name + "Fill", dimensions, value) + declaration(name, dimensions, name + "Fill");
}
std::string parameters() {
  return R"mlir("nodal.parameter"() <{classification = "structural", parameter_kind = "integer", default_value = 4 : i64, sym_name = "COUNT", type = i64, variability = "symbolic", metadata = {semantic_path = "Arrays.COUNT"}}> : () -> ()
"nodal.parameter_constraint"(%c1, %c8) <{constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, parameter = @COUNT, metadata = {}}> : (i64, i64) -> ()
"nodal.parameter_envelope"() <{effects = ["shape", "topology"], policy = "static_generate", parameter = @COUNT, metadata = {}}> : () -> ()
"nodal.parameter"() <{classification = "ordinary", parameter_kind = "real", default_value = 1.25 : f64, sym_name = "LEVEL", type = f64, variability = "symbolic", metadata = {semantic_path = "Arrays.LEVEL"}}> : () -> ()
)mlir";
}
const std::string discipline =
    R"mlir("nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", dimension = "voltage", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
"nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", dimension = "current", metadata = {}, sym_name = "Current", units = "A"}> : () -> ()
"nodal.discipline"() <{domain = "continuous", flow = @Current, potential = @Voltage, metadata = {}, sym_name = "electrical"}> : () -> ()
)mlir";
std::string childInstance(const std::string &name) {
  return owned(
      "\"nodal.instance\"() <{sym_name = \"" + name +
      "\", module = @Child, parameter_bindings = {}, domain_bindings = {}, metadata = "
      "{semantic_path = \"Arrays.gen." +
      name + "\"}}> : () -> ()\n%" + name + "Port = \"nodal.instance_terminal\"() <{instance = @" +
      name + ", port = \"p\", name = \"" + name +
      ".p\", direction = \"inout\", flow_orientation = \"out_of_component\", "
      "source_path = \"Child.p\", metadata = {semantic_path = \"Arrays.gen." +
      name + "Port\"}}> : () -> " + terminal + "\n\"nodal.connect\"(%shared, %" + name +
      "Port) <{connection_id = \"Arrays.gen.bind_" + name + "\", source_path = \"Arrays.gen.bind_" +
      name + "\", metadata = {semantic_path = \"Arrays.gen.bind_" + name + "\"}}> : (" + terminal +
      ", " + terminal + ") -> ()\n");
}
} // namespace

int main(int argc, char **argv) {
  mlir::MLIRContext context;
  context.loadDialect<nodal::NodalDialect, circt::hw::HWDialect>();
  unsigned checks = 0;
  auto check = [&](bool valid, const std::string &message) {
    ++checks;
    if (!valid)
      llvm::errs() << "GeneratedPersistentBackendTest: " << message << "\n";
    return valid;
  };
  auto render = [&](mlir::ModuleOp module, std::string &target, nodal::BackendKind kind) {
    llvm::raw_string_ostream stream(target);
    const auto result = nodal::emitBackend(module, kind, stream);
    stream.flush();
    return mlir::succeeded(result);
  };
  auto emit = [&](const std::string &source, const std::string &name, std::string &target,
                  nodal::BackendKind kind = nodal::BackendKind::VerilogA) {
    if (argc > 1)
      std::ofstream(std::string(argv[1]) + "/" + name + ".mlir") << source;
    auto parsed = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (!check(parsed && mlir::succeeded(mlir::verify(*parsed)), name + " native fixture") ||
        !check(render(*parsed, target, kind), name + " emission"))
      return false;
    std::string repeated, nativeText, roundtrip, gated;
    llvm::raw_string_ostream nativeStream(nativeText);
    parsed->print(nativeStream);
    nativeStream.flush();
    auto reparsed = mlir::parseSourceString<mlir::ModuleOp>(nativeText, &context);
    auto configuration = nodal::resolveBackendConfiguration(*parsed, kind);
    if (!check(render(*parsed, repeated, kind) && target == repeated && reparsed &&
                   mlir::succeeded(mlir::verify(*reparsed)) && render(*reparsed, roundtrip, kind) &&
                   target == roundtrip,
               name + " deterministic repeated/native-roundtrip output") ||
        !check(mlir::succeeded(
                   nodal::runNodalPipelineTransaction(*parsed, nodal::GateProfile::Default)) &&
                   render(*parsed, gated, kind) && gated == target,
               name + " explicit native default gate preserves exact output") ||
        !check(mlir::succeeded(configuration) &&
                   mlir::succeeded(nodal::reparseBackendTarget(target, *configuration)),
               name + " independent target reparse"))
      return false;
    if (argc > 1) {
      std::ofstream(std::string(argv[1]) + "/" + name +
                    (kind == nodal::BackendKind::VerilogA ? ".va" : ".vams"))
          << target;
    }
    return true;
  };

  const auto stateBody =
      root() + index("selected", "values", "2,3", "%c0, %c1", "i64, i64") +
      scalarWrite("write", "selected", "9.0") +
      lexical(read("snapshot", "values", "2,3") + declaration("local", "2,3", "snapshot") +
              read("localRead", "local", "2,3"));
  const auto fixedSource = wrap(generate(procedure(stateBody)));
  std::string fixed;
  for (auto kind : {nodal::BackendKind::VerilogA, nodal::BackendKind::VerilogAMS}) {
    const std::string suffix = kind == nodal::BackendKind::VerilogA ? "va" : "ams";
    std::string target;
    const std::string initial = "real event_Arrays_values[0:5] = '{6{2.5}};";
    if (!emit(fixedSource, "fixed-local-" + suffix, target, kind) ||
        !check(before(target, "begin : gen", initial) && before(target, initial, "analog begin") &&
                   llvm::StringRef(target).count(initial) == 1 &&
                   llvm::StringRef(target).count("analog begin") == 1 &&
                   llvm::StringRef(target).count("begin : event_Arrays_procedure") == 1 &&
                   contains(target, "genvar i;") &&
                   contains(target, "for (i = 0; i < 2; i = i + 1) begin : gen") &&
                   !contains(target, "event_Arrays_valuesFill") &&
                   !contains(target, "event_Arrays_values_copy_index") &&
                   !contains(target, "analog initial") && !contains(target, "Child ") &&
                   contains(target, "real event_Arrays_local[0:5];") &&
                   !contains(target, "real event_Arrays_local[0:5] = ") &&
                   before(target, "event_Arrays_values[event_Arrays_write_target_map] = 9.0;",
                          "event_Arrays_snapshot[event_Arrays_snapshot_copy_index] = ") &&
                   before(target, "event_Arrays_snapshot[event_Arrays_snapshot_copy_index] = ",
                          "event_Arrays_local[event_Arrays_local_copy_index] = ") &&
                   before(target, "event_Arrays_local[event_Arrays_local_copy_index] = ",
                          "event_Arrays_localRead[event_Arrays_localRead_copy_index] = "),
               "per-occurrence root initializes once while lexical copy retains entry ordering"))
      return 1;
    if (kind == nodal::BackendKind::VerilogA)
      fixed = target;
  }

  for (int64_t count : {int64_t(0), int64_t(1), int64_t(1000000)}) {
    std::string target;
    const auto source = wrap(generate(procedure(root()), scope, "i", 0, count));
    if (!emit(source, "count-" + std::to_string(count), target) ||
        !check(target.size() < 5000 &&
                   llvm::StringRef(target).count("real event_Arrays_values[0:5] = '{6{2.5}};") ==
                       1 &&
                   contains(target, "i < " + std::to_string(count)) &&
                   !contains(target, "copy_index") && !contains(target, "event_Arrays_valuesFill"),
               "empty, singleton and large domains retain a compact structural declaration"))
      return 1;
  }

  auto symbolicGenerate = generate(procedure(root("values", "COUNT", "Arrays.LEVEL")));
  if (!check(replace(symbolicGenerate, "upper = 2 : i64", "upper = @COUNT") &&
                 replace(symbolicGenerate, "maximum_trip_count = 2 : i64",
                         "maximum_trip_count = 8 : i64"),
             "symbolic domain fixture anchors"))
    return 1;
  for (auto kind : {nodal::BackendKind::VerilogA, nodal::BackendKind::VerilogAMS}) {
    std::string target;
    if (!emit(wrap(symbolicGenerate, parameters()),
              kind == nodal::BackendKind::VerilogA ? "symbolic-va" : "symbolic-ams", target,
              kind) ||
        !check(contains(target, "real event_Arrays_values[0:(COUNT - 1)] = '{COUNT{LEVEL}};") &&
                   contains(target, "i < COUNT") && contains(target, "parameter real LEVEL") &&
                   !contains(target, "event_Arrays_valuesFill") && !contains(target, "copy_index"),
               "static parameter initializer and occurrence/shape extents remain symbolic"))
      return 1;
  }

  // Domain arithmetic is width-checked independently of the shape-index
  // capability. COUNT owns only topology; ROWS alone owns the array extent.
  auto rowParameters = parameters();
  size_t rename = 0;
  while ((rename = rowParameters.find("COUNT", rename)) != std::string::npos) {
    rowParameters.replace(rename, 5, "ROWS");
    rename += 4;
  }
  if (!check(replace(rowParameters, "effects = [\"shape\", \"topology\"]", "effects = [\"shape\"]"),
             "independent shape envelope anchor"))
    return 1;
  const auto domainParameter = literal("c5", 5) + R"mlir(
"nodal.parameter"() <{classification = "structural", parameter_kind = "integer", default_value = 3 : i64, sym_name = "COUNT", type = i64, variability = "symbolic", metadata = {semantic_path = "Arrays.COUNT"}}> : () -> ()
"nodal.parameter_constraint"(%c1, %c5) <{constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, parameter = @COUNT, metadata = {}}> : (i64, i64) -> ()
"nodal.parameter_envelope"() <{effects = ["topology"], policy = "static_generate", parameter = @COUNT, metadata = {}}> : () -> ()
%count = "nodal.const_parameter_ref"() <{parameter = @COUNT, metadata = {}}> : () -> i64
%upper = "nodal.const_expr"(%count, %c1) <{operator_name = "add", metadata = {semantic_path = "Arrays.upper"}}> : (i64, i64) -> i64
)mlir";
  std::string compound;
  if (!emit(wrap(compoundGenerate(procedure(root("values", "ROWS", "Arrays.LEVEL"))),
                 rowParameters + domainParameter),
            "compound-topology-domain", compound) ||
      !check(contains(compound, "for (i = 1; i < (COUNT + 1); i = i + 2) begin : gen") &&
                 contains(compound, "real event_Arrays_values[0:(ROWS - 1)] = '{ROWS{LEVEL}};"),
             "topology-only bounded SSA domain remains independent of symbolic storage shape"))
    return 1;

  const std::string inner = scope + ".inner", sibling = "Arrays.other";
  const auto siblingSource =
      wrap(generate(procedure(root("first", "2", "1.0"))) +
           generate(procedure(root("second", "3", "2.0"), sibling), sibling, "j", 1, 5, 2));
  std::string siblings;
  if (!emit(siblingSource, "siblings", siblings) ||
      !check(before(siblings, "begin : gen", "real event_Arrays_first[0:1] = '{2{1.0}};") &&
                 before(siblings, "real event_Arrays_first[0:1] = '{2{1.0}};", "begin : other") &&
                 before(siblings, "begin : other", "real event_Arrays_second[0:2] = '{3{2.0}};") &&
                 llvm::StringRef(siblings).count("analog begin") == 2 &&
                 contains(siblings, "for (j = 1; j < 5; j = j + 2) begin : other"),
             "sibling scopes retain independent declaration identities and authored order"))
    return 1;

  const auto nestedSource =
      wrap(generate(procedure(root("outer", "2", "1.0")) +
                    generate(procedure(root("inner", "3", "2.0"), inner), inner, "j", 0, 3)));
  std::string nested;
  if (!emit(nestedSource, "nested", nested) ||
      !check(before(nested, "begin : gen", "real event_Arrays_outer[0:1] = '{2{1.0}};") &&
                 before(nested, "begin : event_Arrays_procedure", "begin : inner") &&
                 before(nested, "begin : inner", "real event_Arrays_inner[0:2] = '{3{2.0}};") &&
                 llvm::StringRef(nested).count("analog begin") == 2 &&
                 llvm::StringRef(nested).count("endgenerate") == 1,
             "nested roots remain in their actual structural scopes without duplicate execution"))
    return 1;

  const auto viewBody =
      fill("fill", "2,3", "7.0") + index("row", "fill", "2,3", "%c1", "i64", "3", false) +
      "%tail = \"nodal.shape_slice\"(%row, %c1, %c3, %c2) <{axis = 0 : i64, dimensions = \"2\", "
      "origin = \"Arrays.row\", observability = \"source_mapped\", owner = \"Arrays\", metadata = "
      "{semantic_path = \"Arrays.tail\", input_path = \"Arrays.row\", storage = \"structural\", "
      "access = \"read_only\"}}> : (" +
      shaped("3", false) + ", i64, i64, i64) -> " + shaped("2", false) +
      "\n%view = \"nodal.shape_view\"(%tail) <{dimensions = \"1,2\", origin = \"Arrays.tail\", "
      "materialization = \"view\", observability = \"source_mapped\", owner = \"Arrays\", metadata "
      "= {semantic_path = \"Arrays.view\", input_path = \"Arrays.tail\", storage = \"structural\", "
      "access = \"read_only\"}}> : (" +
      shaped("2", false) + ") -> " + shaped("1,2", false) + "\n" +
      declaration("values", "1,2", "view");
  std::string viewed;
  if (!emit(wrap(generate(procedure(viewBody))), "view-initializer", viewed) ||
      !check(contains(viewed, "real event_Arrays_values[0:1] = '{2{7.0}};") &&
                 !contains(viewed, "event_Arrays_fill") && !contains(viewed, "event_Arrays_row") &&
                 !contains(viewed, "event_Arrays_tail") && !contains(viewed, "event_Arrays_view") &&
                 !contains(viewed, "copy_index") && !contains(viewed, "source_map"),
             "owned fill projection uses destination count and leaves no initializer carrier"))
    return 1;

  // This scalar projection is an actual non-declaration SSA consumer of the
  // shared fill. Its emitted runtime assignment must retain the fill carrier.
  std::string shared;
  if (!emit(wrap(generate(
                procedure(fill("sharedFill", "4", "3.5") +
                          index("sharedScalar", "sharedFill", "4", "%c2", "i64", "", false) +
                          declaration("values", "4", "sharedFill")))),
            "shared-runtime-fill", shared) ||
      !check(contains(shared, "real event_Arrays_values[0:3] = '{4{3.5}};") &&
                 before(shared, "begin : gen", "real event_Arrays_sharedFill;") &&
                 before(shared, "analog begin", "event_Arrays_sharedFill = 3.5;") &&
                 before(shared, "event_Arrays_sharedFill = 3.5;",
                        "event_Arrays_sharedScalar = event_Arrays_sharedFill;") &&
                 !contains(shared, "event_Arrays_values_copy_index"),
             "shared initializer fill retains its actual ordered runtime consumer"))
    return 1;

  std::string eventTarget;
  if (!emit(wrap(generate(procedure(
                root() + event(index("eventCell", "values", "2,3", "%c1, %c2", "i64, i64") +
                               scalarWrite("eventWrite", "eventCell", "8.0"))))),
            "generated-event", eventTarget) ||
      !check(
          before(eventTarget, "begin : gen", "real event_Arrays_values[0:5] = '{6{2.5}};") &&
              before(eventTarget, "real event_Arrays_values[0:5] = '{6{2.5}};", "analog begin") &&
              llvm::StringRef(eventTarget).count("@(") == 1 &&
              before(eventTarget, "@(initial_step)",
                     "event_Arrays_values[event_Arrays_eventWrite_target_map] = 8.0;") &&
              !contains(eventTarget, "event_Arrays_values_copy_index"),
          "generated event observes persistent declaration state without entry reset"))
    return 1;

  const auto child = module(
      "Child",
      R"mlir("nodal.component_contract"() <{kind = "partial", connectivity_ownership = "extensible", source_path = "Child", metadata = {}}> : () -> ()
%p = "nodal.terminal"() <{name = "p", direction = "inout", flow_orientation = "into_component", source_path = "Child.p", metadata = {semantic_path = "Child.p"}}> : () -> !nodal.terminal<"electrical">
)mlir");
  const auto parentTopology =
      R"mlir("nodal.component_contract"() <{kind = "partial", connectivity_ownership = "extensible", source_path = "Arrays", metadata = {}}> : () -> ()
%shared = "nodal.node"() <{name = "shared", source_path = "Arrays.shared", metadata = {semantic_path = "Arrays.shared"}}> : () -> !nodal.terminal<"electrical">
)mlir";
  std::string mixed;
  if (!emit(wrap(generate(childInstance("u") + childInstance("v") + procedure(root())),
                 parentTopology, discipline + child),
            "mixed-child-state", mixed) ||
      !check(llvm::StringRef(mixed).count("module Child(p);") == 1 &&
                 llvm::StringRef(mixed).count("Child u(.p(shared));") == 1 &&
                 llvm::StringRef(mixed).count("Child v(.p(shared));") == 1 &&
                 before(mixed, "begin : gen", "real event_Arrays_values[0:5] = '{6{2.5}};") &&
                 before(mixed, "Child v(.p(shared));", "analog begin") &&
                 llvm::StringRef(mixed).count("analog begin") == 1,
             "reusable child definition and persistent state coexist per structural occurrence"))
    return 1;

  auto childState = generate(procedure(root("childValues", "2", "4.5"), "Arrays.childGen"),
                             "Arrays.childGen", "k");
  size_t childRename = 0;
  while ((childRename = childState.find("Arrays", childRename)) != std::string::npos) {
    childState.replace(childRename, 6, "Child");
    childRename += 5;
  }
  auto statefulChild = child;
  const auto childEnd = statefulChild.rfind("}) : () -> ()");
  if (!check(childEnd != std::string::npos, "child definition insertion anchor"))
    return 1;
  statefulChild.insert(childEnd, childState);
  std::string reused;
  const std::string childInitializer = "real event_Child_childValues[0:1] = '{2{4.5}};";
  if (!emit(wrap(generate(childInstance("u") + childInstance("v") + procedure(root())),
                 parentTopology, discipline + statefulChild),
            "reused-child-persistent-state", reused))
    return 1;
  const auto childModuleStart = reused.find("module Child(p);");
  const auto childModuleEnd = childModuleStart == std::string::npos
                                  ? std::string::npos
                                  : reused.find("endmodule", childModuleStart);
  const auto childDefinition =
      childModuleStart == std::string::npos || childModuleEnd == std::string::npos
          ? std::string()
          : reused.substr(childModuleStart, childModuleEnd - childModuleStart);
  if (!check(llvm::StringRef(reused).count("module Child(p);") == 1 &&
                 llvm::StringRef(reused).count(childInitializer) == 1 &&
                 before(childDefinition, "begin : childGen", childInitializer) &&
                 before(childDefinition, childInitializer, "analog begin") &&
                 before(reused, "module Arrays;", "Child u(.p(shared));") &&
                 llvm::StringRef(reused).count("Child u(.p(shared));") == 1 &&
                 llvm::StringRef(reused).count("Child v(.p(shared));") == 1 &&
                 contains(reused, "real event_Arrays_values[0:5] = '{6{2.5}};") &&
                 llvm::StringRef(reused).count("analog begin") == 2 &&
                 !contains(reused, "event_Child_childValues_copy_index"),
             "reused child definition keeps its own generated state private to every instance"))
    return 1;

  // Preparation is a capability seam, not an authority supplied by a textual
  // owner. Both omitted legacy context and a forged structural ancestry reject
  // before changing the caller's declaration stream.
  for (const auto &contextSource : {nestedSource, siblingSource}) {
    auto contexts = mlir::parseSourceString<mlir::ModuleOp>(contextSource, &context);
    if (!check(bool(contexts), "context fixture parses"))
      return 1;
    std::vector<mlir::Operation *> procedures, generates;
    contexts->walk([&](mlir::Operation *operation) {
      if (operation->getName().getStringRef() == "nodal.analog_procedure")
        procedures.push_back(operation);
      if (operation->getName().getStringRef() == "nodal.generate")
        generates.push_back(operation);
    });
    if (!check(procedures.size() == 2 && generates.size() == 2, "actual context fixture ancestry"))
      return 1;
    for (auto *procedure : procedures) {
      const auto *actual = procedure->getParentOp()->getParentOp();
      for (auto *candidate : generates) {
        nodal::AnalogEventRenderState state;
        std::string declarations = "sentinel";
        llvm::raw_string_ostream stream(declarations);
        const auto status = nodal::prepareGeneratedAnalogEventBackend(
            procedure, state, stream, 2,
            {nodal::GeneratedAnalogDeclarationContext::Kind::StructuralOccurrence, candidate});
        stream.flush();
        if (!check(candidate == actual ? mlir::succeeded(status) && declarations != "sentinel"
                                       : mlir::failed(status) && declarations == "sentinel",
                   "only the actual direct structural scope supplies declaration lifetime"))
          return 1;
      }
      nodal::AnalogEventRenderState state;
      std::string declarations = "sentinel";
      llvm::raw_string_ostream stream(declarations);
      const auto status = nodal::prepareGeneratedAnalogEventBackend(procedure, state, stream, 2);
      stream.flush();
      if (!check(mlir::failed(status) && declarations == "sentinel",
                 "legacy analog-iteration context still rejects persistent shaped initialization"))
        return 1;
    }
  }

  auto targetConfiguration = nodal::resolveBackendConfiguration(
      *mlir::parseSourceString<mlir::ModuleOp>(fixedSource, &context),
      nodal::BackendKind::VerilogA);
  for (const auto &[source, original, replacement] :
       std::vector<std::tuple<std::string, std::string, std::string>>{
           {fixed, "= '{6{2.5}};", "= '{5{2.5}};"},
           {shared, "= '{4{3.5}};", "= '{4{event_Arrays_sharedFill}};"}}) {
    auto invalid = source;
    if (!check(replace(invalid, original, replacement),
               "scoped target initializer mutation anchor") ||
        !check(mlir::succeeded(targetConfiguration) &&
                   mlir::failed(nodal::reparseBackendTarget(invalid, *targetConfiguration)),
               "scoped target parser rejects replication mismatch and mutable scalar initializer"))
      return 1;
  }

  auto rejectTarget = [&](const std::string &source, const std::string &name) {
    auto parsed = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (!check(parsed && mlir::succeeded(mlir::verify(*parsed)), name + " is native-valid"))
      return false;
    std::string target = "sentinel";
    return check(!render(*parsed, target, nodal::BackendKind::VerilogA) && target == "sentinel",
                 name + " rejects transactionally");
  };
  const auto compoundOverflow =
      literal("domainWide", 4000000000LL) + literal("domainNear", 3999999996LL) +
      "%upper = \"nodal.const_expr\"(%domainWide, %domainNear) <{operator_name = \"sub\", "
      "metadata = {semantic_path = \"Arrays.upper\"}}> : (i64, i64) -> i64\n";
  if (!rejectTarget(wrap(compoundGenerate(procedure(root()), 2), compoundOverflow),
                    "signed32 structural expression intermediate overflow"))
    return 1;
  for (const auto &[lower, upper, step] :
       std::vector<std::tuple<int64_t, int64_t, int64_t>>{{2147483648LL, 2147483649LL, 1},
                                                          {2147483646LL, 2147483647LL, 2},
                                                          {-2147483647LL, 2147483647LL, 1}}) {
    if (!rejectTarget(wrap(generate(procedure(root()), scope, "i", lower, upper, step)),
                      "signed32 structural domain/count/final update overflow"))
      return 1;
  }
  const auto wideExpression =
      literal("wide", 4000000000LL) + literal("near", 3999999996LL) +
      "%small = \"nodal.const_expr\"(%wide, %near) <{operator_name = \"sub\", metadata = "
      "{semantic_path = \"Arrays.small\"}}> : (i64, i64) -> i64\n";
  for (const auto &dimensions : {"2147483648", "46341,46341", "Arrays.small"}) {
    if (!rejectTarget(wrap(generate(procedure(root("values", dimensions))), wideExpression),
                      "shape extent/count/intermediate overflow") ||
        !rejectTarget(
            wrap(generate(procedure(root("values", dimensions)), scope, "i", 0, 0), wideExpression),
            "empty generated domain still validates initializer shape"))
      return 1;
  }
  std::string boundary;
  if (!emit(wrap(generate(procedure(root("values", "2147483647", "6.5")))), "signed32-boundary",
            boundary) ||
      !check(boundary.size() < 5000 &&
                 contains(boundary, "real event_Arrays_values[0:2147483646] = '{2147483647{6.5}};"),
             "maximum signed32 cardinality stays compact"))
    return 1;

  const auto live = root("source", "4") + index("first", "source", "4", "%c0", "i64") +
                    read("captured", "first") +
                    fill("liveFill", "4", "Arrays.first", "%captured", quantity) +
                    declaration("values", "4", "liveFill");
  const auto duplicateProcedures = procedure(root("first")) + procedure(root("second"));
  auto ordinaryDuplicates = duplicateProcedures;
  const std::string generatedOwnership =
      "generated_owner = \"Arrays.gen\", generated_induction = \"Arrays.gen.index\", ";
  while (replace(ordinaryDuplicates, generatedOwnership, "")) {
  }
  for (const auto &source : {wrap(generate(duplicateProcedures)), wrap(ordinaryDuplicates)}) {
    std::string diagnostics;
    mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &diagnostic) {
      llvm::raw_string_ostream stream(diagnostics);
      diagnostic.print(stream);
      return mlir::success();
    });
    auto parsed = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    const bool nativeValid = parsed && mlir::succeeded(mlir::verify(*parsed));
    std::string target = "sentinel";
    if (!check(
            !nativeValid && (!parsed || !render(*parsed, target, nodal::BackendKind::VerilogA)) &&
                target == "sentinel" && contains(diagnostics, "NODAL-ANALOG-033-020"),
            "duplicate procedures in one actual Generate or ordinary module remain native-invalid"))
      return 1;
  }
  const auto malformedSource = wrap(generate(procedure(root())));
  for (const auto &[name, original, replacement, diagnostic] :
       std::vector<std::tuple<std::string, std::string, std::string, std::string>>{
           {"foreign-owner", "owner = \"Arrays\", declaration_order",
            "owner = \"Foreign\", declaration_order", "NODAL-ANALOG-034-014"},
           {"forged-generated-owner", "generated_owner = \"Arrays.gen\", generated_induction",
            "generated_owner = \"Arrays.other\", generated_induction", "NODAL-ITERATION-043-004"},
           {"malformed-initializer", "initialized = true", "initialized = false",
            "NODAL-ANALOG-033-004"},
           {"zero-shape", "2,3", "0,3", "invalid shaped dimension token '0'"}}) {
    auto source = malformedSource;
    if (!check(replace(source, original, replacement), name + " mutation anchor"))
      return 1;
    if (name == "zero-shape")
      while (replace(source, original, replacement)) {
      }
    std::string diagnostics;
    mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &diagnostic) {
      llvm::raw_string_ostream stream(diagnostics);
      diagnostic.print(stream);
      return mlir::success();
    });
    auto parsed = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    const bool nativeValid = parsed && mlir::succeeded(mlir::verify(*parsed));
    std::string target = "sentinel";
    if (!check(!nativeValid &&
                   (!parsed || !render(*parsed, target, nodal::BackendKind::VerilogA)) &&
                   target == "sentinel" && contains(diagnostics, diagnostic),
               name + " fails at the native boundary without publishing output"))
      return 1;
  }
  for (int64_t count : {int64_t(0), int64_t(2)}) {
    const auto source = wrap(generate(procedure(live), scope, "i", 0, count));
    std::string diagnostics;
    mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &diagnostic) {
      llvm::raw_string_ostream stream(diagnostics);
      diagnostic.print(stream);
      return mlir::success();
    });
    auto parsed = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (!check(
            (!parsed || mlir::failed(mlir::verify(*parsed))) &&
                contains(
                    diagnostics,
                    "persistent shaped initialization requires static fill without mutable reads"),
            "live initializer remains invalid even when no occurrence is elaborated"))
      return 1;
  }
  llvm::outs() << checks << " generated-persistent backend checks passed\n";
  return 0;
}
