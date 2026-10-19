import nodal.*
final class PredicateEffect extends Module:
  val MODE = param(1.integer, 0 to 2)
  val input = in(Bits(8))
  val selected = if MODE == { println("predicate effect"); 0 } then ~input else input
