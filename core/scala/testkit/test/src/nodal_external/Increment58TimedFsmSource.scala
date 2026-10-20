package nodal_external

import nodal.*

enum TimedFsmMode derives HwEnum:
  case Wait, Done

object TimedFsmGraphs:
  val owner = FsmDefinition[TimedFsmMode]("timed-worker")
  val transfer = FsmTransfer(owner, "transfer")
  def timer(cycles: Int): FsmDef[TimedFsmMode] =
    FsmDef(owner, TimedFsmMode.Wait)(FsmFragment("timer")(
      FsmState(TimedFsmMode.Wait).after(cycles)(TimedFsmMode.Done),
      FsmState(TimedFsmMode.Done).terminal()
    ))
  val transferred = FsmDef(owner, TimedFsmMode.Wait)(FsmFragment("transfer")(
    FsmState(TimedFsmMode.Wait).awaitTransfer(transfer)(TimedFsmMode.Done),
    FsmState(TimedFsmMode.Done).terminal()
  ))

/** Actual public construction for timer and read-only Stream-transfer observations. */
final class Increment58TimedFsmSource(
    profile: String = "timer",
    previous: Option[Stream[UInt]] = None
) extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val domain = ClockDomain.from(
    clock,
    reset,
    edge = ClockEdge.Rising,
    policy = if profile == "resetless" then ResetPolicy.None else ResetPolicy.Sync,
    polarity = ResetPolarity.ActiveHigh,
    frequency = 100.MHz,
    name = "root"
  )
  var exported: Option[Stream[UInt]] = None
  domain:
    val restartA = in(Bool)
    val restartB = in(Bool)
    val cancel = in(Bool)
    val enable = in(Bool)
    val inputData = in(UInt(8))
    val inputValid = in(Bool)
    val inputReady = in(Bool)
    val stateA = out(EnumType[TimedFsmMode]())
    val stateB = out(EnumType[TimedFsmMode]())
    val statusA = out(EnumType[FsmStatus]())
    val statusB = out(EnumType[FsmStatus]())
    val observedReady = out(Bool)
    val stream = Stream(inputData, inputValid)
    stream.accept(inputReady)
    exported = Some(stream)
    observedReady := stream.ready
    if profile == "live" then
      fsm(TimedFsmGraphs.owner, TimedFsmMode.Wait): machine =>
        stateA := machine.currentState.value
        stateB := machine.currentState.value
        statusA := enumLiteral(EnumType[FsmStatus](), FsmStatus.Running)
        statusB := enumLiteral(EnumType[FsmStatus](), FsmStatus.Running)
        machine.state(TimedFsmMode.Wait)(_.after(3)(TimedFsmMode.Done))
        machine.state(TimedFsmMode.Done)(_.terminal())
    else
      val graph = if profile == "transfer" || previous.nonEmpty then TimedFsmGraphs.transferred
      else
        TimedFsmGraphs.timer(if profile == "one" then 1
        else if profile == "maximum" then Int.MaxValue else 3)
      val bindings = if profile == "transfer" || previous.nonEmpty then
        FsmBindings(Vector(TimedFsmGraphs.transfer.bind(previous.getOrElse(stream))), Vector.empty)
      else FsmBindings[TimedFsmMode](Vector.empty)
      graph.instantiateControlled(
        bindings,
        FsmControl(restartA, cancel, enable),
        illegalState = IllegalStatePolicy.RecoverToInitial
      ): run =>
        stateA := run.state.currentState.value
        statusA := run.status.value
      graph.instantiateControlled(
        bindings,
        FsmControl(restartB, cancel, enable),
        illegalState = IllegalStatePolicy.RecoverToInitial
      ): run =>
        stateB := run.state.currentState.value
        statusB := run.status.value
