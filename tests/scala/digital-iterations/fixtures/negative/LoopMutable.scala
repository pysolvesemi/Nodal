package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-MUTABLE
final class LoopMutable extends Module:
  var state = 0
  loop(LoopBound.Static(0)): _ =>
    state += 1
