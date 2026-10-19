package nodal.iteration.fixture

import nodal.*

object SeparateHelper:
  def effect(index: LoopIndex): Unit =
    val _ = index

final class SeparateChild extends Module:
  val p = inout(Electrical)

final class SeparateImpureChild extends Module:
  println("hidden separately compiled constructor effect")
  val p = inout(Electrical)

final class SeparateDefaultChild(gain: Param[Real] = 2.0) extends Module:
  val p = inout(Electrical)
  analog:
    I(p) <+ gain * V(p)

final class SeparateDigitalChild extends Module:
  val source = in(UInt(8))
  val target = out(UInt(8))
  target := source

object SeparateCallbacks:
  val loopBody: LoopIndex => Unit = _ => ()
