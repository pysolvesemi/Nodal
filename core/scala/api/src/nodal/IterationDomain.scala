package nodal

/** Arithmetic for retained iteration domains, independent of topology and procedural effects.
  *
  * This owner does not allocate lanes, invoke a body, or decide staging. Integer interval
  * operations retain conservative bounds rather than evaluating overridable parameter defaults.
  * Capturing a source expression DAG and validating its ownership remain construction obligations.
  */
private[nodal] object IterationDomain:
  enum ProblemKind:
    case InvalidStep, NegativeMaximum, NegativeCount, CountOverflow, MaximumExceeded
    case InvalidDirection, UnboundedSymbolicDomain, OrdinalOutOfBounds
    case InvalidArithmetic, BoundOverflow, ZeroDivisor

  final case class Problem(kind: ProblemKind, message: String)

  /** A validated, half-open domain whose count fits the existing 32-bit loop metadata. */
  final class Static private[IterationDomain] (
      val lower: Int,
      val upperExclusive: Int,
      val step: Int,
      val tripCount: Int
  ):
    def isEmpty: Boolean = tripCount == 0

    def lastValue: Option[Int] =
      Option.when(!isEmpty)((lower.toLong + (tripCount.toLong - 1L) * step.toLong).toInt)

    /** The first excluded induction value. Keep it wide even when every body value fits Int. */
    def exitValue: Long = lower.toLong + tripCount.toLong * step.toLong

    def valueAt(ordinal: Int): Either[Problem, Int] =
      if ordinal < 0 || ordinal >= tripCount then
        Left(Problem(ProblemKind.OrdinalOutOfBounds, "iteration ordinal is outside the domain"))
      else Right((lower.toLong + ordinal.toLong * step.toLong).toInt)

  /** Shared count check for concrete repetition and a conservatively bounded structural domain. */
  private def positiveTripCount(
      distance: BigInt,
      step: Long,
      maximum: Option[Int]
  ): Either[Problem, Int] =
    val count = if distance <= 0 then BigInt(0) else 1 + (distance - 1) / BigInt(step)
    if count > Int.MaxValue then
      Left(Problem(ProblemKind.CountOverflow, "iteration count exceeds 32-bit loop metadata"))
    else if maximum.exists(limit => count > limit) then
      Left(Problem(ProblemKind.MaximumExceeded, "iteration count exceeds its declared maximum"))
    else Right(count.toInt)

  /** Positive steps only. Equal or reversed bounds are legal empty iteration domains, not shapes.
    */
  def halfOpen(
      lower: Int,
      upperExclusive: Int,
      step: Int = 1,
      maximum: Option[Int] = None
  ): Either[Problem, Static] =
    if step <= 0 then
      Left(Problem(ProblemKind.InvalidStep, "iteration step must be positive"))
    else if maximum.exists(_ < 0) then
      Left(Problem(ProblemKind.NegativeMaximum, "iteration maximum must be non-negative"))
    else
      positiveTripCount(BigInt(upperExclusive) - BigInt(lower), step.toLong, maximum)
        .map(count => new Static(lower, upperExclusive, step, count))

  /** Closed signed-integer interval, not a source expression or mutable registry. */
  final case class Bounds(lower: Long, upper: Long):
    require(lower <= upper, "iteration bound interval must be ordered")

  enum Arithmetic:
    case Add, Subtract, Multiply, Divide, Negate, Maximum, CeilLog2, ChooseZero

    def arity: Int = this match
      case Negate | CeilLog2 => 1
      case ChooseZero => 3
      case _ => 2

  /** Captured source opcodes have one closed mapping to the shared interval arithmetic. */
  def staticArithmetic(operation: String): Option[Arithmetic] = operation match
    case "analog_add" => Some(Arithmetic.Add)
    case "analog_sub" => Some(Arithmetic.Subtract)
    case "analog_mul" => Some(Arithmetic.Multiply)
    case "analog_div" => Some(Arithmetic.Divide)
    case "analog_neg" => Some(Arithmetic.Negate)
    case "static_integer_max" => Some(Arithmetic.Maximum)
    case "static_integer_ceil_log2" => Some(Arithmetic.CeilLog2)
    case "static_integer_choose_zero" => Some(Arithmetic.ChooseZero)
    case _ => None

  /** Wide intermediates also serve trip-count proofs, where distance can exceed signed 64 bits. */
  private def wideArithmetic(
      operation: Arithmetic,
      operands: Vector[Bounds]
  ): Either[Problem, (BigInt, BigInt)] =
    if operands.size != operation.arity then
      Left(Problem(ProblemKind.InvalidArithmetic, "integer interval operation has invalid arity"))
    else
      val left = operands.head
      val lower = BigInt(left.lower)
      val upper = BigInt(left.upper)
      if operation == Arithmetic.Negate then Right((-upper, -lower))
      else if operation == Arithmetic.CeilLog2 then
        if left.lower <= 0L then
          Left(Problem(
            ProblemKind.InvalidArithmetic,
            "ceil_log2 requires a positive Integer envelope"
          ))
        else Right((BigInt((lower - 1).bitLength), BigInt((upper - 1).bitLength)))
      else if operation == Arithmetic.ChooseZero then
        val zero = operands(1)
        val nonzero = operands(2)
        val result =
          if left.lower == 0L && left.upper == 0L then zero
          else if left.lower > 0L || left.upper < 0L then nonzero
          else Bounds(zero.lower.min(nonzero.lower), zero.upper.max(nonzero.upper))
        Right((BigInt(result.lower), BigInt(result.upper)))
      else
        val right = operands(1)
        val rightLower = BigInt(right.lower)
        val rightUpper = BigInt(right.upper)
        operation match
          case Arithmetic.Add => Right((lower + rightLower, upper + rightUpper))
          case Arithmetic.Subtract => Right((lower - rightUpper, upper - rightLower))
          case Arithmetic.Maximum => Right((lower.max(rightLower), upper.max(rightUpper)))
          case Arithmetic.Multiply =>
            val values = Vector(
              lower * rightLower,
              lower * rightUpper,
              upper * rightLower,
              upper * rightUpper
            )
            Right((values.min, values.max))
          case Arithmetic.Divide =>
            if right.lower <= 0L && right.upper >= 0L then
              Left(Problem(ProblemKind.ZeroDivisor, "integer interval divisor can include zero"))
            else
              val values = Vector(
                lower / rightLower,
                lower / rightUpper,
                upper / rightLower,
                upper / rightUpper
              )
              Right((values.min, values.max))
          case Arithmetic.Negate | Arithmetic.CeilLog2 | Arithmetic.ChooseZero =>
            Left(Problem(ProblemKind.InvalidArithmetic, "invalid binary Integer operation"))

  /** Conservative signed-64-bit arithmetic, matching the Integer constant-expression envelope.
    *
    * Operands are independent intervals: no equal-value correlation is inferred from equal bounds.
    * Division truncates toward zero. Reject any possible zero divisor or signed overflow instead of
    * narrowing, saturating, using floating point, or accepting only a favorable default setting.
    */
  def arithmetic(
      operation: Arithmetic,
      operands: Vector[Bounds]
  ): Either[Problem, Bounds] =
    wideArithmetic(operation, operands).flatMap: (lower, upper) =>
      if !lower.isValidLong || !upper.isValidLong then
        Left(Problem(ProblemKind.BoundOverflow, "integer interval exceeds signed 64-bit bounds"))
      else Right(Bounds(lower.toLong, upper.toLong))

  final case class Structural(
      lower: Bounds,
      upperExclusive: Bounds,
      step: Bounds,
      maximum: Option[Int],
      maximumTripCount: Int
  )

  /** Prove a positive, finite half-open structural domain without substituting parameter defaults.
    *
    * The outer endpoints are conservative: the smallest possible lower bound, largest possible
    * upper bound, and smallest possible positive step establish the maximum materialized count. A
    * finite parameter range is therefore enough to prove an envelope even when maximum is omitted.
    * identicalBounds preserves the legal always-empty hdlRange(p, p) case.
    */
  def structural(
      lower: Bounds,
      upperExclusive: Bounds,
      step: Bounds,
      maximum: Option[Int],
      identicalBounds: Boolean = false
  ): Either[Problem, Structural] =
    if step.lower <= 0L then
      Left(Problem(ProblemKind.InvalidStep, "structural iteration step must stay positive"))
    else if maximum.exists(_ < 0) then
      Left(Problem(ProblemKind.NegativeMaximum, "iteration maximum must be non-negative"))
    else if !identicalBounds && lower.upper > upperExclusive.lower then
      Left(
        Problem(
          ProblemKind.InvalidDirection,
          "symbolic structural bounds do not prove one half-open direction for every legal setting"
        )
      )
    else
      wideArithmetic(Arithmetic.Subtract, Vector(upperExclusive, lower)).flatMap: (_, distance) =>
        positiveTripCount(if identicalBounds then BigInt(0) else distance, step.lower, maximum)
          .map(count => Structural(lower, upperExclusive, step, maximum, count))

  /** A selected family ordinal must exist for every legal setting, never only the default. */
  def verifyOrdinal(domain: Structural, ordinal: Bounds): Either[Problem, Unit] =
    val distance = BigInt(domain.upperExclusive.lower) - BigInt(domain.lower.upper)
    positiveTripCount(distance, domain.step.upper, None).flatMap: minimum =>
      if ordinal.lower < 0L || ordinal.upper >= minimum.toLong then
        Left(Problem(
          ProblemKind.OrdinalOutOfBounds,
          "generated ordinal does not exist for every legal parameter setting"
        ))
      else Right(())

  /** Conservative attained values for a validated positive domain. Empty domains have no value.
    * Exact fixed endpoints retain the last strided value instead of filling the excluded tail.
    */
  def attainedBounds(domain: Structural): Option[Bounds] =
    Option.when(domain.maximumTripCount > 0):
      val last =
        (BigInt(domain.lower.upper) +
          (BigInt(domain.maximumTripCount) - 1) * domain.step.upper)
          .min(BigInt(domain.upperExclusive.upper) - 1)
      Bounds(domain.lower.lower, last.toLong)

  /** Finite same-cycle digital domains retain literal reversed emptiness and require one fixed
    * positive step. The structural owner remains authoritative for symbolic direction and counts.
    */
  def boundedDigital(
      lower: Bounds,
      upperExclusive: Bounds,
      step: Bounds,
      maximum: Option[Int],
      identicalBounds: Boolean = false
  ): Either[Problem, Structural] =
    if step.lower != step.upper then
      Left(Problem(ProblemKind.InvalidStep, "digital iteration step must be invariant"))
    else
      val literalEmpty = lower.lower == lower.upper &&
        upperExclusive.lower == upperExclusive.upper && lower.lower >= upperExclusive.upper
      structural(lower, upperExclusive, step, maximum, identicalBounds || literalEmpty)

  /** Repetition has an explicit count: a negative count is invalid, not an empty range. */
  def repeat(iterations: Int): Either[Problem, Static] =
    if iterations < 0 then
      Left(Problem(ProblemKind.NegativeCount, "bounded loop requires a non-negative trip count"))
    else halfOpen(0, iterations)
