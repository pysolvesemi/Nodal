package nodal

import java.util.IdentityHashMap

/** Exact source-level scalar conversions. Width proofs belong to the construction session;
  * conversion operation names and typed operand edges are the target-neutral semantic contract.
  */
private[nodal] object DigitalScalarContract:
  val Prefix: String = "digital_"
  val Operations: Set[String] = Set(
    "digital_extend",
    "digital_truncate",
    "digital_wrap",
    "digital_saturate",
    "digital_resize_checked",
    "digital_to_signed",
    "digital_to_unsigned",
    "digital_reinterpret_signed",
    "digital_reinterpret_unsigned",
    "digital_to_real"
  )

  private def fail(number: Int, message: String): Nothing =
    DigitalValueContract.fail(number, message)

  private def scalarType(value: Expr[?]): KernelTypeDescriptor =
    val descriptor = CandidateRuntime.expressionDataType(value).map(CandidateRuntime.typeDescriptor)
      .getOrElse(fail(5, "digital conversion requires an operand with an exact scalar type"))
    if !Set("Bits", "UInt", "SInt").contains(descriptor.kind) then
      fail(5, s"digital conversion does not support '${descriptor.kind}'")
    descriptor

  def widthBounds(descriptor: KernelTypeDescriptor): (Long, Long) =
    if descriptor.arguments.size != 1 then
      fail(1, "digital scalar type requires exactly one width")
    val bounds = descriptor.arguments.head match
      case width: Int => (width.toLong, width.toLong)
      case width: KernelExpr[?]
          if width.resultDescriptor.contains(KernelTypeDescriptor("Integer")) &&
            width.literal.exists(literal =>
              literal.kind == "integer" && literal.dataType == KernelTypeDescriptor("Integer")
            ) =>
        width.literal.get.value.toIntOption.map(value => (value.toLong, value.toLong))
          .getOrElse(ConstructionKernel.digitalWidthBounds(descriptor).getOrElse(
            fail(1, "derived i64 digital width requires an active construction width proof")
          ))
      case _: Expr[?] => ConstructionKernel.digitalWidthBounds(descriptor).getOrElse(
          fail(6, "symbolic digital conversion requires an active construction width proof")
        )
      case _ => fail(1, "digital width requires a static Integer expression")
    if bounds._1 <= 0L || bounds._2 < bounds._1 then
      fail(1, "digital width must be positive for every legal parameter value")
    bounds

  private def requiredWidth(kind: String, value: BigInt): Long = kind match
    case "SInt" => value.bitLength.toLong + 1L
    case _ if value >= 0 => math.max(1, value.bitLength).toLong
    case _ => Long.MaxValue

  /** Constant proof only: never inspect a parameter default or a signal initializer. Large bit
    * patterns are not allocated just to prove a conversion; an absent proof rejects checked
    * narrowing. The finite traversal bound matches the captured digital expression profile.
    */
  def knownValue(value: Expr[?]): Option[BigInt] =
    val cache = new IdentityHashMap[Expr[?], Option[BigInt]]()
    val active = new IdentityHashMap[Expr[?], java.lang.Boolean]()
    var remaining = 65536
    def visit(current: Expr[?], depth: Int): Option[BigInt] =
      if depth > 128 || active.containsKey(current) then None
      else if cache.containsKey(current) then cache.get(current)
      else if remaining == 0 then None
      else
        remaining -= 1
        active.put(current, true)
        val result = current match
          case expression: KernelExpr[?] =>
            expression.literal match
              case Some(literal)
                  if literal.kind == "integer" && expression.operation.contains("integer") &&
                    expression.resultDescriptor.contains(literal.dataType) &&
                    Set("Bits", "UInt", "SInt").contains(literal.dataType.kind) =>
                literal.value.toIntOption.map(BigInt(_)).filter(value =>
                  requiredWidth(literal.dataType.kind, value) <= widthBounds(literal.dataType)._1
                )
              case Some(literal)
                  if literal.kind == "boolean" && expression.operation.contains("boolean") &&
                    expression.resultDescriptor.contains(KernelTypeDescriptor("Bool")) &&
                    literal.dataType == KernelTypeDescriptor("Bool") =>
                literal.value match
                  case "true" => Some(BigInt(1))
                  case "false" => Some(BigInt(0))
                  case _ => None
              case None
                  if expression.operation.contains(DigitalMaterializationContract.Operation) &&
                    expression.operands.size == 1 =>
                expression.operands.head match
                  case operand: Expr[?] => visit(operand, depth + 1)
                  case _ => None
              case None
                  if expression.operation.exists(operation =>
                    DigitalCombinationalContract.Operations.contains(operation) ||
                      DigitalCombinationalContract.ReachableBooleanOperations.contains(operation)
                  ) =>
                DigitalCombinationalContract.constantValue(expression, visit(_, depth + 1))
              case None
                  if expression.operation.exists(Operations.contains) &&
                    expression.operands.size == 1 =>
                expression.operands.head match
                  case operand: Expr[?] =>
                    visit(operand, depth + 1).flatMap: constant =>
                      val descriptor = expression.resultDescriptor.getOrElse(
                        fail(5, "digital conversion result lacks its exact scalar type")
                      )
                      expression.operation.get match
                        case "digital_extend" | "digital_resize_checked" |
                            "digital_to_signed" | "digital_to_unsigned" => Some(constant)
                        case "digital_to_real" => None
                        case operation =>
                          val (minimum, maximum) = widthBounds(descriptor)
                          if requiredWidth(descriptor.kind, constant) <= minimum then Some(constant)
                          else if minimum != maximum || maximum > 4096L then None
                          else
                            val width = maximum.toInt
                            val modulus = BigInt(1) << width
                            if operation == "digital_saturate" then
                              val lower = if descriptor.kind == "SInt" then -(modulus >> 1)
                              else BigInt(0)
                              val upper = if descriptor.kind == "SInt" then (modulus >> 1) - 1
                              else modulus - 1
                              Some(constant.max(lower).min(upper))
                            else
                              val bits = ((constant % modulus) + modulus) % modulus
                              Some(
                                if descriptor.kind == "SInt" && bits.testBit(width - 1) then
                                  bits - modulus
                                else bits
                              )
                  case _ => None
              case _ => None
          case _ => None
        active.remove(current)
        cache.put(current, result)
        result
    visit(value, 0)

  private def nonnegative(value: Expr[?]): Boolean =
    if knownValue(value).exists(_ >= 0) then true
    else
      var current = value
      var depth = 0
      var result: Option[Boolean] = None
      while depth <= 128 && result.isEmpty do
        val descriptor = scalarType(current)
        if descriptor.kind == "UInt" || descriptor.kind == "Bits" then result = Some(true)
        else
          current match
            case expression: KernelExpr[?] if expression.operands.size == 1 =>
              expression.operation match
                case Some("digital_to_signed") => result = Some(true)
                case Some(
                      "digital_extend" | "digital_resize_checked" | "digital_saturate" |
                      "digital_alias"
                    ) =>
                  expression.operands.head match
                    case operand: Expr[?] => current = operand
                    case _ => result = Some(false)
                case _ => result = Some(false)
            case _ => result = Some(false)
        depth += 1
      result.getOrElse(false)

  private def emit[A <: Data](
      operation: String,
      value: Expr[?],
      descriptor: KernelTypeDescriptor
  ): Expr[A] =
    val expression = new KernelExpr[A](
      Vector(value),
      resultType = Some(descriptor),
      operation = Some(operation)
    )
    ConstructionKernel.expression(expression)
    expression

  def literal[A <: Data](kind: String, value: Int, width: Expr[Integer]): Expr[A] =
    val descriptor = KernelTypeDescriptor(kind, Vector(width))
    val _ = DigitalValueContract.literalRequiredWidth(kind, BigInt(value))
    val facts = ConstructionKernel.digitalLiteralWidthFacts(descriptor)
      .getOrElse(Some(widthBounds(descriptor)))
    facts.foreach(bounds => DigitalValueContract.validateLiteral(kind, BigInt(value), bounds._1))
    val expression = new KernelExpr[A](
      Vector(value, width),
      resultType = Some(descriptor),
      literal = Some(KernelLiteral("integer", value.toString, descriptor)),
      operation = Some("integer")
    )
    ConstructionKernel.expression(expression)
    expression

  /** Preserve the source kind without evaluating a child's formal width in the parent. The existing
    * conversion proof runs after its exact operand has finalized.
    */
  private def emitInferred[A <: Data](
      operation: String,
      value: Expr[?],
      provisional: => KernelTypeDescriptor
  )(result: => KernelTypeDescriptor): Expr[A] =
    if !DeferredResultType.pending(value) then emit(operation, value, result)
    else
      val expression = new KernelExpr[A](
        Vector(value),
        resultType = Some(provisional),
        operation = Some(operation),
        typeIntent = DeferredResultType.intent(Vector(value))(result)
      )
      ConstructionKernel.expression(expression)
      expression

  def resize[A <: Data](value: Expr[A], width: Int, policy: String): Expr[A] =
    def result: KernelTypeDescriptor =
      val source = scalarType(value)
      val (minimum, maximum) = widthBounds(source)
      val resized = source.copy(arguments = Vector(width))
      val _ = widthBounds(resized)
      policy match
        case "extend" if width.toLong < maximum =>
          fail(7, "extend must not narrow for any legal source width")
        case "truncate" if width.toLong > minimum =>
          fail(7, "truncate must not widen for any legal source width")
        case "resize_checked"
            if width.toLong < maximum &&
              !knownValue(value).exists(requiredWidth(source.kind, _) <= width.toLong) =>
          fail(8, "checked resize requires a proof that every source value fits the result width")
        case "extend" | "truncate" | "wrap" | "saturate" | "resize_checked" => ()
        case _ => fail(5, s"unsupported digital resize policy '$policy'")
      resized
    emitInferred(
      s"$Prefix$policy",
      value,
      scalarType(value).copy(arguments = Vector(width))
    )(result)

  def toSigned(value: Expr[UInt]): Expr[SInt] =
    def result: KernelTypeDescriptor =
      val source = scalarType(value)
      if source.kind != "UInt" then fail(5, "toSigned requires an unsigned numeric operand")
      val _ = widthBounds(source)
      val width = source.arguments.head match
        case literal: Int if literal < Int.MaxValue => literal + 1
        case _: Int => fail(7, "numeric signed conversion exceeds the static width representation")
        case symbolic: Expr[?] =>
          CandidateRuntime.integerExpr(
            "analog_add",
            symbolic.asInstanceOf[Expr[Integer]],
            CandidateRuntime.literalInteger(1)
          )
        case _ => fail(1, "digital width requires a static Integer expression")
      val signed = KernelTypeDescriptor("SInt", Vector(width))
      val _ = widthBounds(signed)
      signed
    emitInferred("digital_to_signed", value, scalarType(value).copy(kind = "SInt"))(result)

  def toUnsigned(value: Expr[SInt]): Expr[UInt] =
    def result: KernelTypeDescriptor =
      val source = scalarType(value)
      if source.kind != "SInt" then fail(5, "toUnsigned requires a signed numeric operand")
      val _ = widthBounds(source)
      if !nonnegative(value) then
        fail(
          8,
          "numeric unsigned conversion requires a proof that every source value is nonnegative"
        )
      source.copy(kind = "UInt")
    emitInferred("digital_to_unsigned", value, scalarType(value).copy(kind = "UInt"))(result)

  def reinterpret[A <: Data](value: Expr[?], signed: Boolean): Expr[A] =
    val kind = if signed then "SInt" else "UInt"
    def result: KernelTypeDescriptor =
      val source = scalarType(value)
      val expected = if signed then "UInt" else "SInt"
      if source.kind != expected then
        fail(5, s"signedness reinterpretation requires a $expected operand")
      val _ = widthBounds(source)
      source.copy(kind = kind)
    emitInferred(
      if signed then "digital_reinterpret_signed" else "digital_reinterpret_unsigned",
      value,
      scalarType(value).copy(kind = kind)
    )(result)

  /** Explicit numeric conversion uses binary64 round-to-nearest, ties-to-even. Rounding is
    * permitted; overflow to infinity is not. A width up to 1023 proves finite conversion for the
    * entire unsigned source range; larger widths require an exact constant proof.
    */
  def toReal(value: Expr[UInt]): Expr[Real] =
    def result: KernelTypeDescriptor =
      val source = scalarType(value)
      if source.kind != "UInt" then fail(5, "toReal requires an unsigned numeric operand")
      if widthBounds(source)._2 > 1023L && !knownValue(value).exists(_.toDouble.isFinite) then
        fail(8, "numeric Real conversion requires a proof of finite binary64 representation")
      KernelTypeDescriptor("Real")
    emitInferred("digital_to_real", value, KernelTypeDescriptor("Real"))(result)

  /** Recheck ordinary conversion obligations under an admitted collection Integer context. */
  def validateCaptured(expression: KernelExpr[?]): Unit =
    val value = expression.operands.head.asInstanceOf[Expr[?]]
    val source = scalarType(value)
    val (minimum, maximum) = widthBounds(source)
    val result = expression.resultDescriptor.getOrElse(fail(5, "conversion result type is absent"))
    val operation = expression.operation.getOrElse("")
    val resultBounds = if result.kind == "Real" then (0L, 0L) else widthBounds(result)
    operation match
      case "digital_extend" if resultBounds._1 < maximum =>
        fail(7, "extend narrows in a reachable reduction context")
      case "digital_truncate" if resultBounds._2 > minimum =>
        fail(7, "truncate widens in a reachable reduction context")
      case "digital_resize_checked"
          if resultBounds._1 < maximum &&
            !knownValue(value).exists(requiredWidth(source.kind, _) <= resultBounds._1) =>
        fail(8, "checked resize is unproved in a reachable reduction context")
      case "digital_to_unsigned" if !nonnegative(value) =>
        fail(8, "unsigned conversion is unproved in a reachable reduction context")
      case "digital_to_real" if maximum > 1023L && !knownValue(value).exists(_.toDouble.isFinite) =>
        fail(8, "Real conversion is unproved in a reachable reduction context")
      case _ => ()
