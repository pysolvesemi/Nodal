import nodal.*
object EffectfulSingleton:
  println("must never initialize")
  val harmless = 1.integer
final class StructuralEffects extends Module:
  val MODE = param(0.integer, 0 to 2)
  val input = in(Bits(8))
  var state = 0
  lazy val delayed = ~input
  final def recursive(): Expr[Bits] = recursive()
  val printDead = if MODE == 0 then ~input
  else
    println("must never execute")
    input
  val mutation = if MODE == 0 then
    state += 1
    ~input
  else input
  val lazyRead: Expr[Bits] = if MODE == 0 then delayed else input
  val rec: Expr[Bits] = if MODE == 0 then recursive() else input
  val initializer = if MODE == EffectfulSingleton.harmless then ~input else input
