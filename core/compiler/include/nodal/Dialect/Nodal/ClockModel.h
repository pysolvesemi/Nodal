#ifndef NODAL_DIALECT_NODAL_CLOCKMODEL_H
#define NODAL_DIALECT_NODAL_CLOCKMODEL_H
#include "nodal/Dialect/Nodal/PhysicalControlModel.h"
#include "nodal/Dialect/Nodal/SequentialModel.h"

namespace nodal {
class DomainModel;
/// Physical input-element identity or the exact owned producer. Derived clocks
/// never alias their parents, even when a gate is always enabled.
struct ClockIdentity {
  std::optional<PhysicalControl> physical;
  mlir::Operation *producer = nullptr;
  bool operator==(const ClockIdentity &other) const {
    return producer == other.producer && physical == other.physical;
  }
};
struct OwnedClock {
  mlir::Operation *operation = nullptr;
  mlir::Operation *domain = nullptr;
  mlir::Operation *parents[2] = {nullptr, nullptr};
  mlir::Operation *parentBindings[2] = {nullptr, nullptr};
  bool mux = false;
  uint64_t stateBits = 0;
};
/// Bounded clock-only proof. Does not inspect resets or controls and therefore
/// can be consumed by reset proof without mutually recursive verification.
mlir::FailureOr<ClockIdentity> resolveClock(mlir::Value value, mlir::Operation *use);
bool sameClock(mlir::Value left, mlir::Operation *leftUse, mlir::Value right,
               mlir::Operation *rightUse);
mlir::FailureOr<OwnedClock> inspectOwnedClock(mlir::Operation *op, const DomainModel &domains);
mlir::LogicalResult verifyClockProducer(mlir::Operation *op);
mlir::LogicalResult verifyClockModel(mlir::Operation *module, SequentialModelLimits limits = {});
/// Every binding of a mux clock (including a downstream gated clock) must keep
/// its common assertion roots and release on its actual output clock.
mlir::LogicalResult verifyClockBindingReset(mlir::Operation *binding, mlir::Operation *domain,
                                            const DomainModel &domains);
} // namespace nodal
#endif
