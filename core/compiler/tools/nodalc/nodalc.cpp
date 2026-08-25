#include "circt/Dialect/HW/HWDialect.h"
#include "circt/Support/Version.h"
#include "mlir/IR/DialectRegistry.h"
#include "mlir/Tools/mlir-opt/MlirOptMain.h"
#include "nodal/Dialect/Nodal/NodalDialect.h"
#include "nodal/Support/Version.h"

#include "llvm/Support/CommandLine.h"
#include "llvm/Support/PrettyStackTrace.h"
#include "llvm/Support/raw_ostream.h"

int main(int argc, char **argv) {
  llvm::setBugReportMsg("PLEASE submit a Nodal issue and include the crash backtrace.\n");

  mlir::DialectRegistry registry;
  registry.insert<circt::hw::HWDialect, nodal::NodalDialect>();

  llvm::cl::AddExtraVersionPrinter([](llvm::raw_ostream &os) {
    nodal::printVersion(os);
    os << circt::getCirctVersion() << '\n';
  });

  return mlir::failed(mlir::MlirOptMain(argc, argv, "Nodal native compiler bootstrap", registry));
}
