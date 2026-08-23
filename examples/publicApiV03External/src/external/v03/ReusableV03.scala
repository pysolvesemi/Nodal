package external.v03

import nodal.*

sealed trait UnifiedLink extends Interface

object UnifiedLink:
  val definition: InterfaceType[UnifiedLink] = Interface[UnifiedLink](
    "UnifiedLink",
    InterfaceMember.value("tag", UInt(8)),
    InterfaceMember.stream("payload", UInt(32))
  )

  val sourceRole: Role[SourceRole] = Role[SourceRole](
    "source",
    RoleAccess.Out("tag"),
    RoleAccess.Master("payload")
  )

  val sinkRole: Role[SinkRole] = Role[SinkRole](
    "sink",
    RoleAccess.In("tag"),
    RoleAccess.Slave("payload")
  )

sealed trait LegacyLink extends Interface

object LegacyLink:
  val definition: InterfaceType[LegacyLink] = Interface[LegacyLink](
    "LegacyLink",
    InterfaceMember.valid("word", UInt(16))
  )

  val sourceRole: Role[SourceRole] = Role[SourceRole](
    "source",
    RoleAccess.Master("word")
  )

  val sinkRole: Role[SinkRole] = Role[SinkRole](
    "sink",
    RoleAccess.Slave("word")
  )

final case class ExternalRequest(data: Expr[UInt], tag: Expr[UInt])
final case class ExternalResponse(data: Expr[UInt], tag: Expr[UInt])

final class ReusableV03Pipeline extends Module:
  val domain = ClockDomain.required("external-v03")
  val width = param(32.integer)
  val input = in(UInt(width))
  val tag = in(UInt(8))
  val output = out(UInt(width))

  val sourcePort = interfacePort(
    UnifiedLink.definition,
    UnifiedLink.sourceRole,
    "source",
    domain
  )
  val sinkPort = interfacePort(
    UnifiedLink.definition,
    UnifiedLink.sinkRole,
    "sink",
    domain
  )
  sourcePort.connectExact(sinkPort)

  val scheduled = pipe(
    Txn(ExternalRequest(input, tag)),
    PipelinePolicy(latency = Latency.Exact(2))
  ): current =>
    ExternalResponse(stage(current.data + 1.U(width)), current.tag)

  output := scheduled.value.data
  ExternalEvidence.consume(scheduled.value.tag, sourcePort.monitorView)

/** v0.3 adaptation is a visible module boundary rather than an implicit view conversion. */
final class ExplicitV03Adapter extends Module:
  val domain = ClockDomain.required("external-adapter")
  val legacy = interfacePort(
    LegacyLink.definition,
    LegacyLink.sinkRole,
    "legacy",
    domain
  )
  val modern = interfacePort(
    UnifiedLink.definition,
    UnifiedLink.sourceRole,
    "modern",
    domain
  )
  ExternalEvidence.consume(legacy, modern)

object ExternalEvidence:
  def consume(values: Any*): Unit = values.foreach(_ => ())
