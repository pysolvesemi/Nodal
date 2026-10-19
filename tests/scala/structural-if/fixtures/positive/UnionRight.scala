import nodal.*
final class UnionRight extends Module:
  val MODE = param(1.integer, 0 to 2)
  val rhs: Int | Expr[Integer] = 1
  val input = in(Bits(8))
  val selected = if MODE < rhs then ~input else input
