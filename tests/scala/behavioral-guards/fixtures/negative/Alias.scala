import nodal.*
// Expected: NODAL-SEQUENTIAL-056-CONDITION
final class Alias extends Module:
  val MODE = param(0.integer)
  val enable = in(Bool)
  val other = in(Bool)
  val input = in(UInt(8))
  val state = Reg(0.U(8))
  val predicate = MODE == 0
  when(predicate) { state := input }
