package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import nodal.{Backend, EmitOptions}
import nodal.increment42fixture.HierarchyEquationTop
import nodal.internal.bridge.ScalaToMlirBridge

object Increment42MlirCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.length == 1, "expected output MLIR path")
    val options = EmitOptions(backend = Backend.VerilogA)
    val source = ScalaToMlirBridge.lower(new HierarchyEquationTop, options)
    require(
      source == ScalaToMlirBridge.lower(new HierarchyEquationTop, options),
      "unstable hierarchy source witness"
    )
    require(
      source.text.split("\\Q\"nodal.instance\"\\E").length - 1 == 1,
      "missing or duplicated child instance"
    )
    require(
      source.text.split("\\Q\"nodal.instance_terminal\"\\E").length - 1 == 2,
      "missing or duplicated child terminals"
    )
    require(
      source.text.split("\\Q\"nodal.connect\"\\E").length - 1 == 2,
      "missing or duplicated hierarchy connections"
    )
    require(source.text.contains("\"nodal.parameter_override\""), "missing symbolic override")
    require(source.text.contains("nodal.bridge.analog_semantics"), "missing equation witness")
    Files.writeString(Paths.get(arguments(0)), source.text, StandardCharsets.UTF_8)
    println(s"Increment 42 public source witness: ${source.sha256}")
