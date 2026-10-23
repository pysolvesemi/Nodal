package nodal_external
import nodal.*

final class GeneratedOccurrenceLane extends Module:
  val WIDTH = param(8.integer, range = 4 to 16)
  val payload = Struct("Packet", { println("forbidden"); StructField("data", UInt(WIDTH)) })
  val required = ClockDomain.required("required")
  val input = in(payload)
  val output = out(payload)
  output := input

final class GeneratedOccurrenceSource extends Module:
  val WIDTH = param(8.integer, range = 4 to 16)
  val payload = Struct("Packet", StructField("data", UInt(WIDTH)))
  val LANES = param(3.integer, range = 1 to 1000000)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(payload, LANES))
  val output = out(Vec(payload, LANES))
  val first = out(payload)
  core:
    val lanes =
      for i <- hdlRange(0, LANES) yield new Area:
        private val child = instance(new GeneratedOccurrenceLane).domain(core)
        child(_.input) := input.at(i)
        val stored = wire(payload)
        stored := child(_.output)
        output.writable.at(i) := stored
        child.param(_.WIDTH, WIDTH)
    first := lanes(0).stored
