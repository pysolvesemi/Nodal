#include "nodal/Support/BackendCapabilityRegistry.h"

#include <algorithm>
#include <iterator>

namespace nodal {
namespace {

#include "nodal/Support/BackendCapabilityRegistry.inc"

} // namespace

const BackendOperationDescriptor *lookupBackendOperation(llvm::StringRef operation) {
  auto found = std::lower_bound(std::begin(backendOperationEntries),
                                std::end(backendOperationEntries), operation,
                                [](const BackendOperationDescriptor &entry, llvm::StringRef name) {
                                  return entry.operation < name;
                                });
  return found != std::end(backendOperationEntries) && found->operation == operation ? found
                                                                                     : nullptr;
}

bool isDirectBackendOperation(llvm::StringRef operation) {
  const auto *entry = lookupBackendOperation(operation);
  return entry && entry->admission == BackendOperationAdmission::Direct;
}

bool isAnalogBackendOperation(llvm::StringRef operation) {
  const auto *entry = lookupBackendOperation(operation);
  return entry && (entry->domain == BackendOperationDomain::Analog ||
                   entry->domain == BackendOperationDomain::Mixed);
}

bool isDigitalBackendOperation(llvm::StringRef operation) {
  const auto *entry = lookupBackendOperation(operation);
  return entry && (entry->domain == BackendOperationDomain::Digital ||
                   entry->domain == BackendOperationDomain::Mixed);
}

} // namespace nodal
