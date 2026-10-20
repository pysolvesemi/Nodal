package nodal_external

import nodal.*

/** Flat executable Same contracts preserve independent clocks, state and reset release. */
final class Increment57RelationshipSource(
    structural: Boolean = false,
    nested: Boolean = false,
    edge: ClockEdge = ClockEdge.Rising,
    polarity: ResetPolarity = ResetPolarity.ActiveHigh
) extends Module:
  val srcClock = in(Clock)
  val dstClock = in(Clock)
  val thirdClock = in(Clock)
  val reset = in(Reset)
  val root = ClockDomain.from(
    srcClock,
    reset,
    edge,
    ResetPolicy.AsyncAssertSyncRelease(2),
    polarity,
    100.MHz,
    "root"
  )
  val child = ClockDomain.generated(
    "child",
    if structural then srcClock else dstClock,
    root,
    ClockRelation.Same,
    Rdc.sync(reset, stages = 2)
  )
  var rootValue: Option[Expr[Bool]] = None
  var childValue: Option[Expr[Bool]] = None
  root:
    val rootOut = out(Bool)
    val rootState = Reg(false.B)
    rootState := !rootState
    rootOut := rootState
    rootValue = Some(rootState)
  child:
    val childOut = out(Bool)
    val mixedOut = out(Bool)
    val childState = Reg(false.B)
    val mixedState = Reg(false.B)
    childState := rootValue.get
    mixedState := rootValue.get && !childState
    childOut := childState
    mixedOut := mixedState
    childValue = Some(childState)
  if nested then
    val third = ClockDomain.generated(
      "third",
      thirdClock,
      child,
      ClockRelation.Same,
      Rdc.sync(reset, stages = 2)
    )
    third:
      val thirdOut = out(Bool)
      val thirdState = Reg(false.B)
      thirdState := rootValue.get && !childValue.get
      thirdOut := thirdState

final class Increment57ShapedRelationshipSource extends Module:
  val clocks = in(Vec(Clock, 2))
  val resets = in(Vec(Reset, 2))
  val root = ClockDomain.from(
    clocks.at(0),
    resets.at(1),
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    100.MHz,
    "root"
  )
  val child = ClockDomain.generated(
    "child",
    clocks.slice(1, 2).at(0),
    root,
    ClockRelation.Same,
    Rdc.sync(resets.slice(1, 2).at(0), stages = 2)
  )
  var rootValue: Option[Expr[Bool]] = None
  root:
    val rootOut = out(Bool)
    val rootState = Reg(false.B)
    rootState := !rootState
    rootOut := rootState
    rootValue = Some(rootState)
  child:
    val childOut = out(Bool)
    val childState = Reg(false.B)
    childState := rootValue.get
    childOut := childState

final class Increment57RelationshipCategorySource(
    relation: ClockRelation,
    sample: Boolean = false,
    reuseReset: Boolean = false,
    structural: Boolean = false,
    cdcMixed: Boolean = false
) extends Module:
  val srcClock = in(Clock)
  val dstClock = in(Clock)
  val reset = in(Reset)
  val root = ClockDomain.from(
    srcClock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveHigh,
    100.MHz,
    "root"
  )
  val child = ClockDomain.generated(
    "child",
    if structural then srcClock else dstClock,
    root,
    relation,
    if reuseReset then root.reset else Rdc.sync(reset, stages = 2)
  )
  var source: Option[Expr[Bool]] = None
  root:
    val bit = in(Bool)
    source = Some(bit)
  child:
    val ownBit = in(Bool)
    val state = Reg(false.B)
    if sample then state := source.get
    if cdcMixed then state := Cdc.sync(source.get && ownBit, to = child)
    if !sample && !cdcMixed then state := ownBit
