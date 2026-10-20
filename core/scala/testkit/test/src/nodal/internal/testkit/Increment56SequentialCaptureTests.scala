package nodal.internal.testkit

import nodal.*
import utest.*

/** Test-only raw graph adapter. Reflection bypasses authored-source admission explicitly; the real
  * production callback entrypoint still receives and verifies its ordinary checked carrier.
  */
private object SequentialKernelFixture:
  private val kernelClass = Class.forName("nodal.ConstructionKernel$")
  private val kernel = kernelClass.getField("MODULE$").get(())
  private val callbacksClass = Class.forName("nodal.DigitalCollectionCallback$")
  private val callbacks = callbacksClass.getField("MODULE$").get(())
  private val checked = callbacksClass.getMethod("checkedSequential", classOf[Function0[?]])
  private val branchMethod = kernelClass.getMethod(
    "behavioralBranch",
    classOf[String],
    classOf[Option[?]],
    classOf[Function0[?]]
  )
  private val comparisonMethod = kernelClass.getMethod(
    "behavioralComparison",
    classOf[Expr[?]],
    classOf[Expr[?]],
    classOf[String]
  )

  private def invoke(method: java.lang.reflect.Method, owner: AnyRef, arguments: AnyRef*): AnyRef =
    try method.invoke(owner, arguments*)
    catch
      case failure: java.lang.reflect.InvocationTargetException =>
        scala.util.Failure[Nothing](Option(failure.getCause).getOrElse(failure)).get

  def branch(kind: String, condition: Option[Expr[Bool]], body: () => Unit): Unit =
    val carrier = invoke(checked, callbacks, body)
    val _ = invoke(branchMethod, kernel, kind, condition, carrier)

  def comparison(left: Expr[Integer], right: Expr[Integer], operation: String): Expr[Bool] =
    invoke(comparisonMethod, kernel, left, right, operation).asInstanceOf[Expr[Bool]]

  def sharedWhen(condition: Expr[Bool], body: () => Unit): Unit =
    val runtimeClass = Class.forName("nodal.SequentialRuntime$")
    val runtime = runtimeClass.getField("MODULE$").get(())
    val method = runtimeClass.getMethod("when", classOf[Expr[?]], classOf[Function0[?]])
    val _ = invoke(method, runtime, condition, invoke(checked, callbacks, body))

