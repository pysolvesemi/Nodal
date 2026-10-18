package nodal.internal.integrations

import java.util.Locale

/** IDs belong to a declared finite bench; no caller text becomes a SPICE node or source name. */
private[nodal] enum NgspiceNode:
  case Ground
  case Node(id: Int)

private[nodal] final case class NgspiceSourceId(value: Int)
private[nodal] enum NgspiceSourceKind:
  case Voltage, Current

private[nodal] final case class NgspiceAcExcitation(magnitude: Double, phaseDegrees: Double = 0)
private[nodal] enum NgspiceTransientWaveform:
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

private[nodal] final case class NgspiceSource(
    id: NgspiceSourceId,
    kind: NgspiceSourceKind,
    positive: NgspiceNode,
    negative: NgspiceNode,
    dc: Double = 0,
    ac: Option[NgspiceAcExcitation] = None,
    transient: Option[NgspiceTransientWaveform] = None
)

/** Terminals follow the actual emitted module declaration, including repeated node bindings. */
private[nodal] final case class NgspiceCircuitBinding(
    moduleName: String,
    terminals: Vector[NgspiceNode],
    parameters: Vector[(String, Double)] = Vector.empty
)

private[nodal] enum NgspiceCircuitAnalysis:
  case DcSweep(source: NgspiceSourceId, start: Double, stop: Double, step: Double)
  case AcLinearSweep(points: Int, startHz: Double, stopHz: Double)
  case AcDecadeSweep(pointsPerDecade: Int, startHz: Double, stopHz: Double)
  case Transient(stepSeconds: Double, stopSeconds: Double, useInitialConditions: Boolean)

  def kind: NgspiceAnalysisKind = this match
    case DcSweep(_, _, _, _) => NgspiceAnalysisKind.Dc
    case AcLinearSweep(_, _, _) | AcDecadeSweep(_, _, _) => NgspiceAnalysisKind.Ac
    case Transient(_, _, _) => NgspiceAnalysisKind.Transient

private[nodal] enum NgspiceProbe:
  case Voltage(node: NgspiceNode)
  case Current(source: NgspiceSourceId)

private[nodal] final case class NgspiceInitialCondition(node: NgspiceNode, volts: Double)

/** One OSDI DUT and bounded independent sources; this is not an arbitrary circuit/deck API. */
private[nodal] final case class NgspiceCircuitBench(
    model: NgspiceCircuitBinding,
    nodes: Vector[NgspiceNode],
    sources: Vector[NgspiceSource],
    analysis: NgspiceCircuitAnalysis,
    probes: Vector[NgspiceProbe],
    initialConditions: Vector[NgspiceInitialCondition] = Vector.empty,
    options: NgspiceSolverOptions = NgspiceSolverOptions()
)

/** Shared axis contract consumed by the same independent raw validator for old and new benches. */
private[nodal] enum NgspiceAxis:
  case Dc(start: Double, stop: Double, step: Double)
  case AcLinear(points: Int, start: Double, stop: Double)
  case AcDecade(pointsPerDecade: Int, start: Double, stop: Double)
  case Transient(step: Double, stop: Double)

