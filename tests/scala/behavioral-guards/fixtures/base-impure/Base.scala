import nodal.*
object GuardBase:
  final def write(state: Register[UInt], input: Expr[UInt]): Unit = {
    println("must never execute")
    state := input
  }
