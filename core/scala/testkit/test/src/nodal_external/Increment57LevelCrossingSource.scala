package nodal_external

import nodal.*

/** Public, constructor-captured level synchronizer witness. */
final class Increment57LevelCrossingSource extends Module:
  val source = ClockDomain.external(
    "source",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val destination = ClockDomain.external(
    "destination",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    20.MHz
  )
  var level = Option.empty[Register[Bool]]
  source:
    val state = Reg(false.B)
    state := true.B
    level = Some(state)
  destination:
    val output = out(Bool)
    val independentOutput = out(Bool)
    val synchronized = Cdc.sync(level.get, to = destination, stages = 2)
    val independent = Cdc.sync(level.get, to = destination, stages = 3)
    val sampled = Reg(false.B)
    sampled := synchronized
    val independentSample = Reg(false.B)
    independentSample := independent
    output := sampled
    independentOutput := independentSample

/** Explicit destination also owns a module-level call outside any ClockDomain.apply. */
final class Increment57ModuleLevelCrossingSource extends Module:
  val source = ClockDomain.external(
    "source",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val destination = ClockDomain.external(
    "destination",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    20.MHz
  )
  var level = Option.empty[Register[Bool]]
  source:
    val state = Reg(false.B)
    state := true.B
    level = Some(state)
  val synchronized = Cdc.sync(level.get, to = destination, stages = 2)
  destination:
    val sampled = Reg(false.B)
    sampled := synchronized
    val output = out(Bool)
    output := sampled
