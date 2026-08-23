package examples.interfacepipeline

import nodal.*

sealed trait StoredInterface extends Interface

val storedInterfaceType = Interface[StoredInterface](
  "StoredInterface",
  InterfaceMember.value("payload", UInt(8))
)
val storedInterfaceDomain = ClockDomain.required("stored-interface")
val storedEndpoint = interfacePort(storedInterfaceType, master, "stored", storedInterfaceDomain)

// diagnostic-anchor: NODAL-IFACE-014
val illegalStoredInterface = Reg(storedEndpoint)
