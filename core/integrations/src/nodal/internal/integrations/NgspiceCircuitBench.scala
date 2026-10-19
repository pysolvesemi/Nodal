package nodal.internal.integrations

import java.util.Locale

private[nodal] type NgspiceNode = AnalogNode
private[nodal] val NgspiceNode = AnalogNode

private[nodal] type NgspiceSourceId = AnalogSourceId
private[nodal] val NgspiceSourceId = AnalogSourceId

private[nodal] type NgspiceSourceKind = AnalogSourceKind
private[nodal] val NgspiceSourceKind = AnalogSourceKind

private[nodal] type NgspiceAcExcitation = AnalogAcExcitation
private[nodal] val NgspiceAcExcitation = AnalogAcExcitation

private[nodal] type NgspiceTransientWaveform = AnalogTransientWaveform
private[nodal] val NgspiceTransientWaveform = AnalogTransientWaveform

private[nodal] type NgspiceSource = AnalogSource
private[nodal] val NgspiceSource = AnalogSource

private[nodal] type NgspiceCircuitBinding = AnalogCircuitBinding
private[nodal] val NgspiceCircuitBinding = AnalogCircuitBinding

private[nodal] type NgspiceCircuitAnalysis = AnalogCircuitAnalysis
private[nodal] val NgspiceCircuitAnalysis = AnalogCircuitAnalysis

private[nodal] type NgspiceProbe = AnalogProbe
private[nodal] val NgspiceProbe = AnalogProbe

private[nodal] type NgspiceInitialCondition = AnalogInitialCondition
private[nodal] val NgspiceInitialCondition = AnalogInitialCondition

private[nodal] type NgspiceCircuitBench = AnalogCircuitBench
private[nodal] val NgspiceCircuitBench = AnalogCircuitBench

private[nodal] type NgspiceAxis = AnalogAxis
private[nodal] val NgspiceAxis = AnalogAxis

private[integrations] object NgspiceCircuitRenderer:
  import NgspiceBench.{check, number}
  import AnalogCircuitValidation.{
    validateTopologyIdentity, validateTopologyConnections, validateSources, validateAnalysis, axis
  }

  private val MaxNodes = 63

  def generate(bench: NgspiceCircuitBench): Either[String, NgspiceGeneratedBench] =
    for
      _ <- NgspiceBench.validateParameters(bench.model.moduleName, bench.model.parameters)
      _ <- NgspiceBench.validateOptions(bench.options)
      _ <- validateTopologyIdentity(bench)
      _ <- check(
        bench.nodes.size + bench.sources.count(_.kind == NgspiceSourceKind.Current) <= MaxNodes,
        s"declared and current-sensor nodes together exceed $MaxNodes"
      )
      _ <- validateTopologyConnections(bench)
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
