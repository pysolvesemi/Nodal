package nodal

import scala.collection.mutable

/** Finite reduction transfer/geometry shared by construction and independent snapshot admission.
  * The graph adapter reads the actual owner DAG; no profile or proof is serialized.
  */
private[nodal] object DigitalReductionContract:
  val Kinds: Set[String] = Set("reduce_left", "reduce_balanced")
  val MaximumWork = 65536
  val MaximumArguments = 4098

  enum Transfer[+A]:
    case Invariant
    case Fixed(value: A)
    case Maximum(growth: A)
    case Sum(growth: A)

  trait IntegerGraph[A]:
    def charge(work: Int): Unit = ()
    def same(left: A, right: A): Boolean
    def operation(value: A): Option[(String, Vector[A])]
    def literal(value: Long): A
    def bounds(value: A): (Long, Long)
    def add(left: A, right: A): A
    def subtract(left: A, right: A): A
    def multiply(left: A, right: A): A
    def maximum(left: A, right: A): A
    def ceilLog2(value: A): A
    def chooseZero(test: A, whenZero: A, whenNonzero: A): A

  private def fail(message: String): Nothing = DigitalCollectionContract.fail(7, message)

  def infer[A](
      yielded: A,
      left: A,
      right: A,
      formals: Vector[A],
      graph: IntegerGraph[A]
  ): Transfer[A] =
    var work = 0
    def charge(depth: Int): Unit =
      work += chargedUnit(1L)
      graph.charge(1)
      if work > MaximumWork || depth > 512 then fail("reduction transfer exceeds analysis budget")
    def dependent(value: A, depth: Int): Boolean =
      charge(depth)
      graph.charge(formals.size)
      formals.exists(graph.same(value, _)) ||
      graph.operation(value).exists(_._2.exists(dependent(_, depth + 1)))
    if graph.same(yielded, left) || graph.same(yielded, right) then Transfer.Invariant
    else if !dependent(yielded, 0) then
      if graph.bounds(yielded)._1 <= 0 then fail("fixed reduction width must stay positive")
      Transfer.Fixed(yielded)
    else
      val terms = mutable.ArrayBuffer.empty[A]
      def flatten(value: A, depth: Int): Unit =
        charge(depth)
        graph.operation(value) match
          case Some(("analog_add", Vector(a, b))) =>
            flatten(a, depth + 1)
            flatten(b, depth + 1)
          case _ => terms += value
      flatten(yielded, 0)
      val (variable, constant) = terms.toVector.partition(dependent(_, 0))
      def pair(a: A, b: A): Boolean = (graph.same(a, left) && graph.same(b, right)) ||
        (graph.same(a, right) && graph.same(b, left))
      val growth = constant.reduceOption(graph.add).getOrElse(graph.literal(0))
      if graph.bounds(growth)._1 < 0 then fail("reduction growth must remain nonnegative")
      variable match
        case Vector(a, b) if pair(a, b) => Transfer.Sum(growth)
        case Vector(value) => graph.operation(value) match
            case Some(("static_integer_max", Vector(a, b))) if pair(a, b) =>
              Transfer.Maximum(growth)
            case _ => fail("unsupported coupled or non-finite reduction width transfer")
        case _ => fail("unsupported coupled or non-finite reduction width transfer")

  def formula[A](
      transfer: Transfer[A],
      initial: A,
      count: A,
      balanced: Boolean,
      graph: IntegerGraph[A]
  ): A = transfer match
    case Transfer.Invariant => initial
    case Transfer.Fixed(value) =>
      graph.chooseZero(graph.subtract(count, graph.literal(1)), initial, value)
    case Transfer.Maximum(growth) =>
      val steps = if balanced then graph.ceilLog2(count)
      else graph.subtract(count, graph.literal(1))
      graph.add(initial, graph.multiply(steps, growth))
    case Transfer.Sum(growth) =>
      graph.add(
        graph.multiply(count, initial),
        graph.multiply(graph.subtract(count, graph.literal(1)), growth)
      )

  final case class SizeRange(lower: Long, upper: Long)

  /** A bounded superset of all reachable child sizes over a contiguous legal count envelope. No
    * lane is materialized; wide geometry never constructs a signed 2^63 subtree.
    */
  def contexts(minimum: Long, maximum: Long, balanced: Boolean)
      : Vector[(SizeRange, SizeRange)] =
    if minimum <= 0 || maximum < minimum then fail("reduction count must remain positive")
    val one = SizeRange(1, 1)
    if maximum == 1 then Vector.empty
    else if !balanced then
      Vector(one -> one) ++ Option.when(maximum >= 3)(SizeRange(2, maximum - 1) -> one)
    else
      val result = mutable.ArrayBuffer.empty[(SizeRange, SizeRange)]
      var block = BigInt(1)
      val low = BigInt(minimum)
      val high = BigInt(maximum)
      while block < high do
        val full = SizeRange(block.toLong, block.toLong)
        val period = block * 2
        if high >= period then result += full -> full
        // Intersect the count interval with the first relevant partial-tail interval.
        val firstCycle = low / period
        val firstEnd = firstCycle * period + period - 1
        val cycle = if firstEnd < low then firstCycle + 1 else firstCycle
        val start = (cycle * period + block + 1).max(low)
        val end = (cycle * period + period - 1).min(high)
        if start <= end then
          val nextStart = (cycle + 1) * period + block + 1
          val lower = if nextStart <= high then BigInt(1) else start - cycle * period - block
          val upper = if nextStart <= high then block - 1 else end - cycle * period - block
          if lower <= 1 && upper >= 1 then result += full -> one
          if upper >= 2 then
            result += full -> SizeRange(lower.max(2).toLong, upper.toLong)
        block *= 2
      result.toVector.distinct

  def schemaWork(descriptor: KernelTypeDescriptor): Int =
    var count = 0
    def visit(value: KernelTypeDescriptor, depth: Int): Unit =
      count += chargedUnit(1L + value.arguments.size.toLong)
      if count > MaximumWork || depth > 128 then fail("reduction type exceeds structural budget")
      value.arguments.foreach:
        case nested: DataType[?] => visit(CandidateRuntime.typeDescriptor(nested), depth + 1)
        case fields: Seq[?] => visitSchemaFields(fields):
            case field: StructField[?] =>
              visit(CandidateRuntime.typeDescriptor(field.dataType), depth + 1)
            case _ => ()
        case _ => ()
    visit(descriptor, 0)
    count

  /** Canonical scalar inventory walks an inner Vec schema once, preserving every other type fact.
    */
  def widths(descriptor: KernelTypeDescriptor): Vector[Dimension] =
    val result = mutable.ArrayBuffer.empty[Dimension]
    mapWidths(descriptor): value =>
      if result.size >= (MaximumArguments - 2) / 2 then
        fail("reduction paired width signature exceeds the 4098 argument budget")
      result += value
      value
    result.toVector

  def mapWidths(descriptor: KernelTypeDescriptor)(replace: Dimension => Dimension)
      : KernelTypeDescriptor =
    var work = 0
    def visit(value: KernelTypeDescriptor, depth: Int): KernelTypeDescriptor =
      work += chargedUnit(1L)
      if work > MaximumWork || depth > 128 then fail("reduction type exceeds structural budget")
      value.kind match
        case "Bits" | "UInt" | "SInt" =>
          if value.arguments.size != 1 then fail("reduction scalar requires one width")
          val width = value.arguments.head match
            case number: Int => number
            case expression: Expr[?] => expression.asInstanceOf[Expr[Integer]]
            case _ => fail("reduction scalar width is not static Integer")
          value.copy(arguments = Vector(replace(width)))
        case "Vec" => value.arguments match
            case Vector(element: DataType[?], dimensions: Seq[?]) =>
              value.copy(arguments =
                Vector(
                  CandidateRuntime.dataTypeFromDescriptor(visit(
                    CandidateRuntime.typeDescriptor(element),
                    depth + 1
                  )),
                  dimensions
                )
              )
            case _ => fail("reduction Vec requires an exact element and dimensions")
        case "Struct" => value.arguments match
            case Vector(name: String, fields: Seq[?]) =>
              val mapped = orderedFields(fields).map:
                case field: StructField[?] =>
                  StructField(
                    field.name,
                    CandidateRuntime.dataTypeFromDescriptor(
                      visit(CandidateRuntime.typeDescriptor(field.dataType), depth + 1)
                    )
                  )
                case _ => fail("reduction Struct requires ordered fields")
              value.copy(arguments = Vector(name, mapped))
            case _ => fail("reduction Struct requires a nominal schema")
        case _ => value
    visit(descriptor, 0)

  /** Context-dependent obligations of already captured operations, without replaying the callback
    * or allocating another value DAG. Ordinary constructors own context-independent opcode rules.
    */
  def validateBodyValue(expression: KernelExpr[?]): Unit =
    val operands = expression.operands.collect { case value: Expr[?] => value }
    def descriptor(value: Expr[?]): KernelTypeDescriptor =
      DigitalCollectionContract.descriptor(value)
    def exact(left: Expr[?], right: Expr[?]): Unit =
      if !DigitalEnumContract.sameType(descriptor(left), descriptor(right)) then
        fail("reduction body requires exact complete operand types in every reachable context")
    val operation = expression.operation.getOrElse("")
    if Set("digital_and", "digital_or", "digital_xor").contains(operation) then
      exact(operands(0), operands(1))
    if Set("digital_eq", "digital_ne").contains(operation) &&
      descriptor(operands.head).kind == "Bits"
    then exact(operands(0), operands(1))
    if operation == "digital_select" then exact(operands(1), operands(2))
    if operation == DigitalEnumContract.Prefix + "select" then
      operands.drop(2).foreach(exact(operands(1), _))
    if Set(DigitalEnumContract.Prefix + "decode", DigitalEnumContract.Prefix + "is_valid")
        .contains(operation) && operands.nonEmpty && descriptor(operands.head).kind != "Enum"
    then
      val abi = expression.operands.collectFirst { case value: EnumOperationMetadata =>
        value.abi
      }.get
      if !ConstructionKernel.digitalWidthEquals(descriptor(operands.head), abi.width) then
        fail("enum representation width is not exact in every reachable reduction context")
    if operation == "digital_div" || operation == "digital_rem" then
      if !DigitalScalarContract.knownValue(operands(1)).exists(_ != 0) then
        fail("reduction divisor is not provably nonzero in every reachable context")
    if operation == "digital_extract" then
      val upper = DigitalScalarContract.knownValue(operands(2)).getOrElse(
        fail("reduction extraction bound is not static")
      )
      if upper > DigitalScalarContract.widthBounds(descriptor(operands.head))._1 then
        fail("reduction extraction exceeds a reachable source width")
    if DigitalScalarContract.Operations.contains(operation) then
      DigitalScalarContract.validateCaptured(expression)
    expression.literal.foreach: literal =>
      if Set("Bits", "UInt", "SInt").contains(literal.dataType.kind) then
        DigitalValueContract.validateLiteral(
          literal.dataType.kind,
          BigInt(literal.value),
          DigitalScalarContract.widthBounds(literal.dataType)._1
        )

  // Keep the existing source-bearing replace(width) callsite physically stable above.
  private def chargedUnit(units: Long): Int =
    ConstructionKernel.chargeBoundaryWork(units)
    1

  private def visitSchemaFields(fields: Seq[?])(visit: Any => Unit): Unit =
    if ConstructionKernel.boundaryWorkActive then
      val iterator = fields.iterator
      while iterator.hasNext do
        ConstructionKernel.chargeBoundaryWork(1L)
        visit(iterator.next())
    else fields.foreach(visit)

  private def orderedFields(fields: Seq[?]): Vector[Any] =
    if ConstructionKernel.boundaryWorkActive then
      val result = Vector.newBuilder[Any]
      val iterator = fields.iterator
      while iterator.hasNext do
        ConstructionKernel.chargeBoundaryWork(1L)
        result += iterator.next()
      result.result()
    else fields.toVector
