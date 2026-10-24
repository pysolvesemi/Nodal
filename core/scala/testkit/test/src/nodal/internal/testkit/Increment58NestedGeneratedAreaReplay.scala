package nodal.internal.testkit

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*

/** Actual independently bounded nested body and local-update witnesses. */
object Increment58NestedGeneratedAreaReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected one output directory under .work")
    val directory = Paths.get(args(0)).toAbsolutePath.normalize
    require(directory.iterator().asScala.exists(_.toString == ".work"))
    val _ = Files.createDirectories(directory)
    Vector(
      "concrete" -> (() => new Increment58NestedGeneratedConcrete),
      "symbolic" -> (() => new Increment58NestedGeneratedSymbolic),
      "symbolic-ordinal" -> (() => new Increment58NestedGeneratedSymbolicOrdinal),
      "updated" -> (() => new Increment58NestedGeneratedUpdated)
    ).foreach: (name, source) =>
      val document = ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(source()))
      val _ = Files.writeString(directory.resolve(name + ".mlir"), document.text)
      println(s"F58_NESTED_GENERATED_AREA_SOURCE $name ${document.sha256}")
