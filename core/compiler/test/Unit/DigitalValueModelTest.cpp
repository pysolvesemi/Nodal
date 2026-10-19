#include "nodal/Dialect/Nodal/DigitalValueModel.h"

#include "mlir/IR/Builders.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/SmallString.h"
#include "llvm/Support/raw_ostream.h"

#include <functional>
#include <string>

using namespace mlir;
namespace {
std::string model(StringRef body, StringRef definitions = {}) {
  return "module { " + definitions.str() +
         "\"nodal.module\"() <{metadata = {}, sym_name = \"Fixture\"}> ({\n" + body.str() +
         "}) : () -> () }\n";
}
std::string input(StringRef type, StringRef name = "input") {
  return "\"nodal.port\"() <{sym_name = \"" + name.str() + "\", type = " + type.str() +
         ", direction = \"input\", domain = @none, metadata = {}}> : () -> ()\n%" + name.str() +
         " = \"nodal.port_value\"() <{port = @" + name.str() + ", metadata = {}}> : () -> " +
         type.str() + "\n";
}
std::string literal(StringRef type, StringRef value, StringRef carrier = "i64",
                    StringRef name = "input", bool spelled = true) {
  return "%" + name.str() + " = \"nodal." + (spelled ? "const_literal" : "constant") +
         "\"() <{metadata = {}, value = " + value.str() + " : " + carrier.str() +
         (spelled ? ", spelling = \"" + value.str() + "\"" : "") + "}> : () -> " + type.str() +
         "\n";
}
std::string expression(StringRef opcode, StringRef source, StringRef result,
                       StringRef operand = "%input", StringRef name = "result") {
  return "%" + name.str() + " = \"nodal.digital_expr\"(" + operand.str() +
         ") <{operator_name = \"" + (opcode.starts_with("bool_") ? "" : "digital_") + opcode.str() +
         "\", metadata = {}}> : (" + source.str() + ") -> " + result.str() + "\n";
}
std::string parameter(StringRef name = "W", int64_t lower = 8, int64_t upper = 16,
                      bool constrained = true) {
  std::string result = "\"nodal.parameter\"() <{sym_name = \"" + name.str() +
                       "\", type = i64, default_value = " + std::to_string(lower) +
                       " : i64, classification = \"structural\", parameter_kind = \"integer\", "
                       "variability = \"symbolic\", metadata = {}}> : () -> ()\n";
  if (constrained)
    result += literal("i64", std::to_string(lower), "i64", name.str() + "low") +
              literal("i64", std::to_string(upper), "i64", name.str() + "high") +
              "\"nodal.parameter_constraint\"(%" + name.str() + "low, %" + name.str() +
              "high) <{constraint_kind = \"range\", lower_inclusive = true, "
              "upper_inclusive = true, parameter = @" +
              name.str() + ", metadata = {}}> : (i64, i64) -> ()\n";
  result += "\"nodal.parameter_envelope\"() <{parameter = @" + name.str() +
            ", policy = \"static_generate\", effects = [\"width\"], metadata = {}}> : () -> ()\n%" +
            name.str() + " = \"nodal.const_parameter_ref\"() <{parameter = @" + name.str() +
            ", metadata = {}}> : () -> i64\n";
  return result;
}
std::string symbolic(StringRef kind = "uint", StringRef width = "W") {
  return "!nodal.digital<\"" + kind.str() + "\", \"" + width.str() + "\">";
}
std::string incrementWidth() {
  return literal("i64", "1", "i64", "one") +
         "%plus = \"nodal.const_expr\"(%W, %one) <{operator_name = \"add\", metadata = "
         "{semantic_path = \"Fixture.plus\"}}> : (i64, i64) -> i64\n";
}
std::string decimal(const llvm::APInt &value) {
  llvm::SmallString<512> text;
  value.toString(text, 10, false);
  return text.str().str();
}
std::string print(Operation *operation) {
  std::string text;
  llvm::raw_string_ostream stream(text);
  operation->print(stream);
  return text;
}
std::string replace(std::string text, StringRef before, StringRef after) {
  auto offset = text.find(before.str());
  if (offset == std::string::npos)
    llvm::report_fatal_error("missing test replacement");
  text.replace(offset, before.size(), after.str());
  return text;
}
} // namespace

