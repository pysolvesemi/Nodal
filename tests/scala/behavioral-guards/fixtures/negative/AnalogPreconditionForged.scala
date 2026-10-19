package nodal.behavioral.fixture
import nodal.*
// Expected: NODAL-SEQUENTIAL-056-FORGED
object AnalogPreconditionForged:
  def forge(): Unit = SequentialRuntime.requireAnalogProcedure()
