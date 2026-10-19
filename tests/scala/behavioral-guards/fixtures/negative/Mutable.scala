import nodal.*
// Expected: NODAL-SEQUENTIAL-056-MUTABLE
final class Mutable extends Module:
  val MODE = param(0.integer)
  val enable = in(Bool)
  val other = in(Bool)
  val input = in(UInt(8))
  val state = Reg(0.U(8))
  var counter = 0
  when(enable) { counter += 1 }
