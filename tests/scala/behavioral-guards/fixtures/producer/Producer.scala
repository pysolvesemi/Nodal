import nodal.*
object GuardProducer:
  final def write(state: Register[UInt], input: Expr[UInt]): Unit = GuardBase.write(state, input)
  final def touch(state: Register[UInt], input: Expr[UInt], flag: Expr[Bool]): Expr[Bool] = {
    GuardBase.write(state, input)
    flag
  }
  final def invert(flag: Expr[Bool]): Expr[Bool] = !flag
  final def continuous(output: Signal[UInt], input: Expr[UInt]): Unit = output := input
