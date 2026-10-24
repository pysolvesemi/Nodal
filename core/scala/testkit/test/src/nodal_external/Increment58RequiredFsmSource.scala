package nodal_external

import nodal.*

enum RequiredFsmMode derives HwEnum:
  case Idle, Run, Done

object RequiredFsmGraph:
  val owner = FsmDefinition[RequiredFsmMode]("required-transfer")
  val start = FsmGuard(owner, "start")
  val finish = FsmGuard(owner, "finish")
  val enter = FsmAction(owner, "enter")
  val active = FsmAction(owner, "active")
  val graph = FsmDef(owner, RequiredFsmMode.Idle)(
    FsmFragment("transfer")(
      FsmState(RequiredFsmMode.Idle).entry(enter).on(start)(RequiredFsmMode.Run),
      FsmState(RequiredFsmMode.Run).active(active).on(finish)(RequiredFsmMode.Done),
      FsmState(RequiredFsmMode.Done).terminal()
    )
  )

/** Identical source has no material reset, edge or polarity decision. */
final class Increment58RequiredFsmLeaf(mandatoryRegister: Boolean = false) extends Module:
  val required = ClockDomain.required("required")
  val restart = in(Bool)
  val cancel = in(Bool)
  val enable = in(Bool)
  val start = in(Bool)
  val finish = in(Bool)
  val input = in(UInt(8))
  val status = out(EnumType[FsmStatus]())
  val state = out(EnumType[RequiredFsmMode]())
  val done = out(Bool)
  val value = out(UInt(8))
  required:
    val stored = if mandatoryRegister then Reg(0.U(8)) else Reg.uninitialized(UInt(8))
    val bindings = FsmBindings(
      Vector(RequiredFsmGraph.start.bind(start), RequiredFsmGraph.finish.bind(finish)),
      Vector(
        RequiredFsmGraph.enter.bind { stored := 7.U(8) },
        RequiredFsmGraph.active.bind { stored := input }
      )
    )
    RequiredFsmGraph.graph.instantiateControlled(
      bindings,
      FsmControl(restart, cancel, enable),
      illegalState = IllegalStatePolicy.RecoverToInitial
    ): run =>
      status := run.status.value
      state := run.state.currentState.value
      done := run.completed
    value := stored

final class Increment58RequiredFsmForward extends Module:
  val required = ClockDomain.required("required")
  val restart = in(Bool)
  val cancel = in(Bool)
  val enable = in(Bool)
  val start = in(Bool)
  val finish = in(Bool)
  val input = in(UInt(8))
  val status = out(EnumType[FsmStatus]())
  val state = out(EnumType[RequiredFsmMode]())
  val done = out(Bool)
  val value = out(UInt(8))
  val child = instance(new Increment58RequiredFsmLeaf).domain(required)
  child(_.restart) := restart
  child(_.cancel) := cancel
  child(_.enable) := enable
  child(_.start) := start
  child(_.finish) := finish
  child(_.input) := input
  status := child(_.status)
  state := child(_.state)
  done := child(_.done)
  value := child(_.value)

/** Reset-bearing and resetless occurrences share one actual reusable FSM definition. */
final class Increment58RequiredFsmSource(profile: String = "mixed") extends Module:
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
    if profile == "material" then ClockEdge.Falling else ClockEdge.Rising,
    if profile == "material" then ResetPolicy.Sync else ResetPolicy.None,
    if profile == "material" then ResetPolarity.ActiveLow else ResetPolarity.ActiveHigh,
    25.MHz,
    "second"
  )
  first:
    val restartA = in(Bool)
    val cancelA = in(Bool)
    val enableA = in(Bool)
    val startA = in(Bool)
    val finishA = in(Bool)
    val inputA = in(UInt(8))
    val statusA = out(EnumType[FsmStatus]())
    val stateA = out(EnumType[RequiredFsmMode]())
    val doneA = out(Bool)
    val valueA = out(UInt(8))
    val childA = instance(new Increment58RequiredFsmLeaf).domain(first)
    childA(_.restart) := restartA
    childA(_.cancel) := cancelA
    childA(_.enable) := enableA
    childA(_.start) := startA
    childA(_.finish) := finishA
    childA(_.input) := inputA
    statusA := childA(_.status)
    stateA := childA(_.state)
    doneA := childA(_.done)
    valueA := childA(_.value)
  second:
    val restartB = in(Bool)
    val cancelB = in(Bool)
    val enableB = in(Bool)
    val startB = in(Bool)
    val finishB = in(Bool)
    val inputB = in(UInt(8))
    val statusB = out(EnumType[FsmStatus]())
    val stateB = out(EnumType[RequiredFsmMode]())
    val doneB = out(Bool)
    val valueB = out(UInt(8))
    if profile == "forwarded" then
      val childB = instance(new Increment58RequiredFsmForward).domain(second)
      childB(_.restart) := restartB
      childB(_.cancel) := cancelB
      childB(_.enable) := enableB
      childB(_.start) := startB
      childB(_.finish) := finishB
      childB(_.input) := inputB
      statusB := childB(_.status)
      stateB := childB(_.state)
      doneB := childB(_.done)
      valueB := childB(_.value)
    else
      val childB = instance(new Increment58RequiredFsmLeaf(profile == "mandatory")).domain(second)
      childB(_.restart) := restartB
      childB(_.cancel) := cancelB
      childB(_.enable) := enableB
      childB(_.start) := startB
      childB(_.finish) := finishB
      childB(_.input) := inputB
      statusB := childB(_.status)
      stateB := childB(_.state)
      doneB := childB(_.done)
      valueB := childB(_.value)
