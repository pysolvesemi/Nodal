package nodal_external

import nodal.*

final class Increment58SelectedInstanceTargetLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  output := input
final class Increment58SelectedInstanceTargetWidthLeaf extends Module:
  val WIDTH = param(1.integer, range = 1 to 16)
  val required = ClockDomain.required("required")
  val input = in(UInt(WIDTH))
  val output = out(UInt(WIDTH))
  output := input
final class Increment58SelectedInstanceTargetVarying(
    lower: Int = 0,
    step: Int = 1
) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val output = out(UInt(lower + step + 1))
  val lanes =
    for i <- hdlRange(lower, 8, step) yield new Area:
      val child = instance(new Increment58SelectedInstanceTargetWidthLeaf).domain(core)
      child.param(_.WIDTH, i + 1.integer)
      child(_.input) := 0.U(i + 1.integer)
  val selected = lanes(1).child
  output := selected(_.output)
final class Increment58SelectedInstanceTargetStateLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  val state = Reg(0.U(8))
  state := input
  output := state
final class Increment58SelectedInstanceTargetTwo(stateful: Boolean = false)
    extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), 3))
  val left = out(UInt(8))
  val right = out(UInt(8))
  val again = out(UInt(8))
  if stateful then
    val lanes =
      for i <- hdlRange(0, 3) yield new Area:
        val child = instance(new Increment58SelectedInstanceTargetStateLeaf).domain(core)
        child(_.input) := input.at(i)
    val a = lanes(0).child
    val b = lanes(1).child
    left := a(_.output)
    right := b(_.output)
    val alias = lanes(0).child
    again := alias(_.output)
  else
    val lanes =
      for i <- hdlRange(0, 3) yield new Area:
        val child = instance(new Increment58SelectedInstanceTargetLeaf).domain(core)
        child(_.input) := input.at(i)
    val a = lanes(0).child
    val b = lanes(1).child
    left := a(_.output)
    right := b(_.output)
    val alias = lanes(0).child
    again := alias(_.output)

final class Increment58SelectedInstanceTargetFixedLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  output := input
final class Increment58SelectedInstanceTargetNonunit extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), 8))
  val left = out(UInt(8))
  val right = out(UInt(8))
  val again = out(UInt(8))
  val lanes =
    for i <- hdlRange(2, 8, 2) yield new Area:
      val child = instance(new Increment58SelectedInstanceTargetFixedLeaf).domain(core)
      child(_.input) := input.at(i)
  val a = lanes(0).child
  val b = lanes(1).child
  left := a(_.output)
  right := b(_.output)
  val alias = lanes(0).child
  again := alias(_.output)
final class Increment58SelectedInstanceTargetNested extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(Vec(UInt(8), 3), 2))
  val left = out(UInt(8))
  val right = out(UInt(8))
  val again = out(UInt(8))
  val rows =
    for r <- hdlRange(0, 2) yield new Area:
      val cells =
        for c <- hdlRange(0, 3) yield new Area:
          val child = instance(new Increment58SelectedInstanceTargetFixedLeaf).domain(core)
          child(_.input) := input.at(r).at(c)
  val a = rows(0).cells(1).child
  val b = rows(1).cells(2).child
  left := a(_.output)
  right := b(_.output)
  val alias = rows(0).cells(1).child
  again := alias(_.output)

final class Increment58SelectedInstanceTargetParameterizedLeaf extends Module:
  val WIDTH = param(8.integer, range = 1 to 8)
  val required = ClockDomain.required("required")
  val input = in(UInt(WIDTH))
  val output = out(UInt(WIDTH))
  output := input
