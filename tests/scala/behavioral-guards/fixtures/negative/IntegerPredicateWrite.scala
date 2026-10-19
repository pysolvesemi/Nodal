import nodal.*
// Expected: NODAL-SEQUENTIAL-056-UNAVAILABLE
final class IntegerPredicateWrite extends Module:
  val MODE = param(0.integer)
  val enable = in(Bool)
  val other = in(Bool)
  val input = in(UInt(8))
  val state = Reg(0.U(8))
  private def predicate(): Expr[Integer] = { state := input; MODE }
  when(predicate() == 0) { state := input }
