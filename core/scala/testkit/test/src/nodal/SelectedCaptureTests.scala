package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.ScalaToMlirBridge

import scala.compiletime.testing.typeCheckErrors
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*

import utest.*

final class SelectedClockCapture extends Module:
  val inputClock = in(Clock)
  val inputReset = in(Reset)
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveLow,
    12.5.MHz
  )
  val bound = ClockDomain.from(
    inputClock,
    inputReset,
    ClockEdge.Falling,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    8.MHz,
    "bound"
  )
  val release = Rdc.sync(root.reset, stages = 3)
  val related = ClockDomain.generated(
    "related",
    inputClock,
    root,
    ClockRelation.Ratio(2, 5, 90.deg),
    release
  )
  val transferred = Rdc.sync(inputReset, to = bound, stages = 4)
  val arbitrary: Expr[Reset] = CandidateRuntime.expr(inputReset, 4)
  val authoredPath: Expr[Reset] = CandidateRuntime.expr("SelectedClockCapture.inputReset", 4)

// Private capture fixture: retain runtime invariants independently of public callback attestation.
final class SelectedZeroPhaseFunction extends Module:
  val identity = AnalogFunction("zeroPhaseConstant", Real): function =>
    require(Phase.Zero.degrees == 0.0)
    function.input("value", Real)
  ConstructionKernel.generatedRegion(0, 1, 1, None): _ =>
    require(Phase.Zero.degrees == 0.0)

// Private capture fixture: retain runtime invariants independently of public callback attestation.
final class SelectedGeneratedMetadata(effect: () => Unit) extends Module:
  ConstructionKernel.generatedRegion(0, 1, 1, None): _ =>
    effect()

final class SelectedDefaultPhaseCapture extends Module:
  val clock = in(Clock)
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    1.MHz
  )
  val divided = ClockDomain.generated(
    "divided",
    clock,
    root,
    ClockRelation.Ratio(1, 2),
    root.reset
  )

final class SelectedAnalogCapture extends Module:
  val p = inout(Electrical)
  val n = inout(Electrical)
  val resistance = param(1.kOhm)
  analog:
    I(p, n) <+ V(p, n) / resistance

final class SelectedQuantityEffect(effect: () => Unit) extends Module:
  val illegal = AnalogFunction("quantityEffect", Real): function =>
    effect()
    function.input("value", Real)

final class SelectedStatementCapture extends Module:
  val signal = in(Bool)
  CandidateRuntime.statement(signal)

