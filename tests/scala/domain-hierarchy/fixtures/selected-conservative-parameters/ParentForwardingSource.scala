package nodal_external
import nodal.*

final class SelectedTwoParentReferences extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val parentFirst = param(5.0.real)
  val parentSecond = param(9.0.real)
  val top = terminal(Electrical, "top")
  val lanes =
    for lane <- hdlRange(2, 8, 2) yield new Area:
      val _ = lane
      val first =
        instance(new SelectedTwoChildConservativeLeaf).domain(core).param(_.gain, parentFirst)
      val second =
        instance(new SelectedTwoChildConservativeLeaf).domain(core).param(_.gain, parentSecond)
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

final class SelectedEqualParentReferences extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val parentFirst = param(5.0.real)
  val parentSecond = param(5.0.real)
  val top = terminal(Electrical, "top")
  val lanes =
    for lane <- hdlRange(2, 8, 2) yield new Area:
      val _ = lane
      val first =
        instance(new SelectedTwoChildConservativeLeaf).domain(core).param(_.gain, parentFirst)
      val second =
        instance(new SelectedTwoChildConservativeLeaf).domain(core).param(_.gain, parentSecond)
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

final class SelectedParentExpressionActual extends Module:
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
        (parentGain + 1.0.real)
      ).param(_.offset, 7.0.real)
      private val a = first(_.endpoint)
      a.connectTerminal(SelectedChildConservativeFields.p).connectTo(top.connectView)
      a.connectTerminal(
        SelectedChildConservativeFields.n
      ).connectTo(a.connectTerminal(SelectedChildConservativeFields.p))
  private val childA = lanes(1).first
  private val selectedA = childA(_.endpoint)
  selectedA.connectTerminal(SelectedChildConservativeFields.p).connectTo(top.connectView)

final class SelectedParentForeignActual extends Module:
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
        _.gain,
        foreign.gain
      ).param(_.offset, 7.0.real)
      private val a = first(_.endpoint)
      a.connectTerminal(SelectedChildConservativeFields.p).connectTo(top.connectView)
      a.connectTerminal(
        SelectedChildConservativeFields.n
      ).connectTo(a.connectTerminal(SelectedChildConservativeFields.p))
  private val childA = lanes(1).first
  private val selectedA = childA(_.endpoint)
  selectedA.connectTerminal(SelectedChildConservativeFields.p).connectTo(top.connectView)
