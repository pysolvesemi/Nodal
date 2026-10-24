package nodal_external

import nodal.*

final class Increment58RequiredTimedFsmLeaf extends Module:
  val required = ClockDomain.required("required")
  val restart = in(Bool)
  val cancel = in(Bool)
  val enable = in(Bool)
  val state = out(EnumType[TimedFsmMode]())
  val status = out(EnumType[FsmStatus]())
  required:
    TimedFsmGraphs.timer(3).instantiateControlled(
      FsmBindings[TimedFsmMode](Vector.empty),
      FsmControl(restart, cancel, enable),
      illegalState = IllegalStatePolicy.RecoverToInitial
    ): run =>
      state := run.state.currentState.value
      status := run.status.value

/** One formal timer definition serves reset-bearing and resetless occurrences. */
final class Increment58RequiredTimedFsmSource extends Module:
  val clockA = in(Clock)
  val resetA = in(Reset)
  val clockB = in(Clock)
  val resetB = in(Reset)
  val first = ClockDomain.from(
    clockA,
    resetA,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "first"
  )
  val second = ClockDomain.from(
    clockB,
    resetB,
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    25.MHz,
    "second"
  )
  first:
    val restartA = in(Bool)
    val cancelA = in(Bool)
    val enableA = in(Bool)
    val stateA = out(EnumType[TimedFsmMode]())
    val statusA = out(EnumType[FsmStatus]())
    val childA = instance(new Increment58RequiredTimedFsmLeaf).domain(first)
    childA(_.restart) := restartA
    childA(_.cancel) := cancelA
    childA(_.enable) := enableA
    stateA := childA(_.state)
    statusA := childA(_.status)
  second:
    val restartB = in(Bool)
    val cancelB = in(Bool)
    val enableB = in(Bool)
    val stateB = out(EnumType[TimedFsmMode]())
    val statusB = out(EnumType[FsmStatus]())
    val childB = instance(new Increment58RequiredTimedFsmLeaf).domain(second)
    childB(_.restart) := restartB
    childB(_.cancel) := cancelB
    childB(_.enable) := enableB
    stateB := childB(_.state)
    statusB := childB(_.status)
