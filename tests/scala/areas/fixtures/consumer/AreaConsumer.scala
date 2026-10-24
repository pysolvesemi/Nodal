package nodal

import area_factory.*

final class SeparateAreaConsumer extends Module:
  val input = in(Bits(8))
  val output = out(Bits(8))
  val group = AreaFactory.make(input)
  output := group.nested.inverted

object AreaConsumer:
  def main(args: Array[String]): Unit =
    val _ = args
    val snapshot = ConstructionKernel.inspect(new SeparateAreaConsumer)
    assert(snapshot.modules.size == 1)
    assert(snapshot.areas.size == 2)
    val group = snapshot.areas.find(_.path.endsWith(".group")).get
    val copied = group.members.find(_.name == "copied").get
    val alias = group.members.find(_.name == "alias").get
    assert(copied.target == alias.target)
    assert(snapshot.areas.exists(_.path.endsWith(".group.nested")))
    println("AREA_SEPARATE_LIBRARY_PASS")
