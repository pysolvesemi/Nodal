package nodal.internal.testkit

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*

/** Actual selected nested-family source witnesses, never handwritten transport. */
object Increment58SelectedGeneratedAreaReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected one output directory under .work")
    val directory = Paths.get(args(0)).toAbsolutePath.normalize
    require(directory.iterator().asScala.exists(_.toString == ".work"))
    val _ = Files.createDirectories(directory)
    Vector[(String, () => Module)](
      "selected" -> (() => new Increment58SelectedNestedAreaSource),
      "symbolic" -> (() => new Increment58SelectedNestedSymbolicSource),
      "named" -> (() => new Increment58SelectedNamedFamilySource),
      "triple" -> (() => new Increment58SelectedTripleFamilySource),
      "grouped" -> (() => new Increment58SelectedGroupedFamilySource)
    ).foreach: (name, source) =>
      val document = ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(source()))
      val _ = Files.writeString(directory.resolve(name + ".mlir"), document.text)
      println(s"F58_SELECTED_GENERATED_AREA_SOURCE $name ${document.sha256}")
