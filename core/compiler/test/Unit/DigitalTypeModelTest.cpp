#include "nodal/Dialect/Nodal/DigitalTypeModel.h"

#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/HierarchyBoundaryModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

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

  auto exactModel = parseSourceString<mlir::ModuleOp>(model(parameter("W", 4, 4) + port(digital())),
                                                      ParserConfig(&context, false));
  auto exactOwner = exactModel ? &exactModel->getBody()->front() : nullptr;
  auto packed = [&](StringRef name, StringRef spelling, Operation *owner, int64_t expected) {
    Type type = parseType(spelling);
    auto result = nodal::inferDigitalBitPatternFixedBits(type, owner);
    report(name, expected > 0 ? succeeded(result) && *result == expected : failed(result));
  };
  packed("bit-pattern-fixed-scalar", "!nodal.sint<9>", identityOwner, 9);
  packed("bit-pattern-fixed-row-major-shape", shape("2,3", "!nodal.uint<4>"), identityOwner, 24);
  packed("bit-pattern-fixed-nested-struct",
         structure(field("flag", "!nodal.bits<1>") + ", " +
                   field("lanes", shape("2", "!nodal.sint<3>"))),
         identityOwner, 7);
  packed("bit-pattern-fixed-nominal-enum", "!nodal.enum<\"Mode\", 2>", identityOwner, 2);
  packed("bit-pattern-symbolic-width-not-default", digital(), identityOwner, -1);
  packed("bit-pattern-symbolic-shape-not-default", shape("W", "!nodal.uint<4>"), identityOwner, -1);
  packed("bit-pattern-singleton-width-proof", digital(), exactOwner, 4);
  packed("bit-pattern-singleton-shape-proof", shape("W", digital()), exactOwner, 16);
  packed("bit-pattern-host-integer-not-packed", "i64", identityOwner, -1);
  packed("bit-pattern-nested-real-not-packed", shape("2", "f64"), identityOwner, -1);
  report("bit-pattern-fixed-query-preserves-IR",
         identityBefore == print(identityModel->getOperation()));

  // Generated type-use admission is explicit and retains original DAG identity.
  auto generated = [&](const std::string &body, int64_t lower = 0, int64_t upper = 3,
                       int64_t step = 1, StringRef name = "family") {
    const auto id = name.str();
    const auto count = upper <= lower ? 0 : (upper - lower + step - 1) / step;
    return literal(id + "_lo", lower) + literal(id + "_hi", upper) + literal(id + "_step", step) +
           "%" + id + " = \"nodal.generate\"(%" + id + "_lo, %" + id + "_hi, %" + id +
           "_step) <{induction = \"i\", region_id = \"Fixture." + id +
           "\", induction_path = \"Fixture." + id +
           ".i\", metadata = {maximum_trip_count = " + std::to_string(count) +
           " : i64}}> ({^bb0(%" + id + "_i: i64):\n" + body +
           "}) : (i64, i64, i64) -> !nodal.generated_family\n";
  };
  auto localExpression = [&](StringRef id, StringRef operands, StringRef opcode = "add",
                             StringRef token = "Fixture.width") {
    return "%" + id.str() + " = \"nodal.const_expr\"(" + operands.str() + ") <{operator_name = \"" +
           opcode.str() + "\", metadata = {semantic_path = \"" + token.str() +
           "\"}}> : (i64, i64) -> i64\n";
  };
  auto typedUse = [&](StringRef type) {
    return "%typed = \"builtin.unrealized_conversion_cast\"() {test_use} : () -> " + type.str() +
           "\n";
  };
  const std::string regionalWidth = literal("one", 1) + localExpression("width", "%family_i, %one");
  auto generatedQuery = [&](StringRef name, const std::string &body, StringRef token,
                            StringRef effect, bool expected, int64_t lower = 0, int64_t upper = 0) {
    auto parsed = parseSourceString<mlir::ModuleOp>(model(body), ParserConfig(&context, false));
    Operation *use = nullptr;
    if (parsed)
      parsed->walk([&](Operation *op) {
        if (op->hasAttr("test_use"))
          use = op;
      });
    std::string before = parsed ? print(parsed->getOperation()) : "";
    auto result = use ? nodal::resolveGeneratedTypeDimensionExpression(use, token, effect)
                      : FailureOr<nodal::InstantiatedIntegerExpression>(failure());
    bool good = succeeded(result) == expected;
    if (good && expected)
      good = result->bounds.lower == lower && result->bounds.upper == upper &&
             succeeded(nodal::verifyDigitalTypeOperation(use));
    report(name, good && parsed && before == print(parsed->getOperation()));
  };
  generatedQuery("generated-type-exact-all-lane-width",
                 generated(regionalWidth + typedUse(digital("Fixture.width"))), "Fixture.width",
                 "width", true, 1, 3);
  generatedQuery("generated-type-nonzero-nonunit-width",
                 generated(regionalWidth + typedUse(digital("Fixture.width")), 2, 8, 2),
                 "Fixture.width", "width", true, 3, 7);
  generatedQuery("generated-type-shape-reuses-original-DAG",
                 generated(regionalWidth + typedUse(shape("Fixture.width", "!nodal.uint<2>"))),
                 "Fixture.width", "shape", true, 1, 3);
  generatedQuery("generated-type-forward-token-rejected",
                 generated(typedUse(digital("Fixture.width")) + regionalWidth), "Fixture.width",
                 "width", false);
  generatedQuery("generated-type-duplicate-token-rejected",
                 generated(regionalWidth + localExpression("other", "%family_i, %one") +
                           typedUse(digital("Fixture.width"))),
                 "Fixture.width", "width", false);
  generatedQuery("generated-type-sibling-token-rejected",
                 generated(regionalWidth) +
                     generated(typedUse(digital("Fixture.width")), 0, 3, 1, "other"),
                 "Fixture.width", "width", false);
  generatedQuery("generated-type-module-use-not-admitted",
                 regionalWidth.substr(0, regionalWidth.find("%width")) +
                     typedUse(digital("Fixture.width")),
                 "Fixture.width", "width", false);
  generatedQuery("generated-type-nonpositive-all-lane-width",
                 generated(localExpression("width", "%family_i, %family_i") +
                           typedUse(digital("Fixture.width"))),
                 "Fixture.width", "width", false);
  generatedQuery("generated-type-empty-domain-has-no-fabricated-width",
                 generated(regionalWidth + typedUse(digital("Fixture.width")), 0, 0),
                 "Fixture.width", "width", false);
  generatedQuery("generated-type-overflow-all-lane-width",
                 generated(literal("maximum", std::numeric_limits<int64_t>::max()) +
                           localExpression("width", "%family_i, %maximum") +
                           typedUse(digital("Fixture.width"))),
                 "Fixture.width", "width", false);
  const auto negativeIntermediate =
      literal("minus", -1) + localExpression("width", "%family_i, %minus");
  generatedQuery("generated-width-keeps-mathematical-negative-intermediate",
                 generated(negativeIntermediate + typedUse(digital("Fixture.width")), 2, 5),
                 "Fixture.width", "width", true, 1, 3);
  generatedQuery(
      "generated-shape-keeps-storage-negative-intermediate-refusal",
      generated(negativeIntermediate + typedUse(shape("Fixture.width", "!nodal.uint<1>")), 2, 5),
      "Fixture.width", "shape", false);
  generatedQuery(
      "generated-type-wrong-parameter-effect",
      parameter("W", 1, 4, true, true, true, "\"shape\"") +
          generated(localExpression("width", "%family_i, %W") + typedUse(digital("Fixture.width"))),
      "Fixture.width", "width", false);
  check("generated-type-memo-is-not-shared-across-siblings",
        generated(regionalWidth + typedUse(digital("Fixture.width"))) +
            generated(localExpression("width", "%other_i, %other_i") +
                          typedUse(digital("Fixture.width")),
                      0, 3, 1, "other"),
        false);
  auto guarded = [&](StringRef body) {
    return literal("g0", 0) + literal("g1", 1) +
           "%condition = \"nodal.static_compare\"(%g0, %g1) <{comparison = \"lt\", metadata = {}}> "
           ": (i64, i64) -> !nodal.static_predicate\n"
           "\"nodal.structural_if\"(%condition, %width) <{contract_version = \"structural-if-v1\", "
           "region_id = \"Fixture.guard\", "
           "operandSegmentSizes = array<i32: 1, 1, 0>, metadata = {semantic_path = "
           "\"Fixture.guard\"}}> ({^bb0(%captured: i64):\n" +
           body.str() +
           "\"nodal.structural_yield\"() <{metadata = {}}> : () -> ()\n}, "
           "{\"nodal.structural_yield\"() <{metadata = {}}> : () -> ()\n}) : "
           "(!nodal.static_predicate, i64) -> ()\n";
  };
  generatedQuery("generated-type-explicit-structural-capture",
                 generated(regionalWidth + guarded(typedUse(digital("Fixture.guard.then.arg0")))),
                 "Fixture.guard.then.arg0", "width", true, 1, 3);
  generatedQuery("generated-type-capture-cannot-be-bypassed",
                 generated(regionalWidth + guarded(typedUse(digital("Fixture.width")))),
                 "Fixture.width", "width", false);
  const auto child = "\"nodal.module\"() <{sym_name = \"Child\", metadata = {}}> ({\n" +
                     parameter("W", 1, 32) + port(digital("W"), "input") + "}) : () -> ()\n";
  auto instanceType = [&](StringRef name, const std::string &body, bool expected,
                          StringRef formalType = "", StringRef actualType = "") {
    std::string localChild = child, localBody = body;
    if (!formalType.empty()) {
      auto position = localChild.find("type = " + digital("W"));
      localChild.replace(position + 7, digital("W").size(), formalType.str());
    }
    if (!actualType.empty()) {
      auto position = localBody.find("-> " + digital("Fixture.actual"));
      localBody.replace(position + 3, digital("Fixture.actual").size(), actualType.str());
    }
    std::string source = model(generated(localBody));
    source.insert(source.find("\"nodal.enum\"()"), localChild);
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    Operation *use = nullptr;
    if (parsed)
      parsed->walk([&](nodal::InstancePortOp op) { use = op; });
    report(name, use && succeeded(nodal::inspectInstancePort(use)) == expected);
  };
  const std::string childInstance = "\"nodal.instance\"() <{sym_name = \"child\", module = @Child, "
                                    "parameter_bindings = {}, domain_bindings = {}, "
                                    "generated_owner = \"Fixture.family\", generated_induction = "
                                    "\"Fixture.family.i\", metadata = {}}> : () -> ()\n";
  const std::string overrideWidth =
      "\"nodal.parameter_override\"(%width) <{instance = @child, parameter = @W, generated_owner = "
      "\"Fixture.family\", "
      "generated_induction = \"Fixture.family.i\", metadata = {}}> : (i64) -> ()\n";
  const std::string actualPort = "%actual = \"nodal.instance_port\"() <{instance = @child, port = "
                                 "\"input\", direction = \"input\", metadata = {}}> : () -> " +
                                 digital("Fixture.actual") + "\n";
  instanceType("generated-instance-type-equal-DAG-distinct-token-names",
               regionalWidth + childInstance + overrideWidth +
                   localExpression("actualWidth", "%family_i, %one", "add", "Fixture.actual") +
                   actualPort,
               true);
  instanceType("generated-instance-type-equal-bounds-not-equal-DAG",
               regionalWidth + childInstance + overrideWidth + literal("three", 3) +
                   localExpression("actualWidth", "%three, %family_i", "sub", "Fixture.actual") +
                   actualPort,
               false);

  const auto matchingInstance =
      regionalWidth + childInstance + overrideWidth +
      localExpression("actualWidth", "%family_i, %one", "add", "Fixture.actual") + actualPort;
  instanceType("generated-instance-type-recursive-shape-and-width-DAG", matchingInstance, true,
               shape("W", digital("W")), shape("Fixture.actual", digital("Fixture.actual")));
  instanceType("generated-instance-type-recursive-nominal-field-DAG", matchingInstance, true,
               structure(field("lanes", shape("W", digital("W")))),
               structure(field("lanes", shape("Fixture.actual", digital("Fixture.actual")))));
  instanceType(
      "generated-instance-type-retains-nominal-struct-identity", matchingInstance, false,
      structure(field("lanes", shape("W", digital("W"))), "Packet"),
      structure(field("lanes", shape("Fixture.actual", digital("Fixture.actual"))), "Other"));

  // Checked absence belongs to this original generated Instance, never its
  // sibling, a sampled lane or a replacement default. Numeric leaves stay closed.
  auto emptyInstanceSource = [&](StringRef formal, StringRef actual, int64_t upper = 0,
                                 std::string localBody = "", std::string localChild = "") {
    if (localChild.empty())
      localChild = child;
    auto at = localChild.find("type = " + digital("W"));
    localChild.replace(at + 7, digital("W").size(), formal.str());
    if (localBody.empty())
      localBody = matchingInstance;
    at = localBody.find("-> " + digital("Fixture.actual"));
    localBody.replace(at + 3, digital("Fixture.actual").size(), actual.str());
    auto source = model(generated(localBody, 0, upper));
    source.insert(source.find("\"nodal.enum\"()"), localChild);
    return source;
  };
  auto emptyInstance = [&](StringRef name, StringRef formal, StringRef actual, bool expected,
                           int64_t upper = 0, std::string body = "", std::string declaration = "") {
    auto ir = parseSourceString<mlir::ModuleOp>(
        emptyInstanceSource(formal, actual, upper, body, declaration),
        ParserConfig(&context, false));
    Operation *use = nullptr;
    if (ir)
      ir->walk([&](nodal::InstancePortOp op) { use = op; });
    const std::string before = ir ? print(ir->getOperation()) : "";
    bool good = use && succeeded(nodal::inspectInstancePort(use)) == expected;
    report(name, good && before == print(ir->getOperation()));
  };
  emptyInstance("empty-instance-dependent-width-validity", digital("W"), digital("Fixture.actual"),
                true);
  emptyInstance("empty-instance-dependent-shape-and-width-validity", shape("W", digital("W")),
                shape("Fixture.actual", digital("Fixture.actual")), true);
  emptyInstance("empty-instance-mixed-dependent-formal-width", digital("W"), "!nodal.uint<8>",
                true);
  emptyInstance("empty-instance-mixed-dependent-actual-width", "!nodal.uint<8>",
                digital("Fixture.actual"), true);
  emptyInstance("empty-instance-mixed-shape-dimensions", shape("W,2", digital("W")),
                shape("3,Fixture.actual", "!nodal.uint<8>"), true);
  emptyInstance("empty-instance-concrete-width-mismatch-still-refused", "!nodal.uint<8>",
                "!nodal.uint<9>", false);
  emptyInstance("empty-instance-concrete-dimension-mismatch-still-refused",
                shape("W,2", digital("W")), shape("Fixture.actual,3", digital("Fixture.actual")),
                false);
  emptyInstance("empty-instance-rank-retained", shape("W,2", digital("W")),
                shape("Fixture.actual", digital("Fixture.actual")), false);
  emptyInstance("empty-instance-scalar-kind-retained", digital("W"),
                digital("Fixture.actual", "bits"), false);
  emptyInstance("empty-instance-nominal-identity-retained",
                structure(field("data", digital("W")), "Packet"),
                structure(field("data", digital("Fixture.actual")), "Other"), false);
  emptyInstance(
      "empty-instance-ordered-fields-retained",
      structure(field("a", digital("W")) + ", " + field("b", "!nodal.uint<1>")),
      structure(field("b", "!nodal.uint<1>") + ", " + field("a", digital("Fixture.actual"))),
      false);
  std::string independentActualZero = matchingInstance;
  independentActualZero.insert(
      independentActualZero.find(actualPort),
      localExpression("zeroDimension", "%one, %one", "sub", "Fixture.zero"));
  emptyInstance("empty-instance-invalid-concrete-actual-dimension", shape("W,2", digital("W")),
                shape("Fixture.actual,Fixture.zero", digital("Fixture.actual")), false, 0,
                independentActualZero);
  std::string independentFormalZero = child;
  independentFormalZero.insert(
      independentFormalZero.find("\"nodal.port\""),
      "%zeroDimension = \"nodal.const_literal\"() <{value = 0 : i64, spelling = \"0\", "
      "metadata = {semantic_path = \"Zero\"}}> : () -> i64\n");
  emptyInstance("empty-instance-invalid-concrete-formal-dimension", shape("W,Zero", digital("W")),
                shape("Fixture.actual,2", digital("Fixture.actual")), false, 0, "",
                independentFormalZero);
  emptyInstance("empty-instance-independent-actual-product-overflow", shape("W,2,2", digital("W")),
                shape("Fixture.actual,9223372036854775807,2", digital("Fixture.actual")), false);
  std::string invalidDefault = child;
  auto defaultAt = invalidDefault.find("default_value = 4");
  invalidDefault.replace(defaultAt, std::string("default_value = 4").size(), "default_value = 0");
  emptyInstance("empty-instance-independent-formal-default-invalid", digital("W"),
                digital("Fixture.actual"), false, 0, "", invalidDefault);
  std::string forgedOwner = matchingInstance;
  auto ownerAt = forgedOwner.find("generated_owner = \"Fixture.family\"");
  forgedOwner.replace(ownerAt, std::string("generated_owner = \"Fixture.family\"").size(),
                      "generated_owner = \"Fixture.foreign\"");
  emptyInstance("empty-instance-foreign-generated-owner-refused", digital("W"),
                digital("Fixture.actual"), false, 0, forgedOwner);
  emptyInstance("nonempty-instance-mixed-width-cannot-use-empty-rule", digital("W"),
                "!nodal.uint<8>", false, 3);
  emptyInstance("nonempty-instance-exact-width-remains-valid", digital("W"),
                digital("Fixture.actual"), true, 3);
  {
    auto ir = parseSourceString<mlir::ModuleOp>(
        emptyInstanceSource(digital("W"), digital("Fixture.actual")),
        ParserConfig(&context, false));
    nodal::InstanceOp instance;
    if (ir)
      ir->walk([&](nodal::InstanceOp op) { instance = op; });
    auto formal = parseType(digital("W"));
    auto facts = nodal::inspectGeneratedInstanceDigitalStorageType(formal, instance);
    report("empty-instance-type-facts-preserve-absence", succeeded(facts) && !facts->maximumBits);
    report("empty-instance-numeric-width-remains-unavailable",
           failed(nodal::inferGeneratedInstanceDigitalWidthExpression(formal, instance)));
    report("empty-instance-numeric-boundary-leaves-remain-unavailable",
           failed(nodal::inspectHierarchyBoundaryType(
               formal, nodal::resolveInstanceModuleDefinition(instance), instance)));
    auto fixed =
        nodal::inspectGeneratedInstanceDigitalStorageType(parseType("!nodal.uint<8>"), instance);
    report("empty-instance-independent-fixed-type-retains-number",
           succeeded(fixed) && fixed->maximumBits && *fixed->maximumBits == 8);
  }
  {
    // Query the same token/type on two actual owners in both orders. No cached
    // absent fact may authorize a nonempty sibling or its foreign use pointer.
    auto source = emptyInstanceSource(digital("W"), "!nodal.uint<8>");
    std::string sibling = generated(matchingInstance, 0, 3, 1, "other");
    for (auto replacement :
         {std::pair{"Fixture.family", "Fixture.other"}, std::pair{"%family_i", "%other_i"}}) {
      size_t at = 0;
      while ((at = sibling.find(replacement.first, at)) != std::string::npos) {
        sibling.replace(at, std::string(replacement.first).size(), replacement.second);
        at += std::string(replacement.second).size();
      }
    }
    source.insert(source.find("}) : () -> () \"nodal.module\""), sibling);
    auto ir = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    SmallVector<Operation *> uses, instances;
    if (ir)
      ir->walk([&](Operation *op) {
        if (isa<nodal::InstancePortOp>(op))
          uses.push_back(op);
        if (isa<nodal::InstanceOp>(op))
          instances.push_back(op);
      });
    bool valid = uses.size() == 2 && instances.size() == 2;
    auto formal = parseType(digital("W"));
    auto fixed = parseType("!nodal.uint<8>");
    if (valid)
      valid = nodal::equivalentGeneratedInstanceDigitalStorageTypes(instances[0], formal, fixed,
                                                                    uses[0]) &&
              !nodal::equivalentGeneratedInstanceDigitalStorageTypes(instances[1], formal, fixed,
                                                                     uses[1]) &&
              !nodal::equivalentGeneratedInstanceDigitalStorageTypes(instances[0], formal, fixed,
                                                                     uses[1]) &&
              !nodal::equivalentGeneratedInstanceDigitalStorageTypes(instances[1], formal, fixed,
                                                                     uses[1]) &&
              nodal::equivalentGeneratedInstanceDigitalStorageTypes(instances[0], formal, fixed,
                                                                    uses[0]);
    report("empty-instance-sibling-nonempty-and-foreign-use-isolation", valid);
  }
  for (bool mixed : {false, true}) {
    auto source =
        emptyInstanceSource(digital("W"), mixed ? "!nodal.uint<8>" : digital("Fixture.actual"));
    auto prefix = parameter("COUNT", 0, 4, true, true, true, "\"topology\"");
    source.insert(source.find("%family_lo"), prefix);
    auto from = literal("family_hi", 0);
    auto at = source.find(from);
    source.replace(at, from.size(),
                   "%family_hi = \"nodal.const_parameter_ref\"() <{parameter = @COUNT, metadata = "
                   "{}}> : () -> i64\n");
    at = source.find("maximum_trip_count = 0");
    source.replace(at, std::string("maximum_trip_count = 0").size(), "maximum_trip_count = 4");
    auto ir = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    Operation *use = nullptr;
    if (ir)
      ir->walk([&](nodal::InstancePortOp op) { use = op; });
    report(mixed ? "maybe-empty-instance-mixed-width-not-vacuous"
                 : "maybe-empty-instance-exact-attained-type-valid",
           use && succeeded(nodal::inspectInstancePort(use)) == !mixed);
  }
  generatedQuery("generated-type-width-effect-cannot-authorize-shape",
                 parameter("W", 1, 4, true, true, true, "\"width\"") +
                     generated(localExpression("width", "%family_i, %W") +
                               typedUse(shape("Fixture.width", "!nodal.uint<1>"))),
                 "Fixture.width", "shape", false);
  const std::string mapType = "!nodal.shaped<\"2\", !nodal.uint<1>>";
  const std::string pureNested =
      "%input = \"builtin.unrealized_conversion_cast\"() : () -> " + mapType + "\n" +
      "%mapped = \"nodal.collection\"(%input) <{kind = \"map\", region_id = \"Fixture.map\", "
      "operandSegmentSizes = array<i32: 1, 0, 0>, metadata = {semantic_path = \"Fixture.map\"}}> "
      "({^bb0(%element: !nodal.uint<1>):\n" +
      generated(regionalWidth + typedUse(digital("Fixture.width"))) +
      "\"nodal.collection_yield\"(%element) <{metadata = {}}> : (!nodal.uint<1>) -> ()\n}) : (" +
      mapType + ") -> " + mapType + "\n";
  generatedQuery("generated-type-pure-callback-cannot-grant-structural-type-use", pureNested,
                 "Fixture.width", "width", false);
  // Repeated field tokens exercise the single actual-use memo, not lane expansion.
  std::string repeatedFields;
  for (unsigned i = 0; i < 256; ++i) {
    if (i)
      repeatedFields += ", ";
    repeatedFields += field("f" + std::to_string(i),
                            shape("Fixture.width", "!nodal.uint<" + std::to_string(i + 1) + ">"));
  }
  generatedQuery("generated-type-256-distinct-fields-share-actual-token-proof",
                 generated(regionalWidth + typedUse(structure(repeatedFields)), 0, 1000000),
                 "Fixture.width", "shape", true, 1, 1000000);

  // Empty attainment is a checked absence, never an invented scalar width.
  auto emptyType = [&](StringRef name, const std::string &body, bool valid, bool queryValid = true,
                       StringRef effect = "width", bool duplicateFields = false) {
    auto parsed = parseSourceString<mlir::ModuleOp>(model(body), ParserConfig(&context, false));
    Operation *use = nullptr;
    if (parsed)
      parsed->walk([&](Operation *op) {
        if (op->hasAttr("test_use"))
          use = op;
      });
    if (use && duplicateFields) {
      auto original = cast<nodal::StructType>(use->getResult(0).getType());
      use->getResult(0).setType(nodal::StructType::get(
          &context, original.getName(),
          ArrayAttr::get(&context, {original.getFields()[0], original.getFields()[0]})));
    }
    const auto before = parsed ? print(parsed->getOperation()) : "";
    auto facts = use ? nodal::resolveGeneratedTypeDimensionFacts(use, "Fixture.width", effect)
                     : FailureOr<nodal::StructuralIntegerFacts>(failure());
    bool good = use && succeeded(facts) == queryValid &&
                succeeded(nodal::verifyDigitalTypeOperation(use)) == valid;
    if (queryValid)
      good &=
          succeeded(facts) && !facts->expression &&
          failed(nodal::resolveGeneratedTypeDimensionExpression(use, "Fixture.width", effect)) &&
          failed(nodal::inferDigitalStorageMaximumBits(use->getResult(0).getType(), use));
    report(name, good && parsed && before == print(parsed->getOperation()));
  };
  auto zeroParameter = parameter("ZERO", 0, 0);
  zeroParameter.replace(zeroParameter.find("default_value = 4"),
                        std::string("default_value = 4").size(), "default_value = 0");
  emptyType("empty-dependent-width-is-valid-without-attained-footprint",
            generated(regionalWidth + typedUse(digital("Fixture.width")), 0, 0), true);
  emptyType("empty-dependent-extent-is-valid-without-attained-footprint",
            generated(regionalWidth + typedUse(shape("Fixture.width", "!nodal.uint<2>")), 0, 0),
            true, true, "shape");
  emptyType(
      "empty-dependent-recursive-member-still-validates-all-fields",
      generated(regionalWidth + typedUse(structure(field("dependent", digital("Fixture.width")) +
                                                   ", " + field("fixed", "!nodal.uint<7>"))),
                0, 0),
      true);
  emptyType("empty-dependent-field-cannot-hide-missing-sibling-width",
            generated(regionalWidth +
                          typedUse(structure(field("dependent", digital("Fixture.width")) + ", " +
                                             field("missing", digital("Fixture.missing")))),
                      0, 0),
            false);
  emptyType("empty-dependent-field-cannot-hide-invalid-concrete-shape",
            zeroParameter +
                generated(regionalWidth + typedUse(structure(
                                              field("dependent", digital("Fixture.width")) + ", " +
                                              field("bad", shape("ZERO", "!nodal.uint<1>")))),
                          0, 0),
            false);
  emptyType(
      "empty-dependent-field-cannot-hide-forged-duplicate-field-schema",
      generated(regionalWidth + typedUse(structure(field("dependent", digital("Fixture.width")) +
                                                   ", " + field("fixed", "!nodal.uint<2>"))),
                0, 0),
      false, true, "width", true);
  emptyType(
      "empty-shape-cannot-hide-independent-zero-dimension",
      zeroParameter +
          generated(regionalWidth + typedUse(shape("Fixture.width,ZERO", "!nodal.uint<1>")), 0, 0),
      false, true, "shape");
  emptyType("empty-shape-cannot-hide-independent-overflow-product",
            generated(regionalWidth +
                          typedUse(shape("Fixture.width,9223372036854775807,2", "!nodal.uint<1>")),
                      0, 0),
            false, true, "shape");
  emptyType("empty-dependent-width-still-rejects-forward-reference",
            generated(typedUse(digital("Fixture.width")) + regionalWidth, 0, 0), false, false);
  emptyType("empty-dependent-width-still-rejects-duplicate-token",
            generated(regionalWidth + localExpression("other", "%family_i, %one") +
                          typedUse(digital("Fixture.width")),
                      0, 0),
            false, false);
  emptyType("empty-dependent-width-cannot-use-sibling-token",
            generated(regionalWidth, 0, 0) +
                generated(typedUse(digital("Fixture.width")), 0, 0, 1, "other"),
            false, false);
  emptyType(
      "empty-dependent-width-still-checks-parameter-effect",
      parameter("W", 1, 4, true, true, true, "\"shape\"") +
          generated(localExpression("width", "%family_i, %W") + typedUse(digital("Fixture.width")),
                    0, 0),
      false, false);
  emptyType(
      "empty-dependent-shape-still-checks-storage-arithmetic",
      generated(negativeIntermediate + typedUse(shape("Fixture.width", "!nodal.uint<1>")), 0, 0),
      false, false, "shape");
  emptyType("empty-dependent-width-preserves-mathematical-arithmetic",
            generated(negativeIntermediate + typedUse(digital("Fixture.width")), 0, 0), true);
  emptyType("empty-dependent-expression-does-not-hide-independent-overflow",
            generated(literal("maximum", std::numeric_limits<int64_t>::max()) +
                          localExpression("bad", "%maximum, %maximum", "add", "Fixture.bad") +
                          localExpression("width", "%family_i, %bad") +
                          typedUse(digital("Fixture.width")),
                      0, 0),
            false, false);
  auto wrongEmptyCount = generated(regionalWidth + typedUse(digital("Fixture.width")), 0, 0);
  const auto countPosition = wrongEmptyCount.find("maximum_trip_count = 0");
  wrongEmptyCount.replace(countPosition, std::string("maximum_trip_count = 0").size(),
                          "maximum_trip_count = 1");
  emptyType("empty-dependent-type-requires-exact-domain-contract", wrongEmptyCount, false, false);
  auto selectedEmpty = parseSourceString<mlir::ModuleOp>(
      model(generated("", 0, 0) + typedUse("i64")), ParserConfig(&context, false));
  Operation *emptyFamily = nullptr, *emptyUse = nullptr;
  if (selectedEmpty)
    selectedEmpty->walk([&](Operation *op) {
      if (isa<nodal::GenerateOp>(op))
        emptyFamily = op;
      if (op->hasAttr("test_use"))
        emptyUse = op;
    });
  report("checked-empty-family-cannot-supply-selected-ordinal-zero",
         emptyFamily && emptyUse &&
             failed(nodal::resolveGeneratedOrdinal(emptyFamily, emptyFamily->getOperand(0),
                                                   emptyUse)));
  // A possibly empty parameterized family still has attained lanes and retains
  // their nonpositive bound rejection; zero in its envelope is not vacuity.
  const auto maybePrefix = parameter("W", 0, 4, true, true, true, "\"shape\", \"width\"");
  auto maybeBody = generated(
      localExpression("width", "%family_i, %family_i") + typedUse(digital("Fixture.width")), 0, 4);
  auto maybeHeader = maybeBody.find("%family_lo, %family_hi, %family_step");
  maybeBody.replace(maybeHeader, std::string("%family_lo, %family_hi, %family_step").size(),
                    "%family_lo, %W, %family_step");
  emptyType("possibly-empty-family-still-validates-attained-width", maybePrefix + maybeBody, false,
            false);

  llvm::outs() << "DIGITAL_TYPE_MODEL_SUMMARY " << (cases - failures) << '/' << cases << '\n';
  return failures ? 1 : 0;
}
