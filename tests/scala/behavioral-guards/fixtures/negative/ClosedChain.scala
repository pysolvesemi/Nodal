import nodal.*
// Expected: NODAL-SEQUENTIAL-056-CHAIN
final class ClosedChain extends Module:
  val MODE = param(0.integer)
  val enable = in(Bool)
  val other = in(Bool)
  val input = in(UInt(8))
  val state = Reg(0.U(8))
  when(enable) { state := input }
  otherwise { state := input }
  elsewhen(other) { state := input }