/** Kernel fixtures isolate runtime ownership from the compiler's independent effect attestation. */
final class SequentialCaptureSource(mode: String) extends Module:
  val clock = in(Clock)
  val reset = in(Reset)
  val domain = ClockDomain.from(
    clock,
    reset,
    ClockEdge.Rising,
    if mode == "resetless" then ResetPolicy.None
    else if mode == "release" then ResetPolicy.AsyncAssertSyncRelease(2) else ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enable = in(Bool)
  val second = in(Bool)
  val input = in(UInt(8))
  val alternate = in(UInt(8))
  val output = out(UInt(8))
  val parameter = param(0.integer, range = -1 to 1)
  val state = if mode == "uninitialized" then Reg.uninitialized(UInt(8)) else Reg(0.U(8))
  def branch(condition: Expr[Bool])(body: => Unit): Unit =
    SequentialKernelFixture.branch("when", Some(condition), () => body)
  mode match
    case "unconditional" | "resetless" | "release" | "uninitialized" => state := input
    case "orphan" => SequentialKernelFixture.branch("otherwise", None, () => state := input)
    case "foreign-type" => state := 0.U(9)
    case "bad-sink" => branch(enable)(output := input)
    case "declaration" => branch(enable) { val _ = Reg(0.U(8)) }
    case "orphan-after-assignment" =>
      branch(enable)(state := input)
      output := input
      SequentialKernelFixture.branch("otherwise", None, () => state := alternate)
    case "double-root" =>
      branch(enable)(state := input)
      branch(second)(state := alternate)
    case "mixed-root" =>
      state := input
      branch(enable)(state := alternate)
    case "static" =>
      val predicate = SequentialKernelFixture.comparison(parameter, 0.integer, "gt")
      branch(predicate)(state := input)
    case "mixed" =>
      val predicate = SequentialKernelFixture.comparison(parameter, 0.integer, "gt")
      branch(predicate && enable)(state := input)
    case "chain" =>
      branch(enable):
        state := input
        state := alternate
        branch(second)(state := 3.U(8))
      SequentialKernelFixture.branch("elsewhen", Some(second), () => state := 4.U(8))
      SequentialKernelFixture.branch("otherwise", None, () => state := 5.U(8))
    case "escaped" =>
      var value: Expr[UInt] = input
      branch(enable) { value = input + 0.U(8) }
      state := value
    case _ => branch(enable)(state := input)

final class SequentialExternalCapture(policy: ResetPolicy, initialized: Boolean) extends Module:
  val domain = ClockDomain.external(
    "root",
    ClockEdge.Falling,
    policy,
    ResetPolarity.ActiveLow,
    20.MHz
  )
  val input = in(UInt(8))
  val state = if initialized then Reg(0.U(8)) else Reg.uninitialized(UInt(8))
  state := input

final class SequentialCrossDomainCapture extends Module:
  val a = ClockDomain.external(
    "a",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    20.MHz
  )
  val b = ClockDomain.external(
    "b",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    20.MHz
  )
  var first = Option.empty[Register[UInt]]
  var second = Option.empty[Register[UInt]]
  a { first = Some(Reg(0.U(8))) }
  b { second = Some(Reg(0.U(8))) }
  first.get := second.get

final class SequentialUnattestedCapture(effect: () => Unit, caught: Boolean, kernel: Boolean)
    extends Module:
  private val runtime = Class.forName(
    if kernel then "nodal.ConstructionKernel$" else "nodal.SequentialRuntime$"
  )
  private val method = if kernel then
    runtime.getMethod(
      "behavioralBranch",
      classOf[String],
      classOf[Option[?]],
      classOf[Function0[?]]
    )
  else runtime.getMethod("when", classOf[Expr[?]], classOf[Function0[?]])
  def invoke(): Unit =
    val owner = runtime.getField("MODULE$").get(())
    val _ = if kernel then method.invoke(owner, "when", Some(true.B), effect)
    else method.invoke(owner, true.B, effect)
  if caught then
    val _ = scala.util.Try(invoke())
  else invoke()

final class SequentialWireCapture(foreign: Boolean, guard: Boolean) extends Module:
  val a = ClockDomain.external(
    "a",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    20.MHz
  )
  val b = ClockDomain.external(
    "b",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    20.MHz
  )
  var source = Option.empty[Register[UInt]]
  var predicate = Option.empty[Register[Bool]]
  var target = Option.empty[Register[UInt]]
  a { target = Some(Reg(0.U(8))) }
  (if foreign then b else a) { source = Some(Reg(0.U(8))); predicate = Some(Reg(false.B)) }
  val dataWire = wire(UInt(8))
  val guardWire = wire(Bool)
  dataWire := source.get
  guardWire := predicate.get
  if guard then
    SequentialKernelFixture.branch("when", Some(guardWire), () => target.get := 1.U(8))
  else target.get := dataWire

final class SequentialPureStructuralCapture(default: Int) extends Module:
  val domain = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    20.MHz
  )
  val MODE = param(default.integer)
  val input = in(UInt(8))
  val other = in(UInt(8))
  val enable = in(Bool)
  val state = Reg.uninitialized(UInt(8))
  SequentialKernelFixture.branch(
    "when",
    Some(enable),
    () => {
      val predicate = ConstructionKernel.staticPredicate("eq", Vector(MODE, 0.integer))
      val next = ConstructionKernel.structuralIf(predicate, true)(
        () => Some(input),
        () => Some(other)
      ).get.asInstanceOf[Expr[UInt]]
      state := next
    }
  )

