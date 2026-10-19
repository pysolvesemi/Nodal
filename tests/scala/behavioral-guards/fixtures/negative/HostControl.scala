import nodal.*
// Expected: NODAL-SEQUENTIAL-056-TOPOLOGY
final class HostControl extends Module:
  val MODE = param(0.integer)
  val enable = in(Bool)
  val other = in(Bool)
  val input = in(UInt(8))
  val state = Reg(0.U(8))
  val choose = true
  when(enable) { if choose then state := input }
