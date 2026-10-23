package nodal_external
import nodal.*
final class SelectedLiteralAlternate extends Module:
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
        11.0.real
      ).param(_.offset, 13.0.real)
      private val a = first(_.endpoint)
      a.connectTerminal(SelectedChildConservativeFields.p).connectTo(top.connectView)
      a.connectTerminal(
        SelectedChildConservativeFields.n
      ).connectTo(a.connectTerminal(SelectedChildConservativeFields.p))
  private val childA = lanes(1).first
  private val selectedA = childA(_.endpoint)
  selectedA.connectTerminal(SelectedChildConservativeFields.p).connectTo(top.connectView)

final class SelectedLiteralDefault extends Module:
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
      val first = instance(new SelectedChildConservativeLeaf).domain(core)
      private val a = first(_.endpoint)
      a.connectTerminal(SelectedChildConservativeFields.p).connectTo(top.connectView)
      a.connectTerminal(
        SelectedChildConservativeFields.n
      ).connectTo(a.connectTerminal(SelectedChildConservativeFields.p))
  private val childA = lanes(1).first
  private val selectedA = childA(_.endpoint)
  selectedA.connectTerminal(SelectedChildConservativeFields.p).connectTo(top.connectView)

final class SelectedLiteralParentReference extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val parentGain = param(5.0.real)
  val top = terminal(Electrical, "top")
  val lanes =
    for lane <- hdlRange(2, 8, 2) yield new Area:
      val _ = lane
      val first = instance(new SelectedChildConservativeLeaf).domain(core).param(
        _.gain,
        parentGain
      ).param(_.offset, 7.0.real)
      private val a = first(_.endpoint)
      a.connectTerminal(SelectedChildConservativeFields.p).connectTo(top.connectView)
      a.connectTerminal(
        SelectedChildConservativeFields.n
      ).connectTo(a.connectTerminal(SelectedChildConservativeFields.p))
  private val childA = lanes(1).first
  private val selectedA = childA(_.endpoint)
  selectedA.connectTerminal(SelectedChildConservativeFields.p).connectTo(top.connectView)

final class SelectedLiteralExpressionActual extends Module:
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
        (1.0.real + 2.0.real)
      ).param(_.offset, 7.0.real)
      private val a = first(_.endpoint)
      a.connectTerminal(SelectedChildConservativeFields.p).connectTo(top.connectView)
      a.connectTerminal(
        SelectedChildConservativeFields.n
      ).connectTo(a.connectTerminal(SelectedChildConservativeFields.p))
  private val childA = lanes(1).first
  private val selectedA = childA(_.endpoint)
  selectedA.connectTerminal(SelectedChildConservativeFields.p).connectTo(top.connectView)

final class SelectedLiteralDuplicate extends Module:
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
      ).param(_.gain, 4.0.real).param(_.offset, 7.0.real)
      private val a = first(_.endpoint)
      a.connectTerminal(SelectedChildConservativeFields.p).connectTo(top.connectView)
      a.connectTerminal(
        SelectedChildConservativeFields.n
      ).connectTo(a.connectTerminal(SelectedChildConservativeFields.p))
  private val childA = lanes(1).first
  private val selectedA = childA(_.endpoint)
  selectedA.connectTerminal(SelectedChildConservativeFields.p).connectTo(top.connectView)

final class SelectedLiteralForeign extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val foreign = new SelectedChildConservativeLeaf
  val top = terminal(Electrical, "top")
  val lanes =
    for lane <- hdlRange(2, 8, 2) yield new Area:
      val _ = lane
      val first = instance(new SelectedChildConservativeLeaf).domain(core).param(
        _ => foreign.gain,
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

final class SelectedLeafExpressionDefault extends Module:
  val gain = param((1.0.real + 1.0.real))
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

final class SelectedLiteralExpressionDefault extends Module:
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
      val first = instance(new SelectedLeafExpressionDefault).domain(core).param(
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

final class SelectedLeafUnitDefault extends Module:
  val gain = param(2.0.V)
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

final class SelectedLiteralUnitDefault extends Module:
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
      val first = instance(new SelectedLeafUnitDefault).domain(core).param(
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
