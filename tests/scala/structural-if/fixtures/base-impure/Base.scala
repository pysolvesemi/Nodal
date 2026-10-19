import nodal.*
object BaseOps:
  final def invert(input: Expr[Bits]): Expr[Bits] =
    println("unsupported separately compiled helper effect")
    ~input
