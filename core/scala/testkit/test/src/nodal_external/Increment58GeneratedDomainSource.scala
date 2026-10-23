package nodal_external
import nodal.*

final class Increment58GeneratedNonzeroBounds extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val data = in(Vec(UInt(8), 8))
  val enables = in(Vec(Bool, 8))
  val first = out(UInt(8))
  val middle = out(UInt(8))
  val last = out(UInt(8))
  clock:
    val lanes =
      for i <- hdlRange(1, 4) yield new Area:
        val stored = Reg(0.U(8))
        when(enables.at(i)):
          stored := data.at(i)
    first := lanes(0).stored
    middle := lanes(1).stored
    last := lanes(2).stored

final class Increment58GeneratedNonunitBounds extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val data = in(Vec(UInt(8), 8))
  val enables = in(Vec(Bool, 8))
  val first = out(UInt(8))
  val middle = out(UInt(8))
  val last = out(UInt(8))
  clock:
    val lanes =
      for i <- hdlRange(1, 7, 2) yield new Area:
        val stored = Reg(0.U(8))
        when(enables.at(i)):
          stored := data.at(i)
    first := lanes(0).stored
    middle := lanes(1).stored
    last := lanes(2).stored

final class Increment58GeneratedEmptyBounds extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val data = in(UInt(8))
  val enable = in(Bool)
  val output = out(UInt(8))
  clock:
    val lanes =
      for i <- hdlRange(0, 0) yield new Area:
        val stored = Reg(0.U(8))
        val _ = i
        when(enable):
          stored := data
    val _ = lanes
    output := 0.U(8)

final class Increment58GeneratedMaybeEmptyBounds extends Module:
  val count = param(0.integer, range = 0 to 4)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val data = in(Vec(UInt(8), 4))
  val enables = in(Vec(Bool, 4))
  val output = out(UInt(8))
  clock:
    val lanes =
      for i <- hdlRange(0, count) yield new Area:
        val stored = Reg(0.U(8))
        when(enables.at(i)):
          stored := data.at(i)
    if count > 0 then output := lanes(0).stored
    else output := 0.U(8)

final class Increment58GeneratedGuardedBounds extends Module:
  val count = param(0.integer, range = 0 to 4)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val data = in(Vec(UInt(8), 4))
  val enables = in(Vec(Bool, 4))
  val output = out(UInt(8))
  clock:
    if count > 0 then
      val lanes =
        for i <- hdlRange(0, count) yield new Area:
          val stored = Reg(0.U(8))
          when(enables.at(i)):
            stored := data.at(i)
      output := lanes(0).stored
    else output := 0.U(8)

final class Increment58GeneratedCapturedBounds extends Module:
  val ROWS = param(3.integer, range = 2 to 4)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val data = in(Vec(UInt(8), 4, 4))
  val enables = in(Vec(Bool, 4, 4))
  val first = out(UInt(8))
  val last = out(UInt(8))
  clock:
    val outer =
      for i <- hdlRange(0, ROWS) yield new Area:
        private val rowData = data.row(i)
        private val rowEnables = enables.row(i)
        val inner =
          for j <- hdlRange(0, i + 1.integer) yield new Area:
            val stored = Reg(0.U(8))
            when(rowEnables.at(j)):
              stored := rowData.at(j)
    first := outer(0).inner(0).stored
    last := outer(1).inner(0).stored

final class Increment58GeneratedCapturedSelectedBounds extends Module:
  val ROWS = param(3.integer, range = 2 to 4)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val data = in(Vec(UInt(8), 4, 4))
  val enables = in(Vec(Bool, 4, 4))
  val output = out(UInt(8))
  clock:
    val outer =
      for i <- hdlRange(0, ROWS) yield new Area:
        private val rowData = data.row(i)
        private val rowEnables = enables.row(i)
        val inner =
          for j <- hdlRange(0, i + 1.integer) yield new Area:
            val stored = Reg(0.U(8))
            when(rowEnables.at(j)):
              stored := rowData.at(j)
    output := outer(1).inner(1).stored

final class Increment58GeneratedCapturedNonunitBounds extends Module:
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
    last := outer(1).inner(0).stored

final class Increment58GeneratedGuardedScalarExpression extends Module:
  val count = param(2.integer, range = 0 to 4)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val a = in(UInt(8))
  val b = in(UInt(8))
  val enable = in(Bool)
  val output = out(UInt(8))
  clock:
    if count > 0 then
      val lanes =
        for i <- hdlRange(0, count) yield new Area:
          private val combined = a ^ b
          val stored = Reg(0.U(8))
          val _ = i
          when(enable):
            stored := combined
      output := lanes(0).stored
    else output := 0.U(8)

final class Increment58GeneratedGuardedSymbolicExpression extends Module:
  val count = param(2.integer, range = 0 to 4)
  val width = param(4.integer, range = 2 to 8)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val a = in(UInt(width))
  val b = in(UInt(width))
  val enable = in(Bool)
  val output = out(UInt(width))
  clock:
    if count > 0 then
      val lanes =
        for i <- hdlRange(0, count) yield new Area:
          private val combined = a ^ b
          val stored = Reg.uninitialized(UInt(width))
          val _ = i
          when(enable):
            stored := combined
      output := lanes(0).stored
    else output := a

final class Increment58GeneratedGuardedNestedExpression extends Module:
  val count = param(2.integer, range = 0 to 4)
  val width = param(4.integer, range = 2 to 8)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val a = in(UInt(width))
  val b = in(UInt(width))
  val enable = in(Bool)
  val output = out(UInt(width))
  clock:
    if count > 0 then
      val lanes =
        for i <- hdlRange(0, count) yield new Area:
          private val combined = a ^ b
          val inner =
            for j <- hdlRange(0, 2) yield new Area:
              private val nestedValue = combined ^ a
              val stored = Reg.uninitialized(UInt(width))
              val _ = i
              val _ = j
              when(enable):
                stored := nestedValue
          val _ = inner
      output := lanes(0).inner(0).stored
    else output := a

final class Increment58NestedGuardedRegisters extends Module:
  val count = param(2.integer, range = 0 to 4)
  val mode = param(1.integer, range = 0 to 1)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val data = in(UInt(8))
  val enable = in(Bool)
  val output = out(UInt(8))
  clock:
    if count > 0 then
      if mode == 1 then
        val lanes =
          for i <- hdlRange(0, count) yield new Area:
            val _ = i
            val stored = Reg(0.U(8))
            when(enable):
              stored := data
        output := lanes(0).stored
      else output := data
    else output := 0.U(8)
