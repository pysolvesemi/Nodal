package contracts.v03negative

import nodal.*

sealed trait MonitorInverseInterface extends Interface
val inverseType = Interface[MonitorInverseInterface](
  "MonitorInverseInterface",
  InterfaceMember.value("status", Bool)
)
val inverseDomain = ClockDomain.required("monitor-inverse")
val inverseMonitor = interfacePort(inverseType, monitor, "monitor", inverseDomain)

// diagnostic-anchor: NODAL-INVERT-015
val illegalInverse = inverseMonitor.inverted