final class SequentialBoundaryCollision extends Module:
  val root_clock = in(Bool)
  val root_reset = in(Bool)
  val domain = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    20.MHz
  )

final class SequentialDeepCapture(arms: Int) extends Module:
  val domain = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    20.MHz
  )
  val state = Reg(false.B)
  SequentialKernelFixture.branch("when", Some(false.B), () => state := true.B)
  (1 until arms).foreach: _ =>
    SequentialKernelFixture.branch("elsewhen", Some(false.B), () => state := true.B)

final class SequentialDistinctBoundCapture extends Module:
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
    20.MHz,
    "a"
  )
  val b = ClockDomain.from(
    clockB,
    resetB,
    ClockEdge.Falling,
    ResetPolicy.Async,
    ResetPolarity.ActiveLow,
    10.MHz,
    "b"
  )
  a { val state = Reg(false.B); state := true.B }
  b { val state = Reg(false.B); state := true.B }

final class SequentialExplicitInputDomainCapture(foreign: Boolean, required: Boolean)
    extends Module:
  val a = ClockDomain.external(
    "a",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    20.MHz
  )
  val b = if required then ClockDomain.required("b")
  else
    ClockDomain.external(
      "b",
      ClockEdge.Rising,
      ResetPolicy.Sync,
      ResetPolarity.ActiveHigh,
      20.MHz
    )
  val input = new KernelExpr[UInt](
    Vector.empty,
    resultType = Some(CandidateRuntime.typeDescriptor(UInt(8)))
  )
  CandidateRuntime.declare(
    input,
    KernelSignalKind.Input,
    dataType = Some(UInt(8)),
    domain = Some(if foreign then b else a)
  )
  a { val state = Reg(0.U(8)); state := input }

final class SequentialPureAnalogHelperCapture(lowered: Boolean) extends Module:
  val state = variable(Real, 0.0.real)
  def pureHelper(): Unit = when(true.B) { () }
  analogProcedure:
    if lowered then SequentialKernelFixture.sharedWhen(true.B, () => ())
    else pureHelper()
    state := 1.0.real

