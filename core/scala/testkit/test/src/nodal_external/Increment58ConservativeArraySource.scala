package nodal_external

import nodal.*

sealed trait ConservativeArrayPair extends Interface
sealed trait ConservativeArrayOuter extends Interface
object ConservativeArrayFields:
  val p = InterfaceMember.terminal("p", Electrical)
  val n = InterfaceMember.terminal("n", Electrical)
  val connect = Role[SourceRole]("connect", RoleAccess.Connect("p"), RoleAccess.Connect("n"))
  val sense = Role[SourceRole]("sense", RoleAccess.Sense("p"), RoleAccess.Sense("n"))
  val pair = Interface[ConservativeArrayPair]("ConservativeArrayPair", p, n).withRoles(connect, sense)
  val nested = InterfaceMember.nested("pair", pair)
  val connected = Role[SourceRole]("connected", RoleAccess.Nested("pair", "connect"))
  val sensed = Role[SourceRole]("sensed", RoleAccess.Nested("pair", "sense"))
  val outer = Interface[ConservativeArrayOuter]("ConservativeArrayOuter", nested)
    .withRoles(connected, sensed)

final class ConservativeArrayLeaf(count: Int) extends Module:
  import ConservativeArrayFields.*
  val required = ClockDomain.required("required")
  val pins = interfaceArray(outer, connected, count, "pins", required)

final class ConservativeArrayMiddle(count: Int) extends Module:
  import ConservativeArrayFields.*
  val required = ClockDomain.required("required")
  val pins = interfaceArray(outer, connected, count, "pins", required)
  val leaf = instance(new ConservativeArrayLeaf(count)).domain(required)
  pins.forwardExact(leaf(_.pins))

final class ConservativeArrayTop(count: Int = 3, mode: String = "direct") extends Module:
  import ConservativeArrayFields.*
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None,
    ResetPolarity.ActiveHigh, 100.MHz)
  val boundDomain = if mode == "domain" then
    ClockDomain.external("other", ClockEdge.Rising, ResetPolicy.None,
      ResetPolarity.ActiveHigh, 100.MHz)
  else core
  val pins = interfaceArray(outer, if mode == "role" then sensed else connected,
    count, "pins", core)
  if mode == "two-level" then
    val middle = instance(new ConservativeArrayMiddle(count)).domain(core)
    pins.forwardExact(middle(_.pins))
  else
    val childDefinition = new ConservativeArrayLeaf(if mode == "count" then count + 1 else count)
    val child = instance(childDefinition)
      .domain(boundDomain)
    val projected = child(_.pins)
    require(projected eq child(_.pins))
    mode match
      case "raw" => pins.forwardExact(child(value => Some(value.pins)).get)
      case "reverse" => projected.forwardExact(pins)
      case "foreign-child" =>
        val foreign = instance(new ConservativeArrayLeaf(count)).domain(core)
        pins.forwardExact(foreign(_ => childDefinition.pins))
      case _ => pins.forwardExact(projected)
    if mode == "duplicate" then pins.forwardExact(projected)
    if mode == "two-instances" then
      val secondPins = interfaceArray(outer, connected, count, "secondPins", core)
      val second = instance(new ConservativeArrayLeaf(count)).domain(core)
      secondPins.forwardExact(second(_.pins))

final class ConservativeArraySymbolic extends Module:
  import ConservativeArrayFields.*
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None,
    ResetPolarity.ActiveHigh, 100.MHz)
  val count = param(3.integer, range = 1 to 4)
  val pins = interfaceArray(outer, connected, count, "pins", core)
  val child = instance(new ConservativeArrayLeaf(3)).domain(core)
  pins.forwardExact(child(_.pins))

final class ConservativeArrayGenerated extends Module:
  import ConservativeArrayFields.*
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None,
    ResetPolarity.ActiveHigh, 100.MHz)
  val family = for lane <- hdlRange(0, 2) yield new Area:
    val _ = lane
    val pins = interfaceArray(outer, connected, 3, "pins", core)
    val child = instance(new ConservativeArrayLeaf(3)).domain(core)
    pins.forwardExact(child(_.pins))
