package nodal.internal.testkit

import java.nio.file.Path
import scala.compiletime.testing.typeCheckErrors

import nodal.*
import nodal.sim.*
import utest.*

/** Public contract regressions; actual tool execution is retained separately by the replay. */
object Increment53CrossToolTests extends TestSuite:
  private val origin = WaveformProvenance("F53 alignment boundary fixture")
  private def trace(points: Vector[(Double, Double)]) =
    Waveform.reference(
      WaveformAxis.Time,
      WaveformUnit.Voltage,
      points.map((x, y) => WaveformSample(x.seconds, ComplexQuantity.real(y.volts))),
      origin
    ).toOption.get

  val tests: Tests = Tests:
    test("existing tools construction still selects ngspice explicitly"):
      val tools =
        SimulatorTools(Path.of("vacask"), "vacask-0.3.4-linux-x86_64", Path.of("evidence"))
      assert(tools.backend == SimulatorBackend.Ngspice)
      assert(tools.runtimeLibraryDirectories.isEmpty)
      val candidate = tools.copy(
        backend = SimulatorBackend.Vacask,
        runtimeLibraryDirectories = Vector(Path.of("runtime"))
      )
      assert(candidate.backend == SimulatorBackend.Vacask)
      assert(candidate.executable == tools.executable)

    test("exact rejects a single ulp grid mismatch without silently interpolating"):
      val reference = trace(Vector(0.0 -> 0.0, 0.5 -> 0.5, 1.0 -> 1.0))
      val shifted = trace(Vector(0.0 -> 0.0, Math.nextUp(0.5) -> 0.5, 1.0 -> 1.0))
      val tolerance = Tolerance(1e-6.volts, 1e-6)
      assert(shifted.compare(reference, tolerance).isLeft)
      assert(shifted.compare(
        reference,
        tolerance,
        alignment = WaveformAlignment.Linear
      ).toOption.get.passed)

    test("explicit linear never extrapolates and tighter tolerances preserve disagreement"):
      val reference = trace(Vector(0.0 -> 0.0, 1.0 -> 1.0))
      val outside = trace(Vector(0.0 -> 0.0, Math.nextUp(1.0) -> 1.0))
      assert(outside.compare(
        reference,
        Tolerance(1.0.volts, 1.0),
        alignment = WaveformAlignment.Linear
      ).isLeft)
      val offset = trace(Vector(0.0 -> 0.01, 1.0 -> 1.01))
      val comparison = offset.compare(reference, Tolerance(1e-6.volts, 1e-6)).toOption.get
      assert(!comparison.passed, comparison.failureCount == 2)

    test("cross-tool dimensions are checked by the public signature"):
      val valid = typeCheckErrors("""
        import nodal.*
        import nodal.sim.*
        def compare(b: SimulationBench, a: SimulatorTools, c: SimulatorTools, p: Probe[VoltageDimension]) =
          SimulationSession.compareTools(b, a, c, WaveformAxis.Time, p, Tolerance(1e-6.volts, 1e-6))
      """)
      val invalid = typeCheckErrors("""
        import nodal.*
        import nodal.sim.*
        def compare(b: SimulationBench, a: SimulatorTools, c: SimulatorTools, p: Probe[VoltageDimension]) =
          SimulationSession.compareTools(b, a, c, WaveformAxis.Time, p, Tolerance(1e-6.amps, 1e-6))
      """)
      assert(valid.isEmpty, invalid.nonEmpty)

    test("numerical disagreement is a completed comparison but not a pass"):
      val comparison = WaveformComparisonResult(2, 1, Some(0), 0.1, 0.05, math.sqrt(0.005))
      val result =
        CrossToolComparisonResult(CrossToolOutcome.Disagreement, None, None, None, Some(comparison))
      assert(result.succeeded, !result.passed)
      val failed = CrossToolComparisonResult(
        CrossToolOutcome.ToolFailure,
        None,
        None,
        Some(SimulationError("missing-tool", "unavailable")),
        None
      )
      assert(!failed.succeeded, !failed.passed)
