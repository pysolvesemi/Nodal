package nodal.increment41fixture

import nodal.*

/** Public-only source witness; qualification retains actual native output separately. */
final class FunctionAmplifier extends Module:
  val stimulus = inout(Electrical)
  val amplified = inout(Electrical)
  val shaped = inout(Electrical)
  val reference = inout(Electrical)
  val gain = param(2.0.real)
  val mode = param(1.integer)

  val affine = AnalogFunction("affineSignal", Real, PhysicalDimension.Voltage): f =>
    val signal = f.input("signal", Real, PhysicalDimension.Voltage)
    val factor = f.input("factor", Real)
    val offset = f.input("offset", Real, PhysicalDimension.Voltage)
    val scaled = f.local("scaled", signal * factor)
    scaled + offset

  val limited = AnalogFunction("limitSignal", Real, PhysicalDimension.Voltage): f =>
    val signal = f.input("signal", Real, PhysicalDimension.Voltage)
    val ceiling = f.input("ceiling", Real, PhysicalDimension.Voltage)
    val adjusted = f.local("adjusted", affine(signal, 1.0.real, 0.0.V))
    f.select(adjusted > ceiling, ceiling, adjusted)

  val incrementCount = AnalogFunction("incrementCount", Integer): f =>
    val count = f.input("count", Integer)
    val nextCount = f.local("nextCount", f.add(count, 1.integer))
    nextCount

  val chooseGain = AnalogFunction("chooseGain", Real): f =>
    val selector = f.input("selector", Integer)
    val low = f.input("low", Real)
    val high = f.input("high", Real)
    f.select(f.lessThan(selector, 2.integer), low, high)

  val half = AnalogFunction("halfSignal", Real, PhysicalDimension.Voltage): f =>
    val signal = f.input("signal", Real, PhysicalDimension.Voltage)
    // The emitted literal expression must remain real division, not 1 / 2.
    signal * (1.0.real / 2.0.real)

  val curve = AnalogFunction("curveSignal", Real): f =>
    val signal = f.input("signal", Real)
    val square = f.local("square", signal * signal)
    AnalogMath.sqrt(square + 1.0.real)

  analog:
    val selectedGain = chooseGain(incrementCount(mode), 1.0.real, gain)
    val linear = affine(V(stimulus, reference), selectedGain, 0.0.V)
    V(amplified, reference) <+ half(limited(linear, 2.0.V))
    val curved = curve(V(stimulus, reference) / 1.0.V) * 1.0.V
    V(shaped, reference) <+ laplaceNd(curved, Seq(1.0.real), Seq(1.0.real, 1.0e-3.s))

object Increment41ConstructionCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.isEmpty, "no arguments expected")
    val _ = Nodal.emit(new FunctionAmplifier)
    println("Increment 41 public analog-function construction passed")
