package nodal.internal.testkit

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import nodal.*
import nodal.internal.bridge.*
import nodal_external.Increment58SymbolicHierarchySource

object Increment58SymbolicHierarchyReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected one output directory under .work")
    val directory = Paths.get(args(0)).toAbsolutePath.normalize
    require(directory.iterator().asScala.exists(_.toString == ".work"))
    val _ = Files.createDirectories(directory)
    for vector <- Vector(false, true); late <- Vector(false, true) do
      val profile = s"${if vector then "vector" else "scalar"}-${if late then "late" else "early"}"
      val document = ScalaToMlirBridge.fromSnapshot(
        ConstructionKernel.inspect(new Increment58SymbolicHierarchySource(vector, late))
      )
      val _ = Files.writeString(directory.resolve(profile + ".mlir"), document.text)
      println(s"F58_SYMBOLIC_HIERARCHY_SOURCE $profile ${document.sha256}")
