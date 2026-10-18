#include "circt/Dialect/HW/HWDialect.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Backend/AnalogEventBackend.h"
#include "nodal/Backend/AnalogVerticalSlice.h"
#include "nodal/Backend/Backend.h"
#include "nodal/Backend/ShapedValueBackend.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"

#include "llvm/Support/raw_ostream.h"

#include <fstream>
#include <string>
#include <vector>

namespace {
const std::string variable = "!nodal.variable<\"real\", \"1\">";
const std::string quantity = "!nodal.quantity<\"real\", \"1\">";
std::string shape(const std::string &dimensions, bool storage = true) {
  return "!nodal.shaped<\"" + dimensions + "\", " + (storage ? variable : quantity) + ">";
}
std::string literal(unsigned value) {
  auto number = std::to_string(value);
  return "%c" + number + " = \"nodal.const_literal\"() <{value = " + number +
         " : i64, spelling = \"" + number + "\", metadata = {}}> : () -> i64\n";
}
std::string metadata(const std::string &name) {
  return "metadata = {semantic_path = \"Arrays." + name + "\"}";
}
std::string fill(const std::string &name, const std::string &dimensions,
                 const std::string &source = "1.0", const std::string &reads = "",
                 const std::string &readTypes = "") {
  return "%" + name + " = \"nodal.analog_array_fill\"(" + reads +
         ") <{owner = \"Arrays\", value = \"" + source + "\", " + metadata(name) + "}> : (" +
         readTypes + ") -> " + shape(dimensions, false) + "\n";
}
std::string declaration(const std::string &name, const std::string &dimensions,
                        const std::string &initializer = "") {
  return "%" + name + " = \"nodal.analog_variable\"(" +
         (initializer.empty() ? "" : "%" + initializer) + ") <{identity = \"Arrays." + name +
         "\", owner = \"Arrays\", declaration_order = " + (name == "values" ? "0" : "1") +
         " : i64, initialized = " + (initializer.empty() ? "false" : "true") +
         ", initializer_value = \"\", initializer_kind = \"\", initializer_dimension = \"\", "
         "initializer_reads = [], " +
         metadata(name) + "}> : (" + (initializer.empty() ? "" : shape(dimensions, false)) +
         ") -> " + shape(dimensions) + "\n";
}
std::string expressionDeclaration(const std::string &name, const std::string &source,
                                  const std::string &captures = "",
                                  const std::string &captureTypes = "", unsigned order = 1,
                                  const std::string &kind = "real",
                                  const std::string &dimension = "1") {
  return "%" + name + " = \"nodal.analog_variable\"(" + captures + ") <{identity = \"Arrays." +
         name + "\", owner = \"Arrays\", declaration_order = " + std::to_string(order) +
         " : i64, initialized = true, initializer_form = \"expression\", initializer_value = \"" +
         source + "\", initializer_kind = \"" + kind + "\", initializer_dimension = \"" +
         dimension + "\", initializer_reads = [], " + metadata(name) + "}> : (" + captureTypes +
         ") -> !nodal.variable<\"" + kind + "\", \"" + dimension + "\">\n";
}
std::string read(const std::string &name, const std::string &input,
                 const std::string &dimensions = "") {
  return "%" + name + " = \"nodal.analog_variable_read\"(%" + input + ") <{read_id = \"Arrays." +
         name + "\", owner = \"Arrays\", " + metadata(name) + "}> : (" +
         (dimensions.empty() ? variable : shape(dimensions)) + ") -> " +
         (dimensions.empty() ? quantity : shape(dimensions, false)) + "\n";
}
std::string assignment(const std::string &name, const std::string &target,
                       const std::string &dimensions, const std::string &source,
                       bool snapshot = true, const std::string &reads = "",
                       const std::string &readTypes = "") {
  return "\"nodal.analog_assign\"(%" + target +
         (snapshot        ? ", %" + source
          : reads.empty() ? ""
                          : ", " + reads) +
         ") <{statement_id = \"Arrays." + name +
         "\", owner = \"Arrays\", authored_order = 0 : i64, " +
         (snapshot ? "value_form = \"snapshot\", " : "") +
         "value_kind = \"real\", value_dimension = \"1\", analyses = [\"dc\", \"transient\"], "
         "guard_present = false, guard_value = \"\", guard_kind = \"\", guard_dimension = \"\", "
         "guard_reads = [], metadata = {" +
         (snapshot ? "" : "value = \"" + source + "\"") + "}}> : (" +
         (dimensions.empty() ? variable : shape(dimensions)) +
         (snapshot            ? ", " + shape(dimensions, false)
          : readTypes.empty() ? ""
                              : ", " + readTypes) +
         ") -> ()\n";
}
std::string projectionMetadata(const std::string &name, const std::string &input,
                               bool storage = true) {
  return "owner = \"Arrays\", metadata = {semantic_path = \"Arrays." + name +
         "\", input_path = \"Arrays." + input + "\", storage = \"" +
         (storage ? "procedural" : "structural") + "\", access = \"" +
         (storage ? "read_write" : "read_only") + "\"}";
}
std::string index(const std::string &name, const std::string &input, const std::string &dimensions,
                  const std::string &indices, const std::string &indexTypes,
                  const std::string &resultDimensions = "", bool storage = true) {
  return "%" + name + " = \"nodal.shape_index\"(%" + input + ", " + indices + ") <{" +
         projectionMetadata(name, input, storage) + "}> : (" + shape(dimensions, storage) + ", " +
         indexTypes + ") -> " +
         (resultDimensions.empty() ? (storage ? variable : quantity)
                                   : shape(resultDimensions, storage)) +
         "\n";
}
std::string slice(const std::string &name, const std::string &input, const std::string &dimensions,
                  unsigned lower, unsigned upper, const std::string &resultDimensions,
                  unsigned axis = 0, bool storage = true) {
  return "%" + name + " = \"nodal.shape_slice\"(%" + input + ", %c" + std::to_string(lower) +
         ", %c" + std::to_string(upper) + ", %c" + std::to_string(upper - lower) +
         ") <{axis = " + std::to_string(axis) + " : i64, dimensions = \"" + resultDimensions +
         "\", origin = \"Arrays." + input + "\", observability = \"source_mapped\", " +
         projectionMetadata(name, input, storage) + "}> : (" + shape(dimensions, storage) +
         ", i64, i64, i64) -> " + shape(resultDimensions, storage) + "\n";
}
std::string view(const std::string &name, const std::string &input, const std::string &dimensions,
                 const std::string &resultDimensions, bool storage = true) {
  return "%" + name + " = \"nodal.shape_view\"(%" + input + ") <{dimensions = \"" +
         resultDimensions + "\", origin = \"Arrays." + input +
         "\", materialization = \"view\", observability = \"source_mapped\", " +
         projectionMetadata(name, input, storage) + "}> : (" + shape(dimensions, storage) +
         ") -> " + shape(resultDimensions, storage) + "\n";
}
std::string scope(const std::string &name, const std::string &body) {
  return "\"nodal.analog_scope\"() <{scope_id = \"Arrays." + name + "\", owner = \"Arrays\", " +
         metadata(name) + "}> ({\n" + body + "}) : () -> ()\n";
}
std::string loop(const std::string &name, const std::string &body,
                 const std::string &maximum = "1") {
  return "\"nodal.analog_loop\"() <{statement_id = \"Arrays." + name +
         "\", owner = \"Arrays\", stage = \"runtime\", bound_value = \"" + maximum +
         "\", bound_kind = \"integer\", bound_dimension = \"1\", bound_reads = [], "
         "minimum_iterations = 0 : i64, maximum_iterations = " +
         maximum + " : i64, static_trip_count_present = false, static_trip_count = 0 : i64, " +
         metadata(name) + "}> ({\n" + body + "}) : () -> ()\n";
}
struct StorageReference {
  std::string name;
  std::string kind = "real";
  std::string dimension = "1";
};
using StorageReferences = std::vector<StorageReference>;
std::string referenceList(const StorageReferences &references, const std::string &form) {
  std::string result;
  for (const auto &reference : references) {
    if (!result.empty())
      result += ", ";
    result += form == "operands" ? "%" + reference.name
              : form == "types"
                  ? "!nodal.variable<\"" + reference.kind + "\", \"" + reference.dimension + "\">"
                  : "\"Arrays." + reference.name + "\"";
  }
  return result;
}
std::string regionBody(const std::string &body) { return body.empty() ? "^bb0:\n" : body; }
// Native control arms require an authored statement. These fixtures end each
// binding list in a dimensionless Real selection; a literal write needs no
// numeric capture and does not add a Real carrier to the target.
std::string controlBody(const std::string &name, const StorageReferences &references,
                        const std::string &body) {
  return body.empty() ? assignment(name + "Body", references.back().name, "", "0.0", false) : body;
}
std::string ifArm(const std::string &name, const std::string &condition,
                  const StorageReferences &references, const std::string &body = "") {
  return "\"nodal.analog_if_arm\"(" + referenceList(references, "operands") +
         ") <{reference_form = \"storage\", arm_id = \"Arrays." + name +
         "\", owner = \"Arrays\", is_else = false, stage = \"runtime\", condition_value = \"" +
         condition +
         "\", condition_kind = \"boolean\", condition_dimension = \"1\", "
         "condition_reads = [" +
         referenceList(references, "reads") +
         "], static_value_present = false, static_value = false, " + metadata(name) + "}> ({\n" +
         controlBody(name, references, body) + "}) : (" + referenceList(references, "types") +
         ") -> ()\n";
}
std::string ifGroup(const std::string &name, const std::string &arms) {
  return "\"nodal.analog_if\"() <{statement_id = \"Arrays." + name + "\", owner = \"Arrays\", " +
         metadata(name) + "}> ({\n" + arms + "}) : () -> ()\n";
}
std::string booleanCase(const std::string &name, const std::string &selector,
                        const StorageReferences &references, const std::string &body = "") {
  return "\"nodal.analog_case\"(" + referenceList(references, "operands") +
         ") <{reference_form = \"storage\", statement_id = \"Arrays." + name +
         "\", owner = \"Arrays\", selector_value = \"" + selector +
         "\", selector_kind = \"boolean\", selector_dimension = \"1\", selector_reads = [" +
         referenceList(references, "reads") +
         "], static_value_present = false, static_value = \"\", " + metadata(name) +
         "}> ({\n\"nodal.analog_case_arm\"() <{arm_id = \"Arrays." + name +
         ".true\", owner = \"Arrays\", is_default = false, labels = [\"boolean:true\"], "
         "metadata = {}}> ({\n" +
         controlBody(name + "True", references, body) +
         "}) : () -> ()\n\"nodal.analog_case_arm\"() <{arm_id = \"Arrays." + name +
         ".default\", owner = \"Arrays\", is_default = true, labels = [], metadata = {}}> ({\n" +
         controlBody(name + "Default", references, "") + "}) : () -> ()\n}) : (" +
         referenceList(references, "types") + ") -> ()\n";
}
std::string storageLoop(const std::string &name, const std::string &bound,
                        const StorageReferences &references, const std::string &body) {
  return "\"nodal.analog_loop\"(" + referenceList(references, "operands") +
         ") <{reference_form = \"storage\", statement_id = \"Arrays." + name +
         "\", owner = \"Arrays\", stage = \"runtime\", bound_value = \"" + bound +
         "\", bound_kind = \"integer\", bound_dimension = \"1\", bound_reads = [" +
         referenceList(references, "reads") +
         "], minimum_iterations = 1 : i64, maximum_iterations = 2 : i64, "
         "static_trip_count_present = false, static_trip_count = 0 : i64, " +
         metadata(name) + "}> ({\n" + regionBody(body) + "}) : (" +
         referenceList(references, "types") + ") -> ()\n";
}
std::string eventArgument(unsigned slot, const std::string &value, const std::string &dimension,
                          const StorageReferences &references = {},
                          const std::string &kind = "real") {
  return "{slot = " + std::to_string(slot) + " : i64, value = \"" + value + "\", kind = \"" + kind +
         "\", dimension = \"" + dimension + "\", reads = [" + referenceList(references, "reads") +
         "]}";
}
std::string storageEvent(const std::string &name, const std::string &operation,
                         const std::string &arguments, const StorageReferences &references) {
  return "%" + name + " = \"nodal.analog_" + operation + "\"(" +
         referenceList(references, "operands") +
         ") <{reference_form = \"storage\", event_id = "
         "\"Arrays." +
         name +
         "\", owner = \"Arrays\", contract = \"increment37\", name = \"\", "
         "arguments = [" +
         arguments + "], analyses = [], event_reads = [" + referenceList(references, "reads") +
         "], " + metadata(name) + "}> : (" + referenceList(references, "types") +
         ") -> !nodal.analog_event\n";
}
std::string on(const std::string &name, const std::string &event, const std::string &body = "") {
  return "\"nodal.analog_on\"(%" + event + ") <{statement_id = \"Arrays." + name +
         "\", owner = \"Arrays\", " + metadata(name) + "}> ({\n" + regionBody(body) +
         "}) : (!nodal.analog_event) -> ()\n";
}
// Extract the actual array accesses from one emitted statement. Assertions can
// compare coordinate identities without depending on private-name spelling.
std::vector<std::string> arrayAccesses(const std::string &statement,
                                       const std::string &root = "event_Arrays_values") {
  std::vector<std::string> result;
  const std::string prefix = root + "[";
  size_t position = 0;
  while ((position = statement.find(prefix, position)) != std::string::npos) {
    const auto end = statement.find(']', position);
    if (end == std::string::npos)
      break;
    result.push_back(statement.substr(position, end + 1 - position));
    position = end + 1;
  }
  return result;
}
std::string statementContaining(const std::string &target, const std::string &anchor) {
  auto position = target.find(anchor);
  if (position == std::string::npos)
    return "";
  const auto start = target.rfind('\n', position);
  return target.substr(start == std::string::npos ? 0 : start + 1,
                       target.find('\n', position) - (start == std::string::npos ? 0 : start + 1));
}
bool before(const std::string &text, const std::string &first, const std::string &second) {
  auto firstPosition = text.find(first), secondPosition = text.find(second);
  return firstPosition != std::string::npos && secondPosition != std::string::npos &&
         firstPosition < secondPosition;
}
std::string wrap(const std::string &body, const std::string &moduleBody = "",
                 bool generated = false, bool persistent = false, bool eventFree = false) {
  std::string procedure = R"mlir("nodal.analog_procedure"() <{owner = "Arrays", metadata = {}}> ({
)mlir";
  const std::string event =
      R"mlir(%initial = "nodal.analog_initial_step"() <{event_id = "Arrays.initial", owner = "Arrays", contract = "increment37", name = "", arguments = [], analyses = [], event_reads = [], metadata = {}}> : () -> !nodal.analog_event
"nodal.analog_on"(%initial) <{statement_id = "Arrays.on", owner = "Arrays", metadata = {}}> ({
)mlir";
  if (eventFree)
    procedure += body;
  else {
    procedure += persistent ? body + event + "^bb0:\n" : event + body;
    procedure += "}) : (!nodal.analog_event) -> ()\n";
  }
  procedure += "}) : () -> ()\n";
  size_t orderPosition = 0;
  unsigned order = 0;
  const std::string orderToken = "authored_order = 0 : i64";
  while ((orderPosition = procedure.find(orderToken, orderPosition)) != std::string::npos) {
    std::string replacement = "authored_order = " + std::to_string(order++) + " : i64";
    procedure.replace(orderPosition, orderToken.size(), replacement);
    orderPosition += replacement.size();
  }
  std::string analog = "\"nodal.analog\"() <{metadata = {}}> ({\n" + procedure + "}) : () -> ()\n";
  if (generated) {
    const std::string attrs =
        "generated_owner = \"Arrays.gen\", generated_induction = \"Arrays.gen.index\", ";
    size_t position = 0;
    while ((position = analog.find("<{", position)) != std::string::npos) {
      analog.insert(position + 2, attrs);
      position += 2 + attrs.size();
    }
    analog = "\"nodal.generate\"() <{induction = \"array_gen\", lower = 0 : i64, upper = 2 : i64, "
             "step = 1 : i64, region_id = \"Arrays.gen\", induction_path = \"Arrays.gen.index\", "
             "metadata = {maximum_trip_count = 2 : i64}}> ({\n" +
             analog + "}) : () -> ()\n";
  }
  return "module attributes {nodal.backend.profile = \"verilog-a\", nodal.target.profile = "
         "\"analog\"} {\n"
         "\"nodal.module\"() <{sym_name = \"Arrays\", metadata = {root = true, semantic_path = "
         "\"Arrays\"}}> ({\n" +
         literal(0) + literal(1) + literal(2) + literal(3) + literal(4) + literal(5) + moduleBody +
         analog + "}) : () -> ()\n}\n";
}
std::string parameter() {
  return R"mlir("nodal.parameter"() <{classification = "structural", parameter_kind = "integer", default_value = 4 : i64, sym_name = "COUNT", type = i64, variability = "symbolic", metadata = {semantic_path = "Arrays.COUNT"}}> : () -> ()
%c8 = "nodal.const_literal"() <{spelling = "8", value = 8 : i64, metadata = {}}> : () -> i64
"nodal.parameter_constraint"(%c3, %c8) <{constraint_kind = "range", lower_inclusive = true, upper_inclusive = true, parameter = @COUNT, metadata = {}}> : (i64, i64) -> ()
"nodal.parameter_envelope"() <{effects = ["shape"], policy = "static_generate", parameter = @COUNT, metadata = {}}> : () -> ()
)mlir";
}
std::string withRealDimension(std::string source, const std::string &dimension) {
  const std::string original = "\"real\", \"1\"";
  const std::string replacement = "\"real\", \"" + dimension + "\"";
  size_t position = 0;
  while ((position = source.find(original, position)) != std::string::npos) {
    source.replace(position, original.size(), replacement);
    position += replacement.size();
  }
  return source;
}
int fail(llvm::StringRef message) {
  llvm::errs() << message << "\n";
  return 1;
}
} // namespace

