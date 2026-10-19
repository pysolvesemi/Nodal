package nodal.iteration.fixture

import nodal.*

/** An available checked summary admits this separate harmless helper. */
object AttestedHelper:
  def test(): Unit = loop(LoopBound.Static(1))(SeparateHelper.effect)
