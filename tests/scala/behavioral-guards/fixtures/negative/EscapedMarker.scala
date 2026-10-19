import nodal.*
// Expected: NODAL-SEQUENTIAL-056-MISSING
final class EscapedMarker extends Module:
  val MODE = param(0.integer)
  val enable = in(Bool)
  val escaped: Expr[Bool] = enable && (MODE == 0)
  when(escaped) { () }
