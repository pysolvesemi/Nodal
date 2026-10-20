package nodal_external
import nodal.*

final class Increment58GeneratedLaneRegisters extends Module:
  val LANES = param(3.integer, range = 1 to 8)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enable = in(Vec(Bool, LANES))
  val dataIn = in(Vec(Bits(8), LANES))
  val dataOut = out(Vec(Bits(8), LANES))
  val firstLane = out(Bits(8))
  clock:
    val lanes =
      for i <- hdlRange(0, LANES) yield new Area:
        val stored = Reg(0.U(8).extract(0, 8))
        if i == 0 then
          when(enable.at(i)):
            stored := ~dataIn.at(i)
        else
          when(enable.at(i)):
            stored := dataIn.at(i)
        dataOut.writable.at(i) := stored
    firstLane := lanes(0).stored

final class Increment58GeneratedConcreteLaneRegisters extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val enable = in(Vec(Bool, 3))
  val dataIn = in(Vec(Bits(8), 3))
  val dataOut = out(Vec(Bits(8), 3))
  val firstLane = out(Bits(8))
  clock:
    val lanes =
      for i <- hdlRange(0, 3) yield new Area:
        val stored = Reg(0.U(8).extract(0, 8))
        if i == 0 then
          when(enable.at(i)):
            stored := ~dataIn.at(i)
        else
          when(enable.at(i)):
            stored := dataIn.at(i)
        dataOut.writable.at(i) := stored
    firstLane := lanes(0).stored
