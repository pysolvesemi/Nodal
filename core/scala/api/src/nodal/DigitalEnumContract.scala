package nodal

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private[nodal] final case class EnumDeclaredCase[A](
    id: String,
    name: String,
    value: A,
    source: Option[SourceSpan]
)

private[nodal] final case class EnumDeclaration[A](
    identity: String,
    cases: Vector[EnumDeclaredCase[A]],
    source: Option[SourceSpan]
):
  val byValue: Map[A, EnumDeclaredCase[A]] = cases.map(value => value.value -> value).toMap

private[nodal] final case class ResolvedEnumCase(
    id: String,
    name: String,
    code: BigInt,
    source: Option[SourceSpan]
)

private[nodal] final case class ResolvedEnumCodeMap(
    identity: String,
    width: Int,
    cases: Vector[ResolvedEnumCase],
    source: Option[SourceSpan]
)

/** Sole immutable ABI owner. Host enum instances never enter the expression graph. */
private[nodal] final case class ResolvedEnumAbi(
    identity: String,
    policy: String,
    width: Int,
    cases: Vector[ResolvedEnumCase],
    hash: String,
    source: Option[SourceSpan]
):
  val caseIds: Set[String] = cases.map(_.id).toSet
  def displayName: String = DigitalEnumContract.displayName(identity)

/** Non-expression facts accompanying real typed graph operands. */
private[nodal] final case class EnumOperationMetadata(
    abi: ResolvedEnumAbi,
    caseIds: Vector[String] = Vector.empty
)

