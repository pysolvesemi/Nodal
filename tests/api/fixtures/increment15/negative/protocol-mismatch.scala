package contracts.v03negative

import nodal.*

sealed trait ValidInterface extends Interface
sealed trait StreamInterface extends Interface
val validType = Interface[ValidInterface](
  "ValidInterface",
  InterfaceMember.valid("payload", UInt(8))
)
val streamType = Interface[StreamInterface](
  "StreamInterface",
  InterfaceMember.stream("payload", UInt(8))
)
val protocolDomain = ClockDomain.required("protocol")
val validMaster = interfacePort(validType, master, "valid", protocolDomain)
val streamSlave = interfacePort(streamType, slave, "stream", protocolDomain)

// diagnostic-anchor: NODAL-PROTOCOL-015
validMaster.connectExact(streamSlave)