int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  unsigned cases = 0, failures = 0;
  auto check = [&](StringRef name, const std::string &source, bool expected) {
    ++cases;
    std::string messages;
    ScopedDiagnosticHandler capture(&context, [&](Diagnostic &message) {
      llvm::raw_string_ostream stream(messages);
      message.print(stream);
      return success();
    });
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    bool good = static_cast<bool>(parsed);
    if (parsed) {
      std::string before = print(parsed->getOperation());
      Operation *module = nullptr;
      parsed->walk([&](nodal::ModuleOp operation) {
        if (!module)
          module = operation;
      });
      good &= module && succeeded(nodal::verifyDigitalValueModel(module)) == expected;
      good &= succeeded(verify(*parsed)) == expected;
      good &= before == print(parsed->getOperation());
      if (expected && good) {
        auto again = parseSourceString<mlir::ModuleOp>(before, ParserConfig(&context, false));
        good &= again && succeeded(verify(*again));
        parsed->walk([&](nodal::DigitalExprOp operation) {
          good &= succeeded(nodal::verifyDigitalValueOperation(operation));
        });
      }
    }
    failures += !good;
    (good ? llvm::outs() : llvm::errs())
        << "DIGITAL_VALUE_MODEL_" << (good ? "PASS " : "FAIL ") << name << '\n';
    if (!good)
      llvm::errs() << messages << '\n';
  };
  auto conversion = [&](StringRef name, StringRef opcode, StringRef source, StringRef result,
                        bool expected) {
    check(name, model(input(source) + expression(opcode, source, result)), expected);
  };

  conversion("unsigned-zero-extension", "extend", "!nodal.uint<8>", "!nodal.uint<16>", true);
  conversion("signed-sign-extension", "extend", "!nodal.sint<8>", "!nodal.sint<16>", true);
  conversion("bits-extension", "extend", "!nodal.bits<8>", "!nodal.bits<16>", true);
  conversion("extend-cannot-narrow", "extend", "!nodal.uint<8>", "!nodal.uint<7>", false);
  conversion("extend-cannot-change-kind", "extend", "!nodal.uint<8>", "!nodal.sint<16>", false);
  conversion("truncate-low-bits", "truncate", "!nodal.sint<16>", "!nodal.sint<7>", true);
  conversion("truncate-cannot-widen", "truncate", "!nodal.uint<8>", "!nodal.uint<9>", false);
  conversion("explicit-wrap", "wrap", "!nodal.sint<16>", "!nodal.sint<4>", true);
  conversion("explicit-saturate", "saturate", "!nodal.uint<16>", "!nodal.uint<4>", true);
  conversion("checked-type-range", "resize_checked", "!nodal.sint<8>", "!nodal.sint<9>", true);
  conversion("checked-dynamic-narrowing-rejected", "resize_checked", "!nodal.uint<8>",
             "!nodal.uint<7>", false);
  conversion("numeric-signed-extra-bit", "to_signed", "!nodal.uint<8>", "!nodal.sint<9>", true);
  conversion("numeric-signed-no-extra-bit-rejected", "to_signed", "!nodal.uint<8>",
             "!nodal.sint<8>", false);
  conversion("numeric-signed-bits-rejected", "to_signed", "!nodal.bits<8>", "!nodal.sint<9>",
             false);
  conversion("unknown-sign-rejected", "to_unsigned", "!nodal.sint<8>", "!nodal.uint<8>", false);
  conversion("reinterpret-signed", "reinterpret_signed", "!nodal.uint<8>", "!nodal.sint<8>", true);
  conversion("reinterpret-unsigned", "reinterpret_unsigned", "!nodal.sint<8>", "!nodal.uint<8>",
             true);
  conversion("reinterpret-cannot-resize", "reinterpret_signed", "!nodal.uint<8>", "!nodal.sint<9>",
             false);
  conversion("binary-operation-rejects-unary-arity", "add", "!nodal.uint<8>", "!nodal.uint<8>",
             false);
  conversion("real-rounding-permitted", "to_real", "!nodal.uint<54>", "f64", true);
  conversion("real-all-values-finite", "to_real", "!nodal.uint<1023>", "f64", true);
  conversion("real-potential-infinity-rejected", "to_real", "!nodal.uint<1024>", "f64", false);
  conversion("real-signed-source-rejected", "to_real", "!nodal.sint<8>", "f64", false);
  conversion("real-wrong-result-rejected", "to_real", "!nodal.uint<8>", "f32", false);
  conversion("huge-width-does-not-allocate-bits", "wrap", "!nodal.uint<9223372036854775807>",
             "!nodal.uint<9223372036854775807>", true);
  conversion("signed-width-overflow-rejected", "to_signed", "!nodal.uint<9223372036854775807>",
             "!nodal.sint<9223372036854775807>", false);

  check("checked-positive-exact-fit",
        model(literal("!nodal.uint<64>", "255") +
              expression("resize_checked", "!nodal.uint<64>", "!nodal.uint<8>")),
        true);
  check("checked-negative-exact-fit",
        model(literal("!nodal.sint<64>", "-128") +
              expression("resize_checked", "!nodal.sint<64>", "!nodal.sint<8>")),
        true);
  check("checked-negative-overflow",
        model(literal("!nodal.sint<64>", "-129") +
              expression("resize_checked", "!nodal.sint<64>", "!nodal.sint<8>")),
        false);
  check("numeric-unsigned-nonnegative-literal",
        model(literal("!nodal.sint<8>", "127") +
              expression("to_unsigned", "!nodal.sint<8>", "!nodal.uint<8>")),
        true);
  check("numeric-unsigned-negative-rejected",
        model(literal("!nodal.sint<8>", "-1") +
              expression("to_unsigned", "!nodal.sint<8>", "!nodal.uint<8>")),
        false);
  const auto numeric = input("!nodal.uint<8>") + expression("to_signed", "!nodal.uint<8>",
                                                            "!nodal.sint<9>", "%input", "signed");
  check("numeric-lineage-nonnegative",
        model(numeric + expression("to_unsigned", "!nodal.sint<9>", "!nodal.uint<9>", "%signed")),
        true);
  check("saturate-preserves-nonnegative-lineage",
        model(numeric +
              expression("saturate", "!nodal.sint<9>", "!nodal.sint<7>", "%signed", "small") +
              expression("to_unsigned", "!nodal.sint<7>", "!nodal.uint<7>", "%small")),
        true);
  check("truncate-does-not-preserve-nonnegative",
        model(numeric +
              expression("truncate", "!nodal.sint<9>", "!nodal.sint<7>", "%signed", "small") +
              expression("to_unsigned", "!nodal.sint<7>", "!nodal.uint<7>", "%small")),
        false);
  check("reinterpret-exact-negative-proof",
        model(literal("!nodal.uint<8>", "255") +
              expression("reinterpret_signed", "!nodal.uint<8>", "!nodal.sint<8>", "%input",
                         "signed") +
              expression("resize_checked", "!nodal.sint<8>", "!nodal.sint<1>", "%signed")),
        true);
  check("wrap-exact-proof",
        model(literal("!nodal.sint<16>", "-257") +
              expression("wrap", "!nodal.sint<16>", "!nodal.sint<8>", "%input", "small") +
              expression("resize_checked", "!nodal.sint<8>", "!nodal.sint<1>", "%small")),
        true);
  check("saturate-exact-proof",
        model(literal("!nodal.sint<16>", "-257") +
              expression("saturate", "!nodal.sint<16>", "!nodal.sint<8>", "%input", "small") +
              expression("resize_checked", "!nodal.sint<8>", "!nodal.sint<7>", "%small")),
        false);
  check(
      "wide-literal-finite-real",
      model(literal("!nodal.uint<1024>", "1") + expression("to_real", "!nodal.uint<1024>", "f64")),
      true);
  llvm::APInt maxFinite = llvm::APInt::getAllOnes(1024);
  maxFinite.clearLowBits(971);
  check("maximum-finite-binary64",
        model(literal("!nodal.uint<1024>", decimal(maxFinite), "ui1024") +
              expression("to_real", "!nodal.uint<1024>", "f64")),
        true);
  llvm::APInt overflow = llvm::APInt::getAllOnes(1024);
  overflow.clearLowBits(970);
  check("binary64-rounding-overflow-rejected",
        model(literal("!nodal.uint<1024>", decimal(overflow), "ui1024") +
              expression("to_real", "!nodal.uint<1024>", "f64")),
        false);

  check("signed-literal-minimum", model(literal("!nodal.sint<8>", "-128")), true);
  check("signed-literal-maximum", model(literal("!nodal.sint<8>", "127")), true);
  check("signed-literal-overflow", model(literal("!nodal.sint<8>", "128")), false);
  check("unsigned-negative-rejected", model(literal("!nodal.uint<8>", "-1")), false);
  check("unsigned-carrier-high-bit",
        model(literal("!nodal.uint<64>", "18446744073709551615", "ui64")), true);
  check("signed-result-unsigned-carrier-overflow",
        model(literal("!nodal.sint<64>", "18446744073709551615", "ui64")), false);
  check("bool-true-constant",
        model("%input = \"nodal.constant\"() <{value = true, metadata = {}}> "
              ": () -> !nodal.bits<1>\n"),
        true);
  check("bool-false-constant",
        model("%input = \"nodal.constant\"() <{value = false, metadata = {}}> "
              ": () -> !nodal.bits<1>\n"),
        true);
  check("bool-true-spelled",
        model("%input = \"nodal.const_literal\"() <{value = true, spelling = "
              "\"1\", metadata = {}}> : () -> !nodal.bits<1>\n"),
        true);
  check("literal-spelling-forgery",
        model(replace(literal("!nodal.uint<8>", "7"), "spelling = \"7\"", "spelling = \"8\"")),
        false);
  check("legacy-leading-zero-spelling",
        model(replace(literal("!nodal.uint<8>", "7"), "spelling = \"7\"", "spelling = \"007\"")),
        true);
  check("legacy-negative-zero-spelling",
        model(replace(literal("!nodal.uint<8>", "0"), "spelling = \"0\"", "spelling = \"-0\"")),
        true);
  check("huge-width-small-literal", model(literal("!nodal.uint<9223372036854775807>", "0")), true);

  check("symbolic-literal-lower-bound", model(parameter() + literal(symbolic(), "255")), true);
  check("symbolic-literal-default-is-not-bound", model(parameter() + literal(symbolic(), "256")),
        false);
  check("symbolic-literal-no-envelope",
        model(parameter("W", 8, 16, false) + literal(symbolic(), "1")), false);
  check(
      "symbolic-extension-maximum-bound",
      model(parameter() + input(symbolic()) + expression("extend", symbolic(), "!nodal.uint<16>")),
      true);
  check("symbolic-extension-cannot-use-minimum",
        model(parameter() + input(symbolic()) + expression("extend", symbolic(), "!nodal.uint<8>")),
        false);
  check("symbolic-checked-literal-proof",
        model(parameter() + literal(symbolic(), "127") +
              expression("resize_checked", symbolic(), "!nodal.uint<7>")),
        true);
  check("symbolic-reinterpret-exact-identity",
        model(parameter() + input(symbolic()) +
              expression("reinterpret_signed", symbolic(), symbolic("sint"))),
        true);
  check("equal-ranges-not-width-identity",
        model(parameter() + parameter("V") + input(symbolic()) +
              expression("reinterpret_signed", symbolic(), symbolic("sint", "V"))),
        false);
  check("symbolic-to-signed-exact-plus-one",
        model(parameter() + incrementWidth() + input(symbolic()) +
              expression("to_signed", symbolic(), symbolic("sint", "Fixture.plus"))),
        true);
  check("plus-one-bounds-not-identity",
        model(parameter() + parameter("V", 9, 17) + input(symbolic()) +
              expression("to_signed", symbolic(), symbolic("sint", "V"))),
        false);
  check("symbolic-numeric-round-trip",
        model(parameter() + incrementWidth() + input(symbolic()) +
              expression("to_signed", symbolic(), symbolic("sint", "Fixture.plus"), "%input",
                         "signed") +
              expression("to_unsigned", symbolic("sint", "Fixture.plus"),
                         symbolic("uint", "Fixture.plus"), "%signed")),
        true);

  check("positive-parameter-default-is-not-nonnegative-proof",
        model("\"nodal.parameter\"() <{sym_name = \"data\", type = !nodal.sint<8>, "
              "default_value = 0 : i64, classification = \"ordinary\", parameter_kind = "
              "\"integer\", variability = \"symbolic\", metadata = {}}> : () -> ()\n"
              "%input = \"nodal.const_parameter_ref\"() <{parameter = @data, metadata = {}}> "
              ": () -> !nodal.sint<8>\n" +
              expression("to_unsigned", "!nodal.sint<8>", "!nodal.uint<8>")),
        false);

  const auto unary =
      input("!nodal.uint<8>") + expression("extend", "!nodal.uint<8>", "!nodal.uint<16>");
  check("zero-operands-rejected",
        model(replace(replace(unary, "digital_expr\"(%input)", "digital_expr\"()"),
                      ": (!nodal.uint<8>) ->", ": () ->")),
        false);
  check("zero-results-rejected-independently",
        model(replace(replace(unary, "%result = \"nodal.digital_expr", "\"nodal.digital_expr"),
                      ") -> !nodal.uint<16>", ") -> ()")),
        false);
  check("two-operands-rejected",
        model(replace(replace(unary, "digital_expr\"(%input)", "digital_expr\"(%input, %input)"),
                      ": (!nodal.uint<8>) ->", ": (!nodal.uint<8>, !nodal.uint<8>) ->")),
        false);
  check("foreign-root-value-rejected",
        "module { " + literal("!nodal.uint<8>", "1") +
            "\"nodal.module\"() <{metadata = {}, sym_name = \"Fixture\"}> ({" +
            expression("extend", "!nodal.uint<8>", "!nodal.uint<16>") + "}) : () -> () }",
        false);
  std::string deep = input("!nodal.uint<8>");
  for (unsigned index = 0; index < 130; ++index)
    deep += expression("wrap", "!nodal.uint<8>", "!nodal.uint<8>",
                       index ? "%step" + std::to_string(index - 1) : "%input",
                       "step" + std::to_string(index));
  check("bounded-expression-depth", model(deep), false);
  std::string boundary = input("!nodal.uint<8>");
  for (unsigned index = 0; index < 128; ++index)
    boundary += expression("wrap", "!nodal.uint<8>", "!nodal.uint<8>",
                           index ? "%step" + std::to_string(index - 1) : "%input",
                           "step" + std::to_string(index));
  check("expression-depth-boundary", model(boundary), true);

  auto binary = [&](StringRef name, StringRef opcode, StringRef left, StringRef right,
                    StringRef result, bool expected) {
    check(name,
          model(input(left) + input(right, "right") +
                expression(opcode, left.str() + ", " + right.str(), result, "%input, %right")),
          expected);
  };
  binary("unsigned-lossless-add", "add", "!nodal.uint<8>", "!nodal.uint<12>", "!nodal.uint<13>",
         true);
  binary("signed-lossless-add", "add", "!nodal.sint<8>", "!nodal.sint<12>", "!nodal.sint<13>",
         true);
  binary("unsigned-subtraction-is-signed", "sub", "!nodal.uint<8>", "!nodal.uint<12>",
         "!nodal.sint<13>", true);
  binary("signed-subtraction", "sub", "!nodal.sint<8>", "!nodal.sint<12>", "!nodal.sint<13>", true);
  binary("unsigned-subtraction-kind-forgery", "sub", "!nodal.uint<8>", "!nodal.uint<12>",
         "!nodal.uint<13>", false);
  binary("add-cannot-narrow", "add", "!nodal.uint<8>", "!nodal.uint<12>", "!nodal.uint<12>", false);
  binary("mixed-numeric-kinds-rejected", "add", "!nodal.uint<8>", "!nodal.sint<8>",
         "!nodal.sint<9>", false);
  binary("bits-not-numeric", "add", "!nodal.bits<8>", "!nodal.bits<8>", "!nodal.bits<9>", false);
  binary("multiply-width-sum", "mul", "!nodal.uint<8>", "!nodal.uint<12>", "!nodal.uint<20>", true);
  binary("multiply-signed", "mul", "!nodal.sint<8>", "!nodal.sint<12>", "!nodal.sint<20>", true);
  conversion("unsigned-negation", "neg", "!nodal.uint<8>", "!nodal.sint<9>", true);
  conversion("signed-negation", "neg", "!nodal.sint<8>", "!nodal.sint<9>", true);
  conversion("negation-width-forgery", "neg", "!nodal.sint<8>", "!nodal.sint<8>", false);
  for (StringRef opcode : {"eq", "ne", "lt", "le", "gt", "ge"}) {
    binary("numeric-comparison-" + opcode.str(), opcode, "!nodal.sint<8>", "!nodal.sint<12>",
           "!nodal.bits<1>", true);
    binary("mixed-comparison-" + opcode.str(), opcode, "!nodal.uint<8>", "!nodal.sint<12>",
           "!nodal.bits<1>", false);
  }
  binary("bits-exact-equality", "eq", "!nodal.bits<8>", "!nodal.bits<8>", "!nodal.bits<1>", true);
  binary("bits-width-equality-rejected", "eq", "!nodal.bits<8>", "!nodal.bits<9>", "!nodal.bits<1>",
         false);
  binary("bits-order-rejected", "lt", "!nodal.bits<8>", "!nodal.bits<8>", "!nodal.bits<1>", false);
  binary("comparison-builtin-bool-rejected", "eq", "!nodal.uint<8>", "!nodal.uint<8>", "i1", false);
  for (StringRef opcode : {"and", "or", "xor"}) {
    binary("bitwise-signed-" + opcode.str(), opcode, "!nodal.sint<8>", "!nodal.sint<8>",
           "!nodal.sint<8>", true);
    binary("bitwise-width-forgery-" + opcode.str(), opcode, "!nodal.bits<8>", "!nodal.bits<9>",
           "!nodal.bits<9>", false);
  }
  conversion("bitwise-complement", "not", "!nodal.bits<8>", "!nodal.bits<8>", true);
  binary("bool-and", "bool_and", "!nodal.bits<1>", "!nodal.bits<1>", "!nodal.bits<1>", true);
  binary("bool-or", "bool_or", "!nodal.bits<1>", "!nodal.bits<1>", "!nodal.bits<1>", true);
  conversion("bool-not", "bool_not", "!nodal.bits<1>", "!nodal.bits<1>", true);
  binary("bool-wide-rejected", "bool_and", "!nodal.bits<8>", "!nodal.bits<8>", "!nodal.bits<8>",
         false);
  binary("concat-mixed-scalar-kinds", "concat", "!nodal.sint<8>", "!nodal.uint<12>",
         "!nodal.bits<20>", true);
  binary("concat-result-kind-forgery", "concat", "!nodal.bits<8>", "!nodal.bits<12>",
         "!nodal.uint<20>", false);
  for (StringRef opcode : {"div", "mod", "dynamic_shl"})
    binary("unsupported-" + opcode.str(), opcode, "!nodal.uint<8>", "!nodal.uint<8>",
           "!nodal.uint<8>", false);

  auto shift = [&](StringRef name, StringRef opcode, StringRef type, StringRef result,
                   StringRef amount, bool expected) {
    check(name,
          model(input(type) + literal("!nodal.uint<63>", amount, "i64", "amount") +
                expression(opcode, type.str() + ", !nodal.uint<63>", result, "%input, %amount")),
          expected);
  };
  shift("left-shift-grows-bits", "shl", "!nodal.bits<8>", "!nodal.bits<11>", "3", true);
  shift("left-shift-zero", "shl", "!nodal.uint<8>", "!nodal.uint<8>", "0", true);
  shift("left-shift-cannot-truncate", "shl", "!nodal.sint<8>", "!nodal.sint<8>", "3", false);
  shift("signed-right-shift", "shr", "!nodal.sint<8>", "!nodal.sint<8>", "3", true);
  shift("signed-logical-right-shift", "logical_shr", "!nodal.sint<8>", "!nodal.sint<8>", "3", true);
  shift("huge-right-shift-no-allocation", "shr", "!nodal.sint<8>", "!nodal.sint<8>",
        "9223372036854775807", true);
  shift("shift-width-overflow", "shl", "!nodal.uint<8>", "!nodal.uint<8>", "9223372036854775807",
        false);
  binary("runtime-shift-amount-rejected", "shl", "!nodal.uint<8>", "!nodal.uint<8>",
         "!nodal.uint<9>", false);
  check("folded-hardware-shift-amount-is-not-static",
        model(input("!nodal.uint<8>") + literal("!nodal.uint<1>", "1", "i64", "amount") +
              expression("wrap", "!nodal.uint<1>", "!nodal.uint<1>", "%amount", "computed") +
              expression("shl", "!nodal.uint<8>, !nodal.uint<1>", "!nodal.uint<9>",
                         "%input, %computed")),
        false);

  auto extract = [&](StringRef name, StringRef lower, StringRef upper, StringRef result,
                     bool expected) {
    check(name,
          model(input("!nodal.sint<8>") + literal("!nodal.uint<8>", lower, "i64", "lower") +
                literal("!nodal.uint<8>", upper, "i64", "upper") +
                expression("extract", "!nodal.sint<8>, !nodal.uint<8>, !nodal.uint<8>", result,
                           "%input, %lower, %upper")),
          expected);
  };
  extract("extract-half-open", "2", "6", "!nodal.bits<4>", true);
  extract("extract-whole", "0", "8", "!nodal.bits<8>", true);
  extract("extract-empty", "2", "2", "!nodal.bits<1>", false);
  extract("extract-outside", "6", "9", "!nodal.bits<3>", false);
  extract("extract-reversed", "6", "2", "!nodal.bits<4>", false);
  extract("extract-width-forgery", "2", "6", "!nodal.bits<6>", false);

  auto select = [&](StringRef name, StringRef predicate, StringRef left, StringRef right,
                    StringRef result, bool expected) {
    check(name,
          model(input(predicate, "predicate") + input(left) + input(right, "right") +
                expression("select", predicate.str() + ", " + left.str() + ", " + right.str(),
                           result, "%predicate, %input, %right")),
          expected);
  };
  select("scalar-select", "!nodal.bits<1>", "!nodal.sint<8>", "!nodal.sint<8>", "!nodal.sint<8>",
         true);
  select("select-branch-type-forgery", "!nodal.bits<1>", "!nodal.sint<8>", "!nodal.uint<8>",
         "!nodal.sint<8>", false);
  select("select-predicate-kind-forgery", "!nodal.uint<1>", "!nodal.uint<8>", "!nodal.uint<8>",
         "!nodal.uint<8>", false);
  const std::string vectorType = "!nodal.shaped<\"4\", !nodal.sint<8>>";
  select("shaped-select", "!nodal.bits<1>", vectorType, vectorType, vectorType, true);
  const std::string structType =
      "!nodal.struct<\"Packet\", [{name = \"data\", type = !nodal.uint<8>}]>";
  select("struct-select", "!nodal.bits<1>", structType, structType, structType, true);
  select("scalar-real-select-excluded", "!nodal.bits<1>", "f64", "f64", "f64", false);
  select("scalar-integer-select-excluded", "!nodal.bits<1>", "i64", "i64", "i64", false);
  const std::string enumType = "!nodal.enum<\"Mode\", 2>";
  const std::string enumDefinition =
      "\"nodal.enum\"() <{sym_name = \"Mode\", underlying_type = !nodal.uint<2>, "
      "encoding = \"custom\", metadata = {abi_version = 1 : i64, semantic_type = \"Mode\", "
      "abi_hash = \"df5b4a51f3fade96f1ab8a106bbcb0114b5d4601e4e2c6aebcf38763dd61e66a\"}}> ({"
      "\"nodal.enum_case\"() <{sym_name = \"Idle\", value = 0 : i64, "
      "metadata = {case_id = \"Mode.Idle\"}}> : () -> () "
      "\"nodal.enum_case\"() <{sym_name = \"Run\", value = 1 : i64, "
      "metadata = {case_id = \"Mode.Run\"}}> : () -> () "
      "}) : () -> ()\n";
  check("enum-select",
        model(input("!nodal.bits<1>", "predicate") + input(enumType) + input(enumType, "right") +
                  expression("select", "!nodal.bits<1>, " + enumType + ", " + enumType, enumType,
                             "%predicate, %input, %right"),
              enumDefinition),
        true);

  const std::string sparseType = "!nodal.enum<\"Mode\", 3>";
  std::string sparseDefinition = replace(enumDefinition, "!nodal.uint<2>", "!nodal.uint<3>");
  sparseDefinition = replace(sparseDefinition, "value = 1 : i64", "value = 3 : i64");
  sparseDefinition = replace(sparseDefinition, "}) : () -> ()",
                             "\"nodal.enum_case\"() <{sym_name = \"Stop\", value = 7 : i64, "
                             "metadata = {case_id = \"Mode.Stop\"}}> : () -> () }) : () -> ()");
  sparseDefinition =
      replace(sparseDefinition, "df5b4a51f3fade96f1ab8a106bbcb0114b5d4601e4e2c6aebcf38763dd61e66a",
              "d06a339707813bfffb47e9e4bbdff53e7bca8a0c8a220786446a4427eaf0f9d6");
  for (StringRef opcode : {"xor", "and", "or", "concat"})
    check(
        "enum-" + opcode.str() + "-rejected",
        model(input(sparseType) + input(sparseType, "right") +
                  expression(opcode, sparseType + ", " + sparseType, sparseType, "%input, %right"),
              sparseDefinition),
        false);
  for (StringRef opcode : {"not", "wrap", "saturate", "extend", "truncate", "resize_checked"})
    check("enum-" + opcode.str() + "-rejected",
          model(input(sparseType) + expression(opcode, sparseType, sparseType), sparseDefinition),
          false);
  for (StringRef opcode : {"shl", "shr", "logical_shr"})
    check("enum-" + opcode.str() + "-rejected",
          model(input(sparseType) + literal("!nodal.uint<1>", "0", "i64", "amount") +
                    expression(opcode, sparseType + ", !nodal.uint<1>", sparseType,
                               "%input, %amount"),
                sparseDefinition),
          false);
  check(
      "enum-expression-select",
      model(input("!nodal.bits<1>", "predicate") + input(sparseType, "right") +
                "%input = \"nodal.enum_expr\"() <{operator_name = \"enum_literal\", "
                "enum_ref = @Mode, case_ids = [\"Mode.Run\"], metadata = {abi_version = 1 : i64, "
                "semantic_type = \"Mode\", abi_hash = "
                "\"d06a339707813bfffb47e9e4bbdff53e7bca8a0c8a220786446a4427eaf0f9d6\"}}> : () -> " +
                sparseType + "\n" +
                expression("select", "!nodal.bits<1>, " + sparseType + ", " + sparseType,
                           sparseType, "%predicate, %input, %right"),
            sparseDefinition),
      true);
  for (bool spelled : {false, true}) {
    check(spelled ? "enum-literal-select" : "enum-constant-select",
          model(input("!nodal.bits<1>", "predicate") +
                    literal(sparseType, "0", "i64", "input", spelled) +
                    literal(sparseType, "3", "i64", "right", spelled) +
                    expression("select", "!nodal.bits<1>, " + sparseType + ", " + sparseType,
                               sparseType, "%predicate, %input, %right"),
                sparseDefinition),
          true);
    check(spelled ? "invalid-enum-literal-select" : "invalid-enum-constant-select",
          model(input("!nodal.bits<1>", "predicate") +
                    literal(sparseType, "2", "i64", "input", spelled) +
                    literal(sparseType, "3", "i64", "right", spelled) +
                    expression("select", "!nodal.bits<1>, " + sparseType + ", " + sparseType,
                               sparseType, "%predicate, %input, %right"),
                sparseDefinition),
          false);
  }

  // Exact value proofs are observed through an independently authored expected
  // literal and a checked resize of a select: false equality selects 256, which
  // cannot fit one bit. No test mutates or specializes the expression graph.
  auto exact = [&](StringRef name, StringRef body, StringRef type, StringRef expectedValue,
                   bool expected = true) {
    check(name,
          model(body.str() + literal(type, expectedValue, "i64", "expected") +
                expression("eq", type.str() + ", " + type.str(), "!nodal.bits<1>",
                           "%result, %expected", "equal") +
                literal("!nodal.uint<9>", "1", "i64", "yes") +
                literal("!nodal.uint<9>", "256", "i64", "no") +
                expression("select", "!nodal.bits<1>, !nodal.uint<9>, !nodal.uint<9>",
                           "!nodal.uint<9>", "%equal, %yes, %no", "selected") +
                expression("resize_checked", "!nodal.uint<9>", "!nodal.uint<1>", "%selected",
                           "checked")),
          expected);
  };
  auto exactBinary = [&](StringRef name, StringRef opcode, StringRef leftValue,
                         StringRef rightValue, StringRef type, StringRef result,
                         StringRef expectedValue) {
    exact(name,
          literal(type, leftValue) + literal(type, rightValue, "i64", "right") +
              expression(opcode, type.str() + ", " + type.str(), result, "%input, %right"),
          result, expectedValue);
  };
  exactBinary("constant-unsigned-carry", "add", "255", "255", "!nodal.uint<8>", "!nodal.uint<9>",
              "510");
  exactBinary("constant-unsigned-negative-subtraction", "sub", "0", "255", "!nodal.uint<8>",
              "!nodal.sint<9>", "-255");
  exactBinary("constant-signed-extrema-add", "add", "-128", "-128", "!nodal.sint<8>",
              "!nodal.sint<9>", "-256");
  exactBinary("constant-signed-multiply", "mul", "-128", "-128", "!nodal.sint<8>",
              "!nodal.sint<16>", "16384");
  exact("constant-negate-minimum",
        literal("!nodal.sint<8>", "-128") + expression("neg", "!nodal.sint<8>", "!nodal.sint<9>"),
        "!nodal.sint<9>", "128");
  exactBinary("constant-signed-and", "and", "-1", "7", "!nodal.sint<8>", "!nodal.sint<8>", "7");
  exactBinary("constant-signed-or", "or", "-128", "7", "!nodal.sint<8>", "!nodal.sint<8>", "-121");
  exactBinary("constant-signed-xor", "xor", "-1", "7", "!nodal.sint<8>", "!nodal.sint<8>", "-8");
  exactBinary("constant-bool-and", "bool_and", "1", "0", "!nodal.bits<1>", "!nodal.bits<1>", "0");
  exactBinary("constant-bool-or", "bool_or", "0", "1", "!nodal.bits<1>", "!nodal.bits<1>", "1");
  exact("constant-bool-not",
        literal("!nodal.bits<1>", "1") + expression("bool_not", "!nodal.bits<1>", "!nodal.bits<1>"),
        "!nodal.bits<1>", "0");
  exact("constant-unsigned-not",
        literal("!nodal.uint<8>", "1") + expression("not", "!nodal.uint<8>", "!nodal.uint<8>"),
        "!nodal.uint<8>", "254");
  exact("constant-signed-not",
        literal("!nodal.sint<8>", "-128") + expression("not", "!nodal.sint<8>", "!nodal.sint<8>"),
        "!nodal.sint<8>", "127");
  for (StringRef opcode : {"eq", "ne", "lt", "le", "gt", "ge"})
    exactBinary("constant-compare-" + opcode.str(), opcode, "-128", "127", "!nodal.sint<8>",
                "!nodal.bits<1>", opcode == "ne" || opcode == "lt" || opcode == "le" ? "1" : "0");
  exactBinary("constant-concat-encoded-signed", "concat", "-1", "1", "!nodal.sint<8>",
              "!nodal.bits<16>", "65281");
  for (StringRef opcode : {"shl", "shr", "logical_shr"}) {
    StringRef type = opcode == "shl" ? "!nodal.sint<11>" : "!nodal.sint<8>";
    exact("constant-shift-" + opcode.str(),
          literal("!nodal.sint<8>", "-128") + literal("!nodal.uint<2>", "3", "i64", "amount") +
              expression(opcode, "!nodal.sint<8>, !nodal.uint<2>", type, "%input, %amount"),
          type,
          opcode == "shl"   ? "-1024"
          : opcode == "shr" ? "-16"
                            : "16");
  }
  exact("constant-extract-negative",
        literal("!nodal.sint<8>", "-8") + literal("!nodal.uint<4>", "2", "i64", "lower") +
            literal("!nodal.uint<4>", "6", "i64", "upper") +
            expression("extract", "!nodal.sint<8>, !nodal.uint<4>, !nodal.uint<4>",
                       "!nodal.bits<4>", "%input, %lower, %upper"),
        "!nodal.bits<4>", "14");
  exact("constant-proof-negative-control",
        literal("!nodal.uint<8>", "7") + expression("not", "!nodal.uint<8>", "!nodal.uint<8>"),
        "!nodal.uint<8>", "7", false);

  const std::string maximumWidth =
      parameter() + parameter("V", 4, 24) +
      "%maximum = \"nodal.const_expr\"(%W, %V) <{operator_name = \"max\", metadata = "
      "{semantic_path = \"Fixture.maximum\"}}> : (i64, i64) -> i64\n" +
      literal("i64", "1", "i64", "one") +
      "%sumWidth = \"nodal.const_expr\"(%maximum, %one) <{operator_name = \"add\", metadata = "
      "{semantic_path = \"Fixture.sumWidth\"}}> : (i64, i64) -> i64\n";
  check("symbolic-max-plus-one-identity",
        model(maximumWidth + input(symbolic()) + input(symbolic("uint", "V"), "right") +
              expression("add", symbolic() + ", " + symbolic("uint", "V"),
                         symbolic("uint", "Fixture.sumWidth"), "%input, %right")),
        true);
  check("same-symbolic-width-add-uses-manual-plus-one",
        model(parameter() + incrementWidth() + input(symbolic()) + input(symbolic(), "right") +
              expression("add", symbolic() + ", " + symbolic(), symbolic("uint", "Fixture.plus"),
                         "%input, %right")),
        true);
  check("symbolic-max-default-is-not-width",
        model(maximumWidth + input(symbolic()) + input(symbolic("uint", "V"), "right") +
              expression("add", symbolic() + ", " + symbolic("uint", "V"), "!nodal.uint<9>",
                         "%input, %right")),
        false);
  check("symbolic-max-equal-envelope-is-not-identity",
        model(maximumWidth + parameter("Fake", 9, 25) + input(symbolic()) +
              input(symbolic("uint", "V"), "right") +
              expression("add", symbolic() + ", " + symbolic("uint", "V"), symbolic("uint", "Fake"),
                         "%input, %right")),
        false);
  const std::string freshMaximumWidth =
      maximumWidth +
      "%maximum2 = \"nodal.const_expr\"(%W, %V) <{operator_name = \"max\", metadata = "
      "{semantic_path = \"Fixture.maximum2\"}}> : (i64, i64) -> i64\n"
      "%sumWidth2 = \"nodal.const_expr\"(%maximum2, %one) <{operator_name = \"add\", metadata = "
      "{semantic_path = \"Fixture.sumWidth2\"}}> : (i64, i64) -> i64\n";
  check("select-fresh-equivalent-symbolic-arithmetic-types",
        model(freshMaximumWidth + input("!nodal.bits<1>", "predicate") + input(symbolic()) +
              input(symbolic("uint", "V"), "right") +
              expression("add", symbolic() + ", " + symbolic("uint", "V"),
                         symbolic("uint", "Fixture.sumWidth"), "%input, %right", "sum1") +
              expression("add", symbolic() + ", " + symbolic("uint", "V"),
                         symbolic("uint", "Fixture.sumWidth2"), "%input, %right", "sum2") +
              expression("select",
                         "!nodal.bits<1>, " + symbolic("uint", "Fixture.sumWidth") + ", " +
                             symbolic("uint", "Fixture.sumWidth2"),
                         symbolic("uint", "Fixture.sumWidth"), "%predicate, %sum1, %sum2")),
        true);
  const std::string nestedFirst =
      "!nodal.shaped<\"2\", !nodal.struct<\"Packet\", [{name = \"data\", type = " +
      symbolic("uint", "Fixture.sumWidth") + "}]>>";
  const std::string nestedSecond =
      "!nodal.shaped<\"2\", !nodal.struct<\"Packet\", [{name = \"data\", type = " +
      symbolic("uint", "Fixture.sumWidth2") + "}]>>";
  check("select-fresh-equivalent-aggregate-widths",
        model(freshMaximumWidth + input("!nodal.bits<1>", "predicate") + input(nestedFirst) +
              input(nestedSecond, "right") +
              expression("select", "!nodal.bits<1>, " + nestedFirst + ", " + nestedSecond,
                         nestedFirst, "%predicate, %input, %right")),
        true);
  check("symbolic-extract-must-fit-minimum",
        model(parameter() + input(symbolic()) + literal("!nodal.uint<8>", "0", "i64", "lower") +
              literal("!nodal.uint<8>", "9", "i64", "upper") +
              expression("extract", symbolic() + ", !nodal.uint<8>, !nodal.uint<8>",
                         "!nodal.bits<9>", "%input, %lower, %upper")),
        false);
  check("symbolic-left-shift-zero-retains-identity",
        model(parameter() + input(symbolic()) + literal("!nodal.uint<1>", "0", "i64", "amount") +
              expression("shl", symbolic() + ", !nodal.uint<1>", symbolic(), "%input, %amount")),
        true);
  binary("addition-width-overflow", "add", "!nodal.uint<9223372036854775807>", "!nodal.uint<1>",
         "!nodal.uint<9223372036854775807>", false);
  binary("concatenation-width-overflow", "concat", "!nodal.bits<9223372036854775807>",
         "!nodal.bits<1>", "!nodal.bits<9223372036854775807>", false);
  binary("huge-width-bitwise-no-allocation", "and", "!nodal.bits<9223372036854775807>",
         "!nodal.bits<9223372036854775807>", "!nodal.bits<9223372036854775807>", true);

  auto proofMagnitude = [&](StringRef amount, StringRef resultType, StringRef checkedType) {
    return model(
        literal("!nodal.uint<4096>", decimal(llvm::APInt::getAllOnes(4096)), "ui4096") +
        literal("!nodal.uint<2>", amount, "i64", "right") +
        expression("mul", "!nodal.uint<4096>, !nodal.uint<2>", resultType, "%input, %right") +
        expression("resize_checked", resultType, checkedType, "%result", "checked"));
  };
  check("constant-magnitude-proof-boundary",
        proofMagnitude("1", "!nodal.uint<4098>", "!nodal.uint<4096>"), true);
  check("constant-magnitude-budget-fails-closed",
        proofMagnitude("2", "!nodal.uint<4098>", "!nodal.uint<4097>"), false);
  check("concat-bounded-temporary-small-magnitude",
        model(literal("!nodal.uint<4096>", "0") +
              literal("!nodal.uint<4096>", "0", "i64", "right") +
              expression("concat", "!nodal.uint<4096>, !nodal.uint<4096>", "!nodal.bits<8192>",
                         "%input, %right") +
              expression("resize_checked", "!nodal.bits<8192>", "!nodal.bits<1>", "%result",
                         "checked")),
        true);
  check("foreign-second-operand-rejected",
        "module { " + literal("!nodal.uint<8>", "1", "i64", "foreign") +
            "\"nodal.module\"() <{metadata = {}, sym_name = \"Fixture\"}> ({" +
            input("!nodal.uint<8>") +
            expression("add", "!nodal.uint<8>, !nodal.uint<8>", "!nodal.uint<9>",
                       "%input, %foreign") +
            "}) : () -> () }",
        false);
  check("symbolic-bitwise-equal-ranges-not-identity",
        model(parameter() + parameter("V") + input(symbolic()) +
              input(symbolic("uint", "V"), "right") +
              expression("and", symbolic() + ", " + symbolic("uint", "V"), symbolic(),
                         "%input, %right")),
        false);
  check("symbolic-select-exact-complete-type",
        model(parameter() + parameter("V") + input("!nodal.bits<1>", "predicate") +
              input(symbolic()) + input(symbolic("uint", "V"), "right") +
              expression("select", "!nodal.bits<1>, " + symbolic() + ", " + symbolic("uint", "V"),
                         symbolic(), "%predicate, %input, %right")),
        false);

  std::string shared = input("!nodal.bits<8>");
  for (unsigned index = 0; index < 128; ++index) {
    std::string previous = index ? "%join" + std::to_string(index - 1) : "%input";
    shared += expression("and", "!nodal.bits<8>, !nodal.bits<8>", "!nodal.bits<8>",
                         previous + ", " + previous, "join" + std::to_string(index));
  }
  check("shared-binary-DAG-depth-boundary", model(shared), true);
  check("shared-binary-DAG-depth-overflow",
        model(shared + expression("or", "!nodal.bits<8>, !nodal.bits<8>", "!nodal.bits<8>",
                                  "%join127, %input", "tooDeep")),
        false);

  // A wide fanout must count standalone roots as well as recursively reached
  // operands. Construct it in memory to avoid a large text fixture/roundtrip.
  {
    ScopedDiagnosticHandler capture(&context, [](Diagnostic &) { return success(); });
    auto parsed = parseSourceString<mlir::ModuleOp>(model(literal("!nodal.bits<1>", "1")),
                                                    ParserConfig(&context, false));
    Operation *module = &parsed->getBody()->front();
    Block &body = module->getRegion(0).front();
    Value input = body.front().getResult(0);
    OpBuilder builder(&context);
    builder.setInsertionPointToEnd(&body);
    auto append = [&]() {
      OperationState state(builder.getUnknownLoc(), "nodal.digital_expr");
      state.addOperands({input, input});
      state.addTypes(input.getType());
      state.addAttribute("operator_name", builder.getStringAttr("digital_and"));
      state.addAttribute("metadata", builder.getDictionaryAttr({}));
      builder.create(state);
    };
    for (unsigned index = 0; index < 65535; ++index)
      append();
    bool boundary = succeeded(nodal::verifyDigitalValueModel(module));
    append();
    bool overflow = failed(nodal::verifyDigitalValueModel(module));
    cases += 2;
    failures += !boundary + !overflow;
    (boundary ? llvm::outs() : llvm::errs())
        << "DIGITAL_VALUE_MODEL_" << (boundary ? "PASS " : "FAIL ")
        << "standalone-fanout-node-budget-boundary\n";
    (overflow ? llvm::outs() : llvm::errs())
        << "DIGITAL_VALUE_MODEL_" << (overflow ? "PASS " : "FAIL ")
        << "standalone-fanout-node-budget-overflow\n";
  }

  llvm::outs() << "DIGITAL_VALUE_MODEL_SUMMARY " << cases - failures << '/' << cases << '\n';
  return failures ? 1 : 0;
}
