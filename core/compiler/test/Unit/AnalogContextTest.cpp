#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/AnalogEvents.h"
#include "nodal/Dialect/Nodal/AnalogFunctions.h"
#include "nodal/Dialect/Nodal/AnalogNumeric.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/TimeWaveform.h"

#include "llvm/Support/raw_ostream.h"

#include <string>
#include <utility>
#include <vector>

namespace {
const std::string temperature = "!nodal.quantity<\"real\", \"temperature\">";
const std::string timeType = "!nodal.quantity<\"real\", \"time\">";
const std::string generated =
    "generated_owner = \"T.gen\", generated_induction = \"T.gen.index\", ";

std::string module(const std::string &body) {
  return "module {\n\"nodal.module\"() <{sym_name = \"T\", metadata = {semantic_path = \"T\"}}> "
         "({\n" +
         body + "}) : () -> ()\n}\n";
}
std::string analog(const std::string &body, const std::string &attributes = "") {
  return "\"nodal.analog\"() <{" + attributes + "metadata = {}}> ({\n" + body + "}) : () -> ()\n";
}
std::string query(const std::string &kind = "temperature", const std::string &extra = "",
                  const std::string &type = temperature, const std::string &version = "1",
                  const std::string &name = "query") {
  const bool owned = llvm::StringRef(extra).starts_with("generated_owner");
  std::string attributes =
      owned || extra.empty() ? "" : " {" + llvm::StringRef(extra).rtrim(", ").str() + "}";
  return "%" + name + " = \"nodal.analog_context\"() <{query_kind = \"" + kind +
         "\", registry_version = \"" + version + "\", " + (owned ? extra : "") +
         "metadata = {semantic_path = \"T." + name + "\"}}>" + attributes + " : () -> " + type +
         "\n";
}
std::string generate(const std::string &body) {
  return "\"nodal.generate\"() <{induction = \"i\", lower = 0 : i64, upper = 2 : i64, "
         "step = 1 : i64, region_id = \"T.gen\", induction_path = \"T.gen.index\", "
         "metadata = {maximum_trip_count = 2 : i64}}> ({\n" +
         body + "}) : () -> ()\n";
}
std::string procedure(const std::string &body) {
  return analog("\"nodal.analog_procedure\"() <{owner = \"T\", metadata = {}}> ({\n" + body +
                "}) : () -> ()\n");
}
std::string variable(const std::string &source, const std::string &dimension = "temperature") {
  return "%held = \"nodal.analog_variable\"() <{identity = \"T.held\", owner = \"T\", "
         "declaration_order = 0 : i64, initialized = true, initializer_value = \"" +
         source + "\", initializer_kind = \"real\", initializer_dimension = \"" + dimension +
         "\", initializer_reads = [], metadata = {}}> : () -> !nodal.variable<\"real\", \"" +
         dimension + "\">\n";
}
std::string condition(const std::string &source, bool isStatic) {
  const std::string literal = isStatic ? "true" : "false";
  return "\"nodal.analog_if\"() <{statement_id = \"T.if\", owner = \"T\", metadata = {}}> ({\n"
         "\"nodal.analog_if_arm\"() <{arm_id = \"T.if.arm\", owner = \"T\", is_else = false, "
         "stage = \"" +
         std::string(isStatic ? "static" : "runtime") + "\", condition_value = \"" + source +
         "\", condition_kind = \"boolean\", condition_dimension = \"1\", condition_reads = [], "
         "static_value_present = " +
         literal + ", static_value = " + literal + ", metadata = {}}> ({\n" + variable("1.0", "1") +
         "}) : () -> ()\n}) : () -> ()\n";
}
std::string changed(std::string source, const std::string &from, const std::string &to) {
  size_t at = source.find(from);
  if (at == std::string::npos)
    return {};
  source.replace(at, from.size(), to);
  return source;
}
} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  std::string lastDiagnostic;
  mlir::ScopedDiagnosticHandler diagnostics(&context, [&](mlir::Diagnostic &diagnostic) {
    llvm::raw_string_ostream stream(lastDiagnostic);
    diagnostic.print(stream);
    return mlir::success();
  });
  unsigned cases = 0;
  auto parse = [&](const std::string &source) {
    lastDiagnostic.clear();
    return mlir::parseSourceString<mlir::ModuleOp>(source, &context);
  };
  auto reject = [&](const std::string &label, const std::string &source,
                    llvm::StringRef diagnostic = {}) {
    ++cases;
    auto parsed = parse(source);
    if (parsed || (!diagnostic.empty() && !llvm::StringRef(lastDiagnostic).contains(diagnostic))) {
      llvm::errs() << "AnalogContextTest: expected rejection " << label << ": " << diagnostic
                   << "\n"
                   << lastDiagnostic << '\n';
      return false;
    }
    return true;
  };
  auto accept = [&](const std::string &source) {
    ++cases;
    auto parsed = parse(source);
    if (!parsed || mlir::failed(nodal::verifyAnalogNumericModel(*parsed))) {
      llvm::errs() << "AnalogContextTest: rejected valid source\n" << lastDiagnostic << '\n';
      return false;
    }
    return true;
  };
  const auto *ambient = nodal::lookupAnalogContext("temperature");
  const auto *nominal = nodal::lookupAnalogContext("nominal_temperature");
  if (!ambient || !nominal || ambient->dimension != "temperature" ||
      nominal->dimension != "temperature" || ambient->verilogA != "$temperature" ||
      nominal->verilogA != "($simparam(\"tnom\") + 273.15)" ||
      nodal::lookupAnalogContextTarget(nominal->verilogA) != nominal ||
      nodal::lookupAnalogContext("frequency") ||
      nodal::lookupAnalogContextTarget("$simparam(\"tnom\", 27.0)"))
    return 1;

  auto valid =
      parse(module(analog(query() + query("nominal_temperature", "", temperature, "1", "nominal") +
                          "%sum = \"nodal.analog_add\"(%query, %nominal) <{metadata = {}}> : (" +
                          temperature + ", " + temperature + ") -> " + temperature + "\n")));
  if (!valid || mlir::failed(nodal::foldAnalogNumericConstants(*valid))) {
    llvm::errs() << lastDiagnostic << '\n';
    return 1;
  }
  nodal::AnalogContextOp exemplar;
  bool dynamic = true;
  valid->walk([&](nodal::AnalogContextOp op) { exemplar = op; });
  valid->walk([&](mlir::Operation *op) {
    if (op->getNumResults() != 1)
      return;
    auto constant = nodal::getAnalogConstantRealValue(op->getResult(0));
    dynamic &= mlir::succeeded(constant) && !*constant &&
               !nodal::isAnalogStaticExpression(op->getResult(0)) && !op->hasAttr("nodal.folded");
  });
  if (!dynamic || !exemplar)
    return 1;

  for (const auto &entry : std::vector<std::pair<std::string, std::string>>{
           {"analog_context_v1_temperature()", "temperature"},
           {"analog_context_v1_nominal_temperature()", "temperature"},
           {"analog_abstime()", "time"},
           {"analog_sub(analog_context_v1_temperature(),analog_context_v1_nominal_temperature())",
            "temperature"},
           {"analog_select(true,1.0 s,analog_abstime())", "time"}}) {
    ++cases;
    auto source = nodal::parseAnalogSourceExpression(exemplar, entry.first);
    if (mlir::failed(source) || source->kind != "real" || source->dimension != entry.second ||
        source->constant || !source->reads.empty() ||
        nodal::isStaticAnalogSourceExpression(*source))
      return 1;
  }
  for (llvm::StringRef source :
       {"analog_context_v1_frequency()", "analog_context_v2_temperature()",
        "analog_context_v1_temperature(1.0)", "analog_context_v1_nominal_temperature(27.0)",
        "analog_abstime(0.0)", "$temperature", "$simparam(tnom)",
        "analog_context_v1_temperature( )"}) {
    ++cases;
    if (mlir::succeeded(nodal::parseAnalogSourceExpression(exemplar, source)))
      return 1;
  }

  for (const auto &entry : std::vector<std::pair<std::string, std::string>>{
           {"unknown query", query("corner")},
           {"unknown version", query("temperature", "", temperature, "2")}})
    if (!reject(entry.first, module(analog(entry.second)), "044-001"))
      return 1;
  for (const auto &type :
       {std::string("f64"), std::string("i1"), std::string("!nodal.quantity<\"real\", \"1\">"),
        timeType, std::string("!nodal.quantity<\"integer\", \"temperature\">")})
    if (!reject("wrong result type", module(analog(query("temperature", "", type))), "044-003"))
      return 1;
  for (const auto &claim : {"nodal.folded = true, ", "nodal.simplified = true, ",
                            "nodal.static = true, ", "static_value = 300.0 : f64, "})
    if (!reject("forged context effect", module(analog(query("temperature", claim))), "FOLD-001"))
      return 1;
  for (const auto &claim : {"default_value = 300.0 : f64, ", "target_spelling = \"$temperature\", ",
                            "constant = true, ", "evaluation_phase = \"static\", "})
    if (!reject("forged context semantics", module(analog(query("temperature", claim))), "044-004"))
      return 1;
  if (!reject("operand arity", module(analog(query("temperature", "", temperature, "1", "first") +
                                             changed(changed(query(), "() <{", "(%first) <{"),
                                                     ": () ->", ": (" + temperature + ") ->")))) ||
      !reject("missing result", module(analog(changed(changed(query(), "%query = ", ""),
                                                      "-> " + temperature, "-> ()")))) ||
      !reject("wrong parent", module(query())))
    return 1;
  const std::string neg = "%negative = \"nodal.analog_neg\"(%query) <{metadata = {}}> "
                          "{nodal.folded = true} : (" +
                          temperature + ") -> " + temperature + "\n";
  if (!reject("transitive effect", module(analog(query() + neg)), "FOLD-001"))
    return 1;
  const std::string analysis =
      "%active = \"nodal.analog_analysis\"() <{analysis_kind = \"dc\", "
      "registry_version = \"1\", metadata = {}}> {nodal.folded = true} : () -> i1\n";
  if (!reject("direct analysis fold", module(analog(analysis)), "FOLD-001"))
    return 1;
  const std::string clock =
      "%now = \"nodal.analog_abstime\"() <{operator_contract = \"increment36\", "
      "operator_id = \"T.now\", owner = \"T\", context = \"legacy-analog\", operand_dimensions = "
      "[], "
      "result_dimension = \"time\", input_continuity = \"none\", output_continuity = "
      "\"continuous\", "
      "analyses = [\"ac\", \"dc\", \"initialization\", \"noise\", \"operating-point\", "
      "\"transient\"], "
      "metadata = {}}> : () -> " +
      timeType + "\n";
  if (!accept(module(analog(clock))) ||
      !reject(
          "transitive time fold",
          module(analog(
              clock +
              "%negative = \"nodal.analog_neg\"(%now) <{metadata = {}}> {nodal.folded = true} : (" +
              timeType + ") -> " + timeType + "\n")),
          "FOLD-001"))
    return 1;

  if (!accept(module(generate(analog(query("temperature", generated), generated)))) ||
      !reject("ordinary ownership", module(analog(query("temperature", generated))), "043-004") ||
      !reject("missing ownership", module(generate(analog(query(), generated))), "043-004") ||
      !reject("cross generated owner",
              module(generate(analog(
                  query("temperature", changed(generated, "T.gen\"", "T.other\"")), generated))),
              "043-004"))
    return 1;

  const std::string compare =
      "real_gt(analog_context_v1_temperature(),analog_context_v1_nominal_temperature())";
  if (!accept(module(procedure(condition(compare, false)))) ||
      !reject("static context condition", module(procedure(condition(compare, true))), "037-010") ||
      !reject("static unchosen time query",
              module(procedure(
                  condition("real_gt(analog_select(true,1.0 s,analog_abstime()),0.0 s)", true))),
              "037-010"))
    return 1;
  for (const auto &entry : std::vector<std::pair<std::string, std::string>>{
           {"analog_context_v1_temperature()", "temperature"},
           {"analog_context_v1_nominal_temperature()", "temperature"},
           {"analog_abstime()", "time"}})
    if (!reject("persistent context initializer",
                module(procedure(variable(entry.first, entry.second))), "037-009"))
      return 1;
  if (!accept(module(procedure(variable("1.0", "1")))))
    return 1;

  const std::string functionPrefix =
      "\"nodal.analog_user_function\"() <{sym_name = \"thermal\", return_type = " + temperature +
      ", contract_version = \"1\", metadata = {}}> ({\n"
      "%input = \"nodal.analog_function_value\"() <{kind = \"input\", name = \"value\", "
      "argument_index = 0 : i64, metadata = {}}> : () -> " +
      temperature + "\n";
  const std::string functionReturn =
      "\"nodal.analog_function_return\"(%input) <{metadata = {}}> : (" + temperature +
      ") -> ()\n}) : () -> ()\n";
  if (!accept(module(functionPrefix + functionReturn)) ||
      !reject("pure user function query", module(functionPrefix + query() + functionReturn),
              "041-001"))
    return 1;
  llvm::outs() << "AnalogContextTest: " << cases << " focused cases passed\n";
  return 0;
}
