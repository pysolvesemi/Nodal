package nodal

/** Arithmetic for retained iteration domains, independent of topology and procedural effects.
  *
  * This initial concrete domain does not allocate lanes, invoke a body, or decide staging. Symbolic
  * parameter bounds and generated-object ownership remain separate construction obligations.
  */
private[nodal] object IterationDomain:
  enum ProblemKind:
    case InvalidStep, NegativeMaximum, NegativeCount, CountOverflow, MaximumExceeded
    case InvalidDirection, UnboundedSymbolicDomain, OrdinalOutOfBounds

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
      val distance = upperExclusive.toLong - lower.toLong
      val count = if distance <= 0L then 0L else 1L + (distance - 1L) / step.toLong
      if count > Int.MaxValue.toLong then
        Left(Problem(ProblemKind.CountOverflow, "iteration count exceeds 32-bit loop metadata"))
      else if maximum.exists(limit => count > limit.toLong) then
        Left(Problem(ProblemKind.MaximumExceeded, "iteration count exceeds its declared maximum"))
      else Right(new Static(lower, upperExclusive, step, count.toInt))

  /** Closed interval used only for proving the finite envelope of a symbolic structural bound. */
  final case class Bounds(lower: Int, upper: Int):
    require(lower <= upper, "iteration bound interval must be ordered")

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
    * upper bound, and smallest possible positive step establish the maximum materialized count.
    * A finite parameter range is therefore enough to prove an envelope even when maximum is
    * omitted. identicalBounds preserves the legal always-empty hdlRange(p, p) case.
    */
  def structural(
      lower: Bounds,
      upperExclusive: Bounds,
      step: Bounds,
      maximum: Option[Int],
      identicalBounds: Boolean = false
  ): Either[Problem, Structural] =
    if step.lower <= 0 then
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
      val distance =
        if identicalBounds then 0L
        else upperExclusive.upper.toLong - lower.lower.toLong
      val count =
        if distance <= 0L then 0L
        else 1L + (distance - 1L) / step.lower.toLong
      if count > Int.MaxValue.toLong then
        Left(Problem(ProblemKind.CountOverflow, "iteration count exceeds 32-bit loop metadata"))
      else if maximum.exists(limit => count > limit.toLong) then
        Left(Problem(ProblemKind.MaximumExceeded, "iteration count exceeds its declared maximum"))
      else Right(Structural(lower, upperExclusive, step, maximum, count.toInt))

  /** Repetition has an explicit count: a negative count is invalid, not an empty range. */
  def repeat(iterations: Int): Either[Problem, Static] =
    if iterations < 0 then
      Left(Problem(ProblemKind.NegativeCount, "bounded loop requires a non-negative trip count"))
    else halfOpen(0, iterations)
