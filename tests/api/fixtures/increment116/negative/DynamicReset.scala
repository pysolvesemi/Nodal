package contracts.registerfactory.negative

import nodal.*

final class DynamicReset(resetValue: Expr[Bool]) extends RegisterMap(name = "dynamic-reset"):
  val control = register(0x00, "CONTROL")
  // diagnostic-anchor: NODAL-REG-SOURCE-001
  val enable = control.field(
    name = "ENABLE",
    dataType = Bool,
    bits = 0,
    software = SoftwareAccess.RW,
    reset = resetValue
  )
