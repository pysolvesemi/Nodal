package nodal.internal.integrations

import java.util.Locale

/** IDs belong to a declared finite bench; no caller text becomes a SPICE node or source name. */
private[nodal] enum AnalogNode:
  case Ground
  case Node(id: Int)

private[nodal] final case class AnalogSourceId(value: Int)
private[nodal] enum AnalogSourceKind:
  case Voltage, Current

private[nodal] final case class AnalogAcExcitation(magnitude: Double, phaseDegrees: Double = 0)
private[nodal] enum AnalogTransientWaveform:
  case Pulse(
      initial: Double,
      pulsed: Double,
      delaySeconds: Double,
      riseSeconds: Double,
      fallSeconds: Double,
      widthSeconds: Double,
      periodSeconds: Double
  )
  case Pwl(points: Vector[(Double, Double)])

private[nodal] final case class AnalogSource(
    id: AnalogSourceId,
    kind: AnalogSourceKind,
    positive: AnalogNode,
    negative: AnalogNode,
    dc: Double = 0,
    ac: Option[AnalogAcExcitation] = None,
    transient: Option[AnalogTransientWaveform] = None
)

/** Terminals follow the actual emitted module declaration, including repeated node bindings. */
private[nodal] final case class AnalogCircuitBinding(
    moduleName: String,
    terminals: Vector[AnalogNode],
    parameters: Vector[(String, Double)] = Vector.empty
)

private[nodal] enum AnalogCircuitAnalysis:
  case DcSweep(source: AnalogSourceId, start: Double, stop: Double, step: Double)
  case AcLinearSweep(points: Int, startHz: Double, stopHz: Double)
  case AcDecadeSweep(pointsPerDecade: Int, startHz: Double, stopHz: Double)
  case Transient(stepSeconds: Double, stopSeconds: Double, useInitialConditions: Boolean)

  def kind: AnalogAnalysisKind = this match
    case DcSweep(_, _, _, _) => AnalogAnalysisKind.Dc
    case AcLinearSweep(_, _, _) | AcDecadeSweep(_, _, _) => AnalogAnalysisKind.Ac
    case Transient(_, _, _) => AnalogAnalysisKind.Transient

private[nodal] enum AnalogProbe:
  case Voltage(node: AnalogNode)
  case Current(source: AnalogSourceId)

private[nodal] final case class AnalogInitialCondition(node: AnalogNode, volts: Double)

/** One OSDI DUT and bounded independent sources; this is not an arbitrary circuit/deck API. */
private[nodal] final case class AnalogCircuitBench(
    model: AnalogCircuitBinding,
    nodes: Vector[AnalogNode],
    sources: Vector[AnalogSource],
    analysis: AnalogCircuitAnalysis,
    probes: Vector[AnalogProbe],
    initialConditions: Vector[AnalogInitialCondition] = Vector.empty,
    options: AnalogSolverOptions = AnalogSolverOptions()
)

/** Shared axis contract consumed by the same independent raw validator for old and new benches. */
private[nodal] enum AnalogAxis:
  case Dc(start: Double, stop: Double, step: Double)
  case AcLinear(points: Int, start: Double, stop: Double)
  case AcDecade(pointsPerDecade: Int, start: Double, stop: Double)
  case Transient(step: Double, stop: Double)

private[nodal] final case class AnalogSolverOptions(
    reltol: Double = 1e-6,
    abstol: Double = 1e-12,
    vntol: Double = 1e-9,
    temp: Double = 27,
    tnom: Double = 27
)

/** Finite bench semantics shared by consumers; deck syntax and actual output grids are not shared.
  */
