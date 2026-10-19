package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-UNAVAILABLE
final class LoopDeclare extends Module:
  loop(LoopBound.Static(0)): _ =>
    val tap = node(Electrical)
    val _ = tap
