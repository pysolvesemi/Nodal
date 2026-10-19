package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-UNAVAILABLE
object HiddenSingleton:
  println("bad")
  def value: Int = 1
final class LoopHiddenSingleton extends Module:
  loop(LoopBound.Static(0)): _ =>
    val _ = HiddenSingleton.value
