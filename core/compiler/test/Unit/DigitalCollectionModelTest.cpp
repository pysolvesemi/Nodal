#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"

#include "mlir/IR/Builders.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/ShapeModel.h"

#include "llvm/Support/raw_ostream.h"

#include <functional>
#include <string>

using namespace mlir;
namespace {
std::string model(StringRef body) {
  return "module { \"nodal.module\"() <{metadata = {}, sym_name = \"Fixture\"}> ({\n" + body.str() +
         "}) : () -> () }\n";
}
std::string shaped(StringRef dimensions, StringRef element = "!nodal.uint<8>") {
  return "!nodal.shaped<\"" + dimensions.str() + "\", " + element.str() + ">";
}
std::string input(StringRef type, StringRef name = "input") {
  return "\"nodal.port\"() <{sym_name = \"" + name.str() + "\", type = " + type.str() +
         ", direction = \"input\", domain = @none, metadata = {}}> : () -> ()\n%" + name.str() +
         " = \"nodal.port_value\"() <{port = @" + name.str() + ", metadata = {}}> : () -> " +
         type.str() + "\n";
}
std::string yield(StringRef value, StringRef type) {
  return "\"nodal.collection_yield\"(" + value.str() + ") <{metadata = {}}> : (" + type.str() +
         ") -> ()\n";
}
std::string collection(StringRef kind, StringRef operands, StringRef inputTypes, StringRef args,
                       StringRef body, StringRef output, StringRef segments = "1, 0, 0",
                       StringRef id = "Fixture.c", StringRef result = "result") {
  return "%" + result.str() + " = \"nodal.collection\"(" + operands.str() + ") <{kind = \"" +
         kind.str() + "\", region_id = \"" + id.str() +
         "\", operandSegmentSizes = array<i32: " + segments.str() +
         ">, metadata = {semantic_path = \"" + id.str() + "\"}}> ({\n^bb0(" + args.str() + "):\n" +
         body.str() + "}) : (" + inputTypes.str() + ") -> " + output.str() + "\n";
}
std::string expression(StringRef opcode, StringRef inputs, StringRef types, StringRef output,
                       StringRef result = "value") {
  return "%" + result.str() + " = \"nodal.digital_expr\"(" + inputs.str() +
         ") <{operator_name = \"" + opcode.str() + "\", metadata = {}}> : (" + types.str() +
         ") -> " + output.str() + "\n";
}
std::string parameter(StringRef name) {
  return "\"nodal.parameter\"() <{sym_name = \"" + name.str() +
         "\", type = i64, default_value = 4 : i64, classification = \"structural\", "
         "parameter_kind = \"integer\", variability = \"symbolic\", metadata = {}}> : () -> ()\n%" +
         name.str() +
         "low = \"nodal.const_literal\"() <{value = 1 : i64, spelling = \"1\", metadata = {}}> : "
         "() -> i64\n%" +
         name.str() +
         "high = \"nodal.const_literal\"() <{value = 1000000 : i64, spelling = \"1000000\", "
         "metadata = {}}> : () -> i64\n" +
         "\"nodal.parameter_constraint\"(%" + name.str() + "low, %" + name.str() +
         "high) <{constraint_kind = \"range\", lower_inclusive = true, upper_inclusive = true, "
         "parameter = @" +
         name.str() + ", metadata = {}}> : (i64, i64) -> ()\n" +
         "\"nodal.parameter_envelope\"() <{parameter = @" + name.str() +
         ", policy = \"static_generate\", effects = [\"shape\", \"width\"], metadata = {}}> : () "
         "-> ()\n";
}
std::string replace(std::string source, StringRef old, StringRef value) {
  auto offset = source.find(old.str());
  if (offset == std::string::npos)
    llvm::report_fatal_error("test replacement not found");
  source.replace(offset, old.size(), value.str());
  return source;
}
std::string print(Operation *operation) {
  std::string output;
  llvm::raw_string_ostream stream(output);
  operation->print(stream);
  return output;
}
} // namespace

