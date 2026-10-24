package nodal_external.guardedjar
import nodal.*
final class Leaf extends Area:
  val stored = Reg(7.U(8))
  val readonly: Expr[UInt] = stored
object Factory:
  def make(count: Expr[Integer]): GeneratedAreas[Leaf] =
    for i <- hdlRange(0, count) yield
      val _ = i
      new Leaf
