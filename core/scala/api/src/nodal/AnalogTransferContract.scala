package nodal

/** A transfer invocation owns state, including when its numerator is zero. */
private[nodal] object AnalogTransferContract:
  val Prefix = "analog_transfer_v1_"

  private[nodal] def fail(number: Int, message: String): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic(f"NODAL-ANALOG-040-$number%03d", message))
    ).get

  def coefficientDimension(kind: String, index: Int): String =
    if kind == "zi_nd" || index == 0 then "1"
    else if index == 1 then "time"
    else s"time^$index"

  def validate(
      kind: String,
      numeratorSize: Int,
      denominatorSize: Int,
      inputs: Vector[Expr[Real]],
      dimensions: Vector[String],
      constants: Vector[Option[Double]],
      static: Vector[Boolean]
  ): String =
    val timingStart = 1 + numeratorSize + denominatorSize
    val timingCount = inputs.size - timingStart
    if numeratorSize <= 0 || denominatorSize <= 0 ||
      !(kind == "laplace_nd" && timingCount == 0 ||
        kind == "zi_nd" && timingCount >= 1 && timingCount <= 3)
    then fail(2, "transfer requires supported ND form and nonempty coefficient arrays")
    if inputs.exists(v => CandidateRuntime.expressionDataType(v).exists(_ != Real)) ||
      dimensions.exists(d => d.isEmpty || d == "unknown")
    then fail(3, "transfer operands must be real quantities with known dimensions")
    if constants.flatten.exists(v => !v.isFinite) then fail(4, "transfer constants must be finite")
    for (offset, size) <- Vector(1 -> numeratorSize, (1 + numeratorSize) -> denominatorSize) do
      for index <- 0 until size do
        if dimensions(offset + index) != coefficientDimension(kind, index) then
          fail(3, "coefficient dimension disagrees with its polynomial power")
        if !static(offset + index) then
          fail(5, "transfer coefficients must remain static throughout an analysis")
    // A default value is not proof for every override. Closed-loop integrators
    // and symbolic d0 envelope proofs are deliberately outside this profile.
    constants(1 + numeratorSize) match
      case Some(value) if value != 0.0 => ()
      case Some(_) => fail(4, "denominator coefficient zero must be nonzero in this profile")
      case None => fail(6, "denominator coefficient zero requires a proven nonzero constant")
    for index <- timingStart until inputs.size do
      if dimensions(index) != "time" then fail(3, "sample timing requires time units")
      val constant = constants(index).getOrElse(
        fail(6, "sample timing requires proven constants, not parameter defaults")
      )
      if index - timingStart < 2 && constant <= 0.0 ||
        index - timingStart == 2 && constant < 0.0
      then fail(4, "interval/transition must be positive and start must be nonnegative")
    dimensions.head

  def call(
      kind: String,
      input: Expr[Real],
      numerator: Seq[Expr[Real]],
      denominator: Seq[Expr[Real]],
      timing: Vector[Expr[Real]]
  ): Expr[Real] =
    // Freeze caller-owned collections before recording the construction graph.
    val n = numerator.toVector
    val d = denominator.toVector
    val inputs = Vector(input) ++ n ++ d ++ timing
    val expression = new KernelExpr[Real](
      inputs,
      resultType = Some(KernelTypeDescriptor("Real")),
      operation = Some(Prefix + kind)
    )
    ConstructionKernel.transferOperator(expression, kind, n.size, d.size, inputs)
    expression
