package nodal_empty_init
import nodal.*
final class EmptyInitializedSource extends Module:
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync, ResetPolarity.ActiveHigh, 100.MHz)
  clock:
    val lanes =
      for i <- hdlRange(0, 0) yield new Area:
        val stored = Reg(0.U(i + 1.integer))
    val _ = lanes
final class AttainedInitializedSource extends Module:
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync, ResetPolarity.ActiveHigh, 100.MHz)
  clock:
    val lanes =
      for i <- hdlRange(0, 2) yield new Area:
        val stored = Reg(0.U(i + 1.integer))
    val _ = lanes
