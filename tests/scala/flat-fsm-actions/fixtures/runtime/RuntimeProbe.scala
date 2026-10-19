package nodal.flatfsm.fixture
import nodal.*
enum Mode derives HwEnum:
  case Idle, Run, Done
final class CheckedActions extends Module:
  val flag = in(Bool)
  val input = in(UInt(8))
  val selector = in(EnumType[Mode]())
  val completed = out(Bool)
  val valid = out(Bool)
  val next = out(EnumType[Mode]())
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  var graphVisits = 0
  root {
    val target = Reg(0.U(8))
    val switchTarget = Reg(0.U(8))
    val frozen: Unit = fsm(FsmDefinition[Mode]("machine"), Mode.Idle): machine =>
      graphVisits += 1
      completed := machine.completed
      valid := machine.currentState.valid
      next := machine.nextState.value
      val encoding: FsmEncoding = machine.encoding
      assert(encoding == FsmEncoding.Compact)
      machine.state(Mode.Idle): state =>
        state.entry { target := 1.U(8) }
        state.active { target := input }
        state.exit { target := 2.U(8) }
        state.onWithAction(flag)(Mode.Run) { target := 3.U(8) }
      machine.state(Mode.Run): state =>
        val oldOn: Unit = state.on(flag)(Mode.Done)
        val _ = oldOn
      machine.state(Mode.Done)(_.terminal())
    val _ = frozen
    switchEnum(selector)(invalid = { switchTarget := 0.U(8) }): cases =>
      cases.is(Mode.Idle) { switchTarget := input }
      cases.is(Mode.Run) { when(flag) { switchTarget := 2.U(8) } }
      cases.is(Mode.Done) { () }
  }
final class RawAction(kind: Int) extends Module:
  val flag = in(Bool)
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  root {
    fsm(FsmDefinition[Mode]("raw"), Mode.Idle): machine =>
      machine.state(Mode.Idle): state =>
        if kind == 0 then Raw.action(state)
        else if kind == 1 then Raw.transition(state, flag, Mode.Run)
        else Raw.wrongProfile(state)
  }
final class RawSwitch extends Module:
  val selector = in(EnumType[Mode]())
  Raw.switch(selector)
final class RawCase extends Module:
  val selector = in(EnumType[Mode]())
  switchEnum(selector)(()) { cases => Raw.caseAction(cases, Mode.Idle) }
final class SwallowedAction extends Module:
  val flag = in(Bool)
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  root {
    fsm(FsmDefinition[Mode]("swallowed"), Mode.Idle): machine =>
      machine.state(Mode.Idle): state =>
        val _ = scala.util.Try(Raw.action(state))
        state.on(flag)(Mode.Run)
      machine.state(Mode.Run)(_.on(flag)(Mode.Done))
      machine.state(Mode.Done)(_.terminal())
  }
object RuntimeProbe:
  def main(arguments: Array[String]): Unit =
    val _ = arguments
    assert(FsmEncoding.values.toVector.map(_.ordinal) == Vector(0, 1, 2, 3, 4))
    assert(FsmEncoding.valueOf("Auto") == FsmEncoding.Auto)
    val snapshot = ConstructionKernel.inspect(new CheckedActions)
    assert(snapshot.flatFsms.size == 1)
    val machine = snapshot.flatFsms.head
    assert(machine.states.size == 3 && machine.transitions.size == 2)
    assert(machine.actions.map(_.phase) == Vector("entry", "active", "exit", "transition"))
    assert(snapshot.enumSwitches.size == 1)
    assert(snapshot.enumSwitches.head.caseIds.size == 3)
    assert(snapshot.behavioralIfs.nonEmpty)
    assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 3)
    List(
      () => new RawAction(0),
      () => new RawAction(1),
      () => new RawAction(2),
      () => new RawSwitch,
      () => new RawCase
    ).foreach: create =>
      val failure = scala.util.Try(ConstructionKernel.inspect(create())).failed.get
      assert(
        failure.asInstanceOf[ConstructionException].diagnostic.code ==
          "NODAL-SEQUENTIAL-056-MISSING",
        failure.toString
      )
    val poisoned = scala.util.Try(ConstructionKernel.inspect(new SwallowedAction)).failed.get
    assert(
      poisoned.asInstanceOf[ConstructionException].diagnostic.code ==
        "NODAL-CONSTRUCTOR-LIFECYCLE-021",
      poisoned.toString
    )
    assert(Raw.calls == 0)
    println("FLAT_FSM_ACTION_RUNTIME_PASS checked-actions graph-wiring no-plugin-zero-effects")