int main(int argc, char **argv) {
  auto artifact = [&](const char *name, const std::string &source, const std::string &target,
                      const char *extension = ".va") {
    if (argc < 2)
      return;
    std::ofstream(std::string(argv[1]) + "/" + name + ".mlir") << source;
    std::ofstream(std::string(argv[1]) + "/" + name + extension) << target;
  };
  mlir::DialectRegistry registry;
  registry.insert<circt::hw::HWDialect, nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  context.loadAllAvailableDialects();
  auto emit = [&](const std::string &source, std::string &output,
                  nodal::BackendKind kind = nodal::BackendKind::VerilogA) {
    auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (!module || mlir::failed(mlir::verify(*module)))
      return false;
    llvm::raw_string_ostream stream(output);
    return mlir::succeeded(nodal::emitBackend(*module, kind, stream));
  };
  auto deterministicTarget = [&](const std::string &source, std::string &target,
                                 nodal::BackendKind kind = nodal::BackendKind::VerilogA) {
    auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (!module || mlir::failed(mlir::verify(*module)))
      return false;
    auto configuration = nodal::resolveBackendConfiguration(*module, kind);
    std::string again;
    return mlir::succeeded(configuration) && emit(source, target, kind) &&
           emit(source, again, kind) && target == again &&
           mlir::succeeded(nodal::reparseBackendTarget(target, *configuration));
  };
  const std::string fixed =
      fill("fill", "2,3") + declaration("values", "2,3", "fill") +
      index("selected", "values", "2,3", "%c1, %c2", "i64, i64") +
      assignment("write", "selected", "", "2.0", false) + read("scalar", "selected") +
      fill("same", "2,3", "Arrays.selected", "%scalar", quantity) +
      assignment("refill", "values", "2,3", "same") + read("whole", "values", "2,3") +
      declaration("copy", "2,3", "whole") + read("immutable", "values", "2,3") +
      index("immutableSelected", "immutable", "2,3", "%c0, %c1", "i64, i64", "", false) +
      assignment("immutableUse", "selected", "", "Arrays.immutableSelected", false,
                 "%immutableSelected", quantity) +
      read("scalarForInit", "selected") +
      "%scalarCopy = \"nodal.analog_variable\"(%scalarForInit) <{identity = \"Arrays.scalarCopy\", "
      "owner = \"Arrays\", declaration_order = 2 : i64, initialized = true, initializer_value = "
      "\"\", "
      "initializer_kind = \"\", initializer_dimension = \"\", initializer_reads = [], "
      "metadata = {semantic_path = \"Arrays.scalarCopy\"}}> : (" +
      quantity + ") -> " + variable + "\n";
  std::string output;
  if (!emit(wrap(fixed), output))
    return fail("fixed scalar selection/fill/copy emission failed");
  if (output.find("real event_Arrays_values[0:5];") == std::string::npos ||
      output.find("event_Arrays_write_target_map = 5;") == std::string::npos ||
      output.find("event_Arrays_same = event_Arrays_scalar;") == std::string::npos ||
      output.find("event_Arrays_copy[event_Arrays_copy_copy_index] = event_Arrays_whole[") ==
          std::string::npos)
    return fail("fixed storage rendering lost row-major selection or snapshot/fill semantics");
  artifact("fixed", wrap(fixed), output);
  std::string repeat;
  if (!emit(wrap(fixed), repeat) || repeat != output)
    return fail("array target is nondeterministic");
  const std::string overlap =
      fill("fill", "COUNT") + declaration("values", "COUNT", "fill") +
      index("first", "values", "COUNT", "%c0", "i64") +
      assignment("setFirst", "first", "", "1.0", false) +
      index("second", "values", "COUNT", "%c1", "i64") +
      assignment("setSecond", "second", "", "2.0", false) +
      index("third", "values", "COUNT", "%c2", "i64") +
      assignment("setThird", "third", "", "3.0", false) +
      slice("left", "values", "COUNT", 0, 2, "2") + slice("right", "values", "COUNT", 1, 3, "2") +
      read("before", "left", "2") + assignment("overlap", "right", "2", "before") +
      read("after", "right", "2") + assignment("back", "left", "2", "after");
  output.clear();
  if (!emit(wrap(overlap, parameter()), output))
    return fail("symbolic overlapping copy failed");
  if (output.find("real event_Arrays_values[0:(COUNT - 1)];") == std::string::npos ||
      output.find(" < COUNT;") == std::string::npos ||
      output.find("event_Arrays_before[event_Arrays_before_copy_index] = ") >
          output.find(
              "event_Arrays_values[event_Arrays_overlap_target_map] = event_Arrays_before["))
    return fail("symbolic overlap lost retained extent or all-RHS-before-write ordering");
  artifact("symbolic", wrap(overlap, parameter()), output);
  const std::string mapped =
      fill("fill", "2,3") + declaration("values", "2,3", "fill") +
      slice("columns", "values", "2,3", 1, 3, "2,2", 1) + view("flat", "columns", "2,2", "4") +
      index("tail", "flat", "4", "%c3", "i64") + assignment("tailWrite", "tail", "", "9.0", false) +
      read("captured", "flat", "4") + view("reshaped", "captured", "4", "2,2", false) +
      assignment("self", "columns", "2,2", "reshaped");
  output.clear();
  if (!emit(wrap(mapped), output) || output.find(" % 2") == std::string::npos ||
      output.find(" / 2") == std::string::npos)
    return fail("noncontiguous composed map failed");
  artifact("mapped", wrap(mapped), output);
  std::string generated;
  if (!emit(wrap(overlap, parameter(), true), generated))
    return fail("generated event array failed");
  if (generated.find("real event_Arrays_values[") < generated.find("for (array_gen"))
    return fail("generated array escaped its owning occurrence");
  artifact("generated", wrap(overlap, parameter(), true), generated);
  // Authored genvars share the target identifier namespace with private array
  // carriers, snapshot counters and composed-map temporaries.
  for (const std::string collision : {"event_Arrays_values", "event_Arrays_overlap_copy_index",
                                      "event_Arrays_overlap_target_map"}) {
    std::string source = wrap(overlap, parameter(), true);
    const std::string original = "induction = \"array_gen\"";
    source.replace(source.find(original), original.size(), "induction = \"" + collision + "\"");
    std::string target;
    if (!emit(source, target) || target.find("genvar " + collision + ";") == std::string::npos ||
        target.find(collision + "_1") == std::string::npos)
      return fail("authored genvar collided with private array storage, counter or map");
  }
  // Ordinary procedures share the ordered array renderer. Explicit writes still
  // run at their authored point, independently of declaration initialization.
  auto ordinaryMapped = [&](const std::string &extent) {
    const std::string dimensions = "2," + extent;
    return declaration("values", dimensions) + fill("fill", dimensions, "0.0") +
           assignment("initialize", "values", dimensions, "fill") +
           slice("columns", "values", dimensions, 0, 2, "2,2", 1) +
           view("flat", "columns", "2,2", "4") +
           slice("shifted", "values", dimensions, 1, 3, "2,2", 1) +
           view("shiftedFlat", "shifted", "2,2", "4") + read("before", "flat", "4") +
           assignment("overlap", "shiftedFlat", "4", "before") +
           index("tail", "shiftedFlat", "4", "%c3", "i64") + read("tailRead", "tail") +
           fill("refill", "2,2", "Arrays.tail", "%tailRead", quantity) +
           assignment("refillColumns", "columns", "2,2", "refill") +
           scope("copyScope", read("after", "columns", "2,2") +
                                  declaration("copy", "2,2", "after") +
                                  read("copyRead", "copy", "2,2") +
                                  assignment("restore", "shifted", "2,2", "copyRead"));
  };
  auto eventFreeTarget = [&](const std::string &source, std::string &target) {
    return deterministicTarget(source, target) && target.find("@(") == std::string::npos;
  };
  // Persistent roots use the declaration initialization phase in either analog
  // language profile. Subsequent ordinary writes and lexical copies retain
  // their authored execution order and must not acquire a synthetic reset.
  const std::string persistentFixed =
      fill("fill", "2,3", "2.5") + declaration("values", "2,3", "fill") +
      index("selected", "values", "2,3", "%c1, %c2", "i64, i64") +
      assignment("write", "selected", "", "9.0", false) +
      scope("copyScope", read("after", "values", "2,3") + declaration("copy", "2,3", "after") +
                             read("copyRead", "copy", "2,3") +
                             assignment("restore", "values", "2,3", "copyRead"));
  auto profileSource = [](std::string source, nodal::BackendKind kind) {
    if (kind == nodal::BackendKind::VerilogAMS) {
      const std::string original = "nodal.backend.profile = \"verilog-a\"";
      source.replace(source.find(original), original.size(),
                     "nodal.backend.profile = \"verilog-ams\"");
    }
    return source;
  };
  // An expression initializer is evaluated at each reached local declaration,
  // including the one-capture case that must not take the legacy copy branch.
  // Prefix-similar leaf names exercise exact captured binding identities.
  const std::string expressionRoot = fill("fill", "2", "3.0") + declaration("values", "2", "fill") +
                                     index("first", "values", "2", "%c0", "i64") +
                                     index("firstMore", "values", "2", "%c1", "i64");
  const std::string firstCapture = read("firstCapture", "first");
  const std::string secondCapture = read("secondCapture", "firstMore");
  struct InitializerWitness {
    const char *name;
    std::string body;
    std::string assignment;
  };
  const std::vector<InitializerWitness> initializerWitnesses{
      {"zero", expressionDeclaration("local", "analog_add(1.0,2.0)"),
       "event_Arrays_local = (1.0 + 2.0);"},
      {"one",
       firstCapture + expressionDeclaration("local", "analog_add(Arrays.first,1.0)",
                                            "%firstCapture", quantity),
       "event_Arrays_local = (event_Arrays_firstCapture + 1.0);"},
      {"multiple",
       firstCapture + secondCapture +
           expressionDeclaration("local", "analog_add(Arrays.first,Arrays.firstMore)",
                                 "%firstCapture, %secondCapture", quantity + ", " + quantity),
       "event_Arrays_local = (event_Arrays_firstCapture + event_Arrays_secondCapture);"},
      {"projected",
       firstCapture +
           fill("scalarFill", "2", "analog_add(Arrays.first,2.0)", "%firstCapture", quantity) +
           index("selected", "scalarFill", "2", "%c0", "i64", "", false) +
           index("selectedMore", "scalarFill", "2", "%c1", "i64", "", false) +
           expressionDeclaration("local", "analog_add(Arrays.selected,Arrays.selectedMore)",
                                 "%selected, %selectedMore", quantity + ", " + quantity),
       "event_Arrays_local = (event_Arrays_selected + event_Arrays_selectedMore);"},
      {"boolean",
       firstCapture + expressionDeclaration("local", "real_gt(Arrays.first,2.0)", "%firstCapture",
                                            quantity, 1, "boolean"),
       "event_Arrays_local = (event_Arrays_firstCapture > 2.0);"}};
  for (auto kind : {nodal::BackendKind::VerilogA, nodal::BackendKind::VerilogAMS}) {
    for (const auto &witness : initializerWitnesses) {
      // Both local lifetimes are supported inside an ordinary or generated
      // analog procedure. No generated loop-local lifetime is implied here.
      for (bool generated : {false, true}) {
        for (bool eventLocal : {false, true}) {
          const std::string body = expressionRoot + witness.body +
                                   assignment("afterInitializer", "first", "", "9.0", false);
          const std::string source =
              profileSource(wrap(eventLocal ? body : scope("initializerScope", body), "", generated,
                                 false, !eventLocal),
                            kind);
          std::string target;
          const bool projected = std::string(witness.name) == "projected";
          if (!deterministicTarget(source, target, kind) ||
              llvm::StringRef(target).count(witness.assignment) != 1 ||
              !before(target, "event_Arrays_fill = 3.0;", witness.assignment) ||
              !before(target, witness.assignment,
                      "event_Arrays_values[event_Arrays_afterInitializer_target_map] = 9.0;") ||
              (witness.body.find("%firstCapture") != std::string::npos &&
               !before(target, "event_Arrays_firstCapture = ", witness.assignment)) ||
              (std::string(witness.name) == "multiple" &&
               !before(target, "event_Arrays_secondCapture = ", witness.assignment)) ||
              (generated && !before(target, "for (array_gen", "event_Arrays_local;")) ||
              (projected &&
               (llvm::StringRef(target).count(
                    "event_Arrays_scalarFill = (event_Arrays_firstCapture + 2.0);") != 1 ||
                !before(target, "event_Arrays_scalarFill = ",
                        "event_Arrays_selected = event_Arrays_scalarFill;") ||
                !before(target, "event_Arrays_selectedMore = event_Arrays_scalarFill;",
                        witness.assignment))))
            return fail("local initializer expression lost actual captures, source arithmetic or "
                        "declaration-entry ordering");
          if (!generated && eventLocal) {
            const std::string name = std::string("initializer-expression-") + witness.name +
                                     (kind == nodal::BackendKind::VerilogAMS ? "-ams" : "");
            artifact(name.c_str(), source, target,
                     kind == nodal::BackendKind::VerilogAMS ? ".vams" : ".va");
          }
          if (generated && !eventLocal && projected)
            artifact(kind == nodal::BackendKind::VerilogAMS ? "initializer-expression-generated-ams"
                                                            : "initializer-expression-generated",
                     source, target, kind == nodal::BackendKind::VerilogAMS ? ".vams" : ".va");
        }
      }
    }
    const std::string loopSource =
        profileSource(wrap(expressionRoot + loop("initializerLoop", initializerWitnesses[1].body),
                           "", false, false, true),
                      kind);
    std::string loopTarget;
    if (!deterministicTarget(loopSource, loopTarget, kind) ||
        !before(loopTarget, "for (event_Arrays_initializerLoop_index",
                "event_Arrays_firstCapture = ") ||
        !before(loopTarget, "event_Arrays_firstCapture = ", initializerWitnesses[1].assignment) ||
        llvm::StringRef(loopTarget).count(initializerWitnesses[1].assignment) != 1)
      return fail("loop-local expression initializer escaped reached scope entry");
    const std::string dimensionedInitializer = withRealDimension(
        fill("fill", "2", "3.0 V") + declaration("values", "2", "fill") +
            index("first", "values", "2", "%c0", "i64") + firstCapture +
            expressionDeclaration("local", "analog_add(Arrays.first,1.0 V)", "%firstCapture",
                                  quantity, 1, "real", "voltage"),
        "voltage");
    std::string dimensionedTarget;
    if (!deterministicTarget(profileSource(wrap(dimensionedInitializer), kind), dimensionedTarget,
                             kind) ||
        dimensionedTarget.find("event_Arrays_local = (event_Arrays_firstCapture + 1.0);") ==
            std::string::npos)
      return fail("dimensioned scalar expression initializer lost exact-unit rendering");
  }
  // Malformed native forms are rejected for their causal contract violation,
  // and neither native nor target-only failures may modify caller-owned output.
  auto replaceOnce = [](std::string source, const std::string &from, const std::string &to) {
    source.replace(source.find(from), from.size(), to);
    return source;
  };
  struct InitializerRejection {
    const char *name;
    std::string source;
    const char *diagnostic;
    bool nativeValid = false;
  };
  const char *expressionFormDiagnostic =
      "NODAL-ANALOG-033-004: expression initializer requires initialized local scalar storage "
      "and empty textual read inventory";
  const char *sourceContractDiagnostic =
      "NODAL-ANALOG-037-010: procedural source expression does not match its scalar contract";
  const std::string zeroInitializer = expressionDeclaration("local", "1.0", "", "", 0);
  const std::string oneInitializer =
      expressionDeclaration("local", "analog_add(Arrays.first,1.0)", "%firstCapture", quantity);
  const std::string projectedInitializer =
      expressionDeclaration("local", "analog_add(Arrays.selected,1.0)", "%selected", quantity);
  const std::string fillProjection =
      firstCapture + fill("scalarFill", "2", "Arrays.first", "%firstCapture", quantity) +
      index("selected", "scalarFill", "2", "%c0", "i64", "", false);
  std::vector<InitializerRejection> initializerRejections{
      {"root", wrap(zeroInitializer, "", false, false, true), expressionFormDiagnostic},
      {"shaped", wrap(replaceOnce(zeroInitializer, ") -> " + variable, ") -> " + shape("2"))),
       expressionFormDiagnostic},
      {"uninitialized",
       wrap(replaceOnce(zeroInitializer, "initialized = true", "initialized = false")),
       expressionFormDiagnostic},
      {"unknown-form",
       wrap(replaceOnce(zeroInitializer, "initializer_form = \"expression\"",
                        "initializer_form = \"other\"")),
       "NODAL-ANALOG-033-004: unknown procedural initializer form"},
      {"textual-reads",
       wrap(expressionRoot + firstCapture +
            replaceOnce(oneInitializer, "initializer_reads = []",
                        "initializer_reads = [\"Arrays.values\"]")),
       expressionFormDiagnostic},
      {"missing-capture",
       wrap(expressionRoot + expressionDeclaration("local", "analog_add(Arrays.first,1.0)")),
       sourceContractDiagnostic},
      {"extra-capture",
       wrap(expressionRoot + firstCapture +
            expressionDeclaration("local", "1.0", "%firstCapture", quantity)),
       "NODAL-ANALOG-037-010: procedural expression reads or static value were forged"},
      {"duplicate-capture",
       wrap(expressionRoot + firstCapture +
            expressionDeclaration("local", "analog_add(Arrays.first,1.0)",
                                  "%firstCapture, %firstCapture", quantity + ", " + quantity)),
       "NODAL-ANALOG-037-010: initializer captures must bind unique immutable scalar values"},
      {"live-handle",
       wrap(expressionRoot +
            expressionDeclaration("local", "analog_add(Arrays.first,1.0)", "%first", variable)),
       "NODAL-ANALOG-034-014: assignment operands require explicit captured values"},
      {"shaped-capture",
       wrap(expressionRoot + expressionDeclaration("local", "1.0", "%fill", shape("2", false))),
       "NODAL-ANALOG-037-010: initializer captures must bind unique immutable scalar values"},
      {"claimed-kind", wrap(expressionDeclaration("local", "1.0", "", "", 0, "boolean")),
       sourceContractDiagnostic},
      {"claimed-dimension", wrap(expressionDeclaration("local", "1.0 V", "", "", 0)),
       sourceContractDiagnostic},
      {"destination-dimension",
       wrap(replaceOnce(expressionDeclaration("local", "1.0 V", "", "", 0, "real", "voltage"),
                        ") -> !nodal.variable<\"real\", \"voltage\">",
                        ") -> !nodal.variable<\"real\", \"time\">")),
       "NODAL-ANALOG-033-005: procedural initializer dimension does not match the declared "
       "variable dimension"},
      {"ordered-write",
       wrap(expressionRoot + firstCapture + assignment("crossedWrite", "first", "", "9.0", false) +
            oneInitializer),
       "NODAL-ANALOG-033-017: scalar snapshot crosses an ordered statement boundary"},
      {"projection-ordered-write",
       wrap(expressionRoot + fillProjection +
            assignment("crossedWrite", "first", "", "9.0", false) + projectedInitializer),
       "NODAL-ANALOG-033-017: snapshot cannot cross an ordered statement or scope boundary"},
      {"future-capture", wrap(expressionRoot + oneInitializer + firstCapture),
       "NODAL-ANALOG-033-017: scalar captures require unique dominating owned reads"},
      {"capture-scope", wrap(expressionRoot + firstCapture + scope("localScope", oneInitializer)),
       "NODAL-ANALOG-033-017: scalar captures require unique dominating owned reads"},
      {"foreign-owner",
       wrap(replaceOnce(zeroInitializer, "owner = \"Arrays\"", "owner = \"Foreign\"")),
       "NODAL-ANALOG-034-014: structured procedural operation owner does not match the enclosing "
       "component"},
      {"self-reference",
       wrap(expressionDeclaration("local", "analog_add(Arrays.local,1.0)", "", "", 0)),
       "NODAL-ANALOG-037-010: procedural expression reads or static value were forged"},
      {"incoming-selected-da",
       wrap(declaration("values", "2") + index("first", "values", "2", "%c0", "i64") +
            index("firstMore", "values", "2", "%c1", "i64") +
            assignment("initializeFirstOnly", "first", "", "1.0", false) + secondCapture +
            fill("scalarFill", "2", "Arrays.firstMore", "%secondCapture", quantity) +
            index("selected", "scalarFill", "2", "%c0", "i64", "", false) + projectedInitializer),
       "NODAL-ANALOG-034-004: procedural variable 'Arrays.values' is read before definite "
       "initialization on this control-flow path"},
      {"generated-loop-lifetime",
       wrap(expressionRoot + loop("initializerLoop", firstCapture + oneInitializer), "", true,
            false, true),
       "generated loop-local declarations would create unsupported histories", true},
      {"target-layout",
       wrap(fill("fill", "2147483648") + declaration("values", "2147483648", "fill") +
            index("first", "values", "2147483648", "%c0", "i64") + firstCapture + oneInitializer),
       "array shape/count intermediate exceeds portable signed32 indices", true}};
  for (const char *attribute : {"generated_owner", "generated_induction"}) {
    std::string source = wrap(expressionRoot + firstCapture + oneInitializer, "", true);
    const auto declarationStart = source.find("%local = \"nodal.analog_variable\"");
    const std::string prefix = std::string(attribute) + " = \"";
    const auto valueStart = source.find(prefix, declarationStart) + prefix.size();
    source.replace(valueStart, source.find('"', valueStart) - valueStart, "Arrays.foreign");
    initializerRejections.push_back(
        {attribute, source,
         "NODAL-ITERATION-043-004: generated analog ownership or induction does not match its "
         "enclosing generate region"});
  }
  for (const auto &rejection : initializerRejections) {
    for (auto kind : {nodal::BackendKind::VerilogA, nodal::BackendKind::VerilogAMS}) {
      const auto source = profileSource(rejection.source, kind);
      std::string diagnostics;
      mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &diagnostic) {
        llvm::raw_string_ostream stream(diagnostics);
        diagnostic.print(stream);
        return mlir::success();
      });
      auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
      const bool nativeValid = module && mlir::succeeded(mlir::verify(*module));
      if (nativeValid != rejection.nativeValid)
        return fail(std::string("initializer rejection missed its native boundary: ") +
                    rejection.name + "\n" + diagnostics);
      std::string rejected = "sentinel";
      if (emit(source, rejected, kind) || rejected != "sentinel" ||
          diagnostics.find(rejection.diagnostic) == std::string::npos)
        return fail(std::string("initializer rejection lost causal diagnostic or transactional "
                                "output: ") +
                    rejection.name + "\n" + diagnostics);
    }
  }
  for (auto kind : {nodal::BackendKind::VerilogA, nodal::BackendKind::VerilogAMS}) {
    const bool ams = kind == nodal::BackendKind::VerilogAMS;
    for (bool events : {false, true}) {
      const std::string source =
          profileSource(wrap(persistentFixed, "", false, events, !events), kind);
      std::string target;
      const std::string initial = "real event_Arrays_values[0:5] = '{6{2.5}};";
      if (!deterministicTarget(source, target, kind) || !before(target, initial, "analog begin") ||
          target.find("event_Arrays_values_copy_index") != std::string::npos ||
          target.find("event_Arrays_values_source_map") != std::string::npos ||
          target.find("event_Arrays_values_target_map") != std::string::npos ||
          target.find("event_Arrays_fill") != std::string::npos ||
          target.find("analog initial") != std::string::npos ||
          llvm::StringRef(target).count("@(") != (events ? 1u : 0u) ||
          !before(target, "event_Arrays_values[event_Arrays_write_target_map] = 9.0;",
                  "event_Arrays_after[event_Arrays_after_copy_index] = ") ||
          !before(target, "event_Arrays_after[event_Arrays_after_copy_index] = ",
                  "event_Arrays_copy[event_Arrays_copy_copy_index] = event_Arrays_after[") ||
          !before(target, "event_Arrays_copyRead[event_Arrays_copyRead_copy_index] = ",
                  "event_Arrays_values[event_Arrays_restore_copy_index] = "))
        return fail("persistent root lost declaration lifetime, carrier suppression or copy order");
      artifact(ams ? (events ? "persistent-event-ams" : "persistent-fixed-ams")
                   : (events ? "persistent-event" : "persistent-fixed"),
               source, target, ams ? ".vams" : ".va");
    }
  }
  // Symbolic cardinality and a dimensioned static parameter remain symbolic;
  // parameter defaults are not substituted into the assignment pattern.
  const std::string levelParameter = R"mlir(
