package contracts.v03negative

import nodal.*

sealed trait MonitorInterface extends Interface
val monitorType = Interface[MonitorInterface](
  "MonitorInterface",
  InterfaceMember.value("status", Bool)
)
val monitorDomain = ClockDomain.required("monitor-drive")
val monitorEndpoint = interfacePort(monitorType, monitor, "monitor", monitorDomain)

// diagnostic-anchor: NODAL-MONITOR-015
monitorEndpoint.driveMember("status", true.B)
