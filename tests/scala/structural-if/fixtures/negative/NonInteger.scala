import nodal.*
object NonInteger:
  def real(p: Expr[Real]): Boolean = p == 0
  def bool(p: Expr[Bool]): Boolean = p != 0
