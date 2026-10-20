package nodal.internal.testkit

import nodal.*
import utest.*

/** Raw graph tests use an explicitly reflective adapter; public frontend attestation is tested
  * separately.
  */
private object FlatFsmKernelFixture:
  private val runtimeClass = Class.forName("nodal.FlatFsmRuntime$")
  private val runtime = runtimeClass.getField("MODULE$").get(())
  private val callbacksClass = Class.forName("nodal.DigitalCollectionCallback$")
  private val callbacks = callbacksClass.getField("MODULE$").get(())
  private val checked = callbacksClass.getMethod("checkedSequential", classOf[Function0[?]])
  private def invoke(method: java.lang.reflect.Method, owner: AnyRef, arguments: AnyRef*): AnyRef =
    try method.invoke(owner, arguments*)
    catch
      case failure: java.lang.reflect.InvocationTargetException =>
        scala.util.Failure[Nothing](Option(failure.getCause).getOrElse(failure)).get
  private def carrier(body: () => Unit, attested: Boolean): AnyRef =
    if attested then invoke(checked, callbacks, body) else body
  def action[S](
      state: StateBuilder[S],
      phase: String,
      body: () => Unit,
      attested: Boolean = true
  ): Unit =
    val method = runtimeClass.getMethod(
      "action",
      classOf[StateBuilder[?]],
      classOf[String],
      classOf[Function0[?]]
    )
    val _ = invoke(method, runtime, state, phase, carrier(body, attested))
  def transition[S](
      state: StateBuilder[S],
      guard: Expr[Bool],
      mode: TransitionMode,
      to: S
  )(body: () => Unit): Unit =
    val method = runtimeClass.getMethod(
      "transitionAction",
      classOf[StateBuilder[?]],
      classOf[Expr[?]],
      classOf[TransitionMode],
      classOf[Object],
      classOf[Function0[?]]
    )
    val _ =
      invoke(method, runtime, state, guard, mode, to.asInstanceOf[AnyRef], carrier(body, true))
  def switch[S](
      selector: Expr[EnumValue[S]],
      invalid: () => Unit
  )(body: EnumSwitchBuilder[S] => Unit)(using evidence: HwEnum[S]): Unit =
    val method = runtimeClass.getMethod(
      "switchEnum",
      classOf[Expr[?]],
      classOf[Function0[?]],
      classOf[Function1[?, ?]],
      classOf[HwEnum[?]]
    )
    val _ = invoke(method, runtime, selector, carrier(invalid, true), body, evidence)
  def is[S](builder: EnumSwitchBuilder[S], value: S)(body: () => Unit): Unit =
    val method = runtimeClass.getMethod(
      "switchCase",
      classOf[EnumSwitchBuilder[?]],
      classOf[Object],
      classOf[Function0[?]]
    )
    val _ = invoke(method, runtime, builder, value.asInstanceOf[AnyRef], carrier(body, true))
  def staticCompare(left: Expr[Integer], right: Expr[Integer]): Expr[Bool] =
    val moduleClass = Class.forName("nodal.SequentialRuntime$")
    val module = moduleClass.getField("MODULE$").get(())
    val method = moduleClass.getMethod(
      "staticCompare",
      classOf[Expr[?]],
      classOf[Expr[?]],
      classOf[String]
    )
    invoke(method, module, left, right, "gt").asInstanceOf[Expr[Bool]]
  def branch(guard: Expr[Bool])(body: () => Unit): Unit =
    val moduleClass = Class.forName("nodal.ConstructionKernel$")
    val module = moduleClass.getField("MODULE$").get(())
    val method = moduleClass.getMethod(
      "behavioralBranch",
      classOf[String],
      classOf[Option[?]],
      classOf[Function0[?]]
    )
    val _ = invoke(method, module, "when", Some(guard), carrier(body, true))

enum FlatFsmMode derives HwEnum:
  case Idle, Run, Done

