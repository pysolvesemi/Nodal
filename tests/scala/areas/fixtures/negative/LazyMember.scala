import nodal.*

final class LazyAreaMember extends Module:
  val group = new Area:
    lazy val target = wire(Bits(8))
