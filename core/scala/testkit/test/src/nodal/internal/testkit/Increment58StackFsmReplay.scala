package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
object Increment58StackFsmReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1)
    val directory = Paths.get(args(0)).toAbsolutePath.normalize
    require(directory.iterator().asScala.exists(_.toString == ".work"))
    val _ = Files.createDirectories(directory)
    val profiles = Vector("trap", "hold", "resetless", "one", "maximum", "one-hot")
      .map(profile => profile -> (() => new Increment58StackFsmSource(profile))) :+
      ("required" -> (() => new Increment58RequiredStackFsmSource)) :+
      ("composed" -> (() => new Increment58ComposedStackFsmSource))
    profiles.foreach: (name, create) =>
      val snapshot = ConstructionKernel.inspect(create())
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      val _ = Files.writeString(directory.resolve(name + ".mlir"), document.text)
      val _ = Files.writeString(
        directory.resolve(name + ".construction.json"),
        ReproducibilityContract.canonicalSnapshot(snapshot)
      )
      println(s"F58_STACK_FSM_SOURCE $name ${document.sha256}")