final class FlatFsmCaptureSource(
    mode: String,
    encoding: FsmEncoding = FsmEncoding.Compact,
    effect: () => Unit = () => ()
) extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val core = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    if mode == "resetless" then ResetPolicy.None else ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enable = in(Bool)
  val second = in(Bool)
  val input = in(UInt(8))
  val output = out(Bool)
  val stateOutput = out(EnumType[FlatFsmMode]())
  var escaped: Option[FsmBuilder[FlatFsmMode]] = None
  var stateEscape: Option[StateBuilder[FlatFsmMode]] = None
  var observation: Expr[Bool] = enable
  core:
    val count = if mode == "resetless" then Reg.uninitialized(UInt(8)) else Reg(0.U(8))
    def graph(machine: FsmBuilder[FlatFsmMode]): Unit =
      escaped = Some(machine)
      observation = machine.completed
      output := machine.completed
      stateOutput := machine.currentState.value
      machine.state(FlatFsmMode.Idle): state =>
        stateEscape = Some(state)
        if mode == "terminal-outgoing" then state.terminal()
        if mode != "dead-end" then
          val guard = if mode == "next-feedback" then machine.entering(FlatFsmMode.Run) else enable
          if mode == "actions" || mode == "nested" || mode == "unattested" || mode == "structure" ||
            mode == "nonregister" || mode == "unrelated"
          then
            FlatFsmKernelFixture.action(
              state,
              "active",
              () => {
                effect()
                if mode == "structure" then { val _ = Reg(0.U(8)) }
                else if mode == "nonregister" then output := enable
                else if mode == "nested" then
                  FlatFsmKernelFixture.branch(enable)(() => count := input)
                else count := input
                if mode == "actions" then count := select(machine.transitionTaken, input, count)
              },
              mode != "unattested"
            )
            FlatFsmKernelFixture.action(state, "exit", () => count := 2.U(8))
            FlatFsmKernelFixture.transition(
              state,
              guard,
              TransitionMode.Exclusive,
              FlatFsmMode.Run
            )(() => count := 3.U(8))
          else state.on(guard)(FlatFsmMode.Run)
          if mode == "overlap" then state.on(second)(FlatFsmMode.Done)
          if mode == "disjoint" then state.on(!enable)(FlatFsmMode.Done)
          if mode == "mixed-mode" then state.on(second, TransitionMode.Priority)(FlatFsmMode.Done)
          if mode == "gray-bad" then state.on(!enable)(FlatFsmMode.Done)
      if mode == "state-escape" then stateEscape.get.terminal()
      if mode == "duplicate" then machine.state(FlatFsmMode.Idle)(_.terminal())
      if mode != "missing" then
        machine.state(FlatFsmMode.Run): state =>
          if mode == "actions" then
            FlatFsmKernelFixture.action(state, "entry", () => count := 4.U(8))
          val nextGuard = if mode == "unreachable" then false.B else enable
          val nextState = if mode == "unreachable" then FlatFsmMode.Run else FlatFsmMode.Done
          state.on(nextGuard)(nextState)
      machine.state(FlatFsmMode.Done)(_.terminal())
      if mode == "hierarchy" then machine.boundedCallStack(2)
    val definition = FsmDefinition[FlatFsmMode]("machine")
    if mode == "custom" then
      val local = FsmLocalEncoding.custom(
        4,
        enumEncoding(
          FlatFsmMode.Idle -> BigInt(3),
          FlatFsmMode.Run -> BigInt(7),
          FlatFsmMode.Done -> BigInt(9)
        )
      )
      fsm(definition, FlatFsmMode.Idle, local)(graph)
    else fsm(definition, FlatFsmMode.Idle, encoding)(graph)
    if mode == "builder-escape" then output := escaped.get.completed
    if mode == "observation-escape" then output := observation
    if mode == "observation-expression-escape" then output := !observation
    if mode == "unrelated" then count := input

