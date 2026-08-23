package examples.interfacepipeline

import nodal.*

sealed trait MonitorDriveInterface extends Interface

val monitorDriveType = Interface[MonitorDriveInterface](
  "MonitorDriveInterface",
  InterfaceMember.value("status", Bool)
)
val monitorDriveDomain = ClockDomain.required("monitor-drive")
val monitorDriveEndpoint = interfacePort(monitorDriveType, monitor, "monitor", monitorDriveDomain)

// diagnostic-anchor: NODAL-MONITOR-014
monitorDriveEndpoint.driveMember("status", true.B)
