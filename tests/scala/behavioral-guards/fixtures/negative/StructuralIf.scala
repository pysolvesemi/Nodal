import nodal.*
// Expected: NODAL-STRUCTURAL-055-UNAVAILABLE
final class StructuralIf extends Module:
  val MODE = param(0.integer)
  val enable = in(Bool)
  val other = in(Bool)
  val input = in(UInt(8))
  val state = Reg(0.U(8))
  when(enable) { if MODE == 0 then state := input }
