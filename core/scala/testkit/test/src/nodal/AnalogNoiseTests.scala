package nodal.internal.testkit

import nodal.*
import nodal.increment39fixture.AnalogNoiseSource
import nodal.internal.bridge.ScalaToMlirBridge
import utest.*

final class AnalogNoiseBody(body: () => Unit) extends Module:
  analog:
    body()

final class AnalogNoiseParameter extends Module:
  val power = param(-1.0.real)
  analog:
    val _ = whiteNoise(NoiseId("symbolic"), power * 1.0.A * 1.0.A * 1.0.s)

final class AnalogNoiseOutside extends Module:
  val source = whiteNoise(NoiseId("outside"), 1.0.s)

object AnalogNoiseTests extends TestSuite:
  private def density: Expr[Real] = 1.0.A * 1.0.A * 1.0.s
  private def reject(body: => Unit, diagnostic: String): Unit =
    val failure = scala.util.Try(ConstructionKernel.inspect(new AnalogNoiseBody(() => body)))
      .failed.get.asInstanceOf[ConstructionException]
    assert(failure.diagnostic.code == diagnostic)

  val tests: Tests = Tests:
    test("public noise source is deterministic and retains source identity"):
      val source = ScalaToMlirBridge.lower(new AnalogNoiseSource)
      assert(source == ScalaToMlirBridge.lower(new AnalogNoiseSource))
      val snapshot = ConstructionKernel.inspect(new AnalogNoiseSource)
      assert(snapshot.noiseOperators.size == 5)
      assert(snapshot.noiseOperators.map(_.path).distinct.size == 5)
      assert(snapshot.noiseOperators.count(_.label == "thermal") == 2)
      assert(snapshot.noiseOperators.forall(_.resultDimension == "current"))
      assert(snapshot.noiseOperators.forall(_.source.nonEmpty))
      assert(source.text.contains("noise_kind = \"table\""))
      assert(source.text.contains("analyses = [\"noise\"]"))
      assert(source.text.contains("nodal.parameter_ref"))

    test("spectral density dimensions are square per hertz, not amplitudes"):
      assert(AnalogNoiseContract.resultDimension("current^2*time") == "current")
      assert(AnalogNoiseContract.resultDimension("time*voltage^2") == "voltage")
      assert(AnalogNoiseContract.resultDimension("time") == "1")
      reject({ val _ = whiteNoise(NoiseId("bad"), 0.0.A) }, "NODAL-ANALOG-039-003")
      reject({ val _ = whiteNoise(NoiseId("bad"), 0.0.real) }, "NODAL-ANALOG-039-003")
      reject({ val _ = whiteNoise(NoiseId("bad"), true.B.asInstanceOf[Expr[Real]]) }, "NODAL-ANALOG-039-003")

    test("negative and nonfinite powers or exponents reject"):
      reject({ val _ = whiteNoise(NoiseId("bad"), density * -1.0.real) }, "NODAL-ANALOG-039-004")
      reject({ val _ = flickerNoise(NoiseId("bad"), density, Double.NaN) }, "NODAL-ANALOG-039-004")
      reject({ val _ = flickerNoise(NoiseId("bad"), density, Double.PositiveInfinity) }, "NODAL-ANALOG-039-004")
      val _ = ConstructionKernel.inspect(new AnalogNoiseBody(() =>
        val _ = flickerNoise(NoiseId("negative exponent"), density, -1.0)
        val _ = whiteNoise(NoiseId("zero"), density * 0.0.real)
      ))

    test("unproven parameter defaults stay symbolic"):
      val source = ScalaToMlirBridge.lower(new AnalogNoiseParameter)
      assert(source.text.contains("nodal.parameter_ref"))
      assert(source.text.contains("nodal.analog_noise"))

    test("tables require nonempty unique nonnegative frequency-power pairs"):
      reject({ val _ = tableNoise(NoiseId("bad"), Seq.empty) }, "NODAL-ANALOG-039-002")
      reject({ val _ = tableNoise(NoiseId("bad"), Seq(NoisePoint(1.0.real, density))) }, "NODAL-ANALOG-039-003")
      reject({ val _ = tableNoise(NoiseId("bad"), Seq(NoisePoint(-1.0.real / 1.0.s, density))) }, "NODAL-ANALOG-039-004")
      reject({ val _ = tableNoise(NoiseId("bad"), Seq.fill(2)(NoisePoint(0.0.real / 1.0.s, density))) }, "NODAL-ANALOG-039-004")
      reject({ val _ = tableNoise(NoiseId("bad"), Seq(NoisePoint(1.0.real / 1.0.s, density * -1.0.real))) }, "NODAL-ANALOG-039-004")
      val _ = ConstructionKernel.inspect(new AnalogNoiseBody(() =>
        val _ = tableNoise(NoiseId("one point"), Seq(NoisePoint(0.0.real / 1.0.s, density)))
      ))

    test("unsafe labels and unsupported capability selections fail closed"):
      Seq("", "quote\"", "back\\slash", "new\nline", "nonascii\u00e9").foreach: label =>
        reject({ val _ = whiteNoise(NoiseId(label), density) }, "NODAL-ANALOG-039-005")
      reject({ val _ = whiteNoise(NoiseId("bad"), density,
        NoiseOptions(correlation = NoiseCorrelation.Group("shared"))) }, "NODAL-ANALOG-039-006")
      Seq(AnalysisApplicability.All, AnalysisApplicability.only(AnalysisKind.Transient),
        AnalysisApplicability.only(AnalysisKind.Noise, AnalysisKind.Ac)).foreach: analyses =>
        reject({ val _ = whiteNoise(NoiseId("bad"), density, NoiseOptions(analyses = analyses)) }, "NODAL-ANALOG-039-006")

    test("noise effects reject unsupported construction contexts and nesting"):
      val failure = scala.util.Try(ConstructionKernel.inspect(new AnalogNoiseOutside))
        .failed.get.asInstanceOf[ConstructionException]
      assert(failure.diagnostic.code == "NODAL-ANALOG-039-001")
      reject({ val _ = ConstructionKernel.waveformForbidden(whiteNoise(NoiseId("bad"), density)) }, "NODAL-ANALOG-039-001")
      reject({
        val source = whiteNoise(NoiseId("inner"), 1.0.s)
        val _ = whiteNoise(NoiseId("outer"), density * source)
      }, "NODAL-ANALOG-039-006")
