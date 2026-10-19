package nodal.internal.testkit

import nodal.internal.integrations.*
import utest.*

object VacaskBenchTests extends TestSuite:
  private val N = AnalogNode.Node(0)
  private val Id = AnalogSourceId(0)
  private val Source = AnalogSource(
    Id,
    AnalogSourceKind.Voltage,
    N,
    AnalogNode.Ground,
    ac = Some(AnalogAcExcitation(2, 30))
  )
  private val Bench = AnalogCircuitBench(
    AnalogCircuitBinding("TestModel", Vector(N, AnalogNode.Ground), Vector("R" -> 1000)),
    Vector(N),
    Vector(Source),
    AnalogCircuitAnalysis.DcSweep(Id, -0.1, 0.5, 0.05),
    Vector(AnalogProbe.Voltage(N), AnalogProbe.Current(Id))
  )
  private def generated(bench: AnalogCircuitBench = Bench): AnalogGeneratedBench =
    VacaskBench.generate(bench).toOption.get

  val tests: Tests = Tests:
    test("explicit artifact native names and exact DC grid"):
      val value = generated()
      assert(value.requestedPoints == 13)
      assert(value.deck.contains("load \"model.osdi\"\nmodel dut testmodel r=1000.0\n"))
      assert(
        value.deck.contains("abort always\noptions rawfile=\"ascii\" strictsave=2 strictoutput=2")
      )
      assert(value.deck.contains(
        "save v(n0) i(vs0)\nsweep axis instance=\"vs0\" parameter=\"dc\" values=["
      ))
      assert(value.deck.contains("\n]\nanalysis result op\nendc\n"))
      assert(value.expectedVariables ==
        Vector("axis" -> "notype", "n0" -> "notype", "vs0:flow(br)" -> "notype"))
      val reverse = generated(Bench.copy(analysis = AnalogCircuitAnalysis.DcSweep(Id, 1, 0, -0.5)))
      assert(reverse.deck.contains("1.0, 0.5, 0.0"), reverse.requestedPoints == 3)
      val fractional =
        generated(Bench.copy(analysis = AnalogCircuitAnalysis.DcSweep(Id, 0, 1, 0.3)))
      assert(fractional.requestedPoints == 4)

    test("AC saves intervals singleton decade and phase"):
      val linear = generated(Bench.copy(analysis = AnalogCircuitAnalysis.AcLinearSweep(3, 1, 3)))
      assert(linear.deck.contains("save dv(n0) di(vs0)"))
      assert(linear.deck.contains("mode=\"lin\" points=2 writeop=0"))
      assert(linear.deck.contains("mag=2.0 phase=30.0"))
      assert(generated(Bench.copy(analysis = AnalogCircuitAnalysis.AcLinearSweep(1, 1, 1))).deck
        .contains("ac values=[1.0] writeop=0"))
      assert(generated(Bench.copy(analysis =
        AnalogCircuitAnalysis.AcDecadeSweep(4, 1, 100)
      )).requestedPoints == 9)
      assert(VacaskBench.generate(Bench.copy(analysis =
        AnalogCircuitAnalysis.AcDecadeSweep(4, 1, 90)
      )).isLeft)

    test("current sensor signs terminal ordering and lowercase params"):
      val current =
        generated(Bench.copy(sources = Vector(Source.copy(kind = AnalogSourceKind.Current))))
      assert(current.deck.contains("is0 (n0 sense0) isrc dc=0.0"))
      assert(current.deck.contains("vsense0 (sense0 0) vsrc dc=0"))
      assert(current.expectedVariables.last == ("vsense0:flow(br)" -> "notype"))
      assert(generated(Bench.copy(model =
        Bench.model.copy(terminals = Vector(N, N, AnalogNode.Ground))
      )).deck
        .contains("dut_instance (n0 n0 0) dut"))

    test("finite transient PWL pulse and initial conditions"):
      val pwl = Bench.copy(
        sources = Vector(Source.copy(transient =
          Some(
            AnalogTransientWaveform.Pwl(Vector(0.0 -> 0.0, 0.1 -> 1.0, 1.0 -> 1.0))
          )
        )),
        analysis = AnalogCircuitAnalysis.Transient(0.01, 1, true),
        initialConditions = Vector(AnalogInitialCondition(N, 0))
      )
      assert(generated(pwl).deck.contains("type=\"pwl\" break=\"all\" wave=[\n"))
      assert(generated(pwl).deck.contains("icmode=\"uic\" ic={\"n0\", 0.0}"))
      val pulse = pwl.copy(sources =
        Vector(Source.copy(transient =
          Some(
            AnalogTransientWaveform.Pulse(0, 1, 0, 0.1, 0.1, 0.1, 0.5)
          )
        ))
      )
      assert(generated(pulse).deck.contains("type=\"pulse\" val0=0.0 val1=1.0"))
      val equality = pulse.copy(sources =
        Vector(Source.copy(transient =
          Some(
            AnalogTransientWaveform.Pulse(0, 1, 0, 0.125, 0.125, 0.25, 0.5)
          )
        ))
      )
      assert(VacaskBench.generate(equality).isLeft)
      val bias = pwl.copy(
        sources = pwl.sources.map(_.copy(dc = 1)),
        analysis = AnalogCircuitAnalysis.Transient(0.01, 1, false)
      )
      assert(VacaskBench.generate(bias).isLeft)
      assert(VacaskBench.generate(bias.copy(analysis =
        AnalogCircuitAnalysis.Transient(0.01, 1, true)
      )).isRight)

    test("invalid topology injection nonfinite and resource limits"):
      assert(VacaskBench.generate(Bench.copy(model =
        Bench.model.copy(moduleName = "bad\nload")
      )).isLeft)
      assert(VacaskBench.generate(Bench.copy(sources =
        Vector(Source.copy(dc = Double.NaN))
      )).isLeft)
      assert(VacaskBench.generate(Bench.copy(probes =
        Vector(AnalogProbe.Voltage(AnalogNode.Node(2)))
      )).isLeft)
      assert(VacaskBench.generate(Bench.copy(analysis =
        AnalogCircuitAnalysis.DcSweep(Id, 0, 100001, 1)
      )).isLeft)
      assert(VacaskBench.generate(Bench.copy(analysis =
        AnalogCircuitAnalysis.DcSweep(Id, 1e20, 1e20, 1)
      )).isLeft)
