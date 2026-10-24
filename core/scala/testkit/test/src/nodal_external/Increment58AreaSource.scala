package nodal_external

import nodal.*

class Increment58ReusableArea(input: Expr[Bits]) extends Area:
  val copied = wire(Bits(8))
  copied := input
  val nested = new Area:
    val inverted = wire(Bits(8))
    inverted := ~input

final class Increment58DerivedArea(input: Expr[Bits]) extends Increment58ReusableArea(input):
  val alias = copied

final class Increment58AreaLeaf(host: Int) extends Module:
  val required = ClockDomain.required("required")
  val input = in(Bits(8))
  val output = out(Bits(8))
  output := (if host == 0 then input else ~input)

final class Increment58AreaSource extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val mode = param(0.integer, 0 to 1)
  val input = in(Bits(8))
  val output = out(Bits(8))
  def helper(value: Expr[Bits]): Increment58ReusableArea =
    val local = new Increment58ReusableArea(value)
    local
  val first = helper(input)
  val second = new Increment58DerivedArea(input)
  val anonymous = new Area:
    val local = wire(Bits(8))
    val selected = if mode == 0 then ~input else (input: Expr[Bits])
    local := selected
    val child = instance(new Increment58AreaLeaf(0)).domain(clock)
    child(_.input) := local
    val childOutput = child(_.output)
  clock:
    val stateArea = new Area:
      val stored = Reg.uninitialized(Bits(8))
      stored := anonymous.childOutput
    output := stateArea.stored

final class Increment58AreaExternalWrite extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Bits(8))
  val output = out(Bits(8))
  val group = new Area:
    val target = wire(Bits(8))
  group.target := input
  output := group.target

final class Increment58AreaDomainLeaf extends Module:
  val required = ClockDomain.required("required")
  val group = new Area:
    val stored = Reg(false.B)
    stored := true.B

final class Increment58AreaDomainSource extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  clock:
    val inherited = instance(new Increment58AreaDomainLeaf)
    val _ = inherited
  val defaultBound = instance(new Increment58AreaDomainLeaf).domain(clock)
  val namedBound = instance(new Increment58AreaDomainLeaf).domain(_.required, clock)
