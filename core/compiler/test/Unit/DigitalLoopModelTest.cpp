#include "nodal/Dialect/Nodal/DigitalLoopModel.h"

#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/DigitalStorageModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Support/BackendCapabilityRegistry.h"

#include "llvm/Support/raw_ostream.h"

#include <string>
using namespace mlir;
#include "DigitalLoopTestFixtures.h"

int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  context.loadAllAvailableDialects();
  unsigned cases = 0, failures = 0;
  auto check = [&](StringRef name, std::string body, bool expected, StringRef cause = "") {
    std::string messages;
    ScopedDiagnosticHandler capture(&context, [&](Diagnostic &d) {
      llvm::raw_string_ostream s(messages);
      d.print(s);
      return success();
    });
    auto module = parseSourceString<mlir::ModuleOp>(
        "module { \"nodal.module\"() <{sym_name = \"Fixture\", metadata = {}}> ({" + body +
            "}) : () -> () }",
        ParserConfig(&context, false));
    bool valid = module && succeeded(verify(*module));
    bool pass =
        valid == expected && (cause.empty() || messages.find(cause.str()) != std::string::npos);
    ++cases;
    failures += !pass;
    (pass ? llvm::outs() : llvm::errs())
        << "DIGITAL_LOOP_" << (pass ? "PASS " : "FAIL ") << name << '\n';
    if (!pass)
      llvm::errs() << messages << '\n';
  };
  check("nested-widening-reduction-local-width", nestedWideningReduction(), true);
  check("nested-width-induction-dependent-rejected",
        replace(nestedWideningReduction(), "(%four, %growth)", "(%iv, %growth)"), false);
  check("nested-width-foreign-capture-rejected",
        replace(nestedWideningReduction(), "(%depth, %increment)", "(%depth, %step)"), false,
        "NODAL-DIGITAL-054-004");
  check("nested-width-foreign-token-rejected",
        replace(nestedWideningReduction(), "Fixture.width", "Sibling.width"), false);
  check("independent-eight", independent(8), true);
  check("two-residues", independent(8, 2, 0, true), true);
  check("stride-holes", independent(8, 2), false, "complete physical driver coverage");
  check("empty-output-gap", independent(0), false, "undriven combinational wire region");
  check("negative-step", independent(8, -1), false, "positive invariant");
  check("zero-step", independent(8, 0), false, "positive invariant");
  check("out-of-range", independent(9), false, "not in bounds");
  for (int n : {0, 1, 2, 8})
    check("seeded-chain-" + std::to_string(n), chain(n), true);

  check("symbolic-seeded-million", symbolicChain(), true);
  check("chain-external-input", crossLoop(false), true);
  check("two-locally-seeded-global-cycle", crossLoop(true), false,
        "combinational storage-read cycle");
  check("missing-seed", chain(2, false), false);
  check("late-seed", chain(2, true, true), false, "seed authored before");
  check("self-read", replace(chain(2), "index\"(%target, %iv)", "index\"(%target, %next)"), false,
        "earlier same-iteration");
  check("forged-const-expr", replace(chain(2), "nodal.iteration_index", "nodal.const_expr"), false);
  check("empty-loop-plus-complete-external-writer",
        independent(0) + assign("out", "in", shape("8")), true);
  check("empty-loop-does-not-hide-free-capture",
        replace(independent(0), "index\"(%target, %iv)", "index\"(%out, %iv)"), false,
        "explicit matching captures");
  check("unused-capture", replace(independent(8), "index\"(%input, %iv)", "index\"(%target, %iv)"),
        false, "unused or forged capture");
  check("duplicate-capture", replace(independent(8), "%out, %in)", "%out, %out)"), false);
  check("wrong-capture-order",
        replace(replace(independent(8), "index\"(%target, %iv)", "index\"(%input, %iv)"),
                "%src = \"nodal.shape_index\"(%input, %iv)",
                "%src = \"nodal.shape_index\"(%target, %iv)"),
        false, "first actual use order");
  check("forged-selector-two-iv",
        replace(chain(2), "iteration_index\"(%iv, %one)", "iteration_index\"(%iv, %iv)"), false,
        "one actual induction");
  check("forged-selector-static-only",
        replace(chain(2), "iteration_index\"(%iv, %one)", "iteration_index\"(%one, %one)"), false,
        "one actual induction");
  check("local-static-offset-dag",
        replace(chain(2), literal("one", 1),
                literal("staticOne", 1) + literal("staticZero", 0) +
                    "%one = \"nodal.const_expr\"(%staticOne, %staticZero) <{operator_name = "
                    "\"add\", metadata = {}}> : (i64, i64) -> i64\n"),
        true);
  check("maximum-is-not-clamp",
        replace(independent(8), "effect_policy = \"ordered\",",
                "effect_policy = \"ordered\", maximum = 7 : i64,"),
        false, "maximum");
  check("exact-maximum",
        replace(independent(8), "effect_policy = \"ordered\",",
                "effect_policy = \"ordered\", maximum = 8 : i64,"),
        true);
  {
    auto type = shape("8");
    auto first = index("middle", "tmp", "iv", type) + index("source", "input", "iv", type) +
                 assign("middle", "source");
    auto second = index("dst", "target", "iv", type) + assign("dst", "middle");
    auto setup = literal("lo", 0) + literal("hi", 8) + literal("step", 1) + port("in", type) +
                 wire("wire", type) + port("out", type, "output");
    check("earlier-same-iteration-writer",
          setup + loop("ordered", ", %wire, %in, %out", ", " + type + ", " + type + ", " + type,
                       ", %tmp: " + type + ", %input: " + type + ", %target: " + type,
                       first + second),
          true);
    auto early = replace(first, assign("middle", "source"), "") +
                 "%early = \"nodal.digital_expr\"(%middle, %source) <{operator_name = "
                 "\"digital_xor\", metadata = {}}> : (" +
                 u8 + ", " + u8 + ") -> " + u8 + "\n" + assign("middle", "source") +
                 replace(second, assign("dst", "middle"), assign("dst", "early"));
    check("expression-read-before-same-iteration-write",
          setup + loop("ordered", ", %wire, %in, %out", ", " + type + ", " + type + ", " + type,
                       ", %tmp: " + type + ", %input: " + type + ", %target: " + type, early),
          false, "earlier same-iteration");
    auto shared = replace(early, index("dst", "target", "iv", type) + assign("dst", "early"),
                          "%later = \"nodal.digital_expr\"(%middle, %source) <{operator_name = "
                          "\"digital_xor\", metadata = {}}> : (" +
                              u8 + ", " + u8 + ") -> " + u8 +
                              "\n%combined = \"nodal.digital_expr\"(%later, %early) "
                              "<{operator_name = \"digital_xor\", metadata = {}}> : (" +
                              u8 + ", " + u8 + ") -> " + u8 + "\n" +
                              index("dst", "target", "iv", type) + assign("dst", "combined"));
    check("shared-storage-read-occurrences-retain-early-use",
          setup + loop("ordered", ", %wire, %in, %out", ", " + type + ", " + type + ", " + type,
                       ", %tmp: " + type + ", %input: " + type + ", %target: " + type, shared),
          false, "earlier same-iteration");

    auto forward =
        replace(first, assign("middle", "source"), "") + second + assign("middle", "source");
    check("later-same-iteration-writer",
          setup + loop("ordered", ", %wire, %in, %out", ", " + type + ", " + type + ", " + type,
                       ", %tmp: " + type + ", %input: " + type + ", %target: " + type, forward),
          false, "earlier same-iteration");
  }
  {
    auto prefix = symbolicChain();
    auto stop = prefix.find(literal("step", 1));
    prefix = prefix.substr(0, stop);
    prefix = replace(prefix, literal("maximum", 1000000), literal("maximum", 1));
    auto body = assign("target", "input");
    check("possibly-empty-invariant-output-does-not-prove-coverage",
          prefix + literal("step", 1) + port("in", u8) + port("out", u8, "output") +
              loop("scalar", ", %out, %in", ", " + u8 + ", " + u8,
                   ", %target: " + u8 + ", %input: " + u8, body),
          false, "complete physical driver coverage");
  }
  {
    std::string dag = literal("dag0", 0);
    for (unsigned i = 1; i <= 40; ++i)
      dag += "%dag" + std::to_string(i) + " = \"nodal.const_expr\"(%dag" + std::to_string(i - 1) +
             ", %dag" + std::to_string(i - 1) +
             ") <{operator_name = \"add\", metadata = {}}> : (i64, i64) -> i64\n";
    dag += literal("baseOne", 1) + "%one = \"nodal.const_expr\"(%dag40, %baseOne) <{operator_name "
                                   "= \"add\", metadata = {}}> : (i64, i64) -> i64\n";
    check("shared-static-diamond-forty-levels", replace(chain(2), literal("one", 1), dag), true);
  }
  {
    auto setup = literal("lo", 0) + literal("hi", 1) + literal("step", 1) + port("in", u8) +
                 wire("tmp", u8) + port("out", u8, "output");
    auto body = assign("middle", "input") + assign("target", "middle");
    check("one-iteration-invariant-target-ordered-chain",
          setup + loop("scalar", ", %tmp, %in, %out", ", " + u8 + ", " + u8 + ", " + u8,
                       ", %middle: " + u8 + ", %input: " + u8 + ", %target: " + u8, body),
          true);
  }
  check("unknown-version", replace(independent(8), "bounded-digital-v1", "bounded-digital-v2"),
        false);
  check("missing-end", replace(independent(8), "\"nodal.hardware_loop_end\"() : () -> ()", ""),
        false);
  check("legacy-negative",
        "\"nodal.hardware_loop\"() <{induction = \"i\", lower = 3 : i64, upper = 0 : i64, step = "
        "-1 : i64, effect_policy = \"pure\", metadata = {}}> ({^bb0:}) : () -> ()",
        true);
  for (StringRef name : {"nodal.hardware_loop_end", "nodal.iteration_index"}) {
    const auto *descriptor = nodal::lookupBackendOperation(name);
    bool pass = descriptor && descriptor->domain == nodal::BackendOperationDomain::Digital &&
                descriptor->admission == nodal::BackendOperationAdmission::Unsupported;
    ++cases;
    failures += !pass;
    (pass ? llvm::outs() : llvm::errs())
        << "DIGITAL_LOOP_" << (pass ? "PASS " : "FAIL ") << "independent-catalog-" << name << '\n';
  }
  llvm::outs() << "DIGITAL_LOOP_CASES " << cases << " failures=" << failures << '\n';
  return failures ? 1 : 0;
}
