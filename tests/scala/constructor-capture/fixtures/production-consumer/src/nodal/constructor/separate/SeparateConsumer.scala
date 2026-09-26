package nodal.constructor.separate

import nodal.*

final class SeparateTop extends Module:
  val actual: Param[Real] = param(5.0.real)
  val gain: SeparateGain = SeparateFactory.gain(actual)
  val zero: SeparateZero = SeparateFactory.zero()

object SeparateConsumer:
  def main(arguments: Array[String]): Unit =
    val snapshot = ConstructionKernel.inspect(new SeparateTop)
    assert(snapshot.modules.map(_.path) ==
      Vector("SeparateTop", "SeparateTop.gain", "SeparateTop.zero"))
    assert(snapshot.modules.head.instances.map(_.parameterBindings) ==
      Vector(Vector("gain" -> "SeparateTop.actual"), Vector.empty))
    println("SEPARATE_COMPILE_CAPTURE_PASS")
