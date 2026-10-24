package nodal.internal.testkit

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import nodal.*
import nodal.internal.bridge.*
import nodal_external.{Increment57HierarchySource, Increment58HierarchyTargetSource}

/** Five actual public source replays, not handcrafted replacement native input. */
object Increment58HierarchyTargetReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected one output directory under .work")
    val directory = Paths.get(args(0)).toAbsolutePath.normalize
    require(directory.iterator().asScala.exists(_.toString == ".work"))
    val _ = Files.createDirectories(directory)
    Vector("shared", "nested", "material", "resetless").foreach: profile =>
      val snapshot = ConstructionKernel.inspect(new Increment58HierarchyTargetSource(profile))
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      val _ = Files.writeString(directory.resolve(profile + ".mlir"), document.text)
      println(s"F58_HIERARCHY_SOURCE $profile ${document.sha256}")
    val inherited = ScalaToMlirBridge.fromSnapshot(
      ConstructionKernel.inspect(new Increment57HierarchySource(nested = true))
    )
    val _ = Files.writeString(directory.resolve("inherited-release.mlir"), inherited.text)
    println(s"F58_HIERARCHY_SOURCE inherited-release ${inherited.sha256}")
