package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import nodal.increment38fixture.{AnalogMathEventSource, AnalogMathSource}
import nodal.internal.bridge.ScalaToMlirBridge

object Increment38MlirCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.length == 1, "expected output MLIR path")
    val math = ScalaToMlirBridge.lower(new AnalogMathSource)
    require(math == ScalaToMlirBridge.lower(new AnalogMathSource), "unstable math source mapping")
    require(math.text.contains("nodal.analog_function"), "missing mathematical IR")
    require(math.text.contains("nodal.analog_analysis"), "missing query IR")
    Files.writeString(Paths.get(arguments(0)), math.text, StandardCharsets.UTF_8)
    val event = ScalaToMlirBridge.lower(new AnalogMathEventSource)
    require(event == ScalaToMlirBridge.lower(new AnalogMathEventSource), "unstable event query")
    Files.writeString(Paths.get(arguments(0) + ".events.mlir"), event.text, StandardCharsets.UTF_8)
    println(s"Increment 38 source witness: ${math.sha256}; event witness: ${event.sha256}")
