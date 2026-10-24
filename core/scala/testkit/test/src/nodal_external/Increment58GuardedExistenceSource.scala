package nodal_external
import nodal.*

final class Increment58GuardedExistenceAnd extends Module:
  val count = param(0.integer, range = 0 to 8)
  val other = param(0.integer, range = 0 to 8)
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
    val target = Reg(0.U(8))
    if count > 0 && other == 0 then
      val lanes =
        for i <- hdlRange(0, count) yield new Area:
          val stored = Reg(0.U(8))
          val _ = (i, stored)
      when(enable):
        target := lanes(0).stored
    else
      when(enable):
        target := 0.U(8)
    output := target

final class Increment58GuardedExistenceFalseArm extends Module:
  val count = param(0.integer, range = 0 to 8)
  val other = param(0.integer, range = 0 to 8)
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
    val target = Reg(0.U(8))
    if count <= 0 then
      when(enable):
        target := 0.U(8)
    else
      val lanes =
        for i <- hdlRange(0, count) yield new Area:
          val stored = Reg(0.U(8))
          val _ = (i, stored)
      when(enable):
        target := lanes(0).stored
    output := target

final class Increment58GuardedExistenceForeign extends Module:
  val count = param(0.integer, range = 0 to 8)
  val other = param(0.integer, range = 0 to 8)
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
    val target = Reg(0.U(8))
    if other > 0 then
      val lanes =
        for i <- hdlRange(0, count) yield new Area:
          val stored = Reg(0.U(8))
          val _ = (i, stored)
      when(enable):
        target := lanes(0).stored
    else
      when(enable):
        target := 0.U(8)
    output := target

final class Increment58GuardedExistenceInclusive extends Module:
  val count = param(0.integer, range = 0 to 8)
  val other = param(0.integer, range = 0 to 8)
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
    val target = Reg(0.U(8))
    if count >= 0 then
      val lanes =
        for i <- hdlRange(0, count) yield new Area:
          val stored = Reg(0.U(8))
          val _ = (i, stored)
      when(enable):
        target := lanes(0).stored
    else
      when(enable):
        target := 0.U(8)
    output := target

final class Increment58GuardedExistenceLater extends Module:
  val count = param(0.integer, range = 0 to 8)
  val other = param(0.integer, range = 0 to 8)
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
    val target = Reg(0.U(8))
    if count > 0 then
      val lanes =
        for i <- hdlRange(0, count) yield new Area:
          val stored = Reg(0.U(8))
          val _ = (i, stored)
      when(enable):
        target := lanes(1).stored
    else
      when(enable):
        target := 0.U(8)
    output := target

final class Increment58GuardedExistenceNegated extends Module:
  val count = param(0.integer, range = 0 to 8)
  val other = param(0.integer, range = 0 to 8)
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
    val target = Reg(0.U(8))
    if !(count <= 0) then
      val lanes =
        for i <- hdlRange(0, count) yield new Area:
          val stored = Reg(0.U(8))
          val _ = (i, stored)
      when(enable):
        target := lanes(0).stored
    else
      when(enable):
        target := 0.U(8)
    output := target

final class Increment58GuardedExistenceOr extends Module:
  val count = param(0.integer, range = 0 to 8)
  val other = param(0.integer, range = 0 to 8)
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
    val target = Reg(0.U(8))
    if count > 0 || !(count <= 0) then
      val lanes =
        for i <- hdlRange(0, count) yield new Area:
          val stored = Reg(0.U(8))
          val _ = (i, stored)
      when(enable):
        target := lanes(0).stored
    else
      when(enable):
        target := 0.U(8)
    output := target

final class Increment58GuardedExistenceOuterHandle extends Module:
  val count = param(0.integer, range = 0 to 8)
  val other = param(0.integer, range = 0 to 8)
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
    val target = Reg(0.U(8))
    val lanes =
      for i <- hdlRange(0, count) yield new Area:
        val stored = Reg(0.U(8))
        val _ = (i, stored)
    if count > 0 then
      when(enable):
        target := lanes(0).stored
    else
      when(enable):
        target := 0.U(8)
    output := target

final class Increment58GuardedExistenceSource extends Module:
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
    val target = Reg(0.U(8))
    if count > 0 then
      val lanes =
        for i <- hdlRange(0, count) yield new Area:
          val stored = Reg(0.U(8))
          val _ = (i, stored)
      when(enable):
        target := lanes(0).stored
    else
      when(enable):
        target := 0.U(8)
    output := target

final class Increment58GuardedExistenceStrongerOr extends Module:
  val count = param(0.integer, range = 0 to 8)
  val other = param(0.integer, range = 0 to 8)
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
    val target = Reg(0.U(8))
    if count > 0 || count > 1 then
      val lanes =
        for i <- hdlRange(0, count) yield new Area:
          val stored = Reg(0.U(8))
          val _ = (i, stored)
      when(enable):
        target := lanes(0).stored
    else
      when(enable):
        target := 0.U(8)
    output := target

final class Increment58GuardedExistenceUnprovenOr extends Module:
  val count = param(0.integer, range = 0 to 8)
  val other = param(0.integer, range = 0 to 8)
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
    val target = Reg(0.U(8))
    if count > 0 || other > 0 then
      val lanes =
        for i <- hdlRange(0, count) yield new Area:
          val stored = Reg(0.U(8))
          val _ = (i, stored)
      when(enable):
        target := lanes(0).stored
    else
      when(enable):
        target := 0.U(8)
    output := target

final class Increment58GuardedExistenceUnreachable extends Module:
  val count = param(0.integer, range = 0 to 8)
  val other = param(0.integer, range = 0 to 8)
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
    val target = Reg(0.U(8))
    if count > 0 && count <= 0 then
      val lanes =
        for i <- hdlRange(0, count) yield new Area:
          val stored = Reg(0.U(8))
          val _ = (i, stored)
      when(enable):
        target := lanes(0).stored
    else
      when(enable):
        target := 0.U(8)
    output := target
