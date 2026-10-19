package nodal.flatfsm.separate
import nodal.*
object Base:
  def condition(value: Expr[Bool]): Expr[Bool] =
    println("stale dependency")
    value
  def value(input: Expr[UInt]): Expr[UInt] =
    println("stale dependency")
    input
