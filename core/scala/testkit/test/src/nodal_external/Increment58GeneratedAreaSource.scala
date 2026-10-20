package nodal_external
import nodal.*

final class Increment58GeneratedAreaSource extends Module:
  val LANES = param(3.integer, range = 1 to 8)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enable = in(Bool)
  val input = in(Vec(UInt(8), LANES))
  val output = out(Vec(UInt(8), LANES))
  val firstLane = out(UInt(8))
  clock:
    val lanes =
      for i <- hdlRange(0, LANES) yield new Area:
        val stored = Reg(0.U(8))
        if i == 0 then
          when(enable):
            stored := input.at(i)
        else
          when(enable):
            stored := 1.U(8)
        output.writable.at(i) := stored
    firstLane := lanes(0).stored

final class Increment58GeneratedAreaScaleSource extends Module:
  val LANES = param(3.integer, range = 1 to 1000000)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val output = out(UInt(8))
  clock:
    val lanes =
      for i <- hdlRange(0, LANES) yield new Area:
        val stored = Reg(0.U(8))
        val readonly: Expr[UInt] = stored
        val nested = new Area:
          val other = Reg(false.B)
        val _ = i
    val lane = lanes(0)
    val nested = lane.nested
    val _ = nested.other
    output := lane.stored

final class Increment58OrdinalArea extends Area:
  val stored = Reg(0.U(8))
  val readonly: Expr[UInt] = stored

final class Increment58GeneratedOrdinalSource(
    limit: Int,
    selected: Int,
    lower: Int = 0,
    step: Int = 1
) extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val output = out(UInt(8))
  clock:
    val lanes =
      for i <- hdlRange(lower, limit, step) yield
        val _ = i
        new Increment58OrdinalArea
    output := lanes(selected).stored

final class Increment58GeneratedDefaultOnlySource extends Module:
  val count = param(3.integer, range = 1 to 8)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val output = out(UInt(8))
  clock:
    val lanes =
      for i <- hdlRange(0, count) yield
        val _ = i
        new Increment58OrdinalArea
    output := lanes(1).stored

final class Increment58GeneratedEmptySource extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  clock:
    val lanes =
      for i <- hdlRange(0, 0) yield
        val _ = i
        new Increment58OrdinalArea
    val _ = lanes

final class Increment58GeneratedRuntimeOrdinalSource extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val output = out(UInt(8))
  clock:
    val ordinal = Reg(0.integer)
    val lanes =
      for i <- hdlRange(0, 4) yield
        val _ = i
        new Increment58OrdinalArea
    output := lanes(ordinal).stored

final class Increment58GeneratedDuplicateRoots extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enable = in(Bool)
  clock:
    val lanes =
      for i <- hdlRange(0, 3) yield new Area:
        val stored = Reg(0.U(8))
        val _ = i
        when(enable):
          stored := 1.U(8)
        when(enable):
          stored := 2.U(8)
    val _ = lanes

final class Increment58GeneratedIndependentRoots extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enable = in(Bool)
  clock:
    val lanes =
      for i <- hdlRange(0, 3) yield new Area:
        val stored = Reg(0.U(8))
        if i == 0 then
          when(enable):
            stored := 1.U(8)
        if i != 0 then
          when(enable):
            stored := 2.U(8)
    val _ = lanes

final class Increment58GeneratedForeignDomain extends Module:
  val source = ClockDomain.external(
    "source",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val destination = ClockDomain.external(
    "destination",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    25.MHz
  )
  source:
    val lanes =
      for i <- hdlRange(0, 3) yield new Area:
        val stored = Reg(0.U(8))
        val _ = i
    destination:
      val other = Reg(0.U(8))
      other := lanes(0).stored

final class Increment58GeneratedPrivateArea extends Area:
  private val secret = Reg(false.B)
  val visible = secret
  val readonly: Expr[Bool] = visible
  val boxed: Any = visible
  private val nested = new Area:
    val flag = Reg(false.B)
  val _ = nested

final class Increment58GeneratedRangeForms extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  hdlRange(0, 0): i =>
    val _ = i
  hdlRange(0, 0, 1): i =>
    val _ = i
  hdlRange(0, 0, 1, 3): i =>
    val _ = i
  clock:
    val two =
      for i <- hdlRange(0, 3) yield new Area:
        val stored = Reg(false.B)
        val _ = i
    val three =
      for i <- hdlRange(0, 3, 1) yield new Area:
        val stored = Reg(false.B)
        val _ = i
    val four =
      for i <- hdlRange(0, 3, 1, 3) yield new Area:
        val stored = Reg(false.B)
        val _ = i
    val range = hdlRange(0, 0)
    range: i =>
      val _ = i
    val _ = two
    val _ = three
    val _ = four

final class Increment58GeneratedSelectedWhen extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enable = in(Bool)
  clock:
    val lanes =
      for i <- hdlRange(0, 3) yield new Area:
        val stored = Reg(false.B)
        val readonly: Expr[Bool] = stored
        val _ = i
    val target = Reg(false.B)
    val selected = lanes(0)
    when(enable):
      target := selected.readonly

final class Increment58OrdinaryAreaStructuralWhen extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val mode = param(0.integer, range = 0 to 1)
  val enable = in(Bool)
  clock:
    val direct = Reg(false.B)
    if mode == 0 then
      when(enable):
        direct := true.B
    else
      when(enable):
        direct := false.B
    val group = new Area:
      val nested = new Area:
        val stored = Reg(false.B)
        if mode == 0 then
          when(enable):
            stored := true.B
        else
          when(enable):
            stored := false.B
    val _ = group
