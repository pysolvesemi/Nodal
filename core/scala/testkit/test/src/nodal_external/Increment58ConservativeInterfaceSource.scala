package nodal_external
import nodal.*
sealed trait Increment58ConservativePair extends Interface
sealed trait Increment58ConservativeNested extends Interface
object Increment58ConservativeFields:
  val p = InterfaceMember.terminal("p", Electrical)
  val n = InterfaceMember.terminal("n", Electrical)
  val connected = Role[SourceRole]("connected", RoleAccess.Connect("p"), RoleAccess.Connect("n"))
  val contributed =
    Role[SourceRole]("contributed", RoleAccess.Contribute("p"), RoleAccess.Contribute("n"))
  val pair = Interface[Increment58ConservativePair]("Increment58ConservativePair", p, n).withRoles(
    connected,
    contributed
  )
  val nested = InterfaceMember.nested("pair", pair)
  val outerRole = Role[SourceRole]("outer", RoleAccess.Nested("pair", "connected"))
  val outer = Interface[Increment58ConservativeNested](
    "Increment58ConservativeNested",
    nested
  ).withRoles(outerRole)
final class Increment58ConservativeLocal(mode: String = "local") extends Module:
  import Increment58ConservativeFields.*
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val endpoint = interfacePort(pair, contributed, "pins", core)
  val positive = mode match
    case "wrong-field" => endpoint.contributeTerminal(p.copy())
    case "wrong-role" =>
      val _ = endpoint.connectTerminal(p)
      endpoint.contributeTerminal(p)
    case "monitor-connect" =>
      val _ = endpoint.monitorView.connectTerminal(p)
      endpoint.contributeTerminal(p)
    case _ => endpoint.contributeTerminal(p)
  val negative = endpoint.contributeTerminal(n)
  val again = endpoint.contributeTerminal(p)
  analog:
    if mode == "monitor-sense" then
      I(positive, negative) <+ V(endpoint.monitorView.senseTerminal(p), negative) / 2.0.Ohm
    else
      I(positive, negative) <+ V(positive, negative) / 2.0.Ohm
final class Increment58ConservativeLeaf extends Module:
  import Increment58ConservativeFields.*
  val required = ClockDomain.required("required")
  val endpoint = interfacePort(outer, outerRole, "pins", required)
final class Increment58ConservativeHierarchy(mode: String = "nested-child") extends Module:
  import Increment58ConservativeFields.*
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val endpoint = interfacePort(outer, outerRole, "pins", core)
  val child = instance(new Increment58ConservativeLeaf).domain(core)
  val selected =
    if mode == "raw" then child(value => Some(value.endpoint)).get else child(_.endpoint)
  val source = mode match
    case "wrong-nested" => endpoint.connectTerminal(p, nested.copy())
    case "missing-nested" => endpoint.connectTerminal(p)
    case _ => endpoint.connectTerminal(p, nested)
  source.connectTo(selected.connectTerminal(p, nested))
  endpoint.connectTerminal(n, nested).connectTo(selected.connectTerminal(n, nested))

final class Increment58ConservativeSenseContribution(monitor: Boolean = false) extends Module:
  import Increment58ConservativeFields.*
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val endpoint = interfacePort(pair, contributed, "pins", core)
  val positive =
    if monitor then endpoint.monitorView.senseTerminal(p) else endpoint.senseTerminal(p)
  val negative = endpoint.senseTerminal(n)
  analog:
    I(positive, negative) <+ V(positive, negative) / 2.0.Ohm
final class Increment58ConservativeTerminalPredecessor extends Module:
  val p = terminal(Electrical, "p")
  val n = terminal(Electrical, "n")
  val pv = p.contributeView
  val nv = n.contributeView
  val compatible: Terminal[Electrical.type] = pv.terminal
  analog:
    I(pv, nv) <+ V(pv, nv) / 2.0.Ohm
final class Increment58ConservativeNodePredecessor extends Module:
  val p = in(Electrical)
  val n = out(Electrical)
  analog:
    I(p, n) <+ V(p, n) / 2.0.Ohm

final class Increment58ConservativeLiveDigital(mode: String) extends Module:
  import Increment58ConservativeFields.*
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val mixedRole = Role[SourceRole](
    "mixed",
    RoleAccess.Connect("p"),
    RoleAccess.Connect("n"),
    RoleAccess.In("data")
  )
  val mixed = Interface[Increment58ConservativePair](
    "Mixed",
    p,
    n,
    InterfaceMember.value("data", UInt(8))
  ).withRoles(mixedRole)
  val endpoint = if mode == "mixed" then interfacePort(mixed, mixedRole, "pins", core)
  else interfacePort(pair, connected, "pins", core)
  if mode == "state" then
    core:
      val stored = Reg.uninitialized(UInt(8))
      stored := 1.U(8)
