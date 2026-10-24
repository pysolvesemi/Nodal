package nodal_external
import nodal.*

/** Separate shared-anchor routing witness, not distinct-occurrence topology evidence. */
final class NestedConservativeAnchorSource extends Module:
  val core = ClockDomain.external("core", ClockEdge.Rising, ResetPolicy.None,
    ResetPolarity.ActiveHigh, 100.MHz)
  val top = terminal(Electrical, "top")
  val rows =
    for row <- hdlRange(0, 2) yield new Area:
      val _ = row
      val columns =
        for column <- hdlRange(0, 3) yield new Area:
          val _ = column
          val child = instance(new NestedConservativeLeaf).domain(core).param(_.gain, 3.0.real)
          private val body = child(_.endpoint)
          body.connectTerminal(NestedConservativeFields.p).connectTo(top.connectView)
          body.connectTerminal(NestedConservativeFields.n).connectTo(top.connectView)
  private val chosen = rows(0).columns(0).child
  private val chosenAgain = rows(0).columns(0).child
  val selected = chosen(_.endpoint)
  val selectedAgain = chosenAgain(_.endpoint)
  selected.connectTerminal(NestedConservativeFields.p).connectTo(top.connectView)
  selectedAgain.connectTerminal(NestedConservativeFields.p).connectTo(top.connectView)
  assert(selected eq selectedAgain)
