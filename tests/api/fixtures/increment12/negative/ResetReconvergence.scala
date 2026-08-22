package contracts.clockreset.negative

import nodal.*

final class ResetReconvergence extends Module:
  val first = ClockDomain.required("first")
  val second = ClockDomain.required("second")
  val combined = ResetController.combine(
    Rdc.sync(first.reset, to = second),
    first.reset, // diagnostic-anchor: NODAL-RDC-002
  )
