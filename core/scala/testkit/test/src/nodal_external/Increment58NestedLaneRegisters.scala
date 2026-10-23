package nodal_external
import nodal.*
final class Increment58NestedLaneRegisters extends Module:
  val ROWS = param(3.integer, range = 2 to 4)
  val COLS = param(3.integer, range = 2 to 4)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val data = in(Vec(UInt(8), ROWS, COLS))
  val enables = in(Vec(Bool, ROWS, COLS))
  val output = out(Vec(UInt(8), ROWS, COLS))
  val selected = out(UInt(8))
  val savedSelected = out(UInt(8))
  clock:
    val outer =
      for i <- hdlRange(0, ROWS) yield new Area:
        private val rowData = data.row(i)
        private val rowEnable = enables.row(i)
        private val rowOutput = output.writable.row(i)
        val inner =
          for j <- hdlRange(0, COLS) yield new Area:
            val stored = Reg(0.U(8))
            when(rowEnable.at(j)):
              stored := rowData.at(j)
            rowOutput.at(j) := stored
    selected := outer(1).inner(1).stored
    val saved = outer(1).inner
    savedSelected := saved(1).stored
