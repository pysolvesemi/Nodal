package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.{BridgeException, ScalaToMlirBridge}
import nodal.sim.*
import utest.*

final class Increment52InvalidDimension(kind: String) extends Module:
  val positive = inout(Electrical)
  val referenceNode = inout(Electrical)
  analog:
    kind match
      case "exponent" =>
        I(positive, referenceNode) <+ 1.0.A * AnalogMath.exp(V(positive, referenceNode))
      case "phase-initial" =>
        V(positive, referenceNode) <+ 1.0.V * AnalogMath.sin(idt(1.0.real / 1.0.s, 1.0.V))
      case "sine" =>
        V(positive, referenceNode) <+ 1.0.V * AnalogMath.sin(1.0.s)
      case _ => ()

/** Real-valued expression defaults are deliberately outside the current literal-default profile. */
final class Increment52CompoundDefaultSource extends Module:
  val positive = inout(Electrical)
  val referenceNode = inout(Electrical)
  val coefficient: Param[Real] = param(2.0.real * 3.0.real)
  analog:
    V(positive, referenceNode) <+ coefficient * 1.0.V

object Increment52ReferenceTests extends TestSuite:
  import Increment52References.*

  private def near(actual: Double, expected: Double, tolerance: Double = 1e-12): Unit =
    assert(math.abs(actual - expected) <= tolerance)

  val tests: Tests = Tests:
    test("RLC reference retains resonance phase DC and high-frequency limits"):
      val dc = rlcTransfer(0)
      assert(dc.real == 1.0, dc.imaginary == 0.0)
      val resonantHertz = 1.0 / (2.0 * math.Pi * math.sqrt(InductanceHenries * CapacitanceFarads))
      val resonance = rlcTransfer(resonantHertz)
      near(resonance.real, 0)
      near(resonance.imaginary, -1)
      val high = rlcTransfer(100000)
      assert(high.real < 0, high.imaginary < 0, math.hypot(high.real, high.imaginary) < 0.001)
      assert(rlcStepVoltage(0) == 0)
      assert(rlcStepVoltage(0.00036) > 1.1)
      near(rlcStepVoltage(0.01), 1)
      assert(!RlcTolerance.accepts(-resonance.imaginary, resonance.imaginary))

    test("diode reference resolves zero reverse saturation exponential slope and current sign"):
      assert(diodeCurrent(0) == 0, diodeSourceCurrent(0) == 0)
      val reverse = diodeCurrent(-0.1)
      assert(reverse < -0.98e-12, reverse > -1e-12)
      assert(diodeSourceCurrent(-0.1) > 0)
      assert(!DiodeTolerance.accepts(0, reverse))
      assert(!DiodeTolerance.accepts(-reverse, reverse))
      near(diodeCurrent(ThermalVolts), SaturationAmps * (math.E - 1), 1e-26)
      near(diodeConductance(0), SaturationAmps / ThermalVolts, 1e-26)
      val delta = 1e-7
      val derivative =
        (diodeCurrent(DiodeBiasVolts + delta) -
          diodeCurrent(DiodeBiasVolts - delta)) / (2 * delta)
      assert(math.abs(derivative / diodeConductance(DiodeBiasVolts) - 1) < 1e-8)

    test("affine and clipped references preserve knees and surrounding values"):
      val lowerKnee = (LowerVolts - AmplifierOffsetVolts) / AmplifierGain
      val upperKnee = (UpperVolts - AmplifierOffsetVolts) / AmplifierGain
      assert(amplifierVoltage(0, false) == AmplifierOffsetVolts)
      assert(amplifierVoltage(-1, false) < LowerVolts)
      assert(amplifierVoltage(1, false) > UpperVolts)
      near(amplifierVoltage(lowerKnee, true), LowerVolts)
      near(amplifierVoltage(upperKnee, true), UpperVolts)
      assert(amplifierVoltage(lowerKnee - 1e-8, true) == LowerVolts)
      assert(amplifierVoltage(lowerKnee + 1e-8, true) > LowerVolts)
      assert(amplifierVoltage(upperKnee - 1e-8, true) < UpperVolts)
      assert(amplifierVoltage(upperKnee + 1e-8, true) == UpperVolts)

    test("comparator equality is high with no stateful hysteresis"):
      val below = math.nextAfter(ComparatorThresholdVolts, Double.NegativeInfinity)
      val above = math.nextAfter(ComparatorThresholdVolts, Double.PositiveInfinity)
      assert(comparatorVoltage(below) == LowerVolts)
      assert(comparatorVoltage(ComparatorThresholdVolts) == UpperVolts)
      assert(comparatorVoltage(above) == UpperVolts)
      assert(comparatorVoltage(below) == LowerVolts)

    test("VCO phase integrates piecewise constant and ramp frequency continuously"):
      assert(integratedControl(0) == 0, integratedControl(RampStartSeconds) == 0)
      near(integratedControl(0.01), 0.00125)
      near(integratedControl(RampEndSeconds), 0.005)
      near(integratedControl(0.02), 0.01)
      near(vcoPhaseCycles(0.01), 1.0625)
      near(vcoPhaseCycles(0.02), 2.5)
      near(steppedPhaseCycles(RampStartSeconds), 0.5)
      near(steppedPhaseCycles(0.01), 1.25)
      Vector(RampStartSeconds, RampEndSeconds).foreach: boundary =>
        near(vcoPhaseCycles(boundary - 1e-12), vcoPhaseCycles(boundary + 1e-12), 4e-10)
      near(vcoVoltage(0.0025, 0), 1)
      val time = 0.017
      val wrongPhase = (BaseHertz + SensitivityHertzPerVolt) * time
      val wrongVoltage = math.sin(2 * math.Pi * wrongPhase)
      assert(!OscillatorTolerance.accepts(wrongVoltage, vcoVoltage(time)))
      assert(math.abs(wrongVoltage - vcoVoltage(time)) > 0.6)

    test("references and fixed tolerances reject invalid inputs and numerical discrepancies"):
      Vector(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity).foreach: invalid =>
        assert(scala.util.Try(diodeCurrent(invalid)).isFailure)
        assert(scala.util.Try(amplifierVoltage(invalid, true)).isFailure)
        assert(scala.util.Try(comparatorVoltage(invalid)).isFailure)
        assert(scala.util.Try(vcoPhaseCycles(invalid)).isFailure)
      assert(scala.util.Try(rlcTransfer(-1)).isFailure)
      assert(scala.util.Try(vcoPhaseCycles(-1)).isFailure)
      assert(scala.util.Try(vcoPhaseCycles(0, -1)).isFailure)
      Vector(RlcTolerance, DiodeTolerance, VoltageTolerance, OscillatorTolerance).foreach: limit =>
        assert(limit.accepts(0, 0))
        assert(!limit.accepts(Double.NaN, 0), !limit.accepts(Double.PositiveInfinity, 0))
        assert(!limit.accepts(2 * limit.absolute, 0))

    test("public scalar sources are deterministic and preserve exact boundary labels"):
      val sources: Vector[() => SimulationSource] = Vector(
        () => Increment52Sources.rlc(),
        () => Increment52Sources.diode(),
        () => Increment52Sources.amplifier(false),
        () => Increment52Sources.amplifier(true),
        () => Increment52Sources.comparator(),
        () => Increment52Sources.vco(),
        () => Increment52Sources.vco(0)
      )
      sources.foreach: construct =>
        val first = construct()
        val second = construct()
        assert(first.canonicalConstruction == second.canonicalConstruction)
        assert(first.ports.map(_.label) == second.ports.map(_.label))
        assert(first.ports.forall(_.semanticPath.startsWith(first.semanticPath + ".")))
        assert(first.port("ground").isRight)
      assert(Increment52Sources.rlc().ports.map(_.label) == Vector("input", "output", "ground"))
      assert(Increment52Sources.vco().ports.map(_.label) == Vector("control", "output", "ground"))

    test("RLC source has actual internal node and separate inductor capacitor derivatives"):
      val snapshot = ConstructionKernel.inspect(new Increment52Rlc)
      val internal = snapshot.modules.head.declarations.filter(_.kind == "analog-node")
      assert(internal.size == 1)
      assert(snapshot.analogRegions.flatMap(_.contributions).size == 3)
      assert(snapshot.continuousOperators.map(_.operation) == Vector("analog_ddt", "analog_ddt"))
      assert(snapshot.continuousOperators.map(_.inputDimension) == Vector("current", "voltage"))
      assert(snapshot.continuousOperators.map(_.path).distinct.size == 2)
      assert(snapshot.continuousOperators.forall(_.stateId.isEmpty))
      val text = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text
      val expressions = snapshot.analogRegions.flatMap(_.expressions)
      val inductance = expressions.find(expression =>
        expression.operation == "analog_mul" &&
          expression.operands.exists(_.endsWith(".inductancePerSecond"))
      ).get
      val timeScale = expressions.find(_.path == inductance.operands.last).get
      assert(timeScale.unit.contains("s"), timeScale.literal.contains("1.0"))
      assert(snapshot.continuousOperators.head.resultDimension == "current*time^-1")
      assert(text.contains("\"nodal.analog_mul\""))
      assert(text.contains("\"nodal.analog_ddt\""))

    test("native function definitions preserve affine clip and comparator equality operators"):
      val affine = ConstructionKernel.inspect(new Increment52Amplifier(false)).analogFunctions
      val clipped = ConstructionKernel.inspect(new Increment52Amplifier(true)).analogFunctions
      val comparator = ConstructionKernel.inspect(new Increment52Comparator).analogFunctions
      assert(affine.size == 1, clipped.size == 1, comparator.size == 1)
      assert(!affine.head.definition.nodes.exists(_.operation == "select"))
      assert(clipped.head.definition.nodes.count(_.operation == "select") == 2)
      val comparison = comparator.head.definition
      assert(comparison.result.dimension == "voltage")
      assert(comparison.nodes.count(_.operation == "lt") == 1)
      assert(comparison.nodes.count(_.operation == "select") == 1)
      assert(!comparison.nodes.exists(node => Set("le", "gt", "ge").contains(node.operation)))

    test(
      "VCO source carries a stateful dimensionless phase integral instead of elapsed-time product"
    ):
      val snapshot = ConstructionKernel.inspect(new Increment52Vco)
      assert(snapshot.continuousOperators.size == 1)
      val phase = snapshot.continuousOperators.head
      assert(phase.operation == "analog_idt", phase.stateId.nonEmpty)
      assert(phase.inputDimension == "time^-1", phase.resultDimension == "1")
      assert(phase.initialCondition.nonEmpty)
      val operations = snapshot.analogRegions.flatMap(_.expressions).map(_.operation)
      assert(operations.contains("analog_function_v1_sin"))
      assert(!operations.contains("analog_abstime"))
      assert(snapshot.waveformOperators.isEmpty)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text
      assert(text.contains("\"nodal.analog_idt\""))

    test("compound Real parameter defaults reject explicitly outside the literal-default profile"):
      val snapshot = ConstructionKernel.inspect(new Increment52CompoundDefaultSource)
      assert(snapshot.parameterExpressions.exists(_.operation == "analog_mul"))
      val failed = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA))
      assert(failed.isFailure)
      val error = failed.failed.get.asInstanceOf[BridgeException].diagnostic
      assert(error.code == "NODAL-BRIDGE-021")
      assert(error.semanticPath.contains("Increment52CompoundDefaultSource.coefficient"))
      assert(error.message.startsWith("unsupported real parameter default"))

    test("invalid component values nonfinite parameters and physical dimensions reject"):
      val invalid: Vector[() => Module] = Vector(
        () => new Increment52Rlc(resistanceOhms = 0),
        () => new Increment52Rlc(inductanceHenries = -1),
        () => new Increment52Rlc(capacitanceFarads = Double.NaN),
        () => new Increment52Diode(saturationAmps = -1),
        () => new Increment52Diode(thermalVolts = 0),
        () => new Increment52Amplifier(true, gainValue = Double.PositiveInfinity),
        () => new Increment52Amplifier(true, lowerVolts = 1, upperVolts = 1),
        () => new Increment52Comparator(thresholdVolts = Double.NaN),
        () => new Increment52Vco(baseHertz = 0),
        () => new Increment52Vco(sensitivityHertzPerVolt = -1),
        () => new Increment52Vco(amplitudeVolts = Double.NaN)
      )
      invalid.foreach(construct =>
        assert(scala.util.Try(ConstructionKernel.inspect(construct())).isFailure)
      )
      Vector("exponent", "phase-initial", "sine").foreach: kind =>
        assert(scala.util.Try(
          ConstructionKernel.inspect(new Increment52InvalidDimension(kind))
        ).isFailure)
      assert(Increment52Sources.diode().ports.size == 2)

    test("RLC internal nodes cannot be exposed as root simulation ports"):
      val invalid = SimulationSource.capture(new Increment52Rlc)(model =>
        Vector(
          PortSelection("input", model.inputNode),
          PortSelection("output", model.outputNode),
          PortSelection("ground", model.referenceNode),
          PortSelection("internal", model.junction)
        )
      )
      assert(invalid.left.toOption.exists(_.code == "SIM-CAPTURE"))
      assert(Increment52Sources.rlc().ports.size == 3)

    test("predecessor reuse retains original public model identity"):
      assert(Increment52Sources.rc().semanticPath == "Increment50Rc")
      assert(Increment52Sources.controlled().semanticPath == "Increment50WeightedInputs")
      assert(Increment52Sources.initialStep().semanticPath == "AnalogCapabilityStorage")
