package nodal_external
import nodal.*
enum UnrolledFsmMode derives HwEnum:
  case Work, Done
object UnrolledFsmGraphs:
  def leaf: FsmDef[UnrolledFsmMode] =
    val owner = FsmDefinition[UnrolledFsmMode]("leaf")
    FsmDef(owner, UnrolledFsmMode.Work)(FsmFragment("states")(
      FsmState(UnrolledFsmMode.Work).after(2)(UnrolledFsmMode.Done),
      FsmState(UnrolledFsmMode.Done).terminal()
    ))
  def wrap(level: Int, previous: FsmDef[UnrolledFsmMode]): FsmDef[UnrolledFsmMode] =
    val _ = level
    val owner = FsmDefinition[UnrolledFsmMode]("level")
    val child = FsmChild(owner, "child", previous)
    FsmDef(owner, UnrolledFsmMode.Work)(FsmFragment("states")(
      FsmState(UnrolledFsmMode.Work).children(child).onAllComplete()(UnrolledFsmMode.Done),
      FsmState(UnrolledFsmMode.Done).terminal()
    ))
  def graph: FsmDef[UnrolledFsmMode] = FsmDef.unroll(2, leaf)(wrap)
  def bindings[S](definition: FsmDef[S]): FsmBindings[S] =
    FsmBindings(
      Vector.empty[FsmGuardBinding[S]],
      Vector.empty[FsmActionBinding[S]],
      definition.childSlots.map(child =>
        child.bind(
          bindings(child.definition),
          illegalState = IllegalStatePolicy.RecoverToInitial
        )
      )
    )
final class Increment58UnrolledFsmSource extends Module:
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
    val complete = out(Bool)
    val graph = UnrolledFsmGraphs.graph
    graph.instantiateControlled(
      UnrolledFsmGraphs.bindings(graph),
      FsmControl(restart, cancel, enable),
      illegalState = IllegalStatePolicy.RecoverToInitial
    ): run =>
      status := run.status.value
      complete := run.completed
object UnrolledTransferGraphs:
  def step(level: Int, previous: FsmDef[UnrolledFsmMode]): FsmDef[UnrolledFsmMode] =
    val _ = level
    val _ = previous
    val owner = FsmDefinition[UnrolledFsmMode]("transfer")
    val transfer = FsmTransfer(owner, "transfer")
    FsmDef(owner, UnrolledFsmMode.Work)(FsmFragment("states")(
      FsmState(UnrolledFsmMode.Work).awaitTransfer(transfer)(UnrolledFsmMode.Done),
      FsmState(UnrolledFsmMode.Done).terminal()
    ))
final class Increment58UnrolledTransferFsmSource extends Module:
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
    val inputData = in(UInt(8))
    val inputValid = in(Bool)
    val inputReady = in(Bool)
    val status = out(EnumType[FsmStatus]())
    val stream = Stream(inputData, inputValid)
    stream.accept(inputReady)
    val graph = FsmDef.unroll(1, UnrolledFsmGraphs.leaf)(UnrolledTransferGraphs.step)
    val bindings = FsmBindings(graph.transferSlots.map(_.bind(stream)))
    graph.instantiateControlled(
      bindings,
      FsmControl(restart, cancel, enable),
      illegalState = IllegalStatePolicy.RecoverToInitial
    ): run =>
      status := run.status.value
