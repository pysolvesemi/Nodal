package nodal_iteration_external
import nodal.*
object Uninstrumented:
  var calls = 0
  def loopEmpty(): Unit =
    loop(LoopBound.Static(0)): _ =>
      calls += 1
  def rangeEmpty(): Unit =
    hdlRange(0, 0): _ =>
      calls += 1