final class FlatSwitchCaptureSource(mode: String) extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val core = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val selector = in(EnumType[FlatFsmMode]())
  val input = in(UInt(8))
  var escaped: Option[EnumSwitchBuilder[FlatFsmMode]] = None
  core:
    val count = Reg(0.U(8))
    FlatFsmKernelFixture.switch(selector, () => count := 0.U(8)): cases =>
      escaped = Some(cases)
      FlatFsmKernelFixture.is(cases, FlatFsmMode.Idle)(() => count := input)
      if mode == "duplicate" then FlatFsmKernelFixture.is(cases, FlatFsmMode.Idle)(() => ())
      if mode != "missing" then
        FlatFsmKernelFixture.is(cases, FlatFsmMode.Run)(() => count := 2.U(8))
      FlatFsmKernelFixture.is(cases, FlatFsmMode.Done)(() => ())
    if mode == "escape" then FlatFsmKernelFixture.is(escaped.get, FlatFsmMode.Done)(() => ())
    if mode == "unrelated" then count := input

final class FlatFsmBoundarySource(mode: String) extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val core = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Falling,
    ResetPolicy.Async,
    ResetPolarity.ActiveLow,
    100.MHz
  )
  val enable = in(Bool)
  val input = in(UInt(8))
  val canonical = EnumType.oneHot[FlatFsmMode]()
  val output = out(canonical)
  core:
    val count = Reg(0.U(8))
    val stateRegister = Reg.uninitialized(canonical)
    fsm(
      FsmDefinition("boundary", canonical),
      FlatFsmMode.Idle,
      FsmEncoding.Compact,
      IllegalStatePolicy.RecoverToInitial
    ): machine =>
      output := machine.nextState.value
      val outerNext = machine.nextState.value
      machine.state(FlatFsmMode.Idle): state =>
        FlatFsmKernelFixture.action(
          state,
          "active",
          () => {
            if mode == "outer-next" then stateRegister := outerNext
            else
              FlatFsmKernelFixture.switch(machine.currentState.value, () => count := 0.U(8)):
                cases =>
                  FlatFsmKernelFixture.is(cases, FlatFsmMode.Idle)(() => {
                    count := select(machine.nextState.valid, input, count)
                    stateRegister := machine.nextState.value
                  })
                  FlatFsmKernelFixture.is(cases, FlatFsmMode.Run)(() => ())
                  FlatFsmKernelFixture.is(cases, FlatFsmMode.Done)(() => ())
          }
        )
        state.on(machine.currentState.value.isOneOf(FlatFsmMode.Idle))(FlatFsmMode.Run)
      machine.state(FlatFsmMode.Run)(_.on(enable)(FlatFsmMode.Done))
      machine.state(FlatFsmMode.Done)(_.terminal())

/** Directly affected v0.1 storage predecessor, using actual typed input/register leaves. */
final class FlatSequentialStorageSource[A <: Data](dataType: DataType[A], context: String)
    extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val core = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enable = in(Bool)
  val input = in(dataType)
  val output = out(dataType)
  val outputAgain = out(dataType)
  val selector = in(EnumType[FlatFsmMode]())
  core:
    val target = Reg.uninitialized(dataType)
    val other = Reg.uninitialized(dataType)
    def updates(): Unit =
      target := input
      target := other
    if context == "behavioral" then FlatFsmKernelFixture.branch(enable)(() => updates())
    else if context == "switch" then
      FlatFsmKernelFixture.switch(selector, () => updates()): cases =>
        FlatFsmMode.values.foreach(value => FlatFsmKernelFixture.is(cases, value)(() => updates()))
    else
      fsm(FsmDefinition[FlatFsmMode]("typedStorage"), FlatFsmMode.Idle): machine =>
        machine.state(FlatFsmMode.Idle): state =>
          FlatFsmKernelFixture.action(state, "active", () => updates())
          state.on(enable)(FlatFsmMode.Run)
        machine.state(FlatFsmMode.Run)(_.on(enable)(FlatFsmMode.Done))
        machine.state(FlatFsmMode.Done)(_.terminal())
    output := target
    outputAgain := target

