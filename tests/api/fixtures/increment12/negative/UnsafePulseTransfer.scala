package contracts.clockreset.negative

import nodal.*

final class UnsafePulseTransfer extends Module:
  val destination = ClockDomain.required("destination")
  val sourcePulse = Pulse(in(Bool))
  val crossed = Cdc.sync(sourcePulse, to = destination) // diagnostic-anchor: NODAL-CDC-003
