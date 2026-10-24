package nodal_external
import nodal.*

final class GeneratedOccurrenceLane extends Module:
  private def concealed(): Unit = println("not hardware")
  concealed()
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  output := input

final class GeneratedOccurrenceSource extends Module:
  val MODE = param(1.integer, range = 0 to 1)
  val ENABLE = param(1.integer, range = 0 to 1)
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
    if ENABLE == 1 then
      if MODE == 1 then
        val lanes =
          for i <- hdlRange(0, LANES) yield new Area:
            private val child = instance(new GeneratedOccurrenceLane).domain(core)
            child(_.input) := input.at(i)
            val stored = wire(UInt(8))
            stored := child(_.output)
            output.writable.at(i) := stored
        first := lanes(0).stored
      else
        output := input
        first := 0.U(8)
    else
      output := input
      first := 0.U(8)