object Increment56SequentialCaptureTests extends TestSuite:
  private def capture(mode: String): ConstructionSnapshot =
    ConstructionKernel.inspect(new SequentialCaptureSource(mode))
  private def rejected(mode: String, code: String = ""): Unit =
    val result = scala.util.Try(capture(mode))
    assert(result.isFailure)
    if code.nonEmpty then assert(result.failed.get.getMessage.contains(code))

  val tests: Tests = Tests:
    test("one when chain retains actual nested predicates captures and source-order writes"):
      val value = capture("chain")
      assert(value.behavioralIfs.size == 3)
      assert(value.behavioralIfs.map(_.root).distinct.size == 1)
      val root = value.behavioralIfs.find(_.parentRegion.isEmpty).get
      val first = value.behavioralIfs.find(_.parentRegion.contains(root.trueArm.path)).get
      val second = value.behavioralIfs.find(_.parentRegion.contains(root.falseArm.path)).get
      assert(first.root == root.path, second.root == root.path)
      assert(root.trueArm.arguments.size == root.trueArm.captures.size)
      assert(value.digitalAssignments.size == 5)
      assert(value.digitalAssignments.forall(_.kind == "register_next"))
      assert(value.digitalAssignments.filter(
        _.region.contains(root.trueArm.path)
      ).map(_.sourceOrdinal) == Vector(0, 1))
      assert(first.sourceOrdinal == 2)
      assert(root.trueArm.captures.exists(_.endsWith(".state")))
      assert(root.trueArm.captures.exists(_.endsWith(".input")))
      assert(root.trueArm.captures.exists(_.endsWith(".alternate")))
      assert(value.behavioralIfs.forall(record =>
        record.trueArm.yieldValue.isEmpty && record.falseArm.yieldValue.isEmpty
      ))
    test(
      "parameter-only and mixed predicates retain static identity without topology classification"
    ):
      Vector("static", "mixed").foreach: mode =>
        val value = capture(mode)
        val parameter = value.modules.flatMap(_.declarations).find(_.name == "parameter").get
        assert(!parameter.attributes.toMap.contains("classification"))
        assert(!parameter.attributes.toMap.contains("structural_effects"))
        assert(value.staticPredicates.size == 1)
        assert(value.staticPredicates.head.operands.contains(parameter.path))
        val conversion = value.digitalExpressions.find(_.operation == "behavioral_predicate").get
        assert(conversion.dataType == "Bool")
        assert(conversion.operands == Vector(value.staticPredicates.head.path))
        assert(value.behavioralIfs.size == 1)
        if mode == "mixed" then assert(value.digitalExpressions.exists(_.operation == "bool_and"))
    test("uninitialized state carries no reset initializer"):
      val value = capture("uninitialized")
      val state = value.modules.flatMap(_.declarations).find(_.name == "state").get
      assert(state.attributes.toMap.apply("initial") == "none")
      assert(value.digitalAssignments.size == 1)
    test("external domains capture real owned clock and reset boundaries"):
      val value = ConstructionKernel.inspect(new SequentialExternalCapture(ResetPolicy.Async, true))
      val module = value.modules.head
      val domain = module.domains.head
      val attrs = domain.attributes.toMap
      val clock = module.declarations.find(_.path == attrs("clock_source")).get
      val reset = module.declarations.find(_.path == attrs("reset_source")).get
      assert(
        clock.kind == "input",
        clock.dataType.contains("Clock"),
        clock.domain.contains(domain.path)
      )
      assert(
        reset.kind == "input",
        reset.dataType.contains("Reset"),
        reset.domain.contains(domain.path)
      )
      assert(clock.name == "root_clock", reset.name == "root_reset")
      assert(domain.edge.contains("falling"), domain.resetPolicy.contains("async"))
      assert(attrs("reset_polarity") == "active_low")
    test("resetless uninitialized external state has no reset boundary"):
      val value = ConstructionKernel.inspect(new SequentialExternalCapture(ResetPolicy.None, false))
      assert(value.modules.head.declarations.count(_.dataType.contains("Clock")) == 1)
      assert(!value.modules.head.declarations.exists(_.dataType.contains("Reset")))
    test("unrelated update roots reject without guessing exclusivity"):
      rejected("double-root", "NODAL-STATE-001")
      rejected("mixed-root", "NODAL-STATE-001")
    test("orphan and interrupted chain continuations reject"):
      rejected("orphan")
      rejected("orphan-after-assignment")
    test("guard bodies reject structural declarations and non-register writes"):
      rejected("declaration")
      rejected("bad-sink")
    test("register update types and branch-local lifetimes remain exact"):
      rejected("foreign-type")
      rejected("escaped")
    test("resetless initialization rejects while owned release synchronization is captured"):
      rejected("resetless")
      val released = ConstructionKernel.inspect(new SequentialCaptureSource("release"))
      assert(released.digitalExpressions.exists(_.reset.exists(r =>
        r.kind == "sync" && r.stages == 2
      )))
    test("cross-domain dependencies reject"):
      val value = scala.util.Try(ConstructionKernel.inspect(new SequentialCrossDomainCapture))
      assert(value.isFailure, value.failed.get.getMessage.contains("NODAL-CDC-001"))
    test("unattested callback never executes and caught errors poison the transaction"):
      var count = 0
      Vector(false, true).foreach: caught =>
        Vector(false, true).foreach: kernel =>
          val result = scala.util.Try(ConstructionKernel.inspect(
            new SequentialUnattestedCapture(() => { count += 1 }, caught, kernel)
          ))
          assert(result.isFailure, count == 0)
    test("capture ordering and names are deterministic across independent sessions"):
      assert(capture("chain") == capture("chain"))

    test("continuous wire dependencies preserve same-domain reads and reject foreign ones"):
      Vector(false, true).foreach: guard =>
        val accepted = ConstructionKernel.inspect(new SequentialWireCapture(false, guard))
        assert(accepted.digitalAssignments.nonEmpty)
        val rejected =
          scala.util.Try(ConstructionKernel.inspect(new SequentialWireCapture(true, guard)))
        assert(rejected.isFailure, rejected.failed.get.getMessage.contains("NODAL-CDC-001"))
    test("pure structural value RHS retains both alternatives and structural parameter role"):
      Vector(0, 1).foreach: default =>
        val value = ConstructionKernel.inspect(new SequentialPureStructuralCapture(default))
        val guard = value.behavioralIfs.head
        val join = value.structuralIfs.head
        assert(join.parentRegion.contains(guard.trueArm.path))
        assert(join.dataType.contains("UInt(8)"))
        assert(join.trueArm.yieldValue.nonEmpty, join.falseArm.yieldValue.nonEmpty)
        val parameter = value.modules.head.declarations.find(_.name == "MODE").get
        assert(parameter.attributes.toMap.apply("classification") == "structural")
        assert(parameter.attributes.toMap.apply("structural_effects") == "topology")
        assert(value.digitalAssignments.size == 1)
    test("external boundary names remain collision-safe and reset getter retains exact identity"):
      val captured = ConstructionKernel.capture(new SequentialBoundaryCollision)(value =>
        Vector(value.domain.reset)
      )
      val declarations = captured.snapshot.modules.head.declarations
      assert(declarations.map(_.path).distinct.size == declarations.size)
      val reset = declarations.find(_.dataType.contains("Reset")).get
      assert(captured.references.head.path == reset.path, captured.references.head.kind == "input")
      assert(captured.snapshot.modules.head.domains.head.attributes.toMap.apply("reset_source") ==
        reset.path)
    test("behavioral lexical depth is finite and rejects at the exact boundary"):
      val accepted = ConstructionKernel.inspect(new SequentialDeepCapture(128))
      assert(accepted.behavioralIfs.size == 128)
      val rejected = scala.util.Try(ConstructionKernel.inspect(new SequentialDeepCapture(129)))
      assert(rejected.isFailure, rejected.failed.get.getMessage.contains("depth exceeds 128"))

    test("distinct from-domain boundary pins retain exact actual source ownership"):
      val value = ConstructionKernel.inspect(new SequentialDistinctBoundCapture)
      val module = value.modules.head
      assert(module.domains.size == 2)
      module.domains.foreach: domain =>
        Vector("clock_source", "reset_source").foreach: key =>
          val pin = module.declarations.find(_.path == domain.attributes.toMap.apply(key)).get
          assert(pin.domain.contains(domain.path))
    test("explicit input domains remain causal while unresolved requirements reject"):
      val accepted =
        ConstructionKernel.inspect(new SequentialExplicitInputDomainCapture(false, false))
      assert(accepted.digitalAssignments.size == 1)
      val foreign =
        scala.util.Try(ConstructionKernel.inspect(new SequentialExplicitInputDomainCapture(
          true,
          false
        )))
      assert(foreign.isFailure, foreign.failed.get.getMessage.contains("NODAL-CDC-001"))
      val unresolved =
        scala.util.Try(ConstructionKernel.inspect(new SequentialExplicitInputDomainCapture(
          true,
          true
        )))
      assert(
        unresolved.isFailure,
        unresolved.failed.get.getMessage.contains("NODAL-ROOT-DOMAIN-016")
      )

    test("context-free pure helper retains actual analog block ownership"):
      Vector(false, true).foreach: lowered =>
        val value = ConstructionKernel.inspect(new SequentialPureAnalogHelperCapture(lowered))
        assert(value.analogProcedural.nonEmpty, value.behavioralIfs.isEmpty)
        assert(value.digitalAssignments.isEmpty)
