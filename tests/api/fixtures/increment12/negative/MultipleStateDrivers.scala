package contracts.clockreset.negative

import nodal.*

final class MultipleStateDrivers extends Module:
  val firstEnable = in(Bool)
  val secondEnable = in(Bool)
  val state = Reg(0.U(8))

  when(firstEnable):
    state := 1.U(8)

  when(secondEnable):
    state := 2.U(8) // diagnostic-anchor: NODAL-STATE-001
