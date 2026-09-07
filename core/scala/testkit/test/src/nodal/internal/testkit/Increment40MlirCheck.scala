package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import nodal.increment40fixture.TransferFilters
import nodal.internal.bridge.ScalaToMlirBridge

object Increment40MlirCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.length == 1, "expected output MLIR path")
    val source = ScalaToMlirBridge.lower(new TransferFilters)
    require(source == ScalaToMlirBridge.lower(new TransferFilters), "unstable transfer source map")
    require(
      source.text.split("\\Q\"nodal.analog_transfer\"\\E").length - 1 == 6,
      "missing or duplicated transfer state"
    )
    Files.writeString(Paths.get(arguments(0)), source.text, StandardCharsets.UTF_8)
    println(s"Increment 40 public source witness: ${source.sha256}")
