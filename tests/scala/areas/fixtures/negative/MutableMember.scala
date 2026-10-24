import nodal.*

final class MutableAreaMember extends Module:
  val group = new Area:
    var target = wire(Bits(8))
