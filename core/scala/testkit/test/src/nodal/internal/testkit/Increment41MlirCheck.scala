package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import nodal.increment41fixture.FunctionAmplifier
import nodal.internal.bridge.ScalaToMlirBridge

object Increment41MlirCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.length == 1, "expected output MLIR path")
    val source = ScalaToMlirBridge.lower(new FunctionAmplifier)
    require(
      source == ScalaToMlirBridge.lower(new FunctionAmplifier),
      "unstable function source witness"
    )
    require(
      source.text.split("\\Q\"nodal.analog_user_function\"\\E").length - 1 == 6,
      "missing or duplicated native function declarations"
    )
    require(source.text.contains("nodal.analog_user_call"), "calls were silently expanded")
    Files.writeString(Paths.get(arguments(0)), source.text, StandardCharsets.UTF_8)
    println(s"Increment 41 public source witness: ${source.sha256}")