"nodal.parameter"() <{classification = "ordinary", default_value = 2.5 : f64, sym_name = "LEVEL", type = f64, parameter_kind = "real", unit = @V, variability = "symbolic", metadata = {semantic_path = "Arrays.LEVEL"}}> : () -> ()
)mlir";
  const std::string voltUnit = R"mlir(
"nodal.unit"() <{dimension = "voltage", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "V", symbol = "V"}> : () -> ()
)mlir";
  std::string persistentSymbolic =
      wrap(withRealDimension(fill("fill", "COUNT", "Arrays.LEVEL") +
                                 declaration("values", "COUNT", "fill") +
                                 read("after", "values", "COUNT"),
                             "voltage"),
           parameter() + levelParameter, false, false, true);
  persistentSymbolic.insert(persistentSymbolic.find("\"nodal.module\""), voltUnit);
  for (auto kind : {nodal::BackendKind::VerilogA, nodal::BackendKind::VerilogAMS}) {
    const bool ams = kind == nodal::BackendKind::VerilogAMS;
    const std::string source = profileSource(persistentSymbolic, kind);
    std::string target;
    if (!deterministicTarget(source, target, kind) ||
        target.find("real event_Arrays_values[0:(COUNT - 1)] = '{COUNT{LEVEL}};") ==
            std::string::npos ||
        target.find("parameter real LEVEL") == std::string::npos ||
        target.find("event_Arrays_values_copy_index") != std::string::npos ||
        target.find("event_Arrays_fill") != std::string::npos ||
        target.find("@(") != std::string::npos)
      return fail("persistent dimensioned parameter fill lost symbolic count or static value");
    artifact(ams ? "persistent-symbolic-ams" : "persistent-symbolic", source, target,
             ams ? ".vams" : ".va");
  }
  // A pure index/slice/view chain of a uniform fill keeps its value but changes
  // its destination cardinality. No initializer-only carrier survives at runtime.
  const std::string persistentView =
      fill("fill", "2,3", "7.0") +
      index("initializerRow", "fill", "2,3", "%c1", "i64", "3", false) +
      slice("initializerTail", "initializerRow", "3", 1, 3, "2", 0, false) +
      view("initializerView", "initializerTail", "2", "1,2", false) +
      declaration("values", "1,2", "initializerView");
  std::string viewTarget;
  const std::string viewSource = wrap(persistentView, "", false, false, true);
  if (!eventFreeTarget(viewSource, viewTarget) ||
      viewTarget.find("real event_Arrays_values[0:1] = '{2{7.0}};") == std::string::npos ||
      viewTarget.find("event_Arrays_fill") != std::string::npos ||
      viewTarget.find("event_Arrays_initializer") != std::string::npos ||
      viewTarget.find("copy_index") != std::string::npos ||
      viewTarget.find("source_map") != std::string::npos ||
      viewTarget.find("target_map") != std::string::npos ||
      viewTarget.find("for (") != std::string::npos)
    return fail("persistent view initializer used producer extent or left runtime carriers");
  artifact("persistent-view", viewSource, viewTarget);
  // Native capture freshness prohibits using a fill in a later ordered copy
  // across its root declaration. A scalar projection before that declaration is
  // a valid shared runtime consumer and must keep the actual SSA fill carrier.
  const std::string sharedSource =
      wrap(fill("fill", "4", "3.5") + index("sharedScalar", "fill", "4", "%c2", "i64", "", false) +
               declaration("values", "4", "fill"),
           "", false, false, true);
  std::string sharedTarget;
  if (!eventFreeTarget(sharedSource, sharedTarget) ||
      sharedTarget.find("real event_Arrays_values[0:3] = '{4{3.5}};") == std::string::npos ||
      sharedTarget.find("real event_Arrays_fill;") == std::string::npos ||
      sharedTarget.find("event_Arrays_values_copy_index") != std::string::npos ||
      !before(sharedTarget, "event_Arrays_fill = 3.5;",
              "event_Arrays_sharedScalar = event_Arrays_fill;"))
    return fail("persistent initializer suppressed or reordered a shared runtime fill carrier");
  artifact("persistent-shared-fill", sharedSource, sharedTarget);
  // Declaration initialization does not relax native mutable-read or snapshot
  // freshness checks, even when the mutable source was initialized statically.
  const std::string staticRoot = fill("fill", "4") + declaration("values", "4", "fill");
  for (const std::string &body :
       {staticRoot + index("first", "values", "4", "%c0", "i64") + read("captured", "first") +
            fill("mutableFill", "4", "Arrays.first", "%captured", quantity) +
            declaration("copy", "4", "mutableFill"),
        staticRoot + read("captured", "values", "4") + declaration("copy", "4", "captured")}) {
    const std::string source = wrap(body, "", false, false, true);
    std::string diagnostics;
    mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &diagnostic) {
      llvm::raw_string_ostream stream(diagnostics);
      diagnostic.print(stream);
      return mlir::success();
    });
    auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (module && mlir::succeeded(mlir::verify(*module)))
      return fail("mutable persistent shaped initializer passed native verification");
    std::string rejected = "sentinel";
    if (emit(source, rejected) || rejected != "sentinel" ||
        diagnostics.find("persistent shaped initialization requires static fill without mutable "
                         "reads") == std::string::npos)
      return fail("mutable persistent initializer missed native static-fill rejection");
  }
  {
    const std::string source =
        wrap(staticRoot + assignment("reuse", "values", "4", "fill"), "", false, false, true);
    std::string diagnostics;
    mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &diagnostic) {
      llvm::raw_string_ostream stream(diagnostics);
      diagnostic.print(stream);
      return mlir::success();
    });
    auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (module && mlir::succeeded(mlir::verify(*module)))
      return fail("shared fill crossed a persistent declaration at the native boundary");
    std::string rejected = "sentinel";
    if (emit(source, rejected) || rejected != "sentinel" ||
        diagnostics.find("snapshot cannot cross an ordered statement or scope boundary") ==
            std::string::npos)
      return fail("persistent declaration weakened native shared-fill freshness");
  }
  for (bool symbolic : {false, true}) {
    const std::string source = wrap(ordinaryMapped(symbolic ? "COUNT" : "3"),
                                    symbolic ? parameter() : "", false, false, true);
    std::string target;
    if (!eventFreeTarget(source, target))
      return fail("ordinary mapped array failed deterministic emission or target reparse");
    if (target.find("real event_Arrays_values[") == std::string::npos ||
        target.find(" % 2") == std::string::npos || target.find(" / 2") == std::string::npos ||
        (symbolic && (target.find("COUNT") == std::string::npos ||
                      target.find("(2 * COUNT)") == std::string::npos)) ||
        !before(target, "event_Arrays_fill = 0.0;", "for (event_Arrays_initialize_copy_index") ||
        !before(target, "event_Arrays_before[event_Arrays_before_copy_index] = ",
                "event_Arrays_values[event_Arrays_overlap_target_map] = event_Arrays_before[") ||
        !before(target, "event_Arrays_values[event_Arrays_overlap_target_map] = ",
                "event_Arrays_tailRead = ") ||
        !before(target, "event_Arrays_refill = event_Arrays_tailRead;",
                "for (event_Arrays_refillColumns_copy_index") ||
        !before(target, "event_Arrays_after[event_Arrays_after_copy_index] = ",
                "event_Arrays_copy[event_Arrays_copy_copy_index] = event_Arrays_after["))
      return fail("ordinary mapped copy lost symbolic layout, fill capture or authored ordering");
    artifact(symbolic ? "ordinary-symbolic" : "ordinary-fixed", source, target);
  }
  // These generated bodies vary in authored read/write count and compose a
  // lexical initialized copy with multiple mapped snapshots. Admission must be
  // based on verified storage semantics rather than a fixture operation count.
  for (unsigned repetitions : {1u, 3u, 7u}) {
    std::string body = ordinaryMapped("COUNT");
    for (unsigned i = 0; i < repetitions; ++i) {
      const std::string suffix = std::to_string(i);
      body += read("repeatRead" + suffix, "shifted", "2,2") +
              assignment("repeatWrite" + suffix, "columns", "2,2", "repeatRead" + suffix);
    }
    const std::string source = wrap(body, parameter(), true, false, true);
    std::string target;
    if (!eventFreeTarget(source, target) ||
        !before(target, "for (array_gen", "real event_Arrays_values[") ||
        !before(target, "for (array_gen", "real event_Arrays_before[") ||
        !before(target, "for (array_gen", "integer event_Arrays_overlap_copy_index;") ||
        !before(target, "for (array_gen", "real event_Arrays_copy[") ||
        !before(target, "event_Arrays_repeatRead0[event_Arrays_repeatRead0_copy_index] = ",
                "event_Arrays_values[event_Arrays_repeatWrite0_target_map] = "))
      return fail("generated event-free composition lost independent storage or ordered copies");
    if (repetitions == 3)
      artifact("ordinary-generated", source, target);
  }
  // A lexical declaration initializer must still execute after the preceding
  // write in an event-free procedure; it is not a procedure-root initializer.
  std::string scopedTarget;
  if (!eventFreeTarget(wrap(scope("local", fixed), "", false, false, true), scopedTarget) ||
      !before(scopedTarget, "event_Arrays_fill = 1.0;", "for (event_Arrays_values_copy_index") ||
      !before(scopedTarget, "event_Arrays_scalarForInit = ",
              "event_Arrays_scalarCopy = event_Arrays_scalarForInit;"))
    return fail("ordinary lexical initializer lost authored fill/copy/read evaluation");
  std::string dimensionedBody = fill("fill", "2", "1.0 V") + declaration("values", "2", "fill") +
                                read("voltageSnapshot", "values", "2") +
                                declaration("copy", "2", "voltageSnapshot");
  size_t dimensionPosition = 0;
  const std::string dimensionless = "\"real\", \"1\"";
  while ((dimensionPosition = dimensionedBody.find(dimensionless, dimensionPosition)) !=
         std::string::npos) {
    dimensionedBody.replace(dimensionPosition, dimensionless.size(), "\"real\", \"voltage\"");
    dimensionPosition += 17;
  }
  std::string dimensioned;
  if (!emit(wrap(dimensionedBody), dimensioned))
    return fail("dimensioned Real fill and initialized-variable copy failed");
  const std::string compoundDefinition = R"mlir(
