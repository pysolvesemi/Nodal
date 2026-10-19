import nodal.*
final class OtherEquality:
  def ==(value: Expr[Integer]): Boolean =
    val _ = value
    true
final class SameName extends Module:
  val MODE = param(1.integer, 0 to 2)
  val input = in(Bits(8))
  val custom = new OtherEquality
  val x = if custom == MODE then ~input else input
