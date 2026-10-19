package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-SUMMARY
// Expected detail: cyclic constructor effect-summary dependency
final class CyclicSummary extends Module:
  hdlRange(0, 2): _ =>
    val _ = instance(new CyclicSummaryChild)