%extent = "nodal.const_expr"(%c2, %c3) <{operator_name = "mul", metadata = {semantic_path = "Arrays.extent"}}> : (i64, i64) -> i64
)mlir";
  std::string compound;
  if (!emit(wrap(fill("fill", "Arrays.extent") + declaration("values", "Arrays.extent", "fill"),
                 compoundDefinition),
            compound) ||
      compound.find("(2 * 3)") == std::string::npos)
    return fail("retained compound extent emission failed");
  std::string deep = fill("fill", "2,3") + declaration("values", "2,3", "fill");
  std::string previous = "values";
  for (unsigned depth = 0; depth < 48; ++depth) {
    auto next = "identity" + std::to_string(depth);
    deep += slice(next, previous, "2,3", 0, 3, "2,3", 1);
    previous = next;
  }
  deep += read("deepSnapshot", previous, "2,3") + declaration("copy", "2,3", "deepSnapshot");
  std::string deepOutput;
  if (!emit(wrap(deep), deepOutput) || deepOutput.size() > 30000 ||
      llvm::StringRef(deepOutput).count("event_Arrays_deepSnapshot_target_map = ") != 48)
    return fail("projection stages expanded with depth instead of staying linear");
  artifact("deep", wrap(deep), deepOutput);
  const std::string wideExpression = R"mlir(
