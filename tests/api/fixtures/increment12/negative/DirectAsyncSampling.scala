package contracts.clockreset.negative

import nodal.*

final class DirectAsyncSampling extends Module:
  val sourceDomain = ClockDomain.required("source")
  val destinationDomain = ClockDomain.required("destination")
  val sourceValue = in(UInt(8))
  val destinationValue = out(UInt(8))

  destinationDomain:
    val captured = Reg(0.U(8))
    captured := sourceValue // diagnostic-anchor: NODAL-CDC-001
    destinationValue := captured
