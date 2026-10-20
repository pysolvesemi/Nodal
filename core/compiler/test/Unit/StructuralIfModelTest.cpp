#include "nodal/Dialect/Nodal/StructuralIfModel.h"

#include "mlir/IR/Builders.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/DigitalLoopPlanningModel.h"
#include "nodal/Dialect/Nodal/DigitalMaterializationModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/StaticPredicateModel.h"
#include "nodal/Dialect/Nodal/TargetCapabilities.h"
#include "nodal/Support/BackendCapabilityRegistry.h"

#include "llvm/Support/Signals.h"
#include "llvm/Support/raw_ostream.h"

#include <string>
using namespace mlir;
namespace loops {
#include "DigitalLoopTestFixtures.h"
}
namespace {
std::string metadata(std::string id) {
  return "metadata = {semantic_path = \"Fixture." + id + "\"}";
}
std::string literal(std::string id, int64_t n, std::string type = "i64") {
  return "%" + id + " = \"nodal.const_literal\"() <{value = " + std::to_string(n) +
         " : i64, spelling = \"" + std::to_string(n) + "\", " + metadata(id) + "}> : () -> " +
         type + "\n";
}
std::string param(int n = 0) {
  return "\"nodal.parameter\"() <{sym_name = \"MODE\", type = i64, default_value = " +
         std::to_string(n) +
         " : i64, parameter_kind = \"integer\", classification = \"structural\", variability = "
         "\"symbolic\", " +
         metadata("MODE") +
         "}> : () -> ()\n"
         "\"nodal.parameter_envelope\"() <{parameter = @MODE, effects = [\"topology\"], policy = "
         "\"static_generate\", metadata = {}}> : () -> ()\n"
         "%mode = \"nodal.const_parameter_ref\"() <{parameter = @MODE, " +
         metadata("mode") + "}> : () -> i64\n" + literal("zero", 0);
}
std::string compare(std::string id = "pred", std::string cmp = "eq", std::string lhs = "mode",
                    std::string rhs = "zero") {
  return "%" + id + " = \"nodal.static_compare\"(%" + lhs + ", %" + rhs + ") <{comparison = \"" +
         cmp + "\", " + metadata(id) + "}> : (i64, i64) -> !nodal.static_predicate\n";
}
std::string port(std::string id, std::string type) {
  return "\"nodal.port\"() <{sym_name = \"" + id + "\", type = " + type +
         ", direction = \"input\", domain = @none, metadata = {}}> : () -> ()\n%" + id +
         " = \"nodal.port_value\"() <{port = @" + id + ", " + metadata(id) + "}> : () -> " + type +
         "\n";
}
std::string yield(std::string arm, std::string value = "", std::string type = "") {
  return "\"nodal.structural_yield\"(" + (value.empty() ? "" : "%" + value) + ") <{" +
         metadata("choice." + arm + ".yield") + "}> : (" + type + ") -> ()\n";
}
std::string choice(std::string type = "!nodal.uint<8>", std::string left = "",
                   std::string right = "") {
  return "%joined = \"nodal.structural_if\"(%pred, %a, %b) <{contract_version = "
         "\"structural-if-v1\", region_id = \"Fixture.choice\", operandSegmentSizes = array<i32: "
         "1, 1, 1>, " +
         metadata("choice") + "}> ({^bb0(%x: " + type + "):\n" +
         (left.empty() ? yield("then", "x", type) : left) + "}, {^bb0(%y: " + type + "):\n" +
         (right.empty() ? yield("else", "y", type) : right) + "}) : (!nodal.static_predicate, " +
         type + ", " + type + ") -> " + type + "\n";
}
std::string base(std::string type = "!nodal.uint<8>", int mode = 0) {
  return param(mode) + compare() + port("a", type) + port("b", type) + choice(type);
}
std::string replace(std::string text, std::string from, std::string to) {
  auto at = text.find(from);
  if (at != std::string::npos)
    text.replace(at, from.size(), to);
  return text;
}

} // namespace
int main() {
  llvm::sys::PrintStackTraceOnErrorSignal("nodal-structural-if-model-unit-tests");
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  context.loadAllAvailableDialects();
  unsigned cases = 0, failures = 0;
  std::string diagnostics;
  ScopedDiagnosticHandler capture(&context, [&](Diagnostic &d) {
    llvm::raw_string_ostream out(diagnostics);
    d.print(out);
    return success();
  });
  auto parse = [&](std::string body, bool quality = false, std::string root = "") {
    // A direct module identity is canonical; no synthetic runtime carrier is used.
    std::string source =
        "module" +
        std::string(quality ? " attributes {nodal.digital.quality = {version = 1 : i64, "
                              "temporaries = \"inline-safe\", naming = \"semantic\"}}"
                            : "") +
        " { " + root +
        " \"nodal.module\"() <{sym_name = \"Fixture\", metadata = {semantic_path = "
        "\"Fixture\"}}> ({\n" +
        body + "}) : () -> () }";
    return parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
  };
  auto check = [&](std::string name, bool pass) {
    ++cases;
    failures += !pass;
    (pass ? llvm::outs() : llvm::errs())
        << "STRUCTURAL_IF_" << (pass ? "PASS " : "FAIL ") << name << '\n';
    llvm::outs().flush();
    if (!pass)
      llvm::errs() << diagnostics << '\n';
    diagnostics.clear();
  };
  auto verifyCase = [&](std::string name, std::string body, bool expected, std::string root = "") {
    auto ir = parse(body, false, root);
    bool valid = ir && succeeded(verify(*ir));
    check(name, ir && valid == expected);
  };
  const std::string sparseEnum =
      "\"nodal.enum\"() <{sym_name = \"Mode\", underlying_type = !nodal.uint<3>, "
      "encoding = \"custom\", metadata = {abi_version = 1 : i64, semantic_type = \"Mode\", "
      "abi_hash = \"d06a339707813bfffb47e9e4bbdff53e7bca8a0c8a220786446a4427eaf0f9d6\"}}> ({"
      "\"nodal.enum_case\"() <{sym_name = \"Idle\", value = 0 : i64, metadata = {case_id = "
      "\"Mode.Idle\"}}> : () -> () "
      "\"nodal.enum_case\"() <{sym_name = \"Run\", value = 3 : i64, metadata = {case_id = "
      "\"Mode.Run\"}}> : () -> () "
      "\"nodal.enum_case\"() <{sym_name = \"Stop\", value = 7 : i64, metadata = {case_id = "
      "\"Mode.Stop\"}}> : () -> () "
      "}) : () -> ()\n";
  const std::string enumType = "!nodal.enum<\"Mode\", 3>";
  for (bool spelled : {false, true}) {
    auto armLiteral = [&](std::string arm, int value) {
      std::string result = literal("choice." + arm + ".value", value, enumType);
      if (!spelled) {
        result = replace(result, "nodal.const_literal", "nodal.constant");
        result = replace(result, ", spelling = \"" + std::to_string(value) + "\"", "");
      }
      return result + yield(arm, "choice." + arm + ".value", enumType);
    };
    auto body = [&](int left) {
      return param() + compare() +
             "%joined = \"nodal.structural_if\"(%pred) <{contract_version = \"structural-if-v1\", "
             "region_id = \"Fixture.choice\", operandSegmentSizes = array<i32: 1, 0, 0>, " +
             metadata("choice") + "}> ({" + armLiteral("then", left) + "}, {" +
             armLiteral("else", 3) + "}) : (!nodal.static_predicate) -> " + enumType + "\n";
    };
    verifyCase(spelled ? "enum-literal-arm" : "enum-constant-arm", body(0), true, sparseEnum);
    verifyCase(spelled ? "invalid-enum-literal-arm" : "invalid-enum-constant-arm", body(2), false,
               sparseEnum);
  }
  verifyCase("unconstrained-i64-equality", base(), true);
  verifyCase("changed-default-preserves-symbolic-choice", base("!nodal.uint<8>", 7), true);
  for (std::string cmp : {"eq", "ne", "lt", "le", "gt", "ge"})
    verifyCase("closed-compare-" + cmp, param() + compare("pred", cmp), true);
  verifyCase("unknown-comparison",
             replace(base(), "comparison = \"eq\"", "comparison = \"equals\""), false);
  verifyCase("ordinary-parameter-is-not-topology",
             replace(base(), "classification = \"structural\"", "classification = \"ordinary\""),
             false);
  verifyCase("unbounded-arithmetic-overflow",
             param() + literal("one", 1) +
                 "%bad = \"nodal.const_expr\"(%mode, %one) <{operator_name = \"add\", " +
                 metadata("bad") + "}> : (i64, i64) -> i64\n" + compare("pred", "eq", "bad"),
             false);
  verifyCase("host-runtime-i64-is-not-static", port("mode", "i64") + literal("zero", 0) + compare(),
             false);
  verifyCase("foreign-parameter-reference",
             replace(base(), "parameter = @MODE, metadata = {semantic_path = \"Fixture.mode\"}",
                     "parameter = @OTHER, metadata = {semantic_path = \"Fixture.mode\"}"),
             false);
  verifyCase(
      "boolean-composition",
      param() + compare() + compare("other", "ne") +
          "%both = \"nodal.static_logic\"(%pred, %other) <{operator_name = \"and\", " +
          metadata("both") +
          "}> : (!nodal.static_predicate, !nodal.static_predicate) -> !nodal.static_predicate\n",
      true);
  {
    auto ir = parse(param() + compare());
    auto &module = ir->getBody()->front();
    auto &comparison = module.getRegion(0).front().back();
    comparison.setAttr("static_value", BoolAttr::get(&context, true));
    check("serialized-predicate-proof-reject", failed(verify(*ir)));
  }
  verifyCase("static-token-constant-producer-reject",
             literal("forged", 1, "!nodal.static_predicate"), false);
  verifyCase("logic-wrong-arity",
             param() + compare() +
                 "%bad = \"nodal.static_logic\"(%pred) <{operator_name = \"and\", " +
                 metadata("bad") + "}> : (!nodal.static_predicate) -> !nodal.static_predicate\n",
             false);
  for (std::string type : {"!nodal.bits<1>", "!nodal.bits<8>", "!nodal.sint<8>",
                           "!nodal.shaped<\"2,3\", !nodal.uint<8>>",
                           "!nodal.struct<\"Pair\", [{name = \"x\", type = !nodal.uint<8>}, {name "
                           "= \"flag\", type = !nodal.bits<1>}]>"})
    verifyCase("exact-join-" + type, base(type), true);
  verifyCase("root-Integer-join-reject", base("i64"), false);
  verifyCase("root-Real-join-reject", base("f64"), false);
  verifyCase("wrong-yield-sign",
             replace(base(), yield("else", "y", "!nodal.uint<8>"),
                     literal("negative", 0, "!nodal.sint<8>") +
                         yield("else", "negative", "!nodal.sint<8>")),
             false);
  verifyCase(
      "wrong-yield-width",
      replace(base(), yield("else", "y", "!nodal.uint<8>"),
              literal("narrow", 0, "!nodal.uint<7>") + yield("else", "narrow", "!nodal.uint<7>")),
      false);
  {
    auto ir = parse(base());
    auto module = &ir->getBody()->front();
    auto &join = module->getRegion(0).front().back();
    join.getRegion(1).front().back().setOperand(0, join.getRegion(0).front().getArgument(0));
    check("cross-arm-escape", failed(verify(*ir)));
  }
  verifyCase("missing-yield", replace(base(), yield("else", "y", "!nodal.uint<8>"), ""), false);
  verifyCase("wrong-arm-identity",
             replace(base(), "Fixture.choice.else.yield", "Fixture.choice.then.yield"), false);
  verifyCase("bad-capture-segments", replace(base(), "array<i32: 1, 1, 1>", "array<i32: 1, 2, 0>"),
             false);
  verifyCase(
      "implicit-capture-reject",
      replace(base(), yield("then", "x", "!nodal.uint<8>"), yield("then", "a", "!nodal.uint<8>")),
      false);
  verifyCase("static-token-storage-reject", port("token", "!nodal.static_predicate"), false);
  verifyCase("static-token-storage-after-cached-token-reject",
             param() + compare() + port("token", "!nodal.static_predicate"), false);
  verifyCase("invariant-dead-arm-still-invalid",
             replace(replace(base(), "(%mode, %zero)", "(%zero, %zero)"),
                     yield("else", "y", "!nodal.uint<8>"), yield("else", "a", "!nodal.uint<8>")),
             false);
  {
    const std::string scalar = "!nodal.uint<8>", vec = "!nodal.shaped<\"2\", !nodal.uint<8>>";
    std::string inside = "%localMode = \"nodal.const_parameter_ref\"() <{parameter = @MODE, " +
                         metadata("map.mode") + "}> : () -> i64\n" + literal("localZero", 0) +
                         compare("pred", "eq", "localMode", "localZero");
    auto joined = replace(replace(choice(scalar), "%a, %b", "%element, %element"), "Fixture.choice",
                          "Fixture.map.choice");
    // Both arm identities are rewritten independently because replace is singular.
    joined = replace(replace(joined, "Fixture.choice", "Fixture.map.choice"), "Fixture.choice",
                     "Fixture.map.choice");
    joined = replace(joined, "Fixture.choice", "Fixture.map.choice");
    auto map = [&](std::string body) {
      return param() + port("input", vec) +
             "%mapped = \"nodal.collection\"(%input) <{kind = \"map\", region_id = "
             "\"Fixture.map\", operandSegmentSizes = array<i32: 1, 0, 0>, " +
             metadata("map") + "}> ({^bb0(%element: " + scalar + "):\n" + body +
             "\"nodal.collection_yield\"(%joined) <{metadata = {}}> : (" + scalar +
             ") -> ()\n}) : (" + vec + ") -> " + vec + "\n";
    };
    verifyCase("value-choice-in-map-callback", map(inside + joined), true);
    verifyCase(
        "shared-choice-data-boundary-reused-by-two-inputs",
        base(vec) +
            "%zipped = \"nodal.collection\"(%joined, %joined) <{kind = \"zip_with\", region_id = "
            "\"Fixture.zip\", operandSegmentSizes = array<i32: 2, 0, 0>, " +
            metadata("zip") + "}> ({^bb0(%left: " + scalar + ", %right: " + scalar +
            "):\n"
            "\"nodal.collection_yield\"(%left) <{metadata = {}}> : (" +
            scalar + ") -> ()\n}) : (" + vec + ", " + vec + ") -> " + vec + "\n",
        true);
    std::string reduction =
        param() + port("input", vec) +
        "%reduced = \"nodal.collection\"(%input) <{kind = \"reduce_left\", region_id = "
        "\"Fixture.reduce\", operandSegmentSizes = array<i32: 1, 0, 0>, " +
        metadata("reduce") +
        "}> ({^bb0(%L: i64, %R: i64, %left: !nodal.digital<\"uint\", \"Fixture.reduce.arg0\">, "
        "%right: !nodal.digital<\"uint\", \"Fixture.reduce.arg1\">):\n" +
        literal("one", 1) + compare("forbidden", "eq", "L", "one") +
        "\"nodal.collection_yield\"(%left) <{metadata = {}}> : (!nodal.digital<\"uint\", "
        "\"Fixture.reduce.arg0\">) -> ()\n}) : (" +
        vec + ") -> " + scalar + "\n";
    verifyCase("reduction-width-formal-never-static-predicate", reduction, false);
  }
  {
    auto type = loops::shape("8");
    auto joined = replace(choice(), "%a, %b", "%src, %src");
    auto inside =
        loops::index("dst", "target", "iv", type) + loops::index("src", "input", "iv", type) +
        compare("pred", "eq", "localMode", "localZero") + joined + loops::assign("dst", "joined");
    auto body =
        param() + loops::literal("lo", 0) + loops::literal("hi", 8) + loops::literal("step", 1) +
        loops::port("in", type) + loops::port("out", type, "output") +
        loops::loop("loop", ", %out, %in, %mode, %zero", ", " + type + ", " + type + ", i64, i64",
                    ", %target: " + type + ", %input: " + type +
                        ", %localMode: i64, %localZero: i64",
                    inside);
    verifyCase("parameter-only-value-if-in-procedural-loop", body, true);
    const std::string statement =
        "\"nodal.structural_if\"(%pred, %dst, %src, %dst, %src) <{contract_version = "
        "\"structural-if-v1\", region_id = \"Fixture.choice\", operandSegmentSizes = array<i32: "
        "1, 2, 2>, " +
        metadata("choice") + "}> ({^bb0(%td: !nodal.uint<8>, %ts: !nodal.uint<8>):\n" +
        loops::assign("td", "ts") + yield("then") +
        "}, {^bb0(%fd: !nodal.uint<8>, %fs: !nodal.uint<8>):\n" + loops::assign("fd", "fs") +
        yield("else") +
        "}) : (!nodal.static_predicate, !nodal.uint<8>, !nodal.uint<8>, !nodal.uint<8>, "
        "!nodal.uint<8>) -> ()\n";
    auto statementBody = replace(body, joined + loops::assign("dst", "joined"), statement);
    verifyCase("parameter-only-statement-if-in-procedural-loop", statementBody, true);
    auto missing = replace(statement, "%pred, %dst, %src, %dst, %src", "%pred, %dst, %src");
    missing = replace(missing, "array<i32: 1, 2, 2>", "array<i32: 1, 2, 0>");
    missing = replace(
        missing, "^bb0(%fd: !nodal.uint<8>, %fs: !nodal.uint<8>):\n" + loops::assign("fd", "fs"),
        "^bb0():\n");
    missing = replace(missing,
                      "(!nodal.static_predicate, !nodal.uint<8>, !nodal.uint<8>, "
                      "!nodal.uint<8>, !nodal.uint<8>)",
                      "(!nodal.static_predicate, !nodal.uint<8>, !nodal.uint<8>)");
    auto uncovered = parse(replace(body, joined + loops::assign("dst", "joined"), missing));
    check("procedural-loop-if-missing-alternative-coverage",
          uncovered && failed(verify(*uncovered)) &&
              diagnostics.find("NODAL-DIGITAL-055-010") != std::string::npos);
    verifyCase("procedural-induction-never-static-predicate",
               replace(body, "(%localMode, %localZero)", "(%iv, %localZero)"), false);
    auto ir = parse(body);
    auto module = ir ? &ir->getBody()->front() : nullptr;
    auto plan =
        module ? nodal::analyzeDigitalLoopPlans(module) : FailureOr<DictionaryAttr>(failure());
    bool preserved = succeeded(plan);
    if (preserved) {
      auto entry = llvm::cast<DictionaryAttr>(plan->getAs<ArrayAttr>("plans")[0]);
      preserved = entry.getAs<StringAttr>("selected").getValue() == "procedural";
    }
    check("loop-plan-preserves-structural-regions", preserved);
  }
  {
    const std::string scalar = "!nodal.uint<8>", vec = loops::shape("2");
    auto arm = [&](std::string side, std::string root, std::string iv) {
      return loops::index(side + "Value", root, iv, vec) + yield(side, side + "Value", scalar);
    };
    const std::string join =
        "%joined = \"nodal.structural_if\"(%pred, %input, %iv, %input, %iv) <{contract_version = "
        "\"structural-if-v1\", region_id = \"Fixture.choice\", operandSegmentSizes = array<i32: "
        "1, 2, 2>, " +
        metadata("choice") + "}> ({^bb0(%tx: " + vec + ", %ti: i64):\n" + arm("then", "tx", "ti") +
        "}, {^bb0(%fx: " + vec + ", %fi: i64):\n" + arm("else", "fx", "fi") +
        "}) : (!nodal.static_predicate, " + vec + ", i64, " + vec + ", i64) -> " + scalar + "\n";
    auto assigned = replace(loops::assign("dst", "joined"), "metadata = {}",
                            "metadata = {source_ordinal = 1 : i64}");
    const std::string setup = param() + literal("lo", 0) + literal("hi", 2) + literal("step", 1) +
                              loops::port("inputRoot", vec) +
                              loops::port("outputRoot", vec, "output");
    const std::string body =
        setup +
        loops::loop("selectorLoop", ", %mode, %zero, %inputRoot, %outputRoot",
                    ", i64, i64, " + vec + ", " + vec,
                    ", %localMode: i64, %localZero: i64, %input: " + vec + ", %output: " + vec,
                    compare("pred", "eq", "localMode", "localZero") + join +
                        loops::index("dst", "output", "iv", vec) + assigned);
    verifyCase("procedural-selector-captured-by-value-arms", body, true);
    verifyCase("conditional-event-counted-in-loop-order",
               replace(body, "source_ordinal = 1", "source_ordinal = 0"), false);
    verifyCase("captured-procedural-selector-never-static-predicate",
               replace(body, arm("then", "tx", "ti"),
                       compare("forbidden", "eq", "ti", "ti") + arm("then", "tx", "ti")),
               false);
    verifyCase("captured-procedural-selector-never-type-width",
               replace(body, arm("then", "tx", "ti"),
                       loops::index("firstUse", "tx", "ti", vec) +
                           literal("forbiddenWidth", 0,
                                   "!nodal.digital<\"uint\", \"Fixture.choice.then.arg1\">") +
                           arm("then", "tx", "ti")),
               false);
    {
      auto ir = parse(body);
      nodal::StructuralIfOp joinOp;
      ir->walk([&](nodal::StructuralIfOp op) { joinOp = op; });
      auto &left = joinOp->getRegion(0).front();
      auto &right = joinOp->getRegion(1).front();
      right.front().setOperand(1, left.getArgument(1));
      check("procedural-selector-sibling-arm-escape", failed(verify(*ir)));
    }
    {
      auto ir = parse(body);
      nodal::StructuralIfOp joinOp;
      ir->walk([&](nodal::StructuralIfOp op) { joinOp = op; });
      joinOp->setOperand(2, joinOp->getOperand(1));
      check("procedural-selector-forged-capture-slot", failed(verify(*ir)));
    }
    auto affine = [&](int64_t offset) {
      return loops::index("firstUse", "tx", "ti", vec) + literal("offset", offset) +
             "%next = \"nodal.iteration_index\"(%ti, %offset) <{operator_name = \"add\", " +
             metadata("next") + "}> : (i64, i64) -> i64\n" + arm("then", "tx", "next");
    };
    verifyCase("arm-local-procedural-affine-selector",
               replace(body, arm("then", "tx", "ti"), affine(0)), true);
    verifyCase("arm-local-procedural-affine-overflow",
               replace(body, arm("then", "tx", "ti"), affine(INT64_MAX)), false);
  }
  {
    const std::string scalar = "!nodal.uint<8>", vec = loops::shape("2");
    const std::string join =
        "%joined = \"nodal.structural_if\"(%pred, %out, %a, %out, %b) <{contract_version = "
        "\"structural-if-v1\", region_id = \"Fixture.choice\", operandSegmentSizes = array<i32: "
        "1, 2, 2>, " +
        metadata("choice") + "}> ({^bb0(%td: " + scalar + ", %ts: " + scalar + "):\n" +
        loops::assign("td", "ts") + yield("then", "ts", scalar) + "}, {^bb0(%fd: " + scalar +
        ", %fs: " + scalar + "):\n" + loops::assign("fd", "fs") + yield("else", "fs", scalar) +
        "}) : (!nodal.static_predicate, " + scalar + ", " + scalar + ", " + scalar + ", " + scalar +
        ") -> " + scalar + "\n";
    verifyCase("value-choice-with-complementary-continuous-writes",
               param() + compare() + port("a", scalar) + port("b", scalar) +
                   loops::port("out", scalar, "output") + join,
               true);
    auto inner = replace(replace(join, "%out, %a, %out, %b", "%sink, %element, %sink, %element"),
                         "%pred", "%localPredicate");
    auto callback = param() + port("input", vec) + loops::port("out", scalar, "output") +
                    "%mapped = \"nodal.collection\"(%input, %out) <{kind = \"map\", region_id = "
                    "\"Fixture.effectMap\", operandSegmentSizes = array<i32: 1, 0, 1>, " +
                    metadata("effectMap") + "}> ({^bb0(%element: " + scalar + ", %sink: " + scalar +
                    "):\n"
                    "%localMode = \"nodal.const_parameter_ref\"() <{parameter = @MODE, " +
                    metadata("map.mode") + "}> : () -> i64\n" + literal("localZero", 0) +
                    compare("localPredicate", "eq", "localMode", "localZero") + inner +
                    "\"nodal.collection_yield\"(%joined) <{metadata = {}}> : (" + scalar +
                    ") -> ()\n}) : (" + vec + ", " + scalar + ") -> " + vec + "\n";
    auto ir = parse(callback);
    check("collection-value-choice-still-rejects-continuous-effects",
          ir && failed(verify(*ir)) &&
              diagnostics.find("this body admits no state or procedural effects") !=
                  std::string::npos);
  }
  for (auto operation : {"nodal.static_compare", "nodal.static_logic", "nodal.structural_if",
                         "nodal.structural_yield"}) {
    auto entry = nodal::lookupBackendOperation(operation);
    check(std::string("explicit-unsupported-target-") + operation,
          entry && entry->domain == nodal::BackendOperationDomain::Digital &&
              entry->admission == nodal::BackendOperationAdmission::Unsupported);
  }
  {
    auto ir = parse(param() + compare());
    check("static-token-rejects-analog-language",
          failed(nodal::verifyTargetLanguageCapabilities(*ir, "verilog-a")));
    check("static-token-digital-language-is-not-lowering",
          succeeded(nodal::verifyTargetLanguageCapabilities(*ir, "verilog-ams")));
  }
  std::string deep = param() + compare();
  for (unsigned i = 0; i < 129; ++i) {
    std::string prior = i ? "n" + std::to_string(i - 1) : "pred", id = "n" + std::to_string(i);
    deep += "%" + id + " = \"nodal.static_logic\"(%" + prior + ") <{operator_name = \"not\", " +
            metadata(id) + "}> : (!nodal.static_predicate) -> !nodal.static_predicate\n";
  }
  verifyCase("predicate-depth-budget", deep, false);
  {
    auto ir = parse(base(), true);
    Operation *module = ir ? &ir->getBody()->front() : nullptr;
    auto report = module ? nodal::analyzeDigitalMaterialization(module)
                         : FailureOr<DictionaryAttr>(failure());
    check("materialization-v2-real-choice-edges",
          succeeded(report) && report->getAs<IntegerAttr>("version").getInt() == 2 &&
              report->getAs<ArrayAttr>("structural_choices").size() >= 4);
    if (succeeded(report)) {
      module->setAttr("nodal.digital.materialization", *report);
      check("attached-report-reverifies",
            succeeded(nodal::verifyDigitalMaterializationReport(module)));
      Operation *compareOp = nullptr;
      module->walk([&](nodal::StaticCompareOp op) { compareOp = op; });
      Value original = compareOp->getOperand(0);
      compareOp->setOperand(0, compareOp->getOperand(1));
      check("predicate-reroute-stales-report",
            failed(nodal::verifyDigitalMaterializationReport(module)));
      compareOp->setOperand(0, original);
      Operation *join = &module->getRegion(0).front().back();
      auto &arm = join->getRegion(1).front();
      auto saved = arm.back().getOperand(0);
      arm.back().setOperand(0, join->getRegion(0).front().getArgument(0));
      check("yield-reroute-stales-report",
            failed(nodal::verifyDigitalMaterializationReport(module)));
      arm.back().setOperand(0, saved);
    }
  }
  {
    auto nested = replace(replace(choice(), "%pred", "%gpred"), "%joined", "%gjoined");
    while (nested.find("Fixture.choice") != std::string::npos)
      nested = replace(nested, "Fixture.choice", "Fixture.generated.choice");
    std::string body = base() + literal("hi", 2) + literal("step", 1) + literal("alternate", 3) +
                       "\"nodal.generate\"(%zero, %hi, %step) <{induction = \"i\", region_id = "
                       "\"Fixture.generated\", induction_path = \"Fixture.generated.i\", metadata "
                       "= {semantic_path = \"Fixture.generated\", maximum_trip_count = 2 : i64}}> "
                       "({^bb0(%gi: i64):\n" +
                       compare("gpred", "eq", "gi", "zero") + nested +
                       "}) : (i64, i64, i64) -> ()\n";
    auto ir = parse(body, true);
    auto module = ir ? &ir->getBody()->front() : nullptr;
    auto report = module ? nodal::analyzeDigitalMaterialization(module)
                         : FailureOr<DictionaryAttr>(failure());
    check("generated-report-retains-domain-and-induction",
          succeeded(report) && succeeded(verify(*ir)));
    if (succeeded(report)) {
      module->setAttr("nodal.digital.materialization", *report);
      Operation *generated = &module->getRegion(0).front().back();
      Value alternate = generated->getPrevNode()->getResult(0);
      generated->setOperand(1, alternate);
      NamedAttrList metadata(generated->getAttrOfType<DictionaryAttr>("metadata"));
      metadata.set("maximum_trip_count", IntegerAttr::get(IntegerType::get(&context, 64), 3));
      generated->setAttr("metadata", metadata.getDictionary(&context));
      check("actual-generated-bound-reroute-stales-report",
            failed(nodal::verifyDigitalMaterializationReport(module)));
    }
  }
  {
    auto ir = parse(base());
    nodal::StructuralIfOp owner;
    if (ir)
      ir->walk([&](nodal::StructuralIfOp operation) { owner = operation; });
    Operation *left = owner ? &owner->getRegion(0).front().back() : nullptr;
    Operation *right = owner ? &owner->getRegion(1).front().back() : nullptr;
    check("actual-opposite-arm-exclusivity",
          left && right && nodal::areOppositeStructuralArms(left, right));
    check("same-arm-not-exclusive", left && !nodal::areOppositeStructuralArms(left, left));
    std::string second = choice();
    for (size_t at = 0; (at = second.find("choice", at)) != std::string::npos; at += 7)
      second.replace(at, 6, "choice2");
    second = replace(second, "%joined", "%joined2");
    auto sameModule = parse(base() + second);
    SmallVector<nodal::StructuralIfOp, 2> choices;
    if (sameModule)
      sameModule->walk([&](nodal::StructuralIfOp operation) { choices.push_back(operation); });
    check("equal-predicate-distinct-choice-not-exclusive",
          choices.size() == 2 &&
              !nodal::areOppositeStructuralArms(&choices[0]->getRegion(0).front().back(),
                                                &choices[1]->getRegion(1).front().back()));
    auto independent = parse(base());
    nodal::StructuralIfOp other;
    if (independent)
      independent->walk([&](nodal::StructuralIfOp operation) { other = operation; });
    check("equal-predicate-foreign-owner-not-exclusive",
          left && other &&
              !nodal::areOppositeStructuralArms(left, &other->getRegion(1).front().back()));
  }
  llvm::outs() << "STRUCTURAL_IF_CASES " << cases << " FAILURES " << failures << '\n';
  return failures ? 1 : 0;
}
