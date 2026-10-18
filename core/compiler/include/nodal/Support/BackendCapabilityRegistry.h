#ifndef NODAL_SUPPORT_BACKENDCAPABILITYREGISTRY_H
#define NODAL_SUPPORT_BACKENDCAPABILITYREGISTRY_H

#include "llvm/ADT/StringRef.h"

namespace nodal {

enum class BackendOperationDomain { Neutral, Analog, Digital, Mixed };
enum class BackendOperationAdmission { Direct, Guarded, Normalize, Unsupported };

/// Name-level facts only. Actual types, ownership, effects and target placement
/// remain the responsibility of the existing semantic and backend validators.
struct BackendOperationDescriptor {
  llvm::StringRef operation;
  llvm::StringRef feature;
  BackendOperationDomain domain;
  BackendOperationAdmission admission;
};

/// Returns immutable generated storage, or nullptr for an unknown operation.
/// The sorted registry is shared by target-neutral checks and backend admission.
const BackendOperationDescriptor *lookupBackendOperation(llvm::StringRef operation);

/// A direct entry still requires independent semantic and rendering validation.
bool isDirectBackendOperation(llvm::StringRef operation);

/// Intrinsic domain membership only; both helpers include Mixed. Neutral shared
/// operations require type-sensitive classification by the caller, not Support.
bool isAnalogBackendOperation(llvm::StringRef operation);
bool isDigitalBackendOperation(llvm::StringRef operation);

} // namespace nodal

#endif // NODAL_SUPPORT_BACKENDCAPABILITYREGISTRY_H
