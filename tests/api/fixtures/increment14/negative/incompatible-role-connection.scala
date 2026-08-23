package examples.interfacepipeline

import nodal.*

sealed trait IncompatibleRoleInterface extends Interface

val incompatibleRoleType = Interface[IncompatibleRoleInterface](
  "IncompatibleRoleInterface",
  InterfaceMember.stream("payload", UInt(8))
)
val incompatibleRoleDomain = ClockDomain.required("incompatible-role")
val firstMaster = interfacePort(incompatibleRoleType, master, "first", incompatibleRoleDomain)
val secondMaster = interfacePort(incompatibleRoleType, master, "second", incompatibleRoleDomain)

// diagnostic-anchor: NODAL-ROLE-014
firstMaster.connectExact(secondMaster)
