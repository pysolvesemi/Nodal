package nodal

/** Checked semantic schema evidence, separate from real value operands. */
private[nodal] final case class StructOperationMetadata(
    schema: DataType[Struct],
    ordinal: Option[Int] = None,
    name: Option[String] = None
)

/** Shared typed Struct primitives. No registry, host-object schema identity, or callback replay. */
private[nodal] object DigitalAggregateContract:
  val Operations: Set[String] = Set("digital_struct_get", "digital_struct_make")

  private def fail(message: String): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic("NODAL-STRUCT-055-001", message))
    ).get

  private def descriptor(value: Expr[?]): KernelTypeDescriptor =
    CandidateRuntime.expressionDataType(value).map(CandidateRuntime.typeDescriptor).getOrElse(
      fail("Struct value operation requires an exact expression type")
    )

  def fields(value: KernelTypeDescriptor): Vector[StructField[? <: Data]] = value match
    case KernelTypeDescriptor("Struct", Vector(name: String, entries: Seq[?])) =>
      val result = entries.map:
        case field: StructField[?] => field
        case _ => fail("Struct schema requires typed fields")
      if !name.matches("[A-Za-z_][A-Za-z0-9_]*") || name.length > 1024 ||
        result.isEmpty || result.size > 65536 ||
        result.exists(field =>
          !field.name.matches("[A-Za-z_][A-Za-z0-9_]*") ||
            field.name.length > 1024
        ) || result.map(_.name).distinct.size != result.size
      then fail("Struct schema requires a nominal identity and unique ordered fields")
      DigitalCombinationalContract.validateValueType(value)
      result.toVector
    case _ => fail("Struct operation requires a declared Struct schema")

  def member[A <: Data](schema: DataType[Struct], field: StructField[A]): StructMember[A] =
    val declared = fields(CandidateRuntime.typeDescriptor(schema))
    val index = declared.indexWhere(_.name == field.name)
    if index < 0 || !DigitalEnumContract.sameType(
        CandidateRuntime.typeDescriptor(declared(index).dataType),
        CandidateRuntime.typeDescriptor(field.dataType)
      )
    then fail("member must identify an exact declared field of this semantic schema")
    new StructMember(schema, index, field)

  def bind[A <: Data](member: StructMember[A], value: Expr[A]): StructBinding[A] =
    DigitalCombinationalContract.validateValueType(descriptor(value))
    if !DigitalEnumContract.sameWidthFamily(
        CandidateRuntime.typeDescriptor(member.declaration.dataType),
        descriptor(value)
      )
    then fail("binding changes a field kind, enum ABI, layout or inner dimension")
    new StructBinding(member, value)

  private def emit[A <: Data](
      operation: String,
      result: KernelTypeDescriptor,
      operands: Vector[Expr[?]],
      metadata: StructOperationMetadata
  ): Expr[A] =
    val value = new KernelExpr[A](
      operands :+ metadata,
      resultType = Some(result),
      operation = Some(operation)
    )
    ConstructionKernel.expression(value)
    value

  def get[A <: Data](value: Expr[Struct], member: StructMember[A]): Expr[A] =
    val actual = descriptor(value)
    val declared = fields(actual)
    if !DigitalEnumContract.sameWidthFamily(
        CandidateRuntime.typeDescriptor(member.schema),
        actual
      ) ||
      member.ordinal < 0 || member.ordinal >= declared.size ||
      declared(member.ordinal).name != member.declaration.name
    then fail("field handle does not belong to the receiver's exact semantic width family")
    DigitalCombinationalContract.validateValueType(
      CandidateRuntime.typeDescriptor(declared(member.ordinal).dataType)
    )
    // The actual receiver, not the handle's original width, determines the complete result.
    emit(
      "digital_struct_get",
      CandidateRuntime.typeDescriptor(declared(member.ordinal).dataType),
      Vector(value),
      StructOperationMetadata(
        member.schema,
        Some(member.ordinal),
        Some(member.declaration.name)
      )
    )

  def make(schema: DataType[Struct], bindings: Vector[StructBinding[? <: Data]]): Expr[Struct] =
    val base = CandidateRuntime.typeDescriptor(schema)
    val declared = fields(base)
    if bindings.size != declared.size then fail("Struct value requires every field exactly once")
    if bindings.size > 4098 then fail("Struct value exceeds the shared value-DAG operand budget")
    val actual = bindings.zipWithIndex.map: (binding, index) =>
      if binding.member.ordinal != index ||
        binding.member.declaration.name != declared(index).name ||
        !DigitalEnumContract.sameType(base, CandidateRuntime.typeDescriptor(binding.member.schema))
      then fail("Struct bindings must belong to this semantic schema in declared order")
      val result = descriptor(binding.expression)
      if !DigitalEnumContract.sameWidthFamily(
          CandidateRuntime.typeDescriptor(declared(index).dataType),
          result
        )
      then fail("Struct value changes a field kind, enum ABI, layout or inner dimension")
      StructField(declared(index).name, new KernelDataType[Data](result))
    val result = base.copy(arguments = Vector(base.arguments.head, actual))
    emit("digital_struct_make", result, bindings.map(_.expression), StructOperationMetadata(schema))
