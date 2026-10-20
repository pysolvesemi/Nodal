package nodal_external

import nodal.*

object ComposedTimedFsmGraphs:
  val owner = FsmDefinition[TimedFsmMode]("timed-parallel")
  val first = FsmChild(owner, "first", TimedFsmGraphs.timer(2))
  val second = FsmChild(owner, "second", TimedFsmGraphs.timer(3))
  val graph = FsmDef(owner, TimedFsmMode.Wait)(FsmFragment("join")(
    FsmState(TimedFsmMode.Wait).children(first, second).onAllComplete()(TimedFsmMode.Done),
    FsmState(TimedFsmMode.Done).terminal()
  ))

final class Increment58ComposedTimedFsmSource extends Module:
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
    val status = out(EnumType[FsmStatus]())
    val firstStatus = out(EnumType[FsmStatus]())
    val secondStatus = out(EnumType[FsmStatus]())
    val firstActive = out(Bool)
    val secondActive = out(Bool)
    val bindings = FsmBindings(
      Vector.empty[FsmGuardBinding[TimedFsmMode]],
      Vector.empty[FsmActionBinding[TimedFsmMode]],
      Vector(
        ComposedTimedFsmGraphs.first.bind(
          FsmBindings[TimedFsmMode](Vector.empty),
          illegalState = IllegalStatePolicy.RecoverToInitial
        ),
        ComposedTimedFsmGraphs.second.bind(
          FsmBindings[TimedFsmMode](Vector.empty),
          illegalState = IllegalStatePolicy.RecoverToInitial
        )
      )
    )
    ComposedTimedFsmGraphs.graph.instantiateControlled(
      bindings,
      FsmControl(restart, cancel, enable),
      illegalState = IllegalStatePolicy.RecoverToInitial
    ): run =>
      status := run.status.value
      val first = run.child(ComposedTimedFsmGraphs.first)
      val second = run.child(ComposedTimedFsmGraphs.second)
      firstStatus := first.run.status.value
      secondStatus := second.run.status.value
      firstActive := first.active
      secondActive := second.active