private[integrations] object NgspiceCircuitRenderer:
  import NgspiceBench.{check, number, positive, nonnegative}

  private val MaxNodes = 63
  private val MaxSources = 63
  private val MaxTerminals = 64
  private val MaxProbes = 63
  private val MaxWaveformPoints = 100000

  def generate(bench: NgspiceCircuitBench): Either[String, NgspiceGeneratedBench] =
    for
      _ <- NgspiceBench.validateParameters(bench.model.moduleName, bench.model.parameters)
      _ <- NgspiceBench.validateOptions(bench.options)
      _ <- validateTopology(bench)
      grid = axis(bench.analysis)
      count <- NgspiceBench.validateGrid(grid, bench.options)
      _ <- check(
        count.toLong * (bench.probes.size + 1) <= 1000000,
        "requested grid and saved probes exceed the raw value budget"
      )
      _ <- validateSources(bench)
      _ <- validateAnalysis(bench)
    yield
      val sourceMap = bench.sources.map(source => source.id -> source).toMap
      val parameters = bench.model.parameters.sortBy(_._1.toLowerCase(Locale.ROOT))
        .map((name, value) => s"$name=${number(value)}").mkString(" ")
      val modelLine = s".model dut ${bench.model.moduleName}" +
        (if parameters.isEmpty then "" else s" ($parameters)")
      val options = bench.options
      val optionLine = s".options reltol=${number(options.reltol)} abstol=${number(options.abstol)}" +
        s" vntol=${number(options.vntol)} temp=${number(options.temp)} tnom=${number(options.tnom)}"
      val probeNames = bench.probes.map:
        case NgspiceProbe.Voltage(node) => s"v(${nodeName(node)})" -> "voltage"
        case NgspiceProbe.Current(id) => currentName(sourceMap(id)) -> "current"
      val axis = bench.analysis match
        case NgspiceCircuitAnalysis.DcSweep(id, _, _, _) =>
          if sourceMap(id).kind == NgspiceSourceKind.Voltage then "v(v-sweep)" -> "voltage"
          else "i(i-sweep)" -> "current"
        case NgspiceCircuitAnalysis.AcLinearSweep(_, _, _) |
            NgspiceCircuitAnalysis.AcDecadeSweep(_, _, _) => "frequency" -> "frequency"
        case NgspiceCircuitAnalysis.Transient(_, _, _) => "time" -> "time"
      val initial = Option.when(bench.initialConditions.nonEmpty)(
        ".ic " + bench.initialConditions.sortBy(condition => nodeName(condition.node))
          .map(condition => s"v(${nodeName(condition.node)})=${number(condition.volts)}")
          .mkString(" ")
      ).toVector
      val deck =
        (Vector("Nodal finite multi-terminal ngspice bench") ++
          bench.sources.sortBy(_.id.value).flatMap(renderSource) ++ Vector(
            s"Nload ${bench.model.terminals.map(nodeName).mkString(" ")} dut",
            modelLine,
            optionLine
          ) ++ initial ++ Vector(
            ".save " + probeNames.map(_._1).mkString(" "),
            renderAnalysis(bench.analysis, sourceMap),
            ".end"
          )).mkString("", "\n", "\n")
      NgspiceGeneratedBench(
        deck,
        bench.analysis.kind,
        count,
        axis +: probeNames,
        grid
      )

  private def validateTopology(bench: NgspiceCircuitBench): Either[String, Unit] =
    for
      _ <- check(
        bench.nodes.nonEmpty && bench.nodes.size <= MaxNodes,
        s"declared node count must be 1 to $MaxNodes"
      )
      nodes = bench.nodes.toSet
      declared = (node: NgspiceNode) => node == NgspiceNode.Ground || nodes.contains(node)
      _ <- check(
        bench.nodes.forall:
          case NgspiceNode.Node(id) => id >= 0 && id < MaxNodes
          case NgspiceNode.Ground => false
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
      _ <- check(
        bench.nodes.size + bench.sources.count(_.kind == NgspiceSourceKind.Current) <= MaxNodes,
        s"declared and current-sensor nodes together exceed $MaxNodes"
      )
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
          case NgspiceProbe.Voltage(node) => node != NgspiceNode.Ground && nodes.contains(node)
          case NgspiceProbe.Current(id) => sourceIds.contains(id)
        ,
        "probe references ground, an undeclared node or a missing source"
      )
      _ <- check(
        bench.initialConditions.size <= MaxNodes,
        s"initial condition count exceeds $MaxNodes"
      )
      _ <- check(
        bench.initialConditions.forall(condition =>
          condition.node != NgspiceNode.Ground && nodes.contains(condition.node) &&
            condition.volts.isFinite
        ),
        "initial conditions need declared non-ground nodes and finite voltages"
      )
      _ <- check(
        bench.initialConditions.map(_.node).distinct.size == bench.initialConditions.size,
        "duplicate initial condition node"
      )
      _ <- check(
        bench.initialConditions.isEmpty || bench.analysis.kind == NgspiceAnalysisKind.Transient,
        "initial conditions require a transient analysis"
      )
    yield ()

  private def validateSources(bench: NgspiceCircuitBench): Either[String, Unit] =
    val pwlCount = bench.sources.foldLeft(0L): (count, source) =>
      count +
        (source.transient match
          case Some(NgspiceTransientWaveform.Pwl(points)) => points.size.toLong
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
            case Some(NgspiceTransientWaveform.Pwl(points)) =>
              check(
                points.size >= 2 &&
                  points.forall((time, value) => nonnegative(time) && value.isFinite) &&
                  points.sliding(2).forall(pair => pair(1)._1 > pair(0)._1),
                "PWL needs at least two finite values with strictly increasing nonnegative times"
              )
            case Some(NgspiceTransientWaveform.Pulse(
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
                case NgspiceCircuitAnalysis.Transient(_, value, _) => Some(value)
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

  private def validateAnalysis(bench: NgspiceCircuitBench): Either[String, Unit] =
    bench.analysis match
      case NgspiceCircuitAnalysis.DcSweep(id, _, _, _) =>
        check(bench.sources.exists(_.id == id), "DC sweep references a missing source")
      case NgspiceCircuitAnalysis.AcLinearSweep(_, _, _) |
          NgspiceCircuitAnalysis.AcDecadeSweep(_, _, _) =>
        check(
          bench.sources.exists(_.ac.exists(_.magnitude > 0)),
          "AC analysis needs nonzero AC excitation"
        )
      case NgspiceCircuitAnalysis.Transient(_, _, _) => Right(())

  private def axis(analysis: NgspiceCircuitAnalysis): NgspiceAxis = analysis match
    case NgspiceCircuitAnalysis.DcSweep(_, start, stop, step) => NgspiceAxis.Dc(start, stop, step)
    case NgspiceCircuitAnalysis.AcLinearSweep(points, start, stop) =>
      NgspiceAxis.AcLinear(points, start, stop)
    case NgspiceCircuitAnalysis.AcDecadeSweep(points, start, stop) =>
      NgspiceAxis.AcDecade(points, start, stop)
    case NgspiceCircuitAnalysis.Transient(step, stop, _) => NgspiceAxis.Transient(step, stop)

  private def nodeName(node: NgspiceNode): String = node match
    case NgspiceNode.Ground => "0"
    case NgspiceNode.Node(id) => s"n$id"

  private def sourceName(source: NgspiceSource): String =
    s"${if source.kind == NgspiceSourceKind.Voltage then "v" else "i"}s${source.id.value}"

  private def currentName(source: NgspiceSource): String =
    s"i(${
        if source.kind == NgspiceSourceKind.Voltage then sourceName(source)
        else s"vsense${source.id.value}"
      })"

  private def renderSource(source: NgspiceSource): Vector[String] =
    val negative = if source.kind == NgspiceSourceKind.Current then s"sense${source.id.value}"
    else nodeName(source.negative)
    val ac =
      source.ac.fold("")(value => s" AC ${number(value.magnitude)} ${number(value.phaseDegrees)}")
    val transient = source.transient.fold(""):
      case NgspiceTransientWaveform.Pulse(initial, pulsed, delay, rise, fall, width, period) =>
        s" PULSE(${Vector(initial, pulsed, delay, rise, fall, width, period).map(number).mkString(" ")})"
      case NgspiceTransientWaveform.Pwl(points) =>
        " PWL(\n" +
          points.map((time, value) => s"+ ${number(time)} ${number(value)}").mkString("\n") +
          "\n+ )"
    Vector(
      s"${sourceName(source)} ${nodeName(source.positive)} $negative DC ${number(source.dc)}$ac$transient"
    ) ++
      Option.when(source.kind == NgspiceSourceKind.Current)(
        s"vsense${source.id.value} $negative ${nodeName(source.negative)} 0"
      ).toVector

  private def renderAnalysis(
      analysis: NgspiceCircuitAnalysis,
      sources: Map[NgspiceSourceId, NgspiceSource]
  ): String =
    analysis match
      case NgspiceCircuitAnalysis.DcSweep(id, start, stop, step) =>
        s".dc ${sourceName(sources(id))} ${number(start)} ${number(stop)} ${number(step)}"
      case NgspiceCircuitAnalysis.AcLinearSweep(points, start, stop) =>
        s".ac lin $points ${number(start)} ${number(stop)}"
      case NgspiceCircuitAnalysis.AcDecadeSweep(points, start, stop) =>
        s".ac dec $points ${number(start)} ${number(stop)}"
      case NgspiceCircuitAnalysis.Transient(step, stop, uic) =>
        s".tran ${number(step)} ${number(stop)} 0 ${number(step)}${if uic then " uic" else ""}"
