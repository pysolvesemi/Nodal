package nodal_external
import nodal.*
final class GuardedLegacyForeach extends Module:
  val choice = param(0.integer, range = 0 to 1)
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync,
    ResetPolarity.ActiveHigh, 100.MHz)
  val enable = in(Bool)
  clock:
    if choice == 0 then
      hdlRange(0, 2): i =>
        val _ = i
    else ()
