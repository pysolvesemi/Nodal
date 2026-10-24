package nodal_external
import nodal.*

final class SelectedParentArithmetic extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val parentFirst = param(5.0.real)
  val parentSecond = param(5.0.real)
  val shared = parentFirst + parentSecond
  val amplified = shared * 2.0.real
  val firstGain = amplified - (-parentFirst)
  val secondGain = amplified - (-parentSecond)
  val top = terminal(Electrical, "top")
  val lanes =
    for lane <- hdlRange(2, 8, 2) yield new Area:
      val _ = lane
      val first =
        instance(new SelectedTwoChildConservativeLeaf).domain(core).param(_.gain, firstGain)
      val second =
        instance(new SelectedTwoChildConservativeLeaf).domain(core).param(_.gain, secondGain)
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
