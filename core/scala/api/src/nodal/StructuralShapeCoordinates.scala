package nodal

/** Shared pure row-major shape maps. Static atoms are opaque owned identities; no parameter
  * defaults, rendered arithmetic, storage state, or conservative endpoint registry is consulted.
  */
private[nodal] object StructuralShapeCoordinates:
  enum Projection:
    case Index(indices: Vector[String], resultDimensions: Vector[String])
    case View(dimensions: Vector[String], slice: Option[KernelShapeSliceSnapshot])

  def projection(value: KernelShapeIndexSnapshot | KernelShapeViewSnapshot): Projection =
    value match
      case index: KernelShapeIndexSnapshot =>
        Projection.Index(index.indices, index.resultDimensions)
      case view: KernelShapeViewSnapshot => Projection.View(view.dimensions, view.slice)
  final class InvalidCoordinate(message: String) extends IllegalArgumentException(message)

  enum Coordinate:
    case Literal(value: Long)
    case Static(identity: String)
    case Ordinal
    case Add(left: Coordinate, right: Coordinate)
    case Multiply(left: Coordinate, right: Coordinate)
    case Subtract(left: Coordinate, right: Coordinate)
    case Divide(left: Coordinate, right: Coordinate)
    case Remainder(left: Coordinate, right: Coordinate)

  import Coordinate.*

  // Exact postorder DAG encoding keeps shared coordinate subexpressions compact. The
  // tables live only for this traversal; node equality includes the complete static token.
  def coordinateKey(value: Coordinate): Vector[(Int, String, Int, Int)] =
    val visited = new java.util.IdentityHashMap[Coordinate, java.lang.Integer]()
    val interned = scala.collection.mutable.HashMap.empty[(Int, String, Int, Int), Int]
    val nodes = scala.collection.mutable.ArrayBuffer.empty[(Int, String, Int, Int)]
    def visit(current: Coordinate): Int =
      val known = visited.get(current)
      if known != null then known.intValue
      else
        val node = current match
          case Literal(number) => (0, number.toString, -1, -1)
          case Static(identity) => (1, identity, -1, -1)
          case Ordinal => (2, "", -1, -1)
          case Add(left, right) => (3, "", visit(left), visit(right))
          case Multiply(left, right) => (4, "", visit(left), visit(right))
          case Subtract(left, right) => (5, "", visit(left), visit(right))
          case Divide(left, right) => (6, "", visit(left), visit(right))
          case Remainder(left, right) => (7, "", visit(left), visit(right))
        val index = interned.getOrElseUpdate(
          node, {
            nodes += node
            nodes.size - 1
          }
        )
        visited.put(current, java.lang.Integer.valueOf(index))
        index
    visit(value)
    nodes.toVector

  def ordered(values: Vector[Coordinate]): Vector[Coordinate] =
    values.map(value => coordinateKey(value) -> value).sortWith: (left, right) =>
      val difference = left._1.iterator.zip(right._1.iterator)
        .map((a, b) => summon[Ordering[(Int, String, Int, Int)]].compare(a, b))
        .find(_ != 0).getOrElse(left._1.size.compare(right._1.size))
      difference < 0
    .map(_._2)

  def invalid(message: String, path: String): Nothing =
    throw new InvalidCoordinate(s"$message at $path")

  def checked(value: BigInt): Coordinate =
    if !value.isValidLong then
      invalid("shape coordinate arithmetic exceeds signed 64-bit range", "shape-coordinate")
    Literal(value.toLong)

  def atom(value: String): Coordinate =
    value.toLongOption match
      case Some(number) => Literal(number)
      case None if value.matches("-?[0-9]+") => checked(BigInt(value))
      case None =>
        if value.isEmpty || value.trim != value then
          invalid("shape coordinate requires a canonical static identity", value)
        Static(value)

  // Associative literal folding also makes separately authored prefix/slice aliases equal.
  // Exact static identities remain opaque; this does not infer symbolic algebraic equivalence.
  def add(left: Coordinate, right: Coordinate): Coordinate =
    def terms(value: Coordinate): Vector[Coordinate] = value match
      case Add(a, b) => terms(a) ++ terms(b)
      case other => Vector(other)
    val all = terms(left) ++ terms(right)
    val constant = all.collect { case Literal(value) => BigInt(value) }.sum
    val symbolic = ordered(all.filter { case Literal(_) => false; case _ => true })
    val values = symbolic ++ Option.when(constant != 0)(checked(constant))
    values.reduceOption(Add.apply).getOrElse(Literal(0))

  def multiply(left: Coordinate, right: Coordinate): Coordinate =
    def factors(value: Coordinate): Vector[Coordinate] = value match
      case Multiply(a, b) => factors(a) ++ factors(b)
      case other => Vector(other)
    val all = factors(left) ++ factors(right)
    val constant = all.collect { case Literal(value) => BigInt(value) }.product
    if constant == 0 then Literal(0)
    else
      val symbolic = ordered(all.filter { case Literal(_) => false; case _ => true })
      val values = symbolic ++ Option.when(constant != 1)(checked(constant))
      values.reduceOption(Multiply.apply).getOrElse(Literal(1))

  def subtract(left: Coordinate, right: Coordinate): Coordinate = (left, right) match
    case (value, Literal(0)) => value
    case _ if coordinateKey(left) == coordinateKey(right) => Literal(0)
    case (Literal(a), Literal(b)) => checked(BigInt(a) - BigInt(b))
    case _ => Subtract(left, right)

  def divide(left: Coordinate, right: Coordinate): Coordinate = (left, right) match
    case (_, Literal(0)) => invalid("shape coordinate divides by zero", "shape-coordinate")
    case (Literal(0), _) => Literal(0)
    case (value, Literal(1)) => value
    case (Literal(a), Literal(b)) => checked(BigInt(a) / BigInt(b))
    case _ if coordinateKey(left) == coordinateKey(right) => Literal(1)
    case _ => Divide(left, right)

  def product(dimensions: Vector[String]): Coordinate =
    dimensions.foldLeft[Coordinate](Literal(1))((value, dimension) =>
      multiply(value, atom(dimension))
    )

  def dimensions(projection: Projection): Vector[String] = projection match
    case value: Projection.Index => value.resultDimensions
    case value: Projection.View => value.dimensions

  /** A canonical region is independent of authored path and logical rank. Its ordinal domain and
    * root mapping preserve order, including a noncontiguous slice followed by reshape.
    */
  final case class Region(cardinality: Coordinate, rootOrdinal: Coordinate):
    private lazy val key = (coordinateKey(cardinality), coordinateKey(rootOrdinal))
    override def equals(other: Any): Boolean = other match
      case that: Region => key == that.key
      case _ => false
    override def hashCode(): Int = key.hashCode()

  def region(rootDimensions: Vector[String], projections: Vector[Projection]): Region =
    val shapes = projections.scanLeft(rootDimensions)((_, projection) =>
      dimensions(projection)
    )
    val count = product(shapes.last)
    var ordinal: Coordinate = if count == Literal(1) then Literal(0) else Ordinal
    projections.indices.reverse.foreach: index =>
      val inputShape = shapes(index)
      val outputShape = shapes(index + 1)
      projections(index) match
        case selection: Projection.Index =>
          var prefix: Coordinate = Literal(0)
          selection.indices.zip(inputShape).foreach: (coordinate, extent) =>
            prefix = add(multiply(prefix, atom(extent)), atom(coordinate))
          ordinal = add(multiply(prefix, product(outputShape)), ordinal)
        case view: Projection.View =>
          view.slice.foreach: slice =>
            val trailing = product(inputShape.drop(slice.axis + 1))
            val outputStride = multiply(atom(outputShape(slice.axis)), trailing)
            // Prefix groups are zero on the leading axis. This also avoids symbolic x / x.
            val groups = if slice.axis == 0 then Literal(0) else divide(ordinal, outputStride)
            val gap = multiply(
              subtract(atom(inputShape(slice.axis)), atom(outputShape(slice.axis))),
              trailing
            )
            ordinal = add(
              add(ordinal, multiply(atom(slice.lower), trailing)),
              multiply(groups, gap)
            )
    Region(count, ordinal)

  def remainder(left: Coordinate, right: Coordinate): Coordinate = (left, right) match
    case (_, Literal(0)) => invalid("shape coordinate divides by zero", "coordinate")
    case (Literal(0), _) | (_, Literal(1)) => Literal(0)
    case (Literal(a), Literal(b)) => checked(BigInt(a) % BigInt(b))
    case _ if coordinateKey(left) == coordinateKey(right) => Literal(0)
    case _ => Remainder(left, right)

  def ordinal(coordinates: Vector[Coordinate], shape: Vector[String]): Coordinate =
    coordinates.zip(shape).foldLeft[Coordinate](Literal(0)):
      case (value, (coordinate, extent)) => add(multiply(value, atom(extent)), coordinate)

  /** Retain direct/prefix axis facts rather than losing disjointness in a wide flat interval. */
  def rootCoordinates(
      rootDimensions: Vector[String],
      projections: Vector[Projection]
  ): Vector[Coordinate] =
    rootCoordinateMap(rootDimensions, projections)._1

  /** Re-radix only literal coordinates for conservative identity. In particular an opaque selector
    * in a singleton extent must not become literal zero through modulo-one folding.
    */
  def scalarRegion(rootDimensions: Vector[String], projections: Vector[Projection]): Region =
    val original = region(rootDimensions, projections)
    val (coordinates, literalRadices) = rootCoordinateMap(rootDimensions, projections)
    if literalRadices && coordinates.forall(coordinate =>
        !coordinateKey(coordinate).exists(node => node._1 == 6 || node._1 == 7)
      )
    then
      Region(original.cardinality, ordinal(coordinates, rootDimensions))
    else original

  private def rootCoordinateMap(
      rootDimensions: Vector[String],
      projections: Vector[Projection]
  ): (Vector[Coordinate], Boolean) =
    val shapes = projections.scanLeft(rootDimensions)((_, projection) => dimensions(projection))
    var coordinates = Vector.empty[Coordinate]
    var literalRadices = true
    projections.indices.reverse.foreach: index =>
      val input = shapes(index)
      val output = shapes(index + 1)
      projections(index) match
        case selection: Projection.Index =>
          coordinates = selection.indices.map(atom) ++ coordinates
        case view: Projection.View => view.slice match
            case Some(slice) =>
              coordinates =
                coordinates.updated(slice.axis, add(atom(slice.lower), coordinates(slice.axis)))
            case None =>
              literalRadices = literalRadices && coordinates.forall {
                case Literal(_) => true; case _ => false
              }
              val flat = ordinal(coordinates, output)
              coordinates = input.indices.map: axis =>
                remainder(divide(flat, product(input.drop(axis + 1))), atom(input(axis)))
              .toVector
    (coordinates, literalRadices)

  def bounds(value: Coordinate, static: String => (BigInt, BigInt)): (BigInt, BigInt) =
    val memo = new java.util.IdentityHashMap[Coordinate, (BigInt, BigInt)]()
    def visit(current: Coordinate): (BigInt, BigInt) =
      val known = memo.get(current)
      if known != null then known
      else
        val result = current match
          case Literal(number) => (BigInt(number), BigInt(number))
          case Static(identity) => static(identity)
          case Ordinal => invalid("unbound shape ordinal", "coordinate")
          case Add(left, right) =>
            val (a, b) = visit(left); val (c, d) = visit(right); (a + c, b + d)
          case Multiply(left, right) =>
            val (a, b) = visit(left); val (c, d) = visit(right)
            val products = Vector(a * c, a * d, b * c, b * d); (products.min, products.max)
          case Subtract(left, right) =>
            val (a, b) = visit(left); val (c, d) = visit(right); (a - d, b - c)
          case Divide(left, right) =>
            val (a, b) = visit(left); val (c, d) = visit(right)
            if a < 0 || c <= 0 then
              invalid("shape division requires nonnegative bounds", "coordinate")
            (a / d, b / c)
          case Remainder(left, right) =>
            val (a, b) = visit(left); val (c, d) = visit(right)
            if a < 0 || c <= 0 then
              invalid("shape remainder requires nonnegative bounds", "coordinate")
            if a == b && c == d then (a % c, a % c) else (BigInt(0), b.min(d - 1))
        memo.put(current, result)
        result
    visit(value)
