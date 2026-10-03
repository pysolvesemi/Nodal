package nodal.internal.testkit

import nodal.*

import utest.*

object IterationBoundArithmeticTests extends TestSuite:
  import IterationDomain.Arithmetic
  import IterationDomain.Bounds
  import IterationDomain.ProblemKind

  private val intervals = (for
    lower <- -3 to 3
    upper <- lower to 3
  yield Bounds(lower.toLong, upper.toLong)).toVector

  private def concrete(operation: Arithmetic, left: BigInt, right: BigInt): BigInt =
    operation match
      case Arithmetic.Add => left + right
      case Arithmetic.Subtract => left - right
      case Arithmetic.Multiply => left * right
      case Arithmetic.Divide => left / right
      case Arithmetic.Negate => -left

  private def evaluate(operation: Arithmetic, operands: Bounds*): Bounds =
    IterationDomain.arithmetic(operation, operands.toVector).toOption.get

  private def rejected(operation: Arithmetic, kind: ProblemKind, operands: Bounds*): Unit =
    val result = IterationDomain.arithmetic(operation, operands.toVector)
    assert(result.left.toOption.exists(_.kind == kind))

  val tests: Tests = Tests:
    test("small binary intervals agree with every independently enumerated concrete result"):
      for
        operation <- Vector(
          Arithmetic.Add,
          Arithmetic.Subtract,
          Arithmetic.Multiply,
          Arithmetic.Divide
        )
        left <- intervals
        right <- intervals
      do
        if operation == Arithmetic.Divide && right.lower <= 0L && right.upper >= 0L then
          rejected(operation, ProblemKind.ZeroDivisor, left, right)
        else
          val expected = (for
            a <- left.lower.toInt to left.upper.toInt
            b <- right.lower.toInt to right.upper.toInt
          yield concrete(operation, BigInt(a), BigInt(b))).toVector
          val actual = evaluate(operation, left, right)
          assert(BigInt(actual.lower) == expected.min)
          assert(BigInt(actual.upper) == expected.max)
          assert(expected.forall(value => value >= actual.lower && value <= actual.upper))

    test("negation reverses interval endpoints and retains every small concrete result"):
      for operand <- intervals do
        val expected = (operand.lower.toInt to operand.upper.toInt).map(value => -BigInt(value))
        val actual = evaluate(Arithmetic.Negate, operand)
        assert(BigInt(actual.lower) == expected.min, BigInt(actual.upper) == expected.max)

    test("equal intervals do not assert that independent parameters have equal values"):
      val interval = Bounds(-3L, 7L)
      assert(evaluate(Arithmetic.Subtract, interval, interval) == Bounds(-10L, 10L))
      assert(evaluate(Arithmetic.Multiply, interval, interval) == Bounds(-21L, 49L))

    test("signed boundaries never wrap saturate or lose precision through floating point"):
      val minimum = Bounds(Long.MinValue, Long.MinValue)
      val maximum = Bounds(Long.MaxValue, Long.MaxValue)
      val one = Bounds(1L, 1L)
      val negativeOne = Bounds(-1L, -1L)
      val two = Bounds(2L, 2L)
      assert(evaluate(Arithmetic.Add, maximum, negativeOne) ==
        Bounds(Long.MaxValue - 1L, Long.MaxValue - 1L))
      assert(evaluate(Arithmetic.Subtract, minimum, negativeOne) ==
        Bounds(Long.MinValue + 1L, Long.MinValue + 1L))
      assert(evaluate(Arithmetic.Multiply, maximum, one) == maximum)
      assert(evaluate(Arithmetic.Add, minimum, maximum) == Bounds(-1L, -1L))
      val beyondDouble = Bounds(9007199254740993L, 9007199254740993L)
      assert(evaluate(Arithmetic.Add, beyondDouble, two) ==
        Bounds(9007199254740995L, 9007199254740995L))
      rejected(Arithmetic.Add, ProblemKind.BoundOverflow, maximum, one)
      rejected(Arithmetic.Add, ProblemKind.BoundOverflow, minimum, negativeOne)
      rejected(Arithmetic.Subtract, ProblemKind.BoundOverflow, minimum, one)
      rejected(Arithmetic.Multiply, ProblemKind.BoundOverflow, maximum, two)
      rejected(Arithmetic.Multiply, ProblemKind.BoundOverflow, minimum, negativeOne)
      rejected(Arithmetic.Negate, ProblemKind.BoundOverflow, minimum)

    test("division truncates toward zero for either operand sign"):
      for (left, right, expected) <- Vector(
          (7L, 2L, 3L),
          (-7L, 2L, -3L),
          (7L, -2L, -3L),
          (-7L, -2L, 3L),
          (1L, -2L, 0L)
        )
      do
        assert(evaluate(Arithmetic.Divide, Bounds(left, left), Bounds(right, right)) ==
          Bounds(expected, expected))
      val value = evaluate(
        Arithmetic.Divide,
        Bounds(Long.MaxValue, Long.MaxValue),
        Bounds(3L, 3L)
      )
      val expected = BigInt(Long.MaxValue) / 3
      assert(BigInt(value.lower) == expected, BigInt(value.upper) == expected)
      rejected(
        Arithmetic.Divide,
        ProblemKind.BoundOverflow,
        Bounds(Long.MinValue, Long.MinValue + 1L),
        Bounds(-2L, -1L)
      )

    test("a possible zero divisor rejects even when the numerator is provably zero"):
      for divisor <- Vector(Bounds(0L, 0L), Bounds(-1L, 0L), Bounds(0L, 1L), Bounds(-3L, 3L)) do
        rejected(Arithmetic.Divide, ProblemKind.ZeroDivisor, Bounds(0L, 0L), divisor)
        rejected(Arithmetic.Divide, ProblemKind.ZeroDivisor, Bounds(7L, 9L), divisor)

    test("invalid operation arities return a diagnostic rather than reading absent operands"):
      for
        operation <- Arithmetic.values.toVector
        count <- 0 to 3
        expected = if operation == Arithmetic.Negate then 1 else 2
        if count != expected
      do rejected(operation, ProblemKind.InvalidArithmetic, Vector.fill(count)(Bounds(1L, 2L))*)

    test("structural distance remains wide while signed expression overflow is still rejected"):
      val lower = Bounds(Long.MinValue, Long.MinValue)
      val upper = Bounds(Long.MaxValue, Long.MaxValue)
      val step = Bounds(Long.MaxValue, Long.MaxValue)
      val actual = IterationDomain.structural(lower, upper, step, Some(3)).toOption.get
      val distance = BigInt(Long.MaxValue) - BigInt(Long.MinValue)
      val expected = (distance + BigInt(Long.MaxValue) - 1) / BigInt(Long.MaxValue)
      assert(BigInt(actual.maximumTripCount) == expected, actual.maximumTripCount == 3)
      assert(IterationDomain.structural(lower, upper, step, Some(2))
        .left.toOption.exists(_.kind == ProblemKind.MaximumExceeded))
      assert(IterationDomain.structural(lower, upper, Bounds(1L, 1L), None)
        .left.toOption.exists(_.kind == ProblemKind.CountOverflow))
      rejected(Arithmetic.Subtract, ProblemKind.BoundOverflow, upper, lower)

    test("structural envelope bounds contain every legal small concrete domain"):
      for
        lower <- intervals
        upper <- intervals
        if lower.upper <= upper.lower
        stepLower <- 1 to 3
        stepUpper <- stepLower to 3
      do
        val steps = Bounds(stepLower.toLong, stepUpper.toLong)
        val actual = IterationDomain.structural(lower, upper, steps, None).toOption.get
        val counts =
          for
            first <- lower.lower.toInt to lower.upper.toInt
            end <- upper.lower.toInt to upper.upper.toInt
            step <- stepLower to stepUpper
          yield (first until end by step).size
        assert(actual.maximumTripCount == counts.max)
        assert(IterationDomain.structural(lower, upper, steps, Some(counts.max)).isRight)
        if counts.max > 0 then
          assert(IterationDomain.structural(lower, upper, steps, Some(counts.max - 1))
            .left.toOption.exists(_.kind == ProblemKind.MaximumExceeded))

    test("identical symbolic bounds remain empty without weakening step or maximum checks"):
      val bounds = Bounds(Long.MinValue, Long.MaxValue)
      val step = Bounds(1L, 1L)
      val actual = IterationDomain.structural(bounds, bounds, step, Some(0), true).toOption.get
      assert(actual.maximumTripCount == 0)
      assert(IterationDomain.structural(bounds, bounds, Bounds(0L, 1L), Some(0), true)
        .left.toOption.exists(_.kind == ProblemKind.InvalidStep))
      assert(IterationDomain.structural(bounds, bounds, step, Some(-1), true)
        .left.toOption.exists(_.kind == ProblemKind.NegativeMaximum))
