package nodal_external
import nodal.*

sealed trait SelectedTwoChildConservativePins extends Interface
object SelectedTwoChildConservativeFields:
  val p = InterfaceMember.terminal("p", Electrical)
  val n = InterfaceMember.terminal("n", Electrical)
  val connected = Role[SourceRole]("connected", RoleAccess.Connect("p"), RoleAccess.Connect("n"))
  val pins = Interface[SelectedTwoChildConservativePins]("SelectedTwoChildConservativePins", p, n)
    .withRoles(connected)

final class SelectedTwoChildConservativeLeaf extends Module:
  val gain = param(2.0.real)
  private val observed = node(Electrical)
  private val reference = node(Electrical)
  analog:
    V(observed, reference) <+ gain * 1.0.V
  val required = ClockDomain.required("required")
  val endpoint = interfacePort(
    SelectedTwoChildConservativeFields.pins,
    SelectedTwoChildConservativeFields.connected,
    "pins",
    required
  )

final class SelectedTwoLiteralParameters extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val top = terminal(Electrical, "top")
  val lanes =
    for lane <- hdlRange(2, 8, 2) yield new Area:
      val _ = lane
      val first =
        instance(new SelectedTwoChildConservativeLeaf).domain(core).param(_.gain, 3.0.real)
      val second =
        instance(new SelectedTwoChildConservativeLeaf).domain(core).param(_.gain, 7.0.real)
      private val a = first(_.endpoint)
      private val b = second(_.endpoint)
      a.connectTerminal(SelectedTwoChildConservativeFields.p).connectTo(top.connectView)
      a.connectTerminal(SelectedTwoChildConservativeFields.n).connectTo(top.connectView)
      b.connectTerminal(SelectedTwoChildConservativeFields.p).connectTo(top.connectView)
      b.connectTerminal(SelectedTwoChildConservativeFields.n).connectTo(top.connectView)
  private val childA = lanes(1).first
  private val selectedA = childA(_.endpoint)
  private val childB = lanes(1).second
  private val selectedB = childB(_.endpoint)
  selectedA.connectTerminal(SelectedTwoChildConservativeFields.p).connectTo(top.connectView)
  selectedB.connectTerminal(SelectedTwoChildConservativeFields.p).connectTo(top.connectView)
