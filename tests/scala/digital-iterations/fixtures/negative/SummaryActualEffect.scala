package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-UNAVAILABLE
final class SummaryActualEffect extends Module:
  val gain = param(real(3.0))
  hdlRange(0, 2): _ =>
    val _ = instance(SeparateFactory.actualChild({
      println("constructor argument host effect")
      gain
    }))
