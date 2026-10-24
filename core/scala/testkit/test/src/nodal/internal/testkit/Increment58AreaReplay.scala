package nodal.internal.testkit

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*

/** Actual lexical Area source, including the native-owned ordinary driver rejection. */
object Increment58AreaReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected one output directory under .work")
    val directory = Paths.get(args(0)).toAbsolutePath.normalize
    require(directory.iterator().asScala.exists(_.toString == ".work"))
    val _ = Files.createDirectories(directory)
    Vector(
      "lexical" -> (() => new Increment58AreaSource),
      "external-write" -> (() => new Increment58AreaExternalWrite),
      "duplicate-driver" -> (() => new Increment58AreaDuplicateDriver)
    ).foreach: (name, source) =>
      val document = ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(source()))
      val _ = Files.writeString(directory.resolve(name + ".mlir"), document.text)
      println(s"F58_AREA_SOURCE $name ${document.sha256}")