final class Increment58SelectedInstanceTargetBindings extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val narrowInput = in(Vec(UInt(4), 3))
  val wideInput = in(Vec(UInt(8), 3))
  val narrow = out(UInt(4))
  val wide = out(UInt(8))
  val narrowAgain = out(UInt(4))
  val wideAgain = out(UInt(8))
  val narrowLanes =
    for i <- hdlRange(0, 3) yield new Area:
      val child = instance(new Increment58SelectedInstanceTargetParameterizedLeaf).domain(core)
      child.param(_.WIDTH, 4.integer)
      child(_.input) := narrowInput.at(i)
  val wideLanes =
    for i <- hdlRange(0, 3) yield new Area:
      val child = instance(new Increment58SelectedInstanceTargetParameterizedLeaf).domain(core)
      child.param(_.WIDTH, 8.integer)
      child(_.input) := wideInput.at(i)
  val n = narrowLanes(1).child
  val w = wideLanes(2).child
  narrow := n(_.output)
  wide := w(_.output)
  val nAlias = narrowLanes(1).child
  val wAlias = wideLanes(2).child
  narrowAgain := nAlias(_.output)
  wideAgain := wAlias(_.output)

object Increment58SelectedInstanceTargetPacket:
  val payload = Struct(
    "Packet",
    StructField("data", UInt(8)),
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
final class Increment58SelectedInstanceTargetPacketLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(Increment58SelectedInstanceTargetPacket.payload)
  val output = out(Increment58SelectedInstanceTargetPacket.payload)
  output := input
final class Increment58SelectedInstanceTargetStruct extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(Increment58SelectedInstanceTargetPacket.payload, 3))
  val left = out(Increment58SelectedInstanceTargetPacket.payload)
  val right = out(Increment58SelectedInstanceTargetPacket.payload)
  val again = out(Increment58SelectedInstanceTargetPacket.payload)
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      val child = instance(new Increment58SelectedInstanceTargetPacketLeaf).domain(core)
      child(_.input) := input.at(i)
  val a = lanes(0).child
  val b = lanes(1).child
  left := a(_.output)
  right := b(_.output)
  val alias = lanes(0).child
  again := alias(_.output)

final class Increment58SelectedInstanceTargetSymbolic extends Module:
  val WIDTH = param(8.integer, range = 4 to 16)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(WIDTH), 3))
  val left = out(UInt(WIDTH))
  val right = out(UInt(WIDTH))
  val again = out(UInt(WIDTH))
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      val child = instance(new Increment58SelectedInstanceTargetWidthLeaf).domain(core)
      child.param(_.WIDTH, WIDTH)
      child(_.input) := input.at(i)
  val a = lanes(0).child
  val b = lanes(1).child
  left := a(_.output)
  right := b(_.output)
  val alias = lanes(0).child
  again := alias(_.output)

final class Increment58SelectedInstanceTargetVecLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(Vec(UInt(4), 2))
  val output = out(Vec(UInt(4), 2))
  output := input

final class Increment58SelectedInstanceTargetVec extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(Vec(UInt(4), 2), 3))
  val left = out(Vec(UInt(4), 2))
  val right = out(Vec(UInt(4), 2))
  val again = out(Vec(UInt(4), 2))
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      val child = instance(new Increment58SelectedInstanceTargetVecLeaf).domain(core)
      child(_.input) := input.at(i)
  val a = lanes(0).child
  val b = lanes(1).child
  left := a(_.output)
  right := b(_.output)
  val alias = lanes(0).child
  again := alias(_.output)

final class Increment58SelectedInstanceTargetGuardedLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  output := input
final class Increment58SelectedInstanceTargetGuarded extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val mode = param(1.integer, range = 0 to 1)
  val input = in(Vec(UInt(8), 3))
  val left = out(UInt(8))
  val right = out(UInt(8))
  val again = out(UInt(8))
  if mode == 1 then
    val lanes =
      for i <- hdlRange(0, 3) yield new Area:
        val child = instance(new Increment58SelectedInstanceTargetGuardedLeaf).domain(core)
        child(_.input) := input.at(i)
    val a = lanes(0).child
    val b = lanes(1).child
    left := a(_.output)
    right := b(_.output)
    val alias = lanes(0).child
    again := alias(_.output)
  else
    left := 0.U(8)
    right := 0.U(8)
    again := 0.U(8)
