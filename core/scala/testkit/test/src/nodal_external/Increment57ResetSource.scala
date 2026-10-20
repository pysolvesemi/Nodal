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

/** Fixed physical array element selection remains an exact Clock/Reset capability. */
final class Increment57ShapedResetSource(mode: String) extends Module:
  val clocks = if mode == "matrix" then in(Vec(Clock, 2, 2)) else in(Vec(Clock, 2))
  val resets = if mode == "matrix" then in(Vec(Reset, 2, 2)) else in(Vec(Reset, 2))
  val selectedClock = if mode == "matrix" then clocks.row(1).at(0)
  else if mode == "slice" then clocks.slice(1, 2).reshape(1, 1).at(0, 0)
  else clocks.at(1)
  val selectedReset = if mode == "matrix" then resets.row(1).at(0)
  else if mode == "slice" then resets.slice(1, 2).reshape(1, 1).at(0, 0)
  else resets.at(1)
  val root = ClockDomain.from(
    selectedClock,
    selectedReset,
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    100.MHz,
    name = "root"
  )
  val child = if mode == "combine" then
    val other = ClockDomain.from(
      selectedClock,
      resets.at(0),
      ClockEdge.Rising,
      ResetPolicy.AsyncAssertSyncRelease(2),
      ResetPolarity.ActiveHigh,
      100.MHz,
      name = "other"
    )
    val release = Rdc.sync(ResetController.combine(root.reset, other.reset), stages = 2)
    ClockDomain.generated("child", selectedClock, root, ClockRelation.Same, release)
  else root
  child:
    val done = out(Bool)
    val state = Reg(false.B)
    state := true.B
    done := state

final class Increment57ShapedResetAliasSource(polarityConflict: Boolean) extends Module:
  val clocks = in(Vec(Clock, 2))
  val resets = in(Vec(Reset, 2))
  val root = ClockDomain.from(
    clocks.at(1),
    resets.at(1),
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    100.MHz,
    name = "root"
  )
  val alias = ClockDomain.from(
    clocks.at(1),
    resets.reshape(1, 2).at(0, 1),
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    if polarityConflict then ResetPolarity.ActiveLow else ResetPolarity.ActiveHigh,
    100.MHz,
    name = "alias"
  )
  val release = ResetController.combine(Rdc.sync(root.reset, root), Rdc.sync(alias.reset, alias))

/** Different reset elements may have distinct synchronous receiving clocks. */
final class Increment57ShapedSyncSource(sharedReset: Boolean) extends Module:
  val clocks = in(Vec(Clock, 2))
  val resets = in(Vec(Reset, 2))
  val first = ClockDomain.from(
    clocks.at(0),
    resets.at(0),
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    name = "first"
  )
  val second = ClockDomain.from(
    clocks.at(1),
    resets.at(if sharedReset then 0 else 1),
    ClockEdge.Falling,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    50.MHz,
    name = "second"
  )
  first:
    val doneA = out(Bool)
    val stateA = Reg(false.B)
    stateA := true.B
    doneA := stateA
  second:
    val doneB = out(Bool)
    val stateB = Reg(false.B)
    stateB := true.B
    doneB := stateB

/** Composition preserves synchronous assertion only when every ancestor supplies it. */
final class Increment57SyncCombineSource(mode: String) extends Module:
  val clocks = in(Vec(Clock, 2))
  val resets = in(Vec(Reset, 2))
  val clock = clocks.at(1)
  val resetA = resets.at(0)
  val resetB = resets.at(1)
  val first = ClockDomain.from(
    clock,
    resetA,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    name = "first"
  )
  val second = ClockDomain.from(
    if mode == "different" then clocks.at(0) else clock,
    resetB,
    ClockEdge.Rising,
    if mode.startsWith("mixed") then ResetPolicy.AsyncExternalRelease else ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    name = "second"
  )
  val combined = ResetController.combine(ResetController.combine(first.reset), second.reset)
  val release = if mode == "mixed_sync" then Rdc.sync(combined, stages = 2) else combined
  val child = ClockDomain.generated("child", clock, first, ClockRelation.Same, release)
  child:
    val done = out(Bool)
    val state = Reg(false.B)
    state := true.B
    done := state
