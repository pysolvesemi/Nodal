#ifndef NODAL_DIAGNOSTICS_DIAGNOSTICMAPPING_H
#define NODAL_DIAGNOSTICS_DIAGNOSTICMAPPING_H

#include "mlir/Pass/Pass.h"
#include "nodal/Diagnostics/DiagnosticSupport.h"

#include <memory>

namespace nodal {

/// Create/register the private Increment 22 cross-layer diagnostic verifier.
std::unique_ptr<mlir::Pass> createCrossLayerDiagnosticPass();
void registerNodalDiagnosticPasses();

} // namespace nodal

#endif // NODAL_DIAGNOSTICS_DIAGNOSTICMAPPING_H
