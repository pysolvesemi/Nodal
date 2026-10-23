package nodal_external
import nodal.*

final class GeneratedOccurrenceLane extends Module:
  private def makeRequired(): ClockDomain = ClockDomain.required("required")
  val required = makeRequired()
  val input = in(UInt(8))
  val output = out(UInt(8))
  output := input

final class GeneratedOccurrenceSource extends Module:
  val LANES = param(3.integer, range = 1 to 1000000)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), LANES))
  val output = out(Vec(UInt(8), LANES))
  val first = out(UInt(8))
  core:
    val lanes =
      for i <- hdlRange(0, LANES) yield new Area:
        private val child = instance(new GeneratedOccurrenceLane).domain(core)
        child(_.input) := input.at(i)
        val stored = wire(UInt(8))
        stored := child(_.output)
        output.writable.at(i) := stored
    first := lanes(0).stored
