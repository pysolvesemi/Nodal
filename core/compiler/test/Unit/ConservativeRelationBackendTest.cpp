#include "circt/Dialect/HW/HWDialect.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Backend/AnalogVerticalSlice.h"
#include "nodal/Backend/Backend.h"
#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/JSON.h"
#include "llvm/Support/raw_ostream.h"

#include <cstdint>
#include <fstream>
#include <functional>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace {
const std::string terminal = "!nodal.terminal<\"electrical\">";

std::string shape(const std::string &dimensions) {
  return "!nodal.shaped<\"" + dimensions + "\", " + terminal + ">";
}

bool replace(std::string &text, const std::string &before, const std::string &after) {
  const auto offset = text.find(before);
  if (offset == std::string::npos)
    return false;
  text.replace(offset, before.size(), after);
  return true;
}

bool contains(const std::string &text, llvm::StringRef value) {
  return llvm::StringRef(text).contains(value);
}

std::string owned(std::string text, const std::string &owner) {
  if (owner != "Parent")
    replace(text, "metadata = {",
            "generated_owner = \"" + owner + "\", generated_induction = \"" + owner +
                ".index\", metadata = {");
  return text;
}

std::string literal(const std::string &name, int64_t value) {
  const auto number = std::to_string(value);
  return "%" + name + " = \"nodal.const_literal\"() <{spelling = \"" + number +
         "\", value = " + number + " : i64, metadata = {}}> : () -> i64\n";
}

std::string constants() {
  std::string result;
  for (int64_t value = 0; value != 6; ++value)
    result += literal("c" + std::to_string(value), value);
  return result;
}

std::string scalar(const std::string &name) {
  return "%" + name + " = \"nodal.node\"() <{name = \"" + name + "\", source_path = \"Parent." +
         name + "\", metadata = {semantic_path = \"Parent." + name + "\"}}> : () -> " + terminal +
         "\n";
}

std::string array(const std::string &owner, const std::string &name, const std::string &dimensions,
                  bool boundary = false) {
  return "%" + name + " = \"nodal." + (boundary ? "terminal_array" : "node_array") +
         "\"() <{name = \"" + name + "\", " +
         (boundary ? "direction = \"inout\", flow_orientation = \"into_component\", " : "") +
         "source_path = \"" + owner + "." + name +
         "\", metadata = {storage = \"structural\", semantic_path = \"" + owner + "." + name +
         "\"}}> : () -> " + shape(dimensions) + "\n";
}

