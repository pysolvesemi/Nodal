package area_producer

import nodal.*

class ReusableArea(input: Expr[Bits]) extends Area:
  val copied = wire(Bits(8))
  copied := input
  val nested = new Area:
    val inverted = wire(Bits(8))
    inverted := ~input
  val description = "reusable"
