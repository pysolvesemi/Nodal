package nodal_external

import nodal.*

sealed trait PrivateInterfaceLink extends Interface
final class PrivateInterfaceLeaf extends Module:
  val required = ClockDomain.required("required")
  val definition = Interface[PrivateInterfaceLink](
    "PrivateInterfaceLink",
    InterfaceMember.value("data", Bool)
  )
  val output = interfacePort(
    definition,
    Role[SourceRole]("producer", RoleAccess.Out("data")),
    "output",
    required
  )

object PrivateInstanceModule:
  def expose(child: Instance[PrivateInterfaceLeaf]) = child.module.output
