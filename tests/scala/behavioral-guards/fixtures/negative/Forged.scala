package nodal.behavioral.fixture
import nodal.*
// Expected: NODAL-SEQUENTIAL-056-FORGED
object Forged:
  def forge(): Unit = {
    val _ = DigitalCollectionCallback.checkedSequential(() => ())
    val _ = SequentialRuntime.staticCompare(0.integer, 0.integer, "eq")
    SequentialRuntime.when(false.B, () => ())
  }
