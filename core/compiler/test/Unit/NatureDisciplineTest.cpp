#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/IR/Verifier.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NatureDiscipline.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/Casting.h"
#include "llvm/Support/raw_ostream.h"

namespace {

int fail(llvm::StringRef message) {
  llvm::errs() << "NatureDisciplineTest: " << message << '\n';
  return 1;
}

constexpr llvm::StringLiteral kValid = R"mlir(
module {
  "nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.nature"() <{abstol = 1.0e-12 : f64, access = "I", metadata = {}, sym_name = "Current", units = "A"}> : () -> ()
  "nodal.nature"() <{abstol = 1.0e-3 : f64, access = "Temp", metadata = {}, sym_name = "Temperature", units = "K"}> : () -> ()
  "nodal.nature_import"() <{definition_hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", metadata = {}, source = "std://disciplines/electrical@2023", sym_name = "VoltageImported", target = @Voltage}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @VoltageImported, sym_name = "electrical"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Current, metadata = {}, potential = @Voltage, sym_name = "electrical_monitor"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", metadata = {}, potential = @Temperature, sym_name = "thermal_signal"}> : () -> ()
  "nodal.discipline_import"() <{definition_hash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", metadata = {}, source = "std://disciplines/electrical@2023", sym_name = "electrical_imported", target = @electrical}> : () -> ()
  "nodal.module"() <{metadata = {root = true}, sym_name = "Fixture"}> ({
  ^bb0:
  }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kInvalidTolerance = R"mlir(
module {
  "nodal.nature"() <{abstol = 0.0 : f64, access = "V", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> ({ ^bb0: }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kInvalidAssociation = R"mlir(
module {
  "nodal.nature"() <{abstol = 1.0e-6 : f64, access = "V", metadata = {}, sym_name = "Voltage", units = "V"}> : () -> ()
  "nodal.discipline"() <{domain = "continuous", flow = @Missing, metadata = {}, potential = @Voltage, sym_name = "electrical"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> ({ ^bb0: }) : () -> ()
}
)mlir";

constexpr llvm::StringLiteral kImportCycle = R"mlir(
module {
  "nodal.nature_import"() <{definition_hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", metadata = {}, source = "pkg://a", sym_name = "A", target = @B}> : () -> ()
  "nodal.nature_import"() <{definition_hash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", metadata = {}, source = "pkg://b", sym_name = "B", target = @A}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Fixture"}> ({ ^bb0: }) : () -> ()
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
    return fail("valid nature/discipline inventory did not verify");

  unsigned natures = 0;
  unsigned disciplines = 0;
  valid->walk([&](mlir::Operation *operation) {
    natures += llvm::isa<nodal::NatureOp>(operation);
    disciplines += llvm::isa<nodal::DisciplineOp>(operation);
  });
  if (natures != 3 || disciplines != 3)
    return fail("typed nature/discipline inventory is incorrect");

  auto electrical = mlir::FlatSymbolRefAttr::get(&context, "electrical");
  auto monitor = mlir::FlatSymbolRefAttr::get(&context, "electrical_monitor");
  auto imported = mlir::FlatSymbolRefAttr::get(&context, "electrical_imported");
  auto thermal = mlir::FlatSymbolRefAttr::get(&context, "thermal_signal");

  auto compatible = nodal::areDisciplinesCompatible(
      valid->getOperation(), electrical, monitor);
  auto aliasCompatible = nodal::areDisciplinesCompatible(
      valid->getOperation(), electrical, imported);
  auto incompatible = nodal::areDisciplinesCompatible(
      valid->getOperation(), electrical, thermal);
  if (mlir::failed(compatible) || !*compatible ||
      mlir::failed(aliasCompatible) || !*aliasCompatible ||
      mlir::failed(incompatible) || *incompatible)
    return fail("canonical discipline compatibility is incorrect");

  if (mlir::parseSourceString<mlir::ModuleOp>(kInvalidTolerance, &context))
    return fail("non-positive nature tolerance was accepted");
  if (mlir::parseSourceString<mlir::ModuleOp>(kInvalidAssociation, &context))
    return fail("unknown flow nature was accepted");
  if (mlir::parseSourceString<mlir::ModuleOp>(kImportCycle, &context))
    return fail("cyclic nature import was accepted");

  return 0;
}
