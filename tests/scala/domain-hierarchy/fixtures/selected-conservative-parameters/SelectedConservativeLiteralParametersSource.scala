package nodal_external
import nodal.*

sealed trait SelectedChildConservativePins extends Interface
object SelectedChildConservativeFields:
  val p = InterfaceMember.terminal("p", Electrical)
  val n = InterfaceMember.terminal("n", Electrical)
  val connected = Role[SourceRole]("connected", RoleAccess.Connect("p"), RoleAccess.Connect("n"))
  val pins = Interface[SelectedChildConservativePins]("SelectedChildConservativePins", p, n)
    .withRoles(connected)

final class SelectedChildConservativeLeaf extends Module:
  val gain = param(2.0.real)
  val offset = param(1.0.real)
  private val observed = node(Electrical)
  private val reference = node(Electrical)
  analog:
    V(observed, reference) <+ (gain + offset) * 1.0.V
  val required = ClockDomain.required("required")
  val endpoint = interfacePort(
    SelectedChildConservativeFields.pins,
    SelectedChildConservativeFields.connected,
    "pins",
    required
  )

final class SelectedLiteralParameters extends Module:
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
      val first = instance(new SelectedChildConservativeLeaf).domain(core).param(
        _.gain,
        3.0.real
      ).param(_.offset, 7.0.real)
      private val a = first(_.endpoint)
      a.connectTerminal(SelectedChildConservativeFields.p).connectTo(top.connectView)
      a.connectTerminal(
        SelectedChildConservativeFields.n
      ).connectTo(a.connectTerminal(SelectedChildConservativeFields.p))
  private val childA = lanes(1).first
  private val selectedA = childA(_.endpoint)
  selectedA.connectTerminal(SelectedChildConservativeFields.p).connectTo(top.connectView)
