package nodal.internal.testkit

import scala.compiletime.testing.typeCheckErrors

import nodal.*
import nodal.sim.*
import utest.*

/** Deliberately declares z before a; emitted scalar ABI sorts a before z. */
final class Increment50Rc(
    val resistanceOhms: Double = 1000.0,
    val capacitanceFarads: Double = 1e-6
) extends Module:
  val z = inout(Electrical)
  val a = inout(Electrical)
  val R: Param[Real] = param(resistanceOhms.Ohm)
  val C: Param[Real] = param(capacitanceFarads.F)
  analog:
    val voltage = V(z, a)
    I(z, a) <+ (voltage / R) + (C * ddt(voltage))

/** Distinct coefficients expose swapped, missing, or accidentally synchronized stimulus pins. */
final class Increment50WeightedInputs extends Module:
  val zClock = inout(Electrical)
  val aGround = inout(Electrical)
  val qRelated = inout(Electrical)
  val bAsync = inout(Electrical)
  val rReset = inout(Electrical)
  val oOutput = inout(Electrical)
  analog:
    V(oOutput, aGround) <+
      V(zClock, aGround) + 2.0.real * V(qRelated, aGround) +
      4.0.real * V(bAsync, aGround) + 8.0.real * V(rReset, aGround)

/** This is a stimulus description. It is never lowered as the analog DUT. */
final class Increment50ClockHarness extends Module:
  val childClock = in(Clock)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.AsyncAssertSyncRelease(2),
    ResetPolarity.ActiveLow,
    1.MHz
  )
  val synchronizedReset = Rdc.sync(core.reset, stages = 3)
  val related = ClockDomain.generated(
    "related",
    childClock,
    core,
    ClockRelation.Ratio(3, 2, 90.deg),
    synchronizedReset
  )
  val asynchronous = ClockDomain.external(
    "asynchronous",
    ClockEdge.Rising,
    ResetPolicy.Async,
    ResetPolarity.ActiveHigh,
    0.8.MHz
  )

final class Increment50DigitalDomainDut extends Module:
  val z = inout(Electrical)
  val a = inout(Electrical)
  val domain = ClockDomain.external(
    "domain",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    1.MHz
  )
  analog:
    I(z, a) <+ V(z, a) / 1000.0.Ohm

/** Unsupported semantic inventories must not vanish beside an otherwise executable resistor. */
final class Increment50UnsupportedSourceEffect(effect: String) extends Module:
  val z = inout(Electrical)
  val a = inout(Electrical)
  analog:
    I(z, a) <+ V(z, a) / 1000.0.Ohm
  effect match
    case "discontinuity" =>
      discontinuity(DiscontinuityContract(DiscontinuityKind.Exact, ContinuityClass.Discontinuous)):
        ()
    case "solver-hints" => solverHints(SolverHint.MaximumStep(1.0.ns))
    case "model-validity" =>
      modelValidity(ModelValidityEnvelope(
        "validity",
        AccuracyClass.Behavioral,
        Seq(ValidityConstraint.TopologyAssumption("two terminals")),
        ViolationPolicy.Error
      ))
    case "equation" =>
      equations:
        equation(V(z, a), 1.0.V)
    case "contribution" =>
      contributions:
        contribution(I(z, a), 1.0.A)
    case "assignment" =>
      val scratch = wire(Bool)
      scratch := true.B
    case _ => ()

private[testkit] object Increment50Sources:
  def rc(resistance: Double = 1000.0, capacitance: Double = 1e-6): SimulationSource =
    SimulationSource.capture(new Increment50Rc(resistance, capacitance))(model =>
      Vector(PortSelection("ground", model.a), PortSelection("positive", model.z))
    ).toOption.get

  def weighted(): SimulationSource =
    SimulationSource.capture(new Increment50WeightedInputs)(model =>
      Vector(
        PortSelection("clock", model.zClock),
        PortSelection("related", model.qRelated),
        PortSelection("async", model.bAsync),
        PortSelection("reset", model.rReset),
        PortSelection("output", model.oOutput),
        PortSelection("ground", model.aGround)
      )
    ).toOption.get

  def harness(): DomainHarness =
    DomainHarness.capture(new Increment50ClockHarness)(model =>
      Vector(model.core, model.related, model.asynchronous)
    ).toOption.get

