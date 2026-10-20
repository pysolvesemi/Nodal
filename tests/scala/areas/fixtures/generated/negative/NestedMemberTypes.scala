package generated_consumer
import nodal.*
import generated_producer.*
object NestedMemberTypes:
  def readonly(value: GeneratedAreaRef[ReusableOuter]): Register[UInt] = value.inner(0).readonly
  def datatype(value: GeneratedAreaRef[ReusableOuter]): Register[Bool] = value.inner(0).stored
  def hidden(value: GeneratedAreaRef[ReusableOuter]): Any = value.hiddenFamily(0)
  def missing(value: GeneratedAreaRef[ReusableOuter]): Any = value.absent(0)
  def storage(value: GeneratedAreaRef[ReusableLane]): Any = value.stored(0)
