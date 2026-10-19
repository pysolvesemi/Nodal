package nodal.iteration.fixture

import nodal.*

final class SelectorChild extends Module:
  val p = inout(Electrical)

// Expected: NODAL-ITERATION-CALLBACK-UNAVAILABLE
final class RangeSelectorWrite extends Module:
  val target = out(UInt(8))
  hdlRange(0, 2): _ =>
    val child = instance(new SelectorChild)
    val _ = child: module =>
      target := 0.U(8)
      module.p
