package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-MUTABLE
final class LoopLazy extends Module:
  lazy val value = 1
  loop(LoopBound.Static(0)): _ =>
    val _ = value
