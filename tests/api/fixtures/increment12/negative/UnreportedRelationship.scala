package contracts.clockreset.negative

import nodal.*

final class UnreportedRelationship extends Module:
  val source = ClockDomain.required("source")
  val destination = ClockDomain.required("destination")
  val payload = in(UInt(8))

  destination:
    val captured = Reg(0.U(8))
    captured := payload // diagnostic-anchor: NODAL-RELATION-001
