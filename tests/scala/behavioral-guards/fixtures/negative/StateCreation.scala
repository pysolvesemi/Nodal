import nodal.*
// Expected: NODAL-SEQUENTIAL-056-UNAVAILABLE
final class StateCreation extends Module:
  val MODE = param(0.integer)
  val enable = in(Bool)
  val other = in(Bool)
  val input = in(UInt(8))
  val state = Reg(0.U(8))
  when(enable) { val _ = Reg(0.U(8)) }
