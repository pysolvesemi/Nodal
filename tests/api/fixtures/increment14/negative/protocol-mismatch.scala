package examples.interfacepipeline

import nodal.*

sealed trait ValidOnlyInterface extends Interface
sealed trait ElasticInterface extends Interface

val validOnlyType = Interface[ValidOnlyInterface](
  "ValidOnlyInterface",
  InterfaceMember.valid("payload", UInt(8))
)
val elasticType = Interface[ElasticInterface](
  "ElasticInterface",
  InterfaceMember.stream("payload", UInt(8))
)
val protocolDomain = ClockDomain.required("protocol-mismatch")
val validMaster = interfacePort(validOnlyType, master, "valid", protocolDomain)
val streamSlave = interfacePort(elasticType, slave, "stream", protocolDomain)

// diagnostic-anchor: NODAL-PROTOCOL-014
validMaster.connectExact(streamSlave)
