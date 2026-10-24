package nodal_external.guardedjar
import nodal.*
final class LoopHelper extends Module:
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync,
    ResetPolarity.ActiveHigh, 100.MHz)
  val count = param(3.integer, range = 2 to 8)
  val enable = in(Bool)
  loop(LoopBound.Static(2)): i =>
    val _ = i
    val _ = Factory.make(count)
