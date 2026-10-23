package nodal.internal.testkit

import java.nio.file.{Files, Paths}
import nodal.*
import nodal.internal.bridge.*
import nodal_external.Increment58LexicalDeferredSource

/** Public source exporter; no source selection or provisional type repair occurs here. */
object Increment58LexicalDeferredReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "expected output directory")
    val directory = Paths.get(args(0))
    val _ = Files.createDirectories(directory)
    for mode <- Vector("direct", "when", "structural", "family"); late <- Vector(false, true) do
      val snapshot = ConstructionKernel.inspect(new Increment58LexicalDeferredSource(mode, late))
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      val profile = s"$mode-${if late then "late" else "early"}"
      val _ = Files.writeString(directory.resolve(s"$profile.mlir"), document.text)
      println(s"$profile ${document.sha256}")
