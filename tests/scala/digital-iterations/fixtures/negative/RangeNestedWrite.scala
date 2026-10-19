package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-UNAVAILABLE
final class RangeNestedWrite extends Module:
  val target = out(UInt(8))
  hdlRange(0, 2): _ =>
    hdlRange(0, 2): _ =>
      target := 0.U(8)
