package generated_negative
import nodal.*
final class ReadOnlyCast extends Module:
  val enable = in(Bool)
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      val stored = Reg(false.B)
      val readonly: Expr[Bool] = stored
      val _ = i
  when(enable):
    lanes(0).readonly.asInstanceOf[Register[Bool]] := true.B
final class DatatypeCast extends Module:
  val enable = in(Bool)
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      val stored = Reg(false.B)
      val _ = i
  when(enable):
    lanes(0).stored.asInstanceOf[Register[UInt]] := 1.U(8)
