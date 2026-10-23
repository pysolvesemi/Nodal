package nodal_external
import nodal.*
sealed trait Increment58LexicalLink extends Interface
object Increment58LexicalContract:
  val producer = Role[SourceRole]("producer", RoleAccess.Out("data"))
final class Increment58LexicalInterfaceSource(select: Boolean) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      private val definition = Interface[Increment58LexicalLink](
        "Increment58LexicalLink",
        InterfaceMember.value("data", UInt(i + 1.integer))
      ).withRoles(Increment58LexicalContract.producer)
      val endpoint =
        interfacePort(definition, Increment58LexicalContract.producer, "endpoint", core)
      endpoint.driveMember("data", 0.U(i + 1.integer))
  if select then
    val endpoint = lanes(1).endpoint
    endpoint.observeMember("data")

final class Increment58LexicalNestedInterfaceSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      private val inner = Interface[Increment58LexicalLink](
        "InnerLink",
        InterfaceMember.value("data", Vec(UInt(i + 1.integer), 2))
      ).withRoles(Increment58LexicalContract.producer)
      private val role = Role[SourceRole]("outer", RoleAccess.Nested("nested", "producer"))
      private val definition = Interface[Increment58LexicalLink](
        "OuterLink",
        InterfaceMember.nested("nested", inner)
      ).withRoles(role)
      val endpoint = interfacePort(definition, role, "endpoint", core)
      private val input = wire(Vec(UInt(i + 1.integer), 2))
      input.writable.at(0) := 0.U(i + 1.integer)
      input.writable.at(1) := 0.U(i + 1.integer)
      endpoint.driveMember("nested.data", input)
  val selected = lanes(1).endpoint
  selected.observeMember("nested.data")

final class Increment58LexicalGuardedInterfaceSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      val marker = wire(UInt(1))
      marker := 0.U(1)
      if i >= 0 then
        val definition = Interface[Increment58LexicalLink](
          "ArmLink",
          InterfaceMember.value("data", UInt(i + 1.integer))
        ).withRoles(Increment58LexicalContract.producer)
        val children =
          for j <- hdlRange(0, 2) yield new Area:
            val _ = j
            val endpoint =
              interfacePort(definition, Increment58LexicalContract.producer, "endpoint", core)
            endpoint.driveMember("data", 0.U(i + 1.integer))
        children(0).endpoint.observeMember("data")

final class Increment58InvariantLexicalInterfaceSource(select: Boolean = true) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val width = param(2.integer, range = 1 to 4)
  val definition = Interface[Increment58LexicalLink](
    "InvariantLink",
    InterfaceMember.value("data", UInt(width + 1.integer))
  ).withRoles(Increment58LexicalContract.producer)
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      val _ = i
      val endpoint =
        interfacePort(definition, Increment58LexicalContract.producer, "endpoint", core)
      endpoint.driveMember("data", 0.U(width + 1.integer))
  if select then lanes(1).endpoint.observeMember("data")

object Increment58LexicalBidirectionalContract:
  val producer = Role[SourceRole]("producer", RoleAccess.Out("data"))
  val consumer = Role[SinkRole]("consumer", RoleAccess.In("data"))
final class Increment58SharedLexicalDefinitionSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val lanes =
    for i <- hdlRange(0, 2) yield new Area:
      val _ = i
      private val definition =
        Interface[Increment58LexicalLink]("SharedLink", InterfaceMember.value("data", UInt(8)))
          .withRoles(
            Increment58LexicalBidirectionalContract.producer,
            Increment58LexicalBidirectionalContract.consumer
          )
      val producer = interfacePort(
        definition,
        Increment58LexicalBidirectionalContract.producer,
        "producer",
        core
      )
      val consumer = interfacePort(
        definition,
        Increment58LexicalBidirectionalContract.consumer,
        "consumer",
        core
      )
      producer.driveMember("data", 0.U(8))
      producer.connectExact(consumer)
  lanes(1).consumer.observeMember("data")
final class Increment58SiblingLexicalDefinitionSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val narrow =
    for i <- hdlRange(0, 2) yield new Area:
      val _ = i
      private val definition =
        Interface[Increment58LexicalLink]("SiblingLink", InterfaceMember.value("data", UInt(4)))
          .withRoles(Increment58LexicalContract.producer)
      val endpoint = interfacePort(definition, Increment58LexicalContract.producer, "narrow", core)
      endpoint.driveMember("data", 0.U(4))
  val wide =
    for i <- hdlRange(0, 2) yield new Area:
      val _ = i
      private val definition =
        Interface[Increment58LexicalLink]("SiblingLink", InterfaceMember.value("data", UInt(8)))
          .withRoles(Increment58LexicalContract.producer)
      val endpoint = interfacePort(definition, Increment58LexicalContract.producer, "wide", core)
      endpoint.driveMember("data", 0.U(8))
  narrow(1).endpoint.observeMember("data")
  wide(1).endpoint.observeMember("data")

final class Increment58UnequalLexicalPayloadSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      private val definition = Interface[Increment58LexicalLink](
        "UnequalLink",
        InterfaceMember.value("data", UInt(i + 1.integer))
      )
        .withRoles(Increment58LexicalContract.producer)
      val endpoint =
        interfacePort(definition, Increment58LexicalContract.producer, "endpoint", core)
      endpoint.driveMember("data", 0.U(i + 2.integer))
