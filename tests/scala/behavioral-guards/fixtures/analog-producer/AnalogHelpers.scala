package analog.guard.fixture
import nodal.*
object AnalogCalls:
  var calls = 0
  var predicates = 0
object AnalogHelpers:
  def assign(flag: Expr[Bool], state: Variable[Real]): Unit =
    when(flag) { AnalogCalls.calls += 1; state := 1.0.real }
    elsewhen(flag) { AnalogCalls.calls += 1; state := 2.0.real }
    otherwise { AnalogCalls.calls += 1; state := 3.0.real }
  def wrapped(flag: Expr[Bool], state: Variable[Real]): Unit =
    analogProcedure { when(flag) { state := 4.0.real } }
  def pure(flag: Expr[Bool]): Unit = when(flag) { () }
  def predicateEffect(state: Variable[Real]): Unit =
    when({ AnalogCalls.predicates += 1; true.B }) {
      AnalogCalls.calls += 1
      state := 5.0.real
    }
