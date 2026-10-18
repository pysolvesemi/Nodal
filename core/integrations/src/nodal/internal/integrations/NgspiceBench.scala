package nodal.internal.integrations

import java.util.Locale

private[nodal] enum NgspiceTerminal:
  case Ground, Driven

/** Terminal order is the model declaration order, never inferred from a sign convention. */
private[nodal] final case class NgspiceModuleBinding(
    moduleName: String,
    terminals: Vector[NgspiceTerminal],
    parameters: Vector[(String, Double)] = Vector.empty
)

private[nodal] final case class NgspiceSolverOptions(
    reltol: Double = 1e-6,
    abstol: Double = 1e-12,
    vntol: Double = 1e-9,
    temp: Double = 27,
    tnom: Double = 27
)

private[nodal] enum NgspiceCurrentStimulus:
  case Ramp(finalAmps: Double, delaySeconds: Double, riseSeconds: Double)
  case Pulse(
      initialAmps: Double,
      pulsedAmps: Double,
      delaySeconds: Double,
      riseSeconds: Double,
      fallSeconds: Double,
      widthSeconds: Double,
      periodSeconds: Double
  )

private[nodal] enum NgspiceAnalysis:
  case DcVoltageSweep(startVolts: Double, stopVolts: Double, stepVolts: Double)
  case AcLinearSweep(points: Int, startHz: Double, stopHz: Double)
  case AcDecadeSweep(pointsPerDecade: Int, startHz: Double, stopHz: Double)
  case TransientCurrent(
      stimulus: NgspiceCurrentStimulus,
      stepSeconds: Double,
      stopSeconds: Double
  )

  def kind: NgspiceAnalysisKind = this match
    case DcVoltageSweep(_, _, _) => NgspiceAnalysisKind.Dc
    case AcLinearSweep(_, _, _) | AcDecadeSweep(_, _, _) => NgspiceAnalysisKind.Ac
    case TransientCurrent(_, _, _) => NgspiceAnalysisKind.Transient

/** requestedPoints is the nominal grid size, not an adaptive transient output guarantee. */
private[nodal] final case class NgspiceGeneratedBench(
    deck: String,
    kind: NgspiceAnalysisKind,
    requestedPoints: Int
)

/** Finite two-terminal ngspice 47 benches, not a public simulation or circuit-description API.
  *
  * No caller-supplied deck, control command, path, node name or expression is accepted. Grid
  * cardinality is checked arithmetically before rendering; rendering never expands grid points. The
  * adapter owns isolated startup/OSDI loading, process execution and bounded raw parsing.
  */
