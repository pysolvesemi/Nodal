package external.reuse

import nodal.*

/** External-library contract: implicit inherited domain and no privileged imports. */
final class ClockedRegister(val width: Int = 8) extends Module:
  val enable = in(Bool)
  val input = in(UInt(width))
  val output = out(UInt(width))

  private val state = Reg(0.U(width))

  when(enable):
    state := input

  output := state
