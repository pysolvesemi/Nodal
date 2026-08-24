#include "mlir/IR/BuiltinOps.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/Parser/Parser.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Dialect/Nodal/NodalOps.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/Casting.h"
#include "llvm/Support/raw_ostream.h"

#include <string>

namespace {

int fail(llvm::StringRef message) {
  llvm::errs() << "NODAL-DIALECT-TEST: " << message << '\n';
  return 1;
}

} // namespace

int main() {
  mlir::DialectRegistry registry;
  registry.insert<nodal::NodalDialect>();
  mlir::MLIRContext context(registry);

  auto module =
      mlir::parseSourceString<mlir::ModuleOp>("module { nodal.placeholder \"unit\" }", &context);
  if (!module)
    return fail("valid placeholder did not parse");

  auto &operation = module->getBody()->front();
  if (!llvm::isa<nodal::PlaceholderOp>(&operation))
    return fail("parsed operation is not nodal.placeholder");

  std::string printed;
  llvm::raw_string_ostream stream(printed);
  module->print(stream);
  stream.flush();
  if (printed.find("nodal.placeholder \"unit\"") == std::string::npos)
    return fail("custom parser/printer round-trip changed the placeholder");

  return 0;
}
