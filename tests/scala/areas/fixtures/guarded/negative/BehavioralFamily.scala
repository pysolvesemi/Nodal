package nodal_external
import nodal.*
final class BehavioralFamily extends Module:
  val choice = param(0.integer, range = 0 to 1)
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync,
    ResetPolarity.ActiveHigh, 100.MHz)
  val enable = in(Bool)
  clock:
    when(enable):
      val lanes = for i <- hdlRange(0, 2) yield new Area:
        val stored = Reg(0.U(8))
        val _ = (i, stored)
      val _ = lanes
