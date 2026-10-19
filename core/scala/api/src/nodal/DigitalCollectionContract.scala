package nodal

/** Exact collection signatures and type rules. ConstructionSession remains the sole owner of
  * expression identities, lexical scopes and captured callback regions.
  */
private[nodal] object DigitalCollectionContract:
  val Operation = "digital_collection"
  val Argument = "collection_argument"
  val Kinds: Set[String] = Set("map", "zip_with", "fold_left", "scan_left") ++
    DigitalReductionContract.Kinds

  def fail(number: Int, message: String, semanticPath: Option[String] = None): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic(
        f"NODAL-COLLECTION-055-$number%03d",
        message,
        semanticPath
      ))
    ).get

  def descriptor(value: Expr[?]): KernelTypeDescriptor =
    CandidateRuntime.expressionDataType(value).map(CandidateRuntime.typeDescriptor).getOrElse(
      fail(1, "collection values require exact types")
    )

  def vector(value: Expr[?]): (DataType[?], Vector[Dimension]) =
    val dataType = descriptor(value)
    dataType.arguments match
      case Vector(element: DataType[?], dimensions: Seq[?]) if dataType.kind == "Vec" =>
        if dimensions.isEmpty then fail(1, "collection input requires a positive-rank Vec")
        (
          element,
          dimensions.toVector.map:
            case value: Int => value
            case value: Expr[?] => value.asInstanceOf[Expr[Integer]]
            case _ => fail(1, "collection dimensions require static Integer values")
        )
      case _ => fail(1, "collection input requires an exact Vec type")

  def arguments(kind: String, inputs: Vector[Expr[?]], seed: Option[Expr[?]])
      : Vector[(DataType[?], String)] =
    if !Kinds.contains(kind) || inputs.size != (if kind == "zip_with" then 2 else 1) ||
      seed.nonEmpty != Set("fold_left", "scan_left").contains(kind)
    then fail(1, "collection kind, input count and seed disagree")
    val vectors = inputs.map(vector)
    if kind == "zip_with" then
      // Use the existing exact static identity algebra, never matching defaults/cardinality.
      val left = KernelTypeDescriptor("Vec", Vector(Bool, vectors(0)._2))
      val right = KernelTypeDescriptor("Vec", Vector(Bool, vectors(1)._2))
      if !DigitalEnumContract.sameType(left, right) then
        fail(2, "zipWith requires exactly equal dimensions, including symbolic identity")
    seed match
      case Some(initial) => Vector(
          CandidateRuntime.dataTypeFromDescriptor(descriptor(initial)) -> "accumulator",
          vectors.head._1 -> "element"
        )
      case None => vectors.zipWithIndex.map((value, index) =>
          value._1 -> (if vectors.size == 1 then "element" else s"element$index")
        )

  def result(
      kind: String,
      input: Expr[?],
      seed: Option[Expr[?]],
      yielded: Expr[?]
  ): KernelTypeDescriptor =
    val yieldedType = descriptor(yielded)
    seed.foreach: initial =>
      if !DigitalEnumContract.sameType(descriptor(initial), yieldedType) then
        fail(
          3,
          "fold/scan callback must yield exactly the complete seed type; use explicit conversion"
        )
    val elementType = seed.map(descriptor).getOrElse(yieldedType)
    if kind == "fold_left" then elementType
    else
      KernelTypeDescriptor(
        "Vec",
        Vector(CandidateRuntime.dataTypeFromDescriptor(elementType), vector(input)._2)
      )

  def map[A <: Data, B <: Data](value: Expr[Vec[A]], function: Expr[A] => Expr[B])
      : Expr[Vec[B]] =
    DigitalCollectionCallback.requireChecked(function)
    ConstructionKernel.collection("map", Vector(value), None): arguments =>
      function(arguments.head.asInstanceOf[Expr[A]])
    .asInstanceOf[Expr[Vec[B]]]

  def zipWith[A <: Data, B <: Data, C <: Data](
      value: Expr[Vec[A]],
      other: Expr[Vec[B]],
      function: (Expr[A], Expr[B]) => Expr[C]
  ): Expr[Vec[C]] =
    DigitalCollectionCallback.requireChecked(function)
    ConstructionKernel.collection("zip_with", Vector(value, other), None): arguments =>
      function(arguments(0).asInstanceOf[Expr[A]], arguments(1).asInstanceOf[Expr[B]])
    .asInstanceOf[Expr[Vec[C]]]

  def fold[A <: Data, B <: Data](
      value: Expr[Vec[A]],
      seed: Expr[B],
      function: (Expr[B], Expr[A]) => Expr[B],
      scan: Boolean
  ): Expr[?] =
    DigitalCollectionCallback.requireChecked(function)
    ConstructionKernel.collection(
      if scan then "scan_left" else "fold_left",
      Vector(value),
      Some(seed)
    ): arguments =>
      function(arguments(0).asInstanceOf[Expr[B]], arguments(1).asInstanceOf[Expr[A]])

  def reduce[A <: Data](
      value: Expr[Vec[A]],
      function: (Expr[A], Expr[A]) => Expr[A],
      balanced: Boolean
  ): Expr[A] =
    DigitalCollectionCallback.requireChecked(function)
    ConstructionKernel.collection(
      if balanced then "reduce_balanced" else "reduce_left",
      Vector(value),
      None
    ): arguments =>
      function(arguments(0).asInstanceOf[Expr[A]], arguments(1).asInstanceOf[Expr[A]])
    .asInstanceOf[Expr[A]]
