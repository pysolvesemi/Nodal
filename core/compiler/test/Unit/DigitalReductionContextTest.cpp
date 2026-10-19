#include "mlir/IR/Builders.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalMaterializationModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/DigitalValueModel.h"
#include "nodal/Dialect/Nodal/EnumValueModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/Support/raw_ostream.h"

#include <cstdint>
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

std::string symbolic(StringRef width, StringRef kind = "uint") {
  return "!nodal.digital<\"" + kind.str() + "\", \"" + width.str() + "\">";
}
std::string stat(StringRef name, StringRef opcode, StringRef operands, unsigned arity) {
  std::string types;
  for (unsigned i = 0; i < arity; ++i) {
    if (i)
      types += ", ";
    types += "i64";
  }
  return "%" + name.str() + " = \"nodal.const_expr\"(" + operands.str() + ") <{operator_name = \"" +
         opcode.str() + "\", metadata = {semantic_path = \"Fixture.c." + name.str() + "\"}}> : (" +
         types + ") -> i64\n";
}
std::string integer(StringRef name, int64_t value) {
  std::string number = std::to_string(value);
  return "%" + name.str() + " = \"nodal.const_literal\"() <{value = " + number +
         " : i64, spelling = \"" + number + "\", metadata = {semantic_path = \"Fixture.c." +
         name.str() + "\"}}> : () -> i64\n";
}
std::string record(StringRef selected) {
  return "!nodal.struct<\"Flags\", [{name = \"selected\", type = " + selected.str() +
         "}, {name = \"flag\", type = !nodal.bits<1>}]>";
}
std::string field(StringRef source, StringRef actual, StringRef schema, StringRef type,
                  unsigned ordinal, StringRef name, StringRef result) {
  return "%" + result.str() + " = \"nodal.digital_expr\"(" + source.str() +
         ") <{operator_name = \"digital_struct_get\", struct_schema = " + schema.str() +
         ", field_ordinal = " + std::to_string(ordinal) + " : i64, field_name = \"" + name.str() +
         "\", metadata = {}}> : (" + actual.str() + ") -> " + type.str() + "\n";
}
std::string makeFlags(StringRef a, StringRef b, StringRef result, StringRef schema) {
  return "%flags = \"nodal.digital_expr\"(%selected, %flag) <{operator_name = "
         "\"digital_struct_make\", struct_schema = " +
         schema.str() + ", metadata = {}}> : (" + a.str() + ", " + b.str() + ") -> " +
         result.str() + "\n";
}
std::string mixedFlags() {
  std::string one = "!nodal.bits<1>", a = symbolic("Fixture.c.arg0", "bits"),
              b = symbolic("Fixture.c.arg1", "bits");
  std::string initial = record(one), left = record(a), right = record(b),
              vec = shaped("3", initial);
  std::string body =
      field("%left", left, initial, a, 0, "selected", "selectedLeft") +
      field("%right", right, initial, b, 0, "selected", "selectedRight") +
      expression("bool_and", "%selectedLeft, %selectedRight", a + ", " + b, a, "selected") +
      field("%left", left, initial, one, 1, "flag", "oldFlag") +
      expression("bool_not", "%oldFlag", one, one, "flag") + makeFlags(a, one, left, initial) +
      yield("%flags", left);
  return model(input(vec) + collection("reduce_left", "%input", vec,
                                       "%L: i64, %R: i64, %left: " + left + ", %right: " + right,
                                       body, initial));
}
std::string selectedBoolean(bool singletonParameter = false, bool opaque = false,
                            int64_t count = 3) {
  std::string one = "!nodal.bits<1>", a = symbolic("Fixture.c.arg0", "bits"),
              b = symbolic("Fixture.c.arg1", "bits");
  std::string initial = singletonParameter ? symbolic("W", "bits") : one,
              vec = shaped(std::to_string(count), initial);
  std::string body = expression("bool_not", "%left", a, a, "inverted"), result = a;
  if (opaque) {
    body += "%coefficient = \"nodal.const_parameter_ref\"() <{parameter = @C, metadata = {}}> : () "
            "-> i64\n" +
            stat("maximum", "max", "%L, %R", 2) + stat("width", "add", "%maximum, %coefficient", 2);
    result = symbolic("Fixture.c.width", "bits");
    body += expression("digital_wrap", "%inverted", a, result, "wrapped");
  }
  body += yield(opaque ? "%wrapped" : "%inverted", result);
  std::string params;
  if (singletonParameter) {
    params = parameter("W");
    params = replace(params, "default_value = 4 : i64", "default_value = 1 : i64");
    params = replace(params, "value = 1000000 : i64, spelling = \"1000000\"",
                     "value = 1 : i64, spelling = \"1\"");
  }
  std::string finalType = initial;
  if (opaque) {
    params = parameter("C");
    params = replace(params, "default_value = 4 : i64", "default_value = 0 : i64");
    params =
        replace(params, "value = 1 : i64, spelling = \"1\"", "value = 0 : i64, spelling = \"0\"");
    params = replace(params, "value = 1000000 : i64, spelling = \"1000000\"",
                     "value = 0 : i64, spelling = \"0\"");
    params += "%moduleCoefficient = \"nodal.const_parameter_ref\"() <{parameter = @C, metadata = "
              "{}}> : () -> i64\n";
    params += integer("initialWidth", 1) + integer("factor", count - 1) +
              stat("growth", "mul", "%factor, %moduleCoefficient", 2) +
              stat("finalWidth", "add", "%initialWidth, %growth", 2);
    finalType = symbolic("Fixture.c.finalWidth", "bits");
  }
  return model(params + input(vec) +
               collection("reduce_left", "%input", vec,
                          "%L: i64, %R: i64, %left: " + a + ", %right: " + b, body, finalType));
}
std::string nestedProjection(bool wrong = false) {
  std::string a = symbolic("Fixture.c.arg0"), b = symbolic("Fixture.c.arg1"),
              initial = shaped("2", "!nodal.uint<4>"), left = shaped("2", a),
              right = shaped("2", b), vec = shaped("3", initial);
  std::string element = wrong ? "!nodal.uint<5>" : a;
  std::string body =
      "%zero = \"nodal.constant\"() <{value = 0 : index, metadata = {}}> : () -> index\n"
      "%selected = \"nodal.shape_index\"(%left, %zero) <{metadata = {}}> : (" +
      left + ", index) -> " + element + "\n" +
      expression("digital_xor", "%selected, %selected", element + ", " + element, element, "same") +
      yield("%left", left);
  return model(input(vec) + collection("reduce_left", "%input", vec,
                                       "%L: i64, %R: i64, %left: " + left + ", %right: " + right,
                                       body, initial));
}
std::string contextualSlice(bool wrong = false) {
  std::string initial = shaped("2,2", "!nodal.uint<4>"), vec = shaped("2", initial),
              result = shaped("1,Fixture.c.two", "!nodal.uint<4>");
  std::string body =
      integer("zero", 0) + integer("one", 1) + integer("two", wrong ? 3 : 2) +
      "%slice = \"nodal.shape_slice\"(%lane, %zero, %one, %one) <{axis = 0 : i64, "
      "dimensions = \"1,Fixture.c.two\", origin = \"Fixture.c.arg0\", observability = "
      "\"source_mapped\", metadata = {semantic_path = \"Fixture.c.slice\", input_path = "
      "\"Fixture.c.arg0\", storage = \"structural\", access = \"read_only\"}}> : (" +
      initial + ", i64, i64, i64) -> " + result + "\n" + yield("%slice", result);
  return model(input(vec) + collection("map", "%input", vec, "%lane: " + initial, body,
                                       shaped("2", shaped("1,2", "!nodal.uint<4>"))));
}
std::string aliasReduction(int64_t count, StringRef intent) {
  std::string a = symbolic("Fixture.c.arg0"), b = symbolic("Fixture.c.arg1"),
              leaf = "!nodal.uint<4>", vec = shaped(std::to_string(count), leaf);
  std::string body =
      "%alias = \"nodal.digital_expr\"(%left) <{operator_name = \"digital_alias\", metadata = "
      "{semantic_path = \"Fixture.c.alias\", digital_intent = {kind = \"" +
      intent.str() + "\", value = \"total\"}}}> : (" + a + ") -> " + a + "\n" +
      "\"nodal.collection_yield\"(%alias) <{metadata = {semantic_path = \"Fixture.c.yield\"}}> : "
      "(" +
      a + ") -> ()\n";
  std::string port = input(vec);
  port = replace(port, "port = @input, metadata = {}",
                 "port = @input, metadata = {semantic_path = \"Fixture.input\"}");
  std::string source =
      model(port + collection("reduce_left", "%input", vec,
                              "%L: i64, %R: i64, %left: " + a + ", %right: " + b, body, leaf));
  return replace(source, "module {",
                 "module attributes {nodal.digital.quality = {version = 1 : i64, temporaries = "
                 "\"readable\", naming = \"semantic\"}} {");
}
std::string nestedStaticCapture(StringRef mode, bool unadmitted = false) {
  const int64_t count = 5;
  std::string a = symbolic("Fixture.c.arg0"), b = symbolic("Fixture.c.arg1"),
              w = symbolic("Fixture.c.width");
  std::string initial = shaped("2", "!nodal.uint<4>"), left = shaped("2", a),
              right = shaped("2", b), out = shaped("2", w), vec = shaped("5", initial);
  std::string ca = symbolic(unadmitted ? "Fixture.c.arg0" : "Fixture.c.child.arg2"),
              cb = symbolic("Fixture.c.child.arg3"), cw = symbolic("Fixture.c.child.childWidth");
  std::string child = stat("childMaximum", "max", "%capturedL, %capturedR", 2) +
                      integer("childOne", 1) +
                      stat("childWidth", "add", "%childMaximum, %childOne", 2);
  for (size_t pos = 0; (pos = child.find("Fixture.c.", pos)) != std::string::npos; pos += 16)
    child.replace(pos, 10, "Fixture.c.child.");
  child += expression("digital_add", "%x, %y", ca + ", " + cb, cw, "sum") + yield("%sum", cw);
  std::string body =
      stat("maximum", "max", "%L, %R", 2) + integer("one", 1) +
      stat("width", "add", "%maximum, %one", 2) +
      collection("zip_with", "%left, %right, %L, %R", left + ", " + right + ", i64, i64",
                 "%x: " + ca + ", %y: " + cb + ", %capturedL: i64, %capturedR: i64", child, out,
                 "2, 0, 2", "Fixture.c.child", "child") +
      yield("%child", out);
  int64_t width = mode == "reduce_left" ? 4 + count - 1 : 7;
  return model(input(vec) + collection(mode, "%input", vec,
                                       "%L: i64, %R: i64, %left: " + left + ", %right: " + right,
                                       body,
                                       shaped("2", "!nodal.uint<" + std::to_string(width) + ">")));
}
int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  unsigned cases = 0, failures = 0;
  auto check = [&](StringRef name, const std::string &source, bool expected,
                   std::function<bool(Operation *)> extra = {}) {
    ++cases;
    std::string diagnostics;
    ScopedDiagnosticHandler handler(&context, [&](Diagnostic &d) {
      llvm::raw_string_ostream out(diagnostics);
      d.print(out);
      return success();
    });
    auto parsed = parseSourceString<mlir::ModuleOp>(source, ParserConfig(&context, false));
    bool good = bool(parsed);
    if (parsed) {
      Operation *module = nullptr;
      parsed->walk([&](nodal::ModuleOp op) {
        if (!module)
          module = op;
      });
      std::string before = print(*parsed);
      bool semantic = module && succeeded(nodal::verifyDigitalCollectionModel(module));
      bool native = succeeded(verify(*parsed));
      bool types = module && succeeded(nodal::verifyDigitalTypeModel(module));
      bool values = module && succeeded(nodal::verifyDigitalValueModel(module));
      bool enums = module && succeeded(nodal::verifyEnumValueModel(module));
      good = semantic == expected && native == expected && types == expected &&
             values == expected && enums == expected && before == print(*parsed);
      if (expected && good && extra)
        good = extra(module);
      if (!good)
        diagnostics += "semantic=" + std::to_string(semantic) +
                       " native=" + std::to_string(native) + " types=" + std::to_string(types) +
                       " values=" + std::to_string(values) + " enums=" + std::to_string(enums) +
                       "\n";
    }
    failures += !good;
    (good ? llvm::outs() : llvm::errs())
        << "REDUCTION_CONTEXT_" << (good ? "PASS " : "FAIL ") << name << "\n";
    if (!good)
      llvm::errs() << diagnostics << "\n";
  };
  auto checkBodyQuery = [&](Operation *module, int64_t left, int64_t right, int64_t width,
                            bool nested) {
    Operation *reduction = nullptr;
    module->walk([&](nodal::CollectionOp op) {
      if (op->getParentOp() == module)
        reduction = op;
    });
    auto facts = nodal::analyzeDigitalReduction(reduction);
    if (failed(facts)) {
      llvm::errs() << "context body query: analysis failed\n";
      return false;
    }
    auto finalType = nodal::digitalReductionFinalType(*facts);
    auto body = nodal::instantiateDigitalReductionBodyTypes(*facts, left, right);
    auto exact = [](const nodal::InstantiatedIntegerExpression &expression, int64_t number) {
      return nodal::equivalentInstantiatedIntegerExpressions(
          expression, nodal::makeLiteralIntegerExpression(number));
    };
    if (failed(finalType) || finalType->dimensions.size() != 1 ||
        !exact(finalType->dimensions.front(), 2) || failed(body) || body->size() != 2) {
      llvm::errs() << "context body query: final=" << succeeded(finalType)
                   << " dimensions=" << (succeeded(finalType) ? finalType->dimensions.size() : 0)
                   << " body=" << succeeded(body)
                   << " values=" << (succeeded(body) ? body->size() : 0) << "\n";
      return false;
    }
    bool sawCollection = false, sawInnerValue = false;
    for (const auto &entry : *body) {
      Operation *producer = entry.value.getDefiningOp();
      if (!producer || entry.value.getType().isIndex() || entry.type.widths.size() != 1 ||
          !exact(entry.type.widths.front(), width)) {
        llvm::errs() << "context body query: unexpected width for " << entry.value
                     << " width count=" << entry.type.widths.size() << " expected=" << width
                     << "\n";
        return false;
      }
      if (llvm::isa<nodal::CollectionOp>(producer)) {
        sawCollection = true;
        if (!nested || entry.type.dimensions.size() != 1 ||
            !exact(entry.type.dimensions.front(), 2) || entry.type.maximumBits != 2 * width)
          return false;
      } else {
        if (!entry.type.dimensions.empty() || entry.type.maximumBits != width)
          return false;
        if (producer->getParentOp() != reduction)
          sawInnerValue = llvm::isa<nodal::DigitalExprOp>(producer) &&
                          llvm::isa<nodal::CollectionOp>(producer->getParentOp());
      }
    }
    return sawCollection == nested && sawInnerValue == nested;
  };
  check("mixed-selected-Bits1-and-invariant-Bool", mixedFlags(), true);
  check("selected-exact-one-boolean", selectedBoolean(), true);
  check("symbolic-singleton-is-not-literal-one", selectedBoolean(true), false);
  check("opaque-singleton-first-control", selectedBoolean(false, true, 2), true);
  check("opaque-singleton-is-not-literal-one", selectedBoolean(false, true, 4), false);
  check("opaque-singleton-bitwise-control",
        replace(selectedBoolean(false, true, 4), "bool_not", "digital_not"), true,
        [&](Operation *module) {
          Operation *value = nullptr;
          module->walk([&](nodal::DigitalExprOp op) {
            if (!value)
              value = op;
          });
          auto contexts = nodal::collectionIntegerContextsAtUse(value);
          if (failed(contexts) || contexts->size() < 2)
            return false;
          auto width = nodal::inferDigitalWidthExpression(value->getResult(0).getType(), module,
                                                          &contexts->back());
          return succeeded(width) && width->bounds.lower == 1 && width->bounds.upper == 1 &&
                 width->root->kind == nodal::InstantiatedIntegerNode::Kind::OpaqueValue &&
                 !nodal::equivalentInstantiatedIntegerExpressions(
                     *width, nodal::makeLiteralIntegerExpression(1));
        });
  check("nested-Vec-contextual-index", nestedProjection(), true,
        [&](Operation *module) { return checkBodyQuery(module, 1, 1, 4, false); });
  check("nested-Vec-forged-index-type", nestedProjection(true), false);
  check("slice-contextual-equivalent-unselected-axis", contextualSlice(), true);
  check("slice-contextual-different-unselected-axis", contextualSlice(true), false);
  for (int64_t count : {1, 3})
    for (StringRef intent : {"named", "keep"})
      check(intent.str() + "-alias-materialization-" + std::to_string(count),
            aliasReduction(count, intent), true, [&](Operation *module) {
              auto report = nodal::analyzeDigitalMaterialization(module);
              if (failed(report))
                return false;
              auto decisions = report->getAs<ArrayAttr>("decisions");
              if (!decisions || decisions.size() != (count == 1 ? 1 : 2))
                return false;
              bool alias = false;
              for (Attribute attr : decisions) {
                auto entry = llvm::cast<DictionaryAttr>(attr);
                if (entry.getAs<StringAttr>("path").getValue() == "Fixture.c.alias")
                  alias = true;
              }
              return alias == (count > 1);
            });
  check("nested-static-capture-left-domain", nestedStaticCapture("reduce_left"), true,
        [&](Operation *module) { return checkBodyQuery(module, 2, 1, 6, true); });
  check("nested-static-capture-balanced-tail", nestedStaticCapture("reduce_balanced"), true,
        [&](Operation *module) { return checkBodyQuery(module, 4, 1, 7, true); });
  check("nested-raw-outer-formal-reject", nestedStaticCapture("reduce_left", true), false);
  llvm::outs() << "REDUCTION_CONTEXT_CASES=" << cases << " FAILURES=" << failures << "\n";
  return failures ? 1 : 0;
}
