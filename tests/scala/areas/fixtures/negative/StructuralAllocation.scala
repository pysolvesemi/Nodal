import nodal.*

final class StructuralAreaAllocation extends Module:
  val mode = param(0.integer, 0 to 1)
  if mode == 0 then
    val group = new Area:
      val target = wire(Bool)
    val _ = group
