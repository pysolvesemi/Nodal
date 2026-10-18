#include "circt/Dialect/HW/HWDialect.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Backend/AnalogVerticalSlice.h"
#include "nodal/Backend/Backend.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/raw_ostream.h"

#include <cstdint>
#include <fstream>
#include <string>
#include <tuple>
#include <utility>
#include <vector>

namespace {
const std::string terminal = "!nodal.terminal<\"electrical\">";
const std::string scope = "Parent.gen";

std::string shape(const std::string &dimensions) {
  return "!nodal.shaped<\"" + dimensions + "\", " + terminal + ">";
}

bool replace(std::string &text, const std::string &before, const std::string &after) {
  auto offset = text.find(before);
  if (offset == std::string::npos)
    return false;
  text.replace(offset, before.size(), after);
  return true;
}

std::string owned(std::string text, const std::string &owner = scope) {
  replace(text, "metadata = {",
          "generated_owner = \"" + owner + "\", generated_induction = \"" + owner +
              ".index\", metadata = {");
  return text;
}

std::string scalar(const std::string &owner, const std::string &name, bool boundary = false) {
  return "%" + name + " = \"nodal." + (boundary ? "terminal" : "node") + "\"() <{name = \"" + name +
         "\", " +
         (boundary ? "direction = \"inout\", flow_orientation = \"into_component\", " : "") +
         "source_path = \"" + owner + "." + name + "\", metadata = {semantic_path = \"" + owner +
         "." + name + "\"}}> : () -> " + terminal + "\n";
}

std::string array(const std::string &owner, const std::string &name, const std::string &dimensions,
                  bool boundary = false) {
  auto text = scalar(owner, name, boundary);
  replace(text, boundary ? "nodal.terminal\"" : "nodal.node\"",
          boundary ? "nodal.terminal_array\"" : "nodal.node_array\"");
  replace(text, "metadata = {", "metadata = {storage = \"structural\", ");
  replace(text, terminal, shape(dimensions));
  return text;
}

std::string module(const std::string &name, const std::string &body) {
  return "\"nodal.module\"() <{sym_name = \"" + name + "\", metadata = {semantic_path = \"" + name +
         "\"}}> ({\n"
         "\"nodal.component_contract\"() <{kind = \"partial\", connectivity_ownership = "
         "\"extensible\", source_path = \"" +
         name + "\", metadata = {}}> : () -> ()\n" + body + "}) : () -> ()\n";
}

std::string design(const std::string &child, const std::string &parent) {
  return R"mlir(module attributes {nodal.target.profile = "analog"} {
"nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", dimension = "voltage", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
"nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", dimension = "current", metadata = {}, sym_name = "Current", units = "A"}> : () -> ()
"nodal.discipline"() <{domain = "continuous", flow = @Current, potential = @Voltage, metadata = {}, sym_name = "electrical"}> : () -> ()
)mlir" + module("Child", child) +
         module("Parent", parent) + "}\n";
}

std::string generate(const std::string &body, const std::string &owner = scope,
                     const std::string &index = "i", int64_t lower = 0, int64_t upper = 3,
                     int64_t step = 1) {
  const int64_t count = upper <= lower ? 0 : (upper - lower - 1) / step + 1;
  return "\"nodal.generate\"() <{induction = \"" + index + "\", lower = " + std::to_string(lower) +
         " : i64, upper = " + std::to_string(upper) + " : i64, step = " + std::to_string(step) +
         " : i64, region_id = \"" + owner + "\", induction_path = \"" + owner +
         ".index\", metadata = {maximum_trip_count = " + std::to_string(count) +
         " : i64}}> ({\n^bb0(%" + index + ": i64):\n" + body + "}) : () -> ()\n";
}

std::string connect(const std::string &actual, const std::string &reference,
                    const std::string &path, const std::string &dimensions = "") {
  const auto type = dimensions.empty() ? terminal : shape(dimensions);
  return "\"nodal." + std::string(dimensions.empty() ? "connect" : "connect_array") + "\"(%" +
         actual + ", %" + reference + ") <{connection_id = \"" + path + "\", source_path = \"" +
         path + "\", metadata = {semantic_path = \"" + path + "\"}}> : (" + type + ", " + type +
         ") -> ()\n";
}

