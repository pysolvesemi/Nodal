package nodal_selected_target
import nodal.*
final class GuardedSelectedLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  output := input
final class GuardedOrdinalStrict extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val other = param(3.integer, range = 0 to 3)
  val count = param(3.integer, range = 0 to 3)
  val input = in(Vec(UInt(8), 3))
  val left = out(UInt(8))
  val right = out(UInt(8))
  val again = out(UInt(8))
  if count > 1 then
    val lanes =
      for i <- hdlRange(0, count) yield new Area:
        val child = instance(new GuardedSelectedLeaf).domain(core)
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
final class GuardedOrdinalInclusive extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val other = param(3.integer, range = 0 to 3)
  val count = param(3.integer, range = 0 to 3)
  val input = in(Vec(UInt(8), 3))
  val left = out(UInt(8))
  val right = out(UInt(8))
  val again = out(UInt(8))
  if count >= 2 then
    val lanes =
      for i <- hdlRange(0, count) yield new Area:
        val child = instance(new GuardedSelectedLeaf).domain(core)
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
final class GuardedOrdinalWeak extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val other = param(3.integer, range = 0 to 3)
  val count = param(3.integer, range = 0 to 3)
  val input = in(Vec(UInt(8), 3))
  val left = out(UInt(8))
  val right = out(UInt(8))
  val again = out(UInt(8))
  if count >= 1 then
    val lanes =
      for i <- hdlRange(0, count) yield new Area:
        val child = instance(new GuardedSelectedLeaf).domain(core)
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
final class GuardedOrdinalZero extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val other = param(3.integer, range = 0 to 3)
  val count = param(3.integer, range = 0 to 3)
  val input = in(Vec(UInt(8), 3))
  val left = out(UInt(8))
  val right = out(UInt(8))
  val again = out(UInt(8))
  if count > 0 then
    val lanes =
      for i <- hdlRange(0, count) yield new Area:
        val child = instance(new GuardedSelectedLeaf).domain(core)
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
final class GuardedOrdinalForeign extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val other = param(3.integer, range = 0 to 3)
  val count = param(3.integer, range = 0 to 3)
  val input = in(Vec(UInt(8), 3))
  val left = out(UInt(8))
  val right = out(UInt(8))
  val again = out(UInt(8))
  if other > 1 then
    val lanes =
      for i <- hdlRange(0, count) yield new Area:
        val child = instance(new GuardedSelectedLeaf).domain(core)
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
final class GuardedOrdinalOr extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val other = param(3.integer, range = 0 to 3)
  val count = param(3.integer, range = 0 to 3)
  val input = in(Vec(UInt(8), 3))
  val left = out(UInt(8))
  val right = out(UInt(8))
  val again = out(UInt(8))
  if count > 1 || other > 1 then
    val lanes =
      for i <- hdlRange(0, count) yield new Area:
        val child = instance(new GuardedSelectedLeaf).domain(core)
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
