#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/ConservativeConnectivity.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/PotentialFlowAccess.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/Support/raw_ostream.h"

#include <string>
#include <vector>

namespace {

const std::string terminal = "!nodal.terminal<\"electrical\">";

std::string shaped(const std::string &dimensions) {
  return "!nodal.shaped<\"" + dimensions + "\", " + terminal + ">";
}

std::string wrap(const std::string &body) {
  return R"mlir(module {
"nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", dimension = "voltage", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
"nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", dimension = "current", metadata = {}, sym_name = "Current", units = "A"}> : () -> ()
"nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical"}> : () -> ()
"nodal.module"() <{metadata = {semantic_path = "Fixture"}, sym_name = "Fixture"}> ({
)mlir" + body +
         "}) : () -> ()\n}\n";
}

std::string array(const std::string &dimensions = "4", const std::string &name = "nodes") {
  return "%" + name + " = \"nodal.node_array\"() <{metadata = {semantic_path = \"Fixture." + name +
         "\", storage = \"structural\"}, name = \"" + name + "\", source_path = \"Fixture." + name +
         "\"}> : () -> " + shaped(dimensions) + "\n";
}

std::string literal(const std::string &name, int64_t value, bool legacy = false) {
  const std::string type = legacy ? "index" : "i64";
  return "%" + name + " = \"nodal." + (legacy ? "constant" : "const_literal") +
         "\"() <{metadata = {}, " +
         (legacy ? "" : "spelling = \"" + std::to_string(value) + "\", ") +
         "value = " + std::to_string(value) + " : " + type + "}> : () -> " + type + "\n";
}

std::string parameter(const std::string &name, int64_t low, int64_t high) {
  return "\"nodal.parameter\"() <{classification = \"structural\", default_value = " +
         std::to_string(low) + " : i64, metadata = {semantic_path = \"Fixture." + name +
         "\"}, parameter_kind = \"integer\", sym_name = \"" + name +
         "\", type = i64, variability = \"symbolic\"}> : () -> ()\n" + literal(name + "_low", low) +
         literal(name + "_high", high) + "\"nodal.parameter_constraint\"(%" + name + "_low, %" +
         name +
         "_high) <{constraint_kind = \"range\", lower_inclusive = true, metadata = {}, parameter = "
         "@" +
         name + ", upper_inclusive = true}> : (i64, i64) -> ()\n" +
         "\"nodal.parameter_envelope\"() <{effects = [\"shape\"], metadata = {}, parameter = @" +
         name + ", policy = \"static_generate\"}> : () -> ()\n%" + name +
         " = \"nodal.const_parameter_ref\"() <{metadata = {}, parameter = @" + name +
         "}> : () -> i64\n";
}

struct Index {
  std::string value;
  std::string identity;
  std::string type = "i64";
};

std::string selection(const std::string &name, const std::vector<Index> &indices,
                      const std::string &dimensions = "4", const std::string &base = "nodes") {
  std::string operands = "%" + base, types = shaped(dimensions), path = "Fixture." + base + ".at(";
  for (const Index &index : indices) {
    operands += ", %" + index.value;
    types += ", " + index.type;
    if (path.back() != '(')
      path += ',';
    path += index.identity;
  }
  path += ')';
  return "%" + name + " = \"nodal.shape_index\"(" + operands +
         ") <{metadata = {input_path = \"Fixture." + base +
         "\", selection_kind = \"node_array\", "
         "semantic_path = \"" +
         path + "\", source_uses = [\"Fixture.use_" + name +
         "\"], storage = \"structural\"}}> : (" + types + ") -> " + terminal + "\n";
}

std::string projectionMetadata(const std::string &name, const std::string &input) {
  return "metadata = {semantic_path = \"Fixture." + name + "\", input_path = \"Fixture." + input +
         "\", storage = \"structural\", access = \"read_only\"}";
}

std::string prefix(const std::string &name, const std::string &input, const std::string &dimensions,
                   const std::vector<Index> &indices, const std::string &resultDimensions) {
  std::string operands = "%" + input, types = shaped(dimensions);
  for (const Index &index : indices) {
    operands += ", %" + index.value;
    types += ", " + index.type;
  }
  return "%" + name + " = \"nodal.shape_index\"(" + operands + ") <{" +
         projectionMetadata(name, input) + "}> : (" + types + ") -> " + shaped(resultDimensions) +
         "\n";
}

std::string view(const std::string &name, const std::string &input, const std::string &dimensions,
                 const std::string &resultDimensions) {
  return "%" + name + " = \"nodal.shape_view\"(%" + input + ") <{dimensions = \"" +
         resultDimensions + "\", origin = \"Fixture." + input +
         "\", materialization = \"view\", observability = \"source_mapped\", " +
         projectionMetadata(name, input) + "}> : (" + shaped(dimensions) + ") -> " +
         shaped(resultDimensions) + "\n";
}

std::string slice(const std::string &name, const std::string &input, const std::string &dimensions,
                  const std::string &lower, const std::string &upper, const std::string &length,
                  const std::string &resultDimensions, int64_t axis = 0) {
  return "%" + name + " = \"nodal.shape_slice\"(%" + input + ", %" + lower + ", %" + upper + ", %" +
         length + ") <{axis = " + std::to_string(axis) + " : i64, dimensions = \"" +
         resultDimensions + "\", origin = \"Fixture." + input +
         "\", observability = \"source_mapped\", " + projectionMetadata(name, input) + "}> : (" +
         shaped(dimensions) + ", i64, i64, i64) -> " + shaped(resultDimensions) + "\n";
}

unsigned scalarSelections(mlir::ModuleOp module) {
  unsigned result = 0;
  module.walk([&](nodal::ShapeIndexOp operation) {
    result += llvm::isa<nodal::TerminalType>(operation->getResult(0).getType());
  });
  return result;
}

std::string ground() {
  return "%ground = \"nodal.terminal\"() <{direction = \"inout\", flow_orientation = "
         "\"into_component\", "
         "metadata = {}, name = \"ground\", source_path = \"Fixture.ground\"}> : () -> " +
         terminal + "\n";
}

