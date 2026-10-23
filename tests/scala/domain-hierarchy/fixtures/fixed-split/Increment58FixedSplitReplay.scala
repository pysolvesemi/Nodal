package nodal.internal.testkit

import java.nio.file.{Files, Path}
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*

object Increment58FixedSplitReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "exact output directory required")
    val root = Path.of(args(0))
    Vector("down", "up", "sibling", "reused", "variants").foreach: mode =>
      val snapshot = ConstructionKernel.inspect:
        mode match
          case "down" => new Increment58FixedSplitDown
          case "up" => new Increment58FixedSplitUp
          case "sibling" => new Increment58FixedSplitSibling
          case "reused" => new Increment58FixedSplitReused
          case _ => new Increment58FixedSplitVariants
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      require(
        !document.text.contains("!nodal.uint<p:"),
        "fixed witness must not retain symbolic payload"
      )
      val expectedSplits = if mode == "variants" then 4 else if mode == "reused" then 2 else 1
      require(snapshot.digitalExpressions.count(_.operation == "digital_net_write_view") ==
        expectedSplits)
      require(snapshot.digitalExpressions.count(_.operation == "digital_net_enable_view") ==
        expectedSplits)
      val directory = root.resolve(mode)
      Files.createDirectories(directory)
      val _ = Files.writeString(directory.resolve("input.mlir"), document.text)
      val _ = Files.writeString(
        directory.resolve("snapshot.json"),
        ReproducibilityContract.canonicalSnapshot(snapshot)
      )
      println(s"$mode: source PASS, $expectedSplits exact split write/enable pairs")
