package nodal_external

import nodal.*

enum StackFsmMode derives HwEnum:
  case Main, Sub, Resume, Fault, Done

object StackFsmGraphs:
  val owner = FsmDefinition[StackFsmMode]("procedure")
  val call = FsmGuard(owner, "call")
  val recurse = FsmGuard(owner, "recurse")
  val ret = FsmGuard(owner, "return")
  val emptyReturn = FsmGuard(owner, "empty-return")
  val active = FsmAction(owner, "active")
  val entry = FsmAction(owner, "entry")
  val exit = FsmAction(owner, "exit")
  def graph(capacity: Int = 2, hold: Boolean = false): FsmDef[StackFsmMode] =
    val overflow =
      if hold then FsmStackPolicy.AssertHold else FsmStackPolicy.Trap(StackFsmMode.Fault)
    val underflow =
      if hold then FsmStackPolicy.AssertHold else FsmStackPolicy.Trap(StackFsmMode.Done)
    // A recovery entry is explicit even in AssertHold capacity tests, keeping Fault reachable.
    FsmDef.withCallStack(
      owner,
      StackFsmMode.Main,
      capacity,
      overflow,
      underflow,
      FsmStackPolicy.Trap(StackFsmMode.Fault)
    )(FsmFragment("states")(
      FsmState(StackFsmMode.Main).active(active).exit(exit)
        .returnOn(emptyReturn, TransitionMode.Priority)
        .call(call, TransitionMode.Priority)(StackFsmMode.Sub, StackFsmMode.Resume),
      FsmState(StackFsmMode.Sub).active(active).entry(entry).exit(exit)
        .call(recurse, TransitionMode.Priority)(StackFsmMode.Sub, StackFsmMode.Sub)
        .returnOn(ret, TransitionMode.Priority),
      FsmState(StackFsmMode.Resume).after(1)(StackFsmMode.Done),
      FsmState(
        StackFsmMode.Fault
      ).active(active).entry(entry).exit(exit).after(2)(StackFsmMode.Done),
      FsmState(StackFsmMode.Done).terminal()
    ))

final class Increment58StackFsmSource(profile: String = "trap") extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val restart = in(Bool)
  val cancel = in(Bool)
  val enable = in(Bool)
  val call = in(Bool)
  val recurse = in(Bool)
  val ret = in(Bool)
  val emptyReturn = in(Bool)
  val input = in(UInt(8))
  val state = out(EnumType[StackFsmMode]())
  val status = out(EnumType[FsmStatus]())
  val taken = out(Bool)
  val enteringFault = out(Bool)
  val exitingSub = out(Bool)
  val value = out(UInt(8))
  val domain = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    if profile == "resetless" then ResetPolicy.None else ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "root"
  )
  domain:
    val stored = if profile == "resetless" then Reg.uninitialized(UInt(8)) else Reg(0.U(8))
    val graph = StackFsmGraphs.graph(
      if profile == "one" then 1 else if profile == "maximum" then 128 else 2,
      profile == "hold"
    )
    val bindings = FsmBindings(
      Vector(
        StackFsmGraphs.call.bind(call),
        StackFsmGraphs.recurse.bind(recurse),
        StackFsmGraphs.ret.bind(ret),
        StackFsmGraphs.emptyReturn.bind(emptyReturn)
      ),
      Vector(
        StackFsmGraphs.active.bind { stored := input },
        StackFsmGraphs.entry.bind { stored := 7.U(8) },
        StackFsmGraphs.exit.bind { stored := 3.U(8) }
      )
    )
    graph.instantiateControlled(
      bindings,
      FsmControl(restart, cancel, enable),
      if profile == "one-hot" then FsmEncoding.OneHot else FsmEncoding.Compact,
      IllegalStatePolicy.RecoverToInitial
    ): run =>
      state := run.state.currentState.value
      status := run.status.value
      taken := run.state.transitionTaken
      enteringFault := run.state.entering(StackFsmMode.Fault)
      exitingSub := run.state.exiting(StackFsmMode.Sub)
    value := stored

