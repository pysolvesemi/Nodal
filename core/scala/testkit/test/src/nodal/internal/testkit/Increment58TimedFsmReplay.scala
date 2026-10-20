package nodal.internal.testkit

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*

object Increment58TimedFsmReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected output directory under .work")
    val directory = Paths.get(args(0)).toAbsolutePath.normalize
    require(directory.iterator().asScala.exists(_.toString == ".work"))
    val _ = Files.createDirectories(directory)
    Vector("timer", "one", "maximum", "live", "transfer", "resetless").foreach: profile =>
      val document = ScalaToMlirBridge.fromSnapshot(
        ConstructionKernel.inspect(new Increment58TimedFsmSource(profile))
      )
      val _ = Files.writeString(directory.resolve(profile + ".mlir"), document.text)
      println(s"F58_TIMED_FSM_SOURCE $profile ${document.sha256}")
    val formal = ScalaToMlirBridge.fromSnapshot(
      ConstructionKernel.inspect(new Increment58RequiredTimedFsmSource)
    )
    val _ = Files.writeString(directory.resolve("required-mixed.mlir"), formal.text)
    println(s"F58_TIMED_FSM_SOURCE required-mixed ${formal.sha256}")
