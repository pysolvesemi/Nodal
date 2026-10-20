package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import java.nio.file.{Files, Paths}

object Increment57RelationshipReplay:
  def main(args: Array[String]): Unit =
    val directory = Paths.get(args(0))
    val _ = Files.createDirectories(directory)
    def save(name: String, module: => Module): Unit =
      val result = ScalaToMlirBridge.fromSnapshot(ConstructionKernel.inspect(module))
      val _ = Files.writeString(directory.resolve(name + ".mlir"), result.text)
    for
      edge <- Vector(ClockEdge.Rising, ClockEdge.Falling)
      polarity <- Vector(ResetPolarity.ActiveHigh, ResetPolarity.ActiveLow)
    do
      save(
        s"external_${edge}_${polarity}",
        new Increment57RelationshipSource(edge = edge, polarity = polarity)
      )
    save("structural", new Increment57RelationshipSource(structural = true))
    save("nested", new Increment57RelationshipSource(nested = true))
    save("shaped", new Increment57ShapedRelationshipSource)
    val kinds = Vector(
      "ratio" -> ClockRelation.Ratio(6, 4, -270.deg),
      "synchronous" -> ClockRelation.Synchronous(true),
      "exclusive" -> ClockRelation.MutuallyExclusive,
      "asynchronous" -> ClockRelation.Asynchronous,
      "unknown" -> ClockRelation.Unknown
    )
    kinds.foreach((name, relation) =>
      save(name, new Increment57RelationshipCategorySource(relation))
    )
