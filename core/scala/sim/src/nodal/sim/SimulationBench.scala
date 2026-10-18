package nodal.sim

import java.nio.file.Path
import java.time.Duration
import scala.util.control.NonFatal

import nodal.*
import nodal.internal.integrations.*

/** Circuit node IDs are finite logical identities; they are never target-language strings. */
enum SimulationNode:
  case Ground
  case Node(id: Int)

object SimulationNode:
  private[sim] def lower(node: SimulationNode): NgspiceNode = node match
    case SimulationNode.Ground => NgspiceNode.Ground
    case SimulationNode.Node(id) => NgspiceNode.Node(id)

sealed trait SimulationSourceDrive:
  private[sim] def lowered: NgspiceSource

final class ElectricalDrive[D] private[sim] (
    private[sim] val lowered: NgspiceSource,
    val unit: String
) extends SimulationSourceDrive

object ElectricalDrive:
  def voltage(
      id: Int,
      positive: SimulationNode,
      negative: SimulationNode,
      dc: Quantity[VoltageDimension],
      ac: Option[Quantity[VoltageDimension]] = None,
      phase: Phase = Phase.Zero,
      waveform: Option[VoltageWaveform] = None
  ): ElectricalDrive[VoltageDimension] =
    new ElectricalDrive(
      NgspiceSource(
        NgspiceSourceId(id),
        NgspiceSourceKind.Voltage,
        SimulationNode.lower(positive),
        SimulationNode.lower(negative),
        dc.siValue,
        ac.map(value => NgspiceAcExcitation(value.siValue, phase.degrees)),
        waveform.map(value =>
          NgspiceTransientWaveform.Pwl(value.points.map((time, voltage) =>
            (time.siValue, voltage.siValue)
          ))
        )
      ),
      "V"
    )

  def current(
      id: Int,
      positive: SimulationNode,
      negative: SimulationNode,
      dc: Quantity[CurrentDimension],
      ac: Option[Quantity[CurrentDimension]] = None,
      phase: Phase = Phase.Zero,
      waveform: Vector[(Quantity[TimeDimension], Quantity[CurrentDimension])] = Vector.empty
  ): ElectricalDrive[CurrentDimension] =
    require(waveform.size <= 100000, "current waveform exceeds 100000 points")
    new ElectricalDrive(
      NgspiceSource(
        NgspiceSourceId(id),
        NgspiceSourceKind.Current,
        SimulationNode.lower(positive),
        SimulationNode.lower(negative),
        dc.siValue,
        ac.map(value => NgspiceAcExcitation(value.siValue, phase.degrees)),
        Option.when(waveform.nonEmpty)(NgspiceTransientWaveform.Pwl(waveform.map((time, current) =>
          (time.siValue, current.siValue)
        )))
      ),
      "A"
    )

sealed trait SimulationAnalysis:
  private[sim] def lowered: NgspiceCircuitAnalysis
  private[sim] def source: Option[SimulationSourceDrive] = None

object SimulationAnalysis:
  def dc[D](
      drive: ElectricalDrive[D],
      start: Quantity[D],
      stop: Quantity[D],
      step: Quantity[D]
  ): SimulationAnalysis =
    new SimulationAnalysis:
      private[sim] val lowered =
        NgspiceCircuitAnalysis.DcSweep(drive.lowered.id, start.siValue, stop.siValue, step.siValue)
      override private[sim] val source = Some(drive)
  def acLinear(points: Int, start: Frequency, stop: Frequency): SimulationAnalysis =
    new SimulationAnalysis:
      private[sim] val lowered =
        NgspiceCircuitAnalysis.AcLinearSweep(points, start.hertz, stop.hertz)
  def acDecade(pointsPerDecade: Int, start: Frequency, stop: Frequency): SimulationAnalysis =
    new SimulationAnalysis:
      private[sim] val lowered =
        NgspiceCircuitAnalysis.AcDecadeSweep(pointsPerDecade, start.hertz, stop.hertz)
  def transient(
      step: Quantity[TimeDimension],
      stop: Quantity[TimeDimension],
      useInitialConditions: Boolean
  ): SimulationAnalysis =
    new SimulationAnalysis:
      private[sim] val lowered =
        NgspiceCircuitAnalysis.Transient(step.siValue, stop.siValue, useInitialConditions)

sealed trait SimulationProbe:
  def name: String
  def unit: String
  private[sim] def lowered: NgspiceProbe
  private[sim] def source: Option[SimulationSourceDrive] = None

final class Probe[D] private[sim] (
    val name: String,
    val unit: String,
    private[sim] val lowered: NgspiceProbe,
    override private[sim] val source: Option[SimulationSourceDrive] = None
) extends SimulationProbe

object Probe:
  def voltage(name: String, node: SimulationNode): Probe[VoltageDimension] =
    new Probe(name, "V", NgspiceProbe.Voltage(SimulationNode.lower(node)))

  /** Positive current flows from the source's positive terminal to its negative terminal. */
  def current(name: String, source: SimulationSourceDrive): Probe[CurrentDimension] =
    new Probe(name, "A", NgspiceProbe.Current(source.lowered.id), Some(source))