std::string boundChild(const std::string &actual = "shared", const std::string &owner = scope,
                       const std::string &instance = "u", const std::string &reference = "ref",
                       const std::string &dimensions = "") {
  const auto type = dimensions.empty() ? terminal : shape(dimensions);
  return owned("\"nodal.instance\"() <{sym_name = \"" + instance +
                   "\", module = @Child, parameter_bindings = {}, domain_bindings = {}, "
                   "metadata = {semantic_path = \"" +
                   owner + "." + instance + "\"}}> : () -> ()\n",
               owner) +
         owned("%" + reference + " = \"nodal." +
                   (dimensions.empty() ? "instance_terminal" : "instance_terminal_array") +
                   "\"() <{instance = @" + instance + ", port = \"p\", name = \"" + instance +
                   ".p\", direction = \"inout\", flow_orientation = \"out_of_component\", "
                   "source_path = \"Child.p\", metadata = {semantic_path = \"" +
                   owner + "." + reference + "\"" +
                   (dimensions.empty() ? "" : ", storage = \"structural\"") + "}}> : () -> " +
                   type + "\n",
               owner) +
         owned(connect(actual, reference, owner + ".binding_" + reference, dimensions), owner);
}

std::string selected(const std::string &dimensions = "3", const std::string &owner = scope,
                     const std::vector<std::pair<std::string, std::string>> &indices = {
                         {"i", "Parent.gen.index"}}) {
  std::string operands = "%nodes", types = shape(dimensions), identity;
  for (const auto &[value, path] : indices) {
    operands += ", %" + value;
    types += ", i64";
    identity += (identity.empty() ? "" : ",") + std::string("induction:") + path;
  }
  return owned("%selected = \"nodal.shape_index\"(" + operands +
                   ") <{metadata = {input_path = \"Parent.nodes\", semantic_path = "
                   "\"Parent.nodes.at(" +
                   identity + ").in(" + owner + ")\", source_uses = [\"" + owner +
                   ".use_selected\"], storage = \"structural\", selection_kind = "
                   "\"node_array\"}}> : (" +
                   types + ") -> " + terminal + "\n",
               owner);
}

std::string literal(const std::string &name, int64_t value) {
  const auto number = std::to_string(value);
  return "%" + name + " = \"nodal.const_literal\"() <{spelling = \"" + number +
         "\", value = " + number + " : i64, metadata = {}}> : () -> i64\n";
}

std::string parameter(const std::string &owner, const std::string &name, int64_t upper) {
  return "\"nodal.parameter\"() <{classification = \"structural\", default_value = 3 : i64, "
         "parameter_kind = \"integer\", sym_name = \"" +
         name + "\", type = i64, variability = \"symbolic\", metadata = {semantic_path = \"" +
         owner + "." + name + "\"}}> : () -> ()\n" + literal(name + "_low", 2) +
         literal(name + "_high", upper) + "\"nodal.parameter_constraint\"(%" + name + "_low, %" +
         name +
         "_high) <{constraint_kind = \"range\", lower_inclusive = true, "
         "upper_inclusive = true, parameter = @" +
         name +
         ", metadata = {}}> : (i64, i64) -> ()\n"
         "\"nodal.parameter_envelope\"() <{effects = [\"shape\"], policy = \"static_generate\", "
         "parameter = @" +
         name + ", metadata = {}}> : () -> ()\n%" + name +
         " = \"nodal.const_parameter_ref\"() <{parameter = @" + name +
         ", metadata = {}}> : () -> i64\n";
}

std::string noncontiguousView() {
  return literal("one", 1) + literal("two", 2) + literal("three", 3) +
         "%columns = \"nodal.shape_slice\"(%nodes, %one, %three, %two) <{axis = 1 : i64, "
         "dimensions = \"2,2\", origin = \"Parent.nodes\", observability = \"source_mapped\", "
         "metadata = {input_path = \"Parent.nodes\", semantic_path = \"Parent.columns\", "
         "storage = \"structural\", access = \"read_only\"}}> : (" +
         shape("2,4") + ", i64, i64, i64) -> " + shape("2,2") + "\n";
}

