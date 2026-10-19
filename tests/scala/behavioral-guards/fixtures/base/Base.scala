import nodal.*
object GuardBase:
  final def write(state: Register[UInt], input: Expr[UInt]): Unit = state := input
