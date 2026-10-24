package nodal_external

import nodal.*

// Bounded source witnesses for real generated child target lowering.
// Every data lane carries independent input; the target may not clone lanes.
final class Increment58TargetOccurrenceLeaf extends Module:
  val WIDTH = param(8.integer, range = 4 to 16)
  val required = ClockDomain.required("required")
  val input = in(UInt(WIDTH))
  val output = out(UInt(WIDTH))
  output := input

final class Increment58GeneratedOccurrenceTargetScalar(mode: String = "valid") extends Module:
  val WIDTH = param(8.integer, range = 4 to 16)
  val OTHER = param(8.integer, range = 4 to 16)
  val actualWidth = mode match
    case "wrong" => 9.integer
    case "foreign" => OTHER
    case _ => WIDTH
  val LANES = param(3.integer, range = 1 to 8)
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
        private val child = instance(new Increment58TargetOccurrenceLeaf).domain(core)
        child(_.input) := input.at(i)
        val stored = wire(UInt(WIDTH))
        stored := child(_.output)
        output.writable.at(i) := stored
        child.param(_.WIDTH, actualWidth)
    first := lanes(0).stored

final class Increment58TargetOccurrenceStructLeaf extends Module:
  val WIDTH = param(8.integer, range = 4 to 16)
  val payload = Struct("Packet", StructField("data", UInt(WIDTH)))
  val required = ClockDomain.required("required")
  val input = in(payload)
  val output = out(payload)
  output := input

final class Increment58GeneratedOccurrenceTargetStruct(wrong: Boolean = false) extends Module:
  val WIDTH = param(8.integer, range = 4 to 16)
  val payload = Struct("Packet", StructField("data", UInt(WIDTH)))
  val actualWidth = if wrong then 9.integer else WIDTH
  val LANES = param(3.integer, range = 1 to 8)
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
        private val child = instance(new Increment58TargetOccurrenceStructLeaf).domain(core)
        child(_.input) := input.at(i)
        val stored = wire(payload)
        stored := child(_.output)
        output.writable.at(i) := stored
        child.param(_.WIDTH, actualWidth)
    first := lanes(0).stored

final class Increment58TargetOccurrenceFixedLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  output := input

final class Increment58GeneratedOccurrenceTargetNested extends Module:
  val LANES = param(3.integer, range = 1 to 8)
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
            private val child = instance(new Increment58TargetOccurrenceFixedLeaf).domain(core)
            child(_.input) := input.row(r).at(i)
            val stored = wire(UInt(8))
            stored := child(_.output)
            output.writable.row(r).at(i) := stored
    first := rows(1).lanes(0).stored

final class Increment58GeneratedOccurrenceTargetEmpty extends Module:
  val LANES = param(3.integer, range = 1 to 8)
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
        private val child = instance(new Increment58TargetOccurrenceFixedLeaf).domain(core)
        child(_.input) := input.at(i)
        val stored = wire(UInt(8))
        stored := child(_.output)
    val _ = lanes
    first := 0.U(8)
    output := input

final class Increment58TargetOccurrenceStateLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  val state = Reg(0.U(8))
  state := input
  output := state

final class Increment58GeneratedOccurrenceTargetState extends Module:
  val LANES = param(3.integer, range = 1 to 8)
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
        private val child = instance(new Increment58TargetOccurrenceStateLeaf).domain(core)
        child(_.input) := input.at(i)
        val stored = wire(UInt(8))
        stored := child(_.output)
        output.writable.at(i) := stored
    first := lanes(0).stored

final class Increment58TargetOccurrenceGuardedLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  output := input

final class Increment58GeneratedOccurrenceTargetGuarded extends Module:
  val ENABLE = param(1.integer, range = 0 to 1)
  val LANES = param(3.integer, range = 1 to 8)
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
      val lanes =
        for i <- hdlRange(0, LANES) yield new Area:
          private val child = instance(new Increment58TargetOccurrenceGuardedLeaf).domain(core)
          child(_.input) := input.at(i)
          val stored = wire(UInt(8))
          stored := child(_.output)
          output.writable.at(i) := stored
      first := lanes(0).stored
    else
      output := input
      first := 0.U(8)