object SelectedCaptureTests extends TestSuite:
  private def clocks(): KernelSelectedCapture =
    ConstructionKernel.capture(new SelectedClockCapture): module =>
      Vector(
        module.root,
        module.bound,
        module.related,
        module.inputClock,
        module.inputReset,
        module.root.reset,
        module.release,
        module.transferred,
        module.arbitrary,
        module.authoredPath
      )

  private def rejection(body: => Any): String =
    scala.util.Try(body).failed.get.asInstanceOf[ConstructionException].diagnostic.code

  val tests: Tests = Tests:
    test("built-in zero phase has no first-use effect in restricted construction"):
      val snapshot = ConstructionKernel.inspect(new SelectedZeroPhaseFunction)
      assert(snapshot.analogFunctions.nonEmpty, snapshot.generatedRegions.nonEmpty)

    test("default phase capture is insensitive to one-time metadata initialization"):
      def capture() = ConstructionKernel.capture(new SelectedDefaultPhaseCapture): module =>
        Vector(module.root, module.divided)
      val first = capture()
      assert(capture() == first)
      assert(first.operationKinds == Vector("metadata"))
      assert(first.snapshot.modules.head.domains.find(_.name == "divided").get
        .attributes.toMap.apply("phase_degrees") == "0.0")

    test("quantities retain signed finite SI values and checked same-dimension addition"):
      val first = (-1.25).volts
      val sum = first + 2.5.volts
      assert(first.siValue == -1.25, sum.siValue == 1.25)
      assert(!(first eq sum))
      assert(0.0.seconds.siValue == 0.0, (-3.0).amps.siValue == -3.0)
      assert(Double.MaxValue.ohms.siValue == Double.MaxValue)
      assert(scala.util.Try(Double.MaxValue.volts + Double.MaxValue.volts).isFailure)
      for invalid <- Vector(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity) do
        assert(scala.util.Try(invalid.volts).isFailure)
      val mismatch = typeCheckErrors("import nodal.*; val bad = 1.0.volts + 1.0.amps")
      assert(mismatch.nonEmpty)

    test("frequency and phase retain finite metadata without normalizing signed phase"):
      assert(12.5.MHz.hertz == 12500000.0, 8.MHz.hertz == 8000000.0)
      assert(Frequency.hertz(0.5).hertz == 0.5)
      assert(Frequency.hertz(Double.MaxValue).hertz == Double.MaxValue)
      assert((-450.5).deg.degrees == -450.5, Phase.Zero.degrees == 0.0)
      for invalid <- Vector(0.0, -1.0, Double.NaN, Double.PositiveInfinity, Double.MaxValue) do
        assert(scala.util.Try(invalid.MHz).isFailure)
      for invalid <- Vector(0.0, -1.0, Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity)
      do
        assert(scala.util.Try(Frequency.hertz(invalid)).isFailure)
      for invalid <- Vector(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity) do
        assert(scala.util.Try(invalid.deg).isFailure)

    test("quantity frequency and phase preserve candidate effect boundaries"):
      val left = 1.0.volts
      val right = 2.0.volts
      val effects = Vector[() => Unit](
        () => { val _ = 1.0.volts },
        () => { val _ = left + right },
        () => { val _ = 1.MHz },
        () => { val _ = Frequency.hertz(1.0) },
        () => { val _ = 45.deg }
      )
      effects.foreach: effect =>
        assert(rejection(ConstructionKernel.inspect(new SelectedQuantityEffect(effect))) ==
          "NODAL-ANALOG-041-001")
        assert(rejection(ConstructionKernel.inspect(new SelectedGeneratedMetadata(effect))) ==
          "NODAL-ITERATION-043-004")

    test("clock snapshots preserve actual sources parents frequency and phase"):
      val capture = clocks()
      val domains = capture.snapshot.modules.head.domains.map(d => d.name -> d).toMap
      val root = domains("root").attributes.toMap
      val bound = domains("bound").attributes.toMap
      val related = domains("related").attributes.toMap
      assert(root("frequency_hz") == "1.25E7")
      assert(!root.contains("clock_source"), !root.contains("clock_parent"))
      assert(bound("frequency_hz") == "8000000.0")
      assert(bound("clock_source") == "SelectedClockCapture.inputClock")
      assert(bound("reset_source") == "SelectedClockCapture.inputReset")
      assert(related("clock_parent") == "SelectedClockCapture.root")
      assert(related("clock_source") == "SelectedClockCapture.inputClock")
      assert(related("reset_source") == "SelectedClockCapture.release")
      assert(related("phase_degrees") == "90.0")
      assert(related("clock_multiply") == "2", related("clock_divide") == "5")
      assert(!related.contains("frequency_hz"))
      assert(!related.contains("reset_polarity"), domains("related").edge.isEmpty)
      assert(capture.references.take(3).forall(_.kind == "domain"))
      assert(capture.references(3).dataType.contains("Clock"))
      assert(capture.references(4).dataType.contains("Reset"))

    test("reset synchronization is tagged and authored path strings remain literals"):
      val capture = clocks()
      val external = capture.references(5)
      val release = capture.references(6)
      val transferred = capture.references(7)
      assert(external.operation.contains("external_reset"), external.dataType.contains("Reset"))
      assert(release.operation.contains("rdc_sync"), release.dataType.contains("Reset"))
      assert(release.operands == Vector(
        KernelSelectedOperand.Reference(external.path),
        KernelSelectedOperand.Literal("integer", "3")
      ))
      assert(transferred.operands == Vector(
        KernelSelectedOperand.Reference("SelectedClockCapture.inputReset"),
        KernelSelectedOperand.Reference("SelectedClockCapture.bound"),
        KernelSelectedOperand.Literal("integer", "4")
      ))
      assert(capture.references(8).operation.isEmpty)
      assert(capture.references(9).operands.head ==
        KernelSelectedOperand.Literal("string", "SelectedClockCapture.inputReset"))

    test("selected paths use canonical source names and source map correlation"):
      val capture = ConstructionKernel.capture(new SelectedAnalogCapture): module =>
        Vector(module.p, module.n, module.resistance, module)
      assert(capture.references.map(_.path) == Vector(
        "SelectedAnalogCapture.p",
        "SelectedAnalogCapture.n",
        "SelectedAnalogCapture.resistance",
        "SelectedAnalogCapture"
      ))
      assert(capture.references.forall(_.owner == "SelectedAnalogCapture"))
      assert(capture.references.map(_.kind) == Vector(
        "analog-inout",
        "analog-inout",
        "parameter",
        "module"
      ))
      assert(capture.references(2).dataType.contains("Real"))
      val located = capture.snapshot.sourceMap.map(_.semanticPath).toSet
      assert(capture.references.take(3).forall(value => located.contains(value.path)))
      val ordinary = ScalaToMlirBridge.lower(new SelectedAnalogCapture)
      assert(ScalaToMlirBridge.fromSnapshot(capture.snapshot) == ordinary)
      assert(capture.snapshot.modules.forall(_.domains.isEmpty))
      assert(clocks().snapshot.modules.head.domains.nonEmpty)

    test("selectors run in the original transaction before final naming"):
      val capture = ConstructionKernel.capture(new SelectedClockCapture): module =>
        Vector(Rdc.sync(module.inputReset, module.bound, 6))
      assert(capture.references.head.operation.contains("rdc_sync"))
      assert(capture.references.head.operands.last == KernelSelectedOperand.Literal("integer", "6"))
      assert(capture.snapshot.sourceMap.exists(_.semanticPath == capture.references.head.path))

    test("foreign selections reject even when another transaction has matching paths"):
      var escaped = Vector.empty[AnyRef]
      val _ = ConstructionKernel.capture(new SelectedClockCapture): module =>
        escaped = Vector(module.root, module.inputClock, module.release, module)
        escaped
      escaped.foreach: value =>
        assert(rejection(ConstructionKernel.capture(new SelectedClockCapture)(_ =>
          Vector(value)
        )) ==
          "NODAL-SELECTION-050-001")
      assert(clocks().references.nonEmpty)

    test("foreign reset and clock sources cannot become domain metadata"):
      var escapedClock = Option.empty[Expr[Clock]]
      var escapedReset = Option.empty[Expr[Reset]]
      val _ = ConstructionKernel.capture(new SelectedClockCapture): module =>
        escapedClock = Some(module.inputClock)
        escapedReset = Some(module.inputReset)
        Vector.empty
      val foreignClock = rejection(ConstructionKernel.inspect(new Module:
        val reset = in(Reset)
        val bound = ClockDomain.from(
          escapedClock.get,
          reset,
          ClockEdge.Rising,
          ResetPolicy.Sync,
          ResetPolarity.ActiveHigh,
          1.MHz
        )))
      val foreignReset = rejection(ConstructionKernel.inspect(new Module:
        val clock = in(Clock)
        val bound = ClockDomain.from(
          clock,
          escapedReset.get,
          ClockEdge.Rising,
          ResetPolicy.Sync,
          ResetPolarity.ActiveHigh,
          1.MHz
        )))
      assert(foreignClock == "NODAL-DOMAIN-020", foreignReset == "NODAL-DOMAIN-020")

    test("selected expression operands reject foreign handles and transactions recover"):
      var foreign = Option.empty[Expr[Reset]]
      val _ = ConstructionKernel.capture(new SelectedClockCapture): module =>
        foreign = Some(module.inputReset)
        Vector.empty
      val code = rejection(ConstructionKernel.capture(new SelectedClockCapture): module =>
        Vector(Rdc.sync(foreign.get, module.bound, 2)))
      assert(code == "NODAL-SELECTION-050-001")
      assert(clocks().references.nonEmpty)

    test("metadata preserves operation guards without becoming an executable statement"):
      val descriptors = clocks()
      assert(descriptors.operationKinds.nonEmpty)
      assert(descriptors.operationKinds.forall(_ == "metadata"))
      assert(descriptors.snapshot.analogSemantics.unsupported.isEmpty)
      def effect() = ConstructionKernel.capture(new SelectedStatementCapture)(m => Vector(m.signal))
      val first = effect()
      assert(first.operationKinds == Vector("statement"))
      assert(first.snapshot.analogSemantics.unsupported.nonEmpty)
      assert(!first.snapshot.analogSemantics.unsupported.exists(_.contains("@")))
      assert(effect() == first)

    test("repeated and parallel captures are deterministic and independent"):
      val expected = clocks()
      assert(clocks() == expected)
      val concurrent = Await.result(Future.sequence(Vector.fill(4)(Future(clocks()))), 30.seconds)
      assert(concurrent.forall(_ == expected))