final class Increment58RequiredStackFsmLeaf extends Module:
  val required = ClockDomain.required("required")
  val restart = in(Bool)
  val cancel = in(Bool)
  val enable = in(Bool)
  val call = in(Bool)
  val ret = in(Bool)
  val state = out(EnumType[StackFsmMode]())
  val status = out(EnumType[FsmStatus]())
  required:
    val graph = StackFsmGraphs.graph()
    val value = Reg.uninitialized(UInt(8))
    val bindings = FsmBindings(
      Vector(
        StackFsmGraphs.call.bind(call),
        StackFsmGraphs.recurse.bind(false.B),
        StackFsmGraphs.ret.bind(ret),
        StackFsmGraphs.emptyReturn.bind(false.B)
      ),
      Vector(
        StackFsmGraphs.active.bind { value := 0.U(8) },
        StackFsmGraphs.entry.bind { value := 7.U(8) },
        StackFsmGraphs.exit.bind { value := 3.U(8) }
      )
    )
    graph.instantiateControlled(
      bindings,
      FsmControl(restart, cancel, enable),
      illegalState = IllegalStatePolicy.RecoverToInitial
    ): run =>
      state := run.state.currentState.value
      status := run.status.value

final class Increment58RequiredStackFsmSource extends Module:
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
    val callA = in(Bool)
    val retA = in(Bool)
    val stateA = out(EnumType[StackFsmMode]())
    val statusA = out(EnumType[FsmStatus]())
    val childA = instance(new Increment58RequiredStackFsmLeaf).domain(first)
    childA(_.restart) := restartA
    childA(_.cancel) := cancelA
    childA(_.enable) := enableA
    childA(_.call) := callA
    childA(_.ret) := retA
    stateA := childA(_.state)
    statusA := childA(_.status)
  second:
    val restartB = in(Bool)
    val cancelB = in(Bool)
    val enableB = in(Bool)
    val callB = in(Bool)
    val retB = in(Bool)
    val stateB = out(EnumType[StackFsmMode]())
    val statusB = out(EnumType[FsmStatus]())
    val childB = instance(new Increment58RequiredStackFsmLeaf).domain(second)
    childB(_.restart) := restartB
    childB(_.cancel) := cancelB
    childB(_.enable) := enableB
    childB(_.call) := callB
    childB(_.ret) := retB
    stateB := childB(_.state)
    statusB := childB(_.status)

enum StackParentMode derives HwEnum:
  case Work, Done
object StackParentGraph:
  val owner = FsmDefinition[StackParentMode]("stack-parent")
  val child = FsmChild(owner, "procedure", StackFsmGraphs.graph())
  val graph = FsmDef(owner, StackParentMode.Work)(FsmFragment("states")(
    FsmState(StackParentMode.Work).children(child).onAllComplete()(StackParentMode.Done),
    FsmState(StackParentMode.Done).terminal()
  ))

final class Increment58ComposedStackFsmSource extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val restart = in(Bool)
  val cancel = in(Bool)
  val enable = in(Bool)
  val call = in(Bool)
  val ret = in(Bool)
  val status = out(EnumType[FsmStatus]())
  val childState = out(EnumType[StackFsmMode]())
  val childStatus = out(EnumType[FsmStatus]())
  val childActive = out(Bool)
  val domain = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "root"
  )
  domain:
    val stored = Reg(0.U(8))
    val childBindings = FsmBindings(
      Vector(
        StackFsmGraphs.call.bind(call),
        StackFsmGraphs.recurse.bind(false.B),
        StackFsmGraphs.ret.bind(ret),
        StackFsmGraphs.emptyReturn.bind(false.B)
      ),
      Vector(
        StackFsmGraphs.active.bind { stored := 0.U(8) },
        StackFsmGraphs.entry.bind { stored := 7.U(8) },
        StackFsmGraphs.exit.bind { stored := 3.U(8) }
      )
    )
    val bindings = FsmBindings(
      Vector.empty[FsmGuardBinding[StackParentMode]],
      Vector.empty[FsmActionBinding[StackParentMode]],
      Vector(StackParentGraph.child.bind(
        childBindings,
        illegalState = IllegalStatePolicy.RecoverToInitial
      ))
    )
    StackParentGraph.graph.instantiateControlled(
      bindings,
      FsmControl(restart, cancel, enable),
      illegalState = IllegalStatePolicy.RecoverToInitial
    ): run =>
      status := run.status.value
      val child = run.child(StackParentGraph.child)
      childState := child.run.state.currentState.value
      childStatus := child.run.status.value
      childActive := child.active
