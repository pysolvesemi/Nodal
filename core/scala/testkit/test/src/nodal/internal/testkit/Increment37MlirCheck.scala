package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import nodal.increment37fixture.{
  AnalogControlledEventsSource, AnalogEventSource, AnalogSampleHoldSource
}
import nodal.internal.bridge.ScalaToMlirBridge

object Increment37MlirCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.length == 1, "expected output MLIR path")
    val first = ScalaToMlirBridge.lower(new AnalogEventSource)
    require(
      first == ScalaToMlirBridge.lower(new AnalogEventSource),
      "nondeterministic event lowering"
    )
    Files.writeString(Paths.get(arguments(0)), first.text, StandardCharsets.UTF_8)
    val held = ScalaToMlirBridge.lower(new AnalogSampleHoldSource)
    require(
      held == ScalaToMlirBridge.lower(new AnalogSampleHoldSource),
      "nondeterministic held-state lowering"
    )
    require(
      held.text.contains("nodal.analog_held_read"),
      "sampled state is not a continuous held read"
    )
    require(!held.text.contains("@event_reference_"), "unresolved procedural expression binding")
    Files.writeString(Paths.get(arguments(0) + ".held.mlir"), held.text, StandardCharsets.UTF_8)
    val controlled = ScalaToMlirBridge.lower(new AnalogControlledEventsSource)
    require(
      controlled == ScalaToMlirBridge.lower(new AnalogControlledEventsSource),
      "nondeterministic control lowering"
    )
    Files.writeString(
      Paths.get(arguments(0) + ".controlled.mlir"),
      controlled.text,
      StandardCharsets.UTF_8
    )
    println(s"Increment 37 source witness: ${first.sha256}; sampled-state witness: ${held.sha256}")
