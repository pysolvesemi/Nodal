package nodal_empty_init
import nodal.*

final class Case_negative extends Module:
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync, ResetPolarity.ActiveHigh, 100.MHz)
  clock:
    val lanes =
      for i <- hdlRange(0, 0) yield new Area:
        val _ = i
        val stored = Reg((-1).U(i + 1.integer))
    val _ = lanes

final class Case_zero extends Module:
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync, ResetPolarity.ActiveHigh, 100.MHz)
  clock:
    val lanes =
      for i <- hdlRange(0, 0) yield new Area:
        val _ = i
        val stored = Reg(0.U(0.integer))
    val _ = lanes

final class Case_badoperand extends Module:
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync, ResetPolarity.ActiveHigh, 100.MHz)
  clock:
    val lanes =
      for i <- hdlRange(0, 0) yield new Area:
        val _ = i
        val stored = Reg(0.U(i + (1.integer / 0.integer)))
    val _ = lanes

final class Case_runtime extends Module:
  val width = in(Integer)
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync, ResetPolarity.ActiveHigh, 100.MHz)
  clock:
    val lanes =
      for i <- hdlRange(0, 0) yield new Area:
        val _ = i
        val stored = Reg(0.U(i + width))
    val _ = lanes

final class Case_unbounded extends Module:
  val width = param(3.integer)
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync, ResetPolarity.ActiveHigh, 100.MHz)
  clock:
    val lanes =
      for i <- hdlRange(0, 0) yield new Area:
        val _ = i
        val stored = Reg(0.U(i + width))
    val _ = lanes

final class Case_parameter extends Module:
  val width = param(3.integer, range = 1 to 8)
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync, ResetPolarity.ActiveHigh, 100.MHz)
  clock:
    val lanes =
      for i <- hdlRange(0, 0) yield new Area:
        val _ = i
        val stored = Reg(0.U(i + width))
    val _ = lanes

final class Case_attainedfit extends Module:
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync, ResetPolarity.ActiveHigh, 100.MHz)
  clock:
    val lanes =
      for i <- hdlRange(0, 2) yield new Area:
        val _ = i
        val stored = Reg(1.U(i + 1.integer))
    val _ = lanes

final class Case_attainedbad extends Module:
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync, ResetPolarity.ActiveHigh, 100.MHz)
  clock:
    val lanes =
      for i <- hdlRange(0, 2) yield new Area:
        val _ = i
        val stored = Reg(2.U(i + 1.integer))
    val _ = lanes

final class Case_attainedzero extends Module:
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync, ResetPolarity.ActiveHigh, 100.MHz)
  clock:
    val lanes =
      for i <- hdlRange(0, 2) yield new Area:
        val _ = i
        val stored = Reg(0.U(i))
    val _ = lanes

final class Case_signed extends Module:
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync, ResetPolarity.ActiveHigh, 100.MHz)
  clock:
    val lanes =
      for i <- hdlRange(0, 0) yield new Area:
        val _ = i
        val stored = Reg((-1).S(i + 1.integer))
    val _ = lanes

final class Case_numeric extends Module:
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync, ResetPolarity.ActiveHigh, 100.MHz)
  clock:
    val lanes =
      for i <- hdlRange(0, 0) yield new Area:
        val _ = i
        val stored = Reg(0.U(i + 1.integer).resizeChecked(1))
    val _ = lanes
final class ForeignWidthLeaf extends Module:
  val width = param(3.integer, range = 1 to 8)
final class Case_foreign extends Module:
  val other = instance(new ForeignWidthLeaf)
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync, ResetPolarity.ActiveHigh, 100.MHz)
  clock:
    val lanes =
      for i <- hdlRange(0, 0) yield new Area:
        val stored = Reg(0.U(i + other(_.width)))
    val _ = lanes
final class Case_nested extends Module:
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.Sync, ResetPolarity.ActiveHigh, 100.MHz)
  clock:
    val lanes =
      for i <- hdlRange(0, 0) yield new Area:
        val input = Reg.uninitialized(Vec(Struct("Nested", StructField("word", UInt(i + 1.integer)), StructField("flag", Bool)), 2))
        val stored = Reg(input)
    val _ = lanes
