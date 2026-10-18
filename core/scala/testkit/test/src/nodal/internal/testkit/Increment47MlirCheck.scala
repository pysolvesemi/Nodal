package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import nodal.*
import nodal.increment47fixture.*
import nodal.internal.bridge.ScalaToMlirBridge

/** Exports unchanged source for native language-gate and final target replay. */
object Increment47MlirCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.length == 1, "expected output directory")
    val directory = Files.createDirectories(Paths.get(arguments(0)))
    val sources = Vector[(String, () => Module)](
      "analog-hierarchy" -> (() => new AnalogCapabilityHierarchy),
      "analog-storage" -> (() => new AnalogCapabilityStorage),
      "digital-descendant-negative" -> (() => new AnalogRootWithDigitalDescendant)
    )
    sources.foreach: (name, source) =>
      val snapshot = ConstructionKernel.inspect(source())
      val replay = ConstructionKernel.inspect(source())
      require(snapshot == replay, s"unstable $name source capture")
      Vector("va" -> Backend.VerilogA, "ams" -> Backend.VerilogAMS).foreach: (profile, backend) =>
        val document = ScalaToMlirBridge.fromSnapshot(snapshot, backend)
        require(
          document == ScalaToMlirBridge.fromSnapshot(replay, backend),
          s"unstable $name $profile capability witness"
        )
        Files.writeString(
          directory.resolve(s"$name.$profile.mlir"),
          document.text,
          StandardCharsets.UTF_8
        )
        println(s"Increment 47 $name $profile public source witness: ${document.sha256}")
