package nodal

/** Finite definite-assignment facts shared by straight-line and structured procedural analysis.
  *
  * Coordinates are normalized to one row-major logical ordinal. A reshape therefore does not alter
  * the map, while a slice composes its stride and offset with the immediate input map. The model
  * stores authored regions, never lanes; fixed scalar promotion only counts authored literal keys.
  * Static tokens are existing owned expression identities, not evaluated parameter defaults.
  */
private[nodal] object AnalogStorageCoverage:
  import AnalogProceduralRuntime.*

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
  private def coordinateKey(value: Coordinate): Vector[(Int, String, Int, Int)] =
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

  private def ordered(values: Vector[Coordinate]): Vector[Coordinate] =
    values.map(value => coordinateKey(value) -> value).sortWith: (left, right) =>
      val difference = left._1.iterator.zip(right._1.iterator)
        .map((a, b) => summon[Ordering[(Int, String, Int, Int)]].compare(a, b))
        .find(_ != 0).getOrElse(left._1.size.compare(right._1.size))
      difference < 0
    .map(_._2)

  private def invalid(message: String, path: String): Nothing =
    reject(Diagnostic("NODAL-ANALOG-033-021", message, Some(path)))

  private def checked(value: BigInt): Coordinate =
    if !value.isValidLong then
      invalid("storage coordinate arithmetic exceeds signed 64-bit range", "storage-coordinate")
    Literal(value.toLong)

  private def atom(value: String): Coordinate =
    value.toLongOption match
      case Some(number) => Literal(number)
      case None if value.matches("-?[0-9]+") => checked(BigInt(value))
      case None =>
        if value.isEmpty || value.trim != value then
          invalid("storage coordinate requires a canonical static identity", value)
        Static(value)

  // Associative literal folding also makes separately authored prefix/slice aliases equal.
  // Exact static identities remain opaque; this does not infer symbolic algebraic equivalence.
  private def add(left: Coordinate, right: Coordinate): Coordinate =
    def terms(value: Coordinate): Vector[Coordinate] = value match
      case Add(a, b) => terms(a) ++ terms(b)
      case other => Vector(other)
    val all = terms(left) ++ terms(right)
    val constant = all.collect { case Literal(value) => BigInt(value) }.sum
    val symbolic = ordered(all.filter { case Literal(_) => false; case _ => true })
    val values = symbolic ++ Option.when(constant != 0)(checked(constant))
    values.reduceOption(Add.apply).getOrElse(Literal(0))

  private def multiply(left: Coordinate, right: Coordinate): Coordinate =
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

  private def subtract(left: Coordinate, right: Coordinate): Coordinate = (left, right) match
    case (value, Literal(0)) => value
    case _ if left == right => Literal(0)
    case (Literal(a), Literal(b)) => checked(BigInt(a) - BigInt(b))
    case _ => Subtract(left, right)

  private def divide(left: Coordinate, right: Coordinate): Coordinate = (left, right) match
    case (_, Literal(0)) => invalid("storage coordinate divides by zero", "storage-coordinate")
    case (Literal(0), _) => Literal(0)
    case (value, Literal(1)) => value
    case (Literal(a), Literal(b)) => checked(BigInt(a) / BigInt(b))
    case _ if left == right => Literal(1)
    case _ => Divide(left, right)

  private def product(dimensions: Vector[String]): Coordinate =
    dimensions.foldLeft[Coordinate](Literal(1))((value, dimension) =>
      multiply(value, atom(dimension))
    )

  private def dimensions(projection: StorageProjection): Vector[String] = projection match
    case value: KernelShapeIndexSnapshot => value.resultDimensions
    case value: KernelShapeViewSnapshot => value.dimensions

  private def validateDimensions(values: Vector[String], path: String): Unit =
    values.foreach: value =>
      atom(value) match
        case Literal(number) if number <= 0 =>
          invalid("storage dimensions must be positive", path)
        case _ => ()
    product(values)

  /** A canonical region is independent of authored path and logical rank. Its ordinal domain and
    * root mapping preserve order, including a noncontiguous slice followed by reshape.
    */
  final case class Region(cardinality: Coordinate, rootOrdinal: Coordinate):
    private lazy val key = (coordinateKey(cardinality), coordinateKey(rootOrdinal))
    override def equals(other: Any): Boolean = other match
      case that: Region => key == that.key
      case _ => false
    override def hashCode(): Int = key.hashCode()

  final case class Access(
      root: Variable,
      region: Region,
      ancestors: Set[Region],
      whole: Boolean
  )

  private def region(root: Variable, projections: Vector[StorageProjection]): Region =
    val shapes = projections.scanLeft(root.valueType.dimensions)((_, projection) =>
      dimensions(projection)
    )
    val count = product(shapes.last)
    var ordinal: Coordinate = if count == Literal(1) then Literal(0) else Ordinal
    projections.indices.reverse.foreach: index =>
      val inputShape = shapes(index)
      val outputShape = shapes(index + 1)
      projections(index) match
        case selection: KernelShapeIndexSnapshot =>
          var prefix: Coordinate = Literal(0)
          selection.indices.zip(inputShape).foreach: (coordinate, extent) =>
            prefix = add(multiply(prefix, atom(extent)), atom(coordinate))
          ordinal = add(multiply(prefix, product(outputShape)), ordinal)
        case view: KernelShapeViewSnapshot =>
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

  def normalize(reference: StorageReference): Access =
    val root = reference.root
    validateDimensions(root.valueType.dimensions, root.identity)
    val projections = reference match
      case _: Variable => Vector.empty
      case selection: StorageSelection => selection.projections
    var input = root.identity
    var inputShape = root.valueType.dimensions
    var fullDomain = true
    val seen = scala.collection.mutable.HashSet(root.identity)
    projections.foreach: projection =>
      val (path, owner, parent) = projection match
        case value: KernelShapeIndexSnapshot => (value.path, value.owner, value.input)
        case value: KernelShapeViewSnapshot => (value.path, value.owner, value.input)
      if owner != root.owner || parent != input || !seen.add(path) then
        invalid("storage projection must retain its root owner and acyclic immediate input", path)
      val outputShape = dimensions(projection)
      validateDimensions(outputShape, path)
      projection match
        case value: KernelShapeIndexSnapshot =>
          if value.indices.isEmpty || value.indices.size > inputShape.size ||
            outputShape != inputShape.drop(value.indices.size)
          then invalid("storage prefix index has an incompatible result shape", path)
          value.indices.zip(inputShape).foreach: (coordinate, extent) =>
            (atom(coordinate), atom(extent)) match
              case (Literal(position), _) if position < 0 =>
                invalid("storage index must be nonnegative", path)
              case (Literal(position), Literal(size)) if position >= size =>
                invalid("storage index is outside its input dimension", path)
              case _ => ()
          fullDomain = fullDomain && value.indices.zip(inputShape).forall { (coordinate, extent) =>
            atom(coordinate) == Literal(0) && atom(extent) == Literal(1)
          }
        case value: KernelShapeViewSnapshot => value.slice match
            case None =>
              (product(inputShape), product(outputShape)) match
                case (Literal(before), Literal(after)) if before != after =>
                  invalid("storage reshape changes element cardinality", path)
                case _ => () // Symbolic cardinality is proved by the shared shape constructor.
            case Some(slice) =>
              if slice.axis < 0 || slice.axis >= inputShape.size ||
                outputShape.size != inputShape.size ||
                inputShape.indices.exists(axis =>
                  axis != slice.axis &&
                    inputShape(axis) != outputShape(axis)
                )
              then invalid("storage slice has an incompatible rank or unselected dimension", path)
              (atom(slice.lower), atom(slice.upperExclusive), atom(inputShape(slice.axis))) match
                case (Literal(lower), Literal(upper), Literal(extent)) =>
                  if lower < 0 || upper <= lower || upper > extent ||
                    atom(outputShape(slice.axis)) != checked(BigInt(upper) - BigInt(lower))
                  then invalid("storage slice has invalid bounds or result extent", path)
                case _ => () // Symbolic bounds have already passed structural bound analysis.
              fullDomain = fullDomain && atom(slice.lower) == Literal(0) &&
                atom(slice.upperExclusive) == atom(inputShape(slice.axis))
      input = path
      inputShape = outputShape
    val normalized = region(root, projections)
    val ancestors = projections.indices.map(index => region(root, projections.take(index))).toSet
    val sameFixedSingleton = product(root.valueType.dimensions) == Literal(1) &&
      normalized == Region(Literal(1), Literal(0))
    Access(root, normalized, ancestors, fullDomain || sameFixedSingleton)

  enum Coverage:
    case Whole
    case Selected(regions: Set[Region])

  /** This is the only authoritative initialization state. Legacy whole-root result sets are derived
    * from it, and are never combined with a second shaped-state tracker.
    */
  final case class State private (roots: Map[String, Coverage]):
    def wholeRoots: Set[String] = roots.collect { case (root, Coverage.Whole) => root }.toSet
    def contains(root: String): Boolean = roots.get(root).contains(Coverage.Whole)
    def contains(reference: StorageReference): Boolean =
      val access = normalize(reference)
      roots.get(access.root.identity) match
        case Some(Coverage.Whole) => true
        case Some(Coverage.Selected(regions)) =>
          regions.contains(access.region) || access.ancestors.exists(regions.contains)
        case None => false

    def +(root: String): State = State(roots.updated(root, Coverage.Whole))
    def -(root: String): State = State(roots - root)
    def --(removed: Set[String]): State = State(roots -- removed)

    def write(reference: StorageReference): State =
      val access = normalize(reference)
      val identity = access.root.identity
      if contains(identity) || access.whole then this + identity
      else
        val previous = roots.get(identity) match
          case Some(Coverage.Selected(regions)) => regions
          case _ => Set.empty[Region]
        val regions = previous + access.region
        val offsets = regions.collect:
          case Region(Literal(1), Literal(offset)) => offset
        val complete = product(access.root.valueType.dimensions) match
          case Literal(count) =>
            offsets.forall(offset => offset >= 0 && offset < count) && offsets.size.toLong == count
          case _ => false
        if complete then this + identity
        else State(roots.updated(identity, Coverage.Selected(regions)))

    def intersect(other: State): State =
      State(roots.iterator.flatMap: (root, left) =>
        other.roots.get(root).flatMap: right =>
          val common = (left, right) match
            case (Coverage.Whole, value) => Some(value)
            case (value, Coverage.Whole) => Some(value)
            case (Coverage.Selected(a), Coverage.Selected(b)) =>
              val intersection = a intersect b
              Option.when(intersection.nonEmpty)(Coverage.Selected(intersection))
          common.map(root -> _)
      .toMap)

  object State:
    val empty: State = State(Map.empty)
    def whole(roots: Set[String]): State = State(roots.iterator.map(_ -> Coverage.Whole).toMap)
