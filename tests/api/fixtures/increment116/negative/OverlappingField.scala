package contracts.registerfactory.negative

import nodal.*

object OverlappingField extends RegisterMap(name = "overlap"):
  val control = register(0x00, "CONTROL")
  val low = control.field(
    name = "LOW",
    dataType = UInt(8),
    bits = 7 downto 0,
    software = SoftwareAccess.RW
  )
  // diagnostic-anchor: NODAL-REG-MAP-002
  val overlap = control.field(
    name = "OVERLAP",
    dataType = UInt(8),
    bits = 11 downto 4,
    software = SoftwareAccess.RW
  )
