package nodal.internal.testkit

import nodal.internal.integrations.NgspiceComplex

/** Independently authored physical references and acceptance limits, fixed before any execution.
  * These use analytical circuit equations, never generated expressions or simulated samples.
  */
private[testkit] object Increment52References:
  import Increment49References.Tolerance

  val ResistanceOhms = 100.0
  val InductanceHenries = 0.01
  val CapacitanceFarads = 1e-6
  val SaturationAmps = 1e-12
  val ThermalVolts = 0.025
  val AmplifierGain = 3.0
  val AmplifierOffsetVolts = 0.1
  val LowerVolts = -1.0
  val UpperVolts = 1.0
  val ComparatorThresholdVolts = 0.2
  val BaseHertz = 100.0
  val SensitivityHertzPerVolt = 50.0
  val AmplitudeVolts = 1.0
  val RampStartSeconds = 0.005
  val RampEndSeconds = 0.015
  val StopSeconds = 0.03
  val StepSeconds = 1e-5
  val DiodeBiasVolts = 0.4
  val RlcTolerance = Tolerance(1e-8, 1e-5)
  // Three orders below Is: reverse-current sign and scale remain observable.
  val DiodeTolerance = Tolerance(1e-15, 1e-5)
  val VoltageTolerance = Tolerance(1e-9, 1e-6)
  val OscillatorTolerance = Tolerance(0.003, 0.003)

  val ControlKnots: Vector[(Double, Double)] = Vector(
    0.0 -> 0.0,
    RampStartSeconds -> 0.0,
    RampEndSeconds -> 1.0,
    StopSeconds -> 1.0
  )

  private def finite(value: Double): Unit =
    require(value.isFinite, "finite reference input required")
  private def nonnegative(value: Double): Unit =
    require(value.isFinite && value >= 0, "finite nonnegative reference input required")

  /** Unit voltage input; H(s)=1/(LC*s^2+RC*s+1). */
  def rlcTransfer(hertz: Double): NgspiceComplex =
    nonnegative(hertz)
    val omega = 2.0 * math.Pi * hertz
    val real = 1.0 - InductanceHenries * CapacitanceFarads * omega * omega
    val imaginary = ResistanceOhms * CapacitanceFarads * omega
    val denominator = real * real + imaginary * imaginary
    NgspiceComplex(real / denominator, -imaginary / denominator)

  /** Under-damped unit-step response, zero stored energy at t=0. */
  def rlcStepVoltage(seconds: Double): Double =
    nonnegative(seconds)
    val alpha = ResistanceOhms / (2.0 * InductanceHenries)
    val omega = math.sqrt(1.0 / (InductanceHenries * CapacitanceFarads) - alpha * alpha)
    1.0 - math.exp(-alpha * seconds) *
      (math.cos(omega * seconds) + alpha / omega * math.sin(omega * seconds))

  def diodeCurrent(volts: Double): Double =
    finite(volts)
    SaturationAmps * math.expm1(volts / ThermalVolts)

  def diodeSourceCurrent(volts: Double): Double = -diodeCurrent(volts)

  /** Small-signal derivative dI/dV at the DC operating point, no capacitive branch. */
  def diodeConductance(volts: Double): Double =
    finite(volts)
    SaturationAmps / ThermalVolts * math.exp(volts / ThermalVolts)

  def amplifierVoltage(inputVolts: Double, clipped: Boolean): Double =
    finite(inputVolts)
    val affine = AmplifierGain * inputVolts + AmplifierOffsetVolts
    if clipped then math.max(LowerVolts, math.min(UpperVolts, affine)) else affine

  def comparatorVoltage(inputVolts: Double): Double =
    finite(inputVolts)
    if inputVolts < ComparatorThresholdVolts then LowerVolts else UpperVolts

  /** Integral of the control voltage in V*s for the fixed finite linear-rise PWL stimulus. */
  def integratedControl(seconds: Double): Double =
    nonnegative(seconds)
    if seconds <= RampStartSeconds then 0.0
    else if seconds < RampEndSeconds then
      val elapsed = seconds - RampStartSeconds
      elapsed * elapsed / (2.0 * (RampEndSeconds - RampStartSeconds))
    else (RampEndSeconds - RampStartSeconds) / 2.0 + seconds - RampEndSeconds

  def vcoPhaseCycles(seconds: Double, sensitivity: Double = SensitivityHertzPerVolt): Double =
    nonnegative(seconds)
    nonnegative(sensitivity)
    BaseHertz * seconds + sensitivity * integratedControl(seconds)

  def vcoVoltage(seconds: Double, sensitivity: Double = SensitivityHertzPerVolt): Double =
    AmplitudeVolts * math.sin(2.0 * math.Pi * vcoPhaseCycles(seconds, sensitivity))

  /** Independently integrated piecewise-constant frequency; equality uses the new rate. */
  def steppedPhaseCycles(seconds: Double): Double =
    nonnegative(seconds)
    BaseHertz * math.min(seconds, RampStartSeconds) +
      (BaseHertz + SensitivityHertzPerVolt) * math.max(0.0, seconds - RampStartSeconds)

  val declaration: String =
    """Independent analytical references, declared before compile/simulation.
      |RLC: series R=100 ohm, L=.01 H, shunt C=1e-6 F, internal electrical junction; H(jw)=1/(1-L*C*w*w+j*R*C*w), w=2*pi*f rad/s. Unit AC input, 10..10000 Hz. Zero-energy unit-step formula is independently available. Tolerance 1e-8 V+1e-5*abs(reference).
      |Diode: Is=1e-12 A, Vt=.025 V; branch I=Is*expm1(V/Vt), voltage-source current is negative branch current. DC -.1..+.5 V. AC bias .4 V, unit AC input, source current=-(Is/Vt)*exp(.4/Vt) A. Tolerance 1e-15 A+1e-5*abs(reference), retaining reverse-current sign and scale.
      |Amplifier: native analog function, affine 3*Vin+.1 V, clipped version bounded at -1,+1 V; knees (-1-.1)/3 and (1-.1)/3 V. Tolerance 1e-9 V+1e-6*abs(reference).
      |Comparator: memoryless native analog function, Vin<.2 V gives -1 V, Vin>=.2 V gives +1 V; equality is high. Same voltage tolerance. No hysteresis, crossing-event or noise qualification.
      |Oscillator/VCO: Vout=sin(2*pi*(100*t+K*integral(Vcontrol dt))) V; phase initial value=0 cycles, K=0 for fixed oscillator or 50 Hz/V for VCO. Control PWL (seconds,volts)=(0,0),(.005,0),(.015,1),(.03,1). Integral is 0 before .005, (t-.005)^2/.02 through .015, then .005+(t-.015) V*s. Transient step=1e-5 s, stop=.03 s. Tolerance .003 V+.003*abs(reference). Multiplying instantaneous frequency by time is not this reference.
      |Source profile: symbolic parameters use literal defaults. RLC inductance is the Ohm-valued coefficient times 1 s inside analog; VCO frequency is (base+sensitivity*Vcontrol/1 V)/1 s inside analog. Compound Real-valued parameter-default expression DAGs are outside the current bridge profile and explicitly reject with NODAL-BRIDGE-021; no new public unit API or parameter-expression feature is claimed.
      |Predecessors: RC and weighted controlled-source fixtures are reused from F50; scalar initial_step storage is reused from F49/F47. Their original units and source identities are retained.
      |""".stripMargin
