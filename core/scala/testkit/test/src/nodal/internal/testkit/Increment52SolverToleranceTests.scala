package nodal.internal.testkit

import scala.compiletime.testing.typeCheckErrors
import scala.util.Try

import nodal.*
import nodal.internal.integrations.*
import nodal.sim.*
import utest.*

object Increment52SolverToleranceTests extends TestSuite:
  private def generated(tolerances: SolverTolerances): NgspiceGeneratedBench =
    val positive = NgspiceNode.Node(0)
    val source = NgspiceSourceId(0)
    NgspiceBench.generate(NgspiceCircuitBench(
      NgspiceCircuitBinding("ScalarModel", Vector(positive, NgspiceNode.Ground)),
      Vector(positive),
      Vector(NgspiceSource(
        source,
        NgspiceSourceKind.Voltage,
        positive,
        NgspiceNode.Ground,
        0
      )),
      NgspiceCircuitAnalysis.DcSweep(source, -0.05, 0, 0.05),
      Vector(NgspiceProbe.Current(source)),
      options = tolerances.lowered
    )).toOption.get

  val tests: Tests = Tests:
    test("default public tolerances preserve every existing solver option exactly"):
      val tolerances = SolverTolerances()
      assert(
        tolerances.relative == 1e-6,
        tolerances.currentAbsolute.siValue == 1e-12,
        tolerances.voltageAbsolute.siValue == 1e-9,
        tolerances.lowered == NgspiceSolverOptions()
      )
      assert(generated(tolerances).deck.contains(
        ".options reltol=1.0E-6 abstol=1.0E-12 vntol=1.0E-9 temp=27.0 tnom=27.0\n"
      ))

    test("explicit stricter tolerances reach the shared circuit renderer without other knobs"):
      val tolerances = SolverTolerances(1e-8, 1e-18.amps, 1e-11.volts)
      assert(tolerances.lowered == NgspiceSolverOptions(1e-8, 1e-18, 1e-11))
      assert(generated(tolerances).deck.contains(
        ".options reltol=1.0E-8 abstol=1.0E-18 vntol=1.0E-11 temp=27.0 tnom=27.0\n"
      ))
      val currentOnly = SolverTolerances(currentAbsolute = 1e-18.amps)
      assert(currentOnly.lowered == NgspiceSolverOptions(abstol = 1e-18))
      assert(generated(currentOnly).deck == generated(SolverTolerances()).deck.replace(
        "abstol=1.0E-12",
        "abstol=1.0E-18"
      ))

    test("nonfinite zero negative and unsupported relative tolerances reject"):
      for value <- Vector(
          Double.NaN,
          Double.PositiveInfinity,
          Double.NegativeInfinity,
          -1.0,
          -0.0,
          0.0
        )
      do
        assert(Try(SolverTolerances(relative = value)).isFailure)
        assert(Try(SolverTolerances(currentAbsolute = value.amps)).isFailure)
        assert(Try(SolverTolerances(voltageAbsolute = value.volts)).isFailure)
      assert(Try(SolverTolerances(relative = Math.nextUp(1e-3))).isFailure)
      assert(SolverTolerances(relative = 1e-3).relative == 1e-3)
      val positiveBoundary = SolverTolerances(
        Double.MinPositiveValue,
        Double.MinPositiveValue.amps,
        Double.MinPositiveValue.volts
      )
      assert(positiveBoundary.lowered.abstol == Double.MinPositiveValue)
      assert(generated(positiveBoundary).kind == NgspiceAnalysisKind.Dc)

    test("shared adapter independently rejects invalid lowered solver values"):
      val defaults = SolverTolerances().lowered
      val model = NgspiceModuleBinding(
        "ScalarModel",
        Vector(NgspiceTerminal.Driven, NgspiceTerminal.Ground)
      )
      val analysis = NgspiceAnalysis.DcVoltageSweep(-0.05, 0, 0.05)
      for options <- Vector(
          defaults.copy(reltol = Double.NaN),
          defaults.copy(reltol = Math.nextUp(1e-3)),
          defaults.copy(abstol = 0),
          defaults.copy(vntol = -1)
        )
      do
        assert(NgspiceBench.generate(model, analysis, options).left.toOption.exists(
          _.contains("solver tolerances")
        ))

    test("absolute solver tolerances retain separate electrical dimensions"):
      val currentMismatch = typeCheckErrors("""
        import nodal.*
        import nodal.sim.*
        SolverTolerances(currentAbsolute = 1e-18.volts)
      """)
      val voltageMismatch = typeCheckErrors("""
        import nodal.*
        import nodal.sim.*
        SolverTolerances(voltageAbsolute = 1e-11.amps)
      """)
      val rawMismatch = typeCheckErrors("""
        import nodal.sim.*
        SolverTolerances(currentAbsolute = 1e-18)
      """)
      val valid = typeCheckErrors("""
        import nodal.*
        import nodal.sim.*
        val tolerances = SolverTolerances(1e-8, 1e-18.amps, 1e-11.volts)
        def create(
            model: CompiledSimulationModel,
            bindings: Vector[(ModelPort, SimulationNode)],
            sources: Vector[SimulationSourceDrive],
            analysis: SimulationAnalysis,
            probes: Vector[SimulationProbe],
            initial: Vector[SimulationInitialCondition]
        ) = (
          SimulationBench.create(model, bindings, sources, analysis, probes),
          SimulationBench.create(model, bindings, sources, analysis, probes, initial),
          SimulationBench.create(model, bindings, sources, analysis, probes, initial, tolerances),
          SimulationBench.create(model, bindings, sources, analysis, probes,
            solverTolerances = tolerances)
        )
      """)
      assert(valid.isEmpty)
      assert(Vector(currentMismatch, voltageMismatch, rawMismatch).forall(_.nonEmpty))
