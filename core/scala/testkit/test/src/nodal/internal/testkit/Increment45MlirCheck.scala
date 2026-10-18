package nodal.internal.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import nodal.*
import nodal.increment45fixture.*
import nodal.internal.bridge.ScalaToMlirBridge

/** Exports actual public construction for native pass and target replay. */
object Increment45MlirCheck:
  def main(arguments: Array[String]): Unit =
    require(arguments.length == 1, "expected output directory")
    val directory = Files.createDirectories(Paths.get(arguments(0)))
    val snapshot = ConstructionKernel.inspect(new AnalogCanonicalizationSource)
    Vector("va" -> Backend.VerilogA, "ams" -> Backend.VerilogAMS).foreach: (name, backend) =>
      val document = ScalaToMlirBridge.fromSnapshot(snapshot, backend)
      require(
        document == ScalaToMlirBridge.fromSnapshot(
          ConstructionKernel.inspect(new AnalogCanonicalizationSource),
          backend
        ),
        s"unstable $name canonicalization witness"
      )
      Files.writeString(
        directory.resolve(s"canonicalization.$name.mlir"),
        document.text,
        StandardCharsets.UTF_8
      )
      println(s"Increment 45 $name public source witness: ${document.sha256}")
    val inventory = ScalaToMlirBridge.lower(new AnalogCanonicalizationEquationInventory)
    require(
      inventory == ScalaToMlirBridge.lower(new AnalogCanonicalizationEquationInventory),
      "unstable authored equation inventory"
    )
    Files.writeString(
      directory.resolve("equation-inventory.mlir"),
      inventory.text,
      StandardCharsets.UTF_8
    )
    println(s"Increment 45 authored-only equation inventory: ${inventory.sha256}")
