#include "nodal/Support/Version.h"

#include "nodal/Support/NodalVersion.inc"
#include "llvm/Support/raw_ostream.h"

namespace nodal {

llvm::StringRef getNodalVersion() { return NODAL_VERSION_STRING; }

llvm::StringRef getNativeToolchainLockId() { return NODAL_NATIVE_LOCK_ID; }

llvm::StringRef getCirctReleaseTag() { return NODAL_CIRCT_RELEASE_TAG; }

llvm::StringRef getCirctCommit() { return NODAL_CIRCT_COMMIT; }

llvm::StringRef getLlvmCommit() { return NODAL_LLVM_COMMIT; }

llvm::StringRef getLlvmPackageVersion() {
  return NODAL_LLVM_PACKAGE_VERSION;
}

void printVersion(llvm::raw_ostream &os) {
  os << "nodalc " << getNodalVersion() << '\n';
  os << "Nodal native toolchain: " << getNativeToolchainLockId() << '\n';
  os << "CIRCT release: " << getCirctReleaseTag() << '\n';
  os << "CIRCT commit: " << getCirctCommit() << '\n';
  os << "LLVM/MLIR commit: " << getLlvmCommit() << '\n';
  os << "LLVM package: " << getLlvmPackageVersion() << '\n';
}

} // namespace nodal
