import nodal.*
// Expected: NODAL-SEQUENTIAL-056-MUTABLE
object ContextFreeHostHelper:
  var calls = 0
  def capture(flag: Expr[Bool]): Unit = when(flag) { calls += 1 }
