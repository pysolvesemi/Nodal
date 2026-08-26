#include "circt/Support/Version.h"
#include "mlir/Tools/mlir-translate/MlirTranslateMain.h"
#include "nodal/Backend/Backend.h"
#include "nodal/Support/Version.h"

#include "llvm/Support/CommandLine.h"
#include "llvm/Support/PrettyStackTrace.h"
#include "llvm/Support/raw_ostream.h"

int main(int argc, char **argv) {
  llvm::setBugReportMsg("PLEASE submit a Nodal issue and include the crash backtrace.\n");

  nodal::registerNodalBackendTranslations();

  llvm::cl::AddExtraVersionPrinter([](llvm::raw_ostream &os) {
    nodal::printVersion(os);
    os << circt::getCirctVersion() << '\n';
  });

  return mlir::failed(mlir::mlirTranslateMain(argc, argv, "Nodal backend translation driver"));
}
