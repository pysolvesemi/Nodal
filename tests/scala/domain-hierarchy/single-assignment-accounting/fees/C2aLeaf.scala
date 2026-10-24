package nodal.internal.testkit

import nodal.*

final class C2aLeaf(count: Int) extends Module:
  val data = out(Vec(SInt(2), count))
