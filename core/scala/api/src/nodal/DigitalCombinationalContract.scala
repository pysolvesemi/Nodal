package nodal

/** Target-neutral finite combinational values. This owner computes exact types before emission;
  * neither host integer overflow nor backend expression sizing defines a digital result.
  */
private[nodal] object DigitalCombinationalContract:
  val Operations: Set[String] = Set(
    "digital_add",
    "digital_sub",
    "digital_mul",
    "digital_neg",
    "digital_eq",
    "digital_ne",
    "digital_lt",
    "digital_le",
    "digital_gt",
    "digital_ge",
    "digital_and",
    "digital_or",
    "digital_xor",
    "digital_not",
    "digital_concat",
    "digital_extract",
    "digital_select",
    "digital_shl",
    "digital_shr",
    "digital_logical_shr"
  )

  // These operations retain their existing analog owner. They are admitted only when reached
  // through an actual digital value root, never used to seed the digital graph globally.
  val ReachableBooleanOperations: Set[String] = Set("bool_and", "bool_or", "bool_not")

  private def fail(number: Int, message: String): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic(f"NODAL-DIGITAL-055-$number%03d", message))
    ).get

  private def descriptor(value: Expr[?]): KernelTypeDescriptor =
    CandidateRuntime.expressionDataType(value).map(CandidateRuntime.typeDescriptor).getOrElse(
      fail(1, "digital operation requires an operand with an exact type")
    )

  private def scalar(value: Expr[?]): KernelTypeDescriptor =
    val result = descriptor(value)
    if !Set("Bits", "UInt", "SInt").contains(result.kind) then
      fail(1, "digital bit-vector operation requires Bits, UInt or SInt")
    val _ = DigitalScalarContract.widthBounds(result)
    result

  private def width(value: KernelTypeDescriptor): Dimension = value.arguments.head match
    case literal: Int => literal
    case expression: Expr[?] => expression.asInstanceOf[Expr[Integer]]
    case _ => fail(2, "digital width requires a static Integer expression")

  private def addWidths(left: Dimension, right: Dimension): Dimension =
    val a = DigitalScalarContract.widthBounds(KernelTypeDescriptor("Bits", Vector(left)))
    val b = DigitalScalarContract.widthBounds(KernelTypeDescriptor("Bits", Vector(right)))
    val maximum = (left, right) match
      case (_: Int, _: Int) => Int.MaxValue.toLong
      case _ => Long.MaxValue
    if a._2 > maximum - b._2 then
      fail(2, "digital result width exceeds its checked static Integer representation")
    StaticIntegerExpression.add(left, right)

  private def emit[A <: Data](
      operation: String,
      result: KernelTypeDescriptor,
      operands: Expr[?]*
  ): Expr[A] =
    val expression = new KernelExpr[A](
      operands.toVector,
      resultType = Some(result),
      operation = Some(operation)
    )
    ConstructionKernel.expression(expression)
    expression

  private def numericPair(
      left: Expr[?],
      right: Expr[?]
  ): (KernelTypeDescriptor, KernelTypeDescriptor) =
    val a = scalar(left)
    val b = scalar(right)
    if !Set("UInt", "SInt").contains(a.kind) || a.kind != b.kind then
      fail(1, "numeric operands require the same UInt or SInt kind; use explicit conversion")
    (a, b)

  def arithmetic[A <: Data](operation: String, left: Expr[?], right: Expr[?]): Expr[A] =
    val (a, b) = numericPair(left, right)
    val resultWidth = operation match
      case "digital_add" | "digital_sub" =>
        addWidths(StaticIntegerExpression.maximum(width(a), width(b)), 1)
      case "digital_mul" => addWidths(width(a), width(b))
      case _ => fail(1, "unsupported digital arithmetic operation")
    val kind = if operation == "digital_sub" then "SInt" else a.kind
    emit(operation, KernelTypeDescriptor(kind, Vector(resultWidth)), left, right)

  def negate(value: Expr[?]): Expr[SInt] =
    val source = scalar(value)
    if !Set("UInt", "SInt").contains(source.kind) then
      fail(1, "numeric negation requires UInt or SInt")
    emit("digital_neg", KernelTypeDescriptor("SInt", Vector(addWidths(width(source), 1))), value)

  def compare(operation: String, left: Expr[?], right: Expr[?]): Expr[Bool] =
    val a = scalar(left)
    val b = scalar(right)
    if !Set("digital_eq", "digital_ne", "digital_lt", "digital_le", "digital_gt", "digital_ge")
        .contains(operation)
    then fail(1, "unsupported digital comparison")
    if a.kind == "Bits" then
      if !Set("digital_eq", "digital_ne").contains(operation) ||
        !DigitalEnumContract.sameType(a, b)
      then
        fail(1, "Bits equality requires identical exact bit-vector types")
    else if a.kind != b.kind then
      fail(1, "numeric comparison requires the same UInt or SInt kind")
    emit(operation, KernelTypeDescriptor("Bool"), left, right)

  def bitwise[A <: Data](operation: String, left: Expr[A], right: Expr[A]): Expr[A] =
    val a = scalar(left)
    val b = scalar(right)
    if !Set("digital_and", "digital_or", "digital_xor").contains(operation) then
      fail(1, "unsupported digital bitwise operation")
    if !DigitalEnumContract.sameType(a, b) then
      fail(3, "bitwise operands require identical exact kinds and widths")
    emit(operation, a, left, right)

  def complement[A <: Data](value: Expr[A]): Expr[A] =
    emit("digital_not", scalar(value), value)

  def concatenate(left: Expr[?], right: Expr[?]): Expr[Bits] =
    val a = scalar(left)
    val b = scalar(right)
    emit(
      "digital_concat",
      KernelTypeDescriptor("Bits", Vector(addWidths(width(a), width(b)))),
      left,
      right
    )

  private def staticOperand(value: Int): Expr[UInt] =
    CandidateRuntime.literalUInt(value, math.max(1, BigInt(value).bitLength))

  def extract(value: Expr[?], lower: Int, upperExclusive: Int): Expr[Bits] =
    val source = scalar(value)
    if lower < 0 || upperExclusive <= lower ||
      upperExclusive.toLong > DigitalScalarContract.widthBounds(source)._1
    then
      fail(4, "extraction requires a nonempty half-open range within every legal source width")
    emit(
      "digital_extract",
      KernelTypeDescriptor("Bits", Vector(upperExclusive - lower)),
      value,
      staticOperand(lower),
      staticOperand(upperExclusive)
    )

  def shift[A <: Data](operation: String, value: Expr[A], amount: Int): Expr[A] =
    val source = scalar(value)
    if amount < 0 then fail(4, "digital shift amount must be nonnegative")
    val result = operation match
      case "digital_shl" if amount != 0 =>
        source.copy(arguments = Vector(addWidths(width(source), amount)))
      case "digital_shl" | "digital_shr" | "digital_logical_shr" => source
      case _ => fail(1, "unsupported digital shift operation")
    emit(operation, result, value, staticOperand(amount))

  /** Shared root capability profile for computed combinational values. Aggregate storage validation
    * remains with the existing complete-type owner.
    */
  def validateValueType(result: KernelTypeDescriptor): Unit =
    if !Set("Bits", "UInt", "SInt", "Bool", "Enum", "Vec", "Struct").contains(result.kind) then
      fail(1, "digital selection requires finite digital values or storable digital aggregates")
    DigitalEnumContract.validateSelectionResult(result)

  def select[A <: Data](condition: Expr[Bool], whenTrue: Expr[A], whenFalse: Expr[A]): Expr[A] =
    if descriptor(condition) != KernelTypeDescriptor("Bool") then
      fail(1, "digital selection requires a Bool predicate")
    val result = descriptor(whenTrue)
    validateValueType(result)
    if !DigitalEnumContract.sameType(result, descriptor(whenFalse)) then
      fail(3, "digital selection branches require identical complete types")
    emit("digital_select", result, condition, whenTrue, whenFalse)

  /** A bounded proof used by explicit checked conversions. Values remain DAG nodes: successful
    * constant reasoning never substitutes parameter defaults or erases operand/source identity.
    */
  def constantValue(
      expression: KernelExpr[?],
      evaluate: Expr[?] => Option[BigInt]
  ): Option[BigInt] =
    val operands = expression.operands.collect { case value: Expr[?] => value }
    if operands.size != expression.operands.size then None
    else
      val operation = expression.operation.getOrElse("")
      def at(index: Int): Option[BigInt] = operands.lift(index).flatMap(evaluate)
      def pair(function: (BigInt, BigInt) => BigInt): Option[BigInt] =
        if operands.size != 2 then None
        else for left <- at(0); right <- at(1) yield function(left, right)
      def test(function: (BigInt, BigInt) => Boolean): Option[BigInt] =
        pair((left, right) => if function(left, right) then BigInt(1) else BigInt(0))
      def fixedWidth(value: Expr[?]): Option[Int] =
        val bounds = DigitalScalarContract.widthBounds(scalar(value))
        Option.when(bounds._1 == bounds._2 && bounds._2 <= 4096L)(bounds._1.toInt)
      def bits(value: BigInt, width: Int): BigInt = value & ((BigInt(1) << width) - 1)
      def decoded(value: BigInt, width: Int): BigInt =
        val encoded = bits(value, width)
        if expression.resultType.exists(_.kind == "SInt") && encoded.testBit(width - 1) then
          encoded - (BigInt(1) << width)
        else encoded
      val result = operation match
        case "digital_add" => pair(_ + _)
        case "digital_sub" => pair(_ - _)
        case "digital_mul" => pair(_ * _)
        case "digital_neg" if operands.size == 1 => at(0).map(-_)
        case "digital_eq" => test(_ == _)
        case "digital_ne" => test(_ != _)
        case "digital_lt" => test(_ < _)
        case "digital_le" => test(_ <= _)
        case "digital_gt" => test(_ > _)
        case "digital_ge" => test(_ >= _)
        case "bool_and" => test((left, right) => left != 0 && right != 0)
        case "bool_or" => test((left, right) => left != 0 || right != 0)
        case "bool_not" if operands.size == 1 =>
          at(0).map(value => if value == 0 then BigInt(1) else BigInt(0))
        case "digital_and" => pair(_ & _)
        case "digital_or" => pair(_ | _)
        case "digital_xor" => pair(_ ^ _)
        case "digital_not" if operands.size == 1 =>
          for value <- at(0); size <- fixedWidth(expression) yield decoded(~value, size)
        case "digital_concat" if operands.size == 2 =>
          for
            left <- at(0); right <- at(1); size <- fixedWidth(operands(1));
            leftSize <- fixedWidth(operands(0))
          yield (bits(left, leftSize) << size) | bits(right, size)
        case "digital_extract" if operands.size == 3 =>
          for
            value <- at(0); lower <- at(1); upper <- at(2)
            if lower >= 0 && upper > lower && upper <= 4096
          yield bits(value >> lower.toInt, (upper - lower).toInt)
        case "digital_select" if operands.size == 3 =>
          at(0).flatMap(value => at(if value != 0 then 1 else 2))
        case "digital_shl" if operands.size == 2 =>
          for value <- at(0); amount <- at(1) if amount >= 0 && amount <= 4096
          yield value << amount.toInt
        case "digital_shr" if operands.size == 2 =>
          for value <- at(0); amount <- at(1) if amount >= 0 && amount <= Int.MaxValue
          yield value >> amount.toInt
        case "digital_logical_shr" if operands.size == 2 =>
          for
            value <- at(0); amount <- at(1); size <- fixedWidth(operands(0))
            if amount >= 0 && amount <= Int.MaxValue
          yield decoded(bits(value, size) >> amount.toInt, size)
        case _ => None
      result.filter(_.bitLength <= 4096)
