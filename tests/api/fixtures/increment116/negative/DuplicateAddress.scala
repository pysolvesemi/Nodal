package contracts.registerfactory.negative

import nodal.*

object DuplicateAddress extends RegisterMap(name = "duplicate"):
  val first = register(0x00, "FIRST")
  // diagnostic-anchor: NODAL-REG-MAP-001
  val second = register(0x00, "SECOND")
