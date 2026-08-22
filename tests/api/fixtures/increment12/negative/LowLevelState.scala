package contracts.clockreset.negative

import nodal.*

final class LowLevelState extends Module:
  val trigger = in(Bool)

  nodal.lowlevel.process(trigger.rising):
    val state = Reg(0.U(8)) // diagnostic-anchor: NODAL-LOWLEVEL-001
    state := state + 1.U(8)
