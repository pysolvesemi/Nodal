package nodal_external

import nodal.*

/** Public-source reset witness: independent child clock may run while parent is stopped. */
final class Increment57ResetSource(edge: ClockEdge, polarity: ResetPolarity, mode: String)
    extends Module:
  val childClock = in(Clock)
  val root = ClockDomain.external(
    "root",
    edge,
    if mode == "external" then ResetPolicy.AsyncExternalRelease
    else ResetPolicy.AsyncAssertSyncRelease(2),
    polarity,
    100.MHz
  )
  val child = if mode == "external" then root
  else
    val first = Rdc.sync(root.reset, stages = 2)
    val release = if mode == "cascade" then Rdc.sync(first, stages = 3) else first
    ClockDomain.generated("child", childClock, root, ClockRelation.Asynchronous, release)
  child:
    val done = out(Bool)
    val state = Reg(false.B)
    state := true.B
    done := state

final class Increment57MixedResetSource extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveLow,
    100.MHz
  )
  val other = ClockDomain.external(
    "other",
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    50.MHz
  )
  val childClock = in(Clock)
  val release = Rdc.sync(ResetController.combine(root.reset, other.reset), stages = 2)
  val child = ClockDomain.generated("child", childClock, root, ClockRelation.Asynchronous, release)
  child:
    val done = out(Bool)
    val state = Reg(false.B)
    state := true.B
    done := state

final class Increment57NoStateResetSource(policy: ResetPolicy) extends Module:
  val root =
    ClockDomain.external("root", ClockEdge.Rising, policy, ResetPolarity.ActiveHigh, 100.MHz)

final class Increment57InvalidResetSource(mode: String) extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val root = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.AsyncExternalRelease,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  if mode == "polarity" then
    val _ = ClockDomain.from(
      clock,
      reset,
      ClockEdge.Rising,
      ResetPolicy.AsyncExternalRelease,
      ResetPolarity.ActiveLow,
      100.MHz,
      name = "other"
    )
  else
    val first = Rdc.sync(reset, root, stages = 2)
    val second = Rdc.sync(reset, root, stages = 2)
    val _ = ResetController.combine(first, second)

final class Increment57AliasedClockSource extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val first = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.AsyncExternalRelease,
    ResetPolarity.ActiveHigh,
    100.MHz,
    name = "first"
  )
  val second = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.AsyncExternalRelease,
    ResetPolarity.ActiveHigh,
    100.MHz,
    name = "second"
  )
