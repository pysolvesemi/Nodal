package nodal_external
import nodal.*

final class SelectedSymbolicFamily(initial: Int = 3, minimum: Int = 2,
    ordinal: Int = 1, lower: Int = 0, symbolicStep: Boolean = false,
    unused: Boolean = false, catchFailure: Boolean = false) extends Module:
  val LANES = param(initial.integer, range = minimum to 5)
  val STEP = param(1.integer, range = 1 to 1)
  val step: Int | Expr[Integer] = if symbolicStep then STEP else 1
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val top = terminal(Electrical, "top")
  val lanes =
    for lane <- hdlRange(lower, LANES, step) yield new Area:
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
  val selection = scala.util.Try {
    val childA = lanes(ordinal).first
    val selectedA = childA(_.endpoint)
    val childB = lanes(ordinal).second
    val selectedB = childB(_.endpoint)
    if !unused then
      selectedA.connectTerminal(SelectedTwoChildConservativeFields.p).connectTo(top.connectView)
      selectedB.connectTerminal(SelectedTwoChildConservativeFields.p).connectTo(top.connectView)
  }
  if catchFailure then
    assert(selection.isFailure)
    val diagnostic = selection.failed.get.toString
    val expected = if minimum < 2 then
      "NODAL-AREA-058-INDEX: generated ordinal existence is not proved in its actual lexical scope"
    else
      "NODAL-INTERFACE-058-ANALOG: selected conservative child requires a fixed family or direct structural Integer extent and a universal literal ordinal"
    assert(diagnostic.contains(expected), diagnostic)
  else selection.get

final class SymbolicNodeArrayPredecessor extends Module:
  val LANES = param(3.integer, range = 2 to 5)
  val top = inout(Electrical)
  val taps = node(Electrical, LANES)
  analog:
    I(taps.at(1), top) <+ (V(taps.at(1), top) / 1.kOhm)
