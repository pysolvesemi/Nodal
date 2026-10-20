#include "circt/Dialect/HW/HWOps.h"
#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/SymbolTable.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "mlir/Pass/Pass.h"
#include "mlir/Pass/PassManager.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Transforms/Passes.h"

#include "llvm/Support/raw_ostream.h"
using namespace mlir;
namespace {
std::string module(StringRef name, const std::string &body) {
  return "\"nodal.module\"() <{sym_name = \"" + name.str() + "\", metadata = {}}> ({\n" + body +
         "}) : () -> ()\n";
}
std::string parameter(StringRef name) {
  std::string n = name.str();
  return "\"nodal.parameter\"() <{classification = \"structural\", default_value = 4 : i64, "
         "metadata = {}, parameter_kind = \"integer\", sym_name = \"" +
         n + "\", type = i64, variability = \"symbolic\"}> : () -> ()\n%" + n +
         "_lo = \"nodal.const_literal\"() <{metadata = {}, spelling = \"1\", value = 1 : i64}> : "
         "() -> i64\n%" +
         n +
         "_hi = \"nodal.const_literal\"() <{metadata = {}, spelling = \"32\", value = 32 : i64}> : "
         "() -> i64\n"
         "\"nodal.parameter_constraint\"(%" +
         n + "_lo, %" + n +
         "_hi) <{constraint_kind = \"range\", lower_inclusive = true, upper_inclusive = true, "
         "metadata = {}, parameter = @" +
         n +
         "}> : (i64, i64) -> ()\n"
         "\"nodal.parameter_envelope\"() <{effects = [\"shape\", \"width\"], metadata = {}, "
         "parameter = @" +
         n + ", policy = \"static_generate\"}> : () -> ()\n%" + n +
         " = \"nodal.const_parameter_ref\"() <{metadata = {}, parameter = @" + n +
         "}> : () -> i64\n";
}
std::string instance(StringRef name, StringRef bindings = "") {
  return "\"nodal.instance\"() <{sym_name = \"" + name.str() +
         "\", module = @Child, parameter_bindings = {" + bindings.str() +
         "}, domain_bindings = {}, metadata = {}}> : () -> ()\n";
}
std::string override(StringRef formal, StringRef actual) {
  return "\"nodal.parameter_override\"(%" + actual.str() + ") <{instance = @u, parameter = @" +
         formal.str() + ", metadata = {}}> : (i64) -> ()\n";
}
std::string digital(StringRef width, StringRef kind = "uint") {
  return "!nodal.digital<\"" + kind.str() + "\", \"" + width.str() + "\">";
}
std::string shape(StringRef extent, StringRef type) {
  return "!nodal.shaped<\"" + extent.str() + "\", " + type.str() + ">";
}
std::string port(StringRef name, StringRef direction, StringRef type) {
  return "\"nodal.port\"() <{sym_name = \"" + name.str() + "\", direction = \"" + direction.str() +
         "\", type = " + type.str() + ", domain = @none, metadata = {}}> : () -> ()\n%" +
         name.str() + " = \"nodal.port_value\"() <{port = @" + name.str() +
         ", metadata = {}}> : () -> " + type.str() + "\n";
}
std::string assign(StringRef target, StringRef value, StringRef type) {
  return "\"nodal.digital_assign\"(%" + target.str() + ", %" + value.str() +
         ") <{kind = \"continuous\", metadata = {}}> : (" + type.str() + ", " + type.str() +
         ") -> ()\n";
}
std::string proxy(StringRef instance, StringRef port, StringRef direction, StringRef type) {
  return "%" + instance.str() + "_" + port.str() + " = \"nodal.instance_port\"() <{instance = @" +
         instance.str() + ", port = \"" + port.str() + "\", direction = \"" + direction.str() +
         "\", metadata = {}}> : () -> " + type.str() + "\n";
}
std::string fixture(bool shaped = false, bool nested = false) {
  std::string formal = shaped ? shape("N,2", digital("W")) : digital("W");
  std::string actual = shaped ? shape("P,2", digital("Q")) : digital("Q");
  std::string concrete = shaped ? shape("3,2", "!nodal.uint<8>") : "!nodal.uint<8>";
  std::string child = parameter("N") + parameter("W") + port("input", "input", formal) +
                      port("output", "output", formal) + assign("output", "input", formal);
  std::string parent =
      parameter("P") + parameter("Q") + port("a", "input", actual) + port("b", "input", concrete) +
      port("x", "output", actual) + port("y", "output", concrete) + instance("u") +
      override("N", "P") + override("W", "Q") + proxy("u", "input", "input", actual) +
      proxy("u", "output", "output", actual) + assign("u_input", "a", actual) +
      assign("x", "u_output", actual) + instance("v", "N = 3 : i64, W = 8 : i64") +
      proxy("v", "input", "input", concrete) + proxy("v", "output", "output", concrete) +
      assign("v_input", "b", concrete) + assign("y", "v_output", concrete);
  std::string result =
      "module attributes {nodal.root.module = @Parent} {" + module("Parent", parent);
  if (nested) {
    std::string forward = parameter("N") + parameter("W") + port("input", "input", formal) +
                          port("output", "output", formal) + instance("u") + override("N", "N") +
                          override("W", "W") + proxy("u", "input", "input", formal) +
                          proxy("u", "output", "output", formal) +
                          assign("u_input", "input", formal) + assign("output", "u_output", formal);
    // Keep one exact generic leaf and one generic forwarding body, never clone
    // either for the two actual parameter settings.
    std::string renamed = module("Leaf", child);
    auto at = forward.find("module = @Child");
    forward.replace(at, std::string("module = @Child").size(), "module = @Leaf");
    result += module("Child", forward) + renamed;
  } else
    result += module("Child", child);
  return result + "}";
}
std::string print(Operation *op) {
  std::string result;
  llvm::raw_string_ostream os(result);
  op->print(os);
  return result;
}
std::string replace(std::string input, StringRef from, StringRef to) {
  auto at = input.find(from.str());
  if (at == std::string::npos)
    llvm::report_fatal_error("missing symbolic target mutation");
  return input.replace(at, from.size(), to.str());
}
std::string replaceAll(std::string input, StringRef from, StringRef to) {
  size_t position = 0;
  while ((position = input.find(from.str(), position)) != std::string::npos) {
    input.replace(position, from.size(), to.str());
    position += to.size();
  }
  return input;
}
std::string structure(StringRef width, StringRef count) {
  unsigned literal = 0;
  bool fixed = !width.getAsInteger(10, literal);
  std::string unsignedType =
      fixed ? "!nodal.uint<" + std::to_string(literal) + ">" : digital(width);
  std::string signedType =
      fixed ? "!nodal.sint<" + std::to_string(literal) + ">" : digital(width, "sint");
  auto inner = "!nodal.struct<\"Inner\", [{name = \"tag\", type = !nodal.bits<3>}, "
               "{name = \"samples\", type = " +
               shape(count, unsignedType) + "}]>";
  return "!nodal.struct<\"Payload\", [{name = \"ready\", type = !nodal.bits<1>}, "
         "{name = \"nested\", type = " +
         shape("2", inner) + "}, {name = \"signed\", type = " + signedType + "}]>";
}
std::string aggregateFixture(bool nested = false) {
  auto input = fixture(true, nested);
  input = replaceAll(input, shape("N,2", digital("W")), structure("W", "N"));
  input = replaceAll(input, shape("P,2", digital("Q")), structure("Q", "P"));
  return replaceAll(input, shape("3,2", "!nodal.uint<8>"), structure("8", "3"));
}
std::string fieldGet(StringRef result, StringRef input, StringRef schema, StringRef field,
                     unsigned ordinal, StringRef type) {
  return "%" + result.str() + " = \"nodal.digital_expr\"(%" + input.str() +
         ") <{operator_name = \"digital_struct_get\", struct_schema = " + schema.str() +
         ", field_ordinal = " + std::to_string(ordinal) + " : i64, field_name = \"" + field.str() +
         "\", metadata = {semantic_path = \"Child." + result.str() + "\"}}> : (" + schema.str() +
         ") -> " + type.str() + "\n";
}
} // namespace
int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  std::string diagnostics;
  ScopedDiagnosticHandler handler(&context, [&](Diagnostic &d) {
    llvm::raw_string_ostream os(diagnostics);
    d.print(os);
    os << '\n';
    return success();
  });
  unsigned cases = 0, failures = 0;
  auto report = [&](StringRef name, bool good) {
    ++cases;
    failures += !good;
    (good ? llvm::outs() : llvm::errs())
        << "SYMBOLIC_HIERARCHY_" << (good ? "PASS " : "FAIL ") << name << '\n';
    if (!good)
      llvm::errs() << diagnostics;
  };
  auto check = [&](StringRef name, std::string input, bool expected, unsigned modules = 2,
                   unsigned instances = 2, StringRef diagnostic = "NODAL-HIERARCHY-057-001") {
    diagnostics.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(input, ParserConfig(&context, false));
    if (!parsed) {
      report(name, false);
      return;
    }
    std::string before = print(parsed->getOperation());
    PassManager pm(&context);
    pm.addPass(nodal::createSequentialToCIRCTPass());
    bool actual = succeeded(pm.run(*parsed));
    bool good = actual == expected;
    if (!actual)
      good &= before == print(parsed->getOperation()) &&
              diagnostics.find(diagnostic.str()) != std::string::npos;
    else {
      unsigned bodies = 0, calls = 0, parameterized = 0;
      parsed->walk([&](circt::hw::HWModuleOp op) {
        ++bodies;
        parameterized += op.getParameters().size() == 2;
      });
      parsed->walk([&](circt::hw::InstanceOp op) {
        ++calls;
        good &= op.getParameters().size() == 2;
      });
      good &= bodies == modules && calls == instances && parameterized == modules &&
              succeeded(verify(*parsed));
      auto parent =
          dyn_cast_or_null<circt::hw::HWModuleOp>(SymbolTable::lookupSymbolIn(*parsed, "Parent"));
      good &= bool(parent);
      if (parent) {
        Block *body = parent.getBodyBlock();
        SmallVector<circt::hw::InstanceOp> uses;
        for (auto instance : body->getOps<circt::hw::InstanceOp>())
          uses.push_back(instance);
        good &= body->getNumArguments() == 2 && uses.size() == 2 &&
                isa<circt::hw::IntType>(body->getArgument(0).getType());
        if (uses.size() == 2 && body->getNumArguments() == 2) {
          for (unsigned index = 0; index < 2; ++index) {
            good &= uses[index].getModuleName() == "Child" && uses[index]->getNumOperands() == 1 &&
                    uses[index]->getOperand(0) == body->getArgument(index) &&
                    body->back().getOperand(index) == uses[index].getResult(0);
            for (Attribute raw : uses[index].getParameters()) {
              auto parameter = cast<circt::hw::ParamDeclAttr>(raw);
              if (parameter.getName().getValue() != "W")
                continue;
              if (!index) {
                auto ref = dyn_cast<circt::hw::ParamDeclRefAttr>(parameter.getValue());
                good &= ref && ref.getName().getValue() == "Q";
              } else {
                auto literal = dyn_cast<IntegerAttr>(parameter.getValue());
                good &= literal && literal.getInt() == 8;
              }
            }
          }
        }
      }
      std::string output = print(parsed->getOperation());
      good &= output.find("!hw.int<") != std::string::npos &&
              output.find("hw.param.decl.ref") != std::string::npos;
      // Target text is the actual firtool ingress. In-memory equality alone can
      // hide a parameter expression width mismatch in HW IntType assembly.
      auto roundtrip = parseSourceString<mlir::ModuleOp>(output, ParserConfig(&context, false));
      good &= roundtrip && succeeded(verify(*roundtrip));
    }
    report(name, good);
  };
  check("two-overrides-one-generic-scalar-definition", fixture(), true);
  auto ordinaryParameter = [&](StringRef name) {
    auto ordinary = replace(parameter(name), "classification = \"structural\"",
                            "classification = \"ordinary\"");
    ordinary = replace(ordinary, "parameter_kind = \"integer\", ", "");
    auto first = ordinary.find("\"nodal.parameter_envelope\"");
    auto last = ordinary.find('\n', first);
    return ordinary.erase(first, last + 1 - first);
  };
  check("bounded-ordinary-inferred-integer-configuration-forwarded",
        replace(replace(fixture(), parameter("N"), ordinaryParameter("N")), parameter("P"),
                ordinaryParameter("P")),
        true);
  check("rank-two-scalar-vec-packed-symbolic-carrier", fixture(true), true);
  check("nested-forwarding-keeps-parameter-identities", fixture(true, true), true, 3, 3);
  auto singleton =
      replace(fixture(), parameter("Q"),
              replace(replace(parameter("Q"), "spelling = \"1\", value = 1 : i64",
                              "spelling = \"4\", value = 4 : i64"),
                      "spelling = \"32\", value = 32 : i64", "spelling = \"4\", value = 4 : i64"));
  check("singleton-envelope-retains-parent-parameter-atom", singleton, true);
  check("equal-bound-width-swap-rejected-atomically",
        replace(fixture(), "parameter_override\"(%Q)", "parameter_override\"(%P)"), false);
  check("out-of-envelope-override-rejected-atomically",
        replace(fixture(), "W = 8 : i64", "W = 64 : i64"), false);
  check("shape-order-forgery-rejected-atomically",
        replaceAll(fixture(true), "!nodal.shaped<\"P,2\", !nodal.digital<\"uint\", \"Q\">>",
                   "!nodal.shaped<\"2,P\", !nodal.digital<\"uint\", \"Q\">>"),
        false);
  std::string runtime = fixture();
  std::string symbolic = digital("W");
  runtime = replace(runtime, assign("output", "input", symbolic),
                    "%xor = \"nodal.digital_expr\"(%input, %input) <{operator_name = "
                    "\"digital_xor\", metadata = {semantic_path = \"Child.xor\"}}> : (" +
                        symbolic + ", " + symbolic + ") -> " + symbolic + "\n" +
                        assign("output", "xor", symbolic));
  check("symbolic-runtime-operation-still-rejected-atomically", runtime, false, 2, 2,
        "symbolic boundary target currently");
  std::string wideParameter = replace(parameter("Huge"), "spelling = \"32\", value = 32 : i64",
                                      "spelling = \"3000000000\", value = 3000000000 : i64");
  check("wide-unused-parameter-envelope-not-truncated",
        replace(fixture(), parameter("P"), parameter("P") + wideParameter), false, 2, 2,
        "target Integer parameter envelope must fit signed 32-bit arithmetic");
  auto negativeIntermediate = [&](int64_t base) {
    auto expression = [&](StringRef owner, StringRef parameterName) {
      return "%negative = \"nodal.const_expr\"(%" + parameterName.str() +
             ") <{operator_name = \"neg\", metadata = {}}> : (i64) -> i64\n"
             "%base = \"nodal.const_literal\"() <{spelling = \"" +
             std::to_string(base) + "\", value = " + std::to_string(base) +
             " : i64, metadata = {}}> : () -> i64\n"
             "%middle = \"nodal.const_expr\"(%negative, %base) "
             "<{operator_name = \"add\", metadata = {}}> : (i64, i64) -> i64\n"
             "%compensate = \"nodal.const_literal\"() <{spelling = \"" +
             std::to_string(-base - 1) + "\", value = " + std::to_string(-base - 1) +
             " : i64, metadata = {}}> : () -> i64\n"
             "%restore = \"nodal.const_expr\"(%middle, %compensate) "
             "<{operator_name = \"add\", metadata = {}}> : (i64, i64) -> i64\n"
             "%offset = \"nodal.const_literal\"() <{spelling = \"34\", value = 34 : i64, "
             "metadata = {}}> : () -> i64\n"
             "%width = \"nodal.const_expr\"(%restore, %offset) "
             "<{operator_name = \"add\", metadata = {semantic_path = \"" +
             owner.str() + ".width\"}}> : (i64, i64) -> i64\n";
    };
    // Both complete expressions prove the same positive width 33 - parameter.
    // Only the intermediate bounds differ; native i64 proof remains valid.
    auto input = fixture();
    input = replace(input, parameter("Q"), parameter("Q") + expression("Parent", "Q"));
    input = replace(input, parameter("W"), parameter("W") + expression("Child", "W"));
    input = replaceAll(input, digital("Q"), digital("Parent.width"));
    input = replaceAll(input, digital("W"), digital("Child.width"));
    return replaceAll(input, "!nodal.uint<8>", "!nodal.uint<25>");
  };
  check("near-lower-bound-intermediate-round-trips", negativeIntermediate(-2147483600), true);
  check("below-lower-bound-intermediate-not-truncated", negativeIntermediate(-2147483648), false, 2,
        2, "static Integer expression bounds must fit signed 32-bit arithmetic");
  std::string namedBaselinePins;
  auto checkNamed = [&](StringRef name, std::string input, bool expected, unsigned modules = 2,
                        bool fieldAdapters = false,
                        StringRef diagnostic = "NODAL-HIERARCHY-057-001") {
    diagnostics.clear();
    auto parsed = parseSourceString<mlir::ModuleOp>(input, ParserConfig(&context, false));
    if (!parsed) {
      report(name, false);
      return;
    }
    std::string before = print(parsed->getOperation());
    PassManager pm(&context);
    auto lowering = nodal::createSequentialToCIRCTPass();
    if (failed(lowering->initializeOptions("boundary-abi=named-fields", [&](const Twine &error) {
          diagnostics += error.str();
          return failure();
        }))) {
      report(name, false);
      return;
    }
    pm.addPass(std::move(lowering));
    bool actual = succeeded(pm.run(*parsed));
    bool good = actual == expected;
    if (!actual)
      good &= print(parsed->getOperation()) == before &&
              diagnostics.find(diagnostic.str()) != std::string::npos;
    else {
      unsigned bodies = 0, creates = 0, extracts = 0;
      parsed->walk([&](circt::hw::HWModuleOp module) {
        ++bodies;
        auto map = module->getAttrOfType<ArrayAttr>("nodal.boundary_map");
        good &= map && !map.empty();
        for (const auto &port : module.getPortList())
          if (port.attrs && port.attrs.get("nodal.logical_leaf"))
            good &= !port.attrs.get("nodal.packed_abi");
      });
      parsed->walk([&](circt::hw::StructCreateOp) { ++creates; });
      parsed->walk([&](circt::hw::StructExtractOp) { ++extracts; });
      good &= bodies == modules && creates >= 4 && extracts >= 16 && succeeded(verify(*parsed));
      auto parent =
          dyn_cast_or_null<circt::hw::HWModuleOp>(SymbolTable::lookupSymbolIn(*parsed, "Parent"));
      good &= parent && parent.getBodyBlock()->getNumArguments() == 8 &&
              parent.getBodyBlock()->back().getNumOperands() == 8;
      auto output = print(parsed->getOperation());
      good &= output.find("a__f_5_ready") != std::string::npos &&
              output.find("a__f_6_nested_7_samples") != std::string::npos &&
              output.find("field_depth = 1") != std::string::npos &&
              output.find("field_depth = 2") != std::string::npos &&
              output.find("named-leaf-vectors") != std::string::npos &&
              output.find("dimension_groups") != std::string::npos;
      std::string pinSignature;
      llvm::raw_string_ostream pins(pinSignature);
      parsed->walk([&](circt::hw::HWModuleOp module) {
        pins << module.getName() << ":";
        for (const auto &port : module.getPortList())
          pins << port.name << ":" << port.type << ":" << unsigned(port.dir) << ";";
      });
      if (namedBaselinePins.empty())
        namedBaselinePins = pinSignature;
      if (fieldAdapters)
        good &= output.find("Child.observed_ready") != std::string::npos &&
                output.find("Child.rebuilt") != std::string::npos &&
                pinSignature == namedBaselinePins;
      auto roundtrip = parseSourceString<mlir::ModuleOp>(output, ParserConfig(&context, false));
      good &= roundtrip && succeeded(verify(*roundtrip));
    }
    report(name, good);
  };
  checkNamed("named-nested-vec-struct-mixed-symbolic-literal-overrides", aggregateFixture(), true);
  checkNamed("named-nested-forwarding-reuses-one-body", aggregateFixture(true), true, 3);
  auto typed = structure("W", "N");
  auto nestedType = shape("2", "!nodal.struct<\"Inner\", [{name = \"tag\", type = !nodal.bits<3>}, "
                               "{name = \"samples\", type = " +
                                   shape("N", digital("W")) + "}]>");
  auto adapters = fieldGet("observed_ready", "input", typed, "ready", 0, "!nodal.bits<1>") +
                  fieldGet("observed_nested", "input", typed, "nested", 1, nestedType) +
                  fieldGet("observed_signed", "input", typed, "signed", 2, digital("W", "sint")) +
                  "%rebuilt = \"nodal.digital_expr\"(%observed_ready, %observed_nested, "
                  "%observed_signed) <{operator_name = \"digital_struct_make\", struct_schema = " +
                  typed +
                  ", metadata = {semantic_path = \"Child.rebuilt\"}}> : "
                  "(!nodal.bits<1>, " +
                  nestedType + ", " + digital("W", "sint") + ") -> " + typed + "\n" +
                  assign("output", "rebuilt", typed);
  checkNamed("exact-field-get-make-inverse-preserves-identical-pins",
             replace(aggregateFixture(), assign("output", "input", typed), adapters), true, 2,
             true);
  checkNamed("unobserved-child-named-output-keeps-following-result-offsets",
             replace(replace(aggregateFixture(), port("output", "output", typed),
                             port("unobserved", "output", typed) + port("output", "output", typed)),
                     assign("output", "input", typed),
                     adapters + assign("unobserved", "rebuilt", typed)),
             true);
  check("symbolic-struct-requires-explicit-named-profile", aggregateFixture(), false, 2, 2,
        "symbolic Struct boundaries require boundary-abi=named-fields");
  checkNamed("same-size-renamed-field-does-not-launder-abi",
             replaceAll(aggregateFixture(), structure("Q", "P"),
                        replace(structure("Q", "P"), "name = \"tag\"", "name = \"bad\"")),
             false);
  checkNamed("sibling-private-dimension-owner-does-not-launder-abi",
             replaceAll(aggregateFixture(), structure("Q", "P"), structure("P", "Q")), false);
  llvm::outs() << "SYMBOLIC_HIERARCHY_SUMMARY cases=" << cases << " failures=" << failures << '\n';
  return failures ? 1 : 0;
}
