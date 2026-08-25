#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"
#include "nodal/Dialect/Nodal/NodalTypes.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/Casting.h"
#include "llvm/Support/raw_ostream.h"

namespace {

int fail(llvm::StringRef message) {
  llvm::errs() << "NODAL-CORE-MODEL-TEST: " << message << '\n';
  return 1;
}

} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);

  mlir::Type uintType = mlir::parseType("!nodal.uint<8>", &context);
  auto uintValue = llvm::dyn_cast<nodal::UIntType>(uintType);
  if (!uintValue || uintValue.getWidth() != 8)
    return fail("uint type did not parse with its exact width");

  mlir::Type shapedType = mlir::parseType("!nodal.shaped<\"2,WIDTH\", !nodal.uint<8>>", &context);
  auto shaped = llvm::dyn_cast<nodal::ShapedType>(shapedType);
  if (!shaped || shaped.getDimensions() != "2,WIDTH" || shaped.getElementType() != uintType)
    return fail("shaped type did not retain dimensions and element type");

  mlir::Type streamType =
      mlir::parseType("!nodal.stream<!nodal.shaped<\"2,WIDTH\", !nodal.uint<8>>>", &context);
  auto stream = llvm::dyn_cast<nodal::StreamType>(streamType);
  if (!stream || stream.getPayloadType() != shapedType)
    return fail("stream type did not retain its shaped payload");

  mlir::Type resolvedType =
      mlir::parseType("!nodal.resolved<\"open_drain\", !nodal.bits<1>>", &context);
  auto resolved = llvm::dyn_cast<nodal::ResolvedType>(resolvedType);
  if (!resolved || resolved.getDriveMode() != "open_drain")
    return fail("resolved-net type did not retain its drive mode");

  mlir::Type enumType = mlir::parseType("!nodal.enum<\"ControlState\", 2>", &context);
  auto semanticEnum = llvm::dyn_cast<nodal::EnumType>(enumType);
  if (!semanticEnum || semanticEnum.getSymbol() != "ControlState" || semanticEnum.getWidth() != 2)
    return fail("enum type did not retain symbol and ABI width");

  if (mlir::parseType("!nodal.uint<0>", &context))
    return fail("zero-width uint type was accepted");

  constexpr llvm::StringLiteral source = R"mlir(
module {
  "nodal.enum"() <{
    encoding = "sequential",
    metadata = {},
    sym_name = "State",
    underlying_type = !nodal.uint<1>
  }> ({
    "nodal.enum_case"() <{
      metadata = {},
      sym_name = "Idle",
      value = 0 : i64
    }> : () -> ()
  }) : () -> ()
}
)mlir";
  auto parsed = mlir::parseSourceString<mlir::ModuleOp>(source, &context);
  if (!parsed)
    return fail("canonical enum graph did not parse");
  if (!parsed->getBody()->front().hasTrait<mlir::OpTrait::IsIsolatedFromAbove>())
    return fail("canonical enum graph lost isolation semantics");

  return 0;
}