std::string module(const std::string &name, const std::string &body) {
  return "\"nodal.module\"() <{sym_name = \"" + name + "\", metadata = {semantic_path = \"" + name +
         "\"}}> ({\n\"nodal.component_contract\"() <{kind = \"partial\", "
         "connectivity_ownership = \"extensible\", source_path = \"" +
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

std::string instance(const std::string &name = "u", const std::string &owner = "Parent") {
  return owned("\"nodal.instance\"() <{sym_name = \"" + name +
                   "\", module = @Child, parameter_bindings = {}, domain_bindings = {}, "
                   "metadata = {semantic_path = \"" +
                   owner + "." + name + "\"}}> : () -> ()\n",
               owner);
}

std::string reference(const std::string &dimensions, const std::string &name = "ref",
                      const std::string &instanceName = "u", const std::string &owner = "Parent") {
  return owned("%" + name + " = \"nodal.instance_terminal_array\"() <{instance = @" + instanceName +
                   ", port = \"p\", name = \"" + instanceName +
                   ".p\", direction = \"inout\", flow_orientation = \"out_of_component\", "
                   "source_path = \"Child.p\", metadata = {semantic_path = \"" +
                   owner + "." + name + "\", storage = \"structural\"}}> : () -> " +
                   shape(dimensions) + "\n",
               owner);
}

std::string relation(const std::string &left, const std::string &right,
                     const std::string &dimensions, const std::string &name,
                     const std::string &owner = "Parent") {
  const auto type = dimensions.empty() ? terminal : shape(dimensions);
  return owned("\"nodal." + std::string(dimensions.empty() ? "connect" : "connect_array") + "\"(%" +
                   left + ", %" + right + ") <{connection_id = \"" + owner + "." + name +
                   "\", source_path = \"" + owner + "." + name +
                   "\", metadata = {semantic_path = \"" + owner + "." + name + "\"}}> : (" + type +
                   ", " + type + ") -> ()\n",
               owner);
}

std::string prefix(const std::string &base, const std::string &dimensions,
                   const std::string &result, unsigned index, const std::string &name,
                   const std::string &owner = "Parent") {
  return owned("%" + name + " = \"nodal.shape_index\"(%" + base + ", %c" + std::to_string(index) +
                   ") <{metadata = {input_path = \"" + owner + "." + base +
                   "\", semantic_path = \"" + owner + "." + name +
                   "\", storage = \"structural\", access = \"read_only\"}}> : (" +
                   shape(dimensions) + ", i64) -> " + shape(result) + "\n",
               owner);
}

std::string slice(const std::string &base, const std::string &dimensions, const std::string &result,
                  unsigned axis, unsigned lower, unsigned upper, const std::string &name) {
  return "%" + name + " = \"nodal.shape_slice\"(%" + base + ", %c" + std::to_string(lower) +
         ", %c" + std::to_string(upper) + ", %c" + std::to_string(upper - lower) +
         ") <{axis = " + std::to_string(axis) + " : i64, dimensions = \"" + result +
         "\", origin = \"Parent." + base +
         "\", observability = \"source_mapped\", metadata = {input_path = \"Parent." + base +
         "\", semantic_path = \"Parent." + name +
         "\", storage = \"structural\", access = \"read_only\"}}> : (" + shape(dimensions) +
         ", i64, i64, i64) -> " + shape(result) + "\n";
}

std::string selection(const std::string &base, const std::string &dimensions, unsigned index,
                      const std::string &name) {
  return "%" + name + " = \"nodal.shape_index\"(%" + base + ", %c" + std::to_string(index) +
         ") <{metadata = {input_path = \"Parent." + base + "\", semantic_path = \"Parent." + base +
         ".at(literal:" + std::to_string(index) + ")\", source_uses = [\"Parent.use_" + name +
         "\"], storage = \"structural\", selection_kind = \"node_array\"}}> : (" +
         shape(dimensions) + ", i64) -> " + terminal + "\n";
}

std::string view(const std::string &base, const std::string &dimensions, const std::string &result,
                 const std::string &name) {
  return "%" + name + " = \"nodal.shape_view\"(%" + base + ") <{dimensions = \"" + result +
         "\", materialization = \"view\", metadata = {input_path = \"Parent." + base +
         "\", semantic_path = \"Parent." + name +
         "\", storage = \"structural\", access = \"read_only\"}, observability = "
         "\"source_mapped\", origin = \"Parent." +
         base + "\"}> : (" + shape(dimensions) + ") -> " + shape(result) + "\n";
}

std::string parameter(const std::string &owner, const std::string &name, int64_t lower,
                      int64_t upper, int64_t initial) {
  return "\"nodal.parameter\"() <{classification = \"structural\", default_value = " +
         std::to_string(initial) +
         " : i64, parameter_kind = \"integer\", variability = \"symbolic\", sym_name = \"" + name +
         "\", type = i64, metadata = {semantic_path = \"" + owner + "." + name +
         "\"}}> : () -> ()\n" + literal(name + "_low", lower) + literal(name + "_high", upper) +
         "\"nodal.parameter_constraint\"(%" + name + "_low, %" + name +
         "_high) <{constraint_kind = \"range\", lower_inclusive = true, upper_inclusive = true, "
         "parameter = @" +
         name +
         ", metadata = {}}> : (i64, i64) -> ()\n\"nodal.parameter_envelope\"() <{effects = "
         "[\"shape\"], policy = \"static_generate\", parameter = @" +
         name + ", metadata = {}}> : () -> ()\n%" + name +
         " = \"nodal.const_parameter_ref\"() <{parameter = @" + name +
         ", metadata = {}}> : () -> i64\n";
}

std::string addition(const std::string &owner, const std::string &name, const std::string &left,
                     const std::string &right) {
  return "%" + name + " = \"nodal.const_expr\"(%" + left + ", %" + right +
         ") <{operator_name = \"add\", metadata = {semantic_path = \"" + owner + "." + name +
         "\"}}> : (i64, i64) -> i64\n";
}

std::string override(const std::string &name, const std::string &actual) {
  return "\"nodal.parameter_override\"(%" + actual + ") <{instance = @u, parameter = @" + name +
         ", metadata = {}}> : (i64) -> ()\n";
}

std::string generate(const std::string &body, int64_t count) {
  return "\"nodal.generate\"() <{induction = \"i\", lower = 0 : i64, upper = " +
         std::to_string(count) +
         " : i64, step = 1 : i64, region_id = \"Parent.gen\", induction_path = "
         "\"Parent.gen.index\", metadata = {maximum_trip_count = " +
         std::to_string(count) + " : i64}}> ({\n^bb0(%i: i64):\n" + body + "}) : () -> ()\n";
}

std::string rowPartition(const std::string &extent = "3", const std::string &owner = "Parent") {
  return instance("u", owner) + reference("2," + extent, "ref", "u", owner) +
         prefix("ref", "2," + extent, extent, 0, "row0", owner) +
         prefix("ref", "2," + extent, extent, 1, "row1", owner) +
         relation("first", "row0", extent, "first_join", owner) +
         relation("row1", "second", extent, "second_join", owner);
}

std::string columnPartition(const std::string &rows = "2") {
  return design(array("Child", "p", rows + ",4", true),
                constants() + array("Parent", "first", rows + ",6") +
                    array("Parent", "second", rows + ",6") + instance() + reference(rows + ",4") +
                    slice("first", rows + ",6", rows + ",2", 1, 1, 3, "actual0") +
                    slice("second", rows + ",6", rows + ",2", 1, 3, 5, "actual1") +
                    slice("ref", rows + ",4", rows + ",2", 1, 0, 2, "child0") +
                    slice("ref", rows + ",4", rows + ",2", 1, 2, 4, "child1") +
                    relation("actual0", "child0", rows + ",2", "first_join") +
                    relation("child1", "actual1", rows + ",2", "second_join"));
}

nodal::BackendConfiguration configuration(nodal::BackendKind kind) {
  return {&nodal::getBackendProfile(kind), nodal::GateProfile::Default,
          nodal::ShapedValueLayout::ScalarOrFlat, nodal::MaterializationPolicy::SafeInline,
          nodal::NamingPolicy::Semantic};
}

std::vector<llvm::json::Value> records(const std::string &target) {
  const llvm::StringRef prefix = "// nodal.array_binding ";
  std::vector<llvm::json::Value> result;
  size_t offset = 0;
  while ((offset = target.find(prefix.str(), offset)) != std::string::npos) {
    offset += prefix.size();
    auto value =
        llvm::json::parse(llvm::StringRef(target).slice(offset, target.find('\n', offset)));
    if (!value) {
      llvm::consumeError(value.takeError());
      return {};
    }
    result.push_back(std::move(*value));
  }
  return result;
}

const llvm::json::Object *endpoint(const llvm::json::Value &record, llvm::StringRef side) {
  const auto *object = record.getAsObject();
  return object ? object->getObject(side) : nullptr;
}

bool mapped(const llvm::json::Value &record, llvm::StringRef side, llvm::StringRef stride) {
  const auto *object = endpoint(record, side);
  const auto *stages = object ? object->getArray("stages") : nullptr;
  if (!stages)
    return false;
  for (const auto &stage : *stages)
    if (const auto *step = stage.getAsObject())
      if (auto expression = step->getString("expression"))
        if (expression->contains(stride))
          return true;
  return false;
}

std::string actual(const std::string &target, const std::string &instanceName = "u") {
  const auto marker = " " + instanceName + "(.p(";
  auto start = target.find(marker);
  if (start == std::string::npos)
    return {};
  start += marker.size();
  const auto end = target.find(')', start);
  return end == std::string::npos ? "" : target.substr(start, end - start);
}

bool fullBus(const std::string &target, int64_t cardinality,
             const std::string &instanceName = "u") {
  const auto bus = actual(target, instanceName);
  if (bus.empty() || bus.find('[') != std::string::npos)
    return false;
  return contains(target, "electrical [0:" + std::to_string(cardinality - 1) + "] " + bus + ";") ||
         contains(target, "electrical [0:(" + std::to_string(cardinality) + " - 1)] " + bus + ";");
}

bool structuralOnly(const std::string &target, size_t relations) {
  return llvm::StringRef(target).count("link(.lhs(") == relations &&
         contains(target, "module nodal_alias_electrical(.lhs(n), .rhs(n));") &&
         !contains(target, "analog begin") && !contains(target, "assign ");
}
} // namespace