std::string access(const std::string &name, const std::string &positive,
                   const std::string &negative = "ground", bool flow = false) {
  return "%" + name + " = \"nodal.terminal_access\"(%" + positive + ", %" + negative +
         ") <{function = \"" + (flow ? "I" : "V") + "\", kind = \"" +
         (flow ? "flow" : "potential") + "\", metadata = {}, source_path = \"Fixture." + name +
         "\"}> : (" + terminal + ", " + terminal + ") -> !nodal.quantity<\"real\", \"" +
         (flow ? "current" : "voltage") + "\">\n";
}

std::string analog(const std::string &body) {
  return "\"nodal.analog\"() <{metadata = {}}> ({\n" + body + "}) : () -> ()\n";
}

std::string print(mlir::ModuleOp module) {
  std::string text;
  llvm::raw_string_ostream stream(text);
  module.print(stream);
  stream.flush();
  return text;
}

unsigned count(mlir::ModuleOp module, llvm::StringRef name) {
  unsigned result = 0;
  module.walk(
      [&](mlir::Operation *operation) { result += operation->getName().getStringRef() == name; });
  return result;
}

std::string replace(std::string text, const std::string &from, const std::string &to) {
  auto position = text.find(from);
  if (position != std::string::npos)
    text.replace(position, from.size(), to);
  return text;
}

} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  unsigned checks = 0, failures = 0;
  auto check = [&](bool condition, llvm::StringRef message) {
    ++checks;
    if (!condition) {
      ++failures;
      llvm::errs() << "NodeArrayTest: " << message << '\n';
    }
  };
  auto parse = [&](const std::string &body) {
    return mlir::parseSourceString<mlir::ModuleOp>(wrap(body), &context);
  };
  auto reject = [&](const std::string &body, llvm::StringRef code) {
    std::string diagnostics;
    mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &diagnostic) {
      llvm::raw_string_ostream stream(diagnostics);
      diagnostic.print(stream);
      return mlir::success();
    });
    auto module = parse(body);
    bool expected = !module && diagnostics.find(code.str()) != std::string::npos;
    check(expected, code);
    if (!expected)
      llvm::errs() << "Unexpected rejection result: " << diagnostics << '\n';
  };
  auto normalize = [&](const std::string &body, unsigned endpoints, llvm::StringRef label) {
    auto module = parse(body);
    check(bool(module), label);
    if (!module)
      return;
    check(mlir::succeeded(nodal::normalizeNodeArraySelections(*module)), label);
    check(scalarSelections(*module) == endpoints && count(*module, "nodal.node") == 0, label);
    check(mlir::succeeded(mlir::verify(*module)), "normalized structural view must verify");
    const std::string first = print(*module);
    check(mlir::succeeded(nodal::normalizeNodeArraySelections(*module)) && print(*module) == first,
          "structural view normalization must be idempotent");
  };
  const std::string duplicate = array() + literal("zero", 0, true) + literal("zero_i64", 0) +
                                selection("first", {{"zero", "literal:0", "index"}}) +
                                selection("second", {{"zero_i64", "literal:0"}});
  {
    auto module =
        parse(duplicate + ground() + analog(access("v0", "first") + access("v1", "second")));
    check(bool(module), "equivalent literal carriers must parse");
    if (module) {
      check(mlir::succeeded(nodal::normalizePotentialFlowAccess(*module)), "probe normalization");
      check(count(*module, "nodal.shape_index") == 1 && count(*module, "nodal.probe") == 1 &&
                count(*module, "nodal.node") == 0,
            "equivalent selectors must share one endpoint/probe without synthetic nodes");
      check(print(*module).find("[\"Fixture.use_first\", \"Fixture.use_second\"]") !=
                std::string::npos,
            "normalization must merge sorted authored source uses");
      std::string first = print(*module);
      check(mlir::succeeded(nodal::normalizePotentialFlowAccess(*module)) &&
                print(*module) == first,
            "normalization must be idempotent");
      check(mlir::succeeded(mlir::verify(*module)), "normalized endpoint/probe model must verify");
    }
  }
  {
    auto module = parse(array() + literal("a", 0, true) + literal("b", 0, true) +
                        selection("first", {{"a", "literal:0", "index"}}) +
                        selection("second", {{"b", "literal:0", "index"}}));
    check(bool(module) && mlir::succeeded(nodal::normalizeNodeArraySelections(*module)) &&
              count(*module, "nodal.constant") == 1,
          "duplicate selection cleanup must remove only its orphaned legacy index literal");
  }
  {
    const std::string partial = "\"nodal.component_contract\"() <{connectivity_ownership = "
                                "\"extensible\", kind = \"partial\", "
                                "metadata = {}, source_path = \"Fixture\"}> : () -> ()\n";
    const std::string selfConnect =
        "\"nodal.connect\"(%first, %second) <{connection_id = \"self\", metadata = {}, "
        "source_path = \"Fixture.connect\"}> : (" +
        terminal + ", " + terminal + ") -> ()\n";
    auto module = parse(partial + duplicate + selfConnect);
    check(bool(module), "partial array connectivity must parse");
    if (module) {
      check(mlir::succeeded(nodal::materializeConservativeConnectivity(*module)),
            "partial connectivity normalization");
      check(count(*module, "nodal.shape_index") == 1 && count(*module, "nodal.connection_set") == 1,
            "duplicate selections must not create duplicate physical topology");
      module->walk([&](nodal::ConnectOp connect) {
        check(connect->getOperand(0) == connect->getOperand(1),
              "self-connect must inherit actual scalar identity");
      });
      check(print(*module).find("complete = false") != std::string::npos &&
                print(*module).find("node-array-use:Fixture.use_second") != std::string::npos,
            "partial topology must preserve incomplete coverage and source provenance");
      check(mlir::succeeded(mlir::verify(*module)), "normalized partial topology must verify");
    }
    reject(replace(replace(partial, "extensible", "local"), "\"partial\"", "\"concrete\"") +
               duplicate,
           "NODAL-SHAPE-043-005");
  }
  {
    auto module = parse(duplicate + analog(access("same", "first", "second")));
    check(bool(module) && mlir::succeeded(nodal::normalizePotentialFlowAccess(*module)),
          "equal-selector potential must retain scalar self-access semantics");
    std::string diagnostics;
    mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &diagnostic) {
      llvm::raw_string_ostream stream(diagnostics);
      diagnostic.print(stream);
      return mlir::success();
    });
    auto flow = parse(duplicate + analog(access("same", "first", "second", true)));
    check(!flow && diagnostics.find("NODAL-ACCESS-FORM-001") != std::string::npos,
          "canonical identity must expose the existing oriented-flow self-access rejection during "
          "verification");
  }
  {
    std::string symbolic = parameter("P", 0, 1) + parameter("Q", 2, 3) + array() +
                           selection("p", {{"P", "parameter:Fixture.P"}}) +
                           selection("q", {{"Q", "parameter:Fixture.Q"}});
    check(bool(parse(symbolic)), "disjoint symbolic selectors must remain distinct");
    reject(parameter("P", 0, 2) + parameter("Q", 1, 3) + array() +
               selection("p", {{"P", "parameter:Fixture.P"}}) +
               selection("q", {{"Q", "parameter:Fixture.Q"}}),
           "NODAL-SHAPE-043-006");
    std::string sameParameter = parameter("P", 0, 2) + array() +
                                "%P_again = \"nodal.const_parameter_ref\"() <{metadata = {}, "
                                "parameter = @P}> : () -> i64\n" +
                                selection("p", {{"P", "parameter:Fixture.P"}}) +
                                selection("again", {{"P_again", "parameter:Fixture.P"}});
    auto module = parse(sameParameter);
    check(bool(module) && mlir::succeeded(nodal::normalizeNodeArraySelections(*module)) &&
              count(*module, "nodal.shape_index") == 1,
          "separate parameter-ref SSA values must resolve to one declaration identity");
  }
  {
    const std::string compound =
        parameter("P", 1, 2) + literal("one", 1) +
        "%index = \"nodal.const_expr\"(%P, %one) <{metadata = {semantic_path = \"Fixture.index\"}, "
        "operator_name = \"sub\"}> : (i64, i64) -> i64\n";
    check(bool(parse(compound + array() + selection("p", {{"index", "expression:Fixture.index"}}))),
          "compound static selector must use its captured root");
    const std::string another =
        "%other = \"nodal.const_expr\"(%P, %one) <{metadata = {semantic_path = \"Fixture.other\"}, "
        "operator_name = \"sub\"}> : (i64, i64) -> i64\n";
    reject(compound + another + array() + selection("p", {{"index", "expression:Fixture.index"}}) +
               selection("other_element", {{"other", "expression:Fixture.other"}}),
           "NODAL-SHAPE-043-006");
    reject(compound + array() + selection("p", {{"index", "expression:Fixture.forged"}}),
           "NODAL-SHAPE-043-006");
    check(bool(parse(parameter("COUNT", 2, 4) + array("2,COUNT") + literal("one", 1) +
                     selection("last", {{"one", "literal:1"}, {"one", "literal:1"}}, "2,COUNT"))),
          "symbolic multidimensional array must retain all-legal axis proof");
  }
  {
    auto distinctArrays = parse(array() + array("4", "other_nodes") + literal("zero", 0) +
                                selection("left", {{"zero", "literal:0"}}) +
                                selection("right", {{"zero", "literal:0"}}, "4", "other_nodes"));
    check(bool(distinctArrays) &&
              mlir::succeeded(nodal::normalizeNodeArraySelections(*distinctArrays)) &&
              count(*distinctArrays, "nodal.shape_index") == 2,
          "identical indices on different actual array values must remain distinct");
    reject(parameter("P", 0, 0) + array() + literal("zero", 0) +
               selection("fixed", {{"zero", "literal:0"}}) +
               selection("symbolic", {{"P", "parameter:Fixture.P"}}),
           "NODAL-SHAPE-043-006");
    reject(parameter("P", 0, 4) + array() +
               selection("unsafe_default", {{"P", "parameter:Fixture.P"}}),
           "NODAL-SHAPE-043-002");
    reject(replace(parameter("P", 0, 1), "effects = [\"shape\"]", "effects = [\"topology\"]") +
               array() + selection("missing_shape", {{"P", "parameter:Fixture.P"}}),
           "NODAL-SHAPE-043-002");
    reject(array("2,2") + literal("zero", 0) + selection("prefix", {{"zero", "literal:0"}}, "2,2"),
           "NODAL-SHAPE-043-002");
    reject(duplicate + array("4", "other_nodes") +
               replace(selection("other", {{"zero", "literal:0", "index"}}, "4", "other_nodes"),
                       "Fixture.use_other", "Fixture.use_first"),
           "NODAL-SHAPE-043-006");
  }
  {
    const std::string rows =
        array("2,3") + literal("one", 1) + literal("two", 2) +
        selection("direct", {{"one", "literal:1"}, {"two", "literal:2"}}, "2,3") +
        prefix("row", "nodes", "2,3", {{"one", "literal:1"}}, "3") +
        selection("via_row", {{"two", "literal:2"}}, "3", "row");
    normalize(rows, 1, "direct and row selections must share one physical endpoint");
    reject(replace(rows, "Fixture.use_via_row", "Fixture.nodes.at(literal:1,literal:2)"),
           "NODAL-SHAPE-043-006");
    normalize(array("2,3") + literal("one", 1) + literal("two", 2) +
                  prefix("row", "nodes", "2,3", {{"one", "literal:1"}}, "3") +
                  selection("via_row", {{"two", "literal:2"}}, "3", "row") +
                  selection("direct", {{"one", "literal:1"}, {"two", "literal:2"}}, "2,3"),
              1, "a view-first representative must preserve canonical root identity");
    auto module =
        parse(rows + ground() + analog(access("direct_v", "direct") + access("row_v", "via_row")));
    check(bool(module) && mlir::succeeded(nodal::normalizePotentialFlowAccess(*module)) &&
              scalarSelections(*module) == 1 && count(*module, "nodal.probe") == 1,
          "different authored view paths must share their physical endpoint and probe");
    if (module) {
      check(print(*module).find("[\"Fixture.use_direct\", \"Fixture.use_via_row\"]") !=
                std::string::npos,
            "view normalization must preserve every sorted authored scalar source use");
      check(print(*module).find("semantic_path = \"Fixture.row\"") != std::string::npos &&
                count(*module, "nodal.shape_index") == 2,
            "merged scalar endpoints must not erase their intermediate view provenance");
      check(mlir::succeeded(mlir::verify(*module)), "merged row probe must verify");
    }
    auto potential = parse(rows + analog(access("self", "direct", "via_row")));
    check(bool(potential) && mlir::succeeded(nodal::normalizePotentialFlowAccess(*potential)),
          "view-equivalent potential endpoints must retain scalar self-access semantics");
    std::string diagnostics;
    mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &diagnostic) {
      llvm::raw_string_ostream stream(diagnostics);
      diagnostic.print(stream);
      return mlir::success();
    });
    auto flow = parse(rows + analog(access("self", "direct", "via_row", true)));
    check(!flow && diagnostics.find("NODAL-ACCESS-FORM-001") != std::string::npos,
          "view-equivalent flow endpoints must retain the oriented-flow self-access rejection "
          "during verification");
  }
  {
    const std::string planes =
        array("2,3,4") + literal("one", 1) + literal("two", 2) + literal("three", 3) +
        selection("direct", {{"one", "literal:1"}, {"two", "literal:2"}, {"three", "literal:3"}},
                  "2,3,4") +
        prefix("plane", "nodes", "2,3,4", {{"one", "literal:1"}}, "3,4") +
        selection("via_plane", {{"two", "literal:2"}, {"three", "literal:3"}}, "3,4", "plane") +
        prefix("row", "plane", "3,4", {{"two", "literal:2"}}, "4") +
        selection("via_row", {{"three", "literal:3"}}, "4", "row");
    normalize(planes, 1, "direct, plane and composed row selections must share root coordinates");
  }
  {
    const std::string reshaped =
        array("2,3") + literal("zero", 0) + literal("one", 1) + literal("two", 2) +
        selection("direct", {{"zero", "literal:0"}, {"two", "literal:2"}}, "2,3") +
        selection("different", {{"one", "literal:1"}, {"zero", "literal:0"}}, "2,3") +
        view("reshape", "nodes", "2,3", "3,2") +
        selection("via_reshape", {{"one", "literal:1"}, {"zero", "literal:0"}}, "3,2", "reshape") +
        view("flat", "reshape", "3,2", "6") +
        selection("via_flat", {{"two", "literal:2"}}, "6", "flat");
    normalize(reshaped, 2, "reshape and flatten preserve logical ordinal, not authored tuples");
    auto module = parse(reshaped);
    check(bool(module) && mlir::succeeded(nodal::normalizeNodeArraySelections(*module)) &&
              print(*module).find("[\"Fixture.use_direct\", \"Fixture.use_via_flat\", "
                                  "\"Fixture.use_via_reshape\"]") != std::string::npos,
          "reshape must merge root ordinal two while leaving root ordinal three distinct");
  }
  {
    const std::string clipped =
        array("3,4") + literal("zero", 0) + literal("one", 1) + literal("two", 2) +
        literal("three", 3) + literal("four", 4) +
        selection("direct", {{"two", "literal:2"}, {"one", "literal:1"}}, "3,4") +
        slice("clipped", "nodes", "3,4", "one", "three", "two", "3,2", 1) +
        view("flat", "clipped", "3,2", "6") +
        selection("via_flat", {{"four", "literal:4"}}, "6", "flat") +
        view("reshape", "flat", "6", "2,3") +
        selection("via_reshape", {{"one", "literal:1"}, {"one", "literal:1"}}, "2,3", "reshape") +
        prefix("row", "clipped", "3,2", {{"two", "literal:2"}}, "2") +
        selection("via_row", {{"zero", "literal:0"}}, "2", "row");
    normalize(clipped, 1,
              "noncontiguous slice compositions must map logical ordinals back to root lanes");
    const std::string composed =
        array("4,5") + literal("one", 1) + literal("two", 2) + literal("three", 3) +
        literal("four", 4) + literal("seven", 7) +
        selection("direct", {{"three", "literal:3"}, {"two", "literal:2"}}, "4,5") +
        slice("rows", "nodes", "4,5", "one", "four", "three", "3,5") +
        slice("columns", "rows", "3,5", "one", "four", "three", "3,3", 1) +
        view("flat", "columns", "3,3", "9") +
        selection("via_slices", {{"seven", "literal:7"}}, "9", "flat");
    normalize(composed, 1, "nonzero slices on separate axes must compose before flattening");
  }
  {
    const std::string parameterRows =
        parameter("P", 0, 1) + parameter("COLS", 3, 5) + array("2,COLS") + literal("one", 1) +
        "%P_again = \"nodal.const_parameter_ref\"() <{metadata = {}, parameter = @P}> : () -> "
        "i64\n" +
        selection("direct", {{"P", "parameter:Fixture.P"}, {"one", "literal:1"}}, "2,COLS") +
        prefix("row", "nodes", "2,COLS", {{"P_again", "parameter:Fixture.P"}}, "COLS") +
        selection("via_row", {{"one", "literal:1"}}, "COLS", "row");
    normalize(parameterRows, 1, "prefix maps must use parameter declaration identity");
    const std::string expressionRows =
        parameter("P", 1, 2) + literal("one", 1) +
        "%index = \"nodal.const_expr\"(%P, %one) <{metadata = {semantic_path = \"Fixture.index\"}, "
        "operator_name = \"sub\"}> : (i64, i64) -> i64\n" +
        array("2,3") +
        selection("direct", {{"index", "expression:Fixture.index"}, {"one", "literal:1"}}, "2,3") +
        prefix("row", "nodes", "2,3", {{"index", "expression:Fixture.index"}}, "3") +
        selection("via_row", {{"one", "literal:1"}}, "3", "row");
    normalize(expressionRows, 1, "prefix maps must preserve the exact captured expression root");
    const std::string full =
        parameter("COLS", 3, 5) + parameter("P", 0, 2) + array("COLS") + literal("zero", 0) +
        selection("direct", {{"P", "parameter:Fixture.P"}}, "COLS") +
        slice("full", "nodes", "COLS", "zero", "COLS", "COLS", "COLS") +
        view("identity", "full", "COLS", "COLS") +
        selection("via_full", {{"P", "parameter:Fixture.P"}}, "COLS", "identity");
    normalize(full, 1, "symbolic full-range slice and identity view must preserve input identity");
    const std::string symbolicSlice =
        parameter("LOW", 1, 2) + array("6") + literal("zero", 0) + literal("four", 4) +
        "%length = \"nodal.const_expr\"(%four, %LOW) <{metadata = {semantic_path = "
        "\"Fixture.length\"}, operator_name = \"sub\"}> : (i64, i64) -> i64\n" +
        selection("direct", {{"LOW", "parameter:Fixture.LOW"}}, "6") +
        slice("clipped", "nodes", "6", "LOW", "four", "length", "Fixture.length") +
        selection("via_slice", {{"zero", "literal:0"}}, "Fixture.length", "clipped");
    normalize(symbolicSlice, 1, "nonzero symbolic slice maps must cancel an exact zero offset");
    const std::string symbolicRoundTrip =
        parameter("COLS", 3, 5) + literal("one", 1) + literal("two", 2) +
        "%total = \"nodal.const_expr\"(%two, %COLS) <{metadata = {semantic_path = "
        "\"Fixture.total\"}, operator_name = \"mul\"}> : (i64, i64) -> i64\n" +
        array("2,COLS") +
        selection("direct", {{"one", "literal:1"}, {"two", "literal:2"}}, "2,COLS") +
        view("flat", "nodes", "2,COLS", "Fixture.total") +
        view("restored", "flat", "Fixture.total", "2,COLS") +
        selection("via_round_trip", {{"one", "literal:1"}, {"two", "literal:2"}}, "2,COLS",
                  "restored");
    normalize(symbolicRoundTrip, 1,
              "symbolic flatten and inverse reshape must cancel without parameter defaults");
    const std::string symbolicColumn =
        parameter("COLS", 2, 8) + array("2,COLS") + literal("zero", 0) + literal("one", 1) +
        literal("two", 2) + slice("column", "nodes", "2,COLS", "one", "two", "one", "2,1", 1) +
        view("flat_column", "column", "2,1", "2") +
        selection("via_column", {{"one", "literal:1"}}, "2", "flat_column");
    normalize(symbolicColumn +
                  selection("direct", {{"one", "literal:1"}, {"one", "literal:1"}}, "2,COLS"),
              1, "literal reshape coordinates must recover the exact symbolic root map");
    normalize(symbolicColumn +
                  selection("other", {{"zero", "literal:0"}, {"one", "literal:1"}}, "2,COLS"),
              2, "fixed intermediate reshape must preserve disjoint symbolic root coordinates");
    reject(parameter("P", 1, 1) + symbolicColumn +
               selection("opaque", {{"P", "parameter:Fixture.P"}}, "2", "flat_column"),
           "NODAL-SHAPE-043-006");
  }
  {
    const std::string separated =
        parameter("P", 0, 2) + parameter("Q", 0, 2) + array("2,3") + literal("zero", 0) +
        literal("one", 1) + prefix("row_zero", "nodes", "2,3", {{"zero", "literal:0"}}, "3") +
        prefix("row_one", "nodes", "2,3", {{"one", "literal:1"}}, "3") +
        selection("left", {{"P", "parameter:Fixture.P"}}, "3", "row_zero") +
        selection("right", {{"Q", "parameter:Fixture.Q"}}, "3", "row_one");
    normalize(separated, 2, "root-axis disjointness must survive prefix maps");
    normalize(parameter("P", 0, 1) + parameter("Q", 2, 3) + array("2,3") +
                  view("flat", "nodes", "2,3", "6") +
                  selection("left", {{"P", "parameter:Fixture.P"}}, "6", "flat") +
                  selection("right", {{"Q", "parameter:Fixture.Q"}}, "6", "flat"),
              2, "disjoint symbolic ordinal bounds must survive flattening");
    reject(parameter("P", 0, 2) + parameter("Q", 0, 2) + array("2,3") + literal("one", 1) +
               prefix("row", "nodes", "2,3", {{"one", "literal:1"}}, "3") +
               selection("left", {{"P", "parameter:Fixture.P"}}, "3", "row") +
               selection("right", {{"one", "literal:1"}, {"Q", "parameter:Fixture.Q"}}, "2,3"),
           "NODAL-SHAPE-043-006");
    reject(parameter("P", 0, 0) + array("2,3") + literal("zero", 0) + literal("one", 1) +
               prefix("row", "nodes", "2,3", {{"one", "literal:1"}}, "3") +
               selection("fixed", {{"zero", "literal:0"}}, "3", "row") +
               selection("symbolic", {{"one", "literal:1"}, {"P", "parameter:Fixture.P"}}, "2,3"),
           "NODAL-SHAPE-043-006");
    reject(parameter("P", 0, 0) + array("1") + literal("zero", 0) +
               selection("fixed", {{"zero", "literal:0"}}, "1") +
               selection("symbolic", {{"P", "parameter:Fixture.P"}}, "1"),
           "NODAL-SHAPE-043-006");
    const std::string expression =
        parameter("P", 1, 2) + literal("one", 1) +
        "%index = \"nodal.const_expr\"(%P, %one) <{metadata = {semantic_path = \"Fixture.index\"}, "
        "operator_name = \"sub\"}> : (i64, i64) -> i64\n"
        "%other = \"nodal.const_expr\"(%P, %one) <{metadata = {semantic_path = \"Fixture.other\"}, "
        "operator_name = \"sub\"}> : (i64, i64) -> i64\n" +
        array("2,3") + prefix("row", "nodes", "2,3", {{"index", "expression:Fixture.index"}}, "3") +
        selection("left", {{"one", "literal:1"}}, "3", "row") +
        selection("right", {{"other", "expression:Fixture.other"}, {"one", "literal:1"}}, "2,3");
    reject(expression, "NODAL-SHAPE-043-006");
    normalize(
        array() + array("4", "other_nodes") + literal("one", 1) +
            view("left_view", "nodes", "4", "2,2") + view("right_view", "other_nodes", "4", "2,2") +
            selection("left", {{"one", "literal:1"}, {"one", "literal:1"}}, "2,2", "left_view") +
            selection("right", {{"one", "literal:1"}, {"one", "literal:1"}}, "2,2", "right_view"),
        2, "equal view maps rooted in different owned arrays must remain independent");
  }
  {
    std::string deep = array("2,3") + literal("one", 1) + literal("two", 2) +
                       selection("direct", {{"one", "literal:1"}, {"two", "literal:2"}}, "2,3");
    std::string input = "nodes", dimensions = "2,3";
    for (unsigned index = 0; index < 32; ++index) {
      const std::string name = "view_" + std::to_string(index);
      const std::string result = index % 2 ? "2,3" : "3,2";
      deep += view(name, input, dimensions, result);
      input = name;
      dimensions = result;
    }
    deep += selection("via_deep", {{"one", "literal:1"}, {"two", "literal:2"}}, dimensions, input);
    normalize(deep, 1, "deep exact reshape composition must preserve one physical endpoint");
  }
  {
    const std::string constants = literal("negative", -1) + literal("zero", 0) + literal("one", 1) +
                                  literal("two", 2) + literal("three", 3) + literal("four", 4) +
                                  literal("five", 5);
    const std::string root = array("2,4") + constants;
    reject(root + prefix("bad_rank", "nodes", "2,4", {{"one", "literal:1"}}, "1,4"),
           "NODAL-SHAPE-043-002");
    reject(root + prefix("bad_extent", "nodes", "2,4", {{"one", "literal:1"}}, "3"),
           "NODAL-SHAPE-043-002");
    reject(root + prefix("outside", "nodes", "2,4", {{"two", "literal:2"}}, "4"),
           "NODAL-SHAPE-043-002");
    reject(root + view("wrong_count", "nodes", "2,4", "7"), "NODAL-SHAPE-043-003");
    reject(root + replace(view("wrong_element", "nodes", "2,4", "8"), "-> " + shaped("8"),
                          "-> !nodal.shaped<\"8\", i64>"),
           "NODAL-SHAPE-043-003");
    reject(root + slice("bad_axis", "nodes", "2,4", "one", "three", "two", "2,2", 2),
           "NODAL-SHAPE-043-004");
    reject(root + slice("bad_rank", "nodes", "2,4", "one", "three", "two", "2", 1),
           "NODAL-SHAPE-043-004");
    reject(root + slice("bad_other_axis", "nodes", "2,4", "one", "three", "two", "3,2", 1),
           "NODAL-SHAPE-043-004");
    reject(root + slice("below_zero", "nodes", "2,4", "negative", "one", "two", "2,2", 1),
           "NODAL-SHAPE-043-004");
    reject(root + slice("outside", "nodes", "2,4", "two", "five", "three", "2,3", 1),
           "NODAL-SHAPE-043-004");
    reject(root + slice("wrong_length", "nodes", "2,4", "one", "three", "one", "2,1", 1),
           "NODAL-SHAPE-043-004");
    reject(root + slice("reversed", "nodes", "2,4", "three", "one", "two", "2,2", 1),
           "NODAL-SHAPE-043-004");
    const std::string row = prefix("row", "nodes", "2,4", {{"one", "literal:1"}}, "4");
    const std::string flat = view("flat", "nodes", "2,4", "8");
    const std::string clipped = slice("clipped", "nodes", "2,4", "one", "three", "two", "2,2", 1);
    for (const std::string &projection : {row, flat, clipped}) {
      reject(root + replace(projection, "access = \"read_only\"", "access = \"read_write\""),
             "NODAL-SHAPE-043");
      reject(root + replace(projection, "storage = \"structural\"", "storage = \"procedural\""),
             "NODAL-SHAPE-043");
      reject(root + replace(projection, "input_path = \"Fixture.nodes\"",
                            "input_path = \"Fixture.forged\""),
             "NODAL-SHAPE-043");
      reject(root + replace(projection, "semantic_path = \"Fixture.", "semantic_path = \"Other."),
             "NODAL-SHAPE-043");
      reject(root + analog(projection), "NODAL-ANALOG-REGION-002");
    }
    reject(root + flat +
               replace(view("again", "flat", "8", "2,4"), "input_path = \"Fixture.flat\"",
                       "input_path = \"Fixture.nodes\""),
           "NODAL-SHAPE-043");
    reject(root + flat +
               replace(view("again", "flat", "8", "2,4"), "origin = \"Fixture.flat\"",
                       "origin = \"Fixture.nodes\""),
           "NODAL-SHAPE-043");
    reject(root + flat +
               replace(view("again", "flat", "8", "2,4"), "semantic_path = \"Fixture.again\"",
                       "semantic_path = \"Fixture.flat\""),
           "NODAL-SHAPE-043");
    reject(root + row +
               replace(selection("forged_path", {{"one", "literal:1"}}, "4", "row"),
                       "Fixture.row.at(literal:1)", "Fixture.nodes.at(literal:1)"),
           "NODAL-SHAPE-043-006");
    reject(root + row +
               replace(selection("forged_input", {{"one", "literal:1"}}, "4", "row"),
                       "input_path = \"Fixture.row\"", "input_path = \"Fixture.nodes\""),
           "NODAL-SHAPE-043-006");
    reject(root + row +
               replace(selection("wrong_element", {{"one", "literal:1"}}, "4", "row"),
                       "-> " + terminal, "-> i64"),
           "NODAL-SHAPE-043-002");
    reject(root + row + selection("outside_view", {{"four", "literal:4"}}, "4", "row"),
           "NODAL-SHAPE-043-002");
    const std::string forgedRoot =
        "%fake = \"nodal.shape_flatten\"(%nodes) <{layout = \"row_major\", metadata = "
        "{semantic_path = \"Fixture.fake\"}}> : (" +
        shaped("2,4") + ") -> " + shaped("8") + "\n";
    reject(root + forgedRoot + selection("forged_selection", {{"one", "literal:1"}}, "8", "fake"),
           "NODAL-SHAPE-043");
    reject(root + forgedRoot + view("fake_view", "fake", "8", "2,4"), "NODAL-SHAPE-043");
    reject(root + forgedRoot + slice("fake_slice", "fake", "8", "one", "three", "two", "2"),
           "NODAL-SHAPE-043");
  }
  {
    auto module = parse(array() + literal("one", 1) + view("first", "nodes", "4", "4") +
                        view("second", "first", "4", "4") +
                        selection("selected", {{"one", "literal:1"}}, "4", "second"));
    check(bool(module), "acyclic fixture must verify before constructing an actual SSA cycle");
    if (module) {
      std::vector<mlir::Operation *> views;
      mlir::Operation *selected = nullptr;
      module->walk([&](nodal::ShapeViewOp operation) { views.push_back(operation); });
      module->walk([&](nodal::ShapeIndexOp operation) { selected = operation; });
      check(views.size() == 2 && selected, "cycle fixture must contain the intended projections");
      if (views.size() == 2 && selected) {
        // Mutate a verified graph, so parser scope or SSA syntax checks cannot
        // substitute for the shared map's independent cycle rejection.
        mlir::Value original = views.front()->getOperand(0);
        views.front()->setOperand(0, views.back()->getResult(0));
        check(mlir::failed(nodal::resolveStaticShapeProjection(selected->getResult(0), selected)),
              "element-neutral mapping must reject actual cyclic projection inputs");
        std::string diagnostics;
        mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &diagnostic) {
          llvm::raw_string_ostream stream(diagnostics);
          diagnostic.print(stream);
          return mlir::success();
        });
        check(mlir::failed(nodal::resolveConservativeSelection(selected->getResult(0), selected)) &&
                  diagnostics.find("NODAL-SHAPE-043-006: conservative view requires an acyclic "
                                   "verified owned shape map") != std::string::npos,
              "conservative capability must independently reject the actual SSA cycle");
        diagnostics.clear();
        check(mlir::failed(llvm::cast<nodal::ShapeIndexOp>(selected).verify()) &&
                  diagnostics.find("NODAL-SHAPE-043-006") != std::string::npos,
              "scalar selection verifier must reject the actual SSA cycle");
        views.front()->setOperand(0, original);
        check(mlir::succeeded(mlir::verify(*module)),
              "restoring the acyclic input must restore the verified source graph");
      }
    }
  }
  {
    auto generatedSelection = [&](const std::string &name) {
      const std::string scope = "Fixture." + name;
      std::string selected = selection(
          name, {{"i", "induction:" + scope + ".index"}, {"zero", "literal:0", "index"}}, "4,2");
      selected = replace(selected, ".at(induction:" + scope + ".index,literal:0)",
                         ".at(induction:" + scope + ".index,literal:0).in(" + scope + ")");
      selected = replace(selected, "metadata = {",
                         "generated_owner = \"" + scope + "\", generated_induction = \"" + scope +
                             ".index\", metadata = {");
      return "\"nodal.generate\"() <{induction = \"i\", lower = 0 : i64, upper = 4 : i64, "
             "step = 1 : i64, region_id = \"" +
             scope + "\", induction_path = \"" + scope +
             ".index\", metadata = {maximum_trip_count = 4 : i64}}> ({\n^bb0(%i: i64):\n" +
             "%zero = \"nodal.constant\"() <{value = 0 : index, metadata = {}}> : () -> index\n" +
             selected + "}) : () -> ()\n";
    };
    auto module = parse(array("4,2") + generatedSelection("left") + generatedSelection("right"));
    check(bool(module), "sibling induction fixture must verify before actual SSA mutation");
    if (module) {
      std::vector<mlir::Operation *> selections;
      module->walk([&](nodal::ShapeIndexOp operation) { selections.push_back(operation); });
      check(selections.size() == 2, "sibling fixture must retain two scoped selections");
      if (selections.size() == 2) {
        using Alias = nodal::ConservativeAlias;
        mlir::Value left = selections[0]->getResult(0), right = selections[1]->getResult(0);
        check(nodal::compareConservativeEndpointOccurrences(left, selections[0], right,
                                                            selections[1]) == Alias::Unknown,
              "independent live induction families require an overlap proof");
        check(nodal::compareConservativeEndpointOccurrences(left, selections[0], left,
                                                            selections[1]) == Alias::Invalid,
              "cross-occurrence query must reject a real sibling SSA escape");
        auto foreign =
            parse(array() + literal("zero", 0) + selection("foreign", {{"zero", "literal:0"}}));
        mlir::Operation *foreignSelection = nullptr;
        if (foreign)
          foreign->walk([&](nodal::ShapeIndexOp operation) { foreignSelection = operation; });
        check(foreignSelection && nodal::compareConservativeEndpointOccurrences(
                                      left, selections[0], foreignSelection->getResult(0),
                                      foreignSelection) == Alias::Invalid,
              "cross-occurrence query must reject different actual module definitions");
        check(nodal::compareConservativeEndpointOccurrences(left, nullptr, right, selections[1]) ==
                  Alias::Invalid,
              "cross-occurrence query must reject an absent actual use");
        mlir::Value original = selections[1]->getOperand(1);
        check(mlir::succeeded(nodal::resolveShapeIndexProof(original, selections[1])),
              "actual local induction must resolve before mutation");
        // Both blocks parsed and verified first: textual SSA scope rejection
        // cannot stand in for the independent use-aware semantic proof.
        selections[1]->setOperand(1, selections[0]->getOperand(1));
        std::string diagnostics;
        mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &diagnostic) {
          llvm::raw_string_ostream stream(diagnostics);
          diagnostic.print(stream);
          return mlir::success();
        });
        check(mlir::failed(nodal::verifyShapeIndexMapping(selections[1])) &&
                  diagnostics.find("NODAL-SHAPE-043-002") != std::string::npos,
              "shape proof must reject an actual sibling induction with identical bounds");
        check(mlir::failed(
                  nodal::resolveStaticShapeProjection(selections[1]->getResult(0), selections[1])),
              "neutral map must reject actual escaped induction before deriving physical keys");
        selections[1]->setOperand(1, original);
        check(mlir::succeeded(mlir::verify(*module)),
              "restoring actual local induction must restore the verified graph");
        mlir::Value literal = selections[1]->getOperand(2);
        selections[1]->setOperand(2, selections[0]->getOperand(2));
        check(mlir::failed(
                  nodal::resolveShapeIndexProof(selections[1]->getOperand(2), selections[1])),
              "equal-valued sibling literal must not bypass lexical ownership");
        selections[1]->setOperand(2, literal);
        literal.getDefiningOp()->moveAfter(selections[1]);
        check(mlir::failed(nodal::resolveShapeIndexProof(literal, selections[1])),
              "actual generated literal must dominate its index use");
        literal.getDefiningOp()->moveBefore(selections[1]);
        check(mlir::succeeded(mlir::verify(*module)),
              "restoring literal dominance must restore the verified graph");
      }
    }
  }
  {
    auto localScope = [&](const std::string &name) {
      const std::string scope = "Fixture." + name;
      auto own = [&](std::string operation) {
        return replace(operation, "metadata = {",
                       "generated_owner = \"" + scope + "\", generated_induction = \"" + scope +
                           ".index\", metadata = {");
      };
      std::string selected = selection("selected", {{"zero", "literal:0"}}, "2", "local");
      selected = replace(selected, "Fixture.local.at(literal:0)",
                         "Fixture.local.at(literal:0).in(" + scope + ")");
      return "\"nodal.generate\"() <{induction = \"i\", lower = 0 : i64, upper = 2 : i64, "
             "step = 1 : i64, region_id = \"" +
             scope + "\", induction_path = \"" + scope +
             ".index\", metadata = {maximum_trip_count = 2 : i64}}> ({\n" +
             own(array("2", "local")) + own(selected) + "}) : () -> ()\n";
    };
    auto module = parse(literal("zero", 0) + localScope("left") + localScope("right"));
    check(bool(module), "constant-index scopes must admit independent generated local arrays");
    if (module) {
      llvm::SmallVector<nodal::NodeArrayOp, 2> roots;
      llvm::SmallVector<nodal::ShapeIndexOp, 2> selections;
      module->walk([&](nodal::NodeArrayOp operation) { roots.push_back(operation); });
      module->walk([&](nodal::ShapeIndexOp operation) { selections.push_back(operation); });
      check(roots.size() == 2 && selections.size() == 2,
            "generated local roots must remain separate actual declarations");
      if (roots.size() == 2 && selections.size() == 2) {
        auto left = nodal::resolveConservativeShape(roots[0]->getResult(0), selections[0]);
        auto right = nodal::resolveConservativeShape(roots[1]->getResult(0), selections[1]);
        check(mlir::succeeded(left) && mlir::succeeded(right) &&
                  left->projection.root != right->projection.root,
              "same-spelled sibling roots must not collapse physical identity");
        auto scope = nodal::resolveConservativeStructuralScope(roots[0]->getParentOp());
        check(mlir::succeeded(scope) && scope->nodeArrays.size() == 1 &&
                  scope->nodeArrays.front() == roots[0].getOperation(),
              "structural scope must expose its actual verified array declaration");
        std::string diagnostics;
        mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &diagnostic) {
          llvm::raw_string_ostream stream(diagnostics);
          diagnostic.print(stream);
          return mlir::success();
        });
        check(mlir::failed(nodal::resolveConservativeShape(roots[0]->getResult(0), selections[1])),
              "root resolver must reject actual sibling escape independently of text parsing");
        auto original = selections[1]->getOperand(0);
        selections[1]->setOperand(0, roots[0]->getResult(0));
        check(mlir::failed(
                  nodal::resolveConservativeSelection(selections[1]->getResult(0), selections[1])),
              "selection resolver must reject forged cross-scope root input");
        selections[1]->setOperand(0, original);
        auto owner = roots[0]->getAttr("generated_owner");
        roots[0]->setAttr("generated_owner", mlir::StringAttr::get(&context, "Fixture.right"));
        check(mlir::failed(nodal::resolveConservativeShape(roots[0]->getResult(0), selections[0])),
              "root resolver must independently reject forged generated owner");
        roots[0]->setAttr("generated_owner", owner);
        check(mlir::succeeded(mlir::verify(*module)),
              "restoring actual root and owner must restore valid generated source");
      }
    }
  }
  reject(array("9223372036854775807,2"), "NODAL-SHAPE-043-005");
  reject(replace(array(), "storage = \"structural\"", "storage = \"memory\""),
         "NODAL-SHAPE-043-005");
  reject(replace(array(), "name = \"nodes\"", "name = \"other\""), "NODAL-SHAPE-043-005");
  reject(replace(array(), "!nodal.terminal<\"electrical\">", "f64"), "NODAL-SHAPE-043-005");
  reject(duplicate + literal("end", 4) + selection("outside", {{"end", "literal:4"}}),
         "NODAL-SHAPE-043-002");
  reject(replace(duplicate, "selection_kind = \"node_array\"", "selection_kind = \"fake\""),
         "NODAL-SHAPE-043-006");
  reject(replace(duplicate, "Fixture.use_first", "Other.use_first"), "NODAL-SHAPE-043-006");
  reject(replace(duplicate, "Fixture.nodes.at(literal:0)", "Fixture.nodes.at(literal:1)"),
         "NODAL-SHAPE-043-006");
  llvm::outs() << "NodeArrayTest: " << checks << " checks, " << failures << " failures\n";
  return failures ? 1 : 0;
}
