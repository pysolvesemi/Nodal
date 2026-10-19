package nodal.sim

import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

import nodal.Quantity
import nodal.internal.integrations.{NgspiceAnalysisKind, OpenVafDiagnosticMapper}

/** A finite source-DC axis; values retain the source's electrical dimension. */
final class SweepAxis private[sim] (
    val name: String,
    private[sim] val source: SimulationSourceDrive,
    val unit: String,
    private[sim] val values: Vector[Double]
)
object SweepAxis:
  def apply[D](name: String, source: ElectricalDrive[D], values: Vector[Quantity[D]]): SweepAxis =
    new SweepAxis(name, source, source.unit, values.map(_.siValue))

final case class SweepCoordinate(axis: String, ordinal: Int, value: Double, unit: String)
final case class SweepPointResult(
    identity: String,
    coordinates: Vector[SweepCoordinate],
    result: SimulationResult
)

object SimulationSweep:
  /** Last axis varies fastest. Every point retains its own fresh tool receipt. */
  def run(
      bench: SimulationBench,
      axes: Vector[SweepAxis],
      tools: SimulatorTools
  ): Either[SimulationError, Vector[SweepPointResult]] =
    try
      require(axes.nonEmpty && axes.size <= 4, "one to four sweep axes are supported")
      require(
        axes.map(_.name).distinct.size == axes.size &&
          axes.forall(axis => axis.name.nonEmpty && axis.name.length <= 128),
        "invalid or duplicate sweep axis name"
      )
      require(
        axes.map(_.source).distinct.size == axes.size,
        "one source cannot occur in two sweep axes"
      )
      require(axes.forall(axis => bench.sources.exists(_ eq axis.source)), "foreign sweep source")
      require(
        axes.forall(axis =>
          axis.values.nonEmpty && axis.values.size <= 128 && axis.values.forall(_.isFinite)
        ),
        "invalid sweep values"
      )
      require(
        axes.forall(axis => !bench.analysis.source.exists(_ eq axis.source)),
        "DC analysis already controls this sweep source"
      )
      var count = 1
      axes.foreach: axis =>
        require(count <= 128 / axis.values.size, "Cartesian sweep exceeds 128 runs")
        count *= axis.values.size
      val baseBench = SimulationExecution.admit(bench.lowered, tools).toOption
      require(baseBench.nonEmpty, "base sweep bench is outside the supported profile")
      // DC/AC have exact validated row counts. Adaptive transient rows can reach
      // the parser ceiling, so use that worst case rather than the nominal grid.
      val rows = if baseBench.get.kind == NgspiceAnalysisKind.Transient then 100000L
      else baseBench.get.requestedPoints.toLong
      val retainedCellsPerRun = math.min(1000000L, rows * baseBench.get.expectedVariables.size)
      require(
        retainedCellsPerRun * count <= 4000000L,
        "sweep worst-case retained values exceed 4000000 cells"
      )
      val baseIdentity = OpenVafDiagnosticMapper.sha256((
        bench.model.evidence.modelSha256.getOrElse("") + "|" + tools.backend.toString + "|" +
          tools.profile + "|" + baseBench.get.deck
      ).getBytes(StandardCharsets.UTF_8).toVector)
      // Preflight every bounded point before launching any process. Invalid late points
      // cannot leave a partially executed sweep masquerading as an admitted request.
      val points = Vector.tabulate(count): ordinal =>
        var remaining = ordinal
        val positions = axes.reverse.map: axis =>
          val position = remaining % axis.values.size
          remaining /= axis.values.size
          position
        val coordinates = axes.zip(positions.reverse).map((axis, position) =>
          SweepCoordinate(axis.name, position, axis.values(position), axis.unit)
        )
        val overrides = axes.zip(coordinates).map((axis, coordinate) =>
          axis.source.lowered.id -> coordinate.value
        ).toMap
        val lowered = bench.lowered.copy(sources =
          bench.lowered.sources.map(source =>
            overrides.get(source.id).fold(source)(value => source.copy(dc = value))
          )
        )
        require(
          SimulationExecution.admit(lowered, tools).isRight,
          "sweep point is outside supported bench profile"
        )
        val pointBench =
          new SimulationBench(bench.model, bench.sources, bench.analysis, bench.probes, lowered)
        val identityText = baseIdentity + "|" + coordinates.map(value =>
          s"${value.axis.length}:${value.axis}:${value.ordinal}:${java.lang.Double.toHexString(value.value)}:${value.unit}"
        ).mkString("|")
        val identity =
          OpenVafDiagnosticMapper.sha256(identityText.getBytes(StandardCharsets.UTF_8).toVector)
        (identity, coordinates, pointBench)
      Right(points.map((identity, coordinates, pointBench) =>
        SweepPointResult(identity, coordinates, SimulationSession.run(pointBench, tools))
      ))
    catch
      case NonFatal(error) => Left(SimulationError(
          "SIM-SWEEP",
          Option(error.getMessage).getOrElse(error.getClass.getName)
        ))
