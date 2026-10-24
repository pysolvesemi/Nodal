package nodal_external
import nodal.*

sealed trait SelectedChildConservativePins extends Interface
object SelectedChildConservativeFields:
  val p = InterfaceMember.terminal("p", Electrical)
  val n = InterfaceMember.terminal("n", Electrical)
  val foreign = InterfaceMember.terminal("p", Electrical)
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


final class SelectedChildConservativeParameterLeaf extends Module:
  val ignored = param(1.integer, range = 1 to 3)
  val required = ClockDomain.required("required")
  val endpoint = interfacePort(
    SelectedChildConservativeFields.pins,
    SelectedChildConservativeFields.connected,
    "pins",
    required
  )


final class SelectedChildConservativeNonunit extends Module:
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val top = terminal(Electrical, "top")
  val lanes = for lane <- hdlRange(2, 8, 2) yield new Area:
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

final class SelectedChildConservativeUnusedLeaf extends Module:
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val top = terminal(Electrical, "top")
  val lanes = for lane <- hdlRange(0, 3) yield new Area:
    val _ = lane
    val child = instance(new SelectedChildConservativeLeaf).domain(core)
    private val bodyEndpoint = child(_.endpoint)
    private val positive = bodyEndpoint.connectTerminal(SelectedChildConservativeFields.p)
    positive.connectTo(top.connectView)
  val selectedChild = lanes(1).child
  val selected = selectedChild(_.endpoint)
  private val positive = selected.connectTerminal(SelectedChildConservativeFields.p)
  positive.connectTo(top.connectView)
  private val aliasChild = lanes(1).child
  private val alias = aliasChild(_.endpoint)
  private val repeated = alias.connectTerminal(SelectedChildConservativeFields.p)
  repeated.connectTo(top.connectView)

final class SelectedChildConservativeBoundaryOnly extends Module:
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val top = terminal(Electrical, "top")
  val lanes = for lane <- hdlRange(0, 3) yield new Area:
    val _ = lane
    val child = instance(new SelectedChildConservativeLeaf).domain(core)
    private val bodyEndpoint = child(_.endpoint)
    val _ = bodyEndpoint
  val selectedChild = lanes(1).child
  val selected = selectedChild(_.endpoint)
  private val positive = selected.connectTerminal(SelectedChildConservativeFields.p)
  positive.connectTo(top.connectView)
  private val aliasChild = lanes(1).child
  private val alias = aliasChild(_.endpoint)
  private val repeated = alias.connectTerminal(SelectedChildConservativeFields.p)
  repeated.connectTo(top.connectView)

final class SelectedChildConservativeMissingBody extends Module:
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val top = terminal(Electrical, "top")
  val lanes = for lane <- hdlRange(0, 3) yield new Area:
    val _ = lane
    val child = instance(new SelectedChildConservativeLeaf).domain(core)
  val selectedChild = lanes(1).child
  val selected = selectedChild(_.endpoint)
  private val positive = selected.connectTerminal(SelectedChildConservativeFields.p)
  positive.connectTo(top.connectView)
  private val aliasChild = lanes(1).child
  private val alias = aliasChild(_.endpoint)
  private val repeated = alias.connectTerminal(SelectedChildConservativeFields.p)
  repeated.connectTo(top.connectView)

final class SelectedChildConservativeChildParameter extends Module:
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val top = terminal(Electrical, "top")
  val lanes = for lane <- hdlRange(0, 3) yield new Area:
    val _ = lane
    val child = instance(new SelectedChildConservativeParameterLeaf).domain(core)
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

final class SelectedChildConservativeParameterFamily extends Module:
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val top = terminal(Electrical, "top")
  val count = param(3.integer, range = 3 to 3)
  val lanes = for lane <- hdlRange(0, count) yield new Area:
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

final class SelectedChildConservativeEmpty extends Module:
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val top = terminal(Electrical, "top")
  val lanes = for lane <- hdlRange(0, 0) yield new Area:
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

final class SelectedChildConservativeOrdinal extends Module:
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
  val selectedChild = lanes(3).child
  val selected = selectedChild(_.endpoint)
  private val positive = selected.connectTerminal(SelectedChildConservativeFields.p)
  positive.connectTo(top.connectView)
  private val aliasChild = lanes(3).child
  private val alias = aliasChild(_.endpoint)
  private val repeated = alias.connectTerminal(SelectedChildConservativeFields.p)
  repeated.connectTo(top.connectView)

final class SelectedChildConservativeOverflow extends Module:
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val top = terminal(Electrical, "top")
  val lanes = for lane <- hdlRange(Int.MinValue, Int.MaxValue) yield new Area:
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

final class SelectedChildConservativeForeignField extends Module:
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
  private val positive = selected.connectTerminal(SelectedChildConservativeFields.foreign)
  positive.connectTo(top.connectView)
  private val aliasChild = lanes(1).child
  private val alias = aliasChild(_.endpoint)
  private val repeated = alias.connectTerminal(SelectedChildConservativeFields.p)
  repeated.connectTo(top.connectView)

final class SelectedChildConservativeSense extends Module:
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
  private val observed = selected.senseTerminal(SelectedChildConservativeFields.p)
  val _ = observed

final class SelectedChildConservativeContribute extends Module:
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
  private val observed = selected.contributeTerminal(SelectedChildConservativeFields.p)
  val _ = observed

final class SelectedChildConservativeTwoViews extends Module:
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
  private val aliasChild = lanes(2).child
  private val alias = aliasChild(_.endpoint)
  private val repeated = alias.connectTerminal(SelectedChildConservativeFields.p)
  repeated.connectTo(top.connectView)

final class SelectedChildConservativeForeignSession(endpoint: InterfacePort[SelectedChildConservativePins, SourceRole]) extends Module:
  val held = endpoint.connectTerminal(SelectedChildConservativeFields.p)
