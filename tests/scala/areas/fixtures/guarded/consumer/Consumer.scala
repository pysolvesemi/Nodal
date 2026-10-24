package nodal_external.guardedjar
import nodal.*
final class Consumer extends Module:
  val choice = param(0.integer, range = 0 to 1)
  val count = param(3.integer, range = 2 to 8)
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync,
    ResetPolarity.ActiveHigh, 100.MHz)
  val enable = in(Bool)
  val output = out(UInt(8))
  clock:
    val target = Reg(0.U(8))
    if choice == 0 then
      val lanes = Factory.make(count)
      when(enable):
        target := lanes(1).readonly
    else
      when(enable):
        target := 0.U(8)
    output := target