%wide = "nodal.const_literal"() <{value = 4000000000 : i64, spelling = "4000000000", metadata = {}}> : () -> i64
%near = "nodal.const_literal"() <{value = 3999999996 : i64, spelling = "3999999996", metadata = {}}> : () -> i64
%small = "nodal.const_expr"(%wide, %near) <{operator_name = "sub", metadata = {semantic_path = "Arrays.small"}}> : (i64, i64) -> i64
)mlir";
  std::string boundary;
  if (!emit(wrap(fill("fill", "2147483647") + declaration("values", "2147483647", "fill")),
            boundary) ||
      boundary.size() > 4096 || boundary.find("[0:2147483646]") == std::string::npos)
    return fail("signed32 endpoint lost compact layout or was spuriously rejected");
  const std::string persistentBoundary =
      wrap(fill("fill", "2147483647", "6.5") + declaration("values", "2147483647", "fill"), "",
           false, false, true);
  std::string persistentBoundaryTarget;
  if (!eventFreeTarget(persistentBoundary, persistentBoundaryTarget) ||
      persistentBoundaryTarget.size() > 4096 ||
      persistentBoundaryTarget.find(
          "real event_Arrays_values[0:2147483646] = '{2147483647{6.5}};") == std::string::npos ||
      persistentBoundaryTarget.find("copy_index") != std::string::npos ||
      persistentBoundaryTarget.find("event_Arrays_fill") != std::string::npos)
    return fail("persistent signed32 endpoint lost compact declaration initialization");
  artifact("persistent-signed32-boundary", persistentBoundary, persistentBoundaryTarget);
  for (const auto &source :
       {wrap(fill("fill", "2147483648") + declaration("values", "2147483648", "fill")),
        wrap(fill("fill", "2147483648") + declaration("values", "2147483648", "fill"), "", false,
             false, true),
        wrap(fill("fill", "46341,46341") + declaration("values", "46341,46341", "fill"), "", false,
             false, true),
        wrap(fill("fill", "Arrays.small") + declaration("values", "Arrays.small", "fill"),
             wideExpression),
        wrap(fill("fill", "Arrays.small") + declaration("values", "Arrays.small", "fill"),
             wideExpression, false, false, true),
        wrap(fill("fill", "2147483648") +
                 slice("smallView", "fill", "2147483648", 0, 2, "2", 0, false) +
                 declaration("values", "2", "smallView"),
             "", false, false, true),
        wrap(fill("fill", "COUNT") + declaration("values", "COUNT", "fill"),
             parameter() + wideExpression +
                 "\"nodal.parameter_value\"(%small) <{parameter = @COUNT, metadata = {}}> : (i64) "
                 "-> ()\n")}) {
    auto validSource = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (!validSource || mlir::failed(mlir::verify(*validSource)))
      return fail("target rejection fixture was not independently native-valid");
    std::string rejected = "sentinel";
    if (emit(source, rejected) || rejected != "sentinel")
      return fail("unsupported array published target output");
  }
  // The legacy analog-iteration caller still cannot establish per-occurrence
  // declaration lifetime. The structural caller is covered by its own tests.
  for (bool events : {false, true}) {
    const std::string source =
        wrap(fill("fill", "4") + declaration("values", "4", "fill"), "", true, events, !events);
    auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (!module || mlir::failed(mlir::verify(*module)))
      return fail("generated persistent initializer was not independently native-valid");
    std::string diagnostics;
    mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &diagnostic) {
      llvm::raw_string_ostream stream(diagnostics);
      diagnostic.print(stream);
      return mlir::success();
    });
    std::string rejected = "sentinel";
    mlir::Operation *procedure = nullptr;
    module->walk([&](mlir::Operation *operation) {
      if (operation->getName().getStringRef() == "nodal.analog_procedure")
        procedure = operation;
    });
    nodal::AnalogEventRenderState state;
    llvm::raw_string_ostream stream(rejected);
    if (!procedure ||
        mlir::succeeded(nodal::prepareGeneratedAnalogEventBackend(procedure, state, stream, 2)) ||
        rejected != "sentinel" ||
        diagnostics.find(
            "generated root shaped initializer requires per-occurrence declaration lifetime") ==
            std::string::npos)
      return fail("generated root initializer lost its explicit lifetime capability boundary");
    artifact(events ? "persistent-generated-event-rejected" : "persistent-generated-rejected",
             source, rejected);
  }
  const std::string initializedRoot = declaration("values", "4") + fill("fill", "4") +
                                      assignment("initialize", "values", "4", "fill");
  std::string guarded = index("guardTarget", "values", "4", "%c0", "i64") +
                        assignment("guarded", "guardTarget", "", "2.0", false);
  const std::string noGuard =
      "guard_present = false, guard_value = \"\", guard_kind = \"\", guard_dimension = \"\"";
  guarded.replace(guarded.find(noGuard), noGuard.size(),
                  "guard_present = true, guard_value = \"true\", guard_kind = \"boolean\", "
                  "guard_dimension = \"1\"");
  std::string restricted =
      fill("restrictedFill", "4") + assignment("restricted", "values", "4", "restrictedFill");
  const std::string allAnalyses = "analyses = [\"dc\", \"transient\"]";
  restricted.replace(restricted.find(allAnalyses), allAnalyses.size(), "analyses = [\"dc\"]");
  for (bool generated : {false, true}) {
    for (const std::string &body :
         {initializedRoot + guarded, initializedRoot + restricted,
          initializedRoot +
              loop("wideControl",
                   fill("insideFill", "4") + assignment("inside", "values", "4", "insideFill"),
                   "2147483648")}) {
      const std::string source = wrap(body, "", generated, false, true);
      auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
      if (!module || mlir::failed(mlir::verify(*module)))
        return fail("event-free target rejection fixture was not independently native-valid");
      std::string diagnostics;
      mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &diagnostic) {
        llvm::raw_string_ostream stream(diagnostics);
        diagnostic.print(stream);
        return mlir::success();
      });
      std::string rejected = "sentinel";
      if (emit(source, rejected) || rejected != "sentinel")
        return fail("event-free guard, analysis or control gate leaked");
      if ((body == initializedRoot + guarded || body == initializedRoot + restricted) &&
          diagnostics.find(
              "guarded or analysis-restricted assignment requires target legalization") ==
              std::string::npos)
        return fail("guard or analysis fixture missed the shared target legalization gate");
    }
  }
  for (const std::string &body :
       {loop("history", fill("fill", "4") + declaration("values", "4", "fill")),
        initializedRoot +
            loop("outer", loop("inner", fill("insideFill", "4") +
                                            assignment("inside", "values", "4", "insideFill")))}) {
    const std::string source = wrap(body, "", true, false, true);
    auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (!module || mlir::failed(mlir::verify(*module)))
      return fail("generated loop rejection fixture was not independently native-valid");
    std::string rejected = "sentinel";
    if (emit(source, rejected) || rejected != "sentinel")
      return fail(
          "generated event-free loop manufactured local histories or admitted nested loops");
  }
  // Ownership is rejected at the native trust boundary, before target admission.
  // Keep it distinct from the independently native-valid target-only cases above.
  for (const std::string attribute : {"generated_owner", "generated_induction"}) {
    std::string source = wrap(ordinaryMapped("COUNT"), parameter(), true, false, true);
    const std::string operation = "%values = \"nodal.analog_variable\"";
    const auto start = source.find(operation);
    const auto key = source.find(attribute + " = \"", start);
    const auto valueStart = key + attribute.size() + 4;
    const auto valueEnd = source.find('"', valueStart);
    if (start == std::string::npos || key == std::string::npos || valueEnd == std::string::npos)
      return fail("event-free generated ownership mutation anchor missing");
    source.replace(valueStart, valueEnd - valueStart, "Arrays.foreign");
    auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (module && mlir::succeeded(mlir::verify(*module)))
      return fail("forged generated event-free storage passed native verification");
    std::string rejected = "sentinel";
    if (emit(source, rejected) || rejected != "sentinel")
      return fail("forged generated event-free storage published target output");
  }

  // Control references are live storage capabilities. Pure aliases may precede
  // the owner, but no numeric leaf may be captured before short-circuit or
  // first-match evaluation. The two aliases of lane zero retain their distinct
  // authored binding identities, while every consumer has its own map setup.
  const std::string flag = R"mlir(
