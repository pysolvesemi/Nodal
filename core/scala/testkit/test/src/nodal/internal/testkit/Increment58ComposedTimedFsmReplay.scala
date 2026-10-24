package nodal.internal.testkit

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*

object Increment58ComposedTimedFsmReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected one output directory under .work")
    val directory = Paths.get(args(0)).toAbsolutePath.normalize
    require(directory.iterator().asScala.exists(_.toString == ".work"))
    val _ = Files.createDirectories(directory)
    val snapshot = ConstructionKernel.inspect(new Increment58ComposedTimedFsmSource)
    val document = ScalaToMlirBridge.fromSnapshot(snapshot)
    val _ = Files.writeString(directory.resolve("composed-timer.mlir"), document.text)
    val _ = Files.writeString(
      directory.resolve("composed-timer.construction.json"),
      ReproducibilityContract.canonicalSnapshot(snapshot)
    )
    println(s"F58_COMPOSED_TIMED_FSM_SOURCE ${document.sha256}")
