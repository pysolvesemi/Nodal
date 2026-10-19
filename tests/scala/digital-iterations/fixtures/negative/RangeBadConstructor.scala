package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-UNAVAILABLE
final class BadChild extends Module:
  println("bad constructor")
final class RangeBadConstructor extends Module:
  hdlRange(0, 2): _ =>
    val _ = instance(new BadChild)
