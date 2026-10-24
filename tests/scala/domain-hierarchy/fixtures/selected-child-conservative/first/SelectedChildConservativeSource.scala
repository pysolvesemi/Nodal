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
  val required = ClockDomain.required("required")
  val endpoint = interfacePort(
    SelectedChildConservativeFields.pins,
    SelectedChildConservativeFields.connected,
    "pins",
    required
  )

/** Proposed authentic source witness. Not yet compiled or captured. */
final class SelectedChildConservativeSource extends Module:
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val top = terminal(Electrical, "top")
  val lanes = for lane <- hdlRange(0, 3) yield new Area:
    val _ = lane
    val child = instance(new SelectedChildConservativeLeaf).domain(core)
    private val bodyEndpoint = child(_.endpoint)
    private val positive = bodyEndpoint.connectTerminal(SelectedChildConservativeFields.p)
    private val negative = bodyEndpoint.connectTerminal(SelectedChildConservativeFields.n)
    positive.connectTo(top.connectView)
    negative.connectTo(positive)
  val selectedChild = lanes(1).child
  val selected = selectedChild(_.endpoint)
  private val positive = selected.connectTerminal(SelectedChildConservativeFields.p)
  positive.connectTo(top.connectView)
  private val aliasChild = lanes(1).child
  private val alias = aliasChild(_.endpoint)
  private val repeated = alias.connectTerminal(SelectedChildConservativeFields.p)
  repeated.connectTo(top.connectView)
