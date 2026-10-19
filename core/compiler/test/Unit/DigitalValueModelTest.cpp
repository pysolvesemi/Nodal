#include "nodal/Dialect/Nodal/DigitalValueModel.h"

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
std::string model(StringRef body) {
  return "module { \"nodal.module\"() <{metadata = {}, sym_name = \"Fixture\"}> ({\n" + body.str() +
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
         ") <{operator_name = \"digital_" + opcode.str() + "\", metadata = {}}> : (" +
         source.str() + ") -> " + result.str() + "\n";
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
  conversion("unknown-closed-operation", "add", "!nodal.uint<8>", "!nodal.uint<8>", false);
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

  llvm::outs() << "DIGITAL_VALUE_MODEL_SUMMARY " << cases - failures << '/' << cases << '\n';
  return failures ? 1 : 0;
}
