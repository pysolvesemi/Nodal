package nodal_external

import nodal.*

/** An outer lexical domain attributes these ports through the local requirement. */
final class Increment58SelectedInputDomainLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(UInt(8))
  val output = out(UInt(8))
  output := input

final class Increment58SelectedInputDomainTwo(foreignDriver: Boolean) extends Module:
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val foreign = ClockDomain.external(
    "foreign", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val source = if foreignDriver then foreign else core
  source:
    val second = in(UInt(8))
    core:
      val left = out(UInt(8))
      val right = out(UInt(8))
      val first = in(UInt(8))
      val lanes =
        for _lane <- hdlRange(0, 2) yield new Area:
          val _ = _lane
          val child = instance(new Increment58SelectedInputDomainLeaf).domain(core)
      val a = lanes(0).child
      val b = lanes(1).child
      a(_.input) := first
      b(_.input) := second
      left := a(_.output)
      right := b(_.output)

/** Construct the neutral child family before entering the parent input domain. */
final class Increment58SelectedInputNeutralAttributed extends Module:
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val lanes =
    for _lane <- hdlRange(0, 2) yield new Area:
      val _ = _lane
      val child = instance(new Increment58SelectedInputDomainLeaf).domain(core)
  val a = lanes(0).child
  val b = lanes(1).child
  core:
    val left = out(UInt(8))
    val right = out(UInt(8))
    val first = in(UInt(8))
    val second = in(UInt(8))
    a(_.input) := first
    b(_.input) := second
    left := a(_.output)
    right := b(_.output)