%flag = "nodal.analog_variable"() <{identity = "Arrays.flag", owner = "Arrays", declaration_order = 1 : i64, initialized = true, initializer_value = "true", initializer_kind = "boolean", initializer_dimension = "1", initializer_reads = [], metadata = {semantic_path = "Arrays.flag"}}> : () -> !nodal.variable<"boolean", "1">
)mlir";
  const std::string controlAliases = index("first", "values", "4", "%c0", "i64") +
                                     index("second", "values", "4", "%c1", "i64") +
                                     index("alias", "values", "4", "%c0", "i64");
  const std::string shortCircuit = "bool_and(Arrays.flag,bool_or(real_gt(Arrays.first,0.0),"
                                   "real_gt(analog_div(1.0,Arrays.second),0.0)))";
  const std::string liveControls =
      ifGroup("firstMatch",
              ifArm("firstArm", shortCircuit, {{"flag", "boolean"}, {"first"}, {"second"}},
                    assignment("firstArmWrite", "first", "", "0.0", false)) +
                  ifArm("laterArm", "real_gt(analog_div(1.0,Arrays.alias),0.0)", {{"alias"}},
                        assignment("laterArmWrite", "second", "", "3.0", false))) +
      assignment("betweenConditions", "first", "", "-2.0", false) +
      ifGroup("reusedCondition",
              ifArm("afterWrite", shortCircuit, {{"flag", "boolean"}, {"first"}, {"second"}})) +
      booleanCase("choose", "real_gt(Arrays.first,Arrays.second)", {{"first"}, {"second"}}) +
      ifGroup("equalSelections",
              ifArm("aliases", "real_gt(Arrays.first,Arrays.alias)", {{"first"}, {"alias"}})) +
      ifGroup(
          "repeatedSelection",
          ifArm("repeatedLeaf", "real_gt(analog_add(Arrays.first,Arrays.first),0.0)", {{"first"}}));
  const std::string partialControlRoot =
      declaration("values", "4") + controlAliases +
      assignment("initializeFirst", "first", "", "1.0", false) +
      assignment("initializeSecond", "second", "", "2.0", false) + flag;
  const std::string persistentControlRoot =
      fill("fill", "4") + declaration("values", "4", "fill") + controlAliases + flag;
  for (bool generated : {false, true}) {
    for (bool eventLocal : {false, true}) {
      const std::string body =
          (eventLocal || generated ? partialControlRoot : persistentControlRoot) + liveControls;
      const std::string source = wrap(body, "", generated, false, !eventLocal);
      std::string target;
      if (!deterministicTarget(source, target))
        return fail("live selected control failed deterministic emission or target reparse");
      const auto first = statementContaining(target, "if ((event_Arrays_flag &&");
      const auto later = statementContaining(target, "else if (");
      const auto selector = statementContaining(target, "case (");
      const auto firstLoads = arrayAccesses(first);
      const auto laterLoads = arrayAccesses(later);
      const auto selectorLoads = arrayAccesses(selector);
      const auto aliasLine = statementContaining(target, "if ((event_Arrays_values["
                                                         "event_Arrays_aliases_reference_0_map]");
      const auto aliasLoads = arrayAccesses(aliasLine);
      const auto repeatedLoads = arrayAccesses(statementContaining(
          target, "if (((event_Arrays_values[event_Arrays_repeatedLeaf_reference_0_map]"));
      if (firstLoads.size() != 2 || firstLoads[0] == firstLoads[1] || laterLoads.size() != 1 ||
          selectorLoads.size() != 2 || selectorLoads[0] == selectorLoads[1] ||
          aliasLoads.size() != 2 || aliasLoads[0] == aliasLoads[1] || repeatedLoads.size() != 2 ||
          repeatedLoads[0] != repeatedLoads[1] ||
          llvm::StringRef(target).count("event_Arrays_repeatedLeaf_reference_0_map = ") != 1 ||
          first.find(" || ") == std::string::npos || first.find("(1.0 / ") == std::string::npos ||
          later.find("(1.0 / ") == std::string::npos ||
          !before(target, "event_Arrays_laterArm_reference_0_map = ", first) ||
          !before(target, "event_Arrays_values[event_Arrays_betweenConditions_target_map] = -2.0;",
                  "event_Arrays_afterWrite_reference_1_map = ") ||
          llvm::StringRef(target).count("real ") != 1 ||
          target.find("real event_Arrays_first") != std::string::npos ||
          target.find("real event_Arrays_alias") != std::string::npos ||
          (generated && !before(target, "for (array_gen", "integer event_Arrays_firstArm_")))
        return fail("selected control lost live leaves, distinct maps or first-match setup order");
      if (!generated && !eventLocal)
        artifact("live-storage-controls", source, target);
      if (generated && eventLocal)
        artifact("live-storage-controls-generated-event", source, target);
    }
  }

  // Native-only Integer grammar witness: the selected Real comparison chooses
  // an Integer limit. Each inner-loop entry captures it once, then body
  // predicates observe the current lane on each reached iteration. This is not
  // evidence for a public Real-to-Integer cast or generated nested-loop support.
  const std::string enteredLoop = storageLoop(
      "selectedBound", "analog_select(real_gt(Arrays.first,0.0),1,2)", {{"first"}},
      ifGroup("insideLoop", ifArm("insideCondition", "real_gt(Arrays.first,0.0)", {{"first"}})) +
          assignment("loopMutation", "first", "", "-1.0", false));
  const std::string nestedLoopSource =
      wrap(persistentControlRoot + loop("outerEntries", enteredLoop, "2"), "", false, false, true);
  std::string nestedLoopTarget;
  if (!deterministicTarget(nestedLoopSource, nestedLoopTarget) ||
      llvm::StringRef(nestedLoopTarget).count("event_Arrays_selectedBound_limit = ") != 1 ||
      !before(nestedLoopTarget, "for (event_Arrays_outerEntries_index",
              "event_Arrays_selectedBound_reference_0_map = ") ||
      !before(nestedLoopTarget, "event_Arrays_selectedBound_reference_0_map = ",
              "event_Arrays_selectedBound_limit = ") ||
      !before(nestedLoopTarget,
              "event_Arrays_selectedBound_limit = ", "for (event_Arrays_selectedBound_index") ||
      !before(nestedLoopTarget, "for (event_Arrays_selectedBound_index",
              "event_Arrays_insideCondition_reference_0_map = ") ||
      arrayAccesses(statementContaining(nestedLoopTarget, "event_Arrays_selectedBound_limit = "))
              .size() != 1 ||
      statementContaining(nestedLoopTarget, "event_Arrays_selectedBound_limit = ")
              .find(" ? 1 : 2)") == std::string::npos ||
      llvm::StringRef(nestedLoopTarget).count("real ") != 1)
    return fail("selected Integer bound lost once-per-entry capture or nested live reevaluation");
  artifact("live-storage-native-integer-bound", nestedLoopSource, nestedLoopTarget);
  const std::string generatedLoopSource =
      wrap(partialControlRoot + enteredLoop, "", true, false, true);
  std::string generatedLoopTarget;
  if (!deterministicTarget(generatedLoopSource, generatedLoopTarget) ||
      !before(generatedLoopTarget, "for (array_gen", "integer event_Arrays_selectedBound_limit;") ||
      !before(generatedLoopTarget,
              "event_Arrays_values[event_Arrays_initializeFirst_target_map] = 1.0;",
              "event_Arrays_selectedBound_limit = ") ||
      llvm::StringRef(generatedLoopTarget).count("event_Arrays_selectedBound_limit = ") != 1)
    return fail("generated selected Integer loop bound escaped its owning finite occurrence");
  artifact("live-storage-generated-integer-bound", generatedLoopSource, generatedLoopTarget);
  const std::string generatedCaseSource =
      wrap(partialControlRoot +
               storageLoop("caseBound", "analog_select(real_gt(Arrays.first,0.0),1,2)", {{"first"}},
                           booleanCase("loopChoose", "real_gt(Arrays.first,Arrays.second)",
                                       {{"first"}, {"second"}})),
           "", true, false, true);
  std::string generatedCaseTarget;
  if (!deterministicTarget(generatedCaseSource, generatedCaseTarget) ||
      !before(generatedCaseTarget, "for (event_Arrays_caseBound_index",
              "event_Arrays_loopChoose_reference_0_map = ") ||
      !before(generatedCaseTarget, "event_Arrays_loopChoose_reference_1_map = ", "case (") ||
      arrayAccesses(statementContaining(generatedCaseTarget, "case (")).size() != 2)
    return fail("generated direct Boolean case lost its typed live selector inside a finite loop");
  artifact("live-storage-generated-loop-case", generatedCaseSource, generatedCaseTarget);

  const std::string continueStatement = R"mlir(
