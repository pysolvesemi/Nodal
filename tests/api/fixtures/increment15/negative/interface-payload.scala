package contracts.v03negative

import nodal.*

sealed trait PayloadInterface extends Interface
val payloadType = Interface[PayloadInterface](
  "PayloadInterface",
  InterfaceMember.value("payload", UInt(8))
)
val payloadDomain = ClockDomain.required("payload")
val payloadEndpoint = interfacePort(payloadType, master, "payload", payloadDomain)

// diagnostic-anchor: NODAL-PAYLOAD-015
val illegalPayload = Valid(payloadEndpoint)
