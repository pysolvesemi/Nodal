package contracts.clockreset.negative

import nodal.*

final class MultiBitSync extends Module:
  val destination = ClockDomain.required("destination")
  val payload = in(UInt(8))
  val crossed = Cdc.sync(payload, to = destination) // diagnostic-anchor: NODAL-CDC-002
