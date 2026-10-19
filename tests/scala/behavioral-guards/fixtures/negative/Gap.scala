import nodal.*
// Expected: NODAL-SEQUENTIAL-056-CHAIN
final class Gap extends Module:
  val MODE = param(0.integer)
  val enable = in(Bool)
  val other = in(Bool)
  val input = in(UInt(8))
  val state = Reg(0.U(8))
  when(enable) { state := input }
  val ignored = 1
  otherwise { state := input }
