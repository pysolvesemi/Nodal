package nodal
import generated_producer.*
final class GeneratedReuseSource extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val output = out(UInt(8))
  val flag = out(Bool)
  clock:
    val lanes =
      for i <- hdlRange(0, 4) yield
        val _ = i
        LaneFactory(0.U(8))
    val selected = LaneFactory.keep(LaneFactory.first(lanes))
    val nested = selected.nested
    output := selected.stored
    flag := nested.flag
object GeneratedReuseProbe:
  def main(args: Array[String]): Unit =
    val _ = args
    val snapshot = ConstructionKernel.inspect(new GeneratedReuseSource)
    assert(snapshot.generatedRegions.size == 1)
    assert(snapshot.generatedRegions.head.exports.map(_.member).toSet ==
      Set("stored", "readonly", "nested.flag"))
    assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 3)
    println("GENERATED_AREA_SEPARATE_JAR_PASS")

final class GeneratedNestedReuseSource extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val output = out(UInt(8))
  clock:
    val outer =
      for i <- hdlRange(5, 9, 2) yield
        val _ = i
        NestedLaneFactory()
    val selected = NestedLaneFactory.selected(outer(1))
    output := selected(3).readonly

object GeneratedNestedReuseProbe:
  def main(args: Array[String]): Unit =
    val _ = args
    val snapshot = ConstructionKernel.inspect(new GeneratedNestedReuseSource)
    assert(snapshot.generatedRegions.size == 2)
    val outer = snapshot.generatedRegions.find(_.parent.isEmpty).get
    val inner = snapshot.generatedRegions.find(_.parent.nonEmpty).get
    assert(outer.exports ==
      Vector(KernelGeneratedAreaExport("inner", inner.path, "read_only", "family")))
    val member = snapshot.digitalExpressions.flatMap(_.generatedMember).head
    assert(member.selectionPrefix ==
      Vector(KernelGeneratedFamilySelection(outer.path, "1", "inner")))
    assert(member.family == inner.path && member.ordinal == "3" && member.access == "read_only")
    println("GENERATED_NESTED_AREA_SEPARATE_JAR_PASS")
