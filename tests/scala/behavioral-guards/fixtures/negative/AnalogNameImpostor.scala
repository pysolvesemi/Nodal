import nodal.*
// Expected: NODAL-SEQUENTIAL-056-MUTABLE
object AnalogNameImpostor:
  def analogProcedure(body: => Unit): Unit = body
  var calls = 0
  def capture(flag: Expr[Bool]): Unit =
    analogProcedure { when(flag) { calls += 1 } }