int main(int argc, char **argv) {
  mlir::MLIRContext context;
  context.loadDialect<nodal::NodalDialect, circt::hw::HWDialect>();
  unsigned checks = 0;
  auto check = [&](bool valid, const std::string &message) {
    ++checks;
    if (!valid)
      llvm::errs() << "ConservativeRelationBackendTest: " << message << "\n";
    return valid;
  };
  auto emit = [&](const std::string &source, const std::string &name, std::string &target,
                  nodal::BackendKind kind = nodal::BackendKind::VerilogA) {
    if (argc > 1)
      std::ofstream(std::string(argv[1]) + "/" + name + ".mlir") << source;
    auto parsed = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (!check(parsed && mlir::succeeded(mlir::verify(*parsed)), name + " native fixture"))
      return false;
    auto config = nodal::resolveBackendConfiguration(*parsed, kind);
    if (!check(mlir::succeeded(config) && config->checkProfile == nodal::GateProfile::Default,
               name + " uses the production default gate"))
      return false;
    auto render = [&](mlir::ModuleOp module, std::string &result) {
      llvm::raw_string_ostream output(result);
      const auto status = nodal::emitBackend(module, kind, output);
      output.flush();
      return mlir::succeeded(status);
    };
    if (!check(render(*parsed, target), name + " emission"))
      return false;
    std::string repeated, nativeText, roundtrip;
    llvm::raw_string_ostream nativeStream(nativeText);
    parsed->print(nativeStream);
    nativeStream.flush();
    auto reparsed = mlir::parseSourceString<mlir::ModuleOp>(nativeText, &context);
    if (!check(render(*parsed, repeated) && target == repeated && reparsed &&
                   mlir::succeeded(mlir::verify(*reparsed)) && render(*reparsed, roundtrip) &&
                   target == roundtrip,
               name + " repeat and native-roundtrip byte parity") ||
        !check(mlir::succeeded(nodal::reparseBackendTarget(target, configuration(kind))),
               name + " independent target grammar"))
      return false;
    if (argc > 1) {
      std::ofstream(std::string(argv[1]) + "/" + name +
                    (kind == nodal::BackendKind::VerilogA ? ".va" : ".vams"))
          << target;
    }
    return true;
  };

  std::string whole;
  const auto wholeSource = design(array("Child", "p", "2,3", true),
                                  array("Parent", "nodes", "2,3") + instance() + reference("2,3") +
                                      relation("ref", "nodes", "2,3", "reversed_whole"));
  if (!emit(wholeSource, "reversed-whole", whole) ||
      !check(contains(whole, "inout [0:5] p;") && contains(whole, "electrical [0:5] nodes;") &&
                 llvm::StringRef(whole).count(" u(.p(") == 1 && !actual(whole).empty(),
             "whole child root is legal in the first operand"))
    return 1;

  const auto rowsSource =
      design(array("Child", "p", "2,3", true), constants() + array("Parent", "first", "3") +
                                                   array("Parent", "second", "3") + rowPartition());
  std::string rows;
  if (!emit(rowsSource, "adjacent-child-rows", rows) ||
      !check(fullBus(rows, 6) && structuralOnly(rows, 2) &&
                 llvm::StringRef(rows).count(" u(.p(") == 1,
             "two adjacent child regions share one complete six-lane port bus"))
    return 1;

  const auto columnsSource = columnPartition();
  std::string columns;
  for (auto kind : {nodal::BackendKind::VerilogA, nodal::BackendKind::VerilogAMS}) {
    std::string target;
    const auto suffix = kind == nodal::BackendKind::VerilogA ? "va" : "ams";
    if (!emit(columnsSource, std::string("two-sided-columns-") + suffix, target, kind) ||
        !check(fullBus(target, 8) && structuralOnly(target, 2) &&
                   llvm::StringRef(target).count(" u(.p(") == 1,
               "both noncontiguous endpoint maps alias the full child bus"))
      return 1;
    auto maps = records(target);
    if (!check(maps.size() == 2 && mapped(maps[0], "left", " * 6") &&
                   mapped(maps[0], "right", " * 4") && mapped(maps[1], "left", " * 4") &&
                   mapped(maps[1], "right", " * 6") &&
                   endpoint(maps[0], "right")->getString("instance") == "u" &&
                   endpoint(maps[0], "right")->getString("formal") == "Child.p" &&
                   endpoint(maps[0], "right")->getString("root_reference") == "Parent.ref" &&
                   endpoint(maps[0], "right")->getString("root_shape") == "2,4" &&
                   endpoint(maps[0], "right")->getString("effective_shape") == "2,2" &&
                   endpoint(maps[0], "left")->getString("root_shape") == "2,6" &&
                   endpoint(maps[1], "left")->getString("instance") == "u" &&
                   endpoint(maps[1], "left")->getString("formal") == "Child.p",
               "source correspondence retains both independent maps and actual child incidence"))
      return 1;
    if (kind == nodal::BackendKind::VerilogA)
      columns = target;
  }

  const auto reshapeSource = design(
      array("Child", "p", "2,4", true),
      constants() + array("Parent", "first", "4") + array("Parent", "second", "4") + instance() +
          reference("2,4") + slice("ref", "2,4", "2,2", 1, 0, 2, "child0") +
          slice("ref", "2,4", "2,2", 1, 2, 4, "child1") + view("child0", "2,2", "4", "flat0") +
          view("child1", "2,2", "4", "flat1") + relation("first", "flat0", "4", "first_join") +
          relation("flat1", "second", "4", "second_join"));
  std::string reshaped;
  if (!emit(reshapeSource, "reshaped-child-columns", reshaped) ||
      !check(fullBus(reshaped, 8) && structuralOnly(reshaped, 2),
             "flattened child columns preserve the selected noncontiguous physical images"))
    return 1;

  std::string local;
  const auto localSource =
      design("", constants() + array("Parent", "left", "2,4") + array("Parent", "right", "2,6") +
                     slice("left", "2,4", "2,2", 1, 1, 3, "left_columns") +
                     slice("right", "2,6", "2,2", 1, 3, 5, "right_columns") +
                     relation("left_columns", "right_columns", "2,2", "local_join"));
  if (!emit(localSource, "local-local-columns", local) ||
      !check(structuralOnly(local, 1) && !contains(local, "Child u(") &&
                 contains(local, "electrical [0:7] left;") &&
                 contains(local, "electrical [0:11] right;"),
             "local/local physical joins need no user child instance"))
    return 1;

  const auto childPairSource =
      design(array("Child", "p", "2,4", true),
             constants() + instance("u") + instance("v") + reference("2,4", "u_ref", "u") +
                 reference("2,4", "v_ref", "v") + slice("u_ref", "2,4", "2,2", 1, 0, 2, "u_first") +
                 slice("u_ref", "2,4", "2,2", 1, 2, 4, "u_second") +
                 slice("v_ref", "2,4", "2,2", 1, 0, 2, "v_first") +
                 slice("v_ref", "2,4", "2,2", 1, 2, 4, "v_second") +
                 relation("u_first", "v_second", "2,2", "cross_first") +
                 relation("v_first", "u_second", "2,2", "cross_second"));
  std::string childPair;
  if (!emit(childPairSource, "child-child-partitions", childPair) ||
      !check(fullBus(childPair, 8, "u") && fullBus(childPair, 8, "v") &&
                 actual(childPair, "u") != actual(childPair, "v") && structuralOnly(childPair, 2) &&
                 llvm::StringRef(childPair).count("module Child(p);") == 1 &&
                 llvm::StringRef(childPair).count(" u(.p(") == 1 &&
                 llvm::StringRef(childPair).count(" v(.p(") == 1,
             "two actual instances of one definition retain distinct full buses and crossed maps"))
    return 1;

  std::string tilesBody = constants() + instance() + reference("2,2");
  for (unsigned row = 0; row != 2; ++row)
    for (unsigned column = 0; column != 2; ++column) {
      const auto name = "tile" + std::to_string(row) + std::to_string(column);
      tilesBody += array("Parent", name, "1,1") +
                   slice("ref", "2,2", "1,2", 0, row, row + 1, name + "_row") +
                   slice(name + "_row", "1,2", "1,1", 1, column, column + 1, name + "_view") +
                   relation(name, name + "_view", "1,1", name + "_join");
    }
  std::string tiles;
  if (!emit(design(array("Child", "p", "2,2", true), tilesBody), "multi-axis-tiles", tiles) ||
      !check(fullBus(tiles, 4) && structuralOnly(tiles, 4),
             "multi-axis singleton tiles cover the actual port without enlarging their images"))
    return 1;

  const auto scalarSource =
      design(array("Child", "p", "2", true),
             constants() + scalar("first") + scalar("second") + instance() + reference("2") +
                 selection("ref", "2", 0, "selected0") + selection("ref", "2", 1, "selected1") +
                 relation("first", "selected0", "", "first_join") +
                 relation("selected1", "second", "", "second_join"));
  std::string selected;
  if (!emit(scalarSource, "scalar-child-selections", selected) ||
      !check(fullBus(selected, 2) && structuralOnly(selected, 2) && records(selected).size() == 2,
             "scalar child-array selections feed singleton relations and one full port bus"))
    return 1;

  // One root use does not imply an unobserved tree: a whole view can fan out
  // to the binding and a scalar branch consumer. Retain the child's full bus.
  const auto sharedViewSource = design(
      array("Child", "p", "2", true),
      constants() + array("Parent", "nodes", "4") + instance() + reference("2") +
          view("ref", "2", "2", "whole") + slice("nodes", "4", "2", 0, 1, 3, "part") +
          selection("whole", "2", 1, "selected") + scalar("returnNode") +
          "%sense = \"nodal.branch\"(%selected, %returnNode) <{declaration_kind = \"named\", "
          "name = \"senseBranch\", source_path = \"Parent.senseBranch\", metadata = {semantic_path "
          "= "
          "\"Parent.senseBranch\"}}> : (" +
          terminal + ", " + terminal + ") -> !nodal.branch<\"electrical\">\n" +
          relation("whole", "part", "2", "shared_join"));
  std::string sharedView;
  if (!emit(sharedViewSource, "shared-whole-child-view", sharedView) ||
      !check(fullBus(sharedView, 2) && structuralOnly(sharedView, 1) &&
                 contains(sharedView,
                          "branch (" + actual(sharedView) + "[1], returnNode) senseBranch;") &&
                 !contains(sharedView, "nodes[1:2]["),
             "shared whole child view preserves scalar consumers on the full root bus"))
    return 1;

  std::string emptyGeneratedSource;
  for (int64_t count : {int64_t(0), int64_t(1), int64_t(1000000)}) {
    const auto generatedSource =
        design(array("Child", "p", "2,3", true),
               constants() + array("Parent", "first", "3") + array("Parent", "second", "3") +
                   generate(rowPartition("3", "Parent.gen"), count));
    if (count == 0)
      emptyGeneratedSource = generatedSource;
    std::string generated;
    if (!emit(generatedSource, "generated-partition-" + std::to_string(count), generated) ||
        !check(
            fullBus(generated, 6) && structuralOnly(generated, 2) && generated.size() < 18000 &&
                contains(generated, "for (i = 0; i < " + std::to_string(count) + ";") &&
                llvm::StringRef(generated).count(" u(.p(") == 1 && records(generated).size() == 2 &&
                llvm::all_of(records(generated),
                             [](const llvm::json::Value &record) {
                               const auto *object = record.getAsObject();
                               return object && object->getString("source_scope") == "Parent.gen";
                             }) &&
                generated.find("begin : gen") < generated.find(actual(generated) + ";"),
            "empty, singleton and million-occurrence partitions retain scoped compact templates"))
      return 1;
  }

  for (bool dependent : {false, true}) {
    const auto extent = dependent ? "Parent.extent" : "N";
    auto child = parameter("Child", "A", 2, 8, 3);
    auto parent = parameter("Parent", "N", 2, 6, 3) + constants();
    if (dependent) {
      child += parameter("Child", "B", 3, 9, 4) + literal("one", 1) +
               addition("Child", "default_b", "A", "one") +
               "\"nodal.parameter_value\"(%default_b) <{parameter = @B, metadata = {}}> : "
               "(i64) -> ()\n";
      parent += addition("Parent", "extent", "N", "c1");
    }
    child += array("Child", "p", dependent ? "2,B" : "2,A", true);
    parent += array("Parent", "first", extent) + array("Parent", "second", extent) +
              rowPartition(extent) + override("A", "N");
    std::string target;
    if (!emit(design(child, parent), dependent ? "dependent-final-root" : "symbolic-final-root",
              target) ||
        !check(
            structuralOnly(target, 2) &&
                contains(target, "Child #(.A(N)) u(.p(" + actual(target) + "));") &&
                contains(target, "parameter integer A = 3") &&
                !contains(target, "electrical [0:5] " + actual(target) + ";") &&
                !contains(target, "electrical [0:(6 - 1)] " + actual(target) + ";") &&
                contains(target, "N") && (!dependent || contains(target, "N + 1")),
            "late override and dependent default determine the full bus without freezing defaults"))
      return 1;
  }

  std::string large;
  if (!emit(columnPartition("1000000"), "large-two-sided-columns", large) ||
      !check(fullBus(large, 4000000) && structuralOnly(large, 2) && large.size() < 18000 &&
                 contains(large, "< 2000000;"),
             "four million child lanes stay in two authored compact relations"))
    return 1;

  // Comments describe correspondence but never grant target grammar acceptance.
  std::string uncommented = columns;
  const std::string recordPrefix = "  // nodal.array_binding ";
  size_t start = 0;
  while ((start = uncommented.find(recordPrefix, start)) != std::string::npos)
    uncommented.erase(start, uncommented.find('\n', start) - start + 1);
  if (!check(mlir::succeeded(nodal::reparseBackendTarget(
                 uncommented, configuration(nodal::BackendKind::VerilogA))),
             "target acceptance is independent of correspondence comments"))
    return 1;
  const auto bus = actual(columns);
  for (const auto &[before, after] : std::vector<std::pair<std::string, std::string>>{
           {".rhs(n)", ".rhs(m)"},
           {"inout n;", "input n;"},
           {" u(.p(" + bus + "));", " u(.p(" + bus + "[0]));"},
           {".lhs(first[", ".lhs(missing["},
           {".rhs(" + bus + "[", ".rhs(missing["}}) {
    auto invalid = columns;
    if (!check(replace(invalid, before, after), "target mutation anchor " + before) ||
        !check(mlir::failed(nodal::reparseBackendTarget(
                   invalid, configuration(nodal::BackendKind::VerilogA))),
               "target independently rejects " + after))
      return 1;
  }

  const auto maps = records(columns);
  for (const auto &[side, bound] :
       {std::pair<llvm::StringRef, unsigned>{"left", 12}, {"right", 8}}) {
    const auto *stages = maps.empty() || !endpoint(maps[0], side)
                             ? nullptr
                             : endpoint(maps[0], side)->getArray("stages");
    const auto *stage = !stages || stages->empty() ? nullptr : (*stages)[0].getAsObject();
    const auto name = stage ? stage->getString("name") : std::nullopt;
    const auto expression = stage ? stage->getString("expression") : std::nullopt;
    if (!check(name && expression, "mapped endpoint has a real target map stage"))
      return 1;
    auto invalid = columns;
    if (!check(replace(invalid,
                       "localparam integer " + name->str() + " = " + expression->str() + ";",
                       "localparam integer " + name->str() + " = " + std::to_string(bound) + ";") &&
                   mlir::failed(nodal::reparseBackendTarget(
                       invalid, configuration(nodal::BackendKind::VerilogA))),
               "target independently bounds-checks the " + side.str() + " root map"))
      return 1;
  }

  auto rejectsTransactionally = [&](const std::string &source, const std::string &name,
                                    const std::function<bool(mlir::ModuleOp)> &mutate) {
    auto parsed = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (!check(parsed && mlir::succeeded(mlir::verify(*parsed)),
               name + " valid starting fixture") ||
        !check(mutate(*parsed), name + " mutation applied"))
      return false;
    std::string output = "sentinel";
    llvm::raw_string_ostream stream(output);
    auto status = nodal::emitBackend(*parsed, nodal::BackendKind::VerilogA, stream);
    stream.flush();
    return check(mlir::failed(status) && output == "sentinel", name + " rejects transactionally");
  };
  auto firstOperation = [](mlir::ModuleOp module, llvm::StringRef name) {
    mlir::Operation *found = nullptr;
    module.walk([&](mlir::Operation *operation) {
      if (!found && operation->getName().getStringRef() == name)
        found = operation;
    });
    return found;
  };
  if (!rejectsTransactionally(columnsSource, "missing child coverage",
                              [&](mlir::ModuleOp module) {
                                auto *operation = firstOperation(module, "nodal.connect_array");
                                if (!operation)
                                  return false;
                                operation->erase();
                                return true;
                              }) ||
      !rejectsTransactionally(
          columnsSource, "duplicate child region",
          [&](mlir::ModuleOp module) {
            auto *operation = firstOperation(module, "nodal.connect_array");
            if (!operation)
              return false;
            auto *duplicate = operation->clone();
            duplicate->setAttr("connection_id",
                               mlir::StringAttr::get(&context, "Parent.duplicate"));
            duplicate->setAttr("source_path", mlir::StringAttr::get(&context, "Parent.duplicate"));
            duplicate->setAttr(
                "metadata",
                mlir::DictionaryAttr::get(
                    &context,
                    {mlir::NamedAttribute(mlir::StringAttr::get(&context, "semantic_path"),
                                          mlir::StringAttr::get(&context, "Parent.duplicate"))}));
            operation->getBlock()->getOperations().insertAfter(operation->getIterator(), duplicate);
            return true;
          }) ||
      !rejectsTransactionally(columnsSource, "forged full root extent",
                              [&](mlir::ModuleOp module) {
                                auto *root =
                                    firstOperation(module, "nodal.instance_terminal_array");
                                auto *localRoot = firstOperation(module, "nodal.node_array");
                                if (!root || !localRoot)
                                  return false;
                                root->getResult(0).setType(localRoot->getResult(0).getType());
                                return true;
                              }) ||
      !rejectsTransactionally(columnsSource, "forged projection origin",
                              [&](mlir::ModuleOp module) {
                                auto *operation = firstOperation(module, "nodal.shape_slice");
                                if (!operation)
                                  return false;
                                operation->setAttr(
                                    "origin", mlir::StringAttr::get(&context, "Parent.absent"));
                                return true;
                              }) ||
      !rejectsTransactionally(
          emptyGeneratedSource, "invalid selection inside empty generated domain",
          [&](mlir::ModuleOp module) {
            bool changed = false;
            module.walk([&](mlir::Operation *operation) {
              auto value = operation->getAttrOfType<mlir::IntegerAttr>("value");
              if (!changed && operation->getName().getStringRef() == "nodal.const_literal" &&
                  value && value.getInt() == 1) {
                operation->setAttr("value", mlir::IntegerAttr::get(value.getType(), 4));
                operation->setAttr("spelling", mlir::StringAttr::get(&context, "4"));
                changed = true;
              }
            });
            return changed;
          }))
    return 1;

  // Derived topology records cannot replace the actual authored net relation.
  for (bool forgeMetadata : {false, true}) {
    auto parsed = mlir::parseSourceString<mlir::ModuleOp>(scalarSource, &context);
    if (!check(parsed && mlir::succeeded(mlir::verify(*parsed)) &&
                   mlir::succeeded(nodal::materializeConservativeConnectivity(*parsed)),
               "singleton relation materializes actual topology records"))
      return 1;
    auto *equality = firstOperation(*parsed, "nodal.potential_equality");
    if (!check(equality && mlir::succeeded(nodal::verifyBackendOperations(
                               *parsed, nodal::getBackendProfile(nodal::BackendKind::VerilogA))),
               "verified singleton potential record is admitted"))
      return 1;
    if (forgeMetadata) {
      equality->setAttr("metadata", mlir::DictionaryAttr::get(&context));
    } else {
      auto *connection = firstOperation(*parsed, "nodal.connect");
      if (!check(connection, "materialized record retains its authored connection"))
        return 1;
      connection->erase();
    }
    if (!check(mlir::failed(nodal::verifyBackendOperations(
                   *parsed, nodal::getBackendProfile(nodal::BackendKind::VerilogA))),
               forgeMetadata ? "forged potential record is rejected independently"
                             : "potential record without an actual net relation is rejected"))
      return 1;
    std::string output = "sentinel";
    llvm::raw_string_ostream stream(output);
    auto status = nodal::emitBackend(*parsed, nodal::BackendKind::VerilogA, stream);
    stream.flush();
    if (!check(mlir::failed(status) && output == "sentinel",
               "invalid potential record rejects before publishing target bytes"))
      return 1;
  }

  const auto wideSource =
      design("", array("Parent", "left", "2147483648") + array("Parent", "right", "2147483648") +
                     relation("left", "right", "2147483648", "wide_join"));
  if (!rejectsTransactionally(wideSource, "signed32 target cardinality",
                              [](mlir::ModuleOp) { return true; }))
    return 1;

  llvm::outs() << checks << " conservative-relation backend checks passed\n";
  return 0;
}
