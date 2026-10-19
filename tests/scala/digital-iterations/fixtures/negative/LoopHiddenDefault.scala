package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-UNAVAILABLE
final class LoopHiddenDefault extends Module:
  private def bad(value: Int = { println("bad"); 1 }): Unit =
    val _ = value
  loop(LoopBound.Static(0)): _ =>
    bad()
