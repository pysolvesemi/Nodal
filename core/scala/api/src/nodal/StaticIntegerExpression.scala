package nodal

/** Internal construction of static Integer expressions. Captured KernelExpr nodes and the shared
  * structural bound analysis remain authoritative; this helper neither substitutes defaults nor
  * introduces a public runtime Integer operation.
  */
private[nodal] object StaticIntegerExpression:
  private def expression(value: Dimension): Expr[Integer] = value match
    case literal: Int => CandidateRuntime.literalInteger(literal)
    case symbolic: Expr[Integer] => symbolic

  def maximum(left: Dimension, right: Dimension): Dimension = (left, right) match
    case (a: Int, b: Int) => math.max(a, b)
    case _ =>
      CandidateRuntime.integerExpr("static_integer_max", expression(left), expression(right))

  def add(left: Dimension, right: Dimension): Dimension = (left, right) match
    case (a: Int, b: Int) =>
      try Math.addExact(a, b)
      catch
        case _: ArithmeticException =>
          scala.util.Failure[Nothing](
            new ConstructionException(
              KernelDiagnostic(
                "NODAL-PARAMETER-BINDING-022",
                "static Integer literal exceeds signed 32-bit source bounds"
              )
            )
          ).get
    case _ =>
      CandidateRuntime.integerExpr("analog_add", expression(left), expression(right))

  /** Checked metadata constructors deliberately avoid the Int-only literal shortcut in add. */
  def literal64(value: BigInt): Expr[Integer] = CandidateRuntime.derivedInteger(value)

  def add64(left: Dimension, right: Dimension): Expr[Integer] =
    CandidateRuntime.integerExpr("analog_add", expression(left), expression(right))

  def subtract64(left: Dimension, right: Dimension): Expr[Integer] =
    CandidateRuntime.integerExpr("analog_sub", expression(left), expression(right))

  def multiply64(left: Dimension, right: Dimension): Expr[Integer] =
    CandidateRuntime.integerExpr("analog_mul", expression(left), expression(right))

  def ceilLog2(value: Dimension): Expr[Integer] =
    CandidateRuntime.integerExpr("static_integer_ceil_log2", expression(value))

  /** Preserve every dependency until the ordinary static DAG validity check has run. */
  def chooseZero(test: Dimension, whenZero: Dimension, whenNonzero: Dimension): Expr[Integer] =
    CandidateRuntime.integerExpr(
      "static_integer_choose_zero",
      expression(test),
      expression(whenZero),
      expression(whenNonzero)
    )
