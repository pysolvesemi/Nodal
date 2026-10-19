package nodal.iteration.fixture
import nodal.*
final class LoopPositive extends Module:
  val source = in(Vec(UInt(8), 2))
  val target = out(Vec(UInt(8), 2))
  private def pass(value: Expr[UInt]): Expr[UInt] = (value + 1.U(8)).wrap(8)
  loop(LoopBound.Static(2)): index =>
    target.writable.at(index.value) := pass(source.at(index.value))
final class MixedPositive extends Module:
  val source = in(Vec(UInt(8), 2))
  val target = out(Vec(UInt(8), 2))
  val p = inout(Electrical)
  hdlRange(0, 2): index =>
    val tap = node(Electrical)
    analog:
      V(tap, p) <+ 1.V
    target.writable.at(index) := source.at(index)
final class NestedPurePositive extends Module:
  val source = in(Vec(UInt(8), 2, 2))
  val target = out(Vec(UInt(8), 2, 2))
  loop(LoopBound.Static(2)): index =>
    target.writable.row(index.value) := source.row(index.value).map(x => (x + 1.U(8)).wrap(8))

object SafeLoopAlias:
  val callback: LoopIndex => Unit = _ => ()

final class LoopAliasPositive extends Module:
  loop(LoopBound.Static(0))(SafeLoopAlias.callback)
