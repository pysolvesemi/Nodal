package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Paths}

object Increment57FifoReplay:
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
      save(s"fifo_${edge}_${polarity}", new Increment57FifoSource(UInt(8), 4, edge, polarity))
    Vector(2, 8, 256).foreach(depth =>
      save(s"fifo_depth_$depth", new Increment57FifoSource(UInt(8), depth))
    )
    save("fifo_vector", new Increment57FifoSource(Vec(UInt(4), 2)))
    save("fifo_struct", new Increment57FifoSource(Increment57FifoPayloads.record))
    save("fifo_enum", new Increment57FifoSource(Increment57GrayPayloads.noZeroEnum))
    save("fifo_unattached", new Increment57FifoSource(UInt(8), mode = "unattached"))
    save("fifo_chain", new Increment57FifoSource(UInt(8), mode = "chain"))
    save("fifo_continuous", new Increment57FifoSource(UInt(8), mode = "continuous"))
    save("fifo_sync", new Increment57FifoSource(UInt(8), mode = "sync"))
