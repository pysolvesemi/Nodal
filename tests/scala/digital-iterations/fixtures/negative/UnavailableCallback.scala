package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-UNAVAILABLE
final class UnavailableCallback extends Module:
  loop(LoopBound.Static(0))(SeparateCallbacks.loopBody)
