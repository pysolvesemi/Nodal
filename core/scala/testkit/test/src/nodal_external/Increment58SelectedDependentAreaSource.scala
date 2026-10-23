package nodal_external
import nodal.*

final class Increment58SelectedDependentArea(
    outerOrdinal: Int,
    innerOrdinal: Int,
    nonunit: Boolean = false
) extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val output = out(UInt(8))
  val savedOutput = out(UInt(8))
  clock:
    val outer =
      for i <-
          hdlRange(if nonunit then 2 else 0, if nonunit then 8 else 3, if nonunit then 2 else 1)
      yield new Area:
        val inner =
          for j <- hdlRange(0, i + 1.integer) yield new Area:
            val _ = j
            val stored = Reg(0.U(8))
    val saved = outer(outerOrdinal).inner
    output := outer(outerOrdinal).inner(innerOrdinal).stored
    savedOutput := saved(innerOrdinal).stored

final class Increment58SymbolicSelectedDependentArea extends Module:
  val rows = param(3.integer, range = 3 to 4)
  val selected = param(1.integer, range = 1 to 2)
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
      for i <- hdlRange(0, rows) yield new Area:
        val inner =
          for j <- hdlRange(0, i + 1.integer) yield new Area:
            val _ = j
            val stored = Reg(0.U(8))
    output := outer(selected).inner(1).stored

final class Increment58SymbolicStepSelectedDependentArea extends Module:
  val lower = param(1.integer, range = 1 to 2)
  val step = param(1.integer, range = 1 to 2)
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
      for i <- hdlRange(lower, 8, step) yield new Area:
        val inner =
          for j <- hdlRange(0, i + 1.integer) yield new Area:
            val _ = j
            val stored = Reg(0.U(8))
    output := outer(1).inner(2).stored

final class Increment58ActiveSelectedDependentArea(innerOrdinal: Int = 2) extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val output = out(Vec(UInt(8), 2))
  clock:
    val outer =
      for i <- hdlRange(0, 2) yield new Area:
        val middle =
          for j <- hdlRange(1, 3) yield new Area:
            val inner =
              for k <- hdlRange(0, i + j + 1.integer) yield new Area:
                val _ = k
                val stored = Reg(0.U(8))
        output.writable.at(i) := middle(1).inner(innerOrdinal).stored
    val _ = outer

final class Increment58GuardedSelectedDependentArea(validGuard: Boolean) extends Module:
  private val guardLimit = if validGuard then 1 else 0
  val count = param(2.integer, range = 0 to 4)
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
      for i <- hdlRange(0, count) yield new Area:
        val inner =
          for j <- hdlRange(0, i + 1.integer) yield new Area:
            val _ = j
            val stored = Reg(0.U(8))
    if count > guardLimit then output := outer(1).inner(1).stored
    else output := 0.U(8)

final class Increment58ForeignSelectedDependentArea extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val output = out(UInt(8))
  val secondOutput = out(UInt(8))
  clock:
    val first =
      for i <- hdlRange(0, 3) yield new Area:
        val inner =
          for j <- hdlRange(0, i + 1.integer) yield new Area:
            val _ = j
            val stored = Reg(0.U(8))
    val second =
      for i <- hdlRange(0, 3) yield new Area:
        val inner =
          for j <- hdlRange(0, i + 1.integer) yield new Area:
            val _ = j
            val stored = Reg(0.U(8))
    output := first(1).inner(1).stored
    secondOutput := second(1).inner(1).stored

final class Increment58NonunitDependentRegisters extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val data = in(Vec(UInt(8), 8, 8))
  val enables = in(Vec(Bool, 8, 8))
  val first = out(UInt(8))
  val last = out(UInt(8))
  clock:
    val outer =
      for i <- hdlRange(2, 8, 2) yield new Area:
        private val rowData = data.row(i)
        private val rowEnables = enables.row(i)
        val inner =
          for j <- hdlRange(0, i + 1.integer) yield new Area:
            val stored = Reg(0.U(8))
            when(rowEnables.at(j)):
              stored := rowData.at(j)
    first := outer(0).inner(0).stored
    last := outer(1).inner(4).stored

final class Increment58SymbolicDomainRegisters extends Module:
  val lower = param(1.integer, range = 1 to 3)
  val upper = param(8.integer, range = 8 to 10)
  val step = param(1.integer, range = 1 to 3)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val data = in(Vec(UInt(8), 16))
  val enables = in(Vec(Bool, 16))
  val first = out(UInt(8))
  val middle = out(UInt(8))
  clock:
    val lanes =
      for i <- hdlRange(lower, upper, step) yield new Area:
        val stored = Reg(0.U(8))
        when(enables.at(i)):
          stored := data.at(i)
    first := lanes(0).stored
    middle := lanes(1).stored

final class Increment58SymbolicBoundsFixedStepRegisters extends Module:
  val lower = param(1.integer, range = 1 to 3)
  val upper = param(8.integer, range = 8 to 10)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val data = in(Vec(UInt(8), 16))
  val enables = in(Vec(Bool, 16))
  val first = out(UInt(8))
  val middle = out(UInt(8))
  clock:
    val lanes =
      for i <- hdlRange(lower, upper, 2) yield new Area:
        val stored = Reg(0.U(8))
        when(enables.at(i)):
          stored := data.at(i)
    first := lanes(0).stored
    middle := lanes(1).stored
