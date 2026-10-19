#include "mlir/IR/Builders.h"
#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/DigitalCollectionModel.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
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
std::string reducer(int64_t count, StringRef mode = "reduce_left", StringRef transfer = "add",
                    bool fixed = false, int64_t initial = 4) {
  std::string a = symbolic("Fixture.c.arg0"), b = symbolic("Fixture.c.arg1"),
              w = symbolic("Fixture.c.width");
  std::string leaf = "!nodal.uint<" + std::to_string(initial) + ">",
              vec = shaped(std::to_string(count), leaf);
  std::string body;
  if (transfer == "add")
    body = stat("maximum", "max", "%L, %R", 2) + integer("one", 1) +
           stat("width", "add", "%maximum, %one", 2);
  else if (transfer == "mul")
    body = stat("width", "add", "%L, %R", 2);
  else
    w = a;
  body += expression("digital_" + transfer.str(), "%left, %right", a + ", " + b, w, "combined");
  int64_t finalWidth = initial;
  if (count > 1) {
    if (transfer == "mul")
      finalWidth = initial * count;
    else if (transfer == "add") {
      int64_t rounds = 0, n = count;
      while (n > 1) {
        n = n / 2 + n % 2;
        ++rounds;
      }
      finalWidth = initial + (mode == "reduce_left" ? count - 1 : rounds);
    }
  }
  if (fixed) {
    body += expression("digital_wrap", "%combined", w, "!nodal.uint<8>", "wrapped");
    body += yield("%wrapped", "!nodal.uint<8>");
    if (count > 1)
      finalWidth = 8;
  } else
    body += yield("%combined", w);
  return model(input(vec) + collection(mode, "%input", vec,
                                       "%L: i64, %R: i64, %left: " + a + ", %right: " + b, body,
                                       "!nodal.uint<" + std::to_string(finalWidth) + ">"));
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
      bool semantic = module && succeeded(nodal::verifyDigitalCollectionModel(module));
      bool native = succeeded(verify(*parsed));
      good = semantic == expected && native == expected;
      if (expected && good && extra)
        good = extra(module);
      if (!good)
        diagnostics +=
            "semantic=" + std::to_string(semantic) + " native=" + std::to_string(native) + "\n";
    }
    failures += !good;
    (good ? llvm::outs() : llvm::errs())
        << "REDUCTION_" << (good ? "PASS " : "FAIL ") << name << "\n";
    if (!good)
      llvm::errs() << diagnostics << "\n";
  };
  for (int64_t n : {1, 2, 3, 5, 6})
    for (StringRef mode : {"reduce_left", "reduce_balanced"}) {
      check(mode.str() + "-sum-" + std::to_string(n), reducer(n, mode), true);
      check(mode.str() + "-product-" + std::to_string(n), reducer(n, mode, "mul"), true);
      check(mode.str() + "-fixed-" + std::to_string(n), reducer(n, mode, "add", true), true);
    }
  for (StringRef mode : {"reduce_left", "reduce_balanced"}) {
    auto signedSource = reducer(5, mode);
    auto bitsSource = reducer(5, mode, "mul");
    auto replaceAll = [](std::string source, StringRef from, StringRef to) {
      size_t pos = 0;
      while ((pos = source.find(from.str(), pos)) != std::string::npos) {
        source.replace(pos, from.size(), to.str());
        pos += to.size();
      }
      return source;
    };
    signedSource = replaceAll(signedSource, "uint", "sint");
    signedSource = replaceAll(signedSource, "digital_add", "digital_sub");
    bitsSource = replaceAll(bitsSource, "uint", "bits");
    bitsSource = replaceAll(bitsSource, "digital_mul", "digital_concat");
    check(mode.str() + "-ordered-signed-subtract", signedSource, true);
    check(mode.str() + "-ordered-bits-concat", bitsSource, true);
  }
  check("left-later-invalid-bitwise", reducer(3, "reduce_left", "xor", true), false);
  check("balanced-partial-invalid-bitwise", reducer(3, "reduce_balanced", "xor", true), false);
  check("fixed-first-only-bitwise-control", reducer(2, "reduce_left", "xor", true), true);
  check("forged-final-width",
        replace(reducer(5), "}) : (!nodal.shaped<\"5\", !nodal.uint<4>>) -> !nodal.uint<8>",
                "}) : (!nodal.shaped<\"5\", !nodal.uint<4>>) -> !nodal.uint<7>"),
        false);
  std::string aliased = reducer(3);
  for (size_t pos = 0; (pos = aliased.find("Fixture.c.arg0", pos)) != std::string::npos; pos += 14)
    aliased.replace(pos, 14, "Fixture.c.arg1");
  check("aliased-width-pair", aliased, false);
  check("unused-width-argument",
        replace(reducer(3), "%L: i64, %R: i64, ", "%extra: i64, %L: i64, %R: i64, "), false);
  auto interior = [&](int64_t n, bool overflow) {
    std::string source = reducer(n, "reduce_left", "add", false, 1);
    std::string w = symbolic("Fixture.c.width"), left = symbolic("Fixture.c.arg0"),
                temp = symbolic("Fixture.c.temporary");
    std::string body = integer("limit", 10000000000LL) + integer("zero", 0) +
                       stat("lower", "sub", "%L, %one", 2) + stat("upper", "sub", "%limit, %L", 2) +
                       stat("product", "mul", overflow ? "%lower, %upper" : "%lower, %zero", 2) +
                       stat("temporary", "add", "%one, %product", 2) +
                       expression("digital_wrap", "%left", left, temp, "temporaryValue") +
                       expression("digital_eq", "%temporaryValue, %temporaryValue",
                                  temp + ", " + temp, "!nodal.bits<1>", "condition") +
                       expression("digital_select", "%condition, %combined, %combined",
                                  "!nodal.bits<1>, " + w + ", " + w, w, "selected") +
                       yield("%selected", w);
    return replace(source, yield("%combined", w), body);
  };
  check("interior-overflow-first-control", interior(2, true), true);
  check("interior-overflow-reachable", interior(10000000001LL, true), false);
  check("large-count-safe-temporary-control", interior(10000000001LL, false), true);

  auto symbolicReducer = [&](StringRef mode, bool fixed = false) {
    std::string source = reducer(5, mode, "add", fixed);
    auto substitute = [&](StringRef a, StringRef b) {
      size_t pos = 0;
      while ((pos = source.find(a.str(), pos)) != std::string::npos) {
        source.replace(pos, a.size(), b.str());
        pos += b.size();
      }
    };
    substitute("!nodal.uint<4>", symbolic("W"));
    substitute("\"5\"", "\"N\"");
    substitute(mode == "reduce_left" || fixed ? "-> !nodal.uint<8>\n" : "-> !nodal.uint<7>\n",
               "-> " + symbolic("Fixture.final") + "\n");
    // Fixed body conversions stay literal eight; only the collection result is dependent.
    if (fixed) {
      source = reducer(5, mode, "add", true);
      substitute("!nodal.uint<4>", symbolic("W"));
      substitute("\"5\"", "\"N\"");
      auto suffix = "}) : (" + shaped("N", symbolic("W")) + ") -> !nodal.uint<8>";
      source = replace(source, suffix,
                       "}) : (" + shaped("N", symbolic("W")) + ") -> " + symbolic("Fixture.final"));
    }
    std::string prelude =
        parameter("W") + parameter("N") +
        "%W = \"nodal.const_parameter_ref\"() <{parameter = @W, metadata = {}}> : () -> i64\n"
        "%N = \"nodal.const_parameter_ref\"() <{parameter = @N, metadata = {}}> : () -> i64\n" +
        integer("outerOne", 1) + integer("outerEight", 8) +
        stat("previous", "sub", "%N, %outerOne", 2);
    if (mode == "reduce_balanced" && !fixed)
      prelude += stat("height", "ceil_log2", "%N", 1);
    prelude +=
        fixed ? stat("final", "choose_zero", "%previous, %W, %outerEight", 3)
              : stat("final", "add", mode == "reduce_left" ? "%W, %previous" : "%W, %height", 2);
    for (size_t pos = 0; (pos = prelude.find("Fixture.c.", pos)) != std::string::npos; pos += 8)
      prelude.replace(pos, 10, "Fixture.");
    return replace(source, "({\n", "({\n" + prelude);
  };
  for (StringRef mode : {"reduce_left", "reduce_balanced"}) {
    check(mode.str() + "-independent-symbolic-width-count", symbolicReducer(mode), true);
    check(mode.str() + "-symbolic-singleton-fixed", symbolicReducer(mode, true), true);
  }
  check("equal-range-distinct-final-identity",
        replace(symbolicReducer("reduce_left"), "(%N, %outerOne)", "(%W, %outerOne)"), false);
  std::string boundedN = parameter("N");
  boundedN = replace(boundedN, "default_value = 4", "default_value = 5");
  boundedN =
      replace(boundedN, "value = 1 : i64, spelling = \"1\"", "value = 5 : i64, spelling = \"5\"");
  boundedN = replace(boundedN, "value = 1000000 : i64, spelling = \"1000000\"",
                     "value = 9 : i64, spelling = \"9\"");
  check("symbolic-tail-exact-node-and-body-query",
        replace(symbolicReducer("reduce_balanced"), parameter("N"), boundedN), true,
        [&](Operation *module) {
          Operation *op = nullptr;
          module->walk([&](nodal::CollectionOp c) { op = c; });
          auto proof = nodal::analyzeDigitalReduction(op);
          if (failed(proof))
            return false;
          auto tail = nodal::digitalReductionTypeAtTreeNode(*proof, 2, 1);
          auto body = nodal::instantiateDigitalReductionBodyTypesAtTreeNode(*proof, 3, 0);
          auto remainder = nodal::composeIntegerExpression(
              "sub", {proof->count(), nodal::makeLiteralIntegerExpression(4)});
          if (failed(remainder))
            return false;
          auto previous = nodal::composeIntegerExpression(
              "sub", {*remainder, nodal::makeLiteralIntegerExpression(1)});
          if (failed(previous))
            return false;
          auto quotient = nodal::composeIntegerExpression(
              "div", {*previous, nodal::makeLiteralIntegerExpression(4)});
          if (failed(quotient))
            return false;
          auto population = nodal::composeIntegerExpression(
              "choose_zero", {*quotient, *remainder, nodal::makeLiteralIntegerExpression(4)});
          if (failed(population))
            return false;
          auto height = nodal::composeIntegerExpression("ceil_log2", {*population});
          auto initial = nodal::inferDigitalWidthExpression(
              llvm::cast<nodal::ShapedType>(op->getOperand(0).getType()).getElementType(), module);
          if (failed(height) || failed(initial))
            return false;
          auto exact = nodal::composeIntegerExpression("add", {*initial, *height});

          return succeeded(tail) && tail->widths.size() == 1 && tail->widths[0].bounds.lower == 1 &&
                 tail->widths[0].bounds.upper <= 1000003 && succeeded(exact) &&
                 nodal::equivalentInstantiatedIntegerExpressions(tail->widths[0], *exact) &&
                 succeeded(body) && !body->empty() &&
                 failed(nodal::digitalReductionTypeAtTreeNode(*proof, 4, 0)) &&
                 failed(nodal::instantiateDigitalReductionBodyTypesAtTreeNode(*proof, 2, 1));
        });
  auto boolReducer = [&](StringRef count) {
    std::string type = shaped(count, "!nodal.bits<1>");
    return model(input(type) +
                 collection("reduce_balanced", "%input", type,
                            "%left: !nodal.bits<1>, %right: !nodal.bits<1>",
                            expression("bool_and", "%left, %right",
                                       "!nodal.bits<1>, !nodal.bits<1>", "!nodal.bits<1>") +
                                yield("%value", "!nodal.bits<1>"),
                            "!nodal.bits<1>"));
  };
  check("zero-slot-bool", boolReducer("5"), true);
  check("signed-i64-count-63-levels", boolReducer("9223372036854775807"), true,
        [&](Operation *module) {
          Operation *op = nullptr;
          module->walk([&](nodal::CollectionOp c) { op = c; });
          auto proof = nodal::analyzeDigitalReduction(op);
          return succeeded(proof) &&
                 succeeded(nodal::digitalReductionTypeAtTreeNode(*proof, 63, 0)) &&
                 succeeded(nodal::instantiateDigitalReductionBodyTypesAtTreeNode(*proof, 63, 0)) &&
                 failed(nodal::digitalReductionTypeAtTreeNode(*proof, 63, 1)) &&
                 failed(nodal::digitalReductionTypeAtTreeNode(*proof, 64, 0));
        });

  auto recordReducer = [&](StringRef mode, bool cross) {
    auto record = [](const std::vector<std::string> &types) {
      const char *names[] = {"score", "tag", "payload", "valid", "position"};
      std::string result = "!nodal.struct<\"Candidate\", [";
      for (unsigned i = 0; i < 5; ++i) {
        if (i)
          result += ", ";
        result += "{name = \"" + std::string(names[i]) + "\", type = " + types[i] + "}";
      }
      return result + "]>";
    };
    std::vector<std::string> initial = {"!nodal.sint<4>", "!nodal.bits<3>",
                                        shaped("Inner", "!nodal.uint<2>"), "!nodal.bits<1>",
                                        "!nodal.uint<3>"};
    std::vector<std::string> left = {
        symbolic("Fixture.c.arg0", "sint"), symbolic("Fixture.c.arg1", "bits"),
        shaped("Inner", symbolic("Fixture.c.arg2")), "!nodal.bits<1>", symbolic("Fixture.c.arg3")};
    std::vector<std::string> right = {
        symbolic("Fixture.c.arg4", "sint"), symbolic("Fixture.c.arg5", "bits"),
        shaped("Inner", symbolic("Fixture.c.arg6")), "!nodal.bits<1>", symbolic("Fixture.c.arg7")};
    auto base = record(initial), l = record(left), r = record(right);
    std::string args;
    for (unsigned i = 0; i < 8; ++i)
      args += "%w" + std::to_string(i) + ": i64, ";
    args += "%left: " + l + ", %right: " + r;
    const char *names[] = {"score", "tag", "payload", "valid", "position"};
    std::string body;
    auto field = [&](unsigned i, bool rhs) {
      auto value = std::string(rhs ? "r" : "l") + names[i];
      return "%" + value + " = \"nodal.digital_expr\"(%" + (rhs ? "right" : "left") +
             ") <{operator_name = \"digital_struct_get\", struct_schema = " + base +
             ", field_ordinal = " + std::to_string(i) + " : i64, field_name = \"" + names[i] +
             "\", metadata = {}}> : (" + (rhs ? r : l) + ") -> " + (rhs ? right[i] : left[i]) +
             "\n";
    };
    body += field(0, false) + field(0, true) +
            expression("digital_ge", "%lscore, %rscore", left[0] + ", " + right[0],
                       "!nodal.bits<1>", "takeLeft");
    std::string output;
    if (!cross) {
      body += expression("digital_select", "%takeLeft, %left, %right",
                         "!nodal.bits<1>, " + l + ", " + r, l, "winner") +
              yield("%winner", l);
      output = base;
    } else {
      body += stat("maximum", "max", "%w0, %w4", 2) + integer("one", 1) +
              stat("width", "add", "%maximum, %one", 2);
      auto result = left;
      result[0] = symbolic("Fixture.c.width", "sint");
      body += expression("digital_add", "%lscore, %rscore", left[0] + ", " + right[0], result[0],
                         "score");
      for (unsigned i = 1; i < 5; ++i)
        body += field(i, false) + field(i, true) +
                expression("digital_select",
                           "%takeLeft, %l" + std::string(names[i]) + ", %r" + names[i],
                           "!nodal.bits<1>, " + left[i] + ", " + right[i], left[i], names[i]);
      std::string operands = "%score, %tag, %payload, %valid, %position", types;
      for (unsigned i = 0; i < 5; ++i) {
        if (i)
          types += ", ";
        types += result[i];
      }
      body += "%made = \"nodal.digital_expr\"(" + operands +
              ") <{operator_name = \"digital_struct_make\", struct_schema = " + base +
              ", metadata = {}}> : (" + types + ") -> " + record(result) + "\n" +
              yield("%made", record(result));
      auto final = initial;
      final[0] = mode == "reduce_left" ? "!nodal.sint<8>" : "!nodal.sint<7>";
      output = record(final);
    }
    auto inputType = shaped("5", base);
    return model(parameter("Inner") + input(inputType) +
                 collection(mode, "%input", inputType, args, body, output));
  };
  for (StringRef mode : {"reduce_left", "reduce_balanced"}) {
    check(mode.str() + "-whole-record-winner-inner-symbol", recordReducer(mode, false), true);
    check(mode.str() + "-cross-field-shared-predicate", recordReducer(mode, true), true,
          [&](Operation *module) {
            Operation *predicate = nullptr;
            unsigned selects = 0;
            module->walk([&](nodal::DigitalExprOp op) {
              if (op->getAttrOfType<StringAttr>("operator_name").getValue() == "digital_ge")
                predicate = op;
            });
            if (!predicate)
              return false;
            for (Operation *use : predicate->getResult(0).getUsers())
              if (use->getAttrOfType<StringAttr>("operator_name").getValue() == "digital_select")
                ++selects;
            Operation *reduction = nullptr;
            module->walk([&](nodal::CollectionOp c) { reduction = c; });
            auto proof = nodal::analyzeDigitalReduction(reduction);
            if (failed(proof))
              return false;
            auto final = nodal::digitalReductionFinalType(*proof);
            return selects == 4 && succeeded(final) && final->dimensions.size() == 1 &&
                   final->dimensions[0].bounds.lower == 1 &&
                   final->dimensions[0].bounds.upper == 1000000;
          });
  }
  check("coupled-slot-transfer-rejected",
        replace(recordReducer("reduce_left", true), "(%w0, %w4)", "(%w0, %w5)"), false);
  check("raw-max-and-prefix-types", reducer(6), true, [&](Operation *module) {
    Operation *op = nullptr;
    module->walk([&](nodal::CollectionOp c) { op = c; });
    auto proof = nodal::analyzeDigitalReduction(op);
    if (failed(proof))
      return false;
    for (int64_t n = 1; n <= 6; ++n) {
      auto type = nodal::digitalReductionTypeAtPrefix(*proof, n);
      if (failed(type) || type->widths.size() != 1 || type->widths[0].bounds.lower != n + 3 ||
          type->widths[0].bounds.upper != n + 3)
        return false;
    }
    Operation *maximum = nullptr;
    op->walk([&](nodal::ConstExprOp e) {
      if (e->getAttrOfType<StringAttr>("operator_name").getValue() == "max")
        maximum = e;
    });
    if (!maximum || maximum->getOperand(0) == maximum->getOperand(1))
      return false;
    auto body = nodal::instantiateDigitalReductionBodyTypes(*proof, 4, 1);
    if (failed(body) || body->empty())
      return false;
    op->setAttr("kind", StringAttr::get(&context, "reduce_balanced"));
    return failed(nodal::digitalReductionTypeAtPrefix(*proof, 2));
  });
  check("balanced-odd-carry-types", reducer(5, "reduce_balanced"), true, [&](Operation *module) {
    Operation *op = nullptr;
    module->walk([&](nodal::CollectionOp c) { op = c; });
    auto proof = nodal::analyzeDigitalReduction(op);
    if (failed(proof))
      return false;
    for (auto query : {std::tuple<unsigned, int64_t, int64_t>{1, 0, 5},
                       {1, 1, 5},
                       {1, 2, 4},
                       {2, 0, 6},
                       {2, 1, 4},
                       {3, 0, 7}}) {
      auto type =
          nodal::digitalReductionTypeAtTreeNode(*proof, std::get<0>(query), std::get<1>(query));
      if (failed(type) || type->widths[0].bounds.lower != std::get<2>(query))
        return false;
    }
    return failed(nodal::digitalReductionTypeAtTreeNode(*proof, 1, 3));
  });
  check("balanced-actual-body-node-queries", reducer(6, "reduce_balanced"), true,
        [&](Operation *module) {
          Operation *op = nullptr;
          module->walk([&](nodal::CollectionOp c) { op = c; });
          auto proof = nodal::analyzeDigitalReduction(op);
          if (failed(proof))
            return false;
          auto final = nodal::digitalReductionFinalType(*proof);
          auto full = nodal::instantiateDigitalReductionBodyTypesAtTreeNode(*proof, 1, 0);
          auto partial = nodal::instantiateDigitalReductionBodyTypesAtTreeNode(*proof, 3, 0);
          return succeeded(final) && final->widths[0].bounds.lower == 7 && succeeded(full) &&
                 succeeded(partial) &&
                 failed(nodal::instantiateDigitalReductionBodyTypesAtTreeNode(*proof, 2, 1)) &&
                 failed(nodal::instantiateDigitalReductionBodyTypes(*proof, 4, 1)) &&
                 succeeded(nodal::instantiateDigitalReductionBodyTypes(*proof, 4, 2));
        });
  auto varyingInnerShape = [&](int64_t count) {
    std::string original = shaped("4", "!nodal.uint<4>");
    std::string output = shaped("4", "!nodal.uint<8>");
    std::string left = shaped("Fixture.c.arg0", symbolic("Fixture.c.arg0"));
    std::string right = shaped("Fixture.c.arg1", symbolic("Fixture.c.arg1"));
    std::string outer = shaped(std::to_string(count), original);
    std::string captured = input(output, "captured");
    return model(input(outer) + captured +
                 collection("reduce_left", "%input, %captured", outer + ", " + output,
                            "%L: i64, %R: i64, %left: " + left + ", %right: " + right +
                                ", %capture: " + output,
                            yield("%capture", output), output, "1, 0, 1"));
  };
  check("formal-dependent-inner-shape-first-control", varyingInnerShape(2), true);
  check("formal-dependent-inner-shape-later-rejected", varyingInnerShape(3), false);
  std::string metadata = "source_line = 1 : i64";
  for (unsigned i = 0; i < 4000; ++i) {
    metadata += ", ";
    metadata += "source_note_" + std::to_string(i) + " = \"inert provenance\"";
  }
  check("large-inert-metadata-does-not-multiply-proof-work",
        replace(reducer(1000000, "reduce_balanced"), "semantic_path = \"Fixture.c.maximum\"",
                "semantic_path = \"Fixture.c.maximum\", " + metadata),
        true);
  std::string excess;
  for (unsigned i = 0; i < 2000; ++i)
    excess += integer("semantic_work_" + std::to_string(i), 1);
  check("semantic-body-context-work-cap",
        replace(reducer(1000000, "reduce_balanced"), "%maximum =", excess + "%maximum ="), false);
  llvm::outs() << "REDUCTION_CASES=" << cases << " FAILURES=" << failures << "\n";
  return failures ? 1 : 0;
}
