package nodal.internal.testkit

import nodal.*
import nodal.increment47fixture.AnalogCapabilityStorage
import nodal.sim.*

/** Series R and L followed by shunt C; the junction is an actual internal electrical node. */
final class Increment52Rlc(
    resistanceOhms: Double = Increment52References.ResistanceOhms,
    inductanceHenries: Double = Increment52References.InductanceHenries,
    capacitanceFarads: Double = Increment52References.CapacitanceFarads
) extends Module:
  require(Vector(resistanceOhms, inductanceHenries, capacitanceFarads)
    .forall(value => value.isFinite && value > 0))
  val inputNode = inout(Electrical)
  val outputNode = inout(Electrical)
  val referenceNode = inout(Electrical)
  val junction = node(Electrical)
  val R: Param[Real] = param(resistanceOhms.Ohm)
  // H = Ohm*s. Keep a supported literal default and apply the physical time scale in analog.
  val inductancePerSecond: Param[Real] = param(inductanceHenries.Ohm)
  val C: Param[Real] = param(capacitanceFarads.F)
  analog:
    val L = inductancePerSecond * 1.0.s
    I(inputNode, junction) <+ V(inputNode, junction) / R
    V(junction, outputNode) <+ L * ddt(I(junction, outputNode))
    I(outputNode, referenceNode) <+ C * ddt(V(outputNode, referenceNode))

/** Finite Shockley profile: no breakdown, charge storage, temperature model or limiting claim. */
final class Increment52Diode(
    saturationAmps: Double = Increment52References.SaturationAmps,
    thermalVolts: Double = Increment52References.ThermalVolts
) extends Module:
  require(Vector(saturationAmps, thermalVolts).forall(value => value.isFinite && value > 0))
  val positive = inout(Electrical)
  val referenceNode = inout(Electrical)
  val saturation: Param[Real] = param(saturationAmps.A)
  val thermal: Param[Real] = param(thermalVolts.V)
  analog:
    I(positive, referenceNode) <+
      saturation *
      (AnalogMath.exp(V(positive, referenceNode) / thermal) - 1.0.real)

/** One native analog function, with all physical values passed explicitly rather than captured. */
final class Increment52Amplifier(
    clipped: Boolean,
    gainValue: Double = Increment52References.AmplifierGain,
    offsetVolts: Double = Increment52References.AmplifierOffsetVolts,
    lowerVolts: Double = Increment52References.LowerVolts,
    upperVolts: Double = Increment52References.UpperVolts
) extends Module:
  require(Vector(gainValue, offsetVolts, lowerVolts, upperVolts).forall(_.isFinite))
  require(lowerVolts < upperVolts)
  val inputNode = inout(Electrical)
  val outputNode = inout(Electrical)
  val referenceNode = inout(Electrical)
  val gain: Param[Real] = param(gainValue.real)
  val offset: Param[Real] = param(offsetVolts.V)
  val lower: Param[Real] = param(lowerVolts.V)
  val upper: Param[Real] = param(upperVolts.V)
  val transfer = AnalogFunction("amplify", Real, PhysicalDimension.Voltage): f =>
    val signal = f.input("signal", Real, PhysicalDimension.Voltage)
    val factor = f.input("factor", Real)
    val bias = f.input("bias", Real, PhysicalDimension.Voltage)
    val low = f.input("low", Real, PhysicalDimension.Voltage)
    val high = f.input("high", Real, PhysicalDimension.Voltage)
    val affine = f.local("affine", factor * signal + bias)
    if clipped then f.select(affine < low, low, f.select(affine > high, high, affine))
    else affine
  analog:
    V(outputNode, referenceNode) <+
      transfer(V(inputNode, referenceNode), gain, offset, lower, upper)

