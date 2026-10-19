import nodal.*
object BaseOps:
  final def invert(input: Expr[Bits]): Expr[Bits] = ~input