std::string analogPayload(const std::string &name = "sense", const std::string &owner = scope) {
  return owned("%branch_" + name + " = \"nodal.branch\"(%local, %shared) <{name = \"" + name +
                   "\", declaration_kind = \"named\", source_path = \"" + owner + "." + name +
                   "\", metadata = {semantic_path = \"" + owner + "." + name + "\"}}> : (" +
                   terminal + ", " + terminal + ") -> !nodal.branch<\"electrical\">\n",
               owner) +
         owned("\"nodal.analog\"() <{metadata = {}}> ({\n", owner) +
         "%potential = \"nodal.real_literal\"() <{value = 1.0 : f64, metadata = {}}> : () -> "
         "f64\n" +
         owned("\"nodal.contribute\"(%branch_" + name +
                   ", %potential) <{kind = \"potential\", "
                   "metadata = {}}> : (!nodal.branch<\"electrical\">, f64) -> ()\n",
               owner) +
         "}) : () -> ()\n";
}

// Root scalar initialization already has declaration lifetime. Keep its value
// observable through a procedure read without authoring any reset of that state.
std::string initializedProcedure() {
  std::string text = R"mlir("nodal.analog"() <{metadata = {}}> ({
"nodal.analog_procedure"() <{owner = "Parent", metadata = {}}> ({
%state = "nodal.analog_variable"() <{identity = "Parent.state", owner = "Parent", declaration_order = 0 : i64, initialized = true, initializer_value = "1.0", initializer_kind = "real", initializer_dimension = "1", initializer_reads = [], metadata = {semantic_path = "Parent.state"}}> : () -> !nodal.variable<"real", "1">
%sink = "nodal.analog_variable"() <{identity = "Parent.sink", owner = "Parent", declaration_order = 1 : i64, initialized = false, initializer_value = "", initializer_kind = "", initializer_dimension = "", initializer_reads = [], metadata = {semantic_path = "Parent.sink"}}> : () -> !nodal.variable<"real", "1">
%state_read = "nodal.analog_variable_read"(%state) <{read_id = "Parent.read_state", owner = "Parent", metadata = {semantic_path = "Parent.read_state"}}> : (!nodal.variable<"real", "1">) -> !nodal.quantity<"real", "1">
"nodal.analog_assign"(%sink, %state_read) <{statement_id = "Parent.copy", owner = "Parent", authored_order = 0 : i64, value_kind = "real", value_dimension = "1", analyses = ["dc", "transient"], guard_present = false, guard_value = "", guard_kind = "", guard_dimension = "", guard_reads = [], metadata = {semantic_path = "Parent.copy", value = "Parent.state"}}> : (!nodal.variable<"real", "1">, !nodal.quantity<"real", "1">) -> ()
}) : () -> ()
}) : () -> ()
)mlir";
  const std::string attributes = "generated_owner = \"Parent.gen\", "
                                 "generated_induction = \"Parent.gen.index\", ";
  size_t offset = 0;
  while ((offset = text.find("<{", offset)) != std::string::npos) {
    text.insert(offset + 2, attributes);
    offset += 2 + attributes.size();
  }
  return text;
}

nodal::BackendConfiguration configuration(nodal::BackendKind kind) {
  return {&nodal::getBackendProfile(kind), nodal::GateProfile::Default,
          nodal::ShapedValueLayout::ScalarOrFlat, nodal::MaterializationPolicy::SafeInline,
          nodal::NamingPolicy::Semantic};
}

bool contains(const std::string &text, llvm::StringRef expected) {
  return llvm::StringRef(text).contains(expected);
}

// The source induction is the array coordinate. It is not an occurrence ordinal,
// even when the first attained coordinate is nonzero or the step is not one.
bool sourceIndexActual(const std::string &target, const std::string &index = "i") {
  return contains(target, "Child u(.p(nodes[" + index + "]));") ||
         (contains(target, "localparam integer node_map_nodes_0_0 = " + index + ";") &&
          contains(target, "Child u(.p(nodes[node_map_nodes_0_0]));"));
}
} // namespace

