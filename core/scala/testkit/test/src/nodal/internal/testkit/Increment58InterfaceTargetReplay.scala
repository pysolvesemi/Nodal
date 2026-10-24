package nodal.internal.testkit

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import nodal.*
import nodal.internal.bridge.*
import nodal_external.HierarchyPayloadParent

/** Actual early/late child override witnesses for whole Interface and aggregate payload targets. */
object Increment58InterfaceTargetReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected one output directory under .work")
    val directory = Paths.get(args(0)).toAbsolutePath.normalize
    require(directory.iterator().asScala.exists(_.toString == ".work"))
    val _ = Files.createDirectories(directory)
    Vector(
      "interface-array-early" -> (() => new HierarchyArrayParent("early")),
      "interface-array-late" -> (() => new HierarchyArrayParent("late")),
      "interface-payload-early" -> (() => new HierarchyPayloadParent("early")),
      "interface-payload-late" -> (() => new HierarchyPayloadParent("late"))
    ).foreach: (name, source) =>
      val document = ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(source()))
      val _ = Files.writeString(directory.resolve(name + ".mlir"), document.text)
      println(s"F58_INTERFACE_TARGET_SOURCE $name ${document.sha256}")
