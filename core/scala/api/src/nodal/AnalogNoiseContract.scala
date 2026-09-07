package nodal

/** Small-signal noise sources are effects, not pure real functions. */
private[nodal] object AnalogNoiseContract:
  val Prefix = "analog_noise_v1_"

  private[nodal] def fail(number: Int, message: String): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic(f"NODAL-ANALOG-039-$number%03d", message))
    ).get

  def validateOptions(id: NoiseId, options: NoiseOptions): Unit =
    // This portable label profile deliberately excludes target escapes and controls.
    // Labels group reports; they never create source correlation.
    if id.value.isEmpty || id.value.exists(c => c < ' ' || c > '~' || c == '"' || c == '\\') then
      fail(5, "noise reporting labels must be nonempty printable ASCII without quotes or backslashes")
    if options.correlation != NoiseCorrelation.Independent then
      fail(6, "correlation groups are unsupported; reuse one noise expression for shared-source correlation")
    if options.analyses.values != Set(AnalysisKind.Noise) then
      fail(6, "this profile supports small-signal Noise analysis only, not transient noise")

  def resultDimension(spectralDensity: String): String =
    if spectralDensity == "unknown" || spectralDensity.isEmpty then
      fail(3, "noise spectral density requires a known physical dimension")
    val powers =
      if spectralDensity == "1" then Map.empty[String, Int]
      else spectralDensity.split("\\*").toVector.map: factor =>
        val pieces = factor.split("\\^", 2)
        pieces(0) -> (if pieces.length == 1 then 1 else pieces(1).toInt)
      .toMap
    val variance = powers.updated("time", powers.getOrElse("time", 0) - 1).filter(_._2 != 0)
    if variance.values.exists(_ % 2 != 0) then
      fail(3, "spectral density must have result-squared per hertz dimensions")
    val factors = variance.toVector.sortBy(_._1).map: (base, exponent) =>
      if exponent / 2 == 1 then base else s"$base^${exponent / 2}"
    if factors.isEmpty then "1" else factors.mkString("*")

  def validate(kind: String, inputs: Vector[Expr[Real]], dimensions: Vector[String],
      constants: Vector[Option[Double]]): String =
    val arity = kind match
      case "white" => inputs.size == 1
      case "flicker" => inputs.size == 2
      case "table" => inputs.nonEmpty && inputs.size % 2 == 0
      case _ => false
    if !arity then fail(2, "unknown noise kind or invalid argument count")
    if dimensions.exists(d => d.isEmpty || d == "unknown") ||
      inputs.exists(v => CandidateRuntime.expressionDataType(v).exists(_ != Real)) then
      fail(3, "noise operands must be real quantities with known physical dimensions")
    if constants.flatten.exists(v => !v.isFinite) then fail(4, "noise arguments must be finite")
    val densityIndices = if kind == "table" then inputs.indices.filter(_ % 2 == 1) else Seq(0)
    if densityIndices.exists(i => constants(i).exists(_ < 0.0)) then
      fail(4, "noise spectral density must be nonnegative")
    val dimension = dimensions(densityIndices.head)
    if densityIndices.exists(i => dimensions(i) != dimension) then
      fail(3, "all table spectral densities must have equal dimensions")
    if kind == "flicker" then
      if dimensions(1) != "1" then fail(3, "flicker exponent must be dimensionless")
      if constants(1).isEmpty then fail(6, "flicker exponent must be a proven constant")
    if kind == "table" then
      if inputs.indices.filter(_ % 2 == 0).exists(i => dimensions(i) != "time^-1") then
        fail(3, "noise table frequencies require inverse-time dimensions")
      if constants.exists(_.isEmpty) then
        fail(6, "noise table points must be proven constants, not parameter defaults or runtime values")
      val frequencies = constants.indices.filter(_ % 2 == 0).map(i => constants(i).get)
      if frequencies.exists(_ < 0.0) || frequencies.distinct.size != frequencies.size then
        fail(4, "noise table frequencies must be nonnegative and unique")
    resultDimension(dimension)

  def call(kind: String, id: NoiseId, inputs: Vector[Expr[Real]],
      options: NoiseOptions): Expr[Real] =
    validateOptions(id, options)
    val expression = new KernelExpr[Real](
      inputs,
      resultType = Some(KernelTypeDescriptor("Real")),
      operation = Some(Prefix + kind)
    )
    ConstructionKernel.noiseOperator(expression, kind, id.value, inputs)
    expression
