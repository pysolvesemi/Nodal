#include "nodal/Support/Version.h"

#include "circt/Support/Version.h"

#include "llvm/ADT/StringRef.h"
#include "llvm/Support/raw_ostream.h"

#include <string>

namespace {

int fail(llvm::StringRef message) {
  llvm::errs() << "NODAL-NATIVE-TEST: " << message << '\n';
  return 1;
}

} // namespace

int main() {
  if (nodal::getNodalVersion() != "0.0.0-dev")
    return fail("unexpected Nodal bootstrap version");
  if (nodal::getNativeToolchainLockId() != "nodal-native-2026.08.20")
    return fail("unexpected native toolchain lock identifier");
  if (nodal::getCirctReleaseTag() != "firtool-1.154.0")
    return fail("unexpected CIRCT release tag");
  if (nodal::getCirctCommit().size() != 40)
    return fail("CIRCT commit is not a full Git SHA");
  if (nodal::getLlvmCommit().size() != 40)
    return fail("LLVM/MLIR commit is not a full Git SHA");

  const std::string circtVersion = circt::getCirctVersion();
  if (!llvm::StringRef(circtVersion).contains(nodal::getCirctReleaseTag()))
    return fail("linked CIRCT library does not report the locked release");

  return 0;
}
