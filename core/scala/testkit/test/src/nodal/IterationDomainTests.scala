package nodal.internal.testkit

import nodal.*

import utest.*

final class DomainRepeatProcedure(count: Int, captured: () => Unit) extends Module:
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    analogRepeat(count):
      captured()
      sink := 1.0.real

object IterationDomainTests extends TestSuite:
  import IterationDomain.ProblemKind

  private def domain(
      lower: Int,
      upper: Int,
      step: Int = 1,
      maximum: Option[Int] = None
  ): IterationDomain.Static =
    IterationDomain.halfOpen(lower, upper, step, maximum).toOption.get

  private val integerType = AnalogProceduralRuntime.ValueType(
    AnalogProceduralRuntime.ScalarKind.Integer,
    "dimensionless"
  )

  private def loop(snapshot: AnalogControlFlowConstruction.Snapshot)
      : AnalogControlFlowRuntime.Statement.Loop =
    snapshot.root.statements.collectFirst:
      case value: AnalogControlFlowRuntime.Statement.Loop => value
    .get

  val tests: Tests = Tests:
    test("small domains agree with ordinary Scala ranges without selecting their staging"):
      for
        lower <- -16 to 16
        upper <- -16 to 16
        step <- 1 to 7
      do
        val expected = (lower until upper by step).toVector
        val actual = domain(lower, upper, step)
        assert(actual.lower == lower, actual.upperExclusive == upper, actual.step == step)
        assert(actual.tripCount == expected.size, actual.isEmpty == expected.isEmpty)
        assert(actual.lastValue == expected.lastOption)
        assert(actual.exitValue == lower.toLong + expected.size.toLong * step.toLong)
        assert(expected.indices.map(actual.valueAt).toVector == expected.map(Right(_)))

    test("equal and reversed domains are empty even at integer extremes"):
      for (lower, upper) <- Seq((0, 0), (7, -2), (Int.MaxValue, Int.MinValue)) do
        val actual = domain(lower, upper, maximum = Some(0))
        assert(actual.tripCount == 0, actual.lastValue.isEmpty)
        assert(actual.exitValue == lower.toLong)
        assert(actual.valueAt(0).left.toOption.get.kind == ProblemKind.OrdinalOutOfBounds)

    test("a singleton keeps its last body value separate from its wide exit value"):
      val actual = domain(Int.MaxValue - 1, Int.MaxValue, Int.MaxValue)
      assert(actual.tripCount == 1)
      assert(actual.lastValue.contains(Int.MaxValue - 1))
      assert(actual.valueAt(0) == Right(Int.MaxValue - 1))
      assert(actual.exitValue == 4294967293L)

    test("wide subtraction and ceiling division do not overflow"):
      val actual = domain(Int.MinValue, Int.MaxValue, step = 3)
      assert(actual.tripCount == 1431655765)
      assert(actual.valueAt(0) == Right(Int.MinValue))
      assert(actual.lastValue.contains(2147483644))
      assert(actual.exitValue == 2147483647L)

    test("too many iterations are rejected rather than narrowed or silently truncated"):
      for step <- Seq(1, 2) do
        val problem = IterationDomain.halfOpen(Int.MinValue, Int.MaxValue, step)
          .left.toOption.get
        assert(problem.kind == ProblemKind.CountOverflow)

    test("maximum count fits without allocating lanes"):
      val actual = domain(Int.MinValue, -1, maximum = Some(Int.MaxValue))
      assert(actual.tripCount == Int.MaxValue)
      assert(actual.lastValue.contains(-2))
      assert(actual.valueAt(Int.MaxValue - 1) == Right(-2))
      assert(actual.exitValue == -1L)

    test("invalid steps are rejected even for otherwise empty domains"):
      for step <- Seq(0, -1, Int.MinValue) do
        assert(IterationDomain.halfOpen(0, 0, step).left.toOption.get.kind == ProblemKind.InvalidStep)

    test("maximum is a checked envelope and never clamps the domain"):
      assert(domain(-5, 6, step = 3, maximum = Some(4)).tripCount == 4)
      val exceeded = IterationDomain.halfOpen(-5, 6, 3, Some(3)).left.toOption.get
      assert(exceeded.kind == ProblemKind.MaximumExceeded)
      val negative = IterationDomain.halfOpen(0, 0, maximum = Some(-1)).left.toOption.get
      assert(negative.kind == ProblemKind.NegativeMaximum)

    test("ordinal lookup rejects both sides of the half-open count"):
      val actual = domain(-5, 6, step = 3)
      for ordinal <- Seq(Int.MinValue, -1, 4, Int.MaxValue) do
        assert(actual.valueAt(ordinal).left.toOption.get.kind == ProblemKind.OrdinalOutOfBounds)
      assert(actual.valueAt(0) == Right(-5), actual.valueAt(3) == Right(4))

    test("negative repetition is not reclassified as an empty interval"):
      for count <- Seq(-1, Int.MinValue) do
        assert(IterationDomain.repeat(count).left.toOption.get.kind == ProblemKind.NegativeCount)
      assert(IterationDomain.repeat(0).toOption.get.isEmpty)
      assert(IterationDomain.repeat(Int.MaxValue).toOption.get.tripCount == Int.MaxValue)

    test("public repetition captures once and retains zero singleton and large counts"):
      for count <- Seq(0, 1, 17, Int.MaxValue) do
        var captures = 0
        val inspection = AnalogControlFlowInspection.inspect(
          new DomainRepeatProcedure(count, () => captures += 1)
        )
        assert(captures == 1, inspection.controlFlow.size == 1)
        val actual = loop(inspection.controlFlow.head)
        assert(actual.stage == AnalogControlFlowRuntime.LoopStage.Static)
        assert(actual.minimumIterations == count, actual.maximumIterations == count)
        assert(actual.staticTripCount.contains(count), actual.boundReads.isEmpty)
        assert(actual.body.statements.count(
          _.isInstanceOf[AnalogControlFlowRuntime.Statement.Assign]
        ) == 1)

    test("invalid public repetition rejects before invoking its body and leaves fresh capture clean"):
      var captures = 0
      val error = scala.util.Try(AnalogControlFlowInspection.inspect(
        new DomainRepeatProcedure(-1, () => captures += 1)
      )).failed.get.asInstanceOf[AnalogControlFlowRuntime.Failure]
      assert(captures == 0, error.diagnostic.code == "NODAL-ANALOG-034-008")
      assert(error.diagnostic.path.exists(_.endsWith(".loop_0")))
      val recovered = AnalogControlFlowInspection.inspect(
        new DomainRepeatProcedure(2, () => captures += 1)
      )
      assert(captures == 1, loop(recovered.controlFlow.head).staticTripCount.contains(2))

    test("the shared builder rejects conflicting metadata without invoking or repairing it"):
      for (minimum, maximum, supplied, code) <- Seq(
          (2, 3, Some(2), "NODAL-ANALOG-034-009"),
          (2, 2, Some(3), "NODAL-ANALOG-034-009"),
          (2, 2, None, "NODAL-ANALOG-034-009"),
          (2, 1, Some(2), "NODAL-ANALOG-034-008"),
          (-1, -1, Some(-1), "NODAL-ANALOG-034-008")
        )
      do
        val builder = new AnalogControlFlowConstruction.Builder("DomainBuilder")
        var captures = 0
        val error = scala.util.Try(builder.loop(
          AnalogControlFlowRuntime.LoopStage.Static,
          minimum,
          maximum,
          Set.empty,
          integerType,
          supplied,
          None
        )(_ => captures += 1)).failed.get.asInstanceOf[AnalogControlFlowRuntime.Failure]
        assert(captures == 0, error.diagnostic.code == code)
        assert(builder.finish().root.statements.isEmpty)

    test("a static builder cannot discard a dynamic bound read"):
      val builder = new AnalogControlFlowConstruction.Builder("DomainBuilder")
      var captures = 0
      val error = scala.util.Try(builder.loop(
        AnalogControlFlowRuntime.LoopStage.Static,
        2,
        2,
        Set("DomainBuilder.variable"),
        integerType,
        Some(2),
        None
      )(_ => captures += 1)).failed.get.asInstanceOf[AnalogControlFlowRuntime.Failure]
      assert(captures == 0, error.diagnostic.code == "NODAL-ANALOG-034-009")

    test("immutable analysis still rejects a forged negative loop independently"):
      val builder = new AnalogControlFlowConstruction.Builder("DomainBuilder")
      builder.loop(
        AnalogControlFlowRuntime.LoopStage.Static,
        2,
        2,
        Set.empty,
        integerType,
        Some(2),
        None
      )(_ => ())
      val snapshot = builder.finish()
      val forged = loop(snapshot).copy(
        minimumIterations = -1,
        maximumIterations = -1,
        staticTripCount = Some(-1)
      )
      val error = scala.util.Try(AnalogControlFlowRuntime.analyze(
        snapshot.root.copy(statements = Vector(forged))
      )).failed.get.asInstanceOf[AnalogControlFlowRuntime.Failure]
      assert(error.diagnostic.code == "NODAL-ANALOG-034-008")
