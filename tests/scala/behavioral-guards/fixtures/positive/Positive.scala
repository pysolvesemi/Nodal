import nodal.*
final class BehavioralPositive extends Module:
  val MODE = param(1.integer, 0 to 3)
  val enable = in(Bool)
  val other = in(Bool)
  val input = in(UInt(8))
  val state = Reg(0.U(8))
  when(enable) { state := input }
  elsewhen(MODE == 0) { state := input }
  otherwise { state := 0.U(8) }
  when((MODE == 0) && enable) { state := input }
  when(enable && (MODE != 1)) { state := input }
  when((MODE > 0) || enable) { state := input }
  when(enable || (MODE <= 1)) { state := input }
  when(!((MODE >= 1) && (enable || other))) { state := input }
  when((MODE == 0.integer) && enable) { state := input }
  when(MODE == 0) {
    val selected = input + 1.U(8)
    when(enable) { state := selected }
    elsewhen(other) { state := input }
    otherwise { state := 0.U(8) }
  }
  when(enable && other) { () }
  when(enable) {
    val selected: Expr[UInt] = if MODE == 0 then input else 0.U(8)
    state := selected
  }
  when(condition = enable)(body = { state := input })
  elsewhen(condition = other)(body = { state := 0.U(8) })
  otherwise(body = { state := 1.U(8) })
  val hostBoolean = true
  if hostBoolean then println("ordinary host if remains ordinary")
object SameName:
  def when(condition: Boolean)(body: => Unit): Unit = if condition then body
  def test(): Unit = when(true) { println("unrelated symbol") }
