package generated_producer
import nodal.*
class BaseLane(initial: Expr[UInt]) extends Area:
  private val privateStorage = Reg(initial)
  val stored = privateStorage
  private val hidden = stored
  protected val protectedValue = stored
  private val hiddenNested = new Area:
    val secret = Reg(false.B)
  val host = 7
  val _ = hidden
  val _ = hiddenNested
  def computed: Expr[UInt] = stored
  val readonly: Expr[UInt] = stored
final class ReusableLane(initial: Expr[UInt]) extends BaseLane(initial):
  val nested = new Area:
    val flag = Reg(false.B)

final class ReusableOuter extends Area:
  private val hiddenFamily =
    for j <- hdlRange(1, 9, 2) yield
      val _ = j
      new ReusableLane(0.U(8))
  val inner = hiddenFamily
