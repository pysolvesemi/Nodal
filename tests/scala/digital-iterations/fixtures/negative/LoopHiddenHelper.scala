package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-UNAVAILABLE
final class LoopHiddenHelper extends Module:
  private def bad(): Unit = println("bad")
  loop(LoopBound.Static(0)): _ =>
    bad()
