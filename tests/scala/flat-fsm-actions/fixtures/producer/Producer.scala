package nodal.flatfsm.separate
import nodal.*
object Producer:
  def condition(value: Expr[Bool]): Expr[Bool] = Base.condition(value)
  def action(target: Register[UInt], input: Expr[UInt]): Unit = target := Base.value(input)
  def effectfulCondition(target: Register[UInt], input: Expr[UInt], flag: Expr[Bool]): Expr[Bool] =
    target := input
    flag