final class FlatSequentialIntegerRoleSource(mode: String) extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val core = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enable = in(Bool)
  val input = in(Integer)
  val static = param(2.integer, range = 1 to 3)
  core:
    val target = Reg.uninitialized(Integer)
    val other = Reg.uninitialized(Integer)
    if mode == "runtime-predicate" then
      val invalid = FlatFsmKernelFixture.staticCompare(input, 0.integer)
      FlatFsmKernelFixture.branch(invalid)(() => target := input)
    else if mode == "runtime-shape" then
      FlatFsmKernelFixture.branch(enable)(() => {
        target := input
        val _ = 0.U(input)
      })
    else
      val algebra = static + 1.integer
      FlatFsmKernelFixture.branch(enable)(() => {
        target := input
        target := other
        target := static
        target := algebra
      })

final class FlatRepeatedPureChild extends Module:
  val enable = in(Bool)
  val output = out(Bool)
  output := (enable && !enable) || (enable && !enable)

final class FlatRepeatedPureRoot extends Module:
  val enable = in(Bool)
  val left = instance(new FlatRepeatedPureChild)
  val right = instance(new FlatRepeatedPureChild)
  left(_.enable) := enable
  right(_.enable) := enable

final class FlatRepeatedMachineRoot(changed: Boolean) extends Module:
  val enable = in(Bool)
  val second = in(Bool)
  val input = in(UInt(8))
  val left = instance(new FlatFsmCaptureSource("plain"))
  val right = instance(new FlatFsmCaptureSource(if changed then "disjoint" else "plain"))
  left(_.enable) := enable
  left(_.second) := second
  left(_.input) := input
  right(_.enable) := enable
  right(_.second) := second
  right(_.input) := input

/** Literal RHS/guards deliberately carry no foreign-domain dependency of their own. */
final class FlatFsmTargetDomainSource(foreign: Boolean, nesting: String) extends Module:
  val clockA = in(Clock)
  val resetA = in(Reset)
  val clockB = in(Clock)
  val resetB = in(Reset)
  val a = ClockDomain.from(
    clockA,
    resetA,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "a"
  )
  val b = ClockDomain.from(
    clockB,
    resetB,
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz,
    "b"
  )
  var target: Option[Register[UInt]] = None
  (if foreign then b else a):
    target = Some(Reg(0.U(8)))
  a:
    fsm(FsmDefinition[FlatFsmMode]("targetDomain"), FlatFsmMode.Idle): machine =>
      machine.state(FlatFsmMode.Idle): state =>
        FlatFsmKernelFixture.action(
          state,
          "active",
          () => {
            if nesting == "when" then
              FlatFsmKernelFixture.branch(true.B)(() => target.get := 1.U(8))
            else if nesting == "switch" then
              FlatFsmKernelFixture.branch(true.B)(() =>
                FlatFsmKernelFixture.switch(
                  enumLiteral(EnumType[FlatFsmMode](), FlatFsmMode.Idle),
                  () => target.get := 2.U(8)
                ): cases =>
                  FlatFsmKernelFixture.is(cases, FlatFsmMode.Idle)(() => target.get := 1.U(8))
                  FlatFsmKernelFixture.is(cases, FlatFsmMode.Run)(() => ())
                  FlatFsmKernelFixture.is(cases, FlatFsmMode.Done)(() => ())
              )
            else target.get := 1.U(8)
          }
        )
        state.on(true.B)(FlatFsmMode.Run)
      machine.state(FlatFsmMode.Run)(_.on(true.B)(FlatFsmMode.Done))
      machine.state(FlatFsmMode.Done)(_.terminal())

