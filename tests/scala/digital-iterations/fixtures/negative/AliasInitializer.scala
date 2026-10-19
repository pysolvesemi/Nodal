package nodal.iteration.fixture

import nodal.*

// Expected: NODAL-ITERATION-CALLBACK-UNAVAILABLE
object AliasInitializer:
  println("bad initializer")
  val callback: LoopIndex => Unit = _ => ()

object AliasInitializerCaller:
  def test(): Unit = loop(LoopBound.Static(0))(AliasInitializer.callback)
