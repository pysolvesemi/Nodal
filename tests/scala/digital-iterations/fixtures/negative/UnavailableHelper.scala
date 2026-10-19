package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-UNAVAILABLE
object UnavailableHelper:
  def test(): Unit = loop(LoopBound.Static(0))(SeparateHelper.effect)
