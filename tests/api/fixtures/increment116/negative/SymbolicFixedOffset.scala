package contracts.registerfactory.negative

import nodal.*

object SymbolicFixedOffset extends RegisterMap(name = "invalid"):
  val symbolicOffset = param(0.integer)
  // diagnostic-anchor: NODAL-REG-PARAM-001
  val invalid = register(symbolicOffset, "INVALID")
