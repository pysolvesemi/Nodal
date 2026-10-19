package nodal.iteration.fixture

import nodal.*

object SeparateFactory:
  def child(): SeparateChild = new SeparateChild
  def defaultChild(): SeparateDefaultChild = new SeparateDefaultChild
  def actualChild(gain: Param[Real]): SeparateDefaultChild = new SeparateDefaultChild(gain)

object ImpureSeparateFactory:
  def child(): SeparateChild =
    println("hidden factory host effect")
    new SeparateChild

object ImpureSeparateFactorySingleton:
  println("hidden factory singleton effect")
  def child(): SeparateChild = new SeparateChild
