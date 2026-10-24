package nodal_external

import nodal.*

enum ReusableMode derives HwEnum:
  case Idle, Run, Done

/** There are no module handles, dynamic values or callbacks in this library graph. */
object ReusableTransfer:
  val owner = FsmDefinition[ReusableMode]("transfer")
  val start = FsmGuard(owner, "start")
  val finish = FsmGuard(owner, "finish")
  val tick = FsmAction(owner, "tick")
  val entry = FsmAction(owner, "entry")
  val edge = FsmAction(owner, "edge")
  val work = FsmFragment("work")(
    FsmState(ReusableMode.Idle).onWithAction(start, edge)(ReusableMode.Run),
    FsmState(ReusableMode.Run).entry(entry).active(tick).on(finish)(ReusableMode.Done)
  )
  val completion = FsmFragment("completion")(FsmState(ReusableMode.Done).terminal())
  val graph = FsmDef(owner, ReusableMode.Idle)(work, completion)

/** Actual reusable graph -> independent captured flat machines -> unchanged native ingress. */
final class Increment58ReusableFsmSource(mode: String = "valid") extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val requestA = in(Bool)
  val requestB = in(Bool)
  val finish = in(Bool)
  val input = in(UInt(8))
  val doneA = out(Bool)
  val doneB = out(Bool)
  val stateA = out(EnumType[ReusableMode]())
  val stateB = out(EnumType[ReusableMode]())
  val valueA = out(UInt(8))
  val valueB = out(UInt(8))
  val root = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "root"
  )
  var escaped: Option[FsmInstance[ReusableMode]] = None
  root:
    val policy =
      if mode == "recovery" then IllegalStatePolicy.RecoverToInitial else IllegalStatePolicy.Error
    val countA = Reg(0.U(8))
    val countB = if mode == "shared-write" then countA else Reg(0.U(8))
    val firstStart = if mode == "foreign" then FsmGuard(ReusableTransfer.owner, "start")
    else ReusableTransfer.start
    val guards =
      if mode == "missing" then Vector(firstStart.bind(requestA))
      else if mode == "duplicate" then
        Vector(
          firstStart.bind(requestA),
          firstStart.bind(requestB),
          ReusableTransfer.finish.bind(finish)
        )
      else if mode == "extra" then
        Vector(
          firstStart.bind(requestA),
          ReusableTransfer.finish.bind(finish),
          FsmGuard(ReusableTransfer.owner, "extra").bind(requestB)
        )
      else Vector(firstStart.bind(requestA), ReusableTransfer.finish.bind(finish))
    val first = FsmBindings(
      guards,
      Vector(
        ReusableTransfer.tick.bind { countA := input },
        ReusableTransfer.entry.bind { countA := 4.U(8) },
        ReusableTransfer.edge.bind { countA := 3.U(8) }
      )
    )
    val second = FsmBindings(
      Vector(ReusableTransfer.start.bind(requestB), ReusableTransfer.finish.bind(finish)),
      Vector(
        ReusableTransfer.tick.bind { countB := input },
        ReusableTransfer.entry.bind { countB := 4.U(8) },
        ReusableTransfer.edge.bind { countB := 3.U(8) }
      )
    )
    ReusableTransfer.graph.instantiate(first, illegalState = policy): instance =>
      escaped = Some(instance)
      doneA := instance.completed
      stateA := instance.currentState.value
    if mode == "escape" then doneB := escaped.get.completed
    else
      ReusableTransfer.graph.instantiate(second, FsmEncoding.OneHot, policy): instance =>
        doneB := instance.completed
        stateB := instance.currentState.value
    valueA := countA
    valueB := countB

final class Increment58CustomReusableFsmSource extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val go = in(Bool)
  val result = out(EnumType[ReusableMode]())
  val root = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Falling,
    ResetPolicy.Sync,
    ResetPolarity.ActiveLow,
    100.MHz,
    "root"
  )
  root:
    val owner = FsmDefinition[ReusableMode]("custom")
    val guard = FsmGuard(owner, "go")
    val graph = FsmDef(owner, ReusableMode.Idle)(
      FsmFragment("all")(
        FsmState(ReusableMode.Idle).on(guard)(ReusableMode.Run),
        FsmState(ReusableMode.Run).on(guard)(ReusableMode.Done),
        FsmState(ReusableMode.Done).terminal()
      )
    )
    val encoding = FsmLocalEncoding.custom(
      4,
      enumEncoding(
        ReusableMode.Idle -> BigInt(3),
        ReusableMode.Run -> BigInt(7),
        ReusableMode.Done -> BigInt(9)
      )
    )
    graph.instantiate(FsmBindings(Vector(guard.bind(go))), encoding): instance =>
      result := instance.nextState.value

final class Increment58CrossSessionBindingsSource(
    previous: Option[FsmBindings[ReusableMode]] = None
) extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val go = in(Bool)
  val done = out(Bool)
  val root = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "root"
  )
  var exported: Option[FsmBindings[ReusableMode]] = None
  root:
    val count = Reg(0.U(8))
    val own = FsmBindings(
      Vector(ReusableTransfer.start.bind(go), ReusableTransfer.finish.bind(go)),
      Vector(
        ReusableTransfer.tick.bind { count := 1.U(8) },
        ReusableTransfer.entry.bind { count := 2.U(8) },
        ReusableTransfer.edge.bind { count := 3.U(8) }
      )
    )
    exported = Some(own)
    ReusableTransfer.graph.instantiate(previous.getOrElse(own)): instance =>
      done := instance.completed
