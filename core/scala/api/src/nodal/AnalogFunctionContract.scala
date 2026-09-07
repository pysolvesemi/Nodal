package nodal

/** Independent frontend validation of the versioned function registry. */
private[nodal] object AnalogFunctionContract:
  private def fail(number: Int, message: String): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic(f"NODAL-ANALOG-038-$number%03d", message))
    ).get

  def entry(id: String): AnalogFunctionRegistry.Entry =
    AnalogFunctionRegistry.entries.find(_.id == id).getOrElse(fail(1, s"unknown function '$id'"))

  def dimension(id: String, dimensions: Vector[String]): String =
    val descriptor = entry(id)
    if dimensions.size != descriptor.arity then
      fail(2, s"$id requires ${descriptor.arity} arguments")
    if dimensions.exists(d => d.isEmpty || d == "unknown") then
      fail(3, s"$id requires real operands with known physical dimensions")
    descriptor.dimensionRule match
      case "preserve" => dimensions.head
      case "same" | "ratio" =>
        if dimensions.distinct.size != 1 then fail(3, s"$id arguments must have equal dimensions")
        if descriptor.dimensionRule == "ratio" then "1" else dimensions.head
      case "sqrt" =>
        if dimensions.head == "1" then "1"
        else
          dimensions.head.split("\\*").toVector.map: factor =>
            val pieces = factor.split("\\^", 2)
            val exponent = if pieces.length == 1 then 1 else pieces(1).toInt
            if exponent % 2 != 0 then fail(3, "sqrt requires even physical-dimension exponents")
            val half = exponent / 2
            if half == 1 then pieces(0) else s"${pieces(0)}^$half"
          .mkString("*")
      case "dimensionless" =>
        if dimensions.exists(_ != "1") then fail(3, s"$id requires dimensionless arguments")
        "1"
      case _ => fail(1, "unknown function dimension policy")

  def constant(id: String, values: Vector[Option[Double]]): Option[Double] =
    val descriptor = entry(id)
    if values.size != descriptor.arity then fail(2, s"$id has invalid arity")
    if values.flatten.exists(v => !java.lang.Double.isFinite(v)) then
      fail(4, s"$id arguments must be finite")
    val first = values.head
    val bad = id match
      case "sqrt" => first.exists(_ < 0.0)
      case "ln" | "log10" => first.exists(_ <= 0.0)
      case "asin" | "acos" => first.exists(v => math.abs(v) > 1.0)
      case "acosh" => first.exists(_ < 1.0)
      case "atanh" => first.exists(v => math.abs(v) >= 1.0)
      case "pow" => first.exists(a =>
          values(1).exists(b =>
            (a == 0.0 && b <= 0.0) || (a < 0.0 && b != math.floor(b))
          )
        )
      case _ => false
    if bad then fail(4, s"$id argument is outside its real domain")
    if values.exists(_.isEmpty) then None
    else
      val a = first.get
      val b = values.lift(1).flatten.getOrElse(0.0)
      val result = id match
        case "abs" => math.abs(a)
        case "min" => if a < b then a else b
        case "max" => if a > b then a else b
        case "sqrt" => StrictMath.sqrt(a)
        case "hypot" => StrictMath.hypot(a, b)
        case "atan2" => StrictMath.atan2(a, b)
        case "pow" => StrictMath.pow(a, b)
        case "exp" => StrictMath.exp(a)
        case "ln" => StrictMath.log(a)
        case "log10" => StrictMath.log10(a)
        case "sin" => StrictMath.sin(a)
        case "cos" => StrictMath.cos(a)
        case "tan" => StrictMath.tan(a)
        case "asin" => StrictMath.asin(a)
        case "acos" => StrictMath.acos(a)
        case "atan" => StrictMath.atan(a)
        case "sinh" => StrictMath.sinh(a)
        case "cosh" => StrictMath.cosh(a)
        case "tanh" => StrictMath.tanh(a)
        // Stable at large magnitudes; avoid squaring a finite argument into infinity.
        case "asinh" =>
          val x = math.abs(a)
          val value = if x > 1.0e150 then StrictMath.log(x) + StrictMath.log(2.0)
          else StrictMath.log1p(x + x * (x / (1.0 + StrictMath.hypot(x, 1.0))))
          StrictMath.copySign(value, a)
        case "acosh" =>
          if a > 1.0e150 then StrictMath.log(a) + StrictMath.log(2.0)
          else StrictMath.log1p((a - 1.0) + StrictMath.sqrt(a - 1.0) * StrictMath.sqrt(a + 1.0))
        case "atanh" => 0.5 * (StrictMath.log1p(a) - StrictMath.log1p(-a))
        case "floor" => StrictMath.floor(a)
        case "ceil" => StrictMath.ceil(a)
        case _ => fail(1, s"$id has no constant evaluator")
      if !java.lang.Double.isFinite(result) then fail(4, s"$id constant result is not finite")
      Some(result)

  def call(id: String, arguments: Vector[Expr[Real]]): Expr[Real] =
    val expression = new KernelExpr[Real](
      arguments,
      resultType = Some(KernelTypeDescriptor("Real")),
      operation = Some(AnalogFunctionRegistry.FunctionPrefix + id)
    )
    ConstructionKernel.analogFunction(expression, id)
    expression

  def analysis(kind: AnalysisKind): Expr[Bool] =
    CandidateRuntime.booleanExpr(
      AnalogFunctionRegistry.AnalysisPrefix + AnalogFunctionRegistry.analysisId(kind)
    )