private[nodal] object NgspiceBench:
  private val MaxPoints = 100000
  private val MaxParameters = 128
  private val MaxIdentifierLength = 128
  private val Identifier = "[A-Za-z_][A-Za-z0-9_]*".r
  private val DcStopSlack = Math.ulp(1.0) * 1000

  def generate(
      model: NgspiceModuleBinding,
      analysis: NgspiceAnalysis,
      options: NgspiceSolverOptions = NgspiceSolverOptions()
  ): Either[String, NgspiceGeneratedBench] =
    for
      _ <- validateModel(model)
      _ <- validateOptions(options)
      points <- validateAnalysis(analysis, options)
    yield
      val terminals = model.terminals.map:
        case NgspiceTerminal.Ground => "0"
        case NgspiceTerminal.Driven => "p"
      val parameters = model.parameters.sortBy(_._1.toLowerCase(Locale.ROOT))
        .map((name, value) => s"$name=${number(value)}").mkString(" ")
      val modelLine = s".model dut ${model.moduleName}" +
        (if parameters.isEmpty then "" else s" ($parameters)")
      val optionLine = s".options reltol=${number(options.reltol)} abstol=${number(options.abstol)}" +
        s" vntol=${number(options.vntol)} temp=${number(options.temp)} tnom=${number(options.tnom)}"
      val (source, commands) = renderAnalysis(analysis)
      val deck =
        (Vector(
          "Nodal finite two-terminal ngspice bench",
          source,
          s"Nload ${terminals.mkString(" ")} dut",
          modelLine,
          optionLine
        ) ++ commands :+ ".end").mkString("", "\n", "\n")
      NgspiceGeneratedBench(deck, analysis.kind, points)

  private def validateModel(model: NgspiceModuleBinding): Either[String, Unit] =
    for
      _ <- check(validIdentifier(model.moduleName), "invalid module identifier")
      _ <- check(
        model.terminals.size == 2 && model.terminals.toSet == Set(
          NgspiceTerminal.Ground,
          NgspiceTerminal.Driven
        ),
        "terminal binding must contain exactly one Ground and one Driven in declaration order"
      )
      _ <- check(model.parameters.size <= MaxParameters, s"parameter count exceeds $MaxParameters")
      _ <- check(
        model.parameters.forall((name, _) => validIdentifier(name)),
        "invalid parameter identifier"
      )
      _ <- check(model.parameters.forall((_, value) => value.isFinite), "parameters must be finite")
      names = model.parameters.map(_._1.toLowerCase(Locale.ROOT))
      _ <-
        check(names.distinct.size == names.size, "duplicate case-insensitive parameter identifier")
    yield ()

  private def validateOptions(options: NgspiceSolverOptions): Either[String, Unit] =
    for
      _ <- check(
        positive(options.reltol) && options.reltol <= 1e-3 &&
          positive(options.abstol) && positive(options.vntol),
        "solver tolerances must be finite and positive, with reltol <= 0.001"
      )
      _ <- check(
        options.temp.isFinite && options.temp > -273.15 &&
          options.tnom.isFinite && options.tnom > -273.15,
        "temp and tnom must be finite Celsius temperatures above absolute zero"
      )
    yield ()

  private def validateAnalysis(
      analysis: NgspiceAnalysis,
      options: NgspiceSolverOptions
  ): Either[String, Int] = analysis match
    case NgspiceAnalysis.DcVoltageSweep(start, stop, step) =>
      for
        _ <- check(
          start.isFinite && stop.isFinite && step.isFinite && step != 0,
          "DC values must be finite with nonzero step"
        )
        _ <- check(start == stop || (stop > start) == (step > 0), "DC step must point toward stop")
        count <-
          boundedCount((decimal(stop) - decimal(start)).abs / decimal(step).abs, roundUp = false)
        next = decimal(start) + decimal(step) * count
        _ <- check(
          (next - decimal(stop)).abs > decimal(DcStopSlack) &&
            (start + step).isFinite && start + step != start && stop - step != stop,
          "DC grid is ambiguous at ngspice stop tolerance or floating-point resolution"
        )
      yield count

    case NgspiceAnalysis.AcLinearSweep(points, start, stop) =>
      for
        _ <- frequencies(start, stop)
        _ <- check(
          (points == 1 && start == stop) || (points >= 3 && points <= MaxPoints && start < stop),
          s"AC linear grid needs one equal-endpoint point or 3 to $MaxPoints distinct-endpoint points"
        )
        delta = if points == 1 then 0.0 else (stop - start) / (points - 1)
        _ <- check(
          points == 1 || (positive(delta) && start + delta > start && stop - delta < stop),
          "AC linear grid is below floating-point resolution"
        )
      yield points

    case NgspiceAnalysis.AcDecadeSweep(pointsPerDecade, start, stop) =>
      for
        _ <- frequencies(start, stop)
        _ <- check(
          pointsPerDecade > 0 && pointsPerDecade <= MaxPoints && stop > start,
          "AC decade density and span must be positive and bounded"
        )
        ratio = stop / start
        decades = Math.log10(ratio)
        integerDecades = Math.rint(decades)
        _ <- check(
          ratio.isFinite && integerDecades >= 1 && decades == integerDecades,
          "AC decade grid supports only finite positive integer-decade spans"
        )
        count <- boundedCount(decimal(integerDecades) * pointsPerDecade, roundUp = false)
        factor = Math.pow(10, 1.0 / pointsPerDecade)
        _ <- check(
          options.reltol < (1 - 1 / factor) / 2 &&
            (stop * factor).isFinite && start * factor > start,
          "AC decade grid is ambiguous at solver tolerance or floating-point resolution"
        )
      yield count

    case NgspiceAnalysis.TransientCurrent(stimulus, step, stop) =>
      for
        _ <- check(
          positive(step) && positive(stop) && step <= stop,
          "transient step and stop must be finite and positive with step <= stop"
        )
        count <- boundedCount(decimal(stop) / decimal(step), roundUp = true)
        _ <- validateStimulus(stimulus, stop)
      yield count

  private def validateStimulus(
      stimulus: NgspiceCurrentStimulus,
      stop: Double
  ): Either[String, Unit] =
    stimulus match
      case NgspiceCurrentStimulus.Ramp(amplitude, delay, rise) =>
        check(
          amplitude.isFinite && nonnegative(delay) && positive(rise) &&
            (delay + rise).isFinite && delay + rise > delay && delay + rise <= stop,
          "current ramp needs finite amplitude and a positive rise ending within the transient"
        )
      case NgspiceCurrentStimulus.Pulse(initial, pulsed, delay, rise, fall, width, period) =>
        for
          _ <- check(
            initial.isFinite && pulsed.isFinite && nonnegative(delay) && delay < stop &&
              positive(rise) && positive(fall) && positive(width) && positive(period),
            "current pulse needs finite amplitudes, a delay within the transient and positive finite durations"
          )
          duration = rise + width + fall
          _ <- check(
            duration.isFinite && period >= duration &&
              (delay + duration).isFinite &&
              delay + rise > delay && delay + rise + width > delay + rise &&
              delay + duration > delay + rise + width,
            "current pulse durations must be ordered, representable and fit within the period"
          )
          _ <- check(
            ((decimal(stop) - decimal(delay)) / decimal(period)).setScale(
              0,
              BigDecimal.RoundingMode.CEILING
            ) * 4 <= MaxPoints,
            s"current pulse requested transitions exceed $MaxPoints"
          )
        yield ()

  private def renderAnalysis(analysis: NgspiceAnalysis): (String, Vector[String]) = analysis match
    case NgspiceAnalysis.DcVoltageSweep(start, stop, step) =>
      (
        "Vdrive p 0 0",
        Vector(
          ".save v(p) i(Vdrive)",
          s".dc Vdrive ${number(start)} ${number(stop)} ${number(step)}"
        )
      )
    case NgspiceAnalysis.AcLinearSweep(points, start, stop) =>
      (
        "Vdrive p 0 DC 0 AC 1",
        Vector(".save v(p) i(Vdrive)", s".ac lin $points ${number(start)} ${number(stop)}")
      )
    case NgspiceAnalysis.AcDecadeSweep(points, start, stop) =>
      (
        "Vdrive p 0 DC 0 AC 1",
        Vector(".save v(p) i(Vdrive)", s".ac dec $points ${number(start)} ${number(stop)}")
      )
    case NgspiceAnalysis.TransientCurrent(stimulus, step, stop) =>
      val source = stimulus match
        case NgspiceCurrentStimulus.Ramp(amplitude, delay, rise) =>
          val beginning = Vector(0.0 -> 0.0) ++
            (if delay == 0 then Vector.empty else Vector(delay -> 0.0))
          val points = beginning :+ ((delay + rise) -> amplitude)
          val held = points ++
            (if delay + rise == stop then Vector.empty else Vector(stop -> amplitude))
          s"Idrive 0 p PWL(${held.map((time, value) => s"${number(time)} ${number(value)}").mkString(" ")})"
        case NgspiceCurrentStimulus.Pulse(initial, pulsed, delay, rise, fall, width, period) =>
          s"Idrive 0 p PULSE(${Vector(initial, pulsed, delay, rise, fall, width, period).map(number).mkString(" ")})"
      (
        source,
        Vector(
          ".ic v(p)=0",
          ".save v(p)",
          s".tran ${number(step)} ${number(stop)} 0 ${number(step)} uic"
        )
      )

  private def boundedCount(intervals: BigDecimal, roundUp: Boolean): Either[String, Int] =
    val rounding =
      if roundUp then BigDecimal.RoundingMode.CEILING else BigDecimal.RoundingMode.FLOOR
    val count = intervals.setScale(0, rounding) + 1
    check(
      count >= 1 && count <= MaxPoints,
      s"requested grid must contain 1 to $MaxPoints points"
    ).map(_ => count.toInt)

  private def frequencies(start: Double, stop: Double): Either[String, Unit] =
    check(
      positive(start) && positive(stop) && stop >= start,
      "AC frequencies must be finite, positive and nondecreasing"
    )

  private def validIdentifier(value: String): Boolean =
    Option(value).exists(name => name.length <= MaxIdentifierLength && Identifier.matches(name))

  private def positive(value: Double): Boolean = value.isFinite && value > 0
  private def nonnegative(value: Double): Boolean = value.isFinite && value >= 0
  private def decimal(value: Double): BigDecimal = BigDecimal.decimal(value)
  private def number(value: Double): String = java.lang.Double.toString(value)
  private def check(condition: Boolean, message: String): Either[String, Unit] =
    Either.cond(condition, (), message)
