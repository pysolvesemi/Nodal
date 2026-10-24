package nodal_external

import nodal.*

final class Increment58SelectedInputLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  output := input

/** No generated-body input connection: both physical lanes are driven after selection. */
final class Increment58SelectedInputTwo(mode: String = "two") extends Module:
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val first = in(UInt(8))
  val second = in(UInt(8))
  val left = out(UInt(8))
  val right = out(UInt(8))
  val lanes =
    for _lane <- hdlRange(0, 2) yield new Area:
      val _ = _lane
      val child = instance(new Increment58SelectedInputLeaf).domain(core)
  val a = lanes(0).child
  val b = lanes(1).child
  a(_.input) := first
  if mode != "missing" then b(_.input) := second
  if mode == "duplicate-alias" then
    val alias = lanes(0.integer).child
    alias(_.input) := second
  if mode == "output-write" then a(_.output) := first
  left := a(_.output)
  right := b(_.output)
