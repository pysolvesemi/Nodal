#include "nodal/Transforms/AnalogSemanticLint.h"

#include "mlir/IR/BuiltinAttributes.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "mlir/Pass/PassManager.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"
#include "nodal/Transforms/AnalogCanonicalization.h"

#include "llvm/Support/raw_ostream.h"

#include <initializer_list>
#include <string>
#include <vector>

namespace {
std::string print(mlir::ModuleOp module) {
  std::string result;
  llvm::raw_string_ostream output(result);
  module.print(output);
  return result;
}
std::string model(llvm::StringRef body, llvm::StringRef attributes = {},
                  llvm::StringRef declarations = {}) {
  return "module " + (attributes.empty() ? "" : "attributes {" + attributes.str() + "}") +
         " { \"nodal.module\"() <{sym_name = \"T\", metadata = {semantic_path = \"T\"}}> ({\n"
         "\"nodal.parameter\"() <{sym_name = \"P\", type = f64, default_value = 2.0 : f64, "
         "variability = \"symbolic\", metadata = {semantic_path = \"T.P\"}}> : () -> ()\n"
         "\"nodal.parameter\"() <{sym_name = \"Z\", type = f64, default_value = 0.0 : f64, "
         "variability = \"symbolic\", metadata = {semantic_path = \"T.Z\"}}> : () -> ()\n"
         "\"nodal.parameter\"() <{sym_name = \"E\", type = i64, default_value = 0 : i64, "
         "variability = \"symbolic\", metadata = {semantic_path = \"T.E\"}}> : () -> ()\n"
         "\"nodal.parameter\"() <{sym_name = \"F\", type = f64, default_value = 2.0 : f64, "
         "variability = \"fixed\", metadata = {semantic_path = \"T.F\"}}> : () -> ()\n" +
         declarations.str() + "\"nodal.analog\"() <{metadata = {}}> ({\n" +
         (body.empty() ? "^bb0:\n" : body.str()) + "}) : () -> ()\n}) : () -> () }\n";
}
std::string procedure(llvm::StringRef body) {
  return "\"nodal.analog_procedure\"() <{owner = \"T\", metadata = {}}> ({\n" + body.str() +
         "}) : () -> ()\n";
}
std::string reference(llvm::StringRef name, llvm::StringRef parameter = "P") {
  return "%" + name.str() + " = \"nodal.parameter_ref\"() <{parameter = @" + parameter.str() +
         ", metadata = {semantic_path = \"T." + name.str() + "\"}}> : () -> f64\n";
}
std::string literal(llvm::StringRef name, llvm::StringRef value = "2.0") {
  return "%" + name.str() + " = \"nodal.real_literal\"() <{value = " + value.str() +
         " : f64, metadata = {}}> : () -> f64\n";
}
std::string divide(llvm::StringRef name, llvm::StringRef denominator) {
  return "%" + name.str() + " = \"nodal.analog_div\"(%n, %" + denominator.str() +
         ") <{metadata = {semantic_path = \"T." + name.str() +
         "\", source_file = \"lint.scala\", source_line = 12 : i64, source_column = 7 : i64}}> "
         ": (f64, f64) -> f64 loc(\"lint.scala\":12:7)\n";
}
std::string function(llvm::StringRef name, llvm::StringRef id, llvm::StringRef arguments = "%p") {
  bool binary = id == "min" || id == "max";
  return "%" + name.str() + " = \"nodal.analog_function\"(" + arguments.str() +
         ") <{function_id = \"" + id.str() +
         "\", registry_version = \"1\", metadata = {semantic_path = \"T." + name.str() +
         "\"}}> : (f64" + (binary ? ", f64" : "") + ") -> f64\n";
}
struct Argument {
  std::string value;
  std::string kind = "real";
  std::string dimension = "1";
  std::string reads = "[]";
};
std::string event(llvm::StringRef name, llvm::StringRef kind,
                  std::initializer_list<Argument> arguments = {}, llvm::StringRef analyses = "[]",
                  llvm::StringRef operand = {}, llvm::StringRef form = {},
                  llvm::StringRef reads = "[]") {
  std::string attrs;
  unsigned slot = 0;
  for (const auto &argument : arguments) {
    if (!attrs.empty())
      attrs += ", ";
    attrs += "{slot = " + std::to_string(slot++) + " : i64, value = \"" + argument.value +
             "\", kind = \"" + argument.kind + "\", dimension = \"" + argument.dimension +
             "\", reads = " + argument.reads + "}";
  }
  const std::string type = form == "live" ? "!nodal.analog_deferred<\"real\", \"1\">"
                                          : "!nodal.variable<\"real\", \"1\">";
  return "%" + name.str() + " = \"nodal.analog_" + kind.str() + "\"(" + operand.str() +
         ") <{event_id = \"T." + name.str() +
         "\", owner = \"T\", contract = \"increment37\", "
         "name = \"\", arguments = [" +
         attrs + "], analyses = " + analyses.str() + ", event_reads = " + reads.str() +
         ", metadata = {semantic_path = \"T." + name.str() + "\"}" +
         (form.empty() ? "" : ", reference_form = \"" + form.str() + "\"") + "}> : (" +
         (operand.empty() ? "" : type) + ") -> !nodal.analog_event\n";
}
std::string on(llvm::StringRef name) {
  return "\"nodal.analog_on\"(%" + name.str() + ") <{statement_id = \"T.on" + name.str() +
         "\", owner = \"T\", metadata = {}}> ({\n"
         "\"nodal.analog_scope\"() <{owner = \"T\", scope_id = \"T.scope" +
         name.str() +
         "\", metadata = {}}> ({^bb0:}) : () -> ()\n"
         "}) : (!nodal.analog_event) -> ()\n";
}
std::string sourcePath(mlir::Operation *operation) {
  if (auto metadata = operation->getAttrOfType<mlir::DictionaryAttr>("metadata"))
    if (auto path = metadata.getAs<mlir::StringAttr>("semantic_path"))
      return path.getValue().str();
  return operation->getName().getStringRef().str();
}
std::string equation(llvm::StringRef kind = "equation") {
  return "{kind = \"" + kind.str() +
         "\", identity = \"T.eq\", authored_left = \"arbitrary opaque source\", authored_right "
         "= \"analog_div(this is not parsed,0)\", dimension = \"voltage\", analyses = [\"" +
         (kind == "initial_equation" ? "initialization" : "transient") +
         "\"], continuity = \"unspecified\", guard = \"opaque_guard\", owner = \"T\", "
         "source_file = \"lint.scala\", source_line = 10 : i64, source_column = 4 : i64, "
         "residual_convention = \"lhs-minus-rhs-equals-zero\", causally_oriented = false, "
         "divided = false}";
}
std::string contribution() {
  return "{kind = \"contribution\", identity = \"T.c\", target_identity = \"T.branch\", "
         "target_kind = \"flow\", target_dimension = \"current\", target_orientation = "
         "\"p-to-n\", value = \"opaque\", analyses = [\"transient\"], continuity = "
         "\"unspecified\", guard = \"\", owner = \"T\", source_file = \"lint.scala\", "
         "source_line = 12 : i64, source_column = 2 : i64}";
}
} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  std::string diagnostic;
  unsigned warnings = 0;
  mlir::ScopedDiagnosticHandler handler(&context, [&](mlir::Diagnostic &message) {
    warnings += message.getSeverity() == mlir::DiagnosticSeverity::Warning;
    llvm::raw_string_ostream output(diagnostic);
    message.print(output);
    return mlir::success();
  });
  unsigned cases = 0, failures = 0;
  auto check = [&](llvm::StringRef label, const std::string &source,
                   const std::vector<std::string> &expected) {
    ++cases;
    diagnostic.clear();
    warnings = 0;
    auto module = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
    if (!module || mlir::failed(nodal::verifyParameterModel(*module)) ||
        mlir::failed(nodal::verifyAnalogNumericModel(*module)) ||
        mlir::failed(nodal::verifyAnalogSemanticInventory(*module))) {
      llvm::errs() << label << ": fixture is not well-formed\n" << diagnostic << '\n';
      ++failures;
      return;
    }
    const auto before = print(*module);
    const auto attributes = (*module)->getAttrDictionary();
    auto issues = nodal::collectAnalogSemanticLint(*module);
    auto repeated = nodal::collectAnalogSemanticLint(*module);
    std::vector<std::string> found;
    if (mlir::succeeded(issues))
      for (const auto &issue : *issues)
        found.push_back(sourcePath(issue.operation) + ":" +
                        issue.code.substr(issue.code.size() - 3));
    bool stable =
        mlir::succeeded(issues) && mlir::succeeded(repeated) && issues->size() == repeated->size();
    if (stable)
      for (size_t i = 0; i < issues->size(); ++i)
        stable &= (*issues)[i].operation == (*repeated)[i].operation &&
                  (*issues)[i].code == (*repeated)[i].code &&
                  (*issues)[i].message == (*repeated)[i].message;
    mlir::PassManager manager(&context);
    manager.addPass(nodal::createAnalogSemanticLintPass());
    bool passed = mlir::succeeded(manager.run(*module));
    if (!stable || found != expected || !passed || warnings != expected.size() ||
        print(*module) != before || (*module)->getAttrDictionary() != attributes) {
      llvm::errs() << label << ": wrong issues, warning severity, mutation, or unstable result\n";
      for (const auto &entry : found)
        llvm::errs() << "  " << entry << '\n';
      llvm::errs() << diagnostic << '\n';
      ++failures;
    }
  };

  check("symbolic defaults are not divisor proofs",
        model(literal("n") + reference("p") + reference("z", "Z") + divide("first", "p") +
              divide("second", "z") + divide("third", "p")),
        {"T.first:001", "T.second:001", "T.third:001"});
  check("known nonzero literals and fixed parameters are quiet",
        model(literal("n") + literal("d", "-2.0") + reference("f", "F") + divide("a", "d") +
              divide("b", "f")),
        {});
  check("fixed wrapper retains the symbolic dependency's divisor and discontinuity risk",
        model(literal("n") + reference("f", "F") + divide("dependent", "f") +
                  function("dependentFloor", "floor", "%f"),
              {}, R"mlir(
%default = "nodal.const_parameter_ref"() <{parameter = @P, metadata = {}}> : () -> f64
"nodal.parameter_value"(%default) <{parameter = @F, metadata = {}}> : (f64) -> ()
)mlir"),
        {"T.dependent:001", "T.dependentFloor:003"});
  const auto range = [](bool inclusive) {
    return std::string(R"mlir(
%low = "nodal.const_literal"() <{metadata = {}, spelling = "0.0", value = 0.0 : f64}> : () -> f64
%high = "nodal.const_literal"() <{metadata = {}, spelling = "4.0", value = 4.0 : f64}> : () -> f64
"nodal.parameter_constraint"(%low, %high) <{constraint_kind = "range", lower_inclusive = )mlir") +
           (inclusive ? "true" : "false") +
           R"mlir(, upper_inclusive = true, metadata = {}, parameter = @P}> : (f64, f64) -> ()
)mlir";
  };
  check("exclusive zero envelope discharges SSA and event-source divisor risk",
        model(literal("n") + reference("p") + divide("bounded", "p") +
                  procedure(event("boundedSource", "cross", {{"analog_div(1.0,T.P)"}})),
              {}, range(false)),
        {});
  check("inclusive zero envelope still permits divisor risk",
        model(literal("n") + reference("p") + divide("bounded", "p") +
                  procedure(event("boundedSource", "cross", {{"analog_div(1.0,T.P)"}})),
              {}, range(true)),
        {"T.bounded:001", "T.boundedSource:001"});
  check("integer nonzero literal is not mislabeled as unknown real", model(literal("n") + R"mlir(
%i = "nodal.analog_integer_literal"() <{value = 2 : i64, metadata = {}}>
: () -> !nodal.quantity<"integer", "1">
%ratio = "nodal.analog_div"(%n, %i) <{metadata = {semantic_path = "T.integerRatio"}}>
: (f64, !nodal.quantity<"integer", "1">) -> f64
)mlir"),
        {});
  check("exact registry discontinuities only",
        model(reference("p") + literal("c") + function("floor", "floor") +
              function("ceil", "ceil") + function("constantFloor", "floor", "%c") +
              function("constantCeil", "ceil", "%c") + function("abs", "abs") +
              function("min", "min", "%p, %c") + function("max", "max", "%p, %c")),
        {"T.floor:003", "T.ceil:003"});
  check(
      "constant cross versus above startup semantics",
      model(procedure(
          event("positiveCross", "cross", {{"1.0"}}) + event("zeroCross", "cross", {{"0.0"}}) +
          event("negativeCross", "cross", {{"-1.0"}}) + event("positiveAbove", "above", {{"1.0"}}) +
          event("zeroAbove", "above", {{"0.0"}}) + event("negativeAbove", "above", {{"-1.0"}}) +
          event("dynamicCross", "cross", {{"T.P"}}))),
      {"T.positiveCross:002", "T.zeroCross:002", "T.negativeCross:002", "T.zeroAbove:002",
       "T.negativeAbove:002"});
  check(
      "zero enable and category dedup",
      model(procedure(
          event("disabledCross", "cross",
                {{"1.0"}, {"0", "integer"}, {"0.0 s", "real", "time"}, {"0.0"}, {"0", "integer"}}) +
          event("disabledAbove", "above",
                {{"T.P"}, {"0.0 s", "real", "time"}, {"0.0"}, {"0", "integer"}}) +
          event("symbolicEnable", "above",
                {{"T.P"}, {"0.0 s", "real", "time"}, {"0.0"}, {"T.E", "integer"}}))),
      {"T.disabledCross:002", "T.disabledAbove:002"});
  check("nonpositive timer period remains lawful one-shot",
        model(procedure(
            event("zeroPeriod", "timer", {{"1.0 s", "real", "time"}, {"0.0 s", "real", "time"}}) +
            event("negativePeriod", "timer",
                  {{"1.0 s", "real", "time"}, {"-1.0 s", "real", "time"}}) +
            event("disabledTimer", "timer",
                  {{"1.0 s", "real", "time"},
                   {"-1.0 s", "real", "time"},
                   {"0.0 s", "real", "time"},
                   {"0", "integer"}}))),
        {"T.disabledTimer:002"});
  check("portable lifecycle registry and exact category dedup",
        model(procedure(event("portable", "initial_step", {},
                              "[\"ic\", \"dc\", \"static\", \"tran\", \"ac\", \"noise\"]") +
                        event("custom", "final_step", {}, "[\"vendorSweep\", \"vendorRF\"]") +
                        event("wrongSpelling", "initial_step", {}, "[\"transient\"]"))),
        {"T.custom:004", "T.wrongSpelling:004"});
  check("event-source risks share the checked expression parser",
        model(procedure(
            event("expression", "cross", {{"analog_div(analog_function_v1_floor(T.P),T.P)"}}))),
        {"T.expression:001", "T.expression:003"});
  for (llvm::StringRef kind : {"equation", "initial_equation"})
    check("equations explicitly report unavailable structural analysis",
          model({}, "nodal.bridge.analog_semantics = [" + equation(kind) + "]"),
          {"builtin.module:005"});
  check("contributions do not masquerade as first-class equations",
        model({}, "nodal.bridge.analog_semantics = [" + contribution() + "]"), {});
  check("empty inventory is quiet", model({}, "nodal.bridge.analog_semantics = []"), {});

  const std::string live = R"mlir(
