#ifndef NODAL_BACKEND_ANALOGVERTICALSLICE_H
#define NODAL_BACKEND_ANALOGVERTICALSLICE_H

#include "nodal/Backend/Backend.h"

#include "llvm/ADT/ArrayRef.h"

namespace nodal {

mlir::LogicalResult verifyBackendOperations(mlir::ModuleOp module, const BackendProfile &profile);
mlir::LogicalResult renderBackendCandidate(llvm::ArrayRef<mlir::Operation *> definitions,
                                           const BackendConfiguration &configuration,
                                           llvm::raw_ostream &output);
mlir::LogicalResult verifyBackendTarget(llvm::StringRef candidate,
                                        const BackendConfiguration &configuration);
mlir::LogicalResult reparseBackendTarget(llvm::StringRef candidate,
                                         const BackendConfiguration &configuration);

} // namespace nodal

#endif // NODAL_BACKEND_ANALOGVERTICALSLICE_H
