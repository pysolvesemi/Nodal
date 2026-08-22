package contracts.clockreset.negative

import nodal.*

final class MissingDomain extends Module:
  val value = out(UInt(8))
  val state = Reg(0.U(8)) // diagnostic-anchor: NODAL-DOMAIN-001
  value := state
