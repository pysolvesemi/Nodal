package external.interfacepipeline

import nodal.*

sealed trait RegisterBus extends Interface

object RegisterBus:
  val definition: InterfaceType[RegisterBus] = Interface[RegisterBus](
    "RegisterBus",
    InterfaceMember.value("address", UInt(16)),
    InterfaceMember.valid("writeData", UInt(32)),
    InterfaceMember.stream("response", UInt(32))
  )

  val initiatorRole: Role[InitiatorRole] = Role[InitiatorRole](
    "initiator",
    RoleAccess.Out("address"),
    RoleAccess.Master("writeData"),
    RoleAccess.Slave("response")
  )

  val targetRole: Role[TargetRole] = Role[TargetRole](
    "target",
    RoleAccess.In("address"),
    RoleAccess.Slave("writeData"),
    RoleAccess.Master("response")
  )

final case class LibraryRequest(
    address: Expr[UInt],
    data: Expr[UInt],
    tag: Expr[UInt]
)

final case class LibraryResponse(
    data: Expr[UInt],
    tag: Expr[UInt]
)

final class ReusableInterfacePipeline extends Module:
  val domain = ClockDomain.required("library-interface")
  val address = in(UInt(16))
  val writeData = in(UInt(32))
  val tag = in(UInt(4))
  val result = out(UInt(32))

  val initiatorEndpoint = interfacePort(
    RegisterBus.definition,
    RegisterBus.initiatorRole,
    "registerBus",
    domain
  )
  val targetEndpoint = interfacePort(
    RegisterBus.definition,
    RegisterBus.targetRole,
    "registerBusPeer",
    domain
  )
  initiatorEndpoint.connectExact(targetEndpoint)

  val request = Txn(LibraryRequest(address, writeData, tag))
  val scheduled = pipe(
    request,
    PipelinePolicy(
      latency = Latency.Exact(2),
      throughput = Throughput.EveryCycle,
      ready = ReadyPath.Auto
    )
  ): current =>
    val combined = stage(current.data + current.address.extend(32))
    LibraryResponse(combined, current.tag)

  result := scheduled.value.data
  LibraryCandidateUse.consume(
    scheduled.value.tag,
    inspectSchedule(scheduled, "external-library", PipelinePolicy(Latency.Exact(2)))
  )

object LibraryCandidateUse:
  def consume(values: Any*): Unit = values.foreach(_ => ())
