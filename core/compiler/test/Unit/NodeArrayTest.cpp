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
    check(bool(flow) && mlir::failed(nodal::normalizePotentialFlowAccess(*flow)) &&
              diagnostics.find("NODAL-ACCESS-FORM-001") != std::string::npos,
          "canonical identity must expose the existing oriented-flow self-access rejection");
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
           "NODAL-SHAPE-043-006");
    reject(duplicate + array("4", "other_nodes") +
               replace(selection("other", {{"zero", "literal:0", "index"}}, "4", "other_nodes"),
                       "Fixture.use_other", "Fixture.use_first"),
           "NODAL-SHAPE-043-006");
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
