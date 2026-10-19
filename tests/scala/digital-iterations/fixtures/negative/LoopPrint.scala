package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-UNAVAILABLE
final class LoopPrint extends Module:
  loop(LoopBound.Static(0)): _ =>
    println("bad")