final case class SimulationInitialCondition(
    node: SimulationNode,
    voltage: Quantity[VoltageDimension]
)

final class SimulationBench private[sim] (
    val model: CompiledSimulationModel,
    val sources: Vector[SimulationSourceDrive],
    val analysis: SimulationAnalysis,
    val probes: Vector[SimulationProbe],
    private[sim] val lowered: NgspiceCircuitBench
)

object SimulationBench:
  def create(
      model: CompiledSimulationModel,
      bindings: Vector[(ModelPort, SimulationNode)],
      sources: Vector[SimulationSourceDrive],
      analysis: SimulationAnalysis,
      probes: Vector[SimulationProbe],
      initialConditions: Vector[SimulationInitialCondition] = Vector.empty
  ): Either[SimulationError, SimulationBench] =
    try
      require(bindings.nonEmpty && bindings.size <= 32, "bind between one and 32 ports")
      require(sources.nonEmpty && sources.size <= 63, "source count must be one to 63")
      require(probes.nonEmpty && probes.size <= 63, "probe count must be one to 63")
      require(initialConditions.size <= 63, "initial condition count exceeds 63")
      require(bindings.forall((port, _) => port.owner eq model.source.owner), "foreign model port")
      require(
        bindings.map(_._1.semanticPath).distinct.size == bindings.size,
        "duplicate port binding"
      )
      require(
        bindings.map(_._1.semanticPath).toSet == model.portsInTargetOrder.map(_.semanticPath).toSet,
        "missing or extra port binding"
      )
      require(
        analysis.source.forall(source => sources.exists(_ eq source)),
        "DC analysis names a foreign source"
      )
      require(
        probes.forall(_.source.forall(source => sources.exists(_ eq source))),
        "current probe names a foreign source"
      )
      require(
        probes.map(_.name).distinct.size == probes.size &&
          probes.forall(probe => probe.name.nonEmpty && probe.name.length <= 128),
        "invalid or duplicate probe name"
      )
      val byPath =
        bindings.map((port, node) => port.semanticPath -> SimulationNode.lower(node)).toMap
      val nodes =
        (bindings.map(_._2).map(SimulationNode.lower) ++
          sources.flatMap(source => Vector(source.lowered.positive, source.lowered.negative)) ++
          initialConditions.map(value => SimulationNode.lower(value.node)))
          .filter(_ != NgspiceNode.Ground).distinct.sortBy {
            case NgspiceNode.Node(id) => id; case NgspiceNode.Ground => -1
          }
      val lowered = NgspiceCircuitBench(
        NgspiceCircuitBinding(
          model.moduleName,
          model.portsInTargetOrder.map(port => byPath(port.semanticPath))
        ),
        nodes,
        sources.map(_.lowered),
        analysis.lowered,
        probes.map(_.lowered),
        initialConditions.map(value =>
          NgspiceInitialCondition(SimulationNode.lower(value.node), value.voltage.siValue)
        )
      )
      NgspiceBench.generate(lowered).left.map(message => SimulationError("SIM-BENCH", message))
        .map(_ => new SimulationBench(model, sources, analysis, probes, lowered))
    catch
      case NonFatal(error) => Left(SimulationError(
          "SIM-BENCH",
          Option(error.getMessage).getOrElse(error.getClass.getName)
        ))

final case class SimulatorTools(
    executable: Path,
    profile: String,
    evidenceRoot: Path,
    timeout: Duration = Duration.ofSeconds(30)
)

final case class SimulationEvidence(
    directory: Option[Path],
    outcome: String,
    message: String,
    requestSha256: Option[String],
    modelSha256: String,
    artifactSha256: Option[String],
    simulatorSha256: Option[String],
    simulatorVersion: Option[String],
    command: Option[CommandEvidence],
    rawSha256: Option[String],
    rawPath: Option[Path]
)

enum SimulationAxisUnit:
  case Volts, Amperes, Hertz, Seconds

enum ComplexComponent:
  case Real, Imaginary, Magnitude

enum SampleReduction:
  case First, Last, Minimum, Maximum, Mean
  case At(index: Int)

final class Measurement[D] private[sim] (val name: String, val value: Quantity[D], val unit: String)
final case class Tolerance[D](absolute: Quantity[D], relative: Double):
  require(
    absolute.siValue >= 0 && relative.isFinite && relative >= 0,
    "tolerances must be finite and nonnegative"
  )

final case class AssertionResult(
    passed: Boolean,
    actual: Double,
    expected: Double,
    absoluteTolerance: Double,
    relativeTolerance: Double,
    unit: String
)

object SimulationAssertion:
  def check[D](
      measurement: Measurement[D],
      expected: Quantity[D],
      tolerance: Tolerance[D]
  ): AssertionResult =
    val actual = measurement.value.siValue
    val reference = expected.siValue
    val allowance = tolerance.absolute.siValue + tolerance.relative * math.abs(reference)
    require(allowance.isFinite, "tolerance calculation overflow")
    AssertionResult(
      math.abs(actual - reference) <= allowance,
      actual,
      reference,
      tolerance.absolute.siValue,
      tolerance.relative,
      measurement.unit
    )

