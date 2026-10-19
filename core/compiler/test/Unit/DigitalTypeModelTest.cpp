#include "nodal/Dialect/Nodal/DigitalTypeModel.h"

#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/Support/raw_ostream.h"

#include <limits>
#include <string>

using namespace mlir;
namespace {
std::string model(StringRef body, StringRef name = "Fixture") {
  return "module { \"nodal.module\"() <{metadata = {}, sym_name = \"" + name.str() + "\"}> ({\n" +
         body.str() +
         "}) : () -> () "
         "\"nodal.enum\"() <{sym_name = \"Mode\", underlying_type = !nodal.uint<2>, "
         "encoding = \"custom\", metadata = {abi_version = 1 : i64, semantic_type = \"Mode\", "
         "abi_hash = \"df5b4a51f3fade96f1ab8a106bbcb0114b5d4601e4e2c6aebcf38763dd61e66a\"}}> ({"
         "\"nodal.enum_case\"() <{sym_name = \"Idle\", value = 0 : i64, "
         "metadata = {case_id = \"Mode.Idle\"}}> : () -> () "
         "\"nodal.enum_case\"() <{sym_name = \"Run\", value = 1 : i64, "
         "metadata = {case_id = \"Mode.Run\"}}> : () -> () "
         "}) : () -> () }\n";
}
std::string port(StringRef type, StringRef name = "data") {
  return "\"nodal.port\"() <{direction = \"input\", domain = @none, metadata = {}, sym_name = \"" +
         name.str() + "\", type = " + type.str() + "}> : () -> ()\n";
}
std::string field(StringRef name, StringRef type) {
  return "{name = \"" + name.str() + "\", type = " + type.str() + "}";
}
std::string structure(StringRef fields, StringRef name = "Packet") {
  return "!nodal.struct<\"" + name.str() + "\", [" + fields.str() + "]>";
}
std::string shape(StringRef dimensions, StringRef element) {
  return "!nodal.shaped<\"" + dimensions.str() + "\", " + element.str() + ">";
}
std::string digital(StringRef width = "W", StringRef kind = "uint") {
  return "!nodal.digital<\"" + kind.str() + "\", \"" + width.str() + "\">";
}
std::string literal(StringRef id, int64_t value) {
  return "%" + id.str() + " = \"nodal.const_literal\"() <{metadata = {}, spelling = \"" +
         std::to_string(value) + "\", value = " + std::to_string(value) + " : i64}> : () -> i64\n";
}
std::string parameter(StringRef name = "W", int64_t lower = 1, int64_t upper = 32,
                      bool ranged = true, bool structural = true, bool envelope = true,
                      StringRef effects = "\"shape\", \"width\"") {
  std::string result =
      "\"nodal.parameter\"() <{classification = \"" +
      std::string(structural ? "structural" : "ordinary") +
      "\", default_value = 4 : i64, metadata = {}, parameter_kind = \"integer\", sym_name = \"" +
      name.str() + "\", type = i64, variability = \"symbolic\"}> : () -> ()\n";
  if (ranged)
    result += literal(name.str() + "_low", lower) + literal(name.str() + "_high", upper) +
              "\"nodal.parameter_constraint\"(%" + name.str() + "_low, %" + name.str() +
              "_high) <{constraint_kind = \"range\", lower_inclusive = true, metadata = {}, "
              "parameter = @" +
              name.str() + ", upper_inclusive = true}> : (i64, i64) -> ()\n";
  if (envelope)
    result += "\"nodal.parameter_envelope\"() <{effects = [" + effects.str() +
              "], metadata = {}, parameter = @" + name.str() +
              ", policy = \"static_generate\"}> : () -> ()\n";
  result += "%" + name.str() + " = \"nodal.const_parameter_ref\"() <{metadata = {}, parameter = @" +
            name.str() + "}> : () -> i64\n";
  return result;
}
std::string expression(StringRef id = "sum") {
  return "%" + id.str() +
         " = \"nodal.const_expr\"(%W, %W) <{metadata = {semantic_path = \"Fixture.width\"}, "
         "operator_name = \"add\"}> : (i64, i64) -> i64\n";
}
std::string print(Operation *operation) {
  std::string result;
  llvm::raw_string_ostream stream(result);
  operation->print(stream);
  return result;
}
std::string print(Type type) {
  std::string result;
  llvm::raw_string_ostream stream(result);
  type.print(stream);
  return result;
}
} // namespace

