package contracts.v03negative

import nodal.*

sealed trait StoredInterface extends Interface
val storedType = Interface[StoredInterface](
  "StoredInterface",
  InterfaceMember.value("payload", UInt(8))
)
val storedDomain = ClockDomain.required("stored")
val storedEndpoint = interfacePort(storedType, master, "stored", storedDomain)

// diagnostic-anchor: NODAL-IFACE-015
val illegalStorage = Reg(storedEndpoint)