final class SimulationResult private[sim] (
    val evidence: SimulationEvidence,
    val error: Option[SimulationError],
    val axisUnit: SimulationAxisUnit,
    private[sim] val probes: Vector[SimulationProbe],
    private[sim] val plot: Option[NgspiceRawPlot]
):
  def succeeded: Boolean = error.isEmpty && plot.nonEmpty
  def sampleCount: Int = plot.map(_.rows.size).getOrElse(0)
  def axis(index: Int): Either[SimulationError, Double] =
    plot.flatMap(_.rows.lift(index)).map(_.head.real).toRight(SimulationError(
      "SIM-MEASURE",
      "missing axis sample"
    ))
  def waveform[A, D](
      axis: WaveformAxis[A],
      probe: Probe[D]
  ): Either[SimulationError, Waveform[A, D]] =
    val column = probes.indexWhere(_ eq probe) + 1
    if column == 0 || !succeeded then
      Left(SimulationError("SIM-WAVEFORM", "probe is absent or simulation did not succeed"))
    else Waveform.fromRows(axis, probe, evidence, plot.get, column, axisUnit)

  def measure[D](
      probe: Probe[D],
      reduction: SampleReduction,
      component: ComplexComponent = ComplexComponent.Real
  ): Either[SimulationError, Measurement[D]] =
    val column = probes.indexWhere(_ eq probe) + 1
    if column == 0 || !succeeded then
      Left(SimulationError("SIM-MEASURE", "probe is absent or simulation did not succeed"))
    else
      val rows = plot.get.rows
      def project(row: Vector[NgspiceComplex]): Double =
        val value = row(column)
        component match
          case ComplexComponent.Real => value.real
          case ComplexComponent.Imaginary => value.imaginary
          case ComplexComponent.Magnitude => math.hypot(value.real, value.imaginary)
      val measured = reduction match
        case SampleReduction.First => rows.headOption.map(project)
        case SampleReduction.Last => rows.lastOption.map(project)
        case SampleReduction.At(index) => rows.lift(index).map(project)
        case aggregate =>
          var finite = true
          val values = rows.iterator.map: row =>
            val value = project(row)
            finite &&= value.isFinite
            value
          val result = aggregate match
            case SampleReduction.Minimum => values.minOption
            case SampleReduction.Maximum => values.maxOption
            case SampleReduction.Mean =>
              Option.when(rows.nonEmpty)(values.foldLeft(0.0)((sum, value) =>
                sum + value / rows.size
              ))
            case _ => None
          result.filter(_ => finite)
      measured.filter(_.isFinite).map(value =>
        new Measurement(probe.name, new Quantity[D](value), probe.unit)
      )
        .toRight(SimulationError("SIM-MEASURE", "measurement is missing or nonfinite"))

object SimulationSession:
  def run(bench: SimulationBench, tools: SimulatorTools): SimulationResult =
    val result = NgspiceSimulator.simulate(NgspiceCircuitSimulateRequest(
      tools.executable,
      tools.evidenceRoot,
      tools.profile,
      bench.model.compiled,
      bench.lowered,
      runTimeout = tools.timeout
    ))
    new SimulationResult(
      evidence(result),
      Option.when(result.outcome != NgspiceOutcome.Simulated)(SimulationError(
        result.diagnosticCode,
        result.message
      )),
      axisUnit(bench),
      bench.probes,
      result.plot
    )

  /** Compares a fresh simulation without retaining its plot rows. */
  def compare[A, D](
      bench: SimulationBench,
      tools: SimulatorTools,
      probe: Probe[D],
      reference: Waveform[A, D],
      tolerance: Tolerance[D],
      component: ComplexComponent = ComplexComponent.Real,
      alignment: WaveformAlignment = WaveformAlignment.Exact
  ): StreamingComparisonResult =
    WaveformSimulation.compare(bench, tools, probe, reference, tolerance, component, alignment)

  private[sim] def evidence(result: NgspiceSimulationResult): SimulationEvidence =
    val command = result.simulation.map(value =>
      CommandEvidence(
        value.command,
        value.standardOutput,
        value.standardError,
        value.exitCode,
        value.failure.map(_.toString)
      )
    )
    SimulationEvidence(
      result.evidenceDirectory,
      result.outcome.toString,
      result.message,
      result.identity.requestSha256,
      result.identity.modelSha256,
      result.identity.artifactSha256,
      result.identity.executableSha256,
      result.identity.version,
      command,
      result.raw.map(_.sha256),
      result.raw.map(_.path)
    )

  private[sim] def axisUnit(bench: SimulationBench): SimulationAxisUnit =
    bench.analysis.lowered match
      case NgspiceCircuitAnalysis.DcSweep(id, _, _, _) =>
        if bench.lowered.sources.find(_.id == id).exists(_.kind == NgspiceSourceKind.Voltage) then
          SimulationAxisUnit.Volts
        else SimulationAxisUnit.Amperes
      case NgspiceCircuitAnalysis.Transient(_, _, _) => SimulationAxisUnit.Seconds
      case _ => SimulationAxisUnit.Hertz
