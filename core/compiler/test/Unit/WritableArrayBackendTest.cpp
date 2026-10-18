#include "circt/Dialect/HW/HWDialect.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Backend/AnalogVerticalSlice.h"
#include "nodal/Backend/Backend.h"
#include "nodal/Backend/ShapedValueBackend.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"

#include "llvm/Support/raw_ostream.h"

#include <fstream>
#include <string>

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
std::string wrap(const std::string &body, const std::string &moduleBody = "",
                 bool generated = false, bool persistent = false) {
  std::string procedure = R"mlir("nodal.analog_procedure"() <{owner = "Arrays", metadata = {}}> ({
)mlir";
  const std::string event =
      R"mlir(%initial = "nodal.analog_initial_step"() <{event_id = "Arrays.initial", owner = "Arrays", contract = "increment37", name = "", arguments = [], analyses = [], event_reads = [], metadata = {}}> : () -> !nodal.analog_event
"nodal.analog_on"(%initial) <{statement_id = "Arrays.on", owner = "Arrays", metadata = {}}> ({
)mlir";
  procedure += persistent ? body + event + "^bb0:\n" : event + body;
  procedure += "}) : (!nodal.analog_event) -> ()\n}) : () -> ()\n";
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
int fail(llvm::StringRef message) {
  llvm::errs() << message << "\n";
  return 1;
}
} // namespace

int main(int argc, char **argv) {
  auto artifact = [&](const char *name, const std::string &source, const std::string &target) {
    if (argc < 2)
      return;
    std::ofstream(std::string(argv[1]) + "/" + name + ".mlir") << source;
    std::ofstream(std::string(argv[1]) + "/" + name + ".va") << target;
  };
  mlir::DialectRegistry registry;
  registry.insert<circt::hw::HWDialect, nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  context.loadAllAvailableDialects();
  auto emit = [&](const std::string &source, std::string &output) {
    auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (!module || mlir::failed(mlir::verify(*module)))
      return false;
    llvm::raw_string_ostream stream(output);
    return mlir::succeeded(nodal::emitBackend(*module, nodal::BackendKind::VerilogA, stream));
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
  for (const auto &source :
       {wrap(fill("fill", "4") + declaration("values", "4", "fill"), "", false, true),
        wrap(fill("fill", "2147483648") + declaration("values", "2147483648", "fill")),
        wrap(fill("fill", "Arrays.small") + declaration("values", "Arrays.small", "fill"),
             wideExpression),
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
  llvm::outs() << "writable array backend checks passed\n";
  return 0;
}
