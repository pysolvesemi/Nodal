package nodal.flatfsm.separate
import nodal.*
object Base:
  def condition(value: Expr[Bool]): Expr[Bool] = value
  def value(input: Expr[UInt]): Expr[UInt] = input
