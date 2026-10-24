package nodal_external
import nodal.*
final class Increment58PendingReductionSource(mode: String = "left", wrong: Boolean = false)
    extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(Bool, 4))
  val output =
    out(SInt(if wrong then 9 else if mode == "left" then 5 else if mode == "tree" then 4 else 2))
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      private val width = i + 1.integer
      val stored = wire(Vec(SInt(width), 4))
      stored := input.map(bit => select(bit, (-1).S(width), 0.S(width)))
      val scalar = wire(SInt(width))
      scalar := select(input.at(i), (-1).S(width), 0.S(width))
  val chosen = lanes(1).stored
  val result = mode match
    case "tree" => chosen.reduceBalancedTree((a, b) => a + b)
    case "invariant" => chosen.reduce((a, _) => a)
    case _ => chosen.reduce((a, b) => a + b)
  output := result
final class Increment58PendingReductionCaptureSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(SInt(2), 4))
  val output = out(SInt(3))
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      val stored = wire(SInt(i + 1.integer))
      stored := 0.S(i + 1.integer)
  val captured = lanes(2).stored
  output := input.reduce((_, _) => captured)

final class Increment58PendingReductionNestedSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(Bool, 4))
  val output = out(Vec(SInt(4), 2))
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      private val width = i + 1.integer
      val stored = wire(Vec(Vec(SInt(width), 2), 4))
      stored := input.map(bit =>
        input.slice(0, 2).map(other => select(bit && other, (-1).S(width), 0.S(width)))
      )
  output := lanes(1).stored.reduceBalancedTree((a, b) => a.zipWith(b)((x, y) => x + y))
final class Increment58PendingReductionGeometrySource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(SInt(2), 3))
  val output = out(SInt(3))
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      val stored = wire(Vec(SInt(2), i + 1.integer))
      stored := input.slice(0, i + 1.integer)
  output := lanes(1).stored.reduce((a, b) => a + b)

final class Increment58PendingReductionPacketSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(Bool, 4))
  private val outData = StructField("data", SInt(2))
  private val outTag = StructField("tag", Bool)
  private val outTail = StructField("tail", Vec(UInt(3), 2))
  val output = out(Struct("ReductionPacket", outData, outTag, outTail))
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      private val data = StructField("data", SInt(i + 1.integer))
      private val tag = StructField("tag", Bool)
      private val tail = StructField("tail", Vec(UInt(i + 2.integer), 2))
      private val schema = Struct("ReductionPacket", data, tag, tail)
      private val dataKey = schema.member(data)
      private val tagKey = schema.member(tag)
      private val tailKey = schema.member(tail)
      val stored = wire(Vec(schema, 4))
      stored := input.map(bit =>
        schema.value(
          dataKey.bind(select(bit, (-1).S(i + 1.integer), 0.S(i + 1.integer))),
          tagKey.bind(bit),
          tailKey.bind(input.slice(0, 2).map(_ => 0.U(i + 2.integer)))
        )
      )
  output := lanes(1).stored.reduceBalancedTree((a, _) => a)

final class Increment58PendingReductionIndexSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(Bool, 1))
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      val stored = wire(Vec(SInt(i + 1.integer), 1))
      stored := input.map(_ => 0.S(i + 1.integer))
  val bad = lanes(1).stored.at(1)

// Independent schema capture control: no reduction participates in this source.
final class Increment58RegionalSchemaCaptureSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(Bool, 2))
  val first =
    for i <- hdlRange(0, 3) yield new Area:
      private val field = StructField("data", SInt(i + 1.integer))
      private val schema = Struct("IndependentSchema", field)
      private val key = schema.member(field)
      val stored = wire(Vec(schema, 2))
      stored := input.map(bit =>
        schema.value(key.bind(select(bit, (-1).S(i + 1.integer), 0.S(i + 1.integer))))
      )
  val second =
    for i <- hdlRange(0, 3) yield new Area:
      private val field = StructField("data", SInt(i + 1.integer))
      private val schema = Struct("IndependentSchema", field)
      private val key = schema.member(field)
      val stored = wire(Vec(schema, 2))
      stored := input.map(bit =>
        schema.value(key.bind(select(bit, (-1).S(i + 1.integer), 0.S(i + 1.integer))))
      )
