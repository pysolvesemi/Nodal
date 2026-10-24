package nodal_external
import nodal.*
final class GuardedLegacyHiddenEffect extends Module:
  val choice = param(0.integer, range = 0 to 1)
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync,
    ResetPolarity.ActiveHigh, 100.MHz)
  val input = in(Vec(UInt(8), 2))
  val output = out(Vec(UInt(8), 2))
  final def hidden(): Unit = println("must never execute")
  if choice == 0 then ()
  else
    hdlRange(0, 2): i =>
      hidden()
      output.writable.at(i) := input.at(i)
