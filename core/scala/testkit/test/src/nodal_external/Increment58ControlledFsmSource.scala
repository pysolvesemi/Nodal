package nodal_external

import nodal.*

/** Both live instances reuse the same immutable graph, with no lifecycle in that graph. */
final class Increment58ControlledFsmSource(
    mode: String = "valid",
    previous: Option[FsmControl] = None
) extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val restartA = in(Bool)
  val restartB = in(Bool)
  val cancel = in(Bool)
  val enable = in(Bool)
  val go = in(Bool)
  val finish = in(Bool)
  val input = in(UInt(8))
  val statusA = out(EnumType[FsmStatus]())
  val statusB = out(EnumType[FsmStatus]())
  val stateA = out(EnumType[ReusableMode]())
  val stateB = out(EnumType[ReusableMode]())
  val doneA = out(Bool)
  val runningA = out(Bool)
  val cancelledA = out(Bool)
  val enteringA = out(Bool)
  val exitingA = out(Bool)
  val takenA = out(Bool)
  val valueA = out(UInt(8))
  val valueB = out(UInt(8))
  val root = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    if mode == "resetless" then ResetPolicy.None else ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "root"
  )
  var escaped: Option[FsmRun[ReusableMode]] = None
  var exported: Option[FsmControl] = None
  root:
    val countA = if mode == "resetless" then Reg.uninitialized(UInt(8)) else Reg(0.U(8))
    val countB = if mode == "resetless" then Reg.uninitialized(UInt(8)) else Reg(0.U(8))
    val control = FsmControl(restartA, cancel, enable)
    exported = Some(control)
    val first = FsmBindings(
      Vector(ReusableTransfer.start.bind(go), ReusableTransfer.finish.bind(finish)),
      Vector(
        ReusableTransfer.tick.bind { countA := input },
        ReusableTransfer.entry.bind { countA := 4.U(8) },
        ReusableTransfer.edge.bind { countA := 3.U(8) }
      )
    )
    val second = FsmBindings(
      Vector(ReusableTransfer.start.bind(go), ReusableTransfer.finish.bind(finish)),
      Vector(
        ReusableTransfer.tick.bind { countB := input },
        ReusableTransfer.entry.bind { countB := 14.U(8) },
        ReusableTransfer.edge.bind { countB := 13.U(8) }
      )
    )
    ReusableTransfer.graph.instantiateControlled(
      first,
      previous.getOrElse(control),
      illegalState = IllegalStatePolicy.RecoverToInitial
    ): run =>
      statusA := run.status.value
      stateA := run.state.currentState.value
      doneA := run.completed
      runningA := run.running
      cancelledA := run.cancelled
      enteringA := run.state.entering(ReusableMode.Idle)
      exitingA := run.state.exiting(ReusableMode.Run)
      takenA := run.state.transitionTaken
      escaped = Some(run)
    ReusableTransfer.graph.instantiateControlled(
      second,
      FsmControl(restartB, cancel, enable),
      FsmEncoding.OneHot,
      IllegalStatePolicy.RecoverToInitial
    ): run =>
      statusB := run.status.value
      stateB := run.state.currentState.value
    if mode == "escape" then
      val _ = escaped.get.status
    valueA := countA
    valueB := countB

enum InitiallyTerminalMode derives HwEnum:
  case Done

final class Increment58InitiallyTerminalFsmSource extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val restart = in(Bool)
  val cancel = in(Bool)
  val enable = in(Bool)
  val status = out(EnumType[FsmStatus]())
  val done = out(Bool)
  val value = out(UInt(8))
  val root = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "root"
  )
  root:
    val count = Reg(0.U(8))
    val owner = FsmDefinition[InitiallyTerminalMode]("terminal")
    val entry = FsmAction(owner, "entry")
    val graph = FsmDef(owner, InitiallyTerminalMode.Done)(
      FsmFragment("terminal")(FsmState(InitiallyTerminalMode.Done).entry(entry).terminal())
    )
    graph.instantiateControlled(
      FsmBindings(Vector.empty, Vector(entry.bind { count := 7.U(8) })),
      FsmControl(restart, cancel, enable),
      illegalState = IllegalStatePolicy.RecoverToInitial
    ): run =>
      status := run.status.value
      done := run.completed
    value := count
