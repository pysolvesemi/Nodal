package nodal_external
import nodal.*
final class GuardedLegacyValueStatement extends Module:
  val choice = param(0.integer, range = 0 to 1)
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync,
    ResetPolarity.ActiveHigh, 100.MHz)
  val input = in(Vec(UInt(8), 2))
  val output = out(Vec(UInt(8), 2))
  val selected: Expr[Vec[UInt]] =
    if choice == 0 then
      if choice == 0 then
        hdlRange(0, 2): i =>
          output.writable.at(i) := input.at(i)
      else ()
      input
    else input
