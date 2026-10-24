package area_factory

import nodal.*
import area_producer.*

final class DerivedArea(input: Expr[Bits]) extends ReusableArea(input):
  val alias = copied

object AreaFactory:
  def make(input: Expr[Bits]): DerivedArea = new DerivedArea(input)
