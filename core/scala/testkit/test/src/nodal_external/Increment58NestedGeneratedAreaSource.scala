package nodal_external
import nodal.*
final class Increment58NestedGeneratedConcrete(selected: Int = 0, innerLimit: Int = 3)
    extends Module:
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
        val inner =
          for j <- hdlRange(0, innerLimit) yield new Area:
            val stored = Reg(0.U(8))
            val _ = j
        output.writable.at(i) := inner(selected).stored
    val _ = outer

final class Increment58NestedGeneratedSymbolic(selected: Int = 0) extends Module:
  val rows = param(2.integer, range = 1 to 4)
  val columns = param(3.integer, range = 1 to 8)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val output = out(Vec(UInt(8), rows))
  clock:
    val outer =
      for i <- hdlRange(0, rows) yield new Area:
        val inner =
          for j <- hdlRange(0, columns) yield new Area:
            val stored = Reg(0.U(8))
            val _ = j
        output.writable.at(i) := inner(selected).stored
    val _ = outer

final class Increment58NestedGeneratedDependent extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  clock:
    val outer =
      for i <- hdlRange(1, 3) yield new Area:
        val inner =
          for j <- hdlRange(0, i) yield new Area:
            val stored = Reg(0.U(8))
            val _ = j
        val _ = inner
    val _ = outer

final class Increment58NestedGeneratedUpdated extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enable = in(Bool)
  val output = out(Vec(UInt(8), 2))
  clock:
    val outer =
      for i <- hdlRange(0, 2) yield new Area:
        val inner =
          for j <- hdlRange(0, 3) yield new Area:
            val stored = Reg(0.U(8))
            when(enable):
              stored := 1.U(8)
            val _ = j
        output.writable.at(i) := inner(0).stored
    val _ = outer

final class Increment58NestedGeneratedSymbolicOrdinal extends Module:
  val selected = param(0.integer, range = 0 to 1)
  val rows = param(2.integer, range = 1 to 4)
  val columns = param(3.integer, range = 2 to 8)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val output = out(Vec(UInt(8), rows))
  clock:
    val outer =
      for i <- hdlRange(0, rows) yield new Area:
        val inner =
          for j <- hdlRange(0, columns) yield new Area:
            val stored = Reg(0.U(8))
            val _ = j
        output.writable.at(i) := inner(selected).stored
    val _ = outer
