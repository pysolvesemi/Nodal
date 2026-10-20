package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Paths}

object Increment57OwnedClockReplay:
  def main(args: Array[String]): Unit =
    val directory = Paths.get(args(0))
    val _ = Files.createDirectories(directory)
    def save(name: String, module: => Module): Unit =
      val result = ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(module))
      val _ = Files.writeString(directory.resolve(name + ".mlir"), result.text)
    for
      kind <- Vector("gate", "mux")
      edge <- Vector(ClockEdge.Rising, ClockEdge.Falling)
      polarity <- Vector(ResetPolarity.ActiveHigh, ResetPolarity.ActiveLow)
    do save(s"${kind}_${edge}_${polarity}", new Increment57OwnedClockSource(kind, edge, polarity))
    for edge <- Vector(ClockEdge.Rising, ClockEdge.Falling) do
      save(
        s"gate_resetless_$edge",
        new Increment57OwnedClockSource(
          "gate",
          edge,
          ResetPolarity.ActiveHigh,
          resetless = true
        )
      )
    save(
      "mux_nested",
      new Increment57OwnedClockSource(
        "mux",
        ClockEdge.Rising,
        ResetPolarity.ActiveHigh,
        nested = true
      )
    )
    save("mux_shaped", new Increment57ShapedOwnedClockSource)