final class Increment58TargetOccurrenceMultifieldLeaf extends Module:
  val WIDTH = param(8.integer, range = 4 to 16)
  val payload = Struct(
    "Packet",
    StructField("data", UInt(WIDTH)),
    StructField("tag", Bits(3)),
    StructField(
      "meta",
      Struct(
        "Meta",
        StructField("flags", Bits(2)),
        StructField("lanes", Vec(UInt(4), 2))
      )
    )
  )
  val required = ClockDomain.required("required")
  val input = in(payload)
  val output = out(payload)
  output := input

final class Increment58GeneratedOccurrenceTargetMultifield(wrong: Boolean = false) extends Module:
  val WIDTH = param(8.integer, range = 4 to 16)
  val payload = Struct(
    "Packet",
    StructField("data", UInt(WIDTH)),
    StructField("tag", Bits(3)),
    StructField(
      "meta",
      Struct(
        "Meta",
        StructField("flags", Bits(2)),
        StructField("lanes", Vec(UInt(4), 2))
      )
    )
  )
  val actualWidth = if wrong then 9.integer else WIDTH
  val LANES = param(3.integer, range = 1 to 8)
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
        private val child = instance(new Increment58TargetOccurrenceMultifieldLeaf).domain(core)
        child(_.input) := input.at(i)
        val stored = wire(payload)
        stored := child(_.output)
        output.writable.at(i) := stored
        child.param(_.WIDTH, actualWidth)
    first := lanes(0).stored

final class Increment58GeneratedOccurrenceTargetNestedMultifield extends Module:
  val WIDTH = param(8.integer, range = 4 to 16)
  val payload = Struct(
    "Packet",
    StructField("data", UInt(WIDTH)),
    StructField("tag", Bits(3)),
    StructField(
      "meta",
      Struct(
        "Meta",
        StructField("flags", Bits(2)),
        StructField("lanes", Vec(UInt(4), 2))
      )
    )
  )
  val actualWidth = WIDTH
  val LANES = param(3.integer, range = 1 to 8)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(payload, 2, LANES))
  val output = out(Vec(payload, 2, LANES))
  val first = out(payload)
  core:
    val rows =
      for r <- hdlRange(0, 2) yield new Area:
        val lanes =
          for i <- hdlRange(0, LANES) yield new Area:
            private val child = instance(new Increment58TargetOccurrenceMultifieldLeaf).domain(core)
            child(_.input) := input.row(r).at(i)
            val stored = wire(payload)
            stored := child(_.output)
            output.writable.row(r).at(i) := stored
            child.param(_.WIDTH, actualWidth)
    first := rows(1).lanes(0).stored

final class Increment58GeneratedOccurrenceTargetNonunit extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), 8))
  val first = out(UInt(8))
  core:
    val lanes =
      for i <- hdlRange(2, 8, 2) yield new Area:
        private val child = instance(new Increment58TargetOccurrenceFixedLeaf).domain(core)
        child(_.input) := input.at(i)
        val stored = wire(UInt(8))
        stored := child(_.output)
    first := lanes(1).stored

// Two independent authored families exercise one batched canonical writer query.
final class Increment58GeneratedOccurrenceTargetRepeated extends Module:
  val LANES = param(3.integer, range = 1 to 8)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input_a = in(Vec(UInt(4), LANES))
  val output_a = out(Vec(UInt(4), LANES))
  val first_a = out(UInt(4))
  val input_b = in(Vec(UInt(8), LANES))
  val output_b = out(Vec(UInt(8), LANES))
  val first_b = out(UInt(8))
  core:
    val left =
      for i <- hdlRange(0, LANES) yield new Area:
        private val child = instance(new Increment58TargetOccurrenceLeaf).domain(core)
        child(_.input) := input_a.at(i)
        val stored = wire(UInt(4))
        stored := child(_.output)
        output_a.writable.at(i) := stored
        child.param(_.WIDTH, 4.integer)
    val right =
      for i <- hdlRange(0, LANES) yield new Area:
        private val child = instance(new Increment58TargetOccurrenceLeaf).domain(core)
        child(_.input) := input_b.at(i)
        val stored = wire(UInt(8))
        stored := child(_.output)
        output_b.writable.at(i) := stored
        child.param(_.WIDTH, 8.integer)
    first_a := left(0).stored
    first_b := right(0).stored
