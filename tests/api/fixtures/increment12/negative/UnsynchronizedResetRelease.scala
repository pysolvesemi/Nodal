package contracts.clockreset.negative

import nodal.*

final class UnsynchronizedResetRelease extends Module:
  val clockPin = in(Clock)
  val resetPin = in(Reset)
  val destination = ClockDomain.from(
    clock = clockPin,
    reset = resetPin, // diagnostic-anchor: NODAL-RDC-001
    edge = ClockEdge.Rising,
    policy = ResetPolicy.Async,
    polarity = ResetPolarity.ActiveLow,
    frequency = 100.MHz,
  )
