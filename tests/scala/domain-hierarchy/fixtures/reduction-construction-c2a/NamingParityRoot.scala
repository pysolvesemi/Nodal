package nodal.internal.testkit

import nodal.*

final class NamingParityLeaf extends Module:
  val source = in(Vec(SInt(2), 2))
  val data = out(Vec(SInt(2), 2))
  data := source
  NamingControls.childModule = this

final class NamingParityRoot extends Module:
  val source = in(Vec(SInt(2), 2))
  val child = instance(new NamingParityLeaf)
  child(_.source) := source
  val lane = source.at(0).named("explicit_lane")
  val duplicate = source.at(1).named("explicit_lane")
  val output = out(SInt(2))
  output := lane
  val shapedOutput = out(SInt(2))
  shapedOutput := source.at(1)
  NamingControls.retain(this, child)
