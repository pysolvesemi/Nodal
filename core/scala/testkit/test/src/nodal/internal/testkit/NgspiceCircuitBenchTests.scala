package nodal.internal.testkit

import nodal.internal.integrations.*
import utest.*

object NgspiceCircuitBenchTests extends TestSuite:
  private val Ground = NgspiceNode.Ground
  private val A = NgspiceNode.Node(0)
  private val B = NgspiceNode.Node(1)
  private val Drive = NgspiceSourceId(0)
  private val Bias = NgspiceSourceId(1)
  private val Model =
    NgspiceCircuitBinding("MultiTerminal", Vector(B, Ground, A), Vector("R" -> 1000))
  private val Bench = NgspiceCircuitBench(
    Model,
    Vector(A, B),
    Vector(
      NgspiceSource(
        Drive,
        NgspiceSourceKind.Voltage,
        A,
        Ground,
        ac = Some(NgspiceAcExcitation(2, 30))
      ),
      NgspiceSource(Bias, NgspiceSourceKind.Current, Ground, B, dc = 0.001)
    ),
    NgspiceCircuitAnalysis.DcSweep(Drive, 0, 1, 0.5),
    Vector(NgspiceProbe.Current(Bias), NgspiceProbe.Voltage(B), NgspiceProbe.Current(Drive))
  )

  private def generated(bench: NgspiceCircuitBench = Bench): NgspiceGeneratedBench =
    NgspiceBench.generate(bench).toOption.get

  private def rejected(bench: NgspiceCircuitBench, message: String): Unit =
    val result = NgspiceBench.generate(bench)
    assert(result.isLeft, result.left.toOption.exists(_.contains(message)))

  val tests: Tests = Tests:
    test("ordered multi-terminal binding independent sources and exact save schema"):
      val result = generated()
      assert(result.deck.contains("Nload n1 0 n0 dut\n"))
      assert(result.deck.contains("vs0 n0 0 DC 0.0 AC 2.0 30.0\n"))
      assert(result.deck.contains("is1 0 sense1 DC 0.001\nvsense1 sense1 n1 0\n"))
      assert(result.deck.contains(".save i(vsense1) v(n1) i(vs0)\n.dc vs0 0.0 1.0 0.5\n"))
      assert(result.expectedVariables == Vector(
        "v(v-sweep)" -> "voltage",
        "i(vsense1)" -> "current",
        "v(n1)" -> "voltage",
        "i(vs0)" -> "current"
      ))
      assert(generated(Bench.copy(sources = Bench.sources.reverse, nodes = Bench.nodes.reverse)) ==
        result)
      assert(generated(Bench.copy(model = Model.copy(terminals = Vector(A, A, Ground)))).deck
        .contains("Nload n0 n0 0 dut"))

    test("named current sweep and independent AC excitation"):
      val current =
        generated(Bench.copy(analysis = NgspiceCircuitAnalysis.DcSweep(Bias, 0, 1, 0.5)))
      assert(current.deck.contains(".dc is1 0.0 1.0 0.5\n"))
      assert(current.expectedVariables.head == ("i(i-sweep)" -> "current"))
      val ac = generated(Bench.copy(analysis = NgspiceCircuitAnalysis.AcLinearSweep(3, 1, 3)))
      assert(ac.expectedVariables.head == ("frequency" -> "frequency"), ac.requestedPoints == 3)
      rejected(
        Bench.copy(
          analysis = NgspiceCircuitAnalysis.AcDecadeSweep(1, 1, 10),
          sources = Bench.sources.map(_.copy(ac = None))
        ),
        "nonzero AC"
      )
      rejected(
        Bench.copy(analysis = NgspiceCircuitAnalysis.DcSweep(NgspiceSourceId(2), 0, 1, 0.5)),
        "missing source"
      )

    test("explicit initialization UIC and PWL source are independent of transient analysis"):
      val transient = Bench.copy(
        sources = Bench.sources.updated(
          0,
          Bench.sources.head.copy(transient =
            Some(
              NgspiceTransientWaveform.Pwl(Vector(0.0 -> 0.0, 0.1 -> 1.0, 1.0 -> 1.0))
            )
          )
        ),
        analysis = NgspiceCircuitAnalysis.Transient(0.1, 1, true),
        initialConditions = Vector(NgspiceInitialCondition(B, 0.25))
      )
      val result = generated(transient)
      assert(result.deck.contains("PWL(\n+ 0.0 0.0\n+ 0.1 1.0\n+ 1.0 1.0\n+ )"))
      assert(
        result.deck.contains(".ic v(n1)=0.25\n"),
        result.deck.contains(".tran 0.1 1.0 0 0.1 uic\n")
      )
      assert(!generated(transient.copy(analysis =
        NgspiceCircuitAnalysis.Transient(0.1, 1, false)
      )).deck.contains("uic"))
      rejected(Bench.copy(initialConditions = transient.initialConditions), "transient analysis")
      rejected(
        transient.copy(initialConditions =
          transient.initialConditions ++ transient.initialConditions
        ),
        "duplicate"
      )
      rejected(
        transient.copy(initialConditions = Vector(NgspiceInitialCondition(Ground, 0))),
        "non-ground"
      )

    test("model parameter injection and numerical validation reuse established owner"):
      for name <- Vector("x\n.control", "x y", "x;quit", "é", "a" * 129) do
        rejected(Bench.copy(model = Model.copy(moduleName = name)), "module identifier")
        rejected(
          Bench.copy(model = Model.copy(parameters = Vector(name -> 1.0))),
          "parameter identifier"
        )
      rejected(
        Bench.copy(model = Model.copy(parameters = Vector("R" -> 1.0, "r" -> 2.0))),
        "duplicate"
      )
      rejected(Bench.copy(model = Model.copy(parameters = Vector("R" -> Double.NaN))), "finite")
      rejected(Bench.copy(options = NgspiceSolverOptions(reltol = Double.NaN)), "tolerances")

    test("foreign and invalid nodes missing sources and duplicate schemas reject before rendering"):
      val foreign = NgspiceNode.Node(2)
      rejected(Bench.copy(nodes = Bench.nodes :+ foreign), "no DUT or source connection")
      rejected(Bench.copy(nodes = Vector(A, A)), "duplicate declared")
      rejected(Bench.copy(nodes = Vector(A, Ground)), "non-ground")
      rejected(Bench.copy(nodes = Vector(A, NgspiceNode.Node(63))), "bounded")
      rejected(Bench.copy(model = Model.copy(terminals = Vector(A, foreign))), "undeclared")
      rejected(
        Bench.copy(sources = Bench.sources.updated(0, Bench.sources.head.copy(positive = foreign))),
        "undeclared"
      )
      rejected(
        Bench.copy(sources = Bench.sources.updated(0, Bench.sources.head.copy(negative = A))),
        "distinct"
      )
      rejected(Bench.copy(sources = Bench.sources :+ Bench.sources.head), "duplicate source")
      rejected(Bench.copy(probes = Vector(NgspiceProbe.Voltage(foreign))), "undeclared")
      rejected(
        Bench.copy(probes = Vector(NgspiceProbe.Current(NgspiceSourceId(2)))),
        "missing source"
      )
      rejected(Bench.copy(probes = Bench.probes ++ Bench.probes), "duplicate probe")
      rejected(Bench.copy(probes = Vector(NgspiceProbe.Voltage(Ground))), "ground")

    test("finite aggregate topology and schema caps"):
      rejected(Bench.copy(nodes = Vector.tabulate(64)(NgspiceNode.Node(_))), "node count")
      rejected(Bench.copy(model = Model.copy(terminals = Vector.fill(65)(A))), "terminal count")
      rejected(Bench.copy(sources = Vector.fill(64)(Bench.sources.head)), "source count")
      rejected(Bench.copy(probes = Vector.fill(64)(Bench.probes.head)), "probe count")
      rejected(Bench.copy(nodes = Vector.tabulate(63)(NgspiceNode.Node(_))), "sensor nodes")
      rejected(
        Bench.copy(sources = Vector(Bench.sources.head.copy(id = NgspiceSourceId(-1)))),
        "bounded"
      )

    test("PWL point count and every value checked before rendering"):
      def withPwl(points: Vector[(Double, Double)]): NgspiceCircuitBench = Bench.copy(
        sources = Bench.sources.updated(
          0,
          Bench.sources.head.copy(transient = Some(NgspiceTransientWaveform.Pwl(points)))
        )
      )
      Vector(
        Vector.empty,
        Vector(0.0 -> 0.0),
        Vector(0.0 -> 0.0, 0.0 -> 1.0),
        Vector(1.0 -> 0.0, 0.0 -> 1.0),
        Vector(-1.0 -> 0.0, 0.0 -> 1.0),
        Vector(0.0 -> 0.0, Double.PositiveInfinity -> 1.0),
        Vector(0.0 -> 0.0, 1.0 -> Double.NaN)
      )
        .foreach(points => rejected(withPwl(points), "PWL needs"))
      val maximum = Vector.tabulate(100000)(index => index.toDouble -> 1.0)
      assert(generated(withPwl(maximum)).deck.contains("+ 99999.0 1.0"))
      rejected(withPwl(maximum :+ (Double.NaN -> 0.0)), "total PWL")
      val both = Bench.sources.map(_.copy(transient =
        Some(NgspiceTransientWaveform.Pwl(maximum.take(50001)))
      ))
      rejected(Bench.copy(sources = both), "total PWL")

    test("source and pulse numeric fields reject nonfinite and inconsistent timings"):
      for value <- Vector(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity) do
        rejected(
          Bench.copy(sources = Bench.sources.updated(0, Bench.sources.head.copy(dc = value))),
          "DC source"
        )
        rejected(
          Bench.copy(sources =
            Bench.sources.updated(0, Bench.sources.head.copy(ac = Some(NgspiceAcExcitation(value))))
          ),
          "AC source"
        )
      val transient = Bench.copy(analysis = NgspiceCircuitAnalysis.Transient(0.01, 1, true))
      val valid = Vector(0.0, 1.0, 0.1, 0.01, 0.01, 0.1, 0.2)
      def withPulse(values: Vector[Double]): NgspiceCircuitBench = transient.copy(
        sources = Bench.sources.updated(
          0,
          Bench.sources.head.copy(transient =
            Some(
              NgspiceTransientWaveform.Pulse(
                values(0),
                values(1),
                values(2),
                values(3),
                values(4),
                values(5),
                values(6)
              )
            )
          )
        )
      )
      assert(generated(withPulse(valid)).deck.contains("PULSE(0.0 1.0 0.1 0.01 0.01 0.1 0.2)"))
      for index <- valid.indices do rejected(withPulse(valid.updated(index, Double.NaN)), "pulse")
      rejected(withPulse(valid.updated(6, 0.11)), "period")
      rejected(withPulse(valid.updated(2, 1.0)), "precede")
      rejected(withPulse(Vector(0, 1, 0, 1e-10, 1e-10, 1e-10, 1e-9)), "transitions")
      rejected(
        transient.copy(analysis = NgspiceCircuitAnalysis.Transient(0.1, Double.NaN, false)),
        "step and stop"
      )

    test("new requests retain exact grid admission and constant-size sweep rendering"):
      val maximum =
        generated(Bench.copy(analysis = NgspiceCircuitAnalysis.DcSweep(Drive, 0, 99999, 1)))
      assert(maximum.requestedPoints == 100000, maximum.deck.length < 1024)
      rejected(Bench.copy(analysis = NgspiceCircuitAnalysis.DcSweep(Drive, 0, 100000, 1)), "grid")
      rejected(
        Bench.copy(analysis = NgspiceCircuitAnalysis.DcSweep(Drive, 1e30, 1e30, 1)),
        "resolution"
      )
      rejected(Bench.copy(analysis = NgspiceCircuitAnalysis.AcLinearSweep(2, 1, 3)), "linear grid")
      rejected(
        Bench.copy(analysis = NgspiceCircuitAnalysis.AcDecadeSweep(1, 1, 3)),
        "integer-decade"
      )
      val manySources = Vector.tabulate(10)(index =>
        NgspiceSource(
          NgspiceSourceId(index),
          NgspiceSourceKind.Voltage,
          A,
          Ground
        )
      )
      rejected(
        Bench.copy(
          sources = manySources,
          probes = manySources.map(source => NgspiceProbe.Current(source.id)),
          analysis = NgspiceCircuitAnalysis.DcSweep(Drive, 0, 99999, 1)
        ),
        "raw value budget"
      )
