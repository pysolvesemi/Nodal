package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Paths}

object Increment57HandshakeReplay:
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
      save(
        s"handshake_${edge}_${polarity}",
        new Increment57HandshakeSource(UInt(4), edge, polarity)
      )
    save("handshake_vector", new Increment57HandshakeSource(Vec(UInt(2), 2)))
    save("handshake_struct", new Increment57HandshakeSource(Increment57GrayPayloads.record))
    save("handshake_enum", new Increment57HandshakeSource(Increment57GrayPayloads.noZeroEnum))
    save("handshake_sync", new Increment57HandshakeSource(UInt(4), mode = "sync"))
