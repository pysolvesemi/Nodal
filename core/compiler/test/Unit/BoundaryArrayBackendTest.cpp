#include "circt/Dialect/HW/HWDialect.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Backend/AnalogVerticalSlice.h"
#include "nodal/Backend/Backend.h"
#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"

#include "llvm/Support/JSON.h"
#include "llvm/Support/raw_ostream.h"

#include <fstream>
#include <string>

namespace {
const std::string terminal = "!nodal.terminal<\"electrical\">";
std::string shape(const std::string &dimensions) {
  return "!nodal.shaped<\"" + dimensions + "\", " + terminal + ">";
}
std::string array(const std::string &owner, const std::string &name, const std::string &dimensions,
                  const std::string &direction = "") {
  return "%" + name + " = \"nodal." + (direction.empty() ? "node_array" : "terminal_array") +
         "\"() <{name = \"" + name + "\", " +
         (direction.empty()
              ? ""
              : "direction = \"" + direction + "\", flow_orientation = \"into_component\", ") +
         "source_path = \"" + owner + "." + name + "\", metadata = {semantic_path = \"" + owner +
         "." + name + "\", storage = \"structural\"}}> : () -> " + shape(dimensions) + "\n";
}
std::string module(const std::string &name, const std::string &body) {
  return "\"nodal.module\"() <{sym_name = \"" + name + "\", metadata = {semantic_path = \"" + name +
         "\"}}> ({\n"
         "\"nodal.component_contract\"() <{kind = \"partial\", connectivity_ownership = "
         "\"extensible\", source_path = \"" +
         name + "\", metadata = {}}> : () -> ()\n" + body + "}) : () -> ()\n";
}
std::string literal(const std::string &name, unsigned value) {
  auto number = std::to_string(value);
  return "%" + name + " = \"nodal.const_literal\"() <{metadata = {}, spelling = \"" + number +
         "\", value = " + number + " : i64}> : () -> i64\n";
}
std::string fixture(const std::string &dimensions = "2,3", const std::string &actual = "nodes",
                    const std::string &view = "", const std::string &rootDimensions = "2,3",
                    const std::string &direction = "") {
  return "module attributes {nodal.target.profile = \"analog\"} {\n"
         "\"nodal.nature\"() <{abstol = 1.0e-6 : f64, access = \"V\", dimension = \"voltage\", "
         "metadata = {}, sym_name = \"Voltage\", units = \"V\"}> : () -> ()\n"
         "\"nodal.nature\"() <{abstol = 1.0e-12 : f64, access = \"I\", dimension = \"current\", "
         "metadata = {}, sym_name = \"Current\", units = \"A\"}> : () -> ()\n"
         "\"nodal.discipline\"() <{domain = \"continuous\", flow = @Current, potential = @Voltage, "
         "metadata = {}, sym_name = \"electrical\"}> : () -> ()\n" +
         module("Child", array("Child", "p", dimensions, "inout")) +
         module(
             "Parent",
             array("Parent", "nodes", rootDimensions, direction) + view +
                 "\"nodal.instance\"() <{sym_name = \"u\", module = @Child, parameter_bindings = "
                 "{}, "
                 "domain_bindings = {}, metadata = {semantic_path = \"Parent.u\"}}> : () -> ()\n"
                 "%ref = \"nodal.instance_terminal_array\"() <{instance = @u, port = \"p\", name = "
                 "\"u.p\", direction = \"inout\", flow_orientation = \"out_of_component\", "
                 "source_path = \"Child.p\", metadata = {semantic_path = \"Parent.ref\", storage = "
                 "\"structural\"}}> : () -> " +
                 shape(dimensions) +
                 "\n"
                 "\"nodal.connect_array\"(%" +
                 actual +
                 ", %ref) <{connection_id = \"Parent.binding\", "
                 "source_path = \"Parent.binding\", metadata = {semantic_path = "
                 "\"Parent.binding\"}}> : (" +
                 shape(dimensions) + ", " + shape(dimensions) + ") -> ()\n") +
         "}\n";
}
bool replace(std::string &text, const std::string &old, const std::string &value) {
  auto position = text.find(old);
  if (position == std::string::npos)
    return false;
  text.replace(position, old.size(), value);
  return true;
}
} // namespace

