#include "nodal/Dialect/Nodal/EnumValueModel.h"

#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ParameterModel.h"

#include "llvm/Support/raw_ostream.h"

#include <functional>
#include <string>

using namespace mlir;
namespace {
constexpr StringLiteral modeHash =
    "cb807326542df7db5314f8d795ea4384c2b40209497084c69a41f9ccd9faf46b";
constexpr StringLiteral otherHash =
    "4e0eb020ed6b4e6df6efbaac27a4cda7d0b0ea5e26ebec9d8f0c0f71ad4a1cde";
constexpr StringLiteral wideHash =
    "95015d78e2d766770445bf95bce42243a1a2fc2a1f535041fab185cfbd03f298";

std::string metadata(StringRef identity = "example.Mode", StringRef hash = modeHash) {
  return "{abi_version = 1 : i64, semantic_type = \"" + identity.str() + "\", abi_hash = \"" +
         hash.str() + "\"}";
}
std::string enumCase(StringRef name, StringRef code, StringRef identity = "example.Mode",
                     StringRef carrier = "i64") {
  return "\"nodal.enum_case\"() <{sym_name = \"" + name.str() + "\", value = " + code.str() +
         " : " + carrier.str() + ", metadata = {case_id = \"" + identity.str() + "." + name.str() +
         "\"}}> : () -> ()\n";
}
std::string definition(StringRef symbol = "Mode", StringRef identity = "example.Mode",
                       StringRef hash = modeHash, int width = 3, StringRef body = {}) {
  std::string cases = body.empty()
                          ? enumCase("Idle", "0", identity) + enumCase("Run", "3", identity) +
                                enumCase("Stop", "7", identity)
                          : body.str();
  return "\"nodal.enum\"() <{sym_name = \"" + symbol.str() + "\", underlying_type = !nodal.uint<" +
         std::to_string(width) +
         ">, encoding = \"custom\", metadata = " + metadata(identity, hash) + "}> ({\n" + cases +
         "}) : () -> ()\n";
}
std::string type(StringRef symbol = "Mode", int width = 3) {
  return "!nodal.enum<\"" + symbol.str() + "\", " + std::to_string(width) + ">";
}
std::string model(StringRef body, StringRef definitions = {}) {
  return "module { " + (definitions.empty() ? definition() : definitions.str()) +
         "\"nodal.module\"() <{metadata = {}, sym_name = \"Fixture\"}> ({\n" + body.str() +
         "}) : () -> () }\n";
}
std::string input(StringRef resultType = "!nodal.bits<3>", StringRef name = "bits") {
  return "\"nodal.port\"() <{sym_name = \"" + name.str() + "\", type = " + resultType.str() +
         ", direction = \"input\", domain = @none, metadata = {}}> : () -> ()\n%" + name.str() +
         " = \"nodal.port_value\"() <{port = @" + name.str() + ", metadata = {}}> : () -> " +
         resultType.str() + "\n";
}
std::string expression(StringRef name, StringRef opcode, StringRef operands, StringRef operandTypes,
                       StringRef resultType, StringRef ids = "[]", StringRef symbol = "Mode",
                       StringRef identity = "example.Mode", StringRef hash = modeHash) {
  return "%" + name.str() + " = \"nodal.enum_expr\"(" + operands.str() +
         ") <{operator_name = \"enum_" + opcode.str() + "\", enum_ref = @" + symbol.str() +
         ", case_ids = " + ids.str() + ", metadata = " + metadata(identity, hash) + "}> : (" +
         operandTypes.str() + ") -> " + resultType.str() + "\n";
}
std::string literal(StringRef name = "idle", StringRef id = "example.Mode.Idle") {
  return expression(name, "literal", "", "", type(), "[\"" + id.str() + "\"]");
}
std::string parameter(StringRef code, StringRef carrier = "i64", StringRef enumType = {}) {
  return "\"nodal.parameter\"() <{sym_name = \"initial\", type = " +
         (enumType.empty() ? type() : enumType.str()) + ", default_value = " + code.str() + " : " +
         carrier.str() +
         ", parameter_kind = \"integer\", classification = \"ordinary\", variability = \"fixed\", "
         "metadata = {}}> : () -> ()\n";
}
std::string replace(std::string text, StringRef before, StringRef after) {
  auto offset = text.find(before.str());
  if (offset == std::string::npos)
    llvm::report_fatal_error("test replacement target absent");
  text.replace(offset, before.size(), after.str());
  return text;
}
std::string print(Operation *operation) {
  std::string result;
  llvm::raw_string_ostream stream(result);
  operation->print(stream);
  return result;
}
} // namespace

