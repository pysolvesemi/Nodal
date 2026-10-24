package nodal_external
import nodal.*
sealed trait GeneratedConservativePins extends Interface
object GeneratedConservativeFields:
  val p = InterfaceMember.terminal("p", Electrical)
  val n = InterfaceMember.terminal("n", Electrical)
  val connected = Role[SourceRole]("connected", RoleAccess.Connect("p"), RoleAccess.Connect("n"))
  val pins = Interface[GeneratedConservativePins]("GeneratedConservativePins", p, n).withRoles(connected)
final class GeneratedConservativeLocal extends Module:
  import GeneratedConservativeFields.*
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
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
final class GeneratedConservativeChild extends Module:
  import GeneratedConservativeFields.*
  val required = ClockDomain.required("required")
  val endpoint = interfacePort(pins, connected, "pins", required)
final class GeneratedConservativeHierarchy extends Module:
  import GeneratedConservativeFields.*
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val top = terminal(Electrical, "top")
  val lanes =
    for _lane <- hdlRange(0, 3) yield new Area:
      val _ = _lane
      val child = instance(new GeneratedConservativeChild).domain(core)
      private val endpoint = child(_.endpoint)
      private val positive = endpoint.connectTerminal(p)
      private val negative = endpoint.connectTerminal(n)
      positive.connectTo(top.connectView)
      negative.connectTo(positive)
