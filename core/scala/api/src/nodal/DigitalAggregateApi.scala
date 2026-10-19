package nodal

/** A typed semantic schema member. Equivalent reconstructed schemas have the same identity. */
final class StructMember[A <: Data] private[nodal] (
    private[nodal] val schema: DataType[Struct],
    private[nodal] val ordinal: Int,
    private[nodal] val declaration: StructField[A]
):
  def bind(value: Expr[A]): StructBinding[A] = DigitalAggregateContract.bind(this, value)

/** A checked field value retaining its actual type and ordinary expression DAG. */
final class StructBinding[A <: Data] private[nodal] (
    private[nodal] val member: StructMember[A],
    private[nodal] val expression: Expr[A]
)

extension (schema: DataType[Struct])
  def member[A <: Data](field: StructField[A]): StructMember[A] =
    DigitalAggregateContract.member(schema, field)
  def value(bindings: StructBinding[? <: Data]*): Expr[Struct] =
    DigitalAggregateContract.make(schema, bindings.toVector)

extension (value: Expr[Struct])
  def field[A <: Data](member: StructMember[A]): Expr[A] =
    DigitalAggregateContract.get(value, member)
