package contracts.clockreset.negative

import nodal.*

final class OrdinaryAlways extends Module:
  val clock = in(Bool)
  val value = out(UInt(8))

  always(clock.rising): // diagnostic-anchor: NODAL-MIGRATION-001
    value := 0.U(8)
