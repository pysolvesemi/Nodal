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

  export StructuralShapeCoordinates.{Coordinate, Region}
  import StructuralShapeCoordinates.{atom, checked, product}
  import Coordinate.*

  private def invalid(message: String, path: String): Nothing =
    reject(Diagnostic("NODAL-ANALOG-033-021", message, Some(path)))

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

  final case class Access(
      root: Variable,
      region: Region,
      ancestors: Set[Region],
      whole: Boolean
  )

  private def region(root: Variable, projections: Vector[StorageProjection]): Region =
    StructuralShapeCoordinates.region(
      root.valueType.dimensions,
      projections.map(StructuralShapeCoordinates.projection)
    )

  def normalize(reference: StorageReference): Access =
    try normalizeReference(reference)
    catch
      case error: StructuralShapeCoordinates.InvalidCoordinate =>
        invalid(error.getMessage, reference.root.identity)

  private def normalizeReference(reference: StorageReference): Access =
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
