package examples.interfacepipeline

import nodal.*

sealed trait MonitorInterface extends Interface

val monitorInterfaceType = Interface[MonitorInterface](
  "MonitorInterface",
  InterfaceMember.value("status", Bool)
)
val monitorDomain = ClockDomain.required("monitor-inversion")
val monitorEndpoint = interfacePort(monitorInterfaceType, monitor, "monitor", monitorDomain)

// diagnostic-anchor: NODAL-INVERT-014
val illegalMonitorInverse = monitorEndpoint.inverted
