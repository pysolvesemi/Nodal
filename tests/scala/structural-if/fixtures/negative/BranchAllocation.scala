import nodal.*
final class BranchAllocation extends Module:
  val MODE = param(1.integer, 0 to 2)
  val input = in(Bits(8))
  val selected: Expr[Bits] = if MODE == 0 then wire(Bits(8)) else input
