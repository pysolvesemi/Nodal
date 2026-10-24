package nodal_external.guardedjar
import nodal.*
final class PureHelper extends Module:
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync,
    ResetPolarity.ActiveHigh, 100.MHz)
  val count = param(3.integer, range = 2 to 8)
  val enable = in(Bool)
  val input = in(Vec(UInt(8), 2))
  val output = input.map: _ =>
    Factory.make(count)(0).readonly
