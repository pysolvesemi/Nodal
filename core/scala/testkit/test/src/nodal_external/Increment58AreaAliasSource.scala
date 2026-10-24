package nodal_external

import nodal.*

/** Real compiler-captured helper binders; no synthetic Area metadata. */
final class Increment58AreaAliasSource extends Module:
  val input = in(Bits(8))
  def helper(value: Expr[Bits]): Increment58ReusableArea =
    val local = new Increment58ReusableArea(value)
    local
  val first = helper(input)
  val second = helper(input)
  val inherited = new Increment58DerivedArea(input)

/** Empty lexical Areas must not reserve the memory write source-map identity. */
final class Increment58AreaAliasMemorySource(withAlias: Boolean) extends Module:
  val clock = ClockDomain.external(
    "clock", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val address = in(UInt(1))
  val data = in(UInt(8))
  val memory = Mem(UInt(8), 2, 1, ReadUnderWrite.OldData, MemoryOrdering.Ordered, clock)
  def helper(): Area =
    if withAlias then
      val local = new Area {}
      local
    else new Area {}
  val memory_write_0 = helper()
  memory.write(address, data, 1.U(1))
