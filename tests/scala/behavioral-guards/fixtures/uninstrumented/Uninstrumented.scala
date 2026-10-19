package nodal.behavioral.fixture
import nodal.*
object Uninstrumented:
  var calls = 0
  def capture(flag: Expr[Bool]): Unit = when(flag) { calls += 1 }
  def direct(flag: Expr[Bool]): Unit = SequentialRuntime.when(flag, () => { calls += 1 })
  def wrongProfile(flag: Expr[Bool]): Unit = SequentialRuntime.when(
    flag,
    DigitalCollectionCallback.checked0(() => { calls += 1 })
  )