int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  unsigned total = 0, failures = 0;
  using Mutation = std::function<void(mlir::ModuleOp)>;
  auto check = [&](StringRef name, const std::string &source, bool expected,
                   StringRef diagnostic = {}, Mutation mutate = {}) {
    ++total;
    std::string messages;
    ScopedDiagnosticHandler capture(&context, [&](Diagnostic &message) {
      llvm::raw_string_ostream stream(messages);
      message.print(stream);
      return success();
    });
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    if (!parsed) {
      ++failures;
      llvm::errs() << name << ": parse failed\n" << messages << '\n';
      return;
    }
    if (mutate)
      mutate(*parsed);
    std::string before = print(parsed->getOperation());
    bool actual = succeeded(verify(*parsed));
    if (actual)
      actual = succeeded(nodal::verifyParameterModel(*parsed));
    bool good = actual == expected;
    if (name == "zero-results-rejected-independently")
      parsed->walk([&](nodal::EnumExprOp operation) {
        good &= failed(nodal::verifyEnumValueOperation(operation));
      });
    if (name.ends_with("capability-selection-rejected-independently"))
      parsed->walk([&](nodal::EnumExprOp operation) {
        if (operation->getAttrOfType<StringAttr>("operator_name").getValue() == "enum_select")
          good &= failed(nodal::verifyEnumValueOperation(operation));
      });
    if (!diagnostic.empty())
      good &= StringRef(messages).contains(diagnostic);
    if (expected && good) {
      // Exact typed IR survives print/reparse; parameter APInts remain intact.
      auto again = parseSourceString<mlir::ModuleOp>(before, ParserConfig(&context, false));
      good &= again && succeeded(verify(*again));
      good &= succeeded(nodal::verifyParameterModel(*parsed));
      parsed->walk([&](nodal::EnumExprOp operation) {
        good &= succeeded(nodal::verifyEnumValueOperation(operation));
      });
    }
    good &= before == print(parsed->getOperation());
    if (!good) {
      ++failures;
      llvm::errs() << "EnumValueModelTest " << name << ": wrong result\n" << messages << '\n';
    } else {
      llvm::outs() << "ENUM_VALUE_TEST_PASS " << name << '\n';
    }
  };

  const auto decode =
      input() +
      expression("decoded", "decode", "%bits", "!nodal.bits<3>", type(), "[\"example.Mode.Run\"]") +
      expression("valid", "is_valid", "%bits", "!nodal.bits<3>", "i1");
  check("dynamic-decode-and-original-bit-validity", model(decode), true);
  check("uint-subtype-decode",
        model(input("!nodal.uint<3>") + expression("decoded", "decode", "%bits", "!nodal.uint<3>",
                                                   type(), "[\"example.Mode.Idle\"]")),
        true);
  check("literal-as-bits-and-validity",
        model(literal() + expression("raw", "as_bits", "%idle", type(), "!nodal.bits<3>") +
              expression("valid", "is_valid", "%idle", type(), "i1")),
        true);
  check("same-enum-equality",
        model(literal() + literal("run", "example.Mode.Run") +
              expression("equal", "eq", "%idle, %run", type() + ", " + type(), "i1")),
        true);
  check("empty-membership-is-false-contract",
        model(literal() + expression("member", "is_one_of", "%idle", type(), "i1")), true);
  check("sparse-membership",
        model(literal() + expression("member", "is_one_of", "%idle", type(), "i1",
                                     "[\"example.Mode.Stop\", \"example.Mode.Run\"]")),
        true);
  const auto selection =
      literal() + input("i64", "fallback") + input("i64", "a") + input("i64", "b") +
      input("i64", "c") +
      expression("selected", "select", "%idle, %fallback, %a, %b, %c",
                 type() + ", i64, i64, i64, i64", "i64",
                 "[\"example.Mode.Idle\", \"example.Mode.Run\", \"example.Mode.Stop\"]");
  check("complete-select-explicit-invalid-result", model(selection), true);
  for (const std::string &capability :
       {std::string("!nodal.capability<\"clock\">"),
        std::string("!nodal.shaped<\"2\", !nodal.capability<\"reset\">>")}) {
    std::string body =
        "\"nodal.domain\"() <{sym_name = \"clock\", edge = \"rising\", reset_policy = \"sync\", "
        "metadata = {}}> : () -> ()\n" +
        literal() + input(capability, "fallback") + input(capability, "a") +
        input(capability, "b") + input(capability, "c") +
        expression(
            "selected", "select", "%idle, %fallback, %a, %b, %c",
            type() + ", " + capability + ", " + capability + ", " + capability + ", " + capability,
            capability, "[\"example.Mode.Idle\", \"example.Mode.Run\", \"example.Mode.Stop\"]");
    while (body.find("domain = @none") != std::string::npos)
      body = replace(body, "domain = @none", "domain = @clock");
    check(capability.find("shaped") == std::string::npos
              ? "scalar-capability-selection-rejected-independently"
              : "shaped-capability-selection-rejected-independently",
          model(body), false, "Clock/Reset");
  }
  check("recursive-enum-port",
        model(input("!nodal.shaped<\"2\", !nodal.struct<\"Packet\", [{name = \"mode\", type = " +
                    type() + "}]>>")),
        true);
  check("legal-enum-parameter", model(parameter("7")), true);
  for (const auto *producer : {"nodal.constant", "nodal.const_literal"}) {
    for (const auto *code : {"0", "2", "3", "7"}) {
      const bool legal = StringRef(code) != "2";
      const std::string spelling = StringRef(producer) == "nodal.const_literal"
                                       ? ", spelling = \"" + std::string(code) + "\""
                                       : "";
      const std::string body = "%code = \"" + std::string(producer) +
                               "\"() <{metadata = {}, value = " + code + " : i64" + spelling +
                               "}> : () -> " + type();
      check(std::string(producer) + "-case-" + code, model(body), legal,
            legal ? StringRef() : StringRef("NODAL-ENUM-054-020"));
    }
  }
  auto wide = definition(
      "Wide", "example.Wide", wideHash, 128,
      enumCase("Low", "0", "example.Wide") +
          enumCase("High", "340282366920938463463374607431768211455", "example.Wide", "ui128"));
  check(
      "unsigned-128-bit-parameter-default",
      model(parameter("340282366920938463463374607431768211455", "ui128", type("Wide", 128)), wide),
      true);
  check("hash-is-case-id-sorted",
        model(literal(),
              definition("Mode", "example.Mode", modeHash, 3,
                         enumCase("Stop", "7") + enumCase("Idle", "0") + enumCase("Run", "3"))),
        true);
  auto unicode = definition(
      "Unicode", "example.État", "e8b9bfed632cd4ca74d768b7a7f5d587496bd7e6771ad76348cf2aaf8b90e97b",
      1, enumCase("Éteint", "0", "example.État") + enumCase("Allumé", "1", "example.État"));
  check("hash-uses-utf8-byte-lengths", model(input(type("Unicode", 1)), unicode), true);
  auto supplementary =
      definition("Unicode", "example.Unicode",
                 "631d26f986996ce889a07da74141f1191ee257af040ca1e4a5ad22842a0a64fb", 1,
                 enumCase("𐐀", "0", "example.Unicode") + enumCase("Ａ", "1", "example.Unicode"));
  check("hash-supplementary-before-bmp-uses-utf16-order",
        model(input(type("Unicode", 1)), supplementary), true);
  check("canonical-bits-bool-result",
        model(literal() + expression("v", "is_valid", "%idle", type(), "!nodal.bits<1>")), true);

  const StringRef error = "NODAL-ENUM-054-020";
  check("missing-enum-definition", model(input(type("Absent"))), false, error);
  check("semantic-identity-is-not-symbol-alias", model(input(type("example.Mode"))), false, error);
  check("typed-use-rejects-legacy-metadata",
        model(literal(), replace(definition(), metadata(), "{}")), false, error);
  check("definition-hash-forged",
        model(literal(), replace(definition(), modeHash, std::string(64, '0'))), false, error);
  check("operation-hash-forged", model(replace(literal(), modeHash, std::string(64, '0'))), false,
        error);
  check("operation-identity-forged",
        model(replace(literal(), "semantic_type = \"example.Mode\"",
                      "semantic_type = \"example.Other\"")),
        false, error);
  const auto padded =
      definition("Mode", "Mode", "df5b4a51f3fade96f1ab8a106bbcb0114b5d4601e4e2c6aebcf38763dd61e66a",
                 2, enumCase("Idle", "0", "Mode") + enumCase("Run", "1", "Mode"));
  check("typed-sequential-width-is-compact",
        model(input(type("Mode", 2)),
              replace(padded, "encoding = \"custom\"", "encoding = \"sequential\"")),
        false, error);
  check("typed-gray-width-is-compact",
        model(input(type("Mode", 2)),
              replace(padded, "encoding = \"custom\"", "encoding = \"gray\"")),
        false, error);
  check("partial-ABI-metadata",
        model(literal(), replace(definition(), "abi_version = 1 : i64, ", "")), false, error);
  check("invalid-ABI-version",
        model(literal(), replace(definition(), "abi_version = 1", "abi_version = 2")), false,
        error);
  check("foreign-case-identity", model(literal("bad", "example.Other.Idle")), false, error);
  check("case-id-must-match-name",
        model(literal(), replace(definition(), "case_id = \"example.Mode.Idle\"",
                                 "case_id = \"example.Mode.Other\"")),
        false, error);
  check("enum-width-disagreement", model(input(type("Mode", 4))), false, error);
  check("decode-width-disagreement",
        model(input("!nodal.bits<2>") + expression("v", "decode", "%bits", "!nodal.bits<2>", type(),
                                                   "[\"example.Mode.Idle\"]")),
        false, error);
  check("decode-signed-input",
        model(input("!nodal.sint<3>") + expression("v", "decode", "%bits", "!nodal.sint<3>", type(),
                                                   "[\"example.Mode.Idle\"]")),
        false, error);
  check("decode-missing-default",
        model(input() + expression("v", "decode", "%bits", "!nodal.bits<3>", type())), false,
        error);
  check("decode-extra-default",
        model(input() + expression("v", "decode", "%bits", "!nodal.bits<3>", type(),
                                   "[\"example.Mode.Idle\", \"example.Mode.Run\"]")),
        false, error);
  check("literal-extra-operand",
        model(input() + expression("v", "literal", "%bits", "!nodal.bits<3>", type(),
                                   "[\"example.Mode.Idle\"]")),
        false, error);
  check("duplicate-membership",
        model(literal() + expression("v", "is_one_of", "%idle", type(), "i1",
                                     "[\"example.Mode.Idle\", \"example.Mode.Idle\"]")),
        false, error);
  check("validity-extra-case-id",
        model(literal() +
              expression("v", "is_valid", "%idle", type(), "i1", "[\"example.Mode.Idle\"]")),
        false, error);
  check("as-bits-wrong-width",
        model(literal() + expression("v", "as_bits", "%idle", type(), "!nodal.bits<4>")), false,
        error);
  check("unknown-operation", model(expression("v", "fabricated", "", "", type())), false, error);
  check(
      "incomplete-selection",
      model(replace(selection, "\"example.Mode.Idle\", \"example.Mode.Run\", \"example.Mode.Stop\"",
                    "\"example.Mode.Idle\", \"example.Mode.Run\"")),
      false, error);
  check(
      "selection-order-disagreement",
      model(replace(selection, "\"example.Mode.Idle\", \"example.Mode.Run\", \"example.Mode.Stop\"",
                    "\"example.Mode.Run\", \"example.Mode.Idle\", \"example.Mode.Stop\"")),
      false, error);
  check("selection-type-disagreement",
        model(replace(selection, ", i64, i64, i64, i64) -> i64", ", i64, i64, i64, i64) -> i1")),
        false, error);
  auto missingInvalid = replace(selection, "%idle, %fallback, %a, %b, %c", "%idle, %a, %b, %c");
  missingInvalid =
      replace(missingInvalid, ", i64, i64, i64, i64) -> i64", ", i64, i64, i64) -> i64");
  check("selection-requires-explicit-invalid-result", model(missingInvalid), false, error);
  check("illegal-enum-parameter", model(parameter("2")), false, error);
  check("wide-default-is-not-truncated-to-legal-zero",
        model(parameter("18446744073709551616", "ui128", type("Wide", 128)), wide), false, error);
  check("signed-negative-default", model(parameter("-1")), false);
  const auto symbolic =
      replace(parameter("0"), "variability = \"fixed\"", "variability = \"symbolic\"");
  const auto child = "\"nodal.module\"() <{sym_name = \"Child\", metadata = {}}> ({" + symbolic +
                     "}) : () -> ()\n";
  auto instance = [](StringRef bindings = "{}") {
    return "\"nodal.instance\"() <{sym_name = \"child\", module = @Child, domain_bindings = {}, "
           "parameter_bindings = " +
           bindings.str() + ", metadata = {}}> : () -> ()\n";
  };
  for (const auto code : {"3", "2"}) {
    const bool valid = StringRef(code) == "3";
    const auto root = replace(model(symbolic), "module {",
                              "module attributes {nodal.root.module = @Fixture, "
                              "nodal.root.parameter_bindings = {initial = " +
                                  std::string(code) + " : i64}} {");
    check(valid ? "legal-root-enum-binding" : "illegal-root-enum-binding", root, valid,
          valid ? StringRef() : error);
    check(valid ? "legal-child-enum-binding" : "illegal-child-enum-binding",
          model(instance("{initial = " + std::string(code) + " : i64}"), definition() + child),
          valid, valid ? StringRef() : error);
  }
  auto overrideBody = [&](StringRef actualType) {
    auto parent = replace(parameter("3", "i64", actualType), "sym_name = \"initial\"",
                          "sym_name = \"upstream\"");
    parent = replace(parent, "variability = \"fixed\"", "variability = \"symbolic\"");
    return parent +
           "%actual = \"nodal.const_parameter_ref\"() <{parameter = @upstream, metadata = {}}> : "
           "() -> " +
           actualType.str() + "\n" + instance() +
           "\"nodal.parameter_override\"(%actual) <{instance = @child, parameter = @initial, "
           "metadata = {}}> : (" +
           actualType.str() + ") -> ()\n";
  };
  check("same-enum-symbolic-override", model(overrideBody(type()), definition() + child), true);
  check("generic-integer-cannot-override-enum", model(overrideBody("i64"), definition() + child),
        false, error);
  auto other = definition("Other", "example.Other", otherHash);
  check("other-enum-cannot-override-enum",
        model(overrideBody(type("Other")), definition() + other + child), false, error);
  check("integer-arithmetic-cannot-forge-enum",
        model(input("i64") +
              "%forged = \"nodal.const_expr\"(%bits, %bits) <{operator_name = \"add\", metadata = "
              "{}}> : (i64, i64) -> " +
              type()),
        false, error);
  check("cross-enum-equality",
        model(literal() + input(type("Other"), "other") +
                  expression("v", "eq", "%idle, %other", type() + ", " + type("Other"), "i1"),
              definition() + other),
        false, error);
  auto changed = definition("Other", "example.Mode",
                            "7f935d268cadaa4fe12045aeabee357cd1f86a4e27860d0ddfb8de9c29c1d857", 3,
                            enumCase("Idle", "0") + enumCase("Run", "3") + enumCase("Stop", "6"));
  check("conflicting-ABI-for-one-identity", model(literal(), definition() + changed), false, error);
  check("enum-reference-resolves-wrong-symbol-kind",
        model(literal(),
              "\"nodal.module\"() <{sym_name = \"Mode\", metadata = {}}> ({^bb0:}) : () -> ()"),
        false, error);
  check("zero-results-rejected-independently", model(literal()), false, {},
        [&](mlir::ModuleOp root) {
          root.walk([&](nodal::EnumExprOp operation) {
            OperationState state(operation.getLoc(), "nodal.enum_expr");
            state.addAttributes(operation->getAttrs());
            Operation *invalid = Operation::create(state);
            operation->getBlock()->getOperations().insert(operation->getIterator(), invalid);
            operation.erase();
          });
        });
  const std::string widths =
      "\"nodal.parameter\"() <{sym_name = \"W\", type = i64, default_value = 4 : i64, "
      "classification = \"structural\", parameter_kind = \"integer\", variability = \"symbolic\", "
      "metadata = {}}> : () -> ()\n"
      "\"nodal.parameter\"() <{sym_name = \"V\", type = i64, default_value = 4 : i64, "
      "classification = \"structural\", parameter_kind = \"integer\", variability = \"symbolic\", "
      "metadata = {}}> : () -> ()\n"
      "%low = \"nodal.const_literal\"() <{value = 1 : i64, spelling = \"1\", metadata = {}}> : () "
      "-> i64\n"
      "%high = \"nodal.const_literal\"() <{value = 8 : i64, spelling = \"8\", metadata = {}}> : () "
      "-> i64\n"
      "\"nodal.parameter_constraint\"(%low, %high) <{parameter = @W, constraint_kind = \"range\", "
      "lower_inclusive = true, upper_inclusive = true, metadata = {}}> : (i64, i64) -> ()\n"
      "\"nodal.parameter_constraint\"(%low, %high) <{parameter = @V, constraint_kind = \"range\", "
      "lower_inclusive = true, upper_inclusive = true, metadata = {}}> : (i64, i64) -> ()\n"
      "\"nodal.parameter_envelope\"() <{parameter = @W, policy = \"static_generate\", effects = "
      "[\"width\"], metadata = {}}> : () -> ()\n"
      "\"nodal.parameter_envelope\"() <{parameter = @V, policy = \"static_generate\", effects = "
      "[\"width\"], metadata = {}}> : () -> ()\n"
      "%W = \"nodal.const_parameter_ref\"() <{parameter = @W, metadata = {}}> : () -> i64\n"
      "%first = \"nodal.const_expr\"(%W, %W) <{operator_name = \"add\", metadata = {semantic_path "
      "= \"Fixture.first\"}}> : (i64, i64) -> i64\n"
      "%second = \"nodal.const_expr\"(%W, %W) <{operator_name = \"add\", metadata = {semantic_path "
      "= \"Fixture.second\"}}> : (i64, i64) -> i64\n";
  auto symbolicSelect = [&](StringRef name, StringRef left, StringRef right, bool expected) {
    check(name,
          model(widths + literal() + input(left, "fallback") + input(right, "a") +
                input(right, "b") + input(right, "c") +
                expression("selected", "select", "%idle, %fallback, %a, %b, %c",
                           type() + ", " + left.str() + ", " + right.str() + ", " + right.str() +
                               ", " + right.str(),
                           left,
                           "[\"example.Mode.Idle\", \"example.Mode.Run\", \"example.Mode.Stop\"]")),
          expected);
  };
  const std::string first = "!nodal.digital<\"uint\", \"Fixture.first\">";
  const std::string second = "!nodal.digital<\"uint\", \"Fixture.second\">";
  symbolicSelect("enum-select-fresh-equivalent-widths", first, second, true);
  symbolicSelect(
      "enum-select-equivalent-nested-storage",
      "!nodal.shaped<\"2\", !nodal.struct<\"Packet\", [{name = \"value\", type = " + first + "}]>>",
      "!nodal.shaped<\"2\", !nodal.struct<\"Packet\", [{name = \"value\", type = " + second +
          "}]>>",
      true);
  symbolicSelect("enum-select-retains-actual-parameter-identity", "!nodal.digital<\"uint\", \"W\">",
                 "!nodal.digital<\"uint\", \"V\">", false);
  llvm::outs() << "Enum value model: " << total << " cases, " << failures << " failures\n";
  return failures ? 1 : 0;
}
