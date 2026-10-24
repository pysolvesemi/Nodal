package generated_negative
import nodal.*
import generated_producer.*
object MemberTypes:
  def readonly(value: GeneratedAreaRef[ReusableLane]): Unit = value.readonly := 1.U(8)
  def datatype(value: GeneratedAreaRef[ReusableLane]): Register[Bool] = value.stored
  def host(value: GeneratedAreaRef[ReusableLane]): Int = value.host
  def hidden(value: GeneratedAreaRef[ReusableLane]): Expr[UInt] = value.hidden
  def protectedField(value: GeneratedAreaRef[ReusableLane]): Expr[UInt] = value.protectedValue
  def method(value: GeneratedAreaRef[ReusableLane]): Expr[UInt] = value.computed
  def missing(value: GeneratedAreaRef[ReusableLane]): Expr[UInt] = value.missing
  def payload(value: GeneratedAreaRef[ReusableLane]): Expr[UInt] = value
  def dynamic(value: GeneratedAreaRef[ReusableLane], name: String): Any = value.selectDynamic(name)
