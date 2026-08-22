package contracts.clockreset.negative

import nodal.*

final class BooleanClock extends Module:
  val booleanClock = in(Bool)
  val resetPin = in(Reset)
  val domain = ClockDomain.from(
    clock = booleanClock, // diagnostic-anchor: NODAL-CLOCK-001
    reset = resetPin,
    edge = ClockEdge.Rising,
    policy = ResetPolicy.Sync,
    polarity = ResetPolarity.ActiveHigh,
    frequency = 100.MHz,
  )
