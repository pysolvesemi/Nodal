package nodal.internal.testkit

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*

/** Actual source witnesses for generated storage, static projections and structural composition. */
object Increment58GeneratedAreaReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected one output directory under .work")
    val directory = Paths.get(args(0)).toAbsolutePath.normalize
    require(directory.iterator().asScala.exists(_.toString == ".work"))
    val _ = Files.createDirectories(directory)
    Vector(
      "lane" -> (() => new Increment58GeneratedAreaSource),
      "ordinary" -> (() => new Increment58OrdinaryAreaStructuralWhen),
      "selected" -> (() => new Increment58GeneratedSelectedWhen),
      "guarded" -> (() => new Increment58GeneratedGuardedSelection),
      "lane-registers" -> (() => new Increment58GeneratedLaneRegisters),
      "concrete-lane-registers" -> (() => new Increment58GeneratedConcreteLaneRegisters),
      "nested-lane-registers" -> (() => new Increment58NestedLaneRegisters)
    ).foreach: (name, source) =>
      val document = ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(source()))
      val _ = Files.writeString(directory.resolve(name + ".mlir"), document.text)
      println(s"F58_GENERATED_AREA_SOURCE $name ${document.sha256}")
