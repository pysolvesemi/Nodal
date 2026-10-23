package nodal_external
import nodal.*
final class Increment58FamilyCaptureSource extends Module:
  val count = param(0.integer, range = 0 to 8)
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
    val lanes =
      for i <- hdlRange(0, count) yield new Area:
        val stored = Reg(0.U(8))
        val _ = (i, stored)
    val target = Reg(0.U(8))
    if count > 0 then
      when(enable):
        target := lanes(0).stored
    else
      when(enable):
        target := 0.U(8)
    output := target

final class Increment58FamilyCaptureFalse extends Module:
  val count = param(0.integer, range = 0 to 8)
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
    val lanes =
      for i <- hdlRange(0, count) yield new Area:
        val stored = Reg(0.U(8))
        val _ = (i, stored)
    val target = Reg(0.U(8))
    if count <= 0 then
      when(enable):
        target := 0.U(8)
    else
      when(enable):
        target := lanes(0).stored
    output := target

final class Increment58FamilyCaptureNested extends Module:
  val count = param(0.integer, range = 0 to 8)
  val other = param(1.integer, range = 0 to 1)
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
    val lanes =
      for i <- hdlRange(0, count) yield new Area:
        val stored = Reg(0.U(8))
        val _ = (i, stored)
    val target = Reg(0.U(8))
    if other > 0 then
      if count > 0 then
        when(enable):
          target := lanes(0).stored
    output := target

final class Increment58FamilyCaptureValue extends Module:
  val count = param(0.integer, range = 0 to 8)
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
    val lanes =
      for i <- hdlRange(0, count) yield new Area:
        val stored = Reg(0.U(8))
        val _ = (i, stored)
    val chosen = if count > 0 then lanes(0).stored else 0.U(8)
    output := chosen

final class Increment58FamilyCaptureSelected extends Module:
  val count = param(0.integer, range = 0 to 8)
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
      for i <- hdlRange(0, 2) yield new Area:
        val inner =
          for j <- hdlRange(0, count) yield new Area:
            val stored = Reg(0.U(8))
            val _ = (i, j, stored)
    val lanes = outer(0).inner
    val target = Reg(0.U(8))
    if count > 0 then
      when(enable):
        target := lanes(0).stored
    else
      when(enable):
        target := 0.U(8)
    output := target

final class Increment58FamilyCaptureTwo extends Module:
  val count = param(0.integer, range = 0 to 8)
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
    val lanes =
      for i <- hdlRange(0, count) yield new Area:
        val stored = Reg(0.U(8))
        val _ = (i, stored)
    val otherLanes =
      for i <- hdlRange(0, count) yield new Area:
        val stored = Reg(0.U(8))
        val _ = (i, stored)
    val target = Reg(0.U(8))
    if count > 0 then
      when(enable):
        target := (lanes(0).stored + otherLanes(0).stored).wrap(8)
    else
      when(enable):
        target := 0.U(8)
    output := target

final class Increment58FamilyCaptureBadGuard extends Module:
  val count = param(0.integer, range = 0 to 8)
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
    val lanes =
      for i <- hdlRange(0, count) yield new Area:
        val stored = Reg(0.U(8))
        val _ = (i, stored)
    val target = Reg(0.U(8))
    if count >= 0 then
      when(enable):
        target := lanes(0).stored
    else
      when(enable):
        target := 0.U(8)
    output := target

final class Increment58FamilyCapturePure extends Module:
  val count = param(0.integer, range = 0 to 8)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(UInt(8), 2))
  val output = out(Vec(UInt(8), 2))
  clock:
    val lanes =
      for i <- hdlRange(0, count) yield new Area:
        val stored = Reg(0.U(8))
        val _ = (i, stored)
    output := input.map: value =>
      if count > 0 then lanes(0).stored else value
final class Increment58FamilyCaptureProcedural extends Module:
  val count = param(0.integer, range = 0 to 8)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  clock:
    val lanes =
      for i <- hdlRange(0, count) yield new Area:
        val stored = Reg(0.U(8))
        val _ = (i, stored)
    loop(LoopBound.Static(1)): index =>
      val selected = if count > 0 then lanes(0).stored else 0.U(8)
      val _ = index
      val _ = selected
