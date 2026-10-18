#include "circt/Dialect/HW/HWDialect.h"
#include "mlir/IR/Diagnostics.h"
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
  // Module-level lifetime does not establish generated per-occurrence lifetime.
  // Both event-containing and event-free generated roots fail transactionally.
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
    if (emit(source, rejected) || rejected != "sentinel" ||
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
  llvm::outs() << "writable array backend checks passed\n";
  return 0;
}
