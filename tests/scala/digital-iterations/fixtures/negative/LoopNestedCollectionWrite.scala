package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-COLLECTION-CALLBACK-UNAVAILABLE
final class LoopNestedCollectionWrite extends Module:
  val source = in(Vec(UInt(8), 2))
  val target = out(UInt(8))
  loop(LoopBound.Static(2)): _ =>
    val _ = source.map: value =>
      target := value
      value
