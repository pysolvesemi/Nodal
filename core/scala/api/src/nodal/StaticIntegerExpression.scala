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
