package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import nodal.increment36fixture.WaveformSource
import nodal.internal.bridge.ScalaToMlirBridge

/** Compiler-side harness for the separately compiled public API example. */
object Increment36MlirCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.length == 1, "expected the output MLIR path")
    val first = ScalaToMlirBridge.lower(new WaveformSource)
    val second = ScalaToMlirBridge.lower(new WaveformSource)
    require(first == second, "waveform lowering is nondeterministic")
    require(first.text.contains("nodal.analog_bound_step"))
    Files.writeString(Paths.get(arguments(0)), first.text, StandardCharsets.UTF_8)
    println(s"Increment 36 source witness passed: ${first.sha256}")
