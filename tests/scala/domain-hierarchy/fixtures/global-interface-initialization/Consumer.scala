package nodal_external
import nodal.*
final class GlobalInitializationSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      private val schema = GlobalInitializationSchemas.eager
      val endpoint = interfacePort(schema, GlobalInitializationSchemas.producer, "endpoint", core)
      endpoint.driveMember("data", 0.U(8))
      val _ = i
final class GlobalInitializationFactorySource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      private val schema = GlobalInitializationSchemas.make(i + 1.integer)
      val endpoint = interfacePort(schema, GlobalInitializationSchemas.producer, "endpoint", core)
      endpoint.driveMember("data", 0.U(i + 1.integer))
final class GlobalInitializationLocalObjectSource extends Module:
  val WIDTH = param(2.integer, range = 1 to 8)
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  object Local:
    val schema = GlobalInitializationSchemas.make(WIDTH)
  val endpoint = interfacePort(Local.schema, GlobalInitializationSchemas.producer, "endpoint", core)
  endpoint.driveMember("data", 0.U(WIDTH))
final class GlobalInitializationTwoRegionsSource extends Module:
  val first =
    for i <- hdlRange(0, 2) yield new Area:
      val value = wire(UInt(i + 1.integer))
      value := 0.U(i + 1.integer)
  val second =
    for i <- hdlRange(0, 2) yield new Area:
      val value = wire(UInt(i + 1.integer))
      value := 0.U(i + 1.integer)
final class GlobalInitializationForeignSource(schema: InterfaceType[GlobalInitializationLink])
    extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val endpoint = interfacePort(schema, GlobalInitializationSchemas.producer, "endpoint", core)
final class GlobalInitializationForeignTypeSource(original: InterfaceType[GlobalInitializationLink])
    extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  private val schema = Interface[GlobalInitializationLink]("CopiedForeign", original.members*)
    .withRoles(GlobalInitializationSchemas.producer)
  val endpoint = interfacePort(schema, GlobalInitializationSchemas.producer, "endpoint", core)
