package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import nodal.increment39fixture.AnalogNoiseSource
import nodal.internal.bridge.ScalaToMlirBridge

object Increment39MlirCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.length == 1, "expected output MLIR path")
    val source = ScalaToMlirBridge.lower(new AnalogNoiseSource)
    require(
      source == ScalaToMlirBridge.lower(new AnalogNoiseSource),
      "unstable noise source mapping"
    )
    require(
      source.text.split("\\Q\"nodal.analog_noise\"\\E").length - 1 == 5,
      "missing or duplicated noise sources"
    )
    Files.writeString(Paths.get(arguments(0)), source.text, StandardCharsets.UTF_8)
    println(s"Increment 39 public source witness: ${source.sha256}")