int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  unsigned cases = 0, failures = 0;
  auto check = [&](StringRef name, const std::string &source, bool expected,
                   std::function<void(Operation *)> mutate = {}, bool schemaReject = false) {
    ++cases;
    std::string messages;
    ScopedDiagnosticHandler handler(&context, [&](Diagnostic &diagnostic) {
      llvm::raw_string_ostream stream(messages);
      diagnostic.print(stream);
      return success();
    });
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    bool good = static_cast<bool>(parsed) || (!expected && schemaReject);
    if (parsed) {
      if (mutate)
        mutate(*parsed);
      Operation *module = nullptr;
      parsed->walk([&](nodal::ModuleOp candidate) {
        if (!module)
          module = candidate;
      });
      const std::string before = print(*parsed);
      good &= module && succeeded(nodal::verifyDigitalCollectionModel(module)) == expected;
      good &= succeeded(verify(*parsed)) == expected;
      good &= before == print(*parsed);
      if (expected && good) {
        auto again = parseSourceString<mlir::ModuleOp>(before, ParserConfig(&context, false));
        good &= again && succeeded(verify(*again));
      }
    }
    failures += !good;
    (good ? llvm::outs() : llvm::errs())
        << "DIGITAL_COLLECTION_MODEL_" << (good ? "PASS " : "FAIL ") << name << '\n';
    if (!good)
      llvm::errs() << messages << '\n';
  };
  const std::string u8 = "!nodal.uint<8>", u9 = "!nodal.uint<9>";
  const std::string vec = shaped("4"), matrix = shaped("2,3");
  auto map = [&](StringRef dimensions, StringRef element = "!nodal.uint<8>") {
    auto type = shaped(dimensions, element);
    return model(input(type) + collection("map", "%input", type, "%element: " + element.str(),
                                          yield("%element", element), type));
  };
  const std::string identity = map("4");
  check("map-identity", identity, true);
  const std::string consumed =
      replace(identity, "}) : () -> () }",
              "%zero = \"nodal.constant\"() <{value = 0 : index, metadata = {}}> : () -> index\n"
              "%selected = \"nodal.shape_index\"(%result, %zero) <{metadata = {}}> : (" +
                  vec + ", index) -> " + u8 +
                  "\n"
                  "%wire = \"nodal.digital_storage\"() <{name = \"out\", kind = \"wire\", metadata "
                  "= {}}> : () -> " +
                  u8 +
                  "\n"
                  "\"nodal.digital_assign\"(%wire, %selected) <{kind = \"continuous\", metadata = "
                  "{}}> : (" +
                  u8 + ", " + u8 +
                  ") -> ()\n"
                  "}) : () -> () }");
  check("collection-result-index-and-assignment", consumed, true);

  check("map-row-major-shape-preserved", map("2,3"), true);
  check("million-lanes-one-body", map("1000000"), true);
  check("singleton", map("1"), true);
  check("nested-vector-whole-element", map("3", shaped("2")), true);
  check("symbolic-positive-envelope",
        model(parameter("N") + input(shaped("N")) +
              collection("map", "%input", shaped("N"), "%element: " + u8, yield("%element", u8),
                         shaped("N"))),
        true);
  check("symbolic-equal-default-not-equal-shape",
        model(parameter("N") + parameter("M") + input(shaped("N")) + input(shaped("M"), "right") +
              collection("zip_with", "%input, %right", shaped("N") + ", " + shaped("M"),
                         "%left: " + u8 + ", %rightElement: " + u8, yield("%left", u8), shaped("N"),
                         "2, 0, 0")),
        false);
  check("symbolic-leaf-width",
        model(parameter("W") + input(shaped("4", "!nodal.digital<\"uint\", \"W\">")) +
              collection("map", "%input", shaped("4", "!nodal.digital<\"uint\", \"W\">"),
                         "%element: !nodal.digital<\"uint\", \"W\">",
                         yield("%element", "!nodal.digital<\"uint\", \"W\">"),
                         shaped("4", "!nodal.digital<\"uint\", \"W\">"))),
        true);
  const std::string equivalentWidths =
      parameter("W") +
      "%w = \"nodal.const_parameter_ref\"() <{parameter = @W, metadata = {}}> : () -> i64\n"
      "%one = \"nodal.const_literal\"() <{value = 1 : i64, spelling = \"1\", metadata = {}}> : "
      "() -> i64\n"
      "%widthA = \"nodal.const_expr\"(%w, %one) <{operator_name = \"add\", metadata = "
      "{semantic_path = \"Fixture.widthA\"}}> : (i64, i64) -> i64\n"
      "%widthB = \"nodal.const_expr\"(%w, %one) <{operator_name = \"add\", metadata = "
      "{semantic_path = \"Fixture.widthB\"}}> : (i64, i64) -> i64\n";
  const std::string widthA = "!nodal.digital<\"uint\", \"Fixture.widthA\">";
  const std::string widthB = "!nodal.digital<\"uint\", \"Fixture.widthB\">";
  check("equivalent-owned-width-DAG-argument-and-result",
        model(equivalentWidths + input(shaped("4", widthA)) +
              collection("map", "%input", shaped("4", widthA), "%element: " + widthB,
                         yield("%element", widthB), shaped("4", widthA))),
        true);
  check("equivalent-owned-dimension-DAG",
        model(equivalentWidths + input(shaped("Fixture.widthA")) +
              collection("map", "%input", shaped("Fixture.widthA"), "%element: " + u8,
                         yield("%element", u8), shaped("Fixture.widthB"))),
        true);

  const std::string record = "!nodal.struct<\"Packet\", [{name = \"data\", type = !nodal.uint<8>}, "
                             "{name = \"valid\", type = !nodal.bits<1>}]>";
  check("whole-struct-element", map("4", record), true);
  check("root-Integer-element-reject", map("4", "i64"), false);
  check("root-Real-element-reject", map("4", "f64"), false);
  check("stored-Integer-Real-fields-retained",
        map("4", "!nodal.struct<\"Stored\", [{name = \"count\", type = i64}, {name = \"sample\", "
                 "type = f64}]>"),
        true);

  const std::string enumMetadata =
      "{abi_version = 1 : i64, semantic_type = \"example.Mode\", abi_hash = "
      "\"cb807326542df7db5314f8d795ea4384c2b40209497084c69a41f9ccd9faf46b\"}";
  const std::string enumDefinition =
      "\"nodal.enum\"() <{sym_name = \"Mode\", underlying_type = !nodal.uint<3>, "
      "encoding = \"custom\", metadata = " +
      enumMetadata +
      "}> ({\n"
      "\"nodal.enum_case\"() <{sym_name = \"Idle\", value = 0 : i64, metadata = {case_id = "
      "\"example.Mode.Idle\"}}> : () -> ()\n"
      "\"nodal.enum_case\"() <{sym_name = \"Run\", value = 3 : i64, metadata = {case_id = "
      "\"example.Mode.Run\"}}> : () -> ()\n"
      "\"nodal.enum_case\"() <{sym_name = \"Stop\", value = 7 : i64, metadata = {case_id = "
      "\"example.Mode.Stop\"}}> : () -> ()\n"
      "}) : () -> ()\n";
  const std::string enumeration = "!nodal.enum<\"Mode\", 3>";
  check("whole-enum-element",
        replace(map("4", enumeration), "module {", "module {" + enumDefinition), true);
  const std::string enumMapped = replace(
      model(input(shaped("4", enumeration)) +
            collection("map", "%input", shaped("4", enumeration), "%element: " + enumeration,
                       "%bits = \"nodal.enum_expr\"(%element) <{operator_name = \"enum_as_bits\", "
                       "enum_ref = @Mode, "
                       "case_ids = [], metadata = " +
                           enumMetadata + "}> : (" + enumeration + ") -> !nodal.bits<3>\n" +
                           yield("%bits", "!nodal.bits<3>"),
                       shaped("4", "!nodal.bits<3>"))),
      "module {", "module {" + enumDefinition);
  check("ordinary-enum-body-verifier", enumMapped, true);
  check("enum-body-wrong-definition",
        replace(enumMapped, "enum_ref = @Mode", "enum_ref = @Unknown"), false);
  const std::string widened = model(
      input(vec) + collection("map", "%input", vec, "%element: " + u8,
                              expression("digital_add", "%element, %element", u8 + ", " + u8, u9) +
                                  yield("%value", u9),
                              shaped("4", u9)));
  check("map-width-changing", widened, true);
  check("body-unit-attribute-reject", widened, false, [&](Operation *root) {
    root->walk([&](nodal::DigitalExprOp op) {
      op->setAttr("unit", FlatSymbolRefAttr::get(&context, "V"));
    });
  });
  check(
      "unused-body-Real-conversion-reject",
      replace(identity, yield("%element", u8),
              expression("digital_to_real", "%element", u8, "f64", "real") + yield("%element", u8)),
      false);
  check("unused-body-Real-literal-reject",
        replace(identity, yield("%element", u8),
                "%real = \"nodal.constant\"() <{value = 1.0 : f64, metadata = {}}> : () -> f64\n" +
                    yield("%element", u8)),
        false);
  check("map-invalid-opcode-width", widened, false, [&](Operation *root) {
    root->walk([&](nodal::DigitalExprOp op) {
      op.getResult().setType(nodal::UIntType::get(&context, 8));
    });
    root->walk([&](nodal::CollectionOp op) {
      op.getResult().setType(
          nodal::ShapedType::get(&context, "4", nodal::UIntType::get(&context, 8)));
    });
  });
  const std::string zip =
      model(input(vec) + input(shaped("4", u9), "right") +
            collection("zip_with", "%input, %right", vec + ", " + shaped("4", u9),
                       "%left: " + u8 + ", %rightElement: " + u9, yield("%rightElement", u9),
                       shaped("4", u9), "2, 0, 0"));
  check("zip-observable-right-distinct-type", zip, true);
  check("zip-equal-count-different-shape", replace(zip, "type = " + vec, "type = " + shaped("2,2")),
        false, [&](Operation *root) {
          root->walk([&](nodal::PortValueOp op) {
            if (op.getPort() == "input")
              op.getResult().setType(
                  nodal::ShapedType::get(&context, "2,2", nodal::UIntType::get(&context, 8)));
          });
        });
  const std::string s8 = "!nodal.sint<8>", s9 = "!nodal.sint<9>";
  const std::string signedVec = shaped("2,3", s8);
  const std::string foldBody =
      expression("digital_sub", "%acc, %element", s8 + ", " + s8, s9, "wide") +
      expression("digital_wrap", "%wide", s9, s8) + yield("%value", s8);
  auto fold = [&](StringRef kind, StringRef output) {
    return model(input(signedVec) + input(s8, "seed") +
                 collection(kind, "%input, %seed", signedVec + ", " + s8,
                            "%acc: " + s8 + ", %element: " + s8, foldBody, output, "1, 1, 0"));
  };
  check("ordered-homogeneous-fold-explicit-wrap", fold("fold_left", s8), true);
  check("scan-seed-excluded-shape", fold("scan_left", signedVec), true);
  check("scan-reject-seed-inclusive-count", fold("scan_left", shaped("7", s8)), false);
  check("fold-reject-changing-accumulator", fold("fold_left", s8), false, [](Operation *root) {
    root->walk([&](nodal::CollectionYieldOp op) {
      auto wide = op->getBlock()->begin();
      op->setOperand(0, wide->getResult(0));
    });
  });
  const std::string captured = model(
      input(vec) + input(u8, "outer") +
      collection("map", "%input, %outer", vec + ", " + u8, "%element: " + u8 + ", %capture: " + u8,
                 expression("digital_and", "%element, %capture", u8 + ", " + u8, u8) +
                     yield("%value", u8),
                 vec, "1, 0, 1"));
  check("explicit-capture", captured, true);
  check("implicit-capture-reject", replace(captured, "%element, %capture", "%element, %outer"),
        false);
  const std::string positiveCapture = "%outer = \"nodal.const_literal\"() <{value = 3 : i64, "
                                      "spelling = \"3\", metadata = {}}> : () -> " +
                                      s8 + "\n";
  const std::string checkedCaptureBody =
      expression("digital_to_unsigned", "%capture", s8, u8, "unsigned") +
      expression("digital_resize_checked", "%unsigned", u8, "!nodal.uint<2>") +
      yield("%value", "!nodal.uint<2>");
  const std::string capturedProof = model(
      input(vec) + positiveCapture +
      collection("map", "%input, %outer", vec + ", " + s8, "%element: " + u8 + ", %capture: " + s8,
                 checkedCaptureBody, shaped("4", "!nodal.uint<2>"), "1, 0, 1"));
  check("capture-follows-positive-literal-proof", capturedProof, true);
  check("capture-negative-literal-does-not-prove-unsigned",
        replace(replace(capturedProof, "value = 3 : i64", "value = -1 : i64"), "spelling = \"3\"",
                "spelling = \"-1\""),
        false);
  check("capture-proof-rejects-forged-segments",
        replace(capturedProof, "array<i32: 1, 0, 1>", "array<i32: 1, 1, 0>"), false);
  check("capture-proof-rejects-escaping-argument", capturedProof, false, [](Operation *root) {
    root->walk([&](nodal::CollectionOp op) {
      op->setOperand(1, op->getRegion(0).front().getArgument(1));
    });
  });
  check("capture-proof-rejects-forward-producer", capturedProof, false, [](Operation *root) {
    nodal::CollectionOp collection;
    root->walk([&](nodal::CollectionOp op) { collection = op; });
    collection->getOperand(1).getDefiningOp()->moveAfter(collection);
  });
  check("capture-proof-rejects-foreign-producer",
        replace(capturedProof, "}) : () -> () }",
                "}) : () -> () \"nodal.module\"() <{sym_name = \"Other\", metadata = {}}> ({" +
                    positiveCapture + "}) : () -> () }"),
        false, [](Operation *root) {
          Value foreign;
          nodal::CollectionOp selected;
          root->walk([&](nodal::ConstLiteralOp op) {
            if (op->getParentOfType<nodal::ModuleOp>().getSymName() == "Other")
              foreign = op.getResult();
          });
          root->walk([&](nodal::CollectionOp op) { selected = op; });
          selected->setOperand(1, foreign);
        });
  check("nested-capture-preserves-proof",
        model(input(shaped("2", vec)) + positiveCapture +
              collection("map", "%input, %outer", shaped("2", vec) + ", " + s8,
                         "%row: " + vec + ", %parentCapture: " + s8,
                         collection("map", "%row, %parentCapture", vec + ", " + s8,
                                    "%element: " + u8 + ", %capture: " + s8, checkedCaptureBody,
                                    shaped("4", "!nodal.uint<2>"), "1, 0, 1", "Fixture.nested",
                                    "nested") +
                             yield("%nested", shaped("4", "!nodal.uint<2>")),
                         shaped("2", shaped("4", "!nodal.uint<2>")), "1, 0, 1")),
        true);
  check("positive-seed-does-not-prove-recurrence-accumulator",
        model(input(signedVec) + positiveCapture +
              collection("fold_left", "%input, %outer", signedVec + ", " + s8,
                         "%acc: " + s8 + ", %element: " + s8,
                         expression("digital_to_unsigned", "%acc", s8, u8, "unsigned") +
                             expression("digital_reinterpret_signed", "%unsigned", u8, s8) +
                             yield("%value", s8),
                         s8, "1, 1, 0")),
        false);
  check("whole-struct-select",
        model(input(shaped("4", record)) + input(record, "outer") +
              input("!nodal.bits<1>", "predicate") +
              collection("map", "%input, %outer, %predicate",
                         shaped("4", record) + ", " + record + ", !nodal.bits<1>",
                         "%element: " + record + ", %capture: " + record +
                             ", %condition: !nodal.bits<1>",
                         expression("digital_select", "%condition, %element, %capture",
                                    "!nodal.bits<1>, " + record + ", " + record, record) +
                             yield("%value", record),
                         shaped("4", record), "1, 0, 2")),
        true);
  const std::string indexed = model(
      input(shaped("3", vec)) +
      collection("map", "%input", shaped("3", vec), "%element: " + vec,
                 "%zero = \"nodal.constant\"() <{value = 0 : index, metadata = {}}> : () -> index\n"
                 "%selected = \"nodal.shape_index\"(%element, %zero) <{metadata = {}}> : (" +
                     vec + ", index) -> " + u8 + "\n" + yield("%selected", u8),
                 shaped("3")));
  check("index-whole-nested-vector-argument", indexed, true);
  check("index-body-out-of-bounds", replace(indexed, "value = 0 : index", "value = 4 : index"),
        false);
  const std::string reshaped =
      model(input(shaped("3", matrix)) +
            collection("map", "%input", shaped("3", matrix), "%element: " + matrix,
                       "%view = \"nodal.shape_view\"(%element) <{dimensions = \"6\", origin = "
                       "\"Fixture.c.arg0\", "
                       "materialization = \"view\", observability = \"source_mapped\", metadata = "
                       "{storage = \"structural\", "
                       "semantic_path = \"Fixture.c.view\"}}> : (" +
                           matrix + ") -> " + shaped("6") + "\n" + yield("%view", shaped("6")),
                       shaped("3", shaped("6"))));
  check("reshape-whole-nested-argument", reshaped, true);
  const std::string symbolicReshaped =
      model(equivalentWidths + input(shaped("3", shaped("4", widthA))) +
            collection("map", "%input", shaped("3", shaped("4", widthA)),
                       "%element: " + shaped("4", widthA),
                       "%view = \"nodal.shape_view\"(%element) <{dimensions = \"2,2\", origin = "
                       "\"Fixture.c.arg0\", materialization = \"view\", observability = "
                       "\"source_mapped\", metadata = {storage = \"structural\", semantic_path = "
                       "\"Fixture.c.view\"}}> : (" +
                           shaped("4", widthA) + ") -> " + shaped("2,2", widthB) + "\n" +
                           yield("%view", shaped("2,2", widthB)),
                       shaped("3", shaped("2,2", widthB))));
  check("reshape-equivalent-owned-element-width-DAG", symbolicReshaped, true);
  check("reshape-argument-source-identity-forgery",
        replace(reshaped, "origin = \"Fixture.c.arg0\"", "origin = \"Fixture.c.arg1\""), false);
  auto selector = [&](StringRef name, StringRef value) {
    return "%" + name.str() + " = \"nodal.const_literal\"() <{value = " + value.str() +
           " : i64, spelling = \"" + value.str() + "\", metadata = {}}> : () -> i64\n";
  };
  const std::string sliced = model(
      input(shaped("3", vec)) +
      collection(
          "map", "%input", shaped("3", vec), "%element: " + vec,
          selector("lower", "1") + selector("upper", "3") + selector("length", "2") +
              "%slice = \"nodal.shape_slice\"(%element, %lower, %upper, %length) <{axis = 0 : i64, "
              "dimensions = \"2\", origin = \"Fixture.c.arg0\", observability = \"source_mapped\", "
              "metadata = {storage = \"structural\", access = \"read_only\", input_path = "
              "\"Fixture.c.arg0\", "
              "semantic_path = \"Fixture.c.slice\"}}> : (" +
              vec + ", i64, i64, i64) -> " + shaped("2") + "\n" + yield("%slice", shaped("2")),
          shaped("3", shaped("2"))));
  check("slice-whole-nested-argument", sliced, true);
  const std::string slicedView =
      "%view = \"nodal.shape_view\"(%element) <{dimensions = \"4\", origin = \"Fixture.c.arg0\", "
      "materialization = \"view\", observability = \"source_mapped\", metadata = {storage = "
      "\"structural\", semantic_path = \"Fixture.c.view\"}}> : (" +
      vec + ") -> " + vec + "\n";
  check("slice-body-local-view",
        replace(replace(replace(replace(sliced, selector("lower", "1"),
                                        slicedView + selector("lower", "1")),
                                "\"nodal.shape_slice\"(%element", "\"nodal.shape_slice\"(%view"),
                        "origin = \"Fixture.c.arg0\", observability = \"source_mapped\", "
                        "metadata = {storage = \"structural\", access = \"read_only\"",
                        "origin = \"Fixture.c.view\", observability = \"source_mapped\", "
                        "metadata = {storage = \"structural\", access = \"read_only\""),
                "input_path = \"Fixture.c.arg0\"", "input_path = \"Fixture.c.view\""),
        true);
  check("slice-argument-source-identity-forgery",
        replace(sliced, "input_path = \"Fixture.c.arg0\"", "input_path = \"Fixture.c.arg1\""),
        false);
  const std::string nestedInput = shaped("3", vec);
  const std::string nested =
      model(input(nestedInput) + input(u8, "outer") +
            collection(
                "map", "%input, %outer", nestedInput + ", " + u8,
                "%element: " + vec + ", %capture: " + u8,
                collection("map", "%element, %capture", vec + ", " + u8,
                           "%inner: " + u8 + ", %nestedCapture: " + u8,
                           expression("digital_and", "%inner, %nestedCapture", u8 + ", " + u8, u8) +
                               yield("%value", u8),
                           vec, "1, 0, 1", "Fixture.c.nested", "nested") +
                    yield("%nested", vec),
                nestedInput, "1, 0, 1"));
  check("nested-explicit-parent-capture", nested, true);
  check("nested-bypass-capture-reject",
        replace(nested, "%inner, %nestedCapture", "%inner, %capture"), false);
  check("negative-segment", replace(identity, "1, 0, 0", "1, -1, 1"), false);
  check("segment-sum-overflow-reject", replace(identity, "1, 0, 0", "2147483647, 2147483647, 0"),
        false);
  check("segment-total", replace(identity, "1, 0, 0", "1, 0, 1"), false);
  // ODS stores a fixed three-element property and rejects its wrong length
  // during parsing, before an operation exists for the model verifier.
  check("segment-count", replace(identity, "1, 0, 0", "1, 0"), false, {}, true);
  check("map-unexpected-seed", replace(captured, "1, 0, 1", "1, 1, 0"), false);
  check("unsupported-reduction-kind", replace(identity, "kind = \"map\"", "kind = \"reduce\""),
        false);
  check("wrong-argument-type", identity, false, [&](Operation *root) {
    root->walk([&](nodal::CollectionOp op) {
      op->getRegion(0).front().getArgument(0).setType(nodal::UIntType::get(&context, 9));
    });
  });
  check("wrong-argument-count", identity, false, [&](Operation *root) {
    root->walk([&](nodal::CollectionOp op) {
      op->getRegion(0).front().addArgument(nodal::UIntType::get(&context, 8), op.getLoc());
    });
  });
  check("wrong-result-shape",
        replace(identity, "}) : (" + vec + ") -> " + vec, "}) : (" + vec + ") -> " + shaped("2,2")),
        false);
  check("forged-source-identity",
        replace(identity, "region_id = \"Fixture.c\"", "region_id = \"Other.c\""), false);
  check("missing-yield", replace(identity, yield("%element", u8), ""), false);
  check("extra-yield",
        replace(identity, yield("%element", u8), yield("%element", u8) + yield("%element", u8)),
        false);
  check("unknown-body-effect",
        replace(identity, yield("%element", u8),
                "\"nodal.placeholder\"() <{label = \"effect\"}> : () -> ()\n" +
                    yield("%element", u8)),
        false);
  check("body-storage-declaration",
        replace(identity, yield("%element", u8),
                "%state = \"nodal.digital_storage\"() <{name = \"x\", kind = \"wire\", metadata = "
                "{}}> : () -> " +
                    u8 + "\n" + yield("%element", u8)),
        false);
  check("escaped-body-value", captured, false, [&](Operation *root) {
    nodal::CollectionOp collection;
    root->walk([&](nodal::CollectionOp op) { collection = op; });
    collection->setOperand(1, collection->getRegion(0).front().getArgument(0));
  });
  check("sibling-region-capture",
        model(input(vec) + input(u8, "outer") +
              collection("map", "%input", vec, "%left: " + u8, yield("%left", u8), vec, "1, 0, 0",
                         "Fixture.first", "first") +
              collection("map", "%input, %outer", vec + ", " + u8,
                         "%right: " + u8 + ", %cap: " + u8, yield("%right", u8), vec, "1, 0, 1")),
        false, [](Operation *root) {
          SmallVector<nodal::CollectionOp> collections;
          root->walk([&](nodal::CollectionOp op) { collections.push_back(op); });
          collections[1]->setOperand(1, collections[0]->getRegion(0).front().getArgument(0));
        });
  check("scalar-Real-through-neutral-view-reject",
        model("%real = \"nodal.constant\"() <{value = 0.0 : f64, metadata = {}}> : () -> f64\n"
              "%input = \"nodal.shape_view\"(%real) <{dimensions = \"4\", materialization = "
              "\"explicit_view\", "
              "origin = \"Fixture.real\", observability = \"source_mapped\", metadata = {storage = "
              "\"structural\"}}> : (f64) -> " +
              vec + "\n" +
              collection("map", "%input", vec, "%element: " + u8, yield("%element", u8), vec)),
        false);
  check("root-Integer-capture-reject",
        model(input(vec) + input("i64", "outer") +
              collection("map", "%input, %outer", vec + ", i64",
                         "%element: " + u8 + ", %capture: i64", yield("%element", u8), vec,
                         "1, 0, 1")),
        false);
  check("capture-unit-attribute-reject", identity, false, [&](Operation *root) {
    root->walk(
        [&](nodal::PortValueOp op) { op->setAttr("unit", FlatSymbolRefAttr::get(&context, "V")); });
  });
  check("collection-unit-attribute-reject", identity, false, [&](Operation *root) {
    root->walk([&](nodal::CollectionOp op) {
      op->setAttr("unit", FlatSymbolRefAttr::get(&context, "V"));
    });
  });
  check("yield-unit-attribute-reject", identity, false, [&](Operation *root) {
    root->walk([&](nodal::CollectionYieldOp op) {
      op->setAttr("unit", FlatSymbolRefAttr::get(&context, "V"));
    });
  });
  check("input-defined-after-use", identity, false, [](Operation *root) {
    nodal::CollectionOp collection;
    nodal::PortValueOp value;
    root->walk([&](nodal::CollectionOp op) { collection = op; });
    root->walk([&](nodal::PortValueOp op) { value = op; });
    collection->moveBefore(value);
  });
  check("duplicate-region-identity",
        model(input(vec) +
              collection("map", "%input", vec, "%element: " + u8, yield("%element", u8), vec,
                         "1, 0, 0", "Fixture.c", "first") +
              collection("map", "%input", vec, "%element: " + u8, yield("%element", u8), vec)),
        false);
  check("extra-region-block", identity, false, [](Operation *root) {
    root->walk([&](nodal::CollectionOp op) { op->getRegion(0).push_back(new Block()); });
  });
  check("yield-extra-operand", identity, false, [](Operation *root) {
    root->walk([&](nodal::CollectionYieldOp op) {
      Value value = op->getOperand(0);
      op->setOperands({value, value});
    });
  });
  check("cross-module-input",
        replace(identity, "}) : () -> () }",
                "}) : () -> () "
                "\"nodal.module\"() <{sym_name = \"Other\", metadata = {}}> ({" +
                    input(vec, "foreign") + "}) : () -> () }"),
        false, [](Operation *root) {
          Value foreign;
          nodal::CollectionOp selected;
          root->walk([&](nodal::PortValueOp op) {
            if (op.getPort() == "foreign")
              foreign = op.getResult();
          });
          root->walk([&](nodal::CollectionOp op) { selected = op; });
          selected->setOperand(0, foreign);
        });

  {
    // The Module inventory checks its direct Collections. Generated bodies are
    // checked through the exact operation query, not that unrelated direct scope.
    auto generatedCheck = [&](StringRef name, const std::string &source, bool expected,
                              std::function<void(Operation *)> mutate = {},
                              StringRef region = "Fixture.c", StringRef knownFullRefusal = {}) {
      ++cases;
      std::string messages;
      ScopedDiagnosticHandler handler(&context, [&](Diagnostic &diagnostic) {
        llvm::raw_string_ostream stream(messages);
        diagnostic.print(stream);
        return success();
      });
      auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
      bool good = static_cast<bool>(parsed);
      if (parsed) {
        if (mutate)
          mutate(*parsed);
        nodal::CollectionOp selected;
        parsed->walk([&](nodal::CollectionOp op) {
          if (op->getAttrOfType<StringAttr>("region_id").getValue() == region)
            selected = op;
        });
        const std::string before = print(*parsed);
        good &=
            selected && succeeded(nodal::verifyDigitalCollectionOperation(selected)) == expected;
        messages.clear();
        bool full = succeeded(verify(*parsed));
        good &= full == (expected && knownFullRefusal.empty());
        if (!knownFullRefusal.empty())
          good &= StringRef(messages).contains(knownFullRefusal);
        good &= before == print(*parsed);
      }
      failures += !good;
      (good ? llvm::outs() : llvm::errs())
          << "DIGITAL_COLLECTION_MODEL_" << (good ? "PASS " : "FAIL ") << name << '\n';
      if (!good)
        llvm::errs() << messages << '\n';
    };
    // Generated coordinates cross only actual static capture slots. They never
    // become ordinary reducer width formals or implicit ancestor token reads.
    auto integer = [&](StringRef name, int64_t value) {
      auto number = std::to_string(value);
      return "%" + name.str() + " = \"nodal.const_literal\"() <{value = " + number +
             " : i64, spelling = \"" + number + "\", metadata = {}}> : () -> i64\n";
    };
    auto generated = [&](const std::string &body, int64_t count = 3) {
      return integer("lo", 0) + integer("hi", count) + integer("step", 1) +
             "%family = \"nodal.generate\"(%lo, %hi, %step) <{induction = \"i\", "
             "region_id = \"Fixture.family\", induction_path = \"Fixture.family.i\", "
             "metadata = {maximum_trip_count = " +
             std::to_string(count) + " : i64}}> ({^bb0(%i: i64):\n" + body +
             "}) : (i64, i64, i64) -> !nodal.generated_family\n";
    };
    auto symbolic = [&](StringRef token) {
      return "!nodal.digital<\"sint\", \"" + token.str() + "\">";
    };
    const std::string generatedWidth =
        integer("one", 1) +
        "%w = \"nodal.const_expr\"(%i, %one) <{operator_name = \"add\", "
        "metadata = {semantic_path = \"Fixture.width\"}}> : (i64, i64) -> i64\n";
    const std::string bits = "!nodal.bits<1>", bitVector = shaped("4", bits),
                      capturedWidth = symbolic("Fixture.c.arg1"),
                      actualWidth = symbolic("Fixture.width");
    auto signedLiteral = [&](StringRef type) {
      return "%negative = \"nodal.const_literal\"() <{value = -1 : i64, spelling = \"-1\", "
             "metadata = {}}> : () -> " +
             type.str() + "\n";
    };
    const auto staticMap = collection(
        "map", "%input, %w", bitVector + ", i64", "%element: " + bits + ", %captured: i64",
        signedLiteral(capturedWidth) + yield("%negative", capturedWidth), shaped("4", actualWidth),
        "1, 0, 1");
    const auto staticCaptured = model(input(bitVector) + generated(generatedWidth + staticMap));
    generatedCheck("generated-static-width-explicit-capture", staticCaptured, true);
    const std::string dynamicInput = shaped("Fixture.width", bits);
    const std::string sliced =
        integer("zero", 0) +
        "%slice = \"nodal.shape_slice\"(%input, %zero, %w, %w) <{axis = 0 : i64, "
        "dimensions = \"Fixture.width\", origin = \"Fixture.input\", observability = "
        "\"source_mapped\", metadata = {storage = \"structural\", access = \"read_only\", "
        "input_path = \"Fixture.input\", semantic_path = \"Fixture.slice\"}}> : (" +
        bitVector + ", i64, i64, i64) -> " + dynamicInput + "\n";
    const auto mappedInput =
        replace(replace(input(bitVector), "metadata = {}",
                        "metadata = {semantic_path = \"Fixture.input\"}"),
                "metadata = {}", "metadata = {semantic_path = \"Fixture.input\"}");
    const auto dynamicMap = collection(
        "map", "%slice, %w", dynamicInput + ", i64", "%element: " + bits + ", %captured: i64",
        signedLiteral(capturedWidth) + yield("%negative", capturedWidth),
        shaped("Fixture.width", actualWidth), "1, 0, 1");
    generatedCheck("generated-shape-count-uses-actual-outer-context",
                   model(mappedInput + generated(generatedWidth + sliced + dynamicMap)), true);
    generatedCheck(
        "generated-capture-cannot-read-raw-outer-token",
        replace(replace(staticCaptured, signedLiteral(capturedWidth), signedLiteral(actualWidth)),
                yield("%negative", capturedWidth), yield("%negative", actualWidth)),
        false);
    generatedCheck("generated-capture-cannot-read-raw-outer-SSA",
                   replace(staticCaptured, "%element: " + bits + ", %captured: i64",
                           "%element: " + bits + ", %captured: i64"),
                   false, [](Operation *root) {
                     nodal::CollectionOp collection;
                     root->walk([&](nodal::CollectionOp op) { collection = op; });
                     auto &body = collection->getRegion(0).front();
                     OpBuilder builder(&body, body.begin());
                     OperationState state(collection.getLoc(), "nodal.const_expr");
                     state.addOperands({collection->getOperand(1), collection->getOperand(1)});
                     state.addTypes(builder.getI64Type());
                     state.addAttribute("operator_name", builder.getStringAttr("add"));
                     state.addAttribute("metadata", builder.getDictionaryAttr({}));
                     builder.create(state);
                   });
    generatedCheck("generated-capture-forward-source-rejected", staticCaptured, false,
                   [](Operation *root) {
                     nodal::CollectionOp collection;
                     root->walk([&](nodal::CollectionOp op) { collection = op; });
                     collection->getOperand(1).getDefiningOp()->moveAfter(collection);
                   });
    generatedCheck(
        "generated-capture-runtime-integer-rejected",
        model(input(bitVector) + input("i64", "runtime") +
              generated(generatedWidth + replace(staticMap, "%input, %w", "%input, %runtime"))),
        false);
    generatedCheck("generated-capture-nonpositive-width-rejected",
                   model(input(bitVector) +
                         generated(replace(generatedWidth, "%i, %one", "%i, %i") + staticMap)),
                   false);
    generatedCheck("generated-empty-capture-invents-no-numeric-width",
                   model(input(bitVector) + generated(generatedWidth + staticMap, 0)), false);
    const std::string parameterRef = "%parameter = \"nodal.const_parameter_ref\"() "
                                     "<{parameter = @W, metadata = {}}> : () -> i64\n";
    const auto parameterWidth = replace(generatedWidth, "%i, %one", "%i, %parameter");
    generatedCheck("generated-capture-keeps-width-effect-proof",
                   model(replace(parameter("W"), "[\"shape\", \"width\"]", "[\"shape\"]") +
                         parameterRef + input(bitVector) + generated(parameterWidth + staticMap)),
                   false);
    generatedCheck("generated-capture-keeps-shape-effect-proof",
                   model(replace(parameter("W"), "[\"shape\", \"width\"]", "[\"width\"]") +
                         parameterRef + input(bitVector) +
                         generated(parameterWidth + sliced + dynamicMap)),
                   false);

    auto sliceCheck = [&](StringRef name, const std::string &source, bool expected,
                          std::function<void(Operation *)> mutate = {}) {
      ++cases;
      std::string messages;
      ScopedDiagnosticHandler handler(&context, [&](Diagnostic &diagnostic) {
        llvm::raw_string_ostream stream(messages);
        diagnostic.print(stream);
        return success();
      });
      auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
      bool good = static_cast<bool>(parsed);
      if (parsed) {
        if (mutate)
          mutate(*parsed);
        nodal::ShapeSliceOp slice;
        parsed->walk([&](nodal::ShapeSliceOp op) {
          auto metadata = op->getAttrOfType<DictionaryAttr>("metadata");
          if (metadata && metadata.getAs<StringAttr>("semantic_path").getValue() == "Fixture.slice")
            slice = op;
        });
        const std::string before = print(*parsed);
        good &= slice && succeeded(nodal::verifyShapeSliceMapping(slice)) == expected;
        good &= succeeded(verify(*parsed)) == expected;
        good &= before == print(*parsed);
      }
      failures += !good;
      (good ? llvm::outs() : llvm::errs())
          << "DIGITAL_COLLECTION_MODEL_" << (good ? "PASS " : "FAIL ") << name << '\n';
      if (!good)
        llvm::errs() << messages << '\n';
    };
    const auto generatedSlice = model(mappedInput + generated(generatedWidth + sliced));
    sliceCheck("generated-slice-actual-all-lane-endpoints", generatedSlice, true);
    sliceCheck("generated-slice-length-identity-is-not-just-bounds",
               replace(generatedSlice, "%input, %zero, %w, %w", "%input, %zero, %w, %one"), false);
    sliceCheck("generated-slice-upper-must-fit-every-source-extent",
               model(mappedInput + generated(generatedWidth + sliced, 5)), false);
    sliceCheck("generated-slice-negative-lower-refused",
               replace(generatedSlice, integer("zero", 0), integer("zero", -1)), false);
    sliceCheck("generated-slice-empty-has-no-numeric-endpoint",
               model(mappedInput + generated(generatedWidth + sliced, 0)), false);
    sliceCheck("generated-slice-forward-endpoint-refused", generatedSlice, false,
               [](Operation *root) {
                 root->walk([&](nodal::ShapeSliceOp slice) {
                   slice->getOperand(2).getDefiningOp()->moveAfter(slice);
                 });
               });
    sliceCheck(
        "generated-slice-unsigned-endpoint-refused", generatedSlice, false, [&](Operation *root) {
          root->walk([&](nodal::ShapeSliceOp slice) {
            slice->getOperand(1).setType(IntegerType::get(&context, 64, IntegerType::Unsigned));
          });
        });
    sliceCheck("generated-slice-runtime-endpoint-refused",
               model(mappedInput + input("i64", "runtime") + generated(generatedWidth + sliced)),
               false, [](Operation *root) {
                 Value runtime;
                 nodal::ShapeSliceOp slice;
                 root->walk([&](nodal::PortValueOp op) {
                   if (op.getPort() == "runtime")
                     runtime = op.getResult();
                 });
                 root->walk([&](nodal::ShapeSliceOp op) { slice = op; });
                 slice->setOperand(2, runtime);
                 slice->setOperand(3, runtime);
               });
    std::string boundedParameter =
        replace(parameter("W"), "default_value = 4", "default_value = 1");
    boundedParameter = replace(boundedParameter, "value = 1000000", "value = 1");
    boundedParameter = replace(boundedParameter, "spelling = \"1000000\"", "spelling = \"1\"");
    const auto parameterSlice =
        model(boundedParameter + parameterRef + mappedInput + generated(parameterWidth + sliced));
    sliceCheck("generated-slice-structural-parameter-shape-effect", parameterSlice, true);
    sliceCheck("generated-slice-missing-shape-effect-refused",
               replace(parameterSlice, "[\"shape\", \"width\"]", "[\"width\"]"), false);
    const auto foreignSlice =
        replace(generatedSlice, "}) : () -> () }",
                "}) : () -> () \"nodal.module\"() <{sym_name = \"Other\", metadata = {}}> ({" +
                    integer("foreign", 2) + "}) : () -> () }");
    sliceCheck("generated-slice-foreign-value-is-not-numeric-equivalence", foreignSlice, false,
               [](Operation *root) {
                 Value foreign;
                 nodal::ShapeSliceOp slice;
                 root->walk([&](nodal::ConstLiteralOp op) {
                   if (op->getParentOfType<nodal::ModuleOp>()
                           ->getAttrOfType<StringAttr>("sym_name")
                           .getValue() == "Other")
                     foreign = op.getResult();
                 });
                 root->walk([&](nodal::ShapeSliceOp op) { slice = op; });
                 slice->setOperand(2, foreign);
                 slice->setOperand(3, foreign);
               });
    auto lengthSlice = replace(sliced, "%input, %zero, %w, %w", "%input, %one, %w, %length");
    lengthSlice =
        replace(lengthSlice, "dimensions = \"Fixture.width\"", "dimensions = \"Fixture.length\"");
    lengthSlice =
        replace(lengthSlice, shaped("Fixture.width", bits), shaped("Fixture.length", bits));
    const auto difference =
        "%length = \"nodal.const_expr\"(%w, %one) <{operator_name = \"sub\", "
        "metadata = {semantic_path = \"Fixture.length\"}}> : (i64, i64) -> i64\n";
    sliceCheck(
        "generated-slice-retains-canonical-nonzero-lower-difference",
        model(mappedInput + replace(replace(generated(generatedWidth + difference + lengthSlice, 4),
                                            integer("lo", 0), integer("lo", 1)),
                                    "maximum_trip_count = 4", "maximum_trip_count = 3")),
        true);
    auto capturedSlice =
        replace(sliced, "%input, %zero, %w, %w", "%row, %zero, %captured, %captured");
    capturedSlice =
        replace(capturedSlice, "dimensions = \"Fixture.width\"", "dimensions = \"Fixture.c.arg1\"");
    capturedSlice =
        replace(capturedSlice, shaped("Fixture.width", bits), shaped("Fixture.c.arg1", bits));
    capturedSlice =
        replace(capturedSlice, "origin = \"Fixture.input\"", "origin = \"Fixture.c.arg0\"");
    capturedSlice =
        replace(capturedSlice, "input_path = \"Fixture.input\"", "input_path = \"Fixture.c.arg0\"");
    const auto capturedSliceMap =
        collection("map", "%matrix, %w", shaped("2", bitVector) + ", i64",
                   "%row: " + bitVector + ", %captured: i64",
                   capturedSlice + yield("%slice", shaped("Fixture.c.arg1", bits)),
                   shaped("2", shaped("Fixture.width", bits)), "1, 0, 1");
    const auto capturedSliceFixture = model(input(shaped("2", bitVector), "matrix") +
                                            generated(generatedWidth + capturedSliceMap));
    sliceCheck("generated-slice-follows-exact-Collection-capture", capturedSliceFixture, true);
    sliceCheck("generated-slice-cannot-bypass-Collection-capture", capturedSliceFixture, false,
               [](Operation *root) {
                 root->walk([&](nodal::ShapeSliceOp slice) {
                   Operation *collection = slice->getParentOp();
                   slice->setOperand(2, collection->getOperand(1));
                   slice->setOperand(3, collection->getOperand(1));
                 });
               });

    auto innerCapturedMap = replace(capturedSliceMap, "%matrix, %w", "%outerRow, %outerCaptured");
    innerCapturedMap = replace(innerCapturedMap, shaped("2", shaped("Fixture.width", bits)),
                               shaped("2", shaped("Fixture.outer.arg1", bits)));
    const auto nestedCapturedMap = collection(
        "map", "%cube, %w", shaped("2", shaped("2", bitVector)) + ", i64",
        "%outerRow: " + shaped("2", bitVector) + ", %outerCaptured: i64",
        innerCapturedMap + yield("%result", shaped("2", shaped("Fixture.outer.arg1", bits))),
        shaped("2", shaped("2", shaped("Fixture.width", bits))), "1, 0, 1", "Fixture.outer",
        "outerResult");
    sliceCheck("generated-slice-retains-nested-Collection-capture-chain",
               model(input(shaped("2", shaped("2", bitVector)), "cube") +
                     generated(generatedWidth + nestedCapturedMap)),
               true);
    auto siblingFamily = replace(generated(generatedWidth), "%family =", "%otherFamily =");
    siblingFamily =
        replace(siblingFamily, "region_id = \"Fixture.family\"", "region_id = \"Fixture.other\"");
    siblingFamily = replace(siblingFamily, "induction_path = \"Fixture.family.i\"",
                            "induction_path = \"Fixture.other.i\"");
    for (StringRef name : {"lo", "hi", "step"}) {
      std::string original = "%" + name.str(), renamed = "%other_" + name.str();
      siblingFamily = replace(replace(siblingFamily, original, renamed), original, renamed);
    }
    sliceCheck("generated-slice-same-spelled-sibling-is-not-the-actual-endpoint",
               model(mappedInput + siblingFamily + generated(generatedWidth + sliced)), false,
               [](Operation *root) {
                 Value foreign;
                 nodal::ShapeSliceOp slice;
                 root->walk([&](nodal::ConstExprOp op) {
                   auto family = op->getParentOfType<nodal::GenerateOp>();
                   if (family &&
                       family->getAttrOfType<StringAttr>("region_id").getValue() == "Fixture.other")
                     foreign = op.getResult();
                 });
                 root->walk([&](nodal::ShapeSliceOp op) { slice = op; });
                 slice->setOperand(2, foreign);
                 slice->setOperand(3, foreign);
               });
    const std::string innerWidth = symbolic("Fixture.inner.arg1"),
                      outerWidth = symbolic("Fixture.outer.arg1");
    const auto inner = collection("map", "%row, %outerCapture", bitVector + ", i64",
                                  "%innerElement: " + bits + ", %innerCapture: i64",
                                  signedLiteral(innerWidth) + yield("%negative", innerWidth),
                                  shaped("4", outerWidth), "1, 0, 1", "Fixture.inner", "inner");
    const auto nested = model(
        input(shaped("2", bitVector)) +
        generated(generatedWidth + collection("map", "%input, %w", shaped("2", bitVector) + ", i64",
                                              "%row: " + bitVector + ", %outerCapture: i64",
                                              inner + yield("%inner", shaped("4", outerWidth)),
                                              shaped("2", shaped("4", actualWidth)), "1, 0, 1",
                                              "Fixture.outer")));
    generatedCheck("generated-capture-nested-map-retains-original-induction", nested, true, {},
                   "Fixture.outer");
    generatedCheck("generated-capture-nested-map-cannot-bypass-parent-capture",
                   replace(nested, "%row, %outerCapture", "%row, %w"), false, {}, "Fixture.outer");
    const std::string L = "!nodal.digital<\"uint\", \"Fixture.reduce.arg0\">",
                      R = "!nodal.digital<\"uint\", \"Fixture.reduce.arg1\">";
    const auto reduction =
        collection("reduce_left", "%input", vec, "%L: i64, %R: i64, %left: " + L + ", %right: " + R,
                   expression("digital_xor", "%left, %right", L + ", " + R, L) + yield("%value", L),
                   u8, "1, 0, 0", "Fixture.reduce");
    generatedCheck("generated-reducer-formals-keep-ordinary-environment",
                   model(input(vec) + generated(reduction)), true, {}, "Fixture.reduce");
    auto applicability = [&](StringRef name, const std::string &source, unsigned budget,
                             int expected, bool repeat = false, bool forgeCapture = false) {
      ++cases;
      auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
      nodal::CollectionOp collection;
      if (parsed)
        parsed->walk([&](nodal::CollectionOp op) { collection = op; });
      if (collection && forgeCapture)
        collection->setOperand(1, collection->getOperand(0));
      unsigned remaining = budget;
      auto fact = collection ? nodal::inspectGeneratedCollectionTypeApplicability(
                                   &collection->getRegion(0).front(), remaining)
                             : FailureOr<bool>(failure());
      bool good = parsed && (failed(fact) ? -1 : *fact ? 1 : 0) == expected && remaining <= budget;
      if (repeat && succeeded(fact)) {
        unsigned previous = remaining;
        auto again = nodal::inspectGeneratedCollectionTypeApplicability(
            &collection->getRegion(0).front(), remaining);
        good &= succeeded(again) && *again == *fact && remaining < previous;
      }
      failures += !good;
      (good ? llvm::outs() : llvm::errs())
          << "DIGITAL_COLLECTION_MODEL_" << (good ? "PASS " : "FAIL ") << name << '\n';
    };
    applicability("generated-applicability-actual-captures", staticCaptured, 64, 1, true);
    applicability("generated-applicability-zero-budget", staticCaptured, 0, -1);
    applicability("generated-applicability-exhausted-budget", staticCaptured, 2, -1);
    applicability("generated-applicability-reducer-distinct-from-failure",
                  model(input(vec) + generated(reduction)), 64, 0);
    applicability("generated-applicability-forged-capture-type", staticCaptured, 64, -1, false,
                  true);
  }
  llvm::outs() << "digital collection model: " << cases - failures << '/' << cases << " passed\n";
  return failures ? 1 : 0;
}
