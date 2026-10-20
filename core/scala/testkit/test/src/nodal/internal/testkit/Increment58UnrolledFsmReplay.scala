package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
object Increment58UnrolledFsmReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected output directory under .work")
    val directory = Paths.get(args(0)).toAbsolutePath.normalize
    require(directory.iterator().asScala.exists(_.toString == ".work"))
    val _ = Files.createDirectories(directory)
    Vector(
      "unrolled" -> (() => new Increment58UnrolledFsmSource),
      "unrolled-transfer" -> (() => new Increment58UnrolledTransferFsmSource)
    ).foreach: (name, create) =>
      val snapshot = ConstructionKernel.inspect(create())
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      val _ = Files.writeString(directory.resolve(name + ".mlir"), document.text)
      val _ = Files.writeString(
        directory.resolve(name + ".construction.json"),
        ReproducibilityContract.canonicalSnapshot(snapshot)
      )
      println(s"F58_UNROLLED_FSM_SOURCE $name ${document.sha256}")
