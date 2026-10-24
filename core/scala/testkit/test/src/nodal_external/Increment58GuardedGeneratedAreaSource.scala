package nodal_external
import nodal.*
final class Increment58GuardedFamilySource extends Module:
  val choice = param(0.integer, range = 0 to 1)
  val count = param(3.integer, range = 2 to 8)
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync,
    ResetPolarity.ActiveHigh, 100.MHz)
  val enable = in(Bool)
  val output = out(UInt(8))
  clock:
    val target = Reg(0.U(8))
    if choice == 0 then
      val lanes = for i <- hdlRange(0, count) yield new Area:
        val stored = Reg(0.U(8))
        val _ = (i, stored)
      when(enable):
        target := lanes(0).stored
    else
      when(enable):
        target := 0.U(8)
    output := target

final class Increment58GuardedNestedFamilySource extends Module:
  val choice = param(0.integer, range = 0 to 1)
  val count = param(3.integer, range = 2 to 8)
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync,
    ResetPolarity.ActiveHigh, 100.MHz)
  val enable = in(Bool)
  val output = out(UInt(8))
  clock:
    val outer = for j <- hdlRange(0, 2) yield new Area:
      val stored = Reg(0.U(8))
      if choice == 0 then
        val inner = for i <- hdlRange(0, count) yield new Area:
          val stored = Reg(1.U(8))
          val _ = (i, stored)
        when(enable):
          stored := inner(1).stored
      else
        when(enable):
          stored := 0.U(8)
      val _ = j
    output := outer(0).stored

final class Increment58GuardedLeaf extends Area:
  val stored = Reg(7.U(8))
  val readonly: Expr[UInt] = stored
object Increment58GuardedFactory:
  def make(count: Expr[Integer]): GeneratedAreas[Increment58GuardedLeaf] =
    for i <- hdlRange(0, count) yield
      val _ = i
      new Increment58GuardedLeaf
final class Increment58GuardedHelperSource extends Module:
  val choice = param(0.integer, range = 0 to 1)
  val count = param(3.integer, range = 2 to 8)
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync,
    ResetPolarity.ActiveHigh, 100.MHz)
  val enable = in(Bool)
  val output = out(UInt(8))
  clock:
    val target = Reg(0.U(8))
    if choice == 0 then
      val lanes = Increment58GuardedFactory.make(count)
      when(enable):
        target := lanes(1).readonly
    else
      when(enable):
        target := 0.U(8)
    output := target

final class Increment58GuardedMaybeEmptySource(select: Boolean) extends Module:
  val choice = param(0.integer, range = 0 to 1)
  val count = param(0.integer, range = 0 to 8)
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync,
    ResetPolarity.ActiveHigh, 100.MHz)
  clock:
    if choice == 0 then
      val lanes = Increment58GuardedFactory.make(count)
      val _ = lanes
    else ()
  // Host constructor option chooses a separate static source contract before callback capture.
  if select then
    clock:
      if count > 0 then
        val lanes = Increment58GuardedFactory.make(count)
        val _ = lanes(0).readonly
      else ()
