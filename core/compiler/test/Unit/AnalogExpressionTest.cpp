#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/Casting.h"
#include "llvm/Support/raw_ostream.h"

namespace {

int fail(llvm::StringRef message) {
  llvm::errs() << "AnalogExpressionTest: " << message << '\n';
  return 1;
}

constexpr llvm::StringLiteral kValid = R"mlir(
module {
  "nodal.module"() <{metadata = {root = true}, sym_name = "Rc"}> ({
  ^bb0:
    "nodal.parameter"() <{default_value = 1000.0 : f64, metadata = {}, sym_name = "R", type = f64, variability = "symbolic"}> : () -> ()
    %p = "nodal.terminal"() <{metadata = {}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.node"() <{metadata = {}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %v = "nodal.access"(%branch) <{kind = "potential", metadata = {}}> : (!nodal.branch<"electrical">) -> f64
      %r = "nodal.parameter_ref"() <{metadata = {}, parameter = @R}> : () -> f64
      %i = "nodal.analog_div"(%v, %r) <{metadata = {}}> : (f64, f64) -> f64
      "nodal.contribute"(%branch, %i) <{kind = "flow", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kUnknownParameter = R"mlir(
module {
  "nodal.module"() <{metadata = {root = true}, sym_name = "Bad"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %x = "nodal.parameter_ref"() <{metadata = {}, parameter = @MISSING}> : () -> f64
    }) : () -> ()
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kInvalidContribution = R"mlir(
module {
  "nodal.module"() <{metadata = {root = true}, sym_name = "Bad"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.node"() <{metadata = {}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %zero = "nodal.real_literal"() <{metadata = {}, value = 0.0 : f64}> : () -> f64
      "nodal.contribute"(%branch, %zero) <{kind = "charge", metadata = {}}> : (!nodal.branch<"electrical">, f64) -> ()
    }) : () -> ()
  }) : () -> ()
}
)mlir";

} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);
  context.loadAllAvailableDialects();

  auto valid = mlir::parseSourceString<mlir::ModuleOp>(kValid, &context);
  if (!valid || mlir::failed(mlir::verify(*valid)))
    return fail("valid minimal analog equation did not parse and verify");

  unsigned analogRegions = 0;
  unsigned contributions = 0;
  valid->walk([&](mlir::Operation *operation) {
    analogRegions += llvm::isa<nodal::AnalogOp>(operation);
    contributions += llvm::isa<nodal::ContributeOp>(operation);
  });
  if (analogRegions != 1 || contributions != 1)
    return fail("typed analog region/contribution inventory is incorrect");

  if (mlir::parseSourceString<mlir::ModuleOp>(kUnknownParameter, &context))
    return fail("unknown analog parameter reference was accepted");
  if (mlir::parseSourceString<mlir::ModuleOp>(kInvalidContribution, &context))
    return fail("invalid analog contribution kind was accepted");

  return 0;
}
