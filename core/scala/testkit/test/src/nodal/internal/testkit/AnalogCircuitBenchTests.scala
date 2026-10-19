package nodal.internal.testkit

import nodal.internal.integrations.*
import utest.*

object AnalogCircuitBenchTests extends TestSuite:
  private val Node = AnalogNode.Node(0)
  private val Source = AnalogSourceId(0)
  private val Bench = AnalogCircuitBench(
    AnalogCircuitBinding("Dut", Vector(Node, AnalogNode.Ground)),
    Vector(Node),
    Vector(AnalogSource(
      Source,
      AnalogSourceKind.Voltage,
      Node,
      AnalogNode.Ground,
      ac = Some(AnalogAcExcitation(1))
    )),
    AnalogCircuitAnalysis.DcSweep(Source, 0, 1, 0.5),
    Vector(AnalogProbe.Voltage(Node))
  )

  val tests: Tests = Tests:
    test("legacy names alias the same finite representation"):
      val legacy: NgspiceCircuitBench = Bench
      val shared: AnalogCircuitBench = legacy.copy(analysis =
        NgspiceCircuitAnalysis.DcSweep(NgspiceSourceId(0), 0, 1, 0.5)
      )
      assert(shared == Bench, AnalogCircuitValidation.validate(shared).isRight)
      assert(NgspiceBench.generate(shared) == NgspiceBench.generate(legacy))
      val cell: AnalogComplex = NgspiceComplex(1, 2)
      assert(cell == AnalogComplex(1, 2))

    test("shared admission does not inherit ngspice grid policy"):
      val twoPoints = Bench.copy(analysis = AnalogCircuitAnalysis.AcLinearSweep(2, 1, 2))
      assert(AnalogCircuitValidation.validate(twoPoints).isRight)
      assert(NgspiceBench.generate(twoPoints).isLeft)
      assert(AnalogCircuitValidation.nominalPointCount(AnalogAxis.Dc(0, 99999.5, 1)) ==
        Right(100000))
      assert(AnalogCircuitValidation.nominalPointCount(AnalogAxis.Dc(0, 100000, 1)).isLeft)

    test("integer decade boundary does not gain a point through log subtraction"):
      val start = 109.0 / 137.0
      val axis = AnalogAxis.AcDecade(33333, start, start * 1000)
      assert(AnalogCircuitValidation.nominalPointCount(axis) == Right(100000))

    test("sensor expansion is ngspice owned"):
      val nodes = Vector.tabulate(63)(AnalogNode.Node.apply)
      val many = Bench.copy(
        model = Bench.model.copy(terminals = nodes),
        nodes = nodes,
        sources = Vector(AnalogSource(Source, AnalogSourceKind.Current, Node, AnalogNode.Ground))
      )
      assert(AnalogCircuitValidation.validate(many).isRight)
      assert(NgspiceBench.generate(many).left.toOption.exists(_.contains("current-sensor")))

    test("shared invalid topology sources and analysis fail closed"):
      assert(AnalogCircuitValidation.validate(Bench.copy(nodes = Vector.empty)).isLeft)
      assert(AnalogCircuitValidation.validate(Bench.copy(sources =
        Bench.sources.map(_.copy(dc = Double.NaN))
      )).isLeft)
      assert(AnalogCircuitValidation.validate(Bench.copy(analysis =
        AnalogCircuitAnalysis.DcSweep(Source, 0, 1, -1)
      )).isLeft)
      assert(AnalogCircuitValidation.validate(Bench.copy(analysis =
        AnalogCircuitAnalysis.DcSweep(AnalogSourceId(1), 0, 1, 1)
      )).isLeft)
