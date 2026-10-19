import nodal.*
object Outside:
  def predicate(p: Param[Integer]): Boolean = p == 0
  def branch(p: Param[Integer]): Int = if p != 1 then 2 else 3
