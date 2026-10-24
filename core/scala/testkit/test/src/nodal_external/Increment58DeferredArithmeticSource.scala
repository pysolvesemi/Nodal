package nodal_external
import nodal.*
final class Increment58DeferredArithmeticLeaf extends Module:
  val required = ClockDomain.required("required")
  val width = param(4.integer, range = 2 to 8)
  val input = in(UInt(width))
  val output = out(UInt(width))
  output := input
final class Increment58DeferredArithmeticSource(
    late: Boolean = true,
    mode: String = "valid",
    concrete: Boolean = false
) extends Module:
  val clock = ClockDomain.external(
    "clock",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val width = if concrete then 4.integer else param(4.integer, range = 2 to 8)
  val input = in(UInt(width))
  val operand = in(UInt(width))
  val sum = out(if mode == "wrong-width" then UInt(width) else UInt(width + 1.integer))
  val product = out(UInt(width + width))
  val negative = out(SInt(width + 1.integer))
  val concatenated = out(Bits(width + width))
  val extracted = out(Bits(2))
  val compared = out(Bool)
  val divided = out(UInt(width))
  val chained = out(UInt((width + 1.integer) + width))
  val child = instance(new Increment58DeferredArithmeticLeaf).domain(clock)
  if !late then child.param(_.width, width)
  val selected = child(_.output)
  sum := selected + operand
  product := selected * operand
  negative := -selected
  concatenated := selected.concat(operand)
  extracted := selected.extract(0, if mode == "extract" then 3 else 2)
  if !concrete then
    val shifted = out(UInt(width + 2.integer))
    shifted := selected << 2
  compared := selected < operand
  divided :=
    (if concrete then selected
     else
       selected / (if mode == "zero" then 0.U(1) else if mode == "runtime" then input else 1.U(1)))
  chained := (selected + operand) * operand
  child(_.input) := input
  if late then child.param(_.width, width)
