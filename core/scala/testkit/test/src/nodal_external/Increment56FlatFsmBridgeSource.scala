package nodal_external
import nodal.*
enum FlatBridgeMode derives HwEnum:
  case Idle, Run, Done
final class Increment56FlatFsmBridgeSource extends Module:
  val flag = in(Bool)
  val input = in(UInt(8))
  val selector = in(EnumType[FlatBridgeMode]())
  val completed = out(Bool)
  val valid = out(Bool)
  val next = out(EnumType[FlatBridgeMode]())
  val clock = in(Clock)
  val reset = in(Reset)
  val root = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "root"
  )
  var graphVisits = 0
  root {
    val target = Reg(0.U(8))
    val switchTarget = Reg(0.U(8))
    val frozen: Unit =
      fsm(FsmDefinition[FlatBridgeMode]("machine"), FlatBridgeMode.Idle): machine =>
        graphVisits += 1
        completed := machine.completed
        valid := machine.currentState.valid
        next := machine.nextState.value
        val encoding: FsmEncoding = machine.encoding
        assert(encoding == FsmEncoding.Compact)
        machine.state(FlatBridgeMode.Idle): state =>
          state.entry { target := 1.U(8) }
          state.active { target := input }
          state.exit { target := 2.U(8) }
          state.onWithAction(flag)(FlatBridgeMode.Run) { target := 3.U(8) }
        machine.state(FlatBridgeMode.Run): state =>
          val oldOn: Unit = state.on(flag)(FlatBridgeMode.Done)
          val _ = oldOn
        machine.state(FlatBridgeMode.Done)(_.terminal())
    val _ = frozen
    switchEnum(selector)(invalid = { switchTarget := 0.U(8) }): cases =>
      cases.is(FlatBridgeMode.Idle) { switchTarget := input }
      cases.is(FlatBridgeMode.Run) { when(flag) { switchTarget := 2.U(8) } }
      cases.is(FlatBridgeMode.Done) { () }
  }

/** Minimal public source accepted by the fixed-width CIRCT adapter. */
final class Increment56EnabledRegisterSource extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val enable = in(Bool)
  val input = in(UInt(8))
  val output = out(UInt(8))
  val core = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "core"
  )
  core:
    val stored = Reg(0.U(8))
    when(enable):
      stored := input
    output := stored

final class Increment56FlatEncodingSource(encoding: FsmEncoding, custom: Boolean = false)
    extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val enable = in(Bool)
  val next = out(EnumType[FlatBridgeMode]())
  val complete = out(Bool)
  val root = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveLow,
    100.MHz,
    "root"
  )
  root:
    val definition = FsmDefinition[FlatBridgeMode]("machine")
    val graph: FsmBuilder[FlatBridgeMode] => Unit = machine =>
      next := machine.nextState.value
      complete := machine.completed
      machine.state(FlatBridgeMode.Idle)(_.on(enable)(FlatBridgeMode.Run))
      machine.state(FlatBridgeMode.Run)(_.on(enable)(FlatBridgeMode.Done))
      machine.state(FlatBridgeMode.Done)(_.terminal())
    if custom then
      fsm(
        definition,
        FlatBridgeMode.Idle,
        FsmLocalEncoding.custom(
          4,
          enumEncoding(
            FlatBridgeMode.Idle -> BigInt(3),
            FlatBridgeMode.Run -> BigInt(7),
            FlatBridgeMode.Done -> BigInt(9)
          )
        ),
        IllegalStatePolicy.RecoverToInitial
      )(graph)
    else fsm(definition, FlatBridgeMode.Idle, encoding)(graph)

final class Increment56NestedFsmSource extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val enable = in(Bool)
  val selector = in(EnumType[FlatBridgeMode]())
  val input = in(UInt(8))
  val MODE = param(0.integer)
  val output = out(UInt(8))
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
    val state = Reg(0.U(8))
    fsm(FsmDefinition[FlatBridgeMode]("nested"), FlatBridgeMode.Idle): machine =>
      machine.state(FlatBridgeMode.Idle): current =>
        current.active:
          when(MODE == 0):
            state := input
          when(machine.transitionTaken):
            switchEnum(machine.nextState.value)(invalid = { state := 0.U(8) }): cases =>
              cases.is(FlatBridgeMode.Idle) { state := 1.U(8) }
              cases.is(FlatBridgeMode.Run) { state := 2.U(8) }
              cases.is(FlatBridgeMode.Done) { () }
        current.on(enable)(FlatBridgeMode.Run)
      machine.state(FlatBridgeMode.Run)(_.on(enable)(FlatBridgeMode.Done))
      machine.state(FlatBridgeMode.Done)(_.terminal())
    output := state

final class Increment56RepeatedFsmSource extends Module:
  val left = instance(new Increment56FlatEncodingSource(FsmEncoding.Compact))
  val right = instance(new Increment56FlatEncodingSource(FsmEncoding.Compact))

final class Increment56ScalarSequentialSource extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val enable = in(Bool)
  val integerInput = in(Integer)
  val realInput = in(Real)
  val selector = in(EnumType[FlatBridgeMode]())
  val integerOutput = out(Integer)
  val realOutput = out(Real)
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
    val integerState = Reg(0.integer)
    val realState = Reg(0.0.real)
    when(enable):
      integerState := integerInput
      realState := realInput
    val fsmInteger = Reg(0.integer)
    val fsmReal = Reg(0.0.real)
    fsm(FsmDefinition[FlatBridgeMode]("scalar"), FlatBridgeMode.Idle): machine =>
      machine.state(FlatBridgeMode.Idle): current =>
        current.active:
          fsmInteger := integerInput
          fsmReal := realInput
        current.on(enable)(FlatBridgeMode.Run)
      machine.state(FlatBridgeMode.Run)(_.on(enable)(FlatBridgeMode.Done))
      machine.state(FlatBridgeMode.Done)(_.terminal())
    val switchInteger = Reg(0.integer)
    val switchReal = Reg(0.0.real)
    switchEnum(selector)(invalid = { () }): cases =>
      cases.is(FlatBridgeMode.Idle):
        switchInteger := integerInput
        switchReal := realInput
      cases.is(FlatBridgeMode.Run) { () }
      cases.is(FlatBridgeMode.Done) { () }
    integerOutput := fsmInteger
    realOutput := fsmReal

