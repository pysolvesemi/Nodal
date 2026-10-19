package nodal.callback.fixture
import nodal.*
object RenamedHelpers:
  def entirelyDifferentName(x: Expr[UInt]): Expr[UInt] = x + 1.U(8)
final class ExtraPositive extends Module:
  val input = in(Vec(UInt(8), 3))
  val nested = in(Vec(Vec(UInt(8), 2), 3))
  val seed = in(UInt(8))
  val callback: Expr[UInt] => Expr[UInt] = x => x + seed
  val aliased = input.map(callback)
  val method = input.map(RenamedHelpers.entirelyDifferentName)
  val mapped = nested.map(inner => inner.map(x => x + seed))
  val selected = nested.map(inner => inner.at(0))
  val arbitraryScala = List(1, 2).map(_ + 1)