int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  context.loadAllAvailableDialects();
  context.allowUnregisteredDialects();
  ScopedDiagnosticHandler silence(&context, [](Diagnostic &) { return success(); });
  unsigned cases = 0, failures = 0;
  auto report = [&](StringRef name, bool good) {
    ++cases;
    failures += !good;
    (good ? llvm::outs() : llvm::errs())
        << "DIGITAL_TYPE_MODEL_" << (good ? "PASS " : "FAIL ") << name << '\n';
  };
  auto parseType = [&](StringRef text) -> Type {
    auto parsed = parseSourceString<mlir::ModuleOp>(
        "module { %v = \"builtin.unrealized_conversion_cast\"() : () -> " + text.str() + " }",
        ParserConfig(&context, false));
    return parsed ? parsed->getBody()->front().getResult(0).getType() : Type();
  };
  auto check = [&](StringRef name, const std::string &body, bool expected) {
    auto parsed = parseSourceString<mlir::ModuleOp>(model(body), ParserConfig(&context, false));
    if (!parsed) {
      report(name, false);
      return;
    }
    std::string before = print(parsed->getOperation());
    Operation *owner = &parsed->getBody()->front();
    bool actual = succeeded(nodal::verifyDigitalTypeModel(owner));
    bool good = actual == expected && before == print(parsed->getOperation());
    // Repeat queries and textual round trips must preserve exact type identities.
    if (good && expected) {
      good = succeeded(nodal::verifyDigitalTypeModel(owner));
      auto again = parseSourceString<mlir::ModuleOp>(before, ParserConfig(&context, false));
      good &= again && before == print(again->getOperation());
    }
    report(name, good);
  };

  const auto packet = structure(field("signedLane", "!nodal.sint<9>") + ", " +
                                field("unsignedLane", "!nodal.uint<7>") + ", " +
                                field("code", "!nodal.enum<\"Mode\", 2>") + ", " +
                                field("count", "i64") + ", " + field("realValue", "f64"));
  const auto nested = structure(
      field("header", packet) + ", " + field("samples", shape("2,3", "!nodal.sint<13>")), "Frame");
  check("fixed-recursive-struct-vec-signed-enum", port(shape("4", nested)), true);
  check("symbolic-width-all-kinds",
        parameter() + port(digital()) + port(digital("W", "bits"), "bits") +
            port(digital("W", "sint"), "signed"),
        true);
  check("symbolic-width-only-effect",
        parameter("W", 1, 32, true, true, true, "\"width\"") + port(digital()), true);
  check("shape-only-effect-is-not-width",
        parameter("W", 1, 32, true, true, true, "\"shape\"") + port(digital()), false);
  check("independent-symbolic-shape-and-width",
        parameter() + parameter("N", 1, 7) +
            port(shape("2,N", structure(field("lane", digital("W", "sint")) + ", " +
                                        field("fixed", shape("3", "!nodal.uint<5>"))))),
        true);
  check("owned-typed-expression-width", parameter() + expression() + port(digital("Fixture.width")),
        true);
  const std::string signedWidth = parameter("W", 2, 32) + literal("minus_one", -1) +
                                  "%derived = \"nodal.const_expr\"(%W, %minus_one) <{metadata = "
                                  "{semantic_path = \"Fixture.width\"}, "
                                  "operator_name = \"add\"}> : (i64, i64) -> i64\n";
  check("derived-width-admits-mathematical-negative-intermediate",
        signedWidth + port(digital("Fixture.width")), true);
  check("ordinary-shape-does-not-opt-in-to-negative-intermediate",
        signedWidth + port(shape("Fixture.width", "!nodal.uint<1>")), false);
  check("derived-width-final-zero-rejected",
        literal("zero", 0) +
            "%derived = \"nodal.const_expr\"(%zero) <{metadata = {semantic_path = "
            "\"Fixture.width\"}, "
            "operator_name = \"neg\"}> : (i64) -> i64\n" +
            port(digital("Fixture.width")),
        false);
  check(
      "derived-width-final-negative-rejected",
      literal("one", 1) +
          "%derived = \"nodal.const_expr\"(%one) <{metadata = {semantic_path = \"Fixture.width\"}, "
          "operator_name = \"neg\"}> : (i64) -> i64\n" +
          port(digital("Fixture.width")),
      false);
  check("derived-width-positive-default-cannot-hide-zero-envelope",
        parameter("W", 1, 32) + literal("minus_one", -1) +
            "%derived = \"nodal.const_expr\"(%W, %minus_one) <{metadata = {semantic_path = "
            "\"Fixture.width\"}, "
            "operator_name = \"add\"}> : (i64, i64) -> i64\n" +
            port(digital("Fixture.width")),
        false);
  check("derived-width-overflow-rejected",
        parameter("W", 2, 32) + literal("large", std::numeric_limits<int64_t>::max()) +
            "%derived = \"nodal.const_expr\"(%W, %large) <{metadata = {semantic_path = "
            "\"Fixture.width\"}, "
            "operator_name = \"add\"}> : (i64, i64) -> i64\n" +
            port(digital("Fixture.width")),
        false);
  check("derived-width-signed64-footprint-overflow-rejected",
        signedWidth + port(shape("9223372036854775807", digital("Fixture.width"))), false);
  const std::string reductionWidth = parameter("W", 2, 32) + literal("four", 4) +
                                     literal("five", 5) + literal("eight", 8) +
                                     "%test = \"nodal.const_expr\"(%W, %four) <{metadata = {}, "
                                     "operator_name = \"sub\"}> : (i64, i64) -> i64\n"
                                     "%width = \"nodal.const_expr\"(%test, %five, %eight) "
                                     "<{metadata = {semantic_path = \"Fixture.width\"}, "
                                     "operator_name = \"choose_zero\"}> : (i64, i64, i64) -> i64\n";
  check("choose-zero-width-mixed-sign-test", reductionWidth + port(digital("Fixture.width")), true);
  const std::string logarithmicWidth =
      parameter() + literal("one", 1) +
      "%log = \"nodal.const_expr\"(%W) <{metadata = {}, operator_name = \"ceil_log2\"}> : (i64) -> "
      "i64\n"
      "%width = \"nodal.const_expr\"(%log, %one) <{metadata = {semantic_path = \"Fixture.width\"}, "
      "operator_name = \"add\"}> : (i64, i64) -> i64\n";
  check("ceil-log2-positive-derived-width", logarithmicWidth + port(digital("Fixture.width")),
        true);
  auto causalWidth = parseSourceString<mlir::ModuleOp>(
      model(signedWidth + port(digital("Fixture.width"))), ParserConfig(&context, false));
  bool repaired = false;
  if (causalWidth) {
    auto *owner = &causalWidth->getBody()->front();
    mlir::Value expression;
    owner->walk([&](nodal::ConstExprOp op) { expression = op->getResult(0); });
    auto type = parseType(digital("Fixture.width"));
    auto oldBounds = nodal::inferParameterIntegerBounds(expression);
    auto mathematical = nodal::inferParameterIntegerBounds(
        expression, nodal::ParameterIntegerMode::MathematicalSigned64);
    auto oldExact = nodal::resolveOwnedIntegerExpression(owner, expression);
    auto width = nodal::inferDigitalWidthExpression(type, owner);
    repaired = failed(oldBounds) && failed(oldExact) && succeeded(mathematical) &&
               mathematical->lower == 1 && mathematical->upper == 31 && succeeded(width) &&
               width->bounds.lower == 1 && width->bounds.upper == 31 &&
               succeeded(mlir::verify(*causalWidth));
    expression.getDefiningOp()->setOperand(1, expression);
    repaired &= failed(nodal::inferDigitalWidthExpression(type, owner));
  }
  report("width-mode-causal-old-reject-new-accept-cycle-reject", repaired);

  check("protocol-payload-recursion",
        parameter() + port("!nodal.stream<" + structure(field("payload", digital())) + ">"), true);
  check("standalone-analog-shape-policy-preserved",
        parameter() + port(shape("9223372036854775807,W", "f64")), true);
  check("ordinary-real-inside-digital-struct-has-width",
        parameter() + port(structure(field("values", shape("9223372036854775807,W", "f64")))),
        false);
  check("maximum-width-no-bit-allocation", port("!nodal.bits<9223372036854775807>"), true);
  check("maximum-cardinality-no-lane-expansion", port(shape("9223372036854775807", "i1")), true);
  check("unknown-width-owner", port(digital()), false);
  check("default-is-not-width-bound", parameter("W", 1, 32, false) + port(digital()), false);
  check("nonpositive-width-envelope", parameter("W", 0, 32) + port(digital()), false);
  check("ordinary-parameter-not-structural", parameter("W", 1, 32, true, false) + port(digital()),
        false);
  check("missing-shape-envelope", parameter("W", 1, 32, true, true, false) + port(digital()),
        false);
  check("duplicate-expression-identity",
        parameter() + expression() + expression("duplicate") + port(digital("Fixture.width")),
        false);
  check("nested-struct-unknown-width", port(structure(field("nested", shape("2", digital())))),
        false);
  check("protocol-does-not-hide-unknown-width", port("!nodal.valid<" + digital() + ">"), false);
  check("nested-struct-unknown-shape",
        port(structure(field("array", shape("N", "!nodal.uint<8>")))), false);
  check("nested-shape-cardinality-overflow",
        port(shape("3037000500", shape("3037000500", "!nodal.bits<1>"))), false);
  check("shape-times-width-overflow", port(shape("2", "!nodal.uint<9223372036854775807>")), false);
  check("struct-sum-overflow",
        port(structure(field("a", "!nodal.bits<9223372036854775807>") + ", " + field("b", "i1"))),
        false);
  check("scalar-result-width-is-checked",
        "%v = \"builtin.unrealized_conversion_cast\"() : () -> " + digital() + "\n", false);
  check("nested-attribute-type-is-checked",
        "\"test.metadata\"() {value = [{nested = " + digital() + "}]} : () -> ()\n", false);
  check("block-argument-type-is-checked",
        "\"test.region\"() ({ ^bb0(%v : " + digital() +
            "): \"test.end\"() : () -> () }) : () -> ()\n",
        false);
  check("type-only-parent-cannot-borrow-child-parameter",
        "\"nodal.module\"() <{metadata = {}, sym_name = \"Child\"}> ({" + parameter() +
            "}) : () -> ()\n" + port(digital()),
        false);

  check("missing-result-static-expression",
        parameter() +
            "\"nodal.const_expr\"(%W, %W) <{metadata = {semantic_path = \"Fixture.width\"}, "
            "operator_name = \"add\"}> : (i64, i64) -> ()\n" +
            port(digital("Fixture.width")),
        false);
  check("wrong-arity-static-expression",
        parameter() +
            "%bad = \"nodal.const_expr\"(%W) <{metadata = {semantic_path = \"Fixture.width\"}, "
            "operator_name = \"add\"}> : (i64) -> i64\n" +
            port(digital("Fixture.width")),
        false);
  check("malformed-parameter-missing-type",
        "\"nodal.parameter\"() <{classification = \"structural\", default_value = 4 : i64, "
        "metadata = {}, parameter_kind = \"integer\", sym_name = \"W\", "
        "variability = \"symbolic\"}> : () -> ()\n" +
            port(digital()),
        false);
  auto fullNegative = parseSourceString<mlir::ModuleOp>(
      model(parameter() +
            "\"nodal.const_expr\"(%W, %W) <{metadata = {semantic_path = "
            "\"Fixture.width\"}, operator_name = \"add\"}> : (i64, i64) -> ()\n" +
            port(digital("Fixture.width"))),
      ParserConfig(&context, false));
  report("full-module-malformed-static-expression-rejects",
         fullNegative && failed(mlir::verify(*fullNegative)));
  auto fullPositive = parseSourceString<mlir::ModuleOp>(model(parameter() + port(digital())),
                                                        ParserConfig(&context, false));
  report("full-module-symbolic-type-hook", fullPositive && succeeded(mlir::verify(*fullPositive)));

  auto rejectType = [&](StringRef name, const std::string &text) {
    report(name, !parseType(text));
  };
  rejectType("empty-struct", structure(""));
  rejectType("duplicate-field-names", structure(field("x", "i64") + ", " + field("x", "f64")));
  rejectType("nonportable-struct-name", structure(field("x", "i64"), "Bad.Name"));
  rejectType("nonportable-field-name", structure(field("bad.field", "i64")));
  rejectType("field-schema-missing-type", structure("{name = \"x\"}"));
  rejectType("field-schema-extra-direction",
             structure("{name = \"x\", type = i64, direction = \"input\"}"));
  rejectType("field-schema-not-dictionary", structure("i64"));
  for (const auto &type :
       {"!nodal.interface<\"Bus\">", "!nodal.valid<i64>", "!nodal.stream<i64>",
        "!nodal.resolved<\"push_pull\", i1>", "!nodal.driver<i1>",
        "!nodal.quantity<\"real\", \"1\">", "!nodal.analog_event", "!nodal.branch<\"electrical\">"})
    rejectType("nonstorable-field-" + std::string(type), structure(field("value", type)));
  rejectType("unknown-digital-kind", digital("W", "integer"));
  rejectType("concrete-width-has-one-representation", digital("8"));
  rejectType("inline-width-arithmetic-rejected", digital("W+1"));
  rejectType("noncanonical-width-whitespace", digital(" W"));
  rejectType("noncanonical-width-empty-segment", digital("Module..width"));
  rejectType("noncanonical-width-trailing-dot", digital("Module.width."));
  rejectType("oversized-identifier", structure(field("x", "i64"), std::string(1025, 'x')));
  rejectType("oversized-width-token", digital(std::string(4097, 'x')));
  rejectType("nested-shape-empty-tail", structure(field("a", shape("2,", "i64"))));
  rejectType("nested-shape-zero", structure(field("a", shape("0", "i64"))));

  auto first =
      parseType(structure(field("b", "!nodal.uint<8>") + ", " + field("a", "!nodal.sint<8>")));
  auto reversed =
      parseType(structure(field("a", "!nodal.sint<8>") + ", " + field("b", "!nodal.uint<8>")));
  report("field-order-is-type-identity", first && reversed && first != reversed);
  report("repeat-types-are-interned", first && first == parseType(print(first)));

  Type deep = nodal::BitsType::get(&context, 1);
  for (unsigned i = 1; i < nodal::digitalTypeMaximumDepth; ++i)
    deep = nodal::ShapedType::get(&context, "1", deep);
  report("storage-depth-boundary", nodal::isDigitalStorageType(deep));
  deep = nodal::ShapedType::get(&context, "1", deep);
  report("storage-depth-overflow", !nodal::isDigitalStorageType(deep));
  report("bounded-wide-metadata", !nodal::isCanonicalDigitalWidthToken(std::string(4097, 'x')));

  std::string wideFields;
  for (unsigned index = 0; index < 1024; ++index) {
    if (index)
      wideFields += ", ";
    wideFields += field("lane_" + std::to_string(index), packet);
  }
  check("bounded-wide-struct-reuses-shared-subtree", port(structure(wideFields)), true);
  Builder builder(&context);
  auto repeatedField = builder.getDictionaryAttr(
      {builder.getNamedAttr("name", builder.getStringAttr("x")),
       builder.getNamedAttr("type", TypeAttr::get(builder.getI64Type()))});
  llvm::SmallVector<Attribute> tooMany(nodal::digitalTypeMaximumNodes + 1, repeatedField);
  report("struct-field-metadata-budget",
         failed(nodal::verifyDigitalStructFields([&] { return emitError(builder.getUnknownLoc()); },
                                                 "TooMany", builder.getArrayAttr(tooMany))));

  auto proofModel = parseSourceString<mlir::ModuleOp>(model(parameter() + port(digital())),
                                                      ParserConfig(&context, false));
  auto widthType = parseType(digital());
  auto bounds = nodal::inferDigitalWidthBounds(widthType, &proofModel->getBody()->front());
  report("width-range-preserved-without-default",
         succeeded(bounds) && bounds->lower == 1 && bounds->upper == 32);
  auto foreign = parseSourceString<mlir::ModuleOp>(model(port(digital()), "Foreign"),
                                                   ParserConfig(&context, false));
  report("same-interned-type-cannot-reuse-foreign-owner-proof",
         failed(nodal::verifyDigitalTypeModel(&foreign->getBody()->front())));

  const std::string identityBody =
      parameter() + parameter("V") +
      "%a = \"nodal.const_expr\"(%W, %W) <{operator_name = \"add\", metadata = {semantic_path = "
      "\"Fixture.a\"}}> : (i64, i64) -> i64\n"
      "%b = \"nodal.const_expr\"(%W, %W) <{operator_name = \"add\", metadata = {semantic_path = "
      "\"Fixture.b\"}}> : (i64, i64) -> i64\n";
  auto identityModel =
      parseSourceString<mlir::ModuleOp>(model(identityBody), ParserConfig(&context, false));
  Operation *identityOwner = &identityModel->getBody()->front();
  const std::string identityBefore = print(identityModel->getOperation());
  auto equivalent = [&](StringRef name, StringRef left, StringRef right, bool expected) {
    Type a = parseType(left), b = parseType(right);
    bool result = a && b && nodal::equivalentDigitalStorageTypes(a, b, identityOwner) == expected &&
                  nodal::equivalentDigitalStorageTypes(b, a, identityOwner) == expected;
    report(name, result && identityBefore == print(identityModel->getOperation()));
  };
  equivalent("equivalent-fresh-owned-width-DAG", digital("Fixture.a"), digital("Fixture.b"), true);
  equivalent("equivalence-keeps-parameter-identity", digital("W"), digital("V"), false);
  equivalent("equivalence-keeps-scalar-kind", digital("W", "uint"), digital("W", "sint"), false);
  equivalent("equivalence-keeps-literal-kind", "!nodal.bits<1>", "i1", false);
  equivalent("equivalence-keeps-enum-identity", "!nodal.enum<\"Mode\", 2>",
             "!nodal.enum<\"Other\", 2>", false);
  equivalent("equivalent-shape-and-element-DAGs", shape("2,Fixture.a", digital("Fixture.a")),
             shape("2,Fixture.b", digital("Fixture.b")), true);
  equivalent("equivalence-keeps-shape-rank", shape("2,3", digital()), shape("6", digital()), false);
  equivalent("equivalence-keeps-shape-parameter", shape("W", digital()), shape("V", digital()),
             false);
  equivalent("equivalent-nested-struct-widths",
             structure(field("lanes", shape("Fixture.a", digital("Fixture.a")))),
             structure(field("lanes", shape("Fixture.b", digital("Fixture.b")))), true);
  equivalent("equivalence-keeps-struct-name", structure(field("x", digital()), "A"),
             structure(field("x", digital()), "B"), false);
  equivalent("equivalence-keeps-field-name", structure(field("x", digital())),
             structure(field("y", digital())), false);
  equivalent("equivalence-keeps-field-order",
             structure(field("x", digital()) + ", " + field("y", digital())),
             structure(field("y", digital()) + ", " + field("x", digital())), false);
  equivalent("equivalence-rejects-capabilities", "!nodal.capability<\"clock\">",
             "!nodal.capability<\"clock\">", false);
  report("equivalence-does-not-reuse-foreign-module-proof",
         !nodal::equivalentDigitalStorageTypes(parseType(digital("Fixture.a")),
                                               parseType(digital("Fixture.b")),
                                               &foreign->getBody()->front()));
  auto firstWidth =
      nodal::inferDigitalWidthExpression(parseType(digital("Fixture.a")), identityOwner);
  auto secondWidth =
      nodal::inferDigitalWidthExpression(parseType(digital("Fixture.b")), identityOwner);
  report("shared-exact-width-expression-owner",
         succeeded(firstWidth) && succeeded(secondWidth) &&
             nodal::equivalentInstantiatedIntegerExpressions(*firstWidth, *secondWidth));

  auto family = [&](StringRef name, StringRef left, StringRef right, bool expected) {
    Type a = parseType(left), b = parseType(right);
    bool result = a && b && nodal::sameDigitalStorageWidthFamily(a, b, identityOwner) == expected &&
                  nodal::sameDigitalStorageWidthFamily(b, a, identityOwner) == expected;
    report(name, result && identityBefore == print(identityModel->getOperation()));
  };
  const auto narrow = structure(field("score", digital("W", "sint")) + ", " +
                                field("payload", shape("W,2", digital("W"))));
  const auto wide = structure(field("score", digital("V", "sint")) + ", " +
                              field("payload", shape("W,2", digital("V"))));
  family("width-family-keeps-independent-symbolic-widths", narrow, wide, true);
  equivalent("width-specializations-remain-distinct-complete-ABI", narrow, wide, false);
  family("width-family-reconstructed-semantic-schema", narrow, narrow, true);
  family("width-family-rejects-kind-change", digital("W", "sint"), digital("V", "uint"), false);
  family("width-family-keeps-inner-parameter-identity", shape("W", digital("W")),
         shape("V", digital("W")), false);
  family("width-family-keeps-inner-rank", shape("2,3", digital("W")), shape("6", digital("V")),
         false);
  family("width-family-keeps-field-order",
         structure(field("x", digital()) + ", " + field("y", digital())),
         structure(field("y", digital()) + ", " + field("x", digital())), false);
  family("width-family-keeps-nominal-name", structure(field("x", digital()), "A"),
         structure(field("x", digital()), "B"), false);
  family("width-family-keeps-enum-identity", "!nodal.enum<\"Mode\", 2>", "!nodal.bits<2>", false);
  family("width-family-keeps-invariant-real-integer-kind", "f64", "i64", false);
  family("width-family-rejects-control-capability", "!nodal.capability<\"clock\">",
         "!nodal.capability<\"clock\">", false);
  family("width-family-rejects-invalid-width", digital("missing"), digital("W"), false);

  llvm::outs() << "DIGITAL_TYPE_MODEL_SUMMARY " << (cases - failures) << '/' << cases << '\n';
  return failures ? 1 : 0;
}
