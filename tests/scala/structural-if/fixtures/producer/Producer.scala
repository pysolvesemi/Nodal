import nodal.*
object ReadOnlyProducer:
  final def invert(input: Expr[Bits]): Expr[Bits] = BaseOps.invert(input)
  final def write(output: Signal[Bits], input: Expr[Bits]): Unit = output := input
