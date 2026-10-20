package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Paths}

object Increment57ResetReplay:
  def main(args: Array[String]): Unit =
    val directory = Paths.get(args(0))
    val _ = Files.createDirectories(directory)
    def save(name: String, module: => Module): Unit =
      val result = ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(module))
      val _ = Files.writeString(directory.resolve(name + ".mlir"), result.text)
    for
      edge <- Vector(ClockEdge.Rising, ClockEdge.Falling);
      polarity <- Vector(ResetPolarity.ActiveHigh, ResetPolarity.ActiveLow)
    do
      save(s"${edge}_${polarity}", new Increment57ResetSource(edge, polarity, "ordinary"))
    save(
      "cascade",
      new Increment57ResetSource(ClockEdge.Rising, ResetPolarity.ActiveHigh, "cascade")
    )
    save(
      "external",
      new Increment57ResetSource(ClockEdge.Rising, ResetPolarity.ActiveHigh, "external")
    )
    save("mixed", new Increment57MixedResetSource)
    for mode <- Vector("single", "combine", "matrix") do
      save("shaped_" + mode, new Increment57ShapedResetSource(mode))
    save("shaped_sync", new Increment57ShapedSyncSource(false))