private[nodal] object AnalogCircuitValidation:
  private val MaxNodes = 63
  private val MaxSources = 63
  private val MaxTerminals = 64
  private val MaxProbes = 63
  private val MaxWaveformPoints = 100000
  private val MaxParameters = 128
  private val MaxIdentifierLength = 128
  private val Identifier = "[A-Za-z_][A-Za-z0-9_]*".r

  def validate(bench: AnalogCircuitBench): Either[String, Unit] =
    for
      _ <- validateParameters(bench.model.moduleName, bench.model.parameters)
      _ <- validateOptions(bench.options)
      _ <- validateTopology(bench)
      count <- nominalPointCount(axis(bench.analysis))
      _ <- check(
        count.toLong * (bench.probes.size + 1) <= 1000000,
        "requested grid and saved probes exceed the raw value budget"
      )
      _ <- validateSources(bench)
      _ <- validateAnalysis(bench)
    yield ()

  /** An input resource bound, never an assertion about a consumer's emitted grid. */
  def nominalPointCount(axis: AnalogAxis): Either[String, Int] =
    def count(intervals: BigDecimal, roundUp: Boolean = true): Either[String, Int] =
      val rounding =
        if roundUp then BigDecimal.RoundingMode.CEILING else BigDecimal.RoundingMode.FLOOR
      val value = intervals.setScale(0, rounding) + 1
      check(value >= 1 && value <= 100000, "requested grid must contain 1 to 100000 points")
        .map(_ => value.toInt)
    axis match
      case AnalogAxis.Dc(start, stop, step) =>
        for
          _ <- check(
            start.isFinite && stop.isFinite && step.isFinite && step != 0,
            "DC values must be finite with nonzero step"
          )
          _ <-
            check(start == stop || (stop > start) == (step > 0), "DC step must point toward stop")
          value <- count(
            (BigDecimal.decimal(stop) - BigDecimal.decimal(start)).abs /
              BigDecimal.decimal(step).abs,
            roundUp = false
          )
        yield value
      case AnalogAxis.AcLinear(points, start, stop) =>
        check(
          positive(start) && positive(stop) &&
            ((points == 1 && start == stop) || (points >= 2 && points <= 100000 && stop > start)),
          "AC linear grid needs finite positive frequencies and one equal-endpoint or bounded distinct-endpoint points"
        )
          .map(_ => points)
      case AnalogAxis.AcDecade(density, start, stop) =>
        for
          _ <- check(
            density > 0 && density <= 100000 && positive(start) && positive(stop) && stop > start,
            "AC decade density and span must be positive and bounded"
          )
          ratio = stop / start
          decades = if ratio.isFinite then Math.log10(ratio)
          else Math.log10(stop) - Math.log10(start)
          intervals = decades * density
          _ <- check(intervals.isFinite, "AC decade grid exceeds finite range")
          value <- count(BigDecimal.decimal(intervals))
        yield value
      case AnalogAxis.Transient(step, stop) =>
        for
          _ <- check(
            positive(step) && positive(stop) && step <= stop,
            "transient step and stop must be finite and positive with step <= stop"
          )
          value <- count(BigDecimal.decimal(stop) / BigDecimal.decimal(step))
        yield value

  def validateParameters(
      moduleName: String,
      parameters: Vector[(String, Double)]
  ): Either[String, Unit] =
    for
      _ <- check(validIdentifier(moduleName), "invalid module identifier")
      _ <- check(parameters.size <= MaxParameters, s"parameter count exceeds $MaxParameters")
      _ <-
        check(parameters.forall((name, _) => validIdentifier(name)), "invalid parameter identifier")
      _ <- check(parameters.forall((_, value) => value.isFinite), "parameters must be finite")
      names = parameters.map(_._1.toLowerCase(Locale.ROOT))
      _ <-
        check(names.distinct.size == names.size, "duplicate case-insensitive parameter identifier")
    yield ()

  def validateOptions(options: AnalogSolverOptions): Either[String, Unit] =
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

  def validateTopology(bench: AnalogCircuitBench): Either[String, Unit] =
    validateTopologyIdentity(bench).flatMap(_ => validateTopologyConnections(bench))

  /** Separate phases let consumers check private topology expansion before connectivity. */
  private[integrations] def validateTopologyIdentity(bench: AnalogCircuitBench)
      : Either[String, Unit] =
    for
      _ <- check(
        bench.nodes.nonEmpty && bench.nodes.size <= MaxNodes,
        s"declared node count must be 1 to $MaxNodes"
      )
      nodes = bench.nodes.toSet
      declared = (node: AnalogNode) => node == AnalogNode.Ground || nodes.contains(node)
      _ <- check(
        bench.nodes.forall:
          case AnalogNode.Node(id) => id >= 0 && id < MaxNodes
          case AnalogNode.Ground => false
        ,
        "declared nodes must have bounded non-ground IDs"
      )
      _ <- check(nodes.size == bench.nodes.size, "duplicate declared node ID")
      _ <- check(
        bench.model.terminals.nonEmpty && bench.model.terminals.size <= MaxTerminals,
        s"terminal count must be 1 to $MaxTerminals"
      )
      _ <- check(bench.model.terminals.forall(declared), "terminal references an undeclared node")
      _ <- check(
        bench.sources.nonEmpty && bench.sources.size <= MaxSources,
        s"source count must be 1 to $MaxSources"
      )
      sourceIds = bench.sources.map(_.id)
      _ <- check(
        sourceIds.forall(id => id.value >= 0 && id.value < MaxSources),
        "source IDs must be bounded"
      )
      _ <- check(sourceIds.distinct.size == sourceIds.size, "duplicate source ID")
    yield ()

  private[integrations] def validateTopologyConnections(bench: AnalogCircuitBench)
      : Either[String, Unit] =
    val nodes = bench.nodes.toSet
    val declared = (node: AnalogNode) => node == AnalogNode.Ground || nodes.contains(node)
    val sourceIds = bench.sources.map(_.id)
    for
      _ <- check(
        bench.sources.forall(source => declared(source.positive) && declared(source.negative)),
        "source references an undeclared node"
      )
      _ <- check(
        bench.sources.forall(source => source.positive != source.negative),
        "source terminals must be distinct"
      )
      usedNodes = bench.model.terminals.toSet ++ bench.sources.flatMap(source =>
        Vector(source.positive, source.negative)
      )
      _ <- check(nodes.subsetOf(usedNodes), "declared node has no DUT or source connection")
      _ <- check(
        bench.probes.nonEmpty && bench.probes.size <= MaxProbes,
        s"probe count must be 1 to $MaxProbes"
      )
      _ <- check(bench.probes.distinct.size == bench.probes.size, "duplicate probe schema")
      _ <- check(
        bench.probes.forall:
          case AnalogProbe.Voltage(node) => node != AnalogNode.Ground && nodes.contains(node)
          case AnalogProbe.Current(id) => sourceIds.contains(id)
        ,
        "probe references ground, an undeclared node or a missing source"
      )
      _ <- check(
        bench.initialConditions.size <= MaxNodes,
        s"initial condition count exceeds $MaxNodes"
      )
      _ <- check(
        bench.initialConditions.forall(condition =>
          condition.node != AnalogNode.Ground && nodes.contains(condition.node) &&
            condition.volts.isFinite
        ),
        "initial conditions need declared non-ground nodes and finite voltages"
      )
      _ <- check(
        bench.initialConditions.map(_.node).distinct.size == bench.initialConditions.size,
        "duplicate initial condition node"
      )
      _ <- check(
        bench.initialConditions.isEmpty || bench.analysis.kind == AnalogAnalysisKind.Transient,
        "initial conditions require a transient analysis"
      )
    yield ()

  def validateSources(bench: AnalogCircuitBench): Either[String, Unit] =
    val pwlCount = bench.sources.foldLeft(0L): (count, source) =>
      count +
        (source.transient match
          case Some(AnalogTransientWaveform.Pwl(points)) => points.size.toLong
          case _ => 0L)
    for
      _ <- check(
        pwlCount <= MaxWaveformPoints,
        s"total PWL points exceed $MaxWaveformPoints"
      )
      _ <- check(bench.sources.forall(_.dc.isFinite), "DC source values must be finite")
      _ <- check(
        bench.sources.forall(_.ac.forall(ac =>
          nonnegative(ac.magnitude) && ac.phaseDegrees.isFinite
        )),
        "AC source magnitude must be finite nonnegative and phase finite"
      )
      _ <- bench.sources.foldLeft[Either[String, Unit]](Right(())): (result, source) =>
        result.flatMap: _ =>
          source.transient match
            case None => Right(())
            case Some(AnalogTransientWaveform.Pwl(points)) =>
              check(
                points.size >= 2 &&
                  points.forall((time, value) => nonnegative(time) && value.isFinite) &&
                  points.sliding(2).forall(pair => pair(1)._1 > pair(0)._1),
                "PWL needs at least two finite values with strictly increasing nonnegative times"
              )
            case Some(AnalogTransientWaveform.Pulse(
                  initial,
                  pulsed,
                  delay,
                  rise,
                  fall,
                  width,
                  period
                )) =>
              val duration = rise + width + fall
              val stop = bench.analysis match
                case AnalogCircuitAnalysis.Transient(_, value, _) => Some(value)
                case _ => None
              for
                _ <- check(
                  initial.isFinite && pulsed.isFinite && nonnegative(delay) &&
                    positive(rise) && positive(fall) && positive(width) && positive(period) &&
                    duration.isFinite && period >= duration &&
                    (delay + duration).isFinite &&
                    delay + rise > delay && delay + rise + width > delay + rise &&
                    delay + duration > delay + rise + width,
                  "pulse amplitudes and ordered durations must be finite and fit the period"
                )
                _ <-
                  check(stop.forall(end => delay < end), "pulse delay must precede transient stop")
                _ <- check(
                  stop.forall(end =>
                    ((BigDecimal.decimal(end) - BigDecimal.decimal(delay)) /
                      BigDecimal.decimal(period))
                      .setScale(0, BigDecimal.RoundingMode.CEILING) * 4 <= MaxWaveformPoints
                  ),
                  s"pulse requested transitions exceed $MaxWaveformPoints"
                )
              yield ()
    yield ()

  def validateAnalysis(bench: AnalogCircuitBench): Either[String, Unit] =
    bench.analysis match
      case AnalogCircuitAnalysis.DcSweep(id, _, _, _) =>
        check(bench.sources.exists(_.id == id), "DC sweep references a missing source")
      case AnalogCircuitAnalysis.AcLinearSweep(_, _, _) |
          AnalogCircuitAnalysis.AcDecadeSweep(_, _, _) =>
        check(
          bench.sources.exists(_.ac.exists(_.magnitude > 0)),
          "AC analysis needs nonzero AC excitation"
        )
      case AnalogCircuitAnalysis.Transient(_, _, _) => Right(())

  def axis(analysis: AnalogCircuitAnalysis): AnalogAxis = analysis match
    case AnalogCircuitAnalysis.DcSweep(_, start, stop, step) => AnalogAxis.Dc(start, stop, step)
    case AnalogCircuitAnalysis.AcLinearSweep(points, start, stop) =>
      AnalogAxis.AcLinear(points, start, stop)
    case AnalogCircuitAnalysis.AcDecadeSweep(points, start, stop) =>
      AnalogAxis.AcDecade(points, start, stop)
    case AnalogCircuitAnalysis.Transient(step, stop, _) => AnalogAxis.Transient(step, stop)

  private def validIdentifier(value: String): Boolean =
    Option(value).exists(name => name.length <= MaxIdentifierLength && Identifier.matches(name))

  def positive(value: Double): Boolean = value.isFinite && value > 0
  def nonnegative(value: Double): Boolean = value.isFinite && value >= 0
  def number(value: Double): String = java.lang.Double.toString(value)
  def check(condition: Boolean, message: String): Either[String, Unit] =
    Either.cond(condition, (), message)