int main(int argc, char **argv) {
  mlir::MLIRContext context;
  context.loadDialect<nodal::NodalDialect, circt::hw::HWDialect>();
  unsigned checks = 0;
  auto check = [&](bool valid, const std::string &message) {
    ++checks;
    if (!valid)
      llvm::errs() << "GeneratedTopologyBackendTest: " << message << "\n";
    return valid;
  };
  auto emit = [&](const std::string &source, const std::string &name, std::string &target,
                  nodal::BackendKind kind = nodal::BackendKind::VerilogA) {
    auto parsed = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (!check(parsed && mlir::succeeded(mlir::verify(*parsed)), name + " native fixture"))
      return false;
    auto render = [&](mlir::ModuleOp module, std::string &result) {
      llvm::raw_string_ostream stream(result);
      auto status = nodal::emitBackend(module, kind, stream);
      stream.flush();
      return mlir::succeeded(status);
    };
    if (!check(render(*parsed, target), name + " emission"))
      return false;
    std::string repeated, nativeText, replayed;
    llvm::raw_string_ostream nativeStream(nativeText);
    parsed->print(nativeStream);
    nativeStream.flush();
    auto reparsed = mlir::parseSourceString<mlir::ModuleOp>(nativeText, &context);
    if (!check(render(*parsed, repeated) && repeated == target && reparsed &&
                   mlir::succeeded(mlir::verify(*reparsed)) && render(*reparsed, replayed) &&
                   replayed == target,
               name + " repeated and native-roundtrip emission is deterministic") ||
        !check(mlir::succeeded(nodal::reparseBackendTarget(target, configuration(kind))),
               name + " independent target reparse"))
      return false;
    if (argc > 1) {
      std::ofstream(std::string(argv[1]) + "/" + name + ".mlir") << source;
      std::ofstream(std::string(argv[1]) + "/" + name +
                    (kind == nodal::BackendKind::VerilogA ? ".va" : ".vams"))
          << target;
    }
    return true;
  };
  const auto scalarChild = scalar("Child", "p", true);
  const auto shared = scalar("Parent", "shared");
  const auto sharedSource = design(scalarChild, shared + generate(boundChild()));
  std::string compact;
  if (!emit(sharedSource, "shared", compact) ||
      !check(llvm::StringRef(compact).count("module Child(p);") == 1 &&
                 llvm::StringRef(compact).count("Child u(.p(shared));") == 1 &&
                 contains(compact, "for (i = 0; i < 3; i = i + 1) begin : gen") &&
                 !contains(compact, "analog begin"),
             "one child definition and one structural template share the parent scalar"))
    return 1;
  for (int64_t upper : {int64_t(0), int64_t(1), int64_t(1000000)}) {
    std::string target;
    if (!emit(design(scalarChild, shared + generate(boundChild(), scope, "i", 0, upper)),
              "count-" + std::to_string(upper), target) ||
        !check(target.size() < 4000 && llvm::StringRef(target).count("Child u(.p(shared));") == 1 &&
                   contains(target, "i < " + std::to_string(upper)),
               "empty, singleton and million-occurrence domains retain compact templates"))
      return 1;
  }

  const auto local = owned(scalar(scope, "local"));
  const auto joinedSource =
      design(scalarChild, shared + generate(local + boundChild("local") +
                                            boundChild("local", scope, "v", "other") +
                                            owned(connect("shared", "local", scope + ".join"))));
  std::string joined;
  if (!emit(joinedSource, "local-join", joined) ||
      !check(contains(joined, "electrical local;") &&
                 joined.find("begin : gen") < joined.find("electrical local;") &&
                 joined.find("electrical local;") < joined.find("endgenerate") &&
                 ((contains(joined, "Child u(.p(local));") &&
                   contains(joined, "Child v(.p(local));")) ||
                  (contains(joined, "Child u(.p(shared));") &&
                   contains(joined, "Child v(.p(shared));"))) &&
                 (contains(joined, "(.lhs(local), .rhs(shared));") ||
                  contains(joined, "(.lhs(shared), .rhs(local));")) &&
                 llvm::StringRef(joined).count("nodal_alias_electrical topology_join_") == 1 &&
                 contains(joined, "module nodal_alias_electrical(.lhs(n), .rhs(n));") &&
                 !contains(joined, "analog begin") && !contains(joined, "assign "),
             "two child templates and a per-occurrence local use one true shared-net join"))
    return 1;

  std::string indexed;
  const auto indexedSource = design(scalarChild, array("Parent", "nodes", "3") +
                                                     generate(selected() + boundChild("selected")));
  if (!emit(indexedSource, "selected", indexed) ||
      !check(sourceIndexActual(indexed) && contains(indexed, "electrical [0:2] nodes;"),
             "direct active induction selects the actual parent coordinate"))
    return 1;
  auto collisionParameter = parameter("Parent", "i", 6);
  if (!check(replace(collisionParameter, "%i = ", "%parameter_i = "),
             "parameter/induction collision source anchor"))
    return 1;
  std::string collision;
  if (!emit(design(scalarChild, collisionParameter + array("Parent", "nodes", "3") +
                                    generate(selected() + boundChild("selected"))),
            "induction-name-collision", collision))
    return 1;
  const auto genvarStart = collision.find("genvar ");
  if (!check(genvarStart != std::string::npos, "collision retains an actual genvar declaration"))
    return 1;
  const auto indexStart = genvarStart + std::string("genvar ").size();
  const auto indexEnd = collision.find(';', indexStart);
  const auto renamedIndex = collision.substr(indexStart, indexEnd - indexStart);
  if (!check(renamedIndex != "i" && sourceIndexActual(collision, renamedIndex) &&
                 contains(collision, "parameter integer i = 3") &&
                 contains(collision, "for (" + renamedIndex + " = 0; " + renamedIndex + " < 3;"),
             "renamed induction still selects by SSA instead of the colliding module parameter"))
    return 1;

  std::string strided;
  if (!emit(design(scalarChild,
                   array("Parent", "nodes", "7") +
                       generate(selected("7") + boundChild("selected"), scope, "i", 1, 7, 2)),
            "strided", strided) ||
      !check(sourceIndexActual(strided) &&
                 contains(strided, "for (i = 1; i < 7; i = i + 2) begin : gen"),
             "nonzero positive stride preserves source indices instead of occurrence ordinals"))
    return 1;

  const std::string inner = scope + ".inner";
  const auto nestedSource =
      design(scalarChild,
             array("Parent", "nodes", "3,2") +
                 generate(generate(
                     selected("3,2", inner, {{"i", scope + ".index"}, {"j", inner + ".index"}}) +
                         boundChild("selected", inner),
                     inner, "j", 0, 2)));
  std::string nested;
  if (!emit(nestedSource, "nested", nested) ||
      !check(contains(nested, "for (i = 0; i < 3; i = i + 1) begin : gen") &&
                 contains(nested, "for (j = 0; j < 2; j = j + 1) begin : inner") &&
                 contains(nested, "((i * 2) + j)") &&
                 llvm::StringRef(nested).count("Child u(") == 1 &&
                 llvm::StringRef(nested).count("endgenerate") == 1 &&
                 contains(nested, "electrical [0:5] nodes;"),
             "nested rectangular scopes retain both source axes and row-major flattening"))
    return 1;

  const auto wholeSource = design(array("Child", "p", "2,3", true),
                                  array("Parent", "nodes", "2,3") +
                                      generate(boundChild("nodes", scope, "u", "ref", "2,3")));
  std::string renamedInstance;
  if (!emit(design(array("Child", "p", "2,3", true),
                   array("Parent", "nodes", "2,3") +
                       generate(boundChild("nodes", scope, "nodes", "ref", "2,3"))),
            "array-instance-collision", renamedInstance) ||
      !check(contains(renamedInstance, "Child nodes_1(.p(nodes));") &&
                 contains(renamedInstance, "\"instance\":\"nodes\"") &&
                 contains(renamedInstance, "\"target_instance\":\"nodes_1\""),
             "array binding correspondence retains actual renamed target instance identity"))
    return 1;
  const auto adapterSource = design(array("Child", "p", "2,2", true),
                                    array("Parent", "nodes", "2,4") + noncontiguousView() +
                                        generate(boundChild("columns", scope, "u", "ref", "2,2")));
  std::string adapter;
  for (auto kind : {nodal::BackendKind::VerilogA, nodal::BackendKind::VerilogAMS}) {
    const std::string suffix = kind == nodal::BackendKind::VerilogA ? "va" : "ams";
    std::string whole, target;
    if (!emit(wholeSource, "whole-" + suffix, whole, kind) ||
        !check(contains(whole, "Child u(.p(nodes));") && contains(whole, "inout [0:5] p;") &&
                   !contains(whole, "nodal_alias_electrical") &&
                   contains(whole, "\"source_scope\":\"Parent.gen\"") &&
                   contains(whole, "\"generated_scope\":\"\""),
               "invariant whole-array binding retains source scope without an adapter scope") ||
        !emit(adapterSource, "adapter-" + suffix, target, kind) ||
        !check(contains(target, "Child u(.p(binding_u_p_bus));") &&
                   contains(target, "electrical [0:(4 - 1)] binding_u_p_bus;") &&
                   contains(target, "(((binding_u_p_lane / 2) * 4) + "
                                    "((binding_u_p_lane % 2) + 1))") &&
                   contains(target, "link(.lhs(nodes[binding_u_p_stage_0]), "
                                    ".rhs(binding_u_p_bus[binding_u_p_lane]));") &&
                   llvm::StringRef(target).count("nodal_alias_electrical link(") == 1 &&
                   llvm::StringRef(target).count("endgenerate") == 1 &&
                   contains(target, "\"source_scope\":\"Parent.gen\"") &&
                   contains(target, "\"generated_scope\":\"binding_u_p_map\"") &&
                   !contains(target, "analog begin"),
               "invariant noncontiguous actual retains one compact nested alias adapter"))
      return 1;
    if (kind == nodal::BackendKind::VerilogA)
      adapter = target;
  }

  const auto override = owned("\"nodal.parameter_override\"(%N) <{instance = @u, parameter = @A, "
                              "metadata = {}}> : (i64) -> ()\n");
  std::string overridden;
  if (!emit(design(parameter("Child", "A", 8) + array("Child", "p", "A", true),
                   parameter("Parent", "N", 6) + array("Parent", "nodes", "N") +
                       generate(boundChild("nodes", scope, "u", "ref", "N") + override)),
            "invariant-override", overridden) ||
      !check(contains(overridden, "Child #(.A(N)) u(.p(nodes));") && contains(overridden, "i < 3"),
             "invariant parameter override follows its generated child without specialization"))
    return 1;

  std::string mixed;
  if (!emit(design(scalarChild, shared + generate(local + boundChild("local") + analogPayload())),
            "mixed-payload", mixed) ||
      !check(contains(mixed, "branch (local, shared) sense;") &&
                 llvm::StringRef(mixed).count("analog begin") == 1 &&
                 llvm::StringRef(mixed).count("V(sense) <+") == 1 &&
                 mixed.find("Child u(.p(local));") < mixed.find("analog begin"),
             "child topology remains structural while the analog contribution executes once per "
             "occurrence"))
    return 1;

  // Separate authored analog blocks concatenate after generate expansion in
  // textual order. A nested scope must not pull either surrounding effect across it.
  std::string ordered;
  const auto orderedSource = design(
      scalarChild,
      shared + generate(local + analogPayload("beforeSense") +
                        generate(boundChild("shared", inner) + analogPayload("innerSense", inner),
                                 inner, "j", 0, 2) +
                        analogPayload("afterSense")));
  if (!emit(orderedSource, "ordered-nested-payload", ordered) ||
      !check(llvm::StringRef(ordered).count("analog begin") == 3 &&
                 llvm::StringRef(ordered).count("V(beforeSense) <+") == 1 &&
                 llvm::StringRef(ordered).count("V(innerSense) <+") == 1 &&
                 llvm::StringRef(ordered).count("V(afterSense) <+") == 1 &&
                 ordered.find("V(beforeSense) <+") < ordered.find("begin : inner") &&
                 ordered.find("begin : inner") < ordered.find("Child u(.p(shared));") &&
                 ordered.find("Child u(.p(shared));") < ordered.find("V(innerSense) <+") &&
                 ordered.find("V(innerSense) <+") < ordered.find("V(afterSense) <+"),
             "authored parent/child/parent analog effects retain textual generate order"))
    return 1;

  std::string initialized;
  if (!emit(design(scalarChild, shared + generate(boundChild() + initializedProcedure())),
            "initialized-scalar-procedure", initialized) ||
      !check(
          contains(initialized, "real event_Parent_state = 1.0;") &&
              initialized.find("begin : gen") < initialized.find("real event_Parent_state") &&
              initialized.find("real event_Parent_state") < initialized.find("analog begin") &&
              initialized.find("Child u(.p(shared));") < initialized.find("analog begin") &&
              contains(initialized, "real event_Parent_read_state;") &&
              initialized.find("real event_Parent_read_state;") <
                  initialized.find("analog begin") &&
              llvm::StringRef(initialized).count("event_Parent_read_state = event_Parent_state;") ==
                  1 &&
              llvm::StringRef(initialized).count("event_Parent_sink = event_Parent_read_state;") ==
                  1 &&
              initialized.find("analog begin") <
                  initialized.find("event_Parent_read_state = event_Parent_state;") &&
              initialized.find("event_Parent_read_state = event_Parent_state;") <
                  initialized.find("event_Parent_sink = event_Parent_read_state;") &&
              llvm::StringRef(initialized).count("event_Parent_state = ") == 1 &&
              llvm::StringRef(initialized).count("analog begin") == 1,
          "mixed scalar initializer keeps declaration lifetime without a per-execution reset"))
    return 1;

  const auto config = configuration(nodal::BackendKind::VerilogA);
  for (const auto &[source, before, after] :
       std::vector<std::tuple<std::string, std::string, std::string>>{
           {compact, "Child u(.p(shared));", "Child u(.p(missing));"},
           {joined, ".rhs(n)", ".rhs(m)"},
           {nested, "genvar j;", "genvar absent;"},
           {adapter,
            "localparam integer binding_u_p_stage_0 = ", "localparam real binding_u_p_stage_0 = "},
           {adapter, ".rhs(binding_u_p_bus[binding_u_p_lane])", ".rhs(binding_u_p_bus[4])"}}) {
    std::string invalid = source;
    if (!check(replace(invalid, before, after), "target mutation anchor " + before) ||
        !check(mlir::failed(nodal::reparseBackendTarget(invalid, config)),
               "independent target rejection " + after))
      return 1;
  }

  // Native induction remains signed64. Narrowing only the structural target
  // carrier must fail before writing anything, including the valid child module.
  for (const auto &[lower, upper, step] : std::vector<std::tuple<int64_t, int64_t, int64_t>>{
           {2147483648LL, 2147483649LL, 1}, {2147483646LL, 2147483647LL, 2}}) {
    const auto source =
        design(scalarChild, shared + generate(boundChild(), scope, "i", lower, upper, step));
    auto parsed = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (!check(parsed && mlir::succeeded(mlir::verify(*parsed)),
               "signed64 domain or final update overflow fixture remains native-valid"))
      return 1;
    std::string sentinel = "sentinel";
    llvm::raw_string_ostream stream(sentinel);
    const auto status = nodal::emitBackend(*parsed, nodal::BackendKind::VerilogA, stream);
    stream.flush();
    if (!check(mlir::failed(status) && sentinel == "sentinel",
               "signed32 domain or final update overflow rejects transactionally"))
      return 1;
  }

  auto invalid = mlir::parseSourceString<mlir::ModuleOp>(sharedSource, &context);
  if (!invalid)
    return 1;
  bool changed = false;
  invalid->walk([&](mlir::Operation *operation) {
    if (operation->getName().getStringRef() == "nodal.instance_terminal") {
      operation->setAttr("port", mlir::StringAttr::get(&context, "missing"));
      changed = true;
    }
  });
  std::string sentinel = "sentinel";
  llvm::raw_string_ostream stream(sentinel);
  const auto status = nodal::emitBackend(*invalid, nodal::BackendKind::VerilogA, stream);
  stream.flush();
  if (!check(changed && mlir::failed(status) && sentinel == "sentinel",
             "malformed generated binding rejects before output publication"))
    return 1;
  llvm::outs() << checks << " generated-topology backend checks passed\n";
  return 0;
}