"nodal.analog_continue"() <{statement_id = "Arrays.continueInner", owner = "Arrays", metadata = {}}> : () -> ()
)mlir";
  const std::string breakStatement = R"mlir(
"nodal.analog_break"() <{statement_id = "Arrays.breakInner", owner = "Arrays", metadata = {}}> : () -> ()
)mlir";
  const std::string exitsSource =
      wrap(persistentControlRoot +
               loop("outerWithExits",
                    loop("innerWithExits",
                         ifGroup("continueIf", ifArm("continueArm", "real_gt(Arrays.first,0.0)",
                                                     {{"first"}}, continueStatement)) +
                             assignment("afterContinue", "first", "", "-1.0", false) +
                             booleanCase("breakCase", "real_gt(Arrays.second,0.0)", {{"second"}},
                                         breakStatement),
                         "2"),
                    "2"),
           "", false, false, true);
  std::string exitsTarget;
  if (!deterministicTarget(exitsSource, exitsTarget) ||
      !before(exitsTarget, "for (event_Arrays_innerWithExits_index",
              "event_Arrays_continueArm_reference_0_map = ") ||
      !before(exitsTarget, "event_Arrays_innerWithExits_continue = 1;",
              "event_Arrays_values[event_Arrays_afterContinue_target_map] = -1.0;") ||
      !before(exitsTarget, "event_Arrays_values[event_Arrays_afterContinue_target_map] = -1.0;",
              "event_Arrays_breakCase_reference_0_map = ") ||
      exitsTarget.find("event_Arrays_innerWithExits_break = 1;") == std::string::npos ||
      exitsTarget.find("event_Arrays_outerWithExits_break = 1;") != std::string::npos ||
      exitsTarget.find("event_Arrays_outerWithExits_continue = 1;") != std::string::npos)
    return fail("nested live control changed nearest-loop break/continue or statement order");
  artifact("live-storage-nested-exits", exitsSource, exitsTarget);

  // An immediate-input slice/flatten/index map remains compact and symbolic.
  // Equal defaults must not substitute for the module-static parameter proof.
  const std::string selectedMapRoot =
      fill("fill", "2,COUNT") + declaration("values", "2,COUNT", "fill") +
      slice("columns", "values", "2,COUNT", 1, 3, "2,2", 1) + view("flat", "columns", "2,2", "4") +
      index("tail", "flat", "4", "%c3", "i64");
  const std::string selectedMapSource =
      wrap(selectedMapRoot +
               ifGroup("mapped", ifArm("mappedArm", "real_gt(Arrays.tail,0.0)", {{"tail"}})),
           parameter(), false, false, true);
  std::string selectedMapTarget;
  if (!deterministicTarget(selectedMapSource, selectedMapTarget) ||
      selectedMapTarget.find(" % 2") == std::string::npos ||
      selectedMapTarget.find(" / 2") == std::string::npos ||
      statementContaining(selectedMapTarget, "if ((event_Arrays_values[").find("COUNT") !=
          std::string::npos ||
      selectedMapTarget.find(" * COUNT") == std::string::npos ||
      arrayAccesses(statementContaining(selectedMapTarget, "if ((event_Arrays_values[")).size() !=
          1 ||
      llvm::StringRef(selectedMapTarget).count("real ") != 1)
    return fail("selected control map lost compact noncontiguous symbolic indexing");
  artifact("live-storage-symbolic-map", selectedMapSource, selectedMapTarget);
  std::string deepControl = fill("fill", "2,3") + declaration("values", "2,3", "fill");
  previous = "values";
  for (unsigned depth = 0; depth < 48; ++depth) {
    auto next = "controlView" + std::to_string(depth);
    deepControl += slice(next, previous, "2,3", 0, 3, "2,3", 1);
    previous = next;
  }
  deepControl +=
      index("deepLeaf", previous, "2,3", "%c1, %c2", "i64, i64") +
      ifGroup("deepControl", ifArm("deepArm", "real_gt(Arrays.deepLeaf,0.0)", {{"deepLeaf"}}));
  std::string deepControlTarget;
  if (!deterministicTarget(wrap(deepControl, "", false, false, true), deepControlTarget) ||
      deepControlTarget.size() > 30000 ||
      llvm::StringRef(deepControlTarget).count("event_Arrays_deepArm_reference_0_map = ") != 49 ||
      arrayAccesses(statementContaining(deepControlTarget, "if ((event_Arrays_values[")).size() !=
          1)
    return fail("deep selected control map expanded recursively or emitted a numeric snapshot");

  // Every legal monitor slot keeps its actual array leaf inline, including
  // physical-unit tolerances. OR emits one ordered monitor expression and unused
  // primitives/compositions retain their independent evaluation requests.
  const StorageReferences voltageFirst{{"voltage", "real", "voltage"}};
  const StorageReferences voltageTolerance{{"voltageTolerance", "real", "voltage"}};
  const StorageReferences startTime{{"startTime", "real", "time"}};
  const StorageReferences period{{"period", "real", "time"}};
  const StorageReferences timeTolerance{{"timeTolerance", "real", "time"}};
  const StorageReferences crossReferences{{"voltage", "real", "voltage"},
                                          {"timeTolerance", "real", "time"},
                                          {"voltageTolerance", "real", "voltage"}};
  const StorageReferences timerReferences{
      {"startTime", "real", "time"}, {"period", "real", "time"}, {"timeTolerance", "real", "time"}};
  const std::string eventRoots =
      withRealDimension(fill("voltageFill", "2", "1.0 V") +
                            declaration("values", "2", "voltageFill") +
                            index("voltage", "values", "2", "%c0", "i64") +
                            index("voltageTolerance", "values", "2", "%c1", "i64"),
                        "voltage") +
      withRealDimension(fill("timeFill", "3", "1.0 s") + declaration("times", "3", "timeFill") +
                            index("startTime", "times", "3", "%c0", "i64") +
                            index("period", "times", "3", "%c1", "i64") +
                            index("timeTolerance", "times", "3", "%c2", "i64"),
                        "time");
  const std::string crossArguments =
      eventArgument(0, "Arrays.voltage", "voltage", voltageFirst) + ", " +
      eventArgument(1, "1", "1", {}, "integer") + ", " +
      eventArgument(2, "Arrays.timeTolerance", "time", timeTolerance) + ", " +
      eventArgument(3, "Arrays.voltageTolerance", "voltage", voltageTolerance) + ", " +
      eventArgument(4, "1", "1", {}, "integer");
  const std::string aboveArguments =
      eventArgument(0, "Arrays.voltage", "voltage", voltageFirst) + ", " +
      eventArgument(1, "Arrays.timeTolerance", "time", timeTolerance) + ", " +
      eventArgument(2, "Arrays.voltageTolerance", "voltage", voltageTolerance) + ", " +
      eventArgument(3, "1", "1", {}, "integer");
  const std::string timerArguments =
      eventArgument(0, "Arrays.startTime", "time", startTime) + ", " +
      eventArgument(1, "Arrays.period", "time", period) + ", " +
      eventArgument(2, "Arrays.timeTolerance", "time", timeTolerance) + ", " +
      eventArgument(3, "1", "1", {}, "integer");
  auto eventOr = [](const std::string &name, const std::string &left, const std::string &right) {
    return "%" + name + " = \"nodal.analog_event_or\"(%" + left + ", %" + right +
           ") <{event_id = \"Arrays." + name +
           "\", owner = \"Arrays\", contract = \"increment37\", "
           "name = \"\", metadata = {}}> : (!nodal.analog_event, !nodal.analog_event) -> "
           "!nodal.analog_event\n";
  };
  const std::string liveEvents =
      storageEvent("cross", "cross", crossArguments, crossReferences) +
      storageEvent("timer", "timer", timerArguments, timerReferences) +
      eventOr("either", "cross", "timer") + on("onEither", "either") +
      storageEvent("unusedAbove", "above", aboveArguments, crossReferences) +
      storageEvent("crossAgain", "cross", crossArguments, crossReferences) +
      on("onAgain", "crossAgain") +
      storageEvent("unusedOrAbove", "above", aboveArguments, crossReferences) +
      storageEvent("unusedOrTimer", "timer", timerArguments, timerReferences) +
      eventOr("unusedEither", "unusedOrAbove", "unusedOrTimer");
  for (auto kind : {nodal::BackendKind::VerilogA, nodal::BackendKind::VerilogAMS}) {
    const std::string source =
        profileSource(wrap(eventRoots + liveEvents, "", false, false, true), kind);
    std::string target;
    if (!deterministicTarget(source, target, kind))
      return fail("live selected monitor failed deterministic emission or target reparse");
    const auto combined = statementContaining(target, "@(cross(");
    const auto timerLoads = arrayAccesses(combined, "event_Arrays_times");
    const auto voltageLoads = arrayAccesses(combined);
    const auto unused = statementContaining(target, "@(above(");
    if (llvm::StringRef(target).count("@(") != 4 || llvm::StringRef(target).count("real ") != 2 ||
        combined.find(" or timer(") == std::string::npos || voltageLoads.size() != 2 ||
        voltageLoads[0] == voltageLoads[1] || timerLoads.size() != 4 ||
        timerLoads[1] == timerLoads[2] || timerLoads[2] == timerLoads[3] ||
        arrayAccesses(unused).size() != 2 ||
        !before(target, "event_Arrays_cross_reference_0_map = ", combined) ||
        !before(target, "event_Arrays_timer_reference_2_map = ", combined) ||
        !before(target, "event_Arrays_unusedAbove_reference_0_map = ", unused) ||
        !before(target, "event_Arrays_unusedOrTimer_reference_2_map = ",
                "@(above(event_Arrays_values[event_Arrays_unusedOrAbove_reference_0_map]") ||
        target.find("real event_Arrays_cross") != std::string::npos)
      return fail("event storage was captured, aliased or lost ordered/unused monitor evaluation");
    artifact(kind == nodal::BackendKind::VerilogAMS ? "live-storage-events-ams"
                                                    : "live-storage-events",
             source, target, kind == nodal::BackendKind::VerilogAMS ? ".vams" : ".va");
  }

  // Native-selected reference checks and target-size checks remain separate:
  // reject before publishing any bytes, even when the caller already has output.
  const std::string validControl =
      wrap(persistentControlRoot + liveControls, "", false, false, true);
  std::vector<std::string> forbiddenControls;
  for (const char *replacement : {"reference_form = \"snapshot\"", "reference_form = \"\""}) {
    std::string source = validControl;
    const std::string original = "reference_form = \"storage\"";
    source.replace(source.find(original), original.size(), replacement);
    forbiddenControls.push_back(source);
  }
  forbiddenControls.push_back(
      wrap(declaration("values", "4") + controlAliases +
               ifGroup("uninitialized",
                       ifArm("uninitializedArm", "real_gt(Arrays.first,0.0)", {{"first"}})),
           "", false, false, true));
  // Correct the dimension on the intervening assignment so relocation, rather
  // than a malformed assignment type, is the rejected property.
  std::string interveningWrite =
      withRealDimension(assignment("interveningWrite", "voltage", "", "2.0 V", false), "voltage");
  const std::string assignmentKind = "value_kind = \"real\", value_dimension = \"1\"";
  interveningWrite.replace(interveningWrite.find(assignmentKind), assignmentKind.size(),
                           "value_kind = \"real\", value_dimension = \"voltage\"");
  forbiddenControls.push_back(
      wrap(eventRoots + storageEvent("moved", "cross", crossArguments, crossReferences) +
               interveningWrite + on("movedOn", "moved"),
           "", false, false, true));
  for (const auto &source : forbiddenControls) {
    mlir::ScopedDiagnosticHandler handler(&context,
                                          [](mlir::Diagnostic &) { return mlir::success(); });
    auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (module && mlir::succeeded(mlir::verify(*module)))
      return fail("forbidden live reference passed native verification");
    std::string rejected = "sentinel";
    if (emit(source, rejected) || rejected != "sentinel")
      return fail("forbidden live reference published partial target output");
  }
  const std::string wideControlSource =
      wrap(fill("fill", "Arrays.small") + declaration("values", "Arrays.small", "fill") +
               index("wideLeaf", "values", "Arrays.small", "%c0", "i64") +
               ifGroup("wide", ifArm("wideArm", "real_gt(Arrays.wideLeaf,0.0)", {{"wideLeaf"}})),
           wideExpression, false, false, true);
  auto validWideControl = mlir::parseSourceString<mlir::ModuleOp>(wideControlSource, &context);
  if (!validWideControl || mlir::failed(mlir::verify(*validWideControl)))
    return fail("selected signed32 target fixture was not independently native-valid");
  std::string rejectedWideControl = "sentinel";
  if (emit(wideControlSource, rejectedWideControl) || rejectedWideControl != "sentinel")
    return fail("selected signed32 intermediate overflow published target output");
  const std::string lastLaneSource =
      wrap(fill("fill", "2147483647") + declaration("values", "2147483647", "fill") +
               index("lastLane", "values", "2147483647", "%c2147483646", "i64") +
               ifGroup("lastLaneControl",
                       ifArm("lastLaneArm", "real_gt(Arrays.lastLane,0.0)", {{"lastLane"}})),
           literal(2147483646), false, false, true);
  std::string lastLaneTarget;
  if (!deterministicTarget(lastLaneSource, lastLaneTarget) || lastLaneTarget.size() > 4096 ||
      lastLaneTarget.find("event_Arrays_lastLaneArm_reference_0_map = 2147483646;") ==
          std::string::npos ||
      llvm::StringRef(lastLaneTarget).count("real ") != 1)
    return fail("last signed32 selected lane was rejected or expanded by array cardinality");
  llvm::outs() << "writable array backend checks passed\n";
  return 0;
}
