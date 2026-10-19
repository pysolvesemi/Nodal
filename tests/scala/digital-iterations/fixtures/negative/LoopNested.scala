package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-EFFECT
final class LoopNested extends Module:
  loop(LoopBound.Static(0)): _ =>
    loop(LoopBound.Static(0)): _ =>
      ()
