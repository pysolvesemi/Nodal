package nodal.internal.testkit

import nodal.*

final class C2aAssignedRoot extends Module:
  val driver = in(Vec(SInt(2), 1))
  val child = instance(new C2aDrivenLeaf)
  child(_.source) := driver
  val input = child(_.data)
  val result = C2aControls.reserveAssigned(input)
  val output = out(SInt(2))
  output := result.asInstanceOf[Expr[SInt]]
  C2aControls.checkStillPending()