object Increment50SimulationTests extends TestSuite:
  val tests: Tests = Tests:
    test("public capture keeps labels and exact source identity independently of selection order"):
      val source = Increment50Sources.rc()
      assert(source.semanticPath == "Increment50Rc")
      assert(source.ports.map(_.label) == Vector("ground", "positive"))
      assert(source.ports.map(_.semanticPath) == Vector("Increment50Rc.a", "Increment50Rc.z"))
      assert(source.port("positive").toOption.get eq source.ports.last)
      assert(source.port("missing").left.toOption.exists(_.code == "SIM-PORT"))
      val second = Increment50Sources.rc()
      assert(second.ports.map(_.semanticPath) == source.ports.map(_.semanticPath))
      assert(!(second.ports.head eq source.ports.head))

    test("partial duplicate and empty source selections reject"):
      val partial = SimulationSource.capture(new Increment50Rc)(model =>
        Vector(PortSelection("positive", model.z))
      )
      val duplicate = SimulationSource.capture(new Increment50Rc)(model =>
        Vector(PortSelection("same", model.z), PortSelection("same", model.a))
      )
      val samePort = SimulationSource.capture(new Increment50Rc)(model =>
        Vector(PortSelection("first", model.z), PortSelection("second", model.z))
      )
      val empty = SimulationSource.capture(new Increment50Rc)(_ => Vector.empty)
      assert(Vector(partial, duplicate, samePort, empty).forall(_.isLeft))

    test("foreign source handles reject and later transactions recover"):
      var foreign = Option.empty[Node[Electrical.type]]
      val first = SimulationSource.capture(new Increment50Rc): model =>
        foreign = Some(model.z)
        Vector(PortSelection("z", model.z), PortSelection("a", model.a))
      assert(first.isRight)
      val rejected = SimulationSource.capture(new Increment50Rc)(model =>
        Vector(PortSelection("z", foreign.get), PortSelection("a", model.a))
      )
      assert(rejected.left.toOption.exists(_.code == "SIM-CAPTURE"))
      assert(Increment50Sources.rc().ports.size == 2)

    test("unlowered source effects cannot disappear beside a valid analog resistor"):
      val reasons = Vector(
        "discontinuity" -> "candidate-discontinuity",
        "solver-hints" -> "candidate-solver-hints",
        "model-validity" -> "candidate-model-validity",
        "equation" ->
          "source equation/contribution inventories have no executable simulator lowering",
        "contribution" ->
          "source equation/contribution inventories have no executable simulator lowering",
        "assignment" -> "source operation has no supported simulator lowering: assignment"
      )
      reasons.foreach: (effect, reason) =>
        val captured =
          SimulationSource.capture(new Increment50UnsupportedSourceEffect(effect))(model =>
            Vector(PortSelection("positive", model.z), PortSelection("ground", model.a))
          )
        assert(captured.left.toOption.exists(error =>
          error.code == "SIM-CAPTURE" && error.message.contains(reason)
        ))
      assert(SimulationSource.capture(new Increment50UnsupportedSourceEffect("none"))(model =>
        Vector(PortSelection("positive", model.z), PortSelection("ground", model.a))
      ).isRight)

    test("nonfinite and invalid tolerance inputs reject"):
      assert(scala.util.Try(Tolerance((-1.0).volts, 0)).isFailure)
      for value <- Vector(-1.0, Double.NaN, Double.PositiveInfinity) do
        assert(scala.util.Try(Tolerance(0.0.volts, value)).isFailure)
      assert(scala.util.Try(Double.NaN.volts).isFailure)

    test("voltage current and time dimensions cannot be interchanged"):
      val driveMismatch = typeCheckErrors("""
        import nodal.*
        import nodal.sim.*
        ElectricalDrive.voltage(0, SimulationNode.Node(0), SimulationNode.Ground, 1.0.amps)
      """)
      val assertionMismatch = typeCheckErrors("""
        import nodal.*
        import nodal.sim.*
        def mismatch(measurement: Measurement[VoltageDimension]) =
          SimulationAssertion.check(measurement, 1.0.amps, Tolerance(0.1.volts, 0.0))
      """)
      val sweepMismatch = typeCheckErrors("""
        import nodal.*
        import nodal.sim.*
        val v = ElectricalDrive.voltage(0, SimulationNode.Node(0), SimulationNode.Ground, 0.0.volts)
        SweepAxis("voltage", v, Vector(1.0.amps))
      """)
      val timeMismatch = typeCheckErrors("""
        import nodal.*
        import nodal.sim.*
        SimulationAnalysis.transient(1.0.volts, 1.0.seconds, false)
      """)
      val valid = typeCheckErrors("""
        import nodal.*
        import nodal.sim.*
        val v = ElectricalDrive.voltage(0, SimulationNode.Node(0), SimulationNode.Ground, 0.0.volts)
        val axis = SweepAxis("voltage", v, Vector(1.0.volts))
        def check(measurement: Measurement[VoltageDimension]) =
          SimulationAssertion.check(measurement, 1.0.volts, Tolerance(0.1.volts, 0.0))
        val analysis = SimulationAnalysis.transient(0.1.seconds, 1.0.seconds, false)
      """)
      assert(valid.isEmpty)
      assert(Vector(
        driveMismatch,
        assertionMismatch,
        sweepMismatch,
        timeMismatch
      ).forall(_.nonEmpty))

    test("public PWL rejects unordered repeated negative and excessive points"):
      val voltage = 0.0.volts
      assert(VoltageWaveform(Vector(
        0.0.seconds -> voltage,
        1.0.seconds -> 1.0.volts
      )).points.size == 2)
      for times <-
          Vector(Vector.empty[Double], Vector(0.0, 0.0), Vector(1.0, 0.0), Vector(-1.0, 0.0))
      do
        assert(scala.util.Try(VoltageWaveform(times.map(time =>
          time.seconds -> voltage
        ))).isFailure)
      assert(scala.util.Try(VoltageWaveform(Vector.tabulate(100001)(index =>
        index.toDouble.seconds -> voltage
      ))).isFailure)

      assert(scala.util.Try(ElectricalDrive.current(
        0,
        SimulationNode.Ground,
        SimulationNode.Node(0),
        0.0.amps,
        waveform = Vector.tabulate(100001)(index => index.toDouble.seconds -> 0.0.amps)
      )).isFailure)

    test("harness domain identities and reset references remain separate from the analog DUT"):
      val harness = Increment50Sources.harness()
      val core = harness.domains(0)
      val related = harness.domains(1)
      val asynchronous = harness.domains(2)
      assert(related.parent.contains(core), asynchronous.parent.isEmpty)
      assert(core.frequencyHz == 1e6, related.frequencyHz == 1.5e6, asynchronous.frequencyHz == 8e5)
      assert(related.synchronizationStages.contains(3))
      assert(related.resetSource != related.rawResetSource)
      assert(Increment50Sources.weighted().ports.size == 6)
