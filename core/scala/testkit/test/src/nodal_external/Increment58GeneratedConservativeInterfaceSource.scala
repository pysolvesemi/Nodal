package nodal_external
import nodal.*
sealed trait Increment58GeneratedConservativePins extends Interface
object Increment58GeneratedConservativeFields:
  val p = InterfaceMember.terminal("p", Electrical)
  val n = InterfaceMember.terminal("n", Electrical)
  val connected = Role[SourceRole]("connected", RoleAccess.Connect("p"), RoleAccess.Connect("n"))
  val pins = Interface[Increment58GeneratedConservativePins](
    "Increment58GeneratedConservativePins",
    p,
    n
  ).withRoles(connected)
final class Increment58GeneratedConservativeLocal extends Module:
  import Increment58GeneratedConservativeFields.*
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val count = param(3.integer, range = 0 to 4)
  val top = terminal(Electrical, "top")
  val lanes =
    for _lane <- hdlRange(0, count) yield new Area:
      val _ = _lane
      val endpoint = interfacePort(pins, connected, "pins", core)
      private val positive = endpoint.connectTerminal(p)
      private val negative = endpoint.connectTerminal(n)
      positive.connectTo(top.connectView)
      negative.connectTo(positive)
final class Increment58GeneratedConservativeChild extends Module:
  import Increment58GeneratedConservativeFields.*
  val required = ClockDomain.required("required")
  val endpoint = interfacePort(pins, connected, "pins", required)
final class Increment58GeneratedConservativeHierarchy extends Module:
  import Increment58GeneratedConservativeFields.*
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val top = terminal(Electrical, "top")
  val lanes =
    for _lane <- hdlRange(0, 3) yield new Area:
      val _ = _lane
      val child = instance(new Increment58GeneratedConservativeChild).domain(core)
      private val endpoint = child(_.endpoint)
      private val positive = endpoint.connectTerminal(p)
      private val negative = endpoint.connectTerminal(n)
      positive.connectTo(top.connectView)
      negative.connectTo(positive)

sealed trait Increment58GeneratedConservativeNestedPins extends Interface
object Increment58GeneratedConservativeControlFields:
  import Increment58GeneratedConservativeFields.*
  val observed = Role[SourceRole]("observed", RoleAccess.Sense("p"), RoleAccess.Sense("n"))
  val full = Interface[Increment58GeneratedConservativePins]("ControlPins", p, n).withRoles(
    connected,
    observed
  )
  val copied = p.copy()
  val nested = InterfaceMember.nested("pair", pins)
  val outerRole = Role[SourceRole]("outer", RoleAccess.Nested("pair", "connected"))
  val outer =
    Interface[Increment58GeneratedConservativeNestedPins]("OuterPins", nested).withRoles(outerRole)
  val mixedRole = Role[SourceRole]("mixed", RoleAccess.Connect("p"), RoleAccess.Out("data"))
  val mixed = Interface[Increment58GeneratedConservativePins](
    "MixedPins",
    p,
    InterfaceMember.value("data", UInt(8))
  ).withRoles(mixedRole)
final class Increment58GeneratedConservativeInvalid(mode: String) extends Module:
  import Increment58GeneratedConservativeFields.*
  import Increment58GeneratedConservativeControlFields.*
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val chosenType = if mode == "mixed" then mixed else full
  val chosenRole =
    if mode == "role" then observed else if mode == "mixed" then mixedRole else connected
  val chosenField = if mode == "field" then copied else p
  val lanes =
    for lane <- hdlRange(0, 2) yield new Area:
      val _ = lane
      val endpoint = interfacePort(chosenType, chosenRole, "pins", core)
      val _ = endpoint.connectTerminal(chosenField)
final class Increment58GeneratedConservativeAncestor extends Module:
  import Increment58GeneratedConservativeFields.*
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val endpoint = interfacePort(pins, connected, "pins", core)
  val lanes =
    for lane <- hdlRange(0, 2) yield new Area:
      val _ = lane
      val _ = endpoint.connectTerminal(p)
final class Increment58GeneratedConservativeNestedMember extends Module:
  import Increment58GeneratedConservativeFields.*
  import Increment58GeneratedConservativeControlFields.*
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val top = terminal(Electrical, "top")
  val lanes =
    for lane <- hdlRange(0, 2) yield new Area:
      val _ = lane
      val endpoint = interfacePort(outer, outerRole, "pins", core)
      private val positive = endpoint.connectTerminal(p, nested)
      private val repeated = endpoint.connectTerminal(p, nested)
      private val negative = endpoint.connectTerminal(n, nested)
      positive.connectTo(top.connectView)
      repeated.connectTo(negative)
final class Increment58GeneratedConservativeNestedFamily extends Module:
  import Increment58GeneratedConservativeFields.*
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val rows =
    for row <- hdlRange(0, 2) yield new Area:
      val _ = row
      val lanes =
        for lane <- hdlRange(0, 2) yield new Area:
          val _ = lane
          val endpoint = interfacePort(pins, connected, "pins", core)
          val _ = endpoint.connectTerminal(p)
final class Increment58GeneratedConservativeSelected extends Module:
  import Increment58GeneratedConservativeFields.*
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val lanes =
    for lane <- hdlRange(0, 2) yield new Area:
      val _ = lane
      val endpoint = interfacePort(pins, connected, "pins", core)
  val selected = lanes(0.integer).endpoint
  val value = selected.connectTerminal(p)

final class Increment58GeneratedConservativeCount0 extends Module:
  import Increment58GeneratedConservativeFields.*
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val top = terminal(Electrical, "top")
  val lanes =
    for _lane <- hdlRange(0, 0) yield new Area:
      val _ = _lane
      val endpoint = interfacePort(pins, connected, "pins", core)
      private val positive = endpoint.connectTerminal(p)
      private val negative = endpoint.connectTerminal(n)
      positive.connectTo(top.connectView)
      negative.connectTo(positive)

final class Increment58GeneratedConservativeCount1 extends Module:
  import Increment58GeneratedConservativeFields.*
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val top = terminal(Electrical, "top")
  val lanes =
    for _lane <- hdlRange(0, 1) yield new Area:
      val _ = _lane
      val endpoint = interfacePort(pins, connected, "pins", core)
      private val positive = endpoint.connectTerminal(p)
      private val negative = endpoint.connectTerminal(n)
      positive.connectTo(top.connectView)
      negative.connectTo(positive)
final class Increment58GeneratedConservativeForeign(foreign: Terminal[Electrical.type])
    extends Module:
  import Increment58GeneratedConservativeFields.*
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val lanes =
    for lane <- hdlRange(0, 2) yield new Area:
      val _ = lane
      val endpoint = interfacePort(pins, connected, "pins", core)
      endpoint.connectTerminal(p).connectTo(foreign.connectView)
final class Increment58GeneratedConservativeGuarded extends Module:
  import Increment58GeneratedConservativeFields.*
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val count = param(1.integer, range = 0 to 2)
  val result: Expr[UInt] = if count > 0.integer then
    val lanes =
      for lane <- hdlRange(0, 2) yield new Area:
        val _ = lane
        val endpoint = interfacePort(pins, connected, "pins", core)
        val _ = endpoint.connectTerminal(p)
    val _ = lanes
    0.U(1)
  else 0.U(1)
