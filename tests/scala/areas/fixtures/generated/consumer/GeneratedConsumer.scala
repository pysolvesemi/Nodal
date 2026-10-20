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
