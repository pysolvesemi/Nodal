package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import nodal.increment37fixture.AnalogEventSource
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
    println(s"Increment 37 source witness: ${first.sha256}")
