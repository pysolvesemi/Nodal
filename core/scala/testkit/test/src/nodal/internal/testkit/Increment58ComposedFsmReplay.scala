package nodal.internal.testkit

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*

/** Exports the actual composed source plus canonical construction artifacts, never handwritten IR.
  */
object Increment58ComposedFsmReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected one output directory under .work")
    val directory = Paths.get(args(0)).toAbsolutePath.normalize
    require(directory.iterator().asScala.exists(_.toString == ".work"))
    val _ = Files.createDirectories(directory)
    Vector("all", "any", "priority", "nested").foreach: profile =>
      val snapshot = ConstructionKernel.inspect(new Increment58ComposedFsmSource(profile))
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      val _ = Files.writeString(directory.resolve(profile + ".mlir"), document.text)
      val _ = Files.writeString(
        directory.resolve(profile + ".construction.json"),
        ReproducibilityContract.canonicalSnapshot(snapshot)
      )
      println(s"F58_COMPOSED_FSM_SOURCE $profile ${document.sha256}")
