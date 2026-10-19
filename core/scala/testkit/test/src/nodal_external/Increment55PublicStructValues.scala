package nodal_external

import nodal.*
import scala.compiletime.testing.typeChecks

/** Separately compiled user-library surface: no private constructors or raw field-name casts. */
object Increment55PublicStructValues:
  def project[A <: Data](value: Expr[Struct], key: StructMember[A]): Expr[A] = value.field(key)
  def build(schema: DataType[Struct], key: StructMember[UInt], value: Expr[UInt]): Expr[Struct] =
    schema.value(key.bind(value))
  val wrongBindingRejected: Boolean = !typeChecks("""
    import nodal.*
    val field = StructField("value", UInt(4))
    val schema = Struct("Record", field)
    schema.member(field).bind(1.S(4))
  """)
  val privateHandleRejected: Boolean = !typeChecks("""
    import nodal.*
    val field = StructField("value", UInt(4))
    val schema = Struct("Record", field)
    new StructMember(schema, 0, field)
  """)