enum FlatTargetMode derives HwEnum:
  case Idle, Run

/** Fixed-width public target witness uses canonical enum identity without enum boundary ports. */
final class Increment56FlatFsmTargetSource(policy: IllegalStatePolicy = IllegalStatePolicy.Error)
    extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val enable = in(Bool)
  val output = out(UInt(8))
  val core = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "core"
  )
  core:
    val state = Reg(0.U(8))
    fsm(FsmDefinition[FlatTargetMode]("target"), FlatTargetMode.Idle, FsmEncoding.Compact, policy):
      machine =>
        machine.state(FlatTargetMode.Idle): current =>
          current.active { state := 1.U(8) }
          current.onWithAction(enable)(FlatTargetMode.Run) { state := 3.U(8) }
        machine.state(FlatTargetMode.Run): current =>
          current.entry { state := 2.U(8) }
          current.on(enable)(FlatTargetMode.Run)
    output := state

enum FlatManyMode derives HwEnum:
  case S00, S01, S02, S03, S04, S05, S06, S07, S08, S09, S10, S11, S12, S13, S14, S15, S16, S17,
    S18, S19, S20, S21, S22, S23, S24, S25, S26, S27, S28, S29, S30, S31

final class Increment56ManyStateSource extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val enable = in(Bool)
  val completed = out(Bool)
  val next = out(EnumType[FlatManyMode]())
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
    fsm(FsmDefinition[FlatManyMode]("many"), FlatManyMode.S00, FsmEncoding.Gray): machine =>
      completed := machine.completed
      next := machine.nextState.value
      machine.state(FlatManyMode.S00)(_.on(enable)(FlatManyMode.S01))
      machine.state(FlatManyMode.S01)(_.on(enable)(FlatManyMode.S02))
      machine.state(FlatManyMode.S02)(_.on(enable)(FlatManyMode.S03))
      machine.state(FlatManyMode.S03)(_.on(enable)(FlatManyMode.S04))
      machine.state(FlatManyMode.S04)(_.on(enable)(FlatManyMode.S05))
      machine.state(FlatManyMode.S05)(_.on(enable)(FlatManyMode.S06))
      machine.state(FlatManyMode.S06)(_.on(enable)(FlatManyMode.S07))
      machine.state(FlatManyMode.S07)(_.on(enable)(FlatManyMode.S08))
      machine.state(FlatManyMode.S08)(_.on(enable)(FlatManyMode.S09))
      machine.state(FlatManyMode.S09)(_.on(enable)(FlatManyMode.S10))
      machine.state(FlatManyMode.S10)(_.on(enable)(FlatManyMode.S11))
      machine.state(FlatManyMode.S11)(_.on(enable)(FlatManyMode.S12))
      machine.state(FlatManyMode.S12)(_.on(enable)(FlatManyMode.S13))
      machine.state(FlatManyMode.S13)(_.on(enable)(FlatManyMode.S14))
      machine.state(FlatManyMode.S14)(_.on(enable)(FlatManyMode.S15))
      machine.state(FlatManyMode.S15)(_.on(enable)(FlatManyMode.S16))
      machine.state(FlatManyMode.S16)(_.on(enable)(FlatManyMode.S17))
      machine.state(FlatManyMode.S17)(_.on(enable)(FlatManyMode.S18))
      machine.state(FlatManyMode.S18)(_.on(enable)(FlatManyMode.S19))
      machine.state(FlatManyMode.S19)(_.on(enable)(FlatManyMode.S20))
      machine.state(FlatManyMode.S20)(_.on(enable)(FlatManyMode.S21))
      machine.state(FlatManyMode.S21)(_.on(enable)(FlatManyMode.S22))
      machine.state(FlatManyMode.S22)(_.on(enable)(FlatManyMode.S23))
      machine.state(FlatManyMode.S23)(_.on(enable)(FlatManyMode.S24))
      machine.state(FlatManyMode.S24)(_.on(enable)(FlatManyMode.S25))
      machine.state(FlatManyMode.S25)(_.on(enable)(FlatManyMode.S26))
      machine.state(FlatManyMode.S26)(_.on(enable)(FlatManyMode.S27))
      machine.state(FlatManyMode.S27)(_.on(enable)(FlatManyMode.S28))
      machine.state(FlatManyMode.S28)(_.on(enable)(FlatManyMode.S29))
      machine.state(FlatManyMode.S29)(_.on(enable)(FlatManyMode.S30))
      machine.state(FlatManyMode.S30)(_.on(enable)(FlatManyMode.S31))
      machine.state(FlatManyMode.S31)(_.terminal())

final class Increment56QuotedLeaf(reason: String) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Bits(8))
  val output = out(Bits(8))
  output := input.keep(reason)

final class Increment56QuotedDefinitionsSource extends Module:
  val left = instance(new Increment56QuotedLeaf("retain %digital_value_0, ({ metadata"))
  val right = instance(new Increment56QuotedLeaf("retain %digital_value_1, ({ metadata"))
