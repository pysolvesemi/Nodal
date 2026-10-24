package nodal_external
import nodal.*
sealed trait GeneratedConservativeNestedPins extends Interface
object GeneratedConservativeControlFields:
  import GeneratedConservativeFields.*
  val observed = Role[SourceRole]("observed", RoleAccess.Sense("p"), RoleAccess.Sense("n"))
  val full = Interface[GeneratedConservativePins]("ControlPins", p, n).withRoles(connected, observed)
  val copied = p.copy()
  val nested = InterfaceMember.nested("pair", pins)
  val outerRole = Role[SourceRole]("outer", RoleAccess.Nested("pair", "connected"))
  val outer = Interface[GeneratedConservativeNestedPins]("OuterPins", nested).withRoles(outerRole)
  val mixedRole = Role[SourceRole]("mixed", RoleAccess.Connect("p"), RoleAccess.Out("data"))
  val mixed = Interface[GeneratedConservativePins]("MixedPins", p, InterfaceMember.value("data", UInt(8))).withRoles(mixedRole)
final class GeneratedConservativeInvalid(mode: String) extends Module:
  import GeneratedConservativeFields.*
  import GeneratedConservativeControlFields.*
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val chosenType = if mode == "mixed" then mixed else full
  val chosenRole = if mode == "role" then observed else if mode == "mixed" then mixedRole else connected
  val chosenField = if mode == "field" then copied else p
  val lanes =
    for lane <- hdlRange(0, 2) yield new Area:
      val _ = lane
      val endpoint = interfacePort(chosenType, chosenRole, "pins", core)
      val _ = endpoint.connectTerminal(chosenField)
final class GeneratedConservativeAncestor extends Module:
  import GeneratedConservativeFields.*
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val endpoint = interfacePort(pins, connected, "pins", core)
  val lanes =
    for lane <- hdlRange(0, 2) yield new Area:
      val _ = lane
      val _ = endpoint.connectTerminal(p)
final class GeneratedConservativeNestedMember extends Module:
  import GeneratedConservativeFields.*
  import GeneratedConservativeControlFields.*
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
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
final class GeneratedConservativeNestedFamily extends Module:
  import GeneratedConservativeFields.*
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val rows =
    for row <- hdlRange(0, 2) yield new Area:
      val _ = row
      val lanes =
        for lane <- hdlRange(0, 2) yield new Area:
          val _ = lane
          val endpoint = interfacePort(pins, connected, "pins", core)
          val _ = endpoint.connectTerminal(p)
final class GeneratedConservativeSelected extends Module:
  import GeneratedConservativeFields.*
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val lanes =
    for lane <- hdlRange(0, 2) yield new Area:
      val _ = lane
      val endpoint = interfacePort(pins, connected, "pins", core)
  val selected = lanes(0.integer).endpoint
  val value = selected.connectTerminal(p)

final class GeneratedConservativeCount0 extends Module:
  import GeneratedConservativeFields.*
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val top = terminal(Electrical, "top")
  val lanes =
    for _lane <- hdlRange(0, 0) yield new Area:
      val _ = _lane
      val endpoint = interfacePort(pins, connected, "pins", core)
      private val positive = endpoint.connectTerminal(p)
      private val negative = endpoint.connectTerminal(n)
      positive.connectTo(top.connectView)
      negative.connectTo(positive)

final class GeneratedConservativeCount1 extends Module:
  import GeneratedConservativeFields.*
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val top = terminal(Electrical, "top")
  val lanes =
    for _lane <- hdlRange(0, 1) yield new Area:
      val _ = _lane
      val endpoint = interfacePort(pins, connected, "pins", core)
      private val positive = endpoint.connectTerminal(p)
      private val negative = endpoint.connectTerminal(n)
      positive.connectTo(top.connectView)
      negative.connectTo(positive)
final class GeneratedConservativeForeign(foreign: Terminal[Electrical.type]) extends Module:
  import GeneratedConservativeFields.*
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val lanes =
    for lane <- hdlRange(0, 2) yield new Area:
      val _ = lane
      val endpoint = interfacePort(pins, connected, "pins", core)
      endpoint.connectTerminal(p).connectTo(foreign.connectView)
final class GeneratedConservativeGuarded extends Module:
  import GeneratedConservativeFields.*
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val count = param(1.integer, range = 0 to 2)
  val result: Expr[UInt] = if count > 0.integer then
    val lanes = for lane <- hdlRange(0, 2) yield new Area:
      val _ = lane
      val endpoint = interfacePort(pins, connected, "pins", core)
      val _ = endpoint.connectTerminal(p)
    val _ = lanes
    0.U(1)
  else 0.U(1)
