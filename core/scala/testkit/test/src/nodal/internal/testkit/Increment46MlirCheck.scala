package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import nodal.*
import nodal.increment46fixture.*
import nodal.internal.bridge.ScalaToMlirBridge

/** Exports public source unchanged for native lint, mandatory-negative and target replay checks. */
object Increment46MlirCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.length == 1, "expected output directory")
    val directory = Files.createDirectories(Paths.get(arguments(0)))
    val sources = Vector[(String, () => Module)](
      "continuous" -> (() => new AnalogLintSource),
      "continuous-safe" -> (() => new AnalogLintSafeSource),
      "events" -> (() => new AnalogLintEventSource),
      "events-safe" -> (() => new AnalogLintSafeEventSource),
      "zero-divisor-negative" -> (() => new AnalogLintZeroDivisorSource)
    )
    sources.foreach: (name, source) =>
      val snapshot = ConstructionKernel.inspect(source())
      val replay = ConstructionKernel.inspect(source())
      Vector("va" -> Backend.VerilogA, "ams" -> Backend.VerilogAMS).foreach: (profile, backend) =>
        val document = ScalaToMlirBridge.fromSnapshot(snapshot, backend)
        require(
          document == ScalaToMlirBridge.fromSnapshot(replay, backend),
          s"unstable $name $profile lint witness"
        )
        Files.writeString(
          directory.resolve(s"$name.$profile.mlir"),
          document.text,
          StandardCharsets.UTF_8
        )
        println(s"Increment 46 $name $profile public source witness: ${document.sha256}")