object Increment56FlatFsmCaptureTests extends TestSuite:
  private def capture(
      mode: String = "plain",
      encoding: FsmEncoding = FsmEncoding.Compact
  ): ConstructionSnapshot =
    ConstructionKernel.inspect(new FlatFsmCaptureSource(mode, encoding))
  private def rejected(mode: String, message: String): Unit =
    val result = scala.util.Try(capture(mode))
    assert(result.isFailure, result.failed.get.getMessage.contains(message))
  val tests: Tests = Tests:
    test("actual private Bits storage and actual transition guards"):
      val value = capture()
      val machine = value.flatFsms.head
      val storage = value.modules.flatMap(_.declarations).find(_.path == machine.storage).get
      assert(storage.kind == "register", storage.dataType.contains("Bits(2)"))
      assert(storage.attributes.toMap.apply("initial") != "none")
      assert(machine.states.size == 3, machine.transitions.size == 2)
      assert(
        machine.nextLocal == machine.path + ".next",
        machine.transitionTaken == machine.path + ".taken"
      )
      assert(value.digitalExpressions.exists(_.path == machine.nextLocal))
      assert(machine.transitions.forall(_.guard.endsWith("enable")))
    test("completion before states resolves with validity and final terminal inventory"):
      val value = capture()
      val membership = value.digitalExpressions.filter(_.operation == "enum_is_one_of")
      assert(membership.nonEmpty, membership.forall(_.caseIds.size == 1))
      assert(membership.forall(_.caseIds.head.endsWith("Done")))
      assert(value.digitalExpressions.exists(_.operation == "bool_and"))
    test("all action phases retain shared typed regions and actual assignments"):
      val value = capture("actions")
      val machine = value.flatFsms.head
      assert(machine.actions.map(_.phase) == Vector("active", "exit", "transition", "entry"))
      assert(machine.actions.forall(_.arguments.take(3).map(_.role) ==
        Vector("current_local", "next_local", "transition_taken")))
      assert(machine.actions.forall(action => action.arguments.size == action.captures.size + 3))
      assert(value.digitalAssignments.count(_.kind == "register_next") == 5)
      assert(!machine.actions.flatMap(_.captures).contains(machine.transitionTaken))
    test("nested behavioral actions inherit machine root"):
      val value = capture("nested")
      assert(
        value.behavioralIfs.size == 1,
        value.behavioralIfs.head.root == value.flatFsms.head.path
      )
    test("local encodings retain one canonical ABI"):
      Vector(FsmEncoding.Compact, FsmEncoding.OneHot, FsmEncoding.Gray, FsmEncoding.Auto).foreach:
        encoding =>
          val value = capture("plain", encoding)
          assert(value.enumDefinitions.size == 1, value.enumDefinitions.head.width == 2)
          val machine = value.flatFsms.head
          assert(machine.localWidth == (if encoding == FsmEncoding.OneHot then 3 else 2))
      assert(capture("plain", FsmEncoding.Auto).flatFsms.head.resolvedEncoding == "auto-compact-v1")
      val custom = capture("custom")
      assert(
        custom.flatFsms.head.requestedEncoding == "custom",
        custom.flatFsms.head.localWidth == 4
      )
      assert(custom.flatFsms.head.localCodes.map(_.code) == Vector("3", "7", "9"))
      assert(custom.enumDefinitions.head.cases.map(_.code) ==
        Vector(BigInt(0), BigInt(1), BigInt(2)))
    test("bare custom and incompatible Gray reject"):
      assert(scala.util.Try(capture("plain", FsmEncoding.Custom)).isFailure)
      assert(scala.util.Try(capture("gray-bad", FsmEncoding.Gray)).isFailure)
    test("resetless private state is explicitly uninitialized"):
      val value = capture("resetless")
      val storage =
        value.modules.flatMap(_.declarations).find(_.path == value.flatFsms.head.storage).get
      assert(storage.attributes.toMap.apply("initial") == "none")
    test("complete unique reachable state graph is required"):
      rejected("missing", "every enum state")
      rejected("duplicate", "repeats a state")
      rejected("unreachable", "unreachable")
      rejected("dead-end", "no outgoing")
      rejected("terminal-outgoing", "terminal state")
    test("exclusive guards prove complements and reject unknown overlap or mixed modes"):
      assert(capture("disjoint").flatFsms.head.transitions.size == 3)
      rejected("overlap", "may overlap")
      rejected("mixed-mode", "mixes")
    test("closed builders and observations cannot be newly consumed"):
      rejected("builder-escape", "builder is closed")
      rejected("state-escape", "state builder is closed")
      rejected("observation-escape", "observation escaped")
      rejected("observation-expression-escape", "observation escaped")
    test("next and event feedback rejects while ordinary current observation is usable"):
      rejected("next-feedback", "feedback")
    test("action structural creation and nonregister writes reject"):
      rejected("structure", "not permitted")
      rejected("nonregister", "only owned whole registers")
    test("unrelated roots and unsupported hierarchy reject"):
      rejected("unrelated", "unrelated update roots")
      rejected("hierarchy", "NODAL-FSM-058-UNAVAILABLE")
    test("missing action attestation executes zero effects"):
      var effects = 0
      val result = scala.util.Try(ConstructionKernel.inspect(new FlatFsmCaptureSource(
        "unattested",
        effect = () => effects += 1
      )))
      assert(result.isFailure, effects == 0)
    test("typed exhaustive switch retains inventory invalid arm and actual captures"):
      val value = ConstructionKernel.inspect(new FlatSwitchCaptureSource("plain"))
      val switch = value.enumSwitches.head
      assert(switch.caseIds.size == 3, switch.arms.size == 3)
      assert(switch.invalidArm.path.endsWith(".arm3"))
      assert(value.digitalAssignments.count(_.kind == "register_next") == 3)
      assert((switch.arms :+ switch.invalidArm).forall(arm =>
        arm.arguments.size == arm.captures.size
      ))
    test("switch completeness uniqueness scope and root ownership reject"):
      Vector("missing", "duplicate", "escape", "unrelated").foreach: mode =>
        assert(
          scala.util.Try(ConstructionKernel.inspect(new FlatSwitchCaptureSource(mode))).isFailure
        )
    test("construction capture is deterministic"):
      assert(capture("actions") == capture("actions"))
    test("local custom map validates exact complete bounded codes"):
      val missing = EnumEncoding(Map(FlatFsmMode.Idle -> BigInt(0)))
      assert(scala.util.Try(FsmLocalEncoding.custom(2, missing)).isFailure)
      val complete = enumEncoding(
        FlatFsmMode.Idle -> BigInt(0),
        FlatFsmMode.Run -> BigInt(1),
        FlatFsmMode.Done -> BigInt(4)
      )
      assert(scala.util.Try(FsmLocalEncoding.custom(2, complete)).isFailure)
      assert(scala.util.Try(FsmLocalEncoding.custom(4097, complete)).isFailure)

    test("explicit canonical ABI stays separate from local compact and nested switch contexts"):
      val value = ConstructionKernel.inspect(new FlatFsmBoundarySource("nested-switch"))
      val machine = value.flatFsms.head
      assert(
        value.enumDefinitions.size == 1,
        value.enumDefinitions.head.width == 3,
        machine.localWidth == 2
      )
      assert(machine.illegalState == "recover_to_initial")
      val switch = value.enumSwitches.head
      assert(switch.root == machine.path, switch.parentRegion.contains(machine.actions.head.path))
      assert(!machine.actions.flatMap(_.captures).contains(machine.nextLocal))
      assert(value.digitalAssignments.count(_.kind == "register_next") == 3)
    test("next observation captured before action must use an in-action context view"):
      val result =
        scala.util.Try(ConstructionKernel.inspect(new FlatFsmBoundarySource("outer-next")))
      assert(result.isFailure, result.failed.get.getMessage.contains("action context"))

    test("whole-register scalar and recursive storage share sequential capture"):
      val enumType = EnumType[FlatFsmMode]()
      val record = Struct(
        "SequentialStorage",
        StructField("count", Integer),
        StructField("level", Real),
        StructField("mode", enumType),
        StructField("samples", Vec(SInt(8), 2, 3))
      )
      Vector(Integer, Real, enumType, record, Vec(record, 2), Vec(UInt(8), 2)).foreach: dataType =>
        Vector("behavioral", "switch", "fsm").foreach: context =>
          val value = ConstructionKernel.inspect(new FlatSequentialStorageSource(dataType, context))
          val assignments = value.digitalAssignments.filter(_.kind == "register_next")
          assert(assignments.nonEmpty)
          assert(assignments.forall(_.value.nonEmpty))
          val moduleOrdinals =
            value.digitalAssignments.filter(_.region.isEmpty).map(_.sourceOrdinal) ++
              value.behavioralIfs.filter(_.parentRegion.isEmpty).map(_.sourceOrdinal) ++
              value.flatFsms.map(_.sourceOrdinal) ++
              value.enumSwitches.filter(_.parentRegion.isEmpty).map(_.sourceOrdinal)
          assert(moduleOrdinals.size == 3, moduleOrdinals.distinct.size == 3)
    test("physical Integer capture never becomes a static proof binding"):
      val value = ConstructionKernel.inspect(new FlatSequentialIntegerRoleSource("roles"))
      val conditional = value.behavioralIfs.head
      val arm = conditional.trueArm
      assert(arm.arguments.map(_.role) ==
        Vector("capture", "capture", "capture", "static_capture", "static_capture"))
      val dynamic = arm.arguments.head.path
      val parent = new StructuralParameterBinding.Query(value)
      assert(scala.util.Try(new StructuralParameterBinding.Query(
        value,
        scope = Some(arm.path),
        enclosing = Some(parent),
        captures = Map(dynamic -> arm.captures.head)
      )).isFailure)
      val parameter = arm.arguments(3).path
      val staticQuery = new StructuralParameterBinding.Query(
        value,
        scope = Some(arm.path),
        enclosing = Some(parent),
        captures = Map(parameter -> arm.captures(3))
      )
      assert(staticQuery.resolve(conditional.owner, parameter) >= 0)
      val runtimeOnly =
        ConstructionKernel.inspect(new FlatSequentialStorageSource(Integer, "behavioral"))
      assert(runtimeOnly.parameterExpressions.isEmpty)
      Vector("runtime-predicate", "runtime-shape").foreach: mode =>
        assert(scala.util.Try(
          ConstructionKernel.inspect(new FlatSequentialIntegerRoleSource(mode))
        ).isFailure)

    test(
      "repeated child identities use a local collision namespace and retain semantic differences"
    ):
      def localExpressions(value: ConstructionSnapshot): Vector[Vector[String]] =
        value.digitalExpressions.groupBy(_.owner).toVector.sortBy(_._1).map: (owner, values) =>
          values.map(item =>
            item.copy(
              path = item.path.stripPrefix(owner + "."),
              owner = "$module",
              operands = item.operands.map(_.stripPrefix(owner + ".")),
              region = item.region.map(_.stripPrefix(owner + ".")),
              reset = item.reset.map(reset =>
                reset.copy(
                  destination = reset.destination.map(_.stripPrefix(owner + ".")),
                  inputs = reset.inputs.map(_.stripPrefix(owner + "."))
                )
              )
            ).toString
          )
      val pure = ConstructionKernel.inspect(new FlatRepeatedPureRoot)
      val pureGroups = localExpressions(pure)
      assert(pureGroups.size == 2, pureGroups(0) == pureGroups(1))
      val repeated = ConstructionKernel.inspect(new FlatRepeatedMachineRoot(false))
      val equal = localExpressions(repeated)
      assert(equal.size == 2, equal(0) == equal(1))
      val different =
        localExpressions(ConstructionKernel.inspect(new FlatRepeatedMachineRoot(true)))
      assert(different(0) != different(1))
      assert(pure.names.filter(_.name == "output").forall(_.provenance == "scala-declaration"))

    test("FSM action targets belong to the actual machine domain through nested roots"):
      Vector("direct", "when", "switch").foreach: nesting =>
        val accepted = ConstructionKernel.inspect(new FlatFsmTargetDomainSource(false, nesting))
        assert(accepted.flatFsms.size == 1)
        val rejected =
          scala.util.Try(ConstructionKernel.inspect(new FlatFsmTargetDomainSource(true, nesting)))
        assert(rejected.isFailure)
        assert(rejected.failed.get.getMessage.contains("NODAL-CDC-001"))
        assert(rejected.failed.get.getMessage.contains("FSM action target"))
