package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-FORGED
object ForgedLoopFactory:
  val callback: LoopIndex => Unit = DigitalIterationCallback.checkedLoop(_ => ())
