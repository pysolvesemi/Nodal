package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import nodal.*
import nodal.increment44fixture.*
import nodal.internal.bridge.ScalaToMlirBridge

object Increment44MlirCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.length == 1, "expected output directory")
    val directory = Files.createDirectories(Paths.get(arguments(0)))
    val sources = Vector[(String, () => Module)](
      "continuous" -> (() => new AnalogContextSource),
      "events" -> (() => new AnalogContextEventSource),
      "generated" -> (() => new AnalogContextGeneratedSource),
      "query-only-generated" -> (() => new AnalogContextQueryOnlyGeneratedSource),
      "shared" -> (() => new AnalogContextSharedSource),
      "generated-continuous" -> (() => new AnalogContextGeneratedContinuousSource),
      "generated-events" -> (() => new AnalogContextGeneratedEventSource)
    )
    sources.foreach: (name, source) =>
      val snapshot = ConstructionKernel.inspect(source())
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      require(document == ScalaToMlirBridge.lower(source()), s"unstable $name query source mapping")
      Files.writeString(directory.resolve(s"$name.mlir"), document.text, StandardCharsets.UTF_8)
      val ams = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogAMS)
      Files.writeString(directory.resolve(s"$name.ams.mlir"), ams.text, StandardCharsets.UTF_8)
      println(s"Increment 44 $name source witness: ${document.sha256}; AMS: ${ams.sha256}")