%x = "nodal.analog_variable"() <{identity = "T.x", declaration_order = 0 : i64,
initialized = true, initializer_kind = "real", initializer_dimension = "1",
initializer_value = "0.0", initializer_reads = [], owner = "T", metadata = {}}>
: () -> !nodal.variable<"real", "1">
%f = "nodal.analog_live_fill"(%x) <{identity = "T.f", owner = "T",
value = "analog_div(analog_function_v1_floor(T.x),T.x)", metadata = {semantic_path = "T.f"}}>
: (!nodal.variable<"real", "1">) -> !nodal.analog_deferred<"real", "1">
%g = "nodal.analog_live_fill"(%f) <{identity = "T.g", owner = "T",
value = "analog_add(T.f,T.f)", metadata = {semantic_path = "T.g"}}>
: (!nodal.analog_deferred<"real", "1">) -> !nodal.analog_deferred<"real", "1">
)mlir";
  check("shared live DAG stays unexpanded and mutable initializer is not constant evidence",
        model(procedure(live +
                        event("liveCross", "cross", {{"T.g", "real", "1", "[\"T.x\"]"}}, "[]", "%g",
                              "live", "[\"T.x\"]") +
                        on("liveCross"))),
        {"T.f:001", "T.f:003"});

  std::string many = literal("n") + reference("p");
  std::vector<std::string> expectedMany;
  for (unsigned i = 0; i < 128; ++i) {
    const std::string name = "risk" + std::to_string(i);
    many += divide(name, "p");
    expectedMany.push_back("T." + name + ":001");
  }
  check("shared denominator preserves authored issue order at representative size", model(many),
        expectedMany);
  if (diagnostic.find("[semantic-path=T.risk127]") == std::string::npos ||
      diagnostic.find("[source-range=lint.scala:12:7-12:7]") == std::string::npos) {
    llvm::errs() << "mapped warning lost source identity or exact source range\n";
    ++failures;
  }

  // A registered standalone pass must independently reject invalid input even
  // when the PassManager's automatic verification is deliberately disabled.
  ++cases;
  auto invalid = mlir::parseSourceString<mlir::ModuleOp>(
      model(literal("n") + literal("zero", "-0.0") + divide("bad", "zero")),
      mlir::ParserConfig(&context, false));
  diagnostic.clear();
  warnings = 0;
  mlir::PassManager manager(&context);
  manager.enableVerifier(false);
  manager.addPass(nodal::createAnalogSemanticLintPass());
  if (!invalid || mlir::succeeded(manager.run(*invalid)) || warnings ||
      diagnostic.find("NODAL-ANALOG-DIVIDE-001") == std::string::npos ||
      diagnostic.find("NODAL-ANALOG-LINT") != std::string::npos) {
    llvm::errs() << "invalid zero divisor was downgraded to lint\n" << diagnostic << '\n';
    ++failures;
  }
  ++cases;
  auto malformedInventory = mlir::parseSourceString<mlir::ModuleOp>(
      model({}, "nodal.bridge.analog_semantics = [{kind = \"equation\"}]"),
      mlir::ParserConfig(&context, false));
  diagnostic.clear();
  warnings = 0;
  mlir::PassManager inventoryManager(&context);
  inventoryManager.enableVerifier(false);
  inventoryManager.addPass(nodal::createAnalogSemanticLintPass());
  if (!malformedInventory || mlir::succeeded(inventoryManager.run(*malformedInventory)) ||
      warnings || diagnostic.find("NODAL-ANALOG-045-001") == std::string::npos ||
      diagnostic.find("NODAL-ANALOG-LINT") != std::string::npos) {
    llvm::errs() << "malformed inventory was mistaken for structural lint evidence\n"
                 << diagnostic << '\n';
    ++failures;
  }
  ++cases;
  std::string invalidEnvelope =
      model(literal("n") + reference("p") + divide("badRange", "p"), {}, range(false));
  const std::string defaultValue = "default_value = 2.0 : f64";
  invalidEnvelope.replace(invalidEnvelope.find(defaultValue), defaultValue.size(),
                          "default_value = 0.0 : f64");
  auto malformedParameter =
      mlir::parseSourceString<mlir::ModuleOp>(invalidEnvelope, mlir::ParserConfig(&context, false));
  diagnostic.clear();
  warnings = 0;
  mlir::PassManager parameterManager(&context);
  parameterManager.enableVerifier(false);
  parameterManager.addPass(nodal::createAnalogSemanticLintPass());
  if (!malformedParameter || mlir::succeeded(parameterManager.run(*malformedParameter)) ||
      warnings || diagnostic.find("NODAL-PARAMETER-CONSTRAINT-001") == std::string::npos ||
      diagnostic.find("NODAL-ANALOG-LINT") != std::string::npos) {
    llvm::errs() << "invalid parameter envelope was silently accepted or downgraded to lint\n"
                 << diagnostic << '\n';
    ++failures;
  }
  llvm::outs() << "AnalogSemanticLintTest: " << cases << " cases, " << failures << " failures\n";
  return failures ? 1 : 0;
}
