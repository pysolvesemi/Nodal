package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-UNAVAILABLE
final class UnavailableConstructor extends Module:
  hdlRange(0, 2): _ =>
    val _ = instance(new MissingSummaryChild)
