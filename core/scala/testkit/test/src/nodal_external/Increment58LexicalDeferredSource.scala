package nodal_external
import nodal.*
final class Increment58LexicalDeferredSource(mode: String, late: Boolean, wrong: Boolean = false) extends Module:
  val width = param(4.integer, range = 2 to 8)
  val otherWidth = param(4.integer, range = 2 to 8)
  val targetWidth = if wrong then otherWidth else width
  val choice = param(0.integer, range = 0 to 1)
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val input = in(UInt(width))
  val enable = in(Bool)
  val output = out(UInt(targetWidth))
  val child = instance(new Increment58DeferredArithmeticLeaf).domain(clock)
  if !late then child.param(_.width, width)
  val selected = child(_.output)
  clock:
    mode match
      case "when" =>
        val stored = Reg.uninitialized(UInt(targetWidth))
        when(enable):
          stored := ~selected
        output := stored
      case "structural" =>
        if choice == 0 then output := ~selected
        else output := selected
      case "family" =>
        val lanes = for i <- hdlRange(0, 2) yield new Area:
          val stored = Reg.uninitialized(UInt(targetWidth))
          val _ = i
          when(enable):
            stored := ~selected
        output := lanes(0).stored
      case "join" =>
        val joined = if choice == 0 then ~selected else selected
        output := joined
      case _ => output := ~selected
  child(_.input) := input
  if late then child.param(_.width, width)

final class Increment58LexicalDeferredForgerySource extends Module:
  val width = param(4.integer, range = 2 to 8)
  val otherWidth = param(4.integer, range = 2 to 8)
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val input = in(UInt(width))
  val otherInput = in(UInt(otherWidth))
  val enable = in(Bool)
  val output = out(UInt(width))
  val child = instance(new Increment58DeferredArithmeticLeaf).domain(clock)
  val otherChild = instance(new Increment58DeferredArithmeticLeaf).domain(clock)
  val selected = child(_.output)
  val unrelated = otherChild(_.output)
  clock:
    val stored = Reg.uninitialized(UInt(width))
    when(enable):
      stored := ~selected
    output := stored
  child(_.input) := input
  otherChild(_.input) := otherInput
  child.param(_.width, width)
  otherChild.param(_.width, otherWidth)

final class Increment58LexicalDeferredForbiddenSource(mode: String) extends Module:
  val width = param(4.integer, range = 2 to 8)
  val clock = ClockDomain.external("clock", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz)
  val input = in(UInt(width))
  val vector = in(Vec(UInt(width), 2))
  val output = out(UInt(width))
  val child = instance(new Increment58DeferredArithmeticLeaf).domain(clock)
  val selected = child(_.output)
  mode match
    case "collection" =>
      val mapped = vector.map(_ => ~selected)
      output := mapped.at(0)
    case _ =>
      loop(LoopBound.Static(2)): _ =>
        output := ~selected
  child(_.input) := input
  child.param(_.width, width)