int main(int argc, char **argv) {
  mlir::MLIRContext context;
  context.loadDialect<nodal::NodalDialect, circt::hw::HWDialect>();
  unsigned checks = 0;
  auto check = [&](bool valid, const std::string &message) {
    ++checks;
    if (!valid)
      llvm::errs() << "BoundaryArrayBackendTest: " << message << "\n";
    return valid;
  };
  auto emit = [&](const std::string &source, const std::string &name, std::string &target,
                  nodal::BackendKind kind = nodal::BackendKind::VerilogA) {
    auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (!module || mlir::failed(mlir::verify(*module)))
      return false;
    llvm::raw_string_ostream stream(target);
    if (mlir::failed(nodal::emitBackend(*module, kind, stream)))
      return false;
    stream.flush();
    std::string repeated;
    llvm::raw_string_ostream repeatedStream(repeated);
    if (mlir::failed(nodal::emitBackend(*module, kind, repeatedStream)))
      return false;
    repeatedStream.flush();
    if (argc > 1) {
      std::ofstream(std::string(argv[1]) + "/" + name + ".mlir") << source;
      std::ofstream(std::string(argv[1]) + "/" + name +
                    (kind == nodal::BackendKind::VerilogA ? ".va" : ".vams"))
          << target;
    }
    return repeated == target;
  };
  auto configuration = nodal::BackendConfiguration{
      &nodal::getBackendProfile(nodal::BackendKind::VerilogA), nodal::GateProfile::Default,
      nodal::ShapedValueLayout::ScalarOrFlat, nodal::MaterializationPolicy::SafeInline,
      nodal::NamingPolicy::Semantic};
  std::string whole;
  if (!check(emit(fixture(), "whole", whole), "whole array emission") ||
      !check(whole.find("electrical [0:5] nodes;") != std::string::npos &&
                 whole.find("inout [0:5] p;") != std::string::npos &&
                 whole.find("Child u(.p(nodes));") != std::string::npos &&
                 whole.find("generate\n") == std::string::npos,
             "whole vector layout/binding"))
    return 1;
  for (const auto &direction : {"input", "output", "inout"}) {
    std::string target;
    if (!check(emit(fixture("2,3", "nodes", "", "2,3", direction), direction, target),
               "boundary actual direction " + std::string(direction)) ||
        !check(target.find(std::string(direction) + " [0:5] nodes;") != std::string::npos,
               "boundary actual range"))
      return 1;
  }
  std::string singleton;
  if (!check(emit(fixture("1", "nodes", "", "1"), "singleton", singleton) &&
                 singleton.find("electrical [0:0] nodes;") != std::string::npos,
             "singleton remains vector"))
    return 1;
  const std::string row =
      literal("one", 1) +
      "%row = \"nodal.shape_index\"(%nodes, %one) <{metadata = {input_path = \"Parent.nodes\", "
      "semantic_path = \"Parent.row\", storage = \"structural\", access = \"read_only\"}}> : (" +
      shape("2,3") + ", i64) -> " + shape("3") + "\n";
  std::string contiguous;
  if (!check(emit(fixture("3", "row", row), "contiguous", contiguous) &&
                 contiguous.find("Child u(.p(nodes[3:5]));") != std::string::npos &&
                 contiguous.find("generate\n") == std::string::npos,
             "contiguous actual part-select"))
    return 1;
  const std::string slice =
      literal("one", 1) + literal("two", 2) + literal("three", 3) +
      "%columns = \"nodal.shape_slice\"(%nodes, %one, %three, %two) <{axis = 1 : i64, dimensions = "
      "\"2,2\", origin = \"Parent.nodes\", observability = \"source_mapped\", metadata = {"
      "input_path = \"Parent.nodes\", semantic_path = \"Parent.columns\", storage = "
      "\"structural\", "
      "access = \"read_only\"}}> : (" +
      shape("2,4") + ", i64, i64, i64) -> " + shape("2,2") + "\n";
  const std::string mappedSource = fixture("2,2", "columns", slice, "2,4");
  std::string mapped;
  if (!check(emit(mappedSource, "noncontiguous", mapped), "noncontiguous actual emission") ||
      !check(mapped.find("module nodal_alias_electrical(.lhs(n), .rhs(n));") != std::string::npos &&
                 mapped.find("electrical [0:(4 - 1)] binding_u_p_bus;") != std::string::npos &&
                 llvm::StringRef(mapped).count("  generate\n") == 1 &&
                 llvm::StringRef(mapped).count("      nodal_alias_electrical link(") == 1 &&
                 mapped.find("analog begin") == std::string::npos,
             "compact true-net alias adapter"))
    return 1;
  auto commentStart = mapped.find("// nodal.array_binding ");
  auto jsonStart = commentStart + std::string("// nodal.array_binding ").size();
  auto record =
      llvm::json::parse(llvm::StringRef(mapped).slice(jsonStart, mapped.find('\n', jsonStart)));
  if (!check(commentStart != std::string::npos && record && record->getAsObject() &&
                 record->getAsObject()->getString("binding") == "Parent.binding" &&
                 record->getAsObject()->getString("root") == "Parent.nodes" &&
                 record->getAsObject()->getString("formal") == "Child.p" &&
                 record->getAsObject()->getString("target_actual") == "binding_u_p_bus" &&
                 record->getAsObject()->getArray("stages")->size() == 1 &&
                 (*record->getAsObject()->getArray("stages"))[0].getAsObject()->getString(
                     "expression") ==
                     "(((binding_u_p_lane / 2) * 4) + ((binding_u_p_lane % 2) + 1))" &&
                 mapped.find("link(.lhs(nodes[binding_u_p_stage_0]), "
                             ".rhs(binding_u_p_bus[binding_u_p_lane]));") != std::string::npos,
             "verified source/target correspondence"))
    return 1;
  std::string ams;
  if (!check(emit(mappedSource, "noncontiguous-ams", ams, nodal::BackendKind::VerilogAMS),
             "AMS adapter emission"))
    return 1;
  std::string collisionSource = mappedSource;
  replace(collisionSource, "module attributes {nodal.target.profile = \"analog\"} {",
          "module attributes {nodal.target.profile = \"analog\"} {" +
              module("nodal_alias_electrical", ""));
  replace(collisionSource, "%nodes =", array("Parent", "binding_u_p_bus", "1") + "%nodes =");
  std::string collision;
  if (!check(emit(collisionSource, "collision", collision) &&
                 collision.find("module nodal_alias_electrical_1(.lhs(n), .rhs(n));") !=
                     std::string::npos &&
                 collision.find("Child u(.p(binding_u_p_bus_1));") != std::string::npos,
             "derived module and bridge names avoid authored declarations"))
    return 1;
  auto escapedSource = mappedSource;
  replace(escapedSource, "module attributes {", R"mlir(module attributes {
    nodal.bridge.source_map = [{semantic_path = "Parent.binding",
      source_path = "unsafe\0A*/\22source.scala", source_line = 7 : i64,
      source_column = 9 : i64, source_end_line = 7 : i64, source_end_column = 13 : i64}],
  )mlir");
  std::string escaped;
  if (!check(emit(escapedSource, "escaped-source", escaped) &&
                 llvm::StringRef(escaped).count("\nmodule ") == 3 &&
                 llvm::StringRef(escaped).count("// nodal.array_binding ") == 1,
             "source comment escaping cannot inject target syntax"))
    return 1;
  auto emitWithParserLocation = [&](const std::string &source, const std::string &filename,
                                    std::string &target) {
    auto parsed = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (!parsed)
      return false;
    parsed->walk([&](mlir::Operation *operation) {
      operation->setLoc(mlir::FileLineColLoc::get(&context, filename, 123, 4));
    });
    llvm::raw_string_ostream stream(target);
    return mlir::succeeded(nodal::emitBackend(*parsed, nodal::BackendKind::VerilogA, stream));
  };
  for (const auto &source : {mappedSource, escapedSource}) {
    std::string first, second;
    if (!check(emitWithParserLocation(source, "/tmp/first/input.mlir", first) &&
                   emitWithParserLocation(source, "/tmp/second/input.mlir", second) &&
                   first == second && first.find("/tmp/") == std::string::npos,
               "source correspondence ignores transient parser filenames"))
      return 1;
  }
  if (!check(escaped.find("unsafe\\n*/\\\"source.scala:7:9-7:13") != std::string::npos,
             "retained authored binding source range survives target mapping"))
    return 1;
  // Broader source-generated topology now permits nonzero origins and strides.
  // Exact adapter coverage is an emitter obligation, not a zero-only grammar.
  if (!check(mapped.find("for (binding_u_p_lane = 0; binding_u_p_lane < 4; "
                         "binding_u_p_lane = binding_u_p_lane + 1)") != std::string::npos,
             "adapter emits every logical lane exactly once from zero with unit stride"))
    return 1;
  for (const auto &[before, after] :
       {std::pair<std::string, std::string>{".rhs(n)", ".rhs(m)"},
        {"inout n;", "input n;"},
        {".lhs(nodes[", ".lhs(missing["},
        {"binding_u_p_lane = 0;", "binding_u_p_lane = -1;"},
        {"binding_u_p_lane + 1", "binding_u_p_lane + 0"},
        {"localparam integer binding_u_p_stage_0 = ", "localparam real binding_u_p_stage_0 = "},
        {".rhs(binding_u_p_bus[binding_u_p_lane])", ".rhs(binding_u_p_bus[4])"},
        {"Child u(.p(binding_u_p_bus));", "Child u(.p(binding_u_p_bus[0]));"},
        {"electrical [0:3] p;", "electrical [0:4] p;"}}) {
    std::string invalid = mapped;
    if (!check(replace(invalid, before, after), "mutation anchor " + before) ||
        !check(mlir::failed(nodal::reparseBackendTarget(invalid, configuration)),
               "reject " + after))
      return 1;
  }
  std::string commentOnly = contiguous;
  if (!check(replace(commentOnly, "nodes[3:5]", "nodes[4:6]") &&
                 mlir::succeeded(nodal::reparseBackendTarget(commentOnly, configuration)),
             "correspondence comments are non-authoritative"))
    return 1;
  std::string outOfBounds = contiguous;
  replace(outOfBounds, "Child u(.p(nodes[3:5]));", "Child u(.p(nodes[4:6]));");
  if (!check(mlir::failed(nodal::reparseBackendTarget(outOfBounds, configuration)),
             "contiguous bounds independently checked"))
    return 1;
  const std::string incidenceSource = R"mlir(module attributes {nodal.target.profile = "analog"} {
"nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", dimension = "voltage", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
"nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", dimension = "current", metadata = {}, sym_name = "Current", units = "A"}> : () -> ()
"nodal.discipline"() <{domain = "continuous", flow = @Current, potential = @Voltage, metadata = {}, sym_name = "electrical"}> : () -> ()
"nodal.module"() <{sym_name = "Child", metadata = {semantic_path = "Child"}}> ({
"nodal.component_contract"() <{kind = "partial", connectivity_ownership = "extensible", source_path = "Child", metadata = {}}> : () -> ()
}) : () -> ()
"nodal.module"() <{sym_name = "Parent", metadata = {semantic_path = "Parent"}}> ({
"nodal.component_contract"() <{kind = "partial", connectivity_ownership = "extensible", source_path = "Parent", metadata = {}}> : () -> ()
%p = "nodal.terminal_array"() <{name = "p", direction = "inout", flow_orientation = "into_component", source_path = "Parent.p", metadata = {semantic_path = "Parent.p", storage = "structural"}}> : () -> !nodal.shaped<"2,3", !nodal.terminal<"electrical">>
%one = "nodal.const_literal"() <{metadata = {}, spelling = "1", value = 1 : i64}> : () -> i64
%row = "nodal.shape_index"(%p, %one) <{metadata = {input_path = "Parent.p", semantic_path = "Parent.row", storage = "structural", access = "read_only"}}> : (!nodal.shaped<"2,3", !nodal.terminal<"electrical">>, i64) -> !nodal.shaped<"3", !nodal.terminal<"electrical">>
%selected = "nodal.shape_index"(%row, %one) <{metadata = {input_path = "Parent.row", semantic_path = "Parent.row.at(literal:1)", source_uses = ["Parent.use_selected"], storage = "structural", selection_kind = "node_array"}}> : (!nodal.shaped<"3", !nodal.terminal<"electrical">>, i64) -> !nodal.terminal<"electrical">
%q = "nodal.terminal"() <{name = "q", direction = "inout", flow_orientation = "into_component", source_path = "Parent.q", metadata = {declaration_kind = "analog-inout"}}> : () -> !nodal.terminal<"electrical">
%b = "nodal.branch"(%selected, %q) <{declaration_kind = "named", name = "sense", source_path = "Parent.sense", metadata = {semantic_path = "Parent.sense"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
"nodal.analog"() <{metadata = {}}> ({
%flow = "nodal.port_flow_access"(%selected) <{kind = "flow", function = "I", metadata = {semantic_path = "Parent.flow"}}> : (!nodal.terminal<"electrical">) -> !nodal.quantity<"real", "current">
"nodal.contribute"(%b, %flow) <{kind = "flow", metadata = {}}> : (!nodal.branch<"electrical">, !nodal.quantity<"real", "current">) -> ()
}) : () -> ()
}) : () -> ()
}
)mlir";
  std::string incidence;
  if (!check(emit(incidenceSource, "port-incidence", incidence) &&
                 incidence.find("I(sense) <+ I(<p[4]>);") != std::string::npos,
             "selected local boundary flow remains port incidence in consumed expression"))
    return 1;
  for (const auto &endpoint : {"p", "p[3:4]", "p[6]", "missing[4]"}) {
    auto invalidIncidence = incidence;
    if (!check(replace(invalidIncidence, "I(<p[4]>)", "I(<" + std::string(endpoint) + ">)") &&
                   mlir::failed(nodal::reparseBackendTarget(invalidIncidence, configuration)),
               "invalid port incidence " + std::string(endpoint)))
      return 1;
  }
  auto internalIncidence = incidence;
  replace(internalIncidence, "module Parent(q, p);", "module Parent(q);");
  replace(internalIncidence, "  inout [0:5] p;\n", "");
  if (!check(mlir::failed(nodal::reparseBackendTarget(internalIncidence, configuration)),
             "internal selected net is not a boundary incidence"))
    return 1;
  for (const auto *recordName : {"nodal.connection_set", "nodal.flow_conservation"}) {
    auto derived = mlir::parseSourceString<mlir::ModuleOp>(incidenceSource, &context);
    if (!check(derived && mlir::succeeded(nodal::materializeConservativeConnectivity(*derived)) &&
                   mlir::succeeded(nodal::verifyBackendOperations(
                       *derived, nodal::getBackendProfile(nodal::BackendKind::VerilogA))),
               "derived topology admission has verified source owner"))
      return 1;
    bool changed = false;
    derived->walk([&](mlir::Operation *operation) {
      if (!changed && operation->getName().getStringRef() == recordName) {
        operation->setAttr("metadata", mlir::DictionaryAttr::get(&context));
        changed = true;
      }
    });
    if (!check(changed && mlir::failed(nodal::verifyBackendOperations(
                              *derived, nodal::getBackendProfile(nodal::BackendKind::VerilogA))),
               "unverified derived topology rejected " + std::string(recordName)))
      return 1;
  }
  std::string largeSource = mappedSource;
  while (replace(largeSource, "\"2,4\"", "\"1000000,4\"")) {
  }
  while (replace(largeSource, "\"2,2\"", "\"1000000,2\"")) {
  }
  std::string large;
  if (!check(emit(largeSource, "large-adapter", large) && large.size() < 4000 &&
                 large.find("binding_u_p_lane < 2000000") != std::string::npos &&
                 llvm::StringRef(large).count("      nodal_alias_electrical link(") == 1,
             "two million adapter lanes retain one compact structural instance"))
    return 1;
  std::string wideSource = mappedSource;
  while (replace(wideSource, "\"2,4\"", "\"2,2147483647\"")) {
  }
  auto wide = mlir::parseSourceString<mlir::ModuleOp>(wideSource, &context);
  if (!check(wide && mlir::succeeded(mlir::verify(*wide)),
             "wide adapter failure fixture remains native-valid"))
    return 1;
  std::string wideOutput = "sentinel";
  llvm::raw_string_ostream wideStream(wideOutput);
  if (!check(mlir::failed(nodal::emitBackend(*wide, nodal::BackendKind::VerilogA, wideStream)) &&
                 wideOutput == "sentinel",
             "wide map rejects transactionally without narrowing semantic signed64"))
    return 1;
  std::string invalidNative = mappedSource;
  replace(invalidNative, "port = \"p\"", "port = \"missing\"");
  auto invalid = mlir::parseSourceString<mlir::ModuleOp>(mappedSource, &context);
  if (!invalid)
    return 1;
  invalid->walk([&](mlir::Operation *op) {
    if (op->getName().getStringRef() == "nodal.instance_terminal_array")
      op->setAttr("port", mlir::StringAttr::get(&context, "missing"));
  });
  std::string sentinel = "sentinel";
  llvm::raw_string_ostream stream(sentinel);
  if (!check(mlir::failed(nodal::emitBackend(*invalid, nodal::BackendKind::VerilogA, stream)) &&
                 sentinel == "sentinel",
             "invalid binding leaves output unchanged"))
    return 1;
  llvm::outs() << checks << " boundary-array backend checks passed\n";
  return 0;
}
