package nodal_external
import nodal.*
final class GuardedLegacyStorage extends Module:
  val choice = param(0.integer, range = 0 to 1)
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync,
    ResetPolarity.ActiveHigh, 100.MHz)
  val input = in(Vec(UInt(8), 2))
  val output = out(Vec(UInt(8), 2))

  if choice == 0 then ()
  else
    hdlRange(0, 2): i =>
      val stored = Reg(0.U(8))
      val _ = stored
      output.writable.at(i) := input.at(i)
