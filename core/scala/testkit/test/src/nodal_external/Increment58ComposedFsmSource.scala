package nodal_external

import nodal.*

enum ComposedFsmMode derives HwEnum:
  case Work, Done

object ComposedFsmGraphs:
  val leafOwner = FsmDefinition[ComposedFsmMode]("worker")
  val finish = FsmGuard(leafOwner, "finish")
  val enter = FsmAction(leafOwner, "enter")
  val leaf = FsmDef(leafOwner, ComposedFsmMode.Work)(FsmFragment("worker")(
    FsmState(ComposedFsmMode.Work).entry(enter).on(finish)(ComposedFsmMode.Done),
    FsmState(ComposedFsmMode.Done).terminal()
  ))
  val parentOwner = FsmDefinition[ComposedFsmMode]("parallel-workers")
  val first = FsmChild(parentOwner, "first", leaf)
  val second = FsmChild(parentOwner, "second", leaf)
  private val work = FsmState(ComposedFsmMode.Work).children(first, second)
  val all = FsmDef(parentOwner, ComposedFsmMode.Work)(FsmFragment("parallel")(
    work.onAllComplete()(ComposedFsmMode.Done),
    FsmState(ComposedFsmMode.Done).terminal()
  ))
  val any = FsmDef(parentOwner, ComposedFsmMode.Work)(FsmFragment("parallel")(
    work.onAnyComplete()(ComposedFsmMode.Done),
    FsmState(ComposedFsmMode.Done).terminal()
  ))
  val priority = FsmDef(parentOwner, ComposedFsmMode.Work)(FsmFragment("parallel")(
    work.onChildComplete(first)(ComposedFsmMode.Done).onChildComplete(second)(ComposedFsmMode.Done),
    FsmState(ComposedFsmMode.Done).terminal()
  ))
  val exclusive = FsmDef(parentOwner, ComposedFsmMode.Work)(FsmFragment("parallel")(
    work.onChildComplete(first, TransitionMode.Exclusive)(ComposedFsmMode.Done)
      .onChildComplete(second, TransitionMode.Exclusive)(ComposedFsmMode.Done),
    FsmState(ComposedFsmMode.Done).terminal()
  ))
  val outerOwner = FsmDefinition[ComposedFsmMode]("nested-workers")
  val workers = FsmChild(outerOwner, "workers", all)
  val nested = FsmDef(outerOwner, ComposedFsmMode.Work)(FsmFragment("outer")(
    FsmState(ComposedFsmMode.Work).children(workers).onAllComplete()(ComposedFsmMode.Done),
    FsmState(ComposedFsmMode.Done).terminal()
  ))

final class Increment58ComposedFsmSource(profile: String = "all") extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val domain = ClockDomain.from(
    clock,
    reset,
    edge = ClockEdge.Rising,
    policy = ResetPolicy.Sync,
    polarity = ResetPolarity.ActiveHigh,
    frequency = 100.MHz,
    name = "root"
  )
  domain:
    val restart = in(Bool)
    val cancel = in(Bool)
    val enable = in(Bool)
    val finishFirst = in(Bool)
    val finishSecond = in(Bool)
    val status = out(EnumType[FsmStatus]())
    val state = out(EnumType[ComposedFsmMode]())
    val firstStatus = out(EnumType[FsmStatus]())
    val secondStatus = out(EnumType[FsmStatus]())
    val firstActive = out(Bool)
    val secondActive = out(Bool)
    val firstValue = out(UInt(8))
    val secondValue = out(UInt(8))
    val firstStored = Reg(0.U(8))
    val secondStored = Reg(0.U(8))
    val bindings = FsmBindings(
      Vector.empty[FsmGuardBinding[ComposedFsmMode]],
      Vector.empty[FsmActionBinding[ComposedFsmMode]],
      Vector(
        ComposedFsmGraphs.first.bind(
          FsmBindings(
            Vector(ComposedFsmGraphs.finish.bind(finishFirst)),
            Vector(ComposedFsmGraphs.enter.bind { firstStored := 11.U(8) })
          ),
          illegalState = IllegalStatePolicy.RecoverToInitial
        ),
        ComposedFsmGraphs.second.bind(
          FsmBindings(
            Vector(ComposedFsmGraphs.finish.bind(finishSecond)),
            Vector(ComposedFsmGraphs.enter.bind { secondStored := 22.U(8) })
          ),
          illegalState = IllegalStatePolicy.RecoverToInitial
        )
      )
    )
    val graph = profile match
      case "nested" => ComposedFsmGraphs.nested
      case "any" => ComposedFsmGraphs.any
      case "priority" => ComposedFsmGraphs.priority
      case "exclusive" => ComposedFsmGraphs.exclusive
      case _ => ComposedFsmGraphs.all
    val actualBindings = if profile == "nested" then
      FsmBindings(
        Vector.empty[FsmGuardBinding[ComposedFsmMode]],
        Vector.empty[FsmActionBinding[ComposedFsmMode]],
        Vector(ComposedFsmGraphs.workers.bind(
          bindings,
          illegalState = IllegalStatePolicy.RecoverToInitial
        ))
      )
    else bindings
    var escaped: Option[FsmChildRun[ComposedFsmMode]] = None
    graph.instantiateControlled(
      actualBindings,
      FsmControl(restart, cancel, enable),
      illegalState = IllegalStatePolicy.RecoverToInitial
    ): run =>
      status := run.status.value
      state := run.state.currentState.value
      val workers = if profile == "nested" then run.child(ComposedFsmGraphs.workers).run else run
      val first = workers.child(ComposedFsmGraphs.first)
      val second = workers.child(ComposedFsmGraphs.second)
      escaped = Some(first)
      firstStatus := first.run.status.value
      secondStatus := second.run.status.value
      firstActive := first.active
      secondActive := second.active
    if profile == "escape" then firstActive := escaped.get.active
    firstValue := firstStored
    secondValue := secondStored
