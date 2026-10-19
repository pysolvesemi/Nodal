import nodal.*
final class StructuralAliases extends Module:
  val MODE = param(1.integer, 0 to 2)
  val input = in(Bits(8))
  val predicate = MODE == 0
  final def test: Boolean = MODE != 0
  val one = if predicate then ~input else input
  val two = if test then ~input else input
