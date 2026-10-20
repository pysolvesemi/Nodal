package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Paths}

object Increment57GrayPulseReplay:
  def main(args: Array[String]): Unit =
    val directory = Paths.get(args(0))
    val _ = Files.createDirectories(directory)
    def save(name: String, module: => Module): Unit =
      val result = ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(module))
      val _ = Files.writeString(directory.resolve(name + ".mlir"), result.text)
    for
      edge <- Vector(ClockEdge.Rising, ClockEdge.Falling)
      polarity <- Vector(ResetPolarity.ActiveHigh, ResetPolarity.ActiveLow)
    do
      save(s"gray_${edge}_${polarity}", new Increment57GraySource(UInt(4), 2, edge, polarity))
      save(s"pulse_${edge}_${polarity}", new Increment57PulseSource(edge, polarity))
    save("gray_resetless", new Increment57GraySource(UInt(4), resetless = true))
    save("gray_32_stages", new Increment57GraySource(UInt(4), stages = 32))
    save("gray_three_stages", new Increment57GraySource(UInt(4), stages = 3))
    save("gray_signed", new Increment57GraySource(SInt(4)))
    save("gray_bits", new Increment57GraySource(Bits(4)))
    save("gray_bool", new Increment57GraySource(Bool))
    save("gray_enum", new Increment57GraySource(Increment57GrayPayloads.noZeroEnum))
    save("gray_vector", new Increment57GraySource(Vec(UInt(2), 2)))
    save("gray_struct", new Increment57GraySource(Increment57GrayPayloads.record))
