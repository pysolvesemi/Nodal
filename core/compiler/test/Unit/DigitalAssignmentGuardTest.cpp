#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/Support/raw_ostream.h"

#include <string>
#include <vector>
using namespace mlir;
namespace {
#include "DigitalLoopTestFixtures.h"
std::string parameter() {
  return literal("zero", 0) + literal("one", 1) +
         "\"nodal.parameter\"() <{sym_name = \"MODE\", type = i64, default_value = 0 : i64, "
         "parameter_kind = \"integer\", classification = \"structural\", variability = "
         "\"symbolic\", metadata = {}}> : () -> ()\n"
         "\"nodal.parameter_constraint\"(%zero, %one) <{parameter = @MODE, constraint_kind = "
         "\"range\", lower_inclusive = true, upper_inclusive = true, metadata = {}}> : (i64, "
         "i64) -> ()\n"
         "\"nodal.parameter_envelope\"() <{parameter = @MODE, policy = \"static_generate\", "
         "effects = [\"topology\"], metadata = {}}> : () -> ()\n"
         "%mode = \"nodal.const_parameter_ref\"() <{parameter = @MODE, metadata = {}}> : () -> "
         "i64\n"
         "%condition = \"nodal.static_compare\"(%mode, %zero) <{comparison = \"eq\", metadata = "
         "{semantic_path = \"Fixture.condition\"}}> : (i64, i64) -> !nodal.static_predicate\n";
}
struct Capture {
  std::string source, argument, type = u8;
};
std::string branch(std::string id, std::vector<Capture> left, std::string thenBody,
                   std::vector<Capture> right, std::string elseBody,
                   std::string condition = "condition", std::string result = "") {
  std::string operands = "%" + condition, types = "!nodal.static_predicate";
  auto arm = [&](const std::vector<Capture> &captures, std::string body, StringRef alternative) {
    const std::string placeholder = "Fixture.yield";
    size_t position = 0;
    while ((position = body.find(placeholder, position)) != std::string::npos) {
      std::string identity = "Fixture." + id + "." + alternative.str() + ".yield";
      body.replace(position, placeholder.size(), identity);
      position += identity.size();
    }
    std::string block = "({^bb0(";
    for (unsigned i = 0; i < captures.size(); ++i) {
      const auto &capture = captures[i];
      operands += ", %" + capture.source;
      types += ", " + capture.type;
      if (i)
        block += ", ";
      block += "%" + capture.argument + ": " + capture.type;
    }
    return block + "):\n" + body + "})";
  };
  std::string a = arm(left, thenBody, "then"), b = arm(right, elseBody, "else");
  return (result.empty() ? "" : "%" + result + " = ") + "\"nodal.structural_if\"(" + operands +
         ") <{contract_version = \"structural-if-v1\", "
         "region_id = \"Fixture." +
         id + "\", operandSegmentSizes = array<i32: 1, " + std::to_string(left.size()) + ", " +
         std::to_string(right.size()) + ">, metadata = {semantic_path = \"Fixture." + id +
         "\"}}> (" + a.substr(1, a.size() - 2) + ", " + b.substr(1, b.size() - 2) + ") : (" +
         types + ") -> " + (result.empty() ? "()" : u8) + "\n";
}
std::string yield(std::string value = "") {
  return "\"nodal.structural_yield\"(" + (value.empty() ? "" : "%" + value) +
         ") <{metadata = {semantic_path = \"Fixture.yield\"}}> : (" + (value.empty() ? "" : u8) +
         ") -> ()\n";
}
std::string model(const std::string &body) {
  return "module { \"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({\n" + body +
         "}) : () -> () }";
}
std::string basic(std::string thenBody, std::string elseBody) {
  return parameter() + port("input", u8) + port("out", u8, "output") +
         branch("choice", {{"out", "dst"}, {"input", "src"}}, thenBody + yield(),
                {{"out", "otherDst"}, {"input", "otherSrc"}}, elseBody + yield());
}
std::string cycle(bool feasible) {
  return parameter() + port("input", u8) + wire("x", u8) + wire("y", u8) +
         branch("choice", {{"x", "tx"}, {"y", "ty"}, {"input", "ti"}},
                assign("tx", "ty") + assign("ty", feasible ? "tx" : "ti") +
                    (feasible
                         ? "%used = \"nodal.digital_expr\"(%ti) <{operator_name = \"digital_not\", "
                           "metadata = {}}> : (!nodal.uint<8>) -> !nodal.uint<8>\n"
                         : "") +
                    yield(),
                {{"y", "fy"}, {"x", "fx"}, {"input", "fi"}},
                assign("fy", "fx") + assign("fx", "fi") + yield());
}
std::string partial() {
  std::string type = shape("2");
  return parameter() + port("input", u8) + port("out", type, "output") +
         branch("choice", {{"out", "left", type}, {"zero", "lo", "i64"}, {"input", "a"}},
                index("partA", "left", "lo", type) + assign("partA", "a") + yield(),
                {{"out", "right", type}, {"one", "hi", "i64"}, {"input", "b"}},
                index("partB", "right", "hi", type) + assign("partB", "b") + yield());
}
std::string branchRead(bool missing) {
  return parameter() + port("input", u8) + wire("x", u8) + port("out", u8, "output") +
         branch("choice", {{"x", "tx"}, {"input", "ti"}, {"out", "to"}},
                assign("tx", "ti") + assign("to", "tx") + yield(),
                {{"out", "fo"}, {missing ? "x" : "input", "fi"}}, assign("fo", "fi") + yield());
}
std::string joinedRead() {
  return parameter() + port("input", u8) + port("other", u8) + port("out", u8, "output") +
         branch("choice", {{"input", "ti"}}, yield("ti"), {{"other", "fi"}}, yield("fi"),
                "condition", "joined") +
         assign("out", "joined");
}
std::string nested(bool missing = false) {
  std::string inner =
      branch("choice.then.inner", {{"td", "id"}, {"ts", "is"}}, assign("id", "is") + yield(),
             missing ? std::vector<Capture>{} : std::vector<Capture>{{"td", "ed"}, {"ts", "es"}},
             (missing ? "" : assign("ed", "es")) + yield(), "predicate");
  return parameter() + port("input", u8) + port("out", u8, "output") +
         branch("choice",
                {{"condition", "predicate", "!nodal.static_predicate"},
                 {"out", "td"},
                 {"input", "ts"}},
                inner + yield(), {{"out", "fd"}, {"input", "fs"}}, assign("fd", "fs") + yield());
}
std::string sharedOuter() {
  std::string expression =
      "%shared = \"nodal.digital_expr\"(%x) <{operator_name = \"digital_not\", "
      "metadata = {}}> : (!nodal.uint<8>) -> !nodal.uint<8>\n";
  return parameter() + port("input", u8) + wire("x", u8) + port("out", u8, "output") + expression +
         branch("choice", {{"x", "tx"}, {"input", "ti"}, {"out", "to"}, {"shared", "tv"}},
                assign("tx", "ti") + assign("to", "tv") + yield(),
                {{"x", "fx"}, {"input", "fi"}, {"out", "fo"}, {"shared", "fv"}},
                assign("fx", "fi") + assign("fo", "fv") + yield());
}
std::string guardedSeed(bool missing = false, bool late = false) {
  std::string parameters = replace(parameter(), literal("one", 1), literal("parameterOne", 1));
  parameters = replace(parameters, "%zero, %one", "%zero, %parameterOne");
  std::string source = parameters + chain(3, true, late);
  return replace(source, assign("base", "seed"),
                 branch("seed", {{"base", "td"}, {"seed", "ts"}}, assign("td", "ts") + yield(),
                        missing ? std::vector<Capture>{}
                                : std::vector<Capture>{{"base", "fd"}, {"seed", "fs"}},
                        (missing ? "" : assign("fd", "fs")) + yield()));
}
std::string generated(bool constantTarget = false, bool missing = false, bool crossLane = false,
                      int64_t count = 4) {
  const std::string type = shape(std::to_string(count));
  std::string left = index("leftTarget", "lt", "li", type) + index("leftValue", "ls", "li", type) +
                     assign("leftTarget", "leftValue");
  std::string right = index("rightTarget", "rt", "ri", type) +
                      index("rightValue", "rs", "ri", type) + assign("rightTarget", "rightValue");
  std::string selected = constantTarget ? "zero" : "iv";
  std::vector<Capture> thenCaptures{
      {"out", "lt", type}, {selected, "li", "i64"}, {"input", "ls", type}};
  std::vector<Capture> elseCaptures{
      {"out", "rt", type}, {selected, "ri", "i64"}, {"input", "rs", type}};
  if (missing) {
    right.clear();
    elseCaptures.clear();
  }
  if (crossLane) {
    left = index("leftTarget", "lt", "li", type) + index("leftValue", "lt", "lz", type) +
           assign("leftTarget", "leftValue");
    thenCaptures = {{"out", "lt", type}, {"iv", "li", "i64"}, {"zero", "lz", "i64"}};
  }
  return literal("lo", 0) + literal("hi", count) + literal("step", 1) + port("input", type) +
         port("out", type, "output") +
         "\"nodal.generate\"(%lo, %hi, %step) <{induction = \"i\", region_id = \"Fixture.lanes\", "
         "induction_path = \"Fixture.lanes.i\", metadata = {semantic_path = \"Fixture.lanes\", "
         "maximum_trip_count = " +
         std::to_string(count) +
         " : i64}}> "
         "({^bb0(%iv: i64):\n" +
         literal("zero", 0) +
         "%condition = \"nodal.static_compare\"(%iv, %zero) <{comparison = \"eq\", metadata = "
         "{semantic_path = \"Fixture.condition\"}}> : (i64, i64) -> !nodal.static_predicate\n" +
         branch("lanes.choice", thenCaptures, left + yield(), elseCaptures, right + yield()) +
         "}) : (i64, i64, i64) -> ()\n";
}
std::string generatedAffine(bool shifted = false) {
  std::string source = generated(), oldType = shape("4"), type = shape("5");
  size_t position = 0;
  while ((position = source.find(oldType, position)) != std::string::npos) {
    source.replace(position, oldType.size(), type);
    position += type.size();
  }
  source =
      replace(source, literal("zero", 0),
              literal("zero", 0) + literal("one", 1) +
                  "%next = \"nodal.const_expr\"(%iv, %one) <{operator_name = \"add\", "
                  "metadata = {semantic_path = \"Fixture.lanes.next\"}}> : (i64, i64) -> i64\n");
  source = replace(source, "%condition, %out, %iv, %input, %out, %iv, %input",
                   shifted ? "%condition, %out, %iv, %input, %out, %next, %input"
                           : "%condition, %out, %next, %input, %out, %next, %input");
  if (!shifted)
    source += literal("baseIndex", 0) + index("baseOut", "out", "baseIndex", type) +
              index("baseInput", "input", "baseIndex", type) + assign("baseOut", "baseInput");
  return source;
}
} // namespace
int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  context.loadAllAvailableDialects();
  unsigned count = 0, failures = 0;
  auto check = [&](StringRef name, const std::string &body, bool expected,
                   nodal::DigitalAssignmentProofLimits limits = {}) {
    ++count;
    std::string diagnostics;
    ScopedDiagnosticHandler capture(&context, [&](Diagnostic &diagnostic) {
      llvm::raw_string_ostream stream(diagnostics);
      diagnostic.print(stream);
      stream << '\n';
      return success();
    });
    auto parsed = parseSourceString<mlir::ModuleOp>(model(body), ParserConfig(&context, false));
    bool moduleValid = false, graphValid = false;
    if (parsed) {
      Operation *module = &parsed->getBody()->front();
      moduleValid = succeeded(verify(parsed->getOperation()));
      graphValid = succeeded(nodal::verifyDigitalAssignmentModel(module, limits));
    }
    bool passed = parsed && graphValid == expected && (!expected || moduleValid) &&
                  (expected || diagnostics.find("NODAL-DIGITAL-055-010") != std::string::npos);
    failures += !passed;
    (passed ? llvm::outs() : llvm::errs())
        << "DIGITAL_GUARD_" << (passed ? "PASS " : "FAIL ") << name << '\n';
    if (!passed)
      llvm::errs() << "module=" << moduleValid << " graph=" << graphValid << '\n' << diagnostics;
  };
  check("complementary-whole-output", basic(assign("dst", "src"), assign("otherDst", "otherSrc")),
        true);
  check("same-arm-duplicate",
        basic(assign("dst", "src") + assign("dst", "src"), assign("otherDst", "otherSrc")), false);
  check("missing-arm-coverage",
        parameter() + port("input", u8) + port("out", u8, "output") +
            branch("choice", {{"out", "dst"}, {"input", "src"}}, assign("dst", "src") + yield(), {},
                   yield()),
        false);
  check("unconditional-plus-conditional",
        basic(assign("dst", "src"), assign("otherDst", "otherSrc")) + assign("out", "input"),
        false);
  check("incompatible-alternatives-do-not-cycle", cycle(false), true);
  check("feasible-cycle-remains", cycle(true), false);
  check("independent-conditionals-still-conflict",
        basic(assign("dst", "src"), assign("otherDst", "otherSrc")) +
            branch("independent", {{"out", "a"}, {"input", "b"}}, assign("a", "b") + yield(),
                   {{"out", "c"}, {"input", "d"}}, assign("c", "d") + yield()),
        false);
  check("opposite-partials-do-not-union-coverage", partial(), false);
  check("branch-local-read-keeps-its-guard", branchRead(false), true);
  check("branch-local-read-in-undriven-arm", branchRead(true), false);
  check("join-yields-retain-arm-coverage", joinedRead(), true);
  check("nested-alternatives", nested(), true);
  check("nested-missing-alternative", nested(true), false);
  check("shared-outer-SSA-keeps-requesting-guard", sharedOuter(), true);
  check("guard-depth-budget", nested(), false, {65536, 262144, 16384, 4096, 4096, 1, 262144});
  check("guard-context-budget", basic(assign("dst", "src"), assign("otherDst", "otherSrc")), false,
        {65536, 262144, 16384, 4096, 1, 128, 262144});
  check("guard-work-budget", basic(assign("dst", "src"), assign("otherDst", "otherSrc")), false,
        {65536, 262144, 16384, 4096, 4096, 128, 1});
  check("generated-same-occurrence", generated(), true);
  check("generated-million-lanes-symbolic-image", generated(false, false, false, 1000000), true);
  check("generated-constant-target-cross-lane-duplicate", generated(true), false);
  check("generated-missing-arm", generated(false, true), false);
  check("generated-guarded-cross-lane-read", generated(false, false, true), false);
  check("generated-affine-same-occurrence", generatedAffine(), true);
  check("generated-shifted-opposite-maps", generatedAffine(true), false);
  check("predecessor-loop-independent-family", independent(8), true);
  check("predecessor-loop-seeded-chain", chain(3), true);
  check("predecessor-loop-missing-seed", chain(3, false), false);
  check("predecessor-loop-symbolic-chain", symbolicChain(), true);
  check("predecessor-loop-guarded-seeds", guardedSeed(), true);
  check("predecessor-loop-missing-guarded-seed", guardedSeed(true), false);
  check("predecessor-loop-late-guarded-seeds", guardedSeed(false, true), false);
  auto parsed =
      parseSourceString<mlir::ModuleOp>(model(generated()), ParserConfig(&context, false));
  SmallVector<Operation *, 2> assignments;
  if (parsed)
    parsed->walk([&](nodal::DigitalAssignOp operation) { assignments.push_back(operation); });
  bool sameOccurrence = false, noGenerateOrdering = false, shapeBudget = false;
  if (assignments.size() == 2) {
    auto a = nodal::resolveStaticShapeProjection(assignments[0]->getOperand(0), assignments[0]);
    auto b = nodal::resolveStaticShapeProjection(assignments[1]->getOperand(0), assignments[1]);
    if (succeeded(a) && succeeded(b)) {
      sameOccurrence = nodal::proveStaticShapeSameOccurrence(*a, *b, assignments[1]) ==
                       nodal::ShapeRegionProofStatus::Proved;
      noGenerateOrdering =
          failed(nodal::compareStaticShapeIterationPositions(*a, *b, assignments[1]));
      shapeBudget = nodal::proveStaticShapeSameOccurrence(*a, *b, assignments[1], {0, 1}) ==
                    nodal::ShapeRegionProofStatus::ResourceLimit;
    }
  }
  for (auto entry :
       {std::make_pair("same-occurrence-shared-shape-proof", sameOccurrence),
        std::make_pair("Generate-does-not-acquire-predecessor-order", noGenerateOrdering),
        std::make_pair("same-occurrence-shape-budget", shapeBudget)}) {
    ++count;
    failures += !entry.second;
    (entry.second ? llvm::outs() : llvm::errs())
        << "DIGITAL_GUARD_" << (entry.second ? "PASS " : "FAIL ") << entry.first << '\n';
  }
  llvm::outs() << "DIGITAL_GUARD_CASES " << count << " FAILURES " << failures << '\n';
  return failures ? 1 : 0;
}
