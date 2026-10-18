package nodal.internal.testkit

import nodal.internal.integrations.*
import utest.*

object NgspiceBenchTests extends TestSuite:
  private val Model = NgspiceModuleBinding(
    "RcFilter",
    Vector(NgspiceTerminal.Ground, NgspiceTerminal.Driven),
    Vector("R" -> 1000.0, "C" -> 1e-6)
  )
  private val Dc = NgspiceAnalysis.DcVoltageSweep(0, 1, 0.25)
  private val Pulse = NgspiceCurrentStimulus.Pulse(0, 0.001, 0.001, 0.0001, 0.0001, 0.02, 0.04)
  private val Transient = NgspiceAnalysis.TransientCurrent(Pulse, 1e-5, 0.01)
  private val Nonfinite = Vector(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity)

  private def generated(
      analysis: NgspiceAnalysis = Dc,
      model: NgspiceModuleBinding = Model,
      options: NgspiceSolverOptions = NgspiceSolverOptions()
  ): NgspiceGeneratedBench = NgspiceBench.generate(model, analysis, options).toOption.get

  private def rejected(
      analysis: NgspiceAnalysis = Dc,
      model: NgspiceModuleBinding = Model,
      options: NgspiceSolverOptions = NgspiceSolverOptions(),
      message: String
  ): Unit =
    val result = NgspiceBench.generate(model, analysis, options)
    assert(result.isLeft, result.left.toOption.exists(_.contains(message)))

  val tests: Tests = Tests:
    test(
      "deterministic ASCII DC deck retains declared terminal order and canonical parameter order"
    ):
      val result = generated()
      val expected = """Nodal finite two-terminal ngspice bench
Vdrive p 0 0
Nload 0 p dut
.model dut RcFilter (C=1.0E-6 R=1000.0)
.options reltol=1.0E-6 abstol=1.0E-12 vntol=1.0E-9 temp=27.0 tnom=27.0
.save v(p) i(Vdrive)
.dc Vdrive 0.0 1.0 0.25
.end
"""
      assert(
        result.deck == expected,
        result.kind == NgspiceAnalysisKind.Dc,
        result.requestedPoints == 5
      )
      assert(
        result.deck.forall(_ <= 127),
        generated(model = Model.copy(parameters = Model.parameters.reverse)) == result
      )
      assert(
        !result.deck.contains(".control"),
        !result.deck.contains(".include"),
        !result.deck.contains("osdi")
      )

    test("binding is explicit and parameter-free models need no empty parentheses"):
      val result = generated(model =
        Model.copy(terminals = Model.terminals.reverse, parameters = Vector.empty)
      )
      assert(result.deck.contains("Nload p 0 dut\n.model dut RcFilter\n"))

    test("DC descending non-aligned and single-point grids"):
      assert(generated(NgspiceAnalysis.DcVoltageSweep(1, -1, -0.5)).requestedPoints == 5)
      assert(generated(NgspiceAnalysis.DcVoltageSweep(0, 1, 0.3)).requestedPoints == 4)
      assert(generated(NgspiceAnalysis.DcVoltageSweep(-2, -2, 0.1)).requestedPoints == 1)

    test("AC unit excitation linear and integer decade grids"):
      val linear = generated(NgspiceAnalysis.AcLinearSweep(11, 10, 10000))
      assert(linear.kind == NgspiceAnalysisKind.Ac, linear.requestedPoints == 11)
      assert(
        linear.deck.contains("Vdrive p 0 DC 0 AC 1\n"),
        linear.deck.contains(".ac lin 11 10.0 10000.0\n")
      )
      val decade = generated(NgspiceAnalysis.AcDecadeSweep(3, 10, 10000))
      assert(decade.requestedPoints == 10, decade.deck.contains(".ac dec 3 10.0 10000.0\n"))
      assert(generated(NgspiceAnalysis.AcLinearSweep(1, 100, 100)).requestedPoints == 1)

    test("transient pulse has explicit zero voltage initial state and UIC"):
      val result = generated(Transient)
      assert(result.kind == NgspiceAnalysisKind.Transient, result.requestedPoints == 1001)
      assert(result.deck.contains("Idrive 0 p PULSE(0.0 0.001 0.001 1.0E-4 1.0E-4 0.02 0.04)\n"))
      assert(result.deck.contains(".ic v(p)=0\n.save v(p)\n.tran 1.0E-5 0.01 0 1.0E-5 uic\n"))
      assert(!result.deck.contains("i(Vdrive)"))

    test("current ramp emits constant-size ordered PWL points and holds its final level"):
      val delayed = generated(NgspiceAnalysis.TransientCurrent(
        NgspiceCurrentStimulus.Ramp(-0.002, 0.001, 0.001),
        1e-5,
        0.01
      ))
      assert(delayed.deck.contains("Idrive 0 p PWL(0.0 0.0 0.001 0.0 0.002 -0.002 0.01 -0.002)\n"))
      val immediate =
        generated(NgspiceAnalysis.TransientCurrent(NgspiceCurrentStimulus.Ramp(1, 0, 1), 0.1, 1))
      assert(immediate.deck.contains("Idrive 0 p PWL(0.0 0.0 1.0 1.0)\n"))

    test("solver options including temperature and nominal temperature are explicit"):
      val result = generated(options = NgspiceSolverOptions(1e-7, 1e-14, 1e-10, -20, 80))
      assert(result.deck.contains(
        ".options reltol=1.0E-7 abstol=1.0E-14 vntol=1.0E-10 temp=-20.0 tnom=80.0\n"
      ))

    test("module and parameter identifiers reject injection whitespace Unicode and punctuation"):
      for name <- Vector(
          "",
          "0bad",
          "x\n.end\n.control",
          "x y",
          "x;quit",
          "x=1",
          "a(b)",
          "x/y",
          "x\\y",
          "x\u0000y",
          "é",
          "x" * 129
        )
      do
        rejected(model = Model.copy(moduleName = name), message = "module identifier")
        rejected(
          model = Model.copy(parameters = Vector(name -> 1.0)),
          message = "parameter identifier"
        )
      assert(generated(model =
        Model.copy(moduleName = "_module9", parameters = Vector("_p0" -> -1.0))
      ).deck.contains(".model dut _module9 (_p0=-1.0)"))

    test("parameter duplicate and finite checks respect ngspice case-insensitivity"):
      rejected(
        model = Model.copy(parameters = Vector("R" -> 1.0, "R" -> 2.0)),
        message = "duplicate"
      )
      rejected(
        model = Model.copy(parameters = Vector("R" -> 1.0, "r" -> 2.0)),
        message = "duplicate"
      )
      for value <- Nonfinite do
        rejected(model = Model.copy(parameters = Vector("R" -> value)), message = "finite")

    test("identifier and parameter count boundaries are bounded before rendering"):
      val parameters = Vector.tabulate(128)(i => s"parameter$i" -> i.toDouble)
      assert(generated(model =
        Model.copy(moduleName = "m" * 128, parameters = parameters)
      ).deck.contains("parameter127=127.0"))
      rejected(
        model = Model.copy(parameters = parameters :+ ("bad\n" -> Double.NaN)),
        message = "parameter count"
      )

    test("terminal cardinality and distinct binding are required"):
      for terminals <- Vector(
          Vector.empty,
          Vector(NgspiceTerminal.Ground),
          Vector.fill(2)(NgspiceTerminal.Ground),
          Vector.fill(2)(NgspiceTerminal.Driven),
          Model.terminals :+ NgspiceTerminal.Ground
        )
      do
        rejected(model = Model.copy(terminals = terminals), message = "terminal binding")

    test("solver tolerance sign finiteness and supported relative bound"):
      for value <- Nonfinite ++ Vector(-1.0, 0.0) do
        rejected(options = NgspiceSolverOptions(reltol = value), message = "tolerances")
        rejected(options = NgspiceSolverOptions(abstol = value), message = "tolerances")
        rejected(options = NgspiceSolverOptions(vntol = value), message = "tolerances")
      rejected(options = NgspiceSolverOptions(reltol = 0.01), message = "reltol")
      for value <- Nonfinite ++ Vector(-273.15, -274.0) do
        rejected(options = NgspiceSolverOptions(temp = value), message = "Celsius")
        rejected(options = NgspiceSolverOptions(tnom = value), message = "Celsius")

    test("DC rejects nonfinite zero wrong-direction and unresolved grids"):
      for value <- Nonfinite do
        rejected(NgspiceAnalysis.DcVoltageSweep(value, 1, 0.1), message = "finite")
        rejected(NgspiceAnalysis.DcVoltageSweep(0, value, 0.1), message = "finite")
        rejected(NgspiceAnalysis.DcVoltageSweep(0, 1, value), message = "finite")
      rejected(NgspiceAnalysis.DcVoltageSweep(0, 1, 0), message = "nonzero")
      rejected(NgspiceAnalysis.DcVoltageSweep(0, 1, -0.1), message = "toward stop")
      rejected(NgspiceAnalysis.DcVoltageSweep(1, 0, 0.1), message = "toward stop")
      rejected(NgspiceAnalysis.DcVoltageSweep(0, 1e-13, 1e-13), message = "ambiguous")
      rejected(NgspiceAnalysis.DcVoltageSweep(1e30, 1e30, 1), message = "resolution")

    test("grid cardinality is checked arithmetically before any expansion"):
      val maximum = generated(NgspiceAnalysis.DcVoltageSweep(0, 99999, 1))
      assert(maximum.requestedPoints == 100000, maximum.deck.length < 512)
      rejected(NgspiceAnalysis.DcVoltageSweep(0, 100000, 1), message = "grid")
      rejected(
        NgspiceAnalysis.DcVoltageSweep(-Double.MaxValue, Double.MaxValue, Double.MinPositiveValue),
        message = "grid"
      )
      assert(generated(NgspiceAnalysis.AcLinearSweep(100000, 1, 100000)).requestedPoints == 100000)
      rejected(NgspiceAnalysis.AcLinearSweep(Int.MaxValue, 1, 100000), message = "linear grid")
      assert(generated(NgspiceAnalysis.AcDecadeSweep(99999, 1, 10)).requestedPoints == 100000)
      rejected(NgspiceAnalysis.AcDecadeSweep(100000, 1, 10), message = "grid")
      rejected(NgspiceAnalysis.AcDecadeSweep(Int.MaxValue, 1, 10), message = "bounded")
      val ramp = NgspiceCurrentStimulus.Ramp(1, 0, 1)
      val transient = generated(NgspiceAnalysis.TransientCurrent(ramp, 1, 99999))
      assert(transient.requestedPoints == 100000, transient.deck.length < 512)
      rejected(NgspiceAnalysis.TransientCurrent(ramp, 1, 100000), message = "grid")
      rejected(
        NgspiceAnalysis.TransientCurrent(ramp, Double.MinPositiveValue, Double.MaxValue),
        message = "grid"
      )

    test("AC endpoints counts and unsupported fractional decades are rejected"):
      for value <- Nonfinite ++ Vector(0.0, -1.0) do
        rejected(NgspiceAnalysis.AcLinearSweep(3, value, 100), message = "frequencies")
        rejected(NgspiceAnalysis.AcLinearSweep(3, 1, value), message = "frequencies")
        rejected(NgspiceAnalysis.AcDecadeSweep(3, value, 100), message = "frequencies")
        rejected(NgspiceAnalysis.AcDecadeSweep(3, 1, value), message = "frequencies")
      for count <- Vector(-1, 0, 2) do
        rejected(NgspiceAnalysis.AcLinearSweep(count, 1, 100), message = "linear grid")
      rejected(NgspiceAnalysis.AcLinearSweep(1, 1, 100), message = "linear grid")
      rejected(NgspiceAnalysis.AcLinearSweep(3, 1, 1), message = "linear grid")
      rejected(NgspiceAnalysis.AcDecadeSweep(3, 1, 5), message = "integer-decade")
      rejected(NgspiceAnalysis.AcDecadeSweep(3, 1, 50), message = "integer-decade")
      rejected(NgspiceAnalysis.AcDecadeSweep(3, 1, 999.9999999999), message = "integer-decade")
      rejected(NgspiceAnalysis.AcDecadeSweep(0, 1, 10), message = "density")
      rejected(
        NgspiceAnalysis.AcDecadeSweep(10000, 1, 10),
        options = NgspiceSolverOptions(reltol = 1e-3),
        message = "solver tolerance"
      )
      rejected(
        NgspiceAnalysis.AcDecadeSweep(1, Double.MinPositiveValue, Double.MaxValue),
        message = "integer-decade"
      )

    test("transient step stop and ramp validation reject nonfinite sign and duration errors"):
      for value <- Nonfinite ++ Vector(0.0, -1.0) do
        rejected(NgspiceAnalysis.TransientCurrent(Pulse, value, 1), message = "step and stop")
        rejected(NgspiceAnalysis.TransientCurrent(Pulse, 0.1, value), message = "step and stop")
      rejected(NgspiceAnalysis.TransientCurrent(Pulse, 2, 1), message = "step <= stop")
      val badRamps = Nonfinite.map(value => NgspiceCurrentStimulus.Ramp(value, 0, 0.1)) ++
        Vector(
          NgspiceCurrentStimulus.Ramp(1, -1, 1),
          NgspiceCurrentStimulus.Ramp(1, 0, 0),
          NgspiceCurrentStimulus.Ramp(1, 0, -1),
          NgspiceCurrentStimulus.Ramp(1, 1, 1),
          NgspiceCurrentStimulus.Ramp(1, Double.NaN, 1),
          NgspiceCurrentStimulus.Ramp(1, 0, Double.PositiveInfinity)
        )
      for ramp <- badRamps do
        rejected(NgspiceAnalysis.TransientCurrent(ramp, 0.1, 1), message = "current ramp")

    test("pulse validation bounds every numeric field and periodic transition count"):
      val valid = Vector(0.0, 1.0, 0.1, 0.01, 0.01, 0.1, 0.2)
      def stimulus(values: Vector[Double]): NgspiceCurrentStimulus =
        NgspiceCurrentStimulus.Pulse(
          values(0),
          values(1),
          values(2),
          values(3),
          values(4),
          values(5),
          values(6)
        )
      for index <- valid.indices; value <- Nonfinite do
        rejected(
          NgspiceAnalysis.TransientCurrent(stimulus(valid.updated(index, value)), 0.1, 1),
          message = "current pulse"
        )
      for index <- 3 to 6; value <- Vector(0.0, -1.0) do
        rejected(
          NgspiceAnalysis.TransientCurrent(stimulus(valid.updated(index, value)), 0.1, 1),
          message = "current pulse"
        )
      rejected(
        NgspiceAnalysis.TransientCurrent(stimulus(valid.updated(2, -1)), 0.1, 1),
        message = "current pulse"
      )
      rejected(
        NgspiceAnalysis.TransientCurrent(stimulus(valid.updated(2, 1)), 0.1, 1),
        message = "current pulse"
      )
      rejected(
        NgspiceAnalysis.TransientCurrent(stimulus(valid.updated(6, 0.11)), 0.1, 1),
        message = "within the period"
      )
      val tiny = NgspiceCurrentStimulus.Pulse(0, 1, 0, 1e-10, 1e-10, 1e-10, 1e-9)
      rejected(NgspiceAnalysis.TransientCurrent(tiny, 0.1, 1), message = "requested transitions")