/** Memoryless comparator: equality is explicitly high; no hysteresis or crossing event. */
final class Increment52Comparator(
    thresholdVolts: Double = Increment52References.ComparatorThresholdVolts,
    lowerVolts: Double = Increment52References.LowerVolts,
    upperVolts: Double = Increment52References.UpperVolts
) extends Module:
  require(Vector(thresholdVolts, lowerVolts, upperVolts).forall(_.isFinite))
  require(lowerVolts < upperVolts)
  val inputNode = inout(Electrical)
  val outputNode = inout(Electrical)
  val referenceNode = inout(Electrical)
  val threshold: Param[Real] = param(thresholdVolts.V)
  val lower: Param[Real] = param(lowerVolts.V)
  val upper: Param[Real] = param(upperVolts.V)
  val compare = AnalogFunction("compare", Real, PhysicalDimension.Voltage): f =>
    val signal = f.input("signal", Real, PhysicalDimension.Voltage)
    val trip = f.input("trip", Real, PhysicalDimension.Voltage)
    val low = f.input("low", Real, PhysicalDimension.Voltage)
    val high = f.input("high", Real, PhysicalDimension.Voltage)
    f.select(signal < trip, low, high)
  analog:
    V(outputNode, referenceNode) <+ compare(V(inputNode, referenceNode), threshold, lower, upper)

/** Behavioral oscillator/VCO: integrate instantaneous frequency, including during a control ramp.
  */
final class Increment52Vco(
    baseHertz: Double = Increment52References.BaseHertz,
    sensitivityHertzPerVolt: Double = Increment52References.SensitivityHertzPerVolt,
    amplitudeVolts: Double = Increment52References.AmplitudeVolts
) extends Module:
  require(baseHertz.isFinite && baseHertz > 0)
  require(sensitivityHertzPerVolt.isFinite && sensitivityHertzPerVolt >= 0)
  require(amplitudeVolts.isFinite && amplitudeVolts > 0)
  val control = inout(Electrical)
  val outputNode = inout(Electrical)
  val referenceNode = inout(Electrical)
  val base: Param[Real] = param(baseHertz.real)
  val sensitivity: Param[Real] = param(sensitivityHertzPerVolt.real)
  val amplitude: Param[Real] = param(amplitudeVolts.V)
  analog:
    val frequency = (base + sensitivity * V(control, referenceNode) / 1.0.V) / 1.0.s
    val cycles = idt(frequency, 0.0.real)
    V(outputNode, referenceNode) <+ amplitude * AnalogMath.sin((2.0 * math.Pi).real * cycles)

private[testkit] object Increment52Sources:
  private def get[A](result: Either[SimulationError, A]): A =
    result.fold(
      error =>
        scala.util.Failure[A](new IllegalArgumentException(s"${error.code}: ${error.message}")).get,
      identity
    )

  def rlc(): SimulationSource = get(SimulationSource.capture(new Increment52Rlc)(model =>
    Vector(
      PortSelection("input", model.inputNode),
      PortSelection("output", model.outputNode),
      PortSelection("ground", model.referenceNode)
    )
  ))

  def diode(): SimulationSource = get(SimulationSource.capture(new Increment52Diode)(model =>
    Vector(PortSelection("positive", model.positive), PortSelection("ground", model.referenceNode))
  ))

  def amplifier(clipped: Boolean): SimulationSource =
    get(SimulationSource.capture(new Increment52Amplifier(clipped))(model =>
      Vector(
        PortSelection("input", model.inputNode),
        PortSelection("output", model.outputNode),
        PortSelection("ground", model.referenceNode)
      )
    ))

  def comparator(): SimulationSource = get(SimulationSource.capture(new Increment52Comparator)(
    model =>
      Vector(
        PortSelection("input", model.inputNode),
        PortSelection("output", model.outputNode),
        PortSelection("ground", model.referenceNode)
      )
  ))

  def vco(sensitivity: Double = Increment52References.SensitivityHertzPerVolt): SimulationSource =
    get(SimulationSource.capture(new Increment52Vco(sensitivityHertzPerVolt = sensitivity))(model =>
      Vector(
        PortSelection("control", model.control),
        PortSelection("output", model.outputNode),
        PortSelection("ground", model.referenceNode)
      )
    ))

  // Reuse the original public-source fixtures rather than maintaining semantic copies.
  def rc(): SimulationSource = Increment50Sources.rc()
  def controlled(): SimulationSource = Increment50Sources.weighted()
  def initialStep(): SimulationSource =
    get(SimulationSource.capture(new AnalogCapabilityStorage)(model =>
      Vector(PortSelection("positive", model.positive), PortSelection("ground", model.negative))
    ))
