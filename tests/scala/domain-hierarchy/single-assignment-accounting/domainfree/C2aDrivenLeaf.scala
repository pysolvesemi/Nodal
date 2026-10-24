package nodal.internal.testkit

import nodal.*

final class C2aDrivenLeaf extends Module:
  val source = in(Vec(SInt(2), 1))
  val data = out(Vec(SInt(2), 1))
  data := source
