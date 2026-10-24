package nodal_external
import nodal.*

final class Increment58OccurrenceLeaf extends Module:
  val WIDTH = param(8.integer, range = 4 to 16)
  val required = ClockDomain.required("required")
  val input = in(UInt(WIDTH))
  val output = out(UInt(WIDTH))
  output := input

final class Increment58GeneratedOccurrenceSource(mode: String = "valid") extends Module:
  val WIDTH = param(8.integer, range = 4 to 16)
  val OTHER = param(8.integer, range = 4 to 16)
  val actualWidth = mode match
    case "wrong" => 9.integer
    case "foreign" => OTHER
    case _ => WIDTH
  val LANES = param(3.integer, range = 1 to 1000000)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(WIDTH), LANES))
  val output = out(Vec(UInt(WIDTH), LANES))
  val first = out(UInt(WIDTH))
  core:
    val lanes =
      for i <- hdlRange(0, LANES) yield new Area:
        private val child = instance(new Increment58OccurrenceLeaf).domain(core)
        child(_.input) := input.at(i)
        val stored = wire(UInt(WIDTH))
        stored := child(_.output)
        output.writable.at(i) := stored
        child.param(_.WIDTH, actualWidth)
    first := lanes(0).stored

final class Increment58DirectOccurrenceSource(wrong: Boolean = false) extends Module:
  val WIDTH = param(8.integer, range = 4 to 16)
  val actualWidth = if wrong then 9.integer else WIDTH
  val LANES = param(3.integer, range = 1 to 1000000)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(WIDTH), LANES))
  val output = out(Vec(UInt(WIDTH), LANES))
  val first = out(UInt(WIDTH))
  core:
    val child = instance(new Increment58OccurrenceLeaf).domain(core)
    child(_.input) := input.at(0)
    first := child(_.output)
    output := input
    child.param(_.WIDTH, actualWidth)

final class Increment58OccurrenceStructLeaf extends Module:
  val WIDTH = param(8.integer, range = 4 to 16)
  val payload = Struct("Packet", StructField("data", UInt(WIDTH)))
  val required = ClockDomain.required("required")
  val input = in(payload)
  val output = out(payload)
  output := input

final class Increment58GeneratedOccurrenceStructSource(wrong: Boolean = false) extends Module:
  val WIDTH = param(8.integer, range = 4 to 16)
  val payload = Struct("Packet", StructField("data", UInt(WIDTH)))
  val actualWidth = if wrong then 9.integer else WIDTH
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
        private val child = instance(new Increment58OccurrenceStructLeaf).domain(core)
        child(_.input) := input.at(i)
        val stored = wire(payload)
        stored := child(_.output)
        output.writable.at(i) := stored
        child.param(_.WIDTH, actualWidth)
    first := lanes(0).stored

final class Increment58OccurrenceFixedLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  output := input

final class Increment58OccurrenceNestedSource extends Module:
  val LANES = param(3.integer, range = 1 to 1000000)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), 2, LANES))
  val output = out(Vec(UInt(8), 2, LANES))
  val first = out(UInt(8))
  core:
    val rows =
      for r <- hdlRange(0, 2) yield new Area:
        val lanes =
          for i <- hdlRange(0, LANES) yield new Area:
            private val child = instance(new Increment58OccurrenceFixedLeaf).domain(core)
            child(_.input) := input.row(r).at(i)
            val stored = wire(UInt(8))
            stored := child(_.output)
            output.writable.row(r).at(i) := stored
    first := rows(1).lanes(0).stored

final class Increment58OccurrenceEmptySource extends Module:
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
      for i <- hdlRange(0, 0) yield new Area:
        private val child = instance(new Increment58OccurrenceFixedLeaf).domain(core)
        child(_.input) := input.at(i)
        val stored = wire(UInt(8))
        stored := child(_.output)
    val _ = lanes
    first := 0.U(8)
    output := input

final class Increment58OccurrenceElaborationLoopSource extends Module:
  val LANES = param(3.integer, range = 3 to 3)
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
    for i <- 0 until 3 do
      val child = instance(new Increment58OccurrenceFixedLeaf).domain(core)
      child(_.input) := input.at(i)
      output.writable.at(i) := child(_.output)
    first := input.at(0)

final class Increment58OccurrenceStateLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  val state = Reg(0.U(8))
  state := input
  output := state

final class Increment58OccurrenceStateSource extends Module:
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
        private val child = instance(new Increment58OccurrenceStateLeaf).domain(core)
        child(_.input) := input.at(i)
        val stored = wire(UInt(8))
        stored := child(_.output)
        output.writable.at(i) := stored
    first := lanes(0).stored

final class Increment58OccurrenceForeignCaptureLeaf(parentValue: Expr[UInt]) extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  output := parentValue

final class Increment58OccurrenceForeignCaptureSource extends Module:
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
        private val child =
          instance(new Increment58OccurrenceForeignCaptureLeaf(input.at(i))).domain(core)
        child(_.input) := input.at(i)
        val stored = wire(UInt(8))
        stored := child(_.output)
        output.writable.at(i) := stored
    first := lanes(0).stored

final class Increment58OccurrenceForeignSelectorLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  output := input

final class Increment58OccurrenceForeignSelectorSource extends Module:
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
        private val child =
          instance(new Increment58OccurrenceForeignSelectorLeaf).domain(_ => core, core)
        child(_.input) := input.at(i)
        val stored = wire(UInt(8))
        stored := child(_.output)
        output.writable.at(i) := stored
    first := lanes(0).stored

final class Increment58OccurrenceOutputDriveLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  output := input

final class Increment58OccurrenceOutputDriveSource extends Module:
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
        private val child = instance(new Increment58OccurrenceOutputDriveLeaf).domain(core)
        child(_.output) := input.at(i)
        val stored = wire(UInt(8))
        stored := child(_.output)
        output.writable.at(i) := stored
    first := lanes(0).stored
