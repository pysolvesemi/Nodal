package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-FORGED
object ForgedRangeFactory:
  val callback: Expr[Integer] => Unit = DigitalIterationCallback.checkedRange(_ => ())
