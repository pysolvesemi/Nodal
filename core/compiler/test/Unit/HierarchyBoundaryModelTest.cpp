#include "nodal/Dialect/Nodal/HierarchyBoundaryModel.h"

#include "mlir/IR/Diagnostics.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/SymbolTable.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/DigitalTypeModel.h"
#include "nodal/Dialect/Nodal/HierarchyModel.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

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
std::string structure(StringRef name, StringRef fields) {
  return "!nodal.struct<\"" + name.str() + "\", [" + fields.str() + "]>";
}
std::string field(StringRef name, StringRef type) {
  return "{name = \"" + name.str() + "\", type = " + type.str() + "}";
}
std::string print(Operation *op) {
  std::string text;
  llvm::raw_string_ostream os(text);
  op->print(os);
  return text;
}
} // namespace
int main() {
  DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  MLIRContext context(registry);
  context.loadAllAvailableDialects();
  unsigned cases = 0, failures = 0;
  ScopedDiagnosticHandler silence(&context, [](Diagnostic &) { return success(); });
  auto report = [&](StringRef name, bool good) {
    ++cases;
    failures += !good;
    (good ? llvm::outs() : llvm::errs())
        << "HIERARCHY_BOUNDARY_" << (good ? "PASS " : "FAIL ") << name << '\n';
  };
  const std::string childBody =
      parameter("N") + parameter("W") +
      "%twice = \"nodal.const_expr\"(%W, %W) <{operator_name = \"add\", metadata = {semantic_path "
      "= \"Child.twice\"}}> : (i64, i64) -> i64\n";
  const std::string parentBody =
      parameter("P") + parameter("Q") + parameter("W") +
      "%twice = \"nodal.const_expr\"(%Q, %Q) <{operator_name = \"add\", metadata = {semantic_path "
      "= \"Parent.twice\"}}> : (i64, i64) -> i64\n" +
      instance("u") + override("N", "P") + override("W", "Q") +
      instance("literal", "N = 3 : i64, W = 8 : i64") + instance("defaults");
  auto parsed = parseSourceString<mlir::ModuleOp>("module {" + module("Child", childBody) +
                                                      module("Parent", parentBody) + "}",
                                                  ParserConfig(&context, false));
  if (!parsed) {
    llvm::errs() << "cannot parse hierarchy boundary fixture\n";
    return 1;
  }
  Operation *child = SymbolTable::lookupSymbolIn(*parsed, "Child");
  Operation *parent = SymbolTable::lookupSymbolIn(*parsed, "Parent");
  Operation *u = SymbolTable::lookupSymbolIn(parent, "u");
  const auto before = print(parsed->getOperation());
  auto type = [&](StringRef text) -> Type {
    auto holder = parseSourceString<mlir::ModuleOp>(
        "module { %v = \"builtin.unrealized_conversion_cast\"() : () -> " + text.str() + " }",
        ParserConfig(&context, false));
    return holder ? holder->getBody()->front().getResult(0).getType() : Type();
  };
  auto equal = [&](StringRef name, StringRef formal, StringRef actual, bool expected,
                   Operation *instance = nullptr) {
    bool result = nodal::equivalentInstanceDigitalStorageTypes(instance ? instance : u,
                                                               type(formal), type(actual));
    report(name, result == expected && before == print(parsed->getOperation()));
  };
  equal("symbolic-width-exact-override", digital("W"), digital("Q"), true);
  equal("symbolic-shape-exact-override", shape("N", digital("W")), shape("P", digital("Q")), true);
  equal("equal-bounds-do-not-prove-symbol-identity", digital("W"), digital("P"), false);
  equal("same-spelling-in-different-owners", digital("W"), digital("W"), false);
  equal("scalar-kind-is-not-just-bitcount", digital("W"), digital("Q", "sint"), false);
  equal("dimension-order-retained", shape("N,W", digital("W")), shape("Q,P", digital("Q")), false);
  equal("rank-retained", shape("N,2", digital("W")), shape("P", digital("Q")), false);
  equal("exact-dependent-expression", digital("Child.twice"), digital("Parent.twice"), true);
  equal("different-expression-rejected", digital("Child.twice"), digital("Q"), false);
  equal("literal-finalized-override", shape("N", digital("W")), shape("3", "!nodal.uint<8>"), true,
        SymbolTable::lookupSymbolIn(parent, "literal"));
  equal("default-used-only-as-actual-binding", shape("N", digital("W")),
        shape("4", "!nodal.uint<4>"), true, SymbolTable::lookupSymbolIn(parent, "defaults"));
  const auto formal =
      structure("Packet", field("first", digital("W")) + ", " + field("second", "!nodal.bits<1>"));
  const auto actual =
      structure("Packet", field("first", digital("Q")) + ", " + field("second", "!nodal.bits<1>"));
  equal("recursive-structure", formal, actual, true);
  equal("nominal-structure-retained", formal,
        structure("Other", field("first", digital("Q")) + ", " + field("second", "!nodal.bits<1>")),
        false);
  equal(
      "authored-field-order-retained", formal,
      structure("Packet", field("second", "!nodal.bits<1>") + ", " + field("first", digital("Q"))),
      false);
  auto boundaryCheck = [&](StringRef name, StringRef actualType, bool expected) {
    std::string formalType = shape("N", digital("W"));
    std::string port = "\"nodal.port\"() <{sym_name = \"in\", direction = \"input\", domain = "
                       "@none, metadata = {}, type = " +
                       formalType + "}> : () -> ()\n";
    std::string proxy = "%proxy = \"nodal.instance_port\"() <{instance = @u, port = \"in\", "
                        "direction = \"input\", metadata = {}}> : () -> " +
                        actualType.str() + "\n";
    auto fixture = parseSourceString<mlir::ModuleOp>(
        "module {" + module("Child", childBody + port) + module("Parent", parentBody + proxy) + "}",
        ParserConfig(&context, false));
    Operation *projection = nullptr;
    if (fixture)
      fixture->walk([&](nodal::InstancePortOp op) { projection = op; });
    report(name, projection && (succeeded(nodal::inspectInstancePort(projection)) == expected));
  };
  boundaryCheck("production-instance-port-uses-instantiated-ABI", shape("P", digital("Q")), true);
  boundaryCheck("production-instance-port-rejects-equal-bounds-forgery", shape("P", digital("P")),
                false);
  const auto nested = shape("N", structure("Frame", field("payload", shape("2,W", digital("W"))) +
                                                        ", " + field("flag", "!nodal.bits<1>")));
  auto view = nodal::inspectHierarchyBoundaryType(type(nested), child, u);
  auto q = nodal::resolveOwnedIntegerParameter(parent, SymbolTable::lookupSymbolIn(parent, "Q"));
  auto p = nodal::resolveOwnedIntegerParameter(parent, SymbolTable::lookupSymbolIn(parent, "P"));
  report("logical-leaves-preserve-owner-and-group-boundaries",
         succeeded(view) && view->declarationOwner == child && view->instance == u &&
             view->leaves.size() == 2 && view->leaves[0].path.size() == 1 &&
             view->leaves[0].path[0].structure.getValue() == "Frame" &&
             view->leaves[0].path[0].name.getValue() == "payload" &&
             view->leaves[0].dimensions.size() == 2 &&
             view->leaves[0].dimensions[0].fieldDepth == 0 &&
             view->leaves[0].dimensions[1].fieldDepth == 1 &&
             view->leaves[0].dimensions[1].extents.size() == 2 &&
             view->leaves[1].dimensions.size() == 1);
  report("logical-leaves-retain-actual-parameter-atoms",
         succeeded(view) && succeeded(p) && succeeded(q) &&
             nodal::equivalentInstantiatedIntegerExpressions(view->leaves[0].width, *q) &&
             nodal::equivalentInstantiatedIntegerExpressions(
                 view->leaves[0].dimensions[0].extents[0], *p) &&
             nodal::equivalentInstantiatedIntegerExpressions(
                 view->leaves[0].dimensions[1].extents[1], *q));
  auto large =
      nodal::inspectHierarchyBoundaryType(type(shape("1000000000", "!nodal.bits<1>")), parent);
  report("billion-lanes-remain-one-leaf",
         succeeded(large) && large->leaves.size() == 1 &&
             large->leaves[0].dimensions[0].extents[0].bounds.lower == 1000000000);
  report("wrong-declaration-owner-rejected",
         failed(nodal::inspectHierarchyBoundaryType(type(digital("Q")), parent, u)));
  report("control-not-a-payload-leaf",
         failed(nodal::inspectHierarchyBoundaryType(type("!nodal.capability<\"clock\">"), parent)));
  report("non-bit-pattern-not-a-payload-leaf",
         failed(nodal::inspectHierarchyBoundaryType(type("f64"), parent)));
  report("all-queries-read-only", before == print(parsed->getOperation()));
  llvm::outs() << "HIERARCHY_BOUNDARY_SUMMARY cases=" << cases << " failures=" << failures << '\n';
  return failures ? 1 : 0;
}
