package nodal.increment39fixture

import nodal.*

/** Public-only, separately compiled source. PSDs use A^2/Hz = A^2*s. */
final class AnalogNoiseSource extends Module:
  val positive = inout(Electrical)
  val negative = inout(Electrical)
  val scale = param(2.0.real)
  analog:
    val density = 1.0e-18.A * 1.0.A * 1.0.s
    val shared = whiteNoise(NoiseId("thermal"), density * scale)
    val independent = whiteNoise(NoiseId("thermal"), density)
    val flicker = flickerNoise(NoiseId("flicker"), density, 1.0)
    // Input order is retained; the standard defines sorting and linear interpolation.
    val spectrum = tableNoise(
      NoiseId("spectrum"),
      Seq(
        NoisePoint(1000.0.real / 1.0.s, density),
        NoisePoint(1.0.real / 1.0.s, density * 4.0.real)
      )
    )
    I(positive, negative) <+ shared + shared + independent + flicker + spectrum
    val _ = whiteNoise(NoiseId("zero power"), 0.0.A * 1.0.A * 1.0.s)

object Increment39ConstructionCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.isEmpty, "no arguments expected")
    val _ = Nodal.emit(new AnalogNoiseSource)
    println("Increment 39 public noise-source construction passed")
