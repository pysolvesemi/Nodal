package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Path}
object GeneratedConservativeReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1)
    val directory = Path.of(args(0))
    val _ = Files.createDirectories(directory)
    Vector("local" -> (() => new GeneratedConservativeLocal), "child" -> (() => new GeneratedConservativeHierarchy)).foreach: (name, build) =>
      val snapshot = ConstructionKernel.inspect(build())
      val rendered = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA)
      val _ = Files.writeString(directory.resolve(name + ".json"), ReproducibilityContract.canonicalSnapshot(snapshot))
      val _ = Files.writeString(directory.resolve(name + ".mlir"), rendered.text)
      println("PASS " + name)
