package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-SUMMARY
final class WrongKindSummary extends Module:
  hdlRange(0, 2): _ =>
    val _ = instance(new WrongKindSummaryChild)
