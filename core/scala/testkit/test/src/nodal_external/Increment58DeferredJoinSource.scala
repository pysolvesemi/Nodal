package nodal_external
import nodal.*
final class Increment58DeferredJoinSource(late: Boolean, concrete: Boolean, mode: String)
    extends Module:
  val width = if concrete then 4.integer else param(4.integer, range = 2 to 8)
  val otherWidth = if concrete then 5.integer else param(4.integer, range = 2 to 8)
  val choice = param(0.integer, range = 0 to 1)
  val alternate = param(0.integer, range = 0 to 1)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(UInt(width))
  val otherInput = in(UInt(otherWidth))
  val enable = in(Bool)
  val output = out(UInt(width))
  val child = instance(new Increment58DeferredArithmeticLeaf).domain(clock)
  val other = instance(new Increment58DeferredArithmeticLeaf).domain(clock)
  if !late then
    child.param(_.width, width)
    other.param(_.width, otherWidth)
  val selected = child(_.output)
  val unrelated = other(_.output)
  clock:
    mode match
      case "mismatch" =>
        val joined = if choice == 0 then ~selected else unrelated
        output := joined
      case "nested" =>
        val joined = if choice == 0 then
          if alternate == 0 then ~selected else selected
        else selected
        output := joined
      case "when" =>
        val stored = Reg.uninitialized(UInt(width))
        when(enable):
          val joined = if choice == 0 then ~selected else selected
          stored := joined
        output := stored
      case "family" =>
        val lanes =
          for i <- hdlRange(0, 2) yield new Area:
            val stored = Reg.uninitialized(UInt(width))
            val _ = i
            when(enable):
              val joined = if choice == 0 then ~selected else selected
              stored := joined
        output := lanes(0).stored
      case _ =>
        val joined = if choice == 0 then ~selected else selected
        output := joined
  child(_.input) := input
  other(_.input) := otherInput
  if late then
    child.param(_.width, width)
    other.param(_.width, otherWidth)

final class Increment58DeferredJoinForbidden(mode: String) extends Module:
  val width = param(4.integer, range = 2 to 8)
  val choice = param(0.integer, range = 0 to 1)
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(UInt(width))
  val vector = in(Vec(UInt(width), 2))
  val output = out(UInt(width))
  val child = instance(new Increment58DeferredArithmeticLeaf).domain(clock)
  val selected = child(_.output)
  mode match
    case "collection" =>
      val mapped = vector.map: _ =>
        if choice == 0 then ~selected else selected
      output := mapped.at(0)
    case _ =>
      loop(LoopBound.Static(2)): _ =>
        val joined = if choice == 0 then ~selected else selected
        output := joined
  child(_.input) := input
  child.param(_.width, width)
