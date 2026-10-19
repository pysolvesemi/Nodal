package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-UNAVAILABLE
final class LoopAnalog extends Module:
  val p = inout(Electrical)
  loop(LoopBound.Static(0)): _ =>
    analog:
      V(p) <+ 1.V
