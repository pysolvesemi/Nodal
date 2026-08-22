package contracts.registerfactory.negative

import nodal.*

object IllegalPolicy extends RegisterMap(name = "illegal-policy"):
  val reservedRegister = register(0x00, "RESERVED")
  // diagnostic-anchor: NODAL-REG-POLICY-001
  val reserved = reservedRegister.field(
    name = "RESERVED",
    dataType = Bool,
    bits = 0,
    software = SoftwareAccess.Reserved,
    hardware = HardwareAccess.Settable
  )
