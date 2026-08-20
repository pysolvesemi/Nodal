#ifndef NODAL_SUPPORT_VERSION_H
#define NODAL_SUPPORT_VERSION_H

#include "llvm/ADT/StringRef.h"

namespace llvm {
class raw_ostream;
}

namespace nodal {

llvm::StringRef getNodalVersion();
llvm::StringRef getNativeToolchainLockId();
llvm::StringRef getCirctReleaseTag();
llvm::StringRef getCirctCommit();
llvm::StringRef getLlvmCommit();
llvm::StringRef getLlvmPackageVersion();

void printVersion(llvm::raw_ostream &os);

} // namespace nodal

#endif // NODAL_SUPPORT_VERSION_H
