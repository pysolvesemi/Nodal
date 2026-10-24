package nodal_external
import nodal.*
final class Increment58SelectedNestedAreaSource(outerOrdinal: Int = 0, innerOrdinal: Int = 1)
    extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val output = out(UInt(8))
  val second = out(UInt(8))
  clock:
    val outer =
      for i <- hdlRange(0, 2) yield new Area:
        val inner =
          for j <- hdlRange(0, 3) yield new Area:
            val stored = Reg(0.U(8))
            val readonly: Expr[UInt] = stored
            val _ = j
        val _ = i
    val saved = outer(outerOrdinal).inner
    output := outer(outerOrdinal).inner(innerOrdinal).stored
    second := saved(innerOrdinal).readonly

final class Increment58SelectedNestedSymbolicSource extends Module:
  val rows = param(2.integer, range = 1 to 4)
  val columns = param(3.integer, range = 2 to 8)
  val outerOrdinal = param(0.integer, range = 0 to 0)
  val innerOrdinal = param(1.integer, range = 0 to 1)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enable = in(Bool)
  val output = out(UInt(8))
  clock:
    val outer =
      for i <- hdlRange(0, rows) yield new Area:
        val inner =
          for j <- hdlRange(0, columns) yield new Area:
            val stored = Reg(0.U(8))
            val _ = j
        val _ = i
    val target = Reg(0.U(8))
    when(enable):
      target := outer(outerOrdinal).inner(innerOrdinal).stored
    output := target

final class Increment58SelectedNestedLeaf extends Area:
  val stored = Reg(0.U(8))
  val readonly: Expr[UInt] = stored

final class Increment58SelectedNamedArea extends Area:
  private val hidden =
    for j <- hdlRange(1, 9, 2) yield
      val _ = j
      new Increment58SelectedNestedLeaf
  val inner = hidden

object Increment58SelectedNestedReadHelper:
  def read(value: GeneratedAreaRef[Increment58SelectedNestedLeaf]): Expr[UInt] = value.readonly

final class Increment58SelectedNamedFamilySource extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val output = out(UInt(8))
  clock:
    val outer =
      for i <- hdlRange(5, 9, 2) yield
        val _ = i
        new Increment58SelectedNamedArea
    val saved: GeneratedAreas[Increment58SelectedNestedLeaf] = outer(1).inner
    output := Increment58SelectedNestedReadHelper.read(saved(3))

final class Increment58SelectedTripleFamilySource extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val output = out(UInt(8))
  clock:
    val outer =
      for i <- hdlRange(0, 2) yield new Area:
        val inner =
          for j <- hdlRange(0, 3) yield new Area:
            val last =
              for k <- hdlRange(0, 4) yield new Area:
                val stored = Reg(0.U(8))
                val _ = k
            val _ = j
        val _ = i
    output := outer(1).inner(2).last(3).stored

final class Increment58SelectedGroupedFamilySource extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val output = out(UInt(8))
  clock:
    val outer =
      for i <- hdlRange(0, 2) yield new Area:
        val group = new Area:
          val inner =
            for j <- hdlRange(0, 3) yield
              val _ = j
              new Increment58SelectedNestedLeaf
        val _ = i
    output := outer(1).group.inner(2).readonly
