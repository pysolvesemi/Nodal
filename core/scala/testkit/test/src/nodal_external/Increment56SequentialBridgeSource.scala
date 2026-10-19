package nodal_external

import nodal.*

/** Public first-checkpoint witness: actual typed domains, priority and a pure structural RHS. */
final class Increment56SequentialBridgeSource extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val core = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "core"
  )
  val MODE = param(0.integer)
  val enable = in(Bool)
  val input = in(UInt(8))
  val output = out(UInt(8))
  core:
    val state = Reg(0.U(8))
    when(MODE == 0):
      state := input
      when(enable):
        state := ~input
    elsewhen(enable):
      val choice = if MODE == 1 then input else ~input
      state := choice
    otherwise:
      state := 0.U(8)
    output := state
