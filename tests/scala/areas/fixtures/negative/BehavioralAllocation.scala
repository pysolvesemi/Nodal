import nodal.*

final class BehavioralAreaAllocation extends Module:
  val enabled = in(Bool)
  when(enabled):
    val group = new Area:
      val target = wire(Bool)
    val _ = group