private[nodal] object DigitalEnumContract:
  // Bound construction before shifting/allocating one-hot magnitudes or parsing custom widths.
  val MaximumCases: Int = 4096
  val MaximumWidth: Int = 1048576
  val MaximumCodeBits: Long = 16777216L
  val Prefix: String = "enum_"

  /** Injective authored-component encoding; separators and carrier punctuation cannot collide. */
  def identityComponent(name: String): String =
    name.codePoints().toArray.iterator.map: codePoint =>
      val text = new String(Character.toChars(codePoint))
      if Character.isLetterOrDigit(codePoint) || codePoint == '_'.toInt || codePoint == '$'.toInt
      then text
      else
        text.getBytes(StandardCharsets.UTF_8).iterator.map(value =>
          f"%%${value & 0xff}%02X"
        ).mkString
    .mkString

  def displayName(identity: String): String =
    java.net.URLDecoder.decode(
      identity.substring(identity.lastIndexOf('.') + 1),
      StandardCharsets.UTF_8
    )

  def fail(number: Int, message: String): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic(f"NODAL-ENUM-054-$number%03d", message))
    ).get

  private def declaration[A](evidence: HwEnum[A]): EnumDeclaration[A] =
    val value = evidence.declaration.getOrElse(
      fail(2, "hardware use requires metadata from a singleton native enum deriving HwEnum")
    )
    if value.cases.isEmpty || value.cases.size > MaximumCases then
      fail(3, s"hardware enum requires 1 through $MaximumCases cases")
    value

  private def belonging[A](value: A, owner: EnumDeclaration[A]): EnumDeclaredCase[A] =
    owner.byValue.get(value).getOrElse(
      fail(4, s"case does not belong to hardware enum '${owner.identity}'")
    )

  def encoding[A](values: Vector[(A, BigInt)], evidence: HwEnum[A]): EnumEncoding[A] =
    // The helper keeps its old specification role: full completeness is checked at resolution.
    val _ = evidence
    val keys = values.map(_._1)
    if keys.distinct.size != keys.size then fail(5, "enum encoding repeats a case")
    val codes = values.map(_._2)
    if codes.exists(_ < 0) then fail(9, "enum codes must be nonnegative")
    if codes.distinct.size != codes.size then fail(10, "enum codes must be unique")
    EnumEncoding(values.toMap)

  /** Versioned UTF-8 byte-length framing; case IDs sort by unsigned UTF-16 code units, matching
    * Scala/Java String order. Source positions and encoding-policy labels do not affect the ABI.
    */
  def abiHash(identity: String, width: Int, cases: Vector[ResolvedEnumCase]): String =
    def field(value: String): String =
      s"${value.getBytes(StandardCharsets.UTF_8).length}:$value\n"
    val canonical = "nodal.enum.abi.v1\n" + field(identity) + field(width.toString) +
      cases.sortBy(_.id).map(value => field(value.id) + field(value.code.toString)).mkString
    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))
      .map(value => f"${value & 0xff}%02x").mkString

  def codeMap[A](
      evidence: HwEnum[A],
      policy: String,
      custom: Option[(Int, EnumEncoding[A])] = None
  ): ResolvedEnumCodeMap =
    val owner = declaration(evidence)
    val count = owner.cases.size
    val width = policy match
      case "sequential" | "gray" => math.max(1, BigInt(count - 1).bitLength)
      case "one_hot" => count
      case "custom" => custom.map(_._1).getOrElse(fail(6, "custom enum encoding is missing"))
      case _ => fail(6, s"unknown hardware enum policy '$policy'")
    if width <= 0 || width > MaximumWidth then
      fail(7, s"enum width must be between 1 and $MaximumWidth")
    val codes = policy match
      case "custom" =>
        val specified = custom.get._2.values
        val entries = specified.toVector.map: (value, code) =>
          belonging(value, owner).id -> code
        if entries.size != count || entries.map(_._1).toSet != owner.cases.map(_.id).toSet then
          fail(8, "custom enum encoding must contain every legal case exactly once")
        val byId = entries.toMap
        owner.cases.map(value => byId(value.id))
      case "one_hot" => Vector.tabulate(count)(index => BigInt(1) << index)
      case "gray" => Vector.tabulate(count)(index => BigInt(index ^ (index >> 1)))
      case _ => Vector.tabulate(count)(BigInt(_))
    if codes.exists(value => value < 0 || value.bitLength > width) then
      fail(9, "enum code must be nonnegative and fit the exact declared width")
    if codes.distinct.size != count then fail(10, "enum codes must be unique")
    if codes.foldLeft(0L)((total, value) => total + math.max(1, value.bitLength).toLong) >
        MaximumCodeBits
    then
      fail(3, s"enum codes exceed the $MaximumCodeBits-bit metadata budget")
    val cases = owner.cases.zip(codes).map: (value, code) =>
      ResolvedEnumCase(value.id, value.name, code, value.source)
    ResolvedEnumCodeMap(owner.identity, width, cases, owner.source)

  def dataType[A](
      evidence: HwEnum[A],
      policy: String,
      custom: Option[(Int, EnumEncoding[A])] = None
  ): DataType[EnumValue[A]] =
    val resolved = codeMap(evidence, policy, custom)
    val abi = ResolvedEnumAbi(
      resolved.identity,
      policy,
      resolved.width,
      resolved.cases,
      abiHash(resolved.identity, resolved.width, resolved.cases),
      resolved.source
    )
    new KernelDataType[EnumValue[A]](KernelTypeDescriptor("Enum", Vector(abi)))

  def abi(descriptor: KernelTypeDescriptor): ResolvedEnumAbi = descriptor match
    case KernelTypeDescriptor("Enum", Vector(value: ResolvedEnumAbi)) => value
    case _ => fail(11, "expression or declaration does not have a resolved enum type")

  def abi(dataType: DataType[?]): ResolvedEnumAbi = abi(CandidateRuntime.typeDescriptor(dataType))

  private def descriptor(value: Expr[?]): KernelTypeDescriptor =
    CandidateRuntime.expressionDataType(value).map(CandidateRuntime.typeDescriptor).getOrElse(
      fail(11, "enum operation requires a known exact expression type")
    )

  def abi(value: Expr[?]): ResolvedEnumAbi = abi(descriptor(value))

  /** Exact structural identity, excluding enum policy/provenance when the actual ABI agrees. */
  def sameType(
      left: KernelTypeDescriptor,
      right: KernelTypeDescriptor,
      integerIdentity: Option[(Dimension, Dimension) => Boolean] = None
  ): Boolean = compareType(left, right, integerIdentity, widthFamily = false)

  private def compareType(
      left: KernelTypeDescriptor,
      right: KernelTypeDescriptor,
      integerIdentity: Option[(Dimension, Dimension) => Boolean],
      widthFamily: Boolean
  ): Boolean =
    val memo =
      new java.util.IdentityHashMap[AnyRef, java.util.IdentityHashMap[AnyRef, java.lang.Boolean]]()
    var examined = 0
    def dimension(value: Any): Option[Dimension] = value match
      case literal: Int => Some(literal)
      case expression: Expr[?]
          if CandidateRuntime.expressionDataType(expression)
            .exists(value =>
              CandidateRuntime.typeDescriptor(value) == KernelTypeDescriptor("Integer")
            ) =>
        Some(expression.asInstanceOf[Expr[Integer]])
      case _ => None
    def literal(value: Dimension): Option[Int] = value match
      case integer: Int => Some(integer)
      case expression: KernelExpr[?] if expression.operation.contains("integer") =>
        expression.literal.filter(value =>
          value.kind == "integer" && value.dataType == KernelTypeDescriptor("Integer")
        ).flatMap(_.value.toIntOption).filter(value => expression.operands == Vector(value))
      case _ => None
    def sameDimension(left: Dimension, right: Dimension): Boolean =
      // Reuse the construction owner's checked, interned static algebra. Parameter identity and
      // ordered operation trees remain exact; neither defaults nor equal intervals are evidence.
      integerIdentity.map(_(
        left,
        right
      )).orElse(ConstructionKernel.sameStaticInteger(left, right)).getOrElse(
        literal(left).exists(value => literal(right).contains(value))
      )
    def same(a: Any, b: Any, depth: Int): Boolean =
      if depth >= 128 then fail(17, "enum selection result type exceeds the structural depth bound")
      val ar = a.asInstanceOf[AnyRef]
      val br = b.asInstanceOf[AnyRef]
      if ar eq br then true
      else
        val row = Option(memo.get(ar)).getOrElse:
          val fresh = new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
          memo.put(ar, fresh)
          fresh
        Option(row.get(br)) match
          case Some(result) => result.booleanValue
          case None =>
            examined += 1
            if examined > 65536 then
              fail(17, "enum selection result type exceeds the structural size bound")
            row.put(br, java.lang.Boolean.FALSE)
            val result = (a, b) match
              case (x: KernelTypeDescriptor, y: KernelTypeDescriptor) =>
                x.kind == y.kind &&
                (if widthFamily && Set("Bits", "UInt", "SInt").contains(x.kind) then
                   x.arguments.size == 1 && y.arguments.size == 1 &&
                   dimension(x.arguments.head).nonEmpty && dimension(y.arguments.head).nonEmpty
                 else same(x.arguments, y.arguments, depth + 1))
              case (x: DataType[?], y: DataType[?]) =>
                same(
                  CandidateRuntime.typeDescriptor(x),
                  CandidateRuntime.typeDescriptor(y),
                  depth
                )
              case (x: ResolvedEnumAbi, y: ResolvedEnumAbi) =>
                x.identity == y.identity && x.hash == y.hash
              case (x: StructField[?], y: StructField[?]) =>
                x.name == y.name && same(x.dataType, y.dataType, depth)
              case (x: Seq[?], y: Seq[?]) =>
                x.size == y.size &&
                x.iterator.zip(y.iterator).forall((x, y) => same(x, y, depth))
              case _ =>
                (dimension(a), dimension(b)) match
                  case (Some(x), Some(y)) => sameDimension(x, y)
                  case _ => a == b
            row.put(br, java.lang.Boolean.valueOf(result))
            result
    same(left, right, 0)

  /** Construction-only relation: erase scalar width leaves, preserve every other exact identity.
    * This is never storage/assignment equality, and never evaluates a parameter default.
    */
  def sameWidthFamily(left: KernelTypeDescriptor, right: KernelTypeDescriptor): Boolean =
    compareType(left, right, None, widthFamily = true)

  def selectedCase[A](value: A, evidence: HwEnum[A], resolved: ResolvedEnumAbi): String =
    val owner = declaration(evidence)
    if owner.identity != resolved.identity then
      fail(4, "enum case identity does not match its type")
    val id = belonging(value, owner).id
    if !resolved.caseIds.contains(id) then
      fail(4, "enum case is missing from its resolved type")
    id

  private def expression[A <: Data](
      operation: String,
      resultType: KernelTypeDescriptor,
      operands: Vector[Any],
      metadata: EnumOperationMetadata
  ): Expr[A] =
    val result = new KernelExpr[A](
      operands :+ metadata,
      resultType = Some(resultType),
      operation = Some(Prefix + operation)
    )
    ConstructionKernel.expression(result)
    result

  def literal[A](enumType: DataType[EnumValue[A]], value: A, evidence: HwEnum[A])
      : Expr[EnumValue[A]] =
    val resolved = abi(enumType)
    val id = selectedCase(value, evidence, resolved)
    expression(
      "literal",
      CandidateRuntime.typeDescriptor(enumType),
      Vector.empty,
      EnumOperationMetadata(resolved, Vector(id))
    )

  def legacyDecode[A](bits: Expr[Bits], default: A): DecodedEnum[A] =
    val _ = (bits, default)
    fail(
      12,
      "host-valued decodeEnum cannot represent hardware; use decodeEnum(bits, enumType, defaultCase)"
    )

  def decode[A](
      bits: Expr[Bits],
      enumType: DataType[EnumValue[A]],
      defaultCase: A,
      evidence: HwEnum[A]
  ): DecodedEnum[Expr[EnumValue[A]]] =
    val resolved = abi(enumType)
    val input = descriptor(bits)
    if !Set("Bits", "UInt").contains(input.kind) ||
      !ConstructionKernel.digitalWidthEquals(input, resolved.width)
    then
      fail(13, "enum decode requires unsigned bits of exactly the enum width")
    val id = selectedCase(defaultCase, evidence, resolved)
    val value = expression[EnumValue[A]](
      "decode",
      CandidateRuntime.typeDescriptor(enumType),
      Vector(bits),
      EnumOperationMetadata(resolved, Vector(id))
    )
    val valid = expression[Bool](
      "is_valid",
      KernelTypeDescriptor("Bool"),
      Vector(bits),
      EnumOperationMetadata(resolved)
    )
    DecodedEnum(value, valid)

  def asBits[A](value: Expr[EnumValue[A]]): Expr[Bits] =
    val resolved = abi(value)
    expression(
      "as_bits",
      KernelTypeDescriptor("Bits", Vector(resolved.width)),
      Vector(value),
      EnumOperationMetadata(resolved)
    )

  def isValid[A](value: Expr[EnumValue[A]]): Expr[Bool] =
    expression(
      "is_valid",
      KernelTypeDescriptor("Bool"),
      Vector(value),
      EnumOperationMetadata(abi(value))
    )

  def isOneOf[A](value: Expr[EnumValue[A]], cases: Vector[A], evidence: HwEnum[A]): Expr[Bool] =
    val resolved = abi(value)
    val ids = cases.map(selectedCase(_, evidence, resolved)).distinct.sorted
    expression(
      "is_one_of",
      KernelTypeDescriptor("Bool"),
      Vector(value),
      EnumOperationMetadata(resolved, ids)
    )

  def equal[A](left: Expr[EnumValue[A]], right: Expr[EnumValue[A]]): Expr[Bool] =
    val resolved = abi(left)
    val other = abi(right)
    if resolved.identity != other.identity || resolved.hash != other.hash then
      fail(14, "enum equality requires identical semantic identity and ABI")
    expression(
      "eq",
      KernelTypeDescriptor("Bool"),
      Vector(left, right),
      EnumOperationMetadata(resolved)
    )

  def validateSelectionResult(descriptor: KernelTypeDescriptor): Unit =
    val visited = new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    var remaining = 65536
    def visit(value: Any, depth: Int): Unit =
      if depth > 128 then fail(17, "enum selection result type exceeds the structural depth bound")
      val reference = value.asInstanceOf[AnyRef]
      if !visited.containsKey(reference) then
        remaining -= 1
        if remaining < 0 then
          fail(17, "enum selection result type exceeds the structural size bound")
        visited.put(reference, java.lang.Boolean.TRUE)
        value match
          case selected: KernelTypeDescriptor =>
            if Set("Clock", "Reset").contains(selected.kind) then
              fail(17, "enum selection cannot compute clock/reset capabilities")
            selected.arguments.foreach(visit(_, depth + 1))
          case selected: DataType[?] => visit(CandidateRuntime.typeDescriptor(selected), depth + 1)
          case selected: StructField[?] => visit(selected.dataType, depth + 1)
          case selected: Seq[?] => selected.foreach(visit(_, depth + 1))
          case _ => ()
    visit(descriptor, 0)

  def completeSelection(ids: Vector[String], resolved: ResolvedEnumAbi): Unit =
    if ids.distinct.size != ids.size then fail(15, "enum selection repeats a case")
    if ids.toSet != resolved.caseIds then
      fail(16, "enum selection must cover every legal case exactly once")

  def select[A, B <: Data](
      selector: Expr[EnumValue[A]],
      invalidResult: Expr[B],
      cases: Vector[(A, Expr[B])],
      evidence: HwEnum[A]
  ): Expr[B] =
    val resolved = abi(selector)
    val ids = cases.map((value, _) => selectedCase(value, evidence, resolved))
    completeSelection(ids, resolved)
    val resultType = descriptor(invalidResult)
    validateSelectionResult(resultType)
    if cases.exists((_, value) => !sameType(descriptor(value), resultType)) then
      fail(17, "enum selection results must have identical exact types")
    val byId = ids.zip(cases.map(_._2)).toMap
    val ordered = resolved.cases.map(_.id)
    expression(
      "select",
      resultType,
      Vector(selector, invalidResult) ++ ordered.map(byId),
      EnumOperationMetadata(resolved, ordered)
    )
