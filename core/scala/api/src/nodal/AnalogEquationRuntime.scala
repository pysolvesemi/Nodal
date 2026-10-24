package nodal

import scala.collection.mutable

/** Backend-neutral source-semantic recording for unordered analog equations and additive
  * potential/flow contributions.
  *
  * This runtime deliberately records authored expressions without orienting, dividing, solving, or
  * lowering them. Residual intent is structural: `lhs - rhs == 0`; the original left and right
  * expressions remain canonical source evidence.
  */
private[nodal] object AnalogEquationRuntime:

  enum RegionKind:
    case Equation, InitialEquation, Contribution, Procedural

  enum ValueKind:
    case Real, Boolean

  enum ContributionKind:
    case Potential, Flow

  final case class SourceSpan(file: String, line: Int, column: Int):
    require(file.nonEmpty, "source file must be non-empty")
    require(line >= 1, "source line must be positive")
    require(column >= 1, "source column must be positive")

  final case class Expression(
      rendered: String,
      dimension: String,
      valueKind: ValueKind = ValueKind.Real,
      root: Option[Expr[?]] = None
  ):
    require(rendered.nonEmpty, "expression text must be non-empty")
    require(dimension.nonEmpty, "expression dimension must be non-empty")

  final case class EquationIdentity(value: String):
    require(value.nonEmpty, "equation identity must be non-empty")

  final case class ContributionIdentity(value: String):
    require(value.nonEmpty, "contribution identity must be non-empty")

  final case class ContributionTarget(
      identity: String,
      kind: ContributionKind,
      dimension: String,
      orientation: String
  ):
    require(identity.nonEmpty, "contribution target identity must be non-empty")
    require(dimension.nonEmpty, "contribution target dimension must be non-empty")
    require(orientation.nonEmpty, "contribution target orientation must be non-empty")

  final case class Metadata(
      owner: String,
      guard: Option[Expression],
      analyses: Set[String],
      continuity: String,
      source: SourceSpan,
      component: Option[String] = None
  ):
    require(owner.nonEmpty, "semantic owner must be non-empty")
    require(analyses.nonEmpty, "analysis applicability must be non-empty")
    require(continuity.nonEmpty, "continuity class must be non-empty")

  final case class ResidualIntent(
      authoredLeft: Expression,
      authoredRight: Expression,
      canonicalConvention: String = "lhs-minus-rhs-equals-zero",
      causallyOriented: Boolean = false,
      divided: Boolean = false
  )

  final case class EquationRecord(
      identity: EquationIdentity,
      initialOnly: Boolean,
      metadata: Metadata,
      residual: ResidualIntent
  )

  final case class ContributionRecord(
      identity: ContributionIdentity,
      target: ContributionTarget,
      value: Expression,
      metadata: Metadata
  )

  final case class ContributionBucket(
      target: ContributionTarget,
      terms: Vector[ContributionRecord]
  )

  /** Immutable source records retain actual expression and declaration identity. */
  final case class ComponentRecord(
      identity: String,
      owner: String,
      contract: PhysicalComponentContract,
      source: SourceSpan
  )

  final case class StateRecord(
      identity: String,
      owner: String,
      component: Option[String],
      state: AnalogState,
      dimension: String,
      initialization: String,
      initializer: Option[Expression],
      source: SourceSpan
  )

  final case class ParameterReference(
      parameter: Param[?],
      owner: String,
      path: String,
      dimension: String
  )

  final case class Snapshot(
      equations: Vector[EquationRecord],
      contributions: Vector[ContributionBucket],
      components: Vector[ComponentRecord] = Vector.empty,
      states: Vector[StateRecord] = Vector.empty,
      parameters: Vector[ParameterReference] = Vector.empty,
      unsupported: Vector[String] = Vector.empty
  )

  def sourceMap(snapshot: Snapshot): Vector[SourceMapEntry] =
    ConstructionKernel.chargePublicationWork(3L) // Dispatch and two empty-inventory tests.
    if snapshot.components.isEmpty && snapshot.states.isEmpty then Vector.empty
    else
      val entries = snapshot.components.map(record => record.identity -> record.source) ++
        snapshot.states.map(record => record.identity -> record.source) ++
        snapshot.equations.map(record => record.identity.value -> record.metadata.source)
      entries.map: (path, source) =>
        SourceMapEntry(
          path,
          nodal.SourceSpan(
            source.file,
            source.line,
            source.column,
            source.line,
            source.column
          )
        )
      .sortBy(_.semanticPath)

  sealed trait Diagnostic:
    def code: String
    def message: String

  final case class SemanticError(code: String, message: String) extends Diagnostic

  final class Recorder:
    private val componentRecords = mutable.LinkedHashMap.empty[String, ComponentRecord]
    private val stateRecords = mutable.LinkedHashMap.empty[String, StateRecord]
    private val unsupportedRecords = mutable.LinkedHashSet.empty[String]
    private var componentStack = List.empty[ComponentRecord]

    def currentComponent: Option[ComponentRecord] = componentStack.headOption
    def containsComponent(identity: String): Boolean = componentRecords.contains(identity)
    def containsState(identity: String): Boolean = stateRecords.contains(identity)

    def component[A](record: ComponentRecord)(body: => A): A =
      require(!componentRecords.contains(record.identity), "duplicate physical component identity")
      if componentStack.nonEmpty then unsupportedRecords += "nested physical components"
      componentRecords(record.identity) = record
      componentStack = record :: componentStack
      try body
      finally componentStack = componentStack.tail

    def state(record: StateRecord): Unit =
      require(!stateRecords.contains(record.identity), "duplicate analog state identity")
      stateRecords(record.identity) = record

    def unsupported(reason: String): Unit = unsupportedRecords += reason

    private val initializationAnalyses = Set("initialization")
    private var activeRegion: Option[RegionKind] = None
    private val equationRecords = mutable.LinkedHashMap.empty[EquationIdentity, EquationRecord]
    private val contributionRecords =
      mutable.LinkedHashMap.empty[ContributionIdentity, ContributionRecord]

    /** No snapshot construction or context mutation; caller charges seven inspections. */
    def publicationEmpty: Boolean =
      componentRecords.isEmpty && stateRecords.isEmpty && unsupportedRecords.isEmpty &&
        componentStack.isEmpty && activeRegion.isEmpty && equationRecords.isEmpty &&
        contributionRecords.isEmpty

    def region[A](kind: RegionKind)(body: => A): Either[Diagnostic, A] =
      if activeRegion.nonEmpty then
        Left(SemanticError("NODAL-ANALOG-032-001", "analog semantic regions cannot overlap"))
      else
        activeRegion = Some(kind)
        try Right(body)
        finally activeRegion = None

    def recordEquation(
        identity: EquationIdentity,
        left: Expression,
        right: Expression,
        metadata: Metadata
    ): Either[Diagnostic, EquationRecord] =
      activeRegion match
        case Some(RegionKind.Equation) =>
          validateAndStoreEquation(identity, initialOnly = false, left, right, metadata)
        case Some(RegionKind.InitialEquation) =>
          validateAndStoreEquation(identity, initialOnly = true, left, right, metadata)
        case Some(RegionKind.Contribution) | Some(RegionKind.Procedural) =>
          Left(
            SemanticError(
              "NODAL-ANALOG-032-002",
              "unordered equations are illegal in contribution and procedural regions"
            )
          )
        case None =>
          Left(SemanticError("NODAL-ANALOG-032-003", "equation requires an equation region"))

    def recordContribution(
        identity: ContributionIdentity,
        target: ContributionTarget,
        value: Expression,
        metadata: Metadata
    ): Either[Diagnostic, ContributionRecord] =
      activeRegion match
        case Some(RegionKind.Contribution) =>
          if contributionRecords.contains(identity) then
            Left(
              SemanticError(
                "NODAL-ANALOG-032-009",
                s"duplicate contribution identity: ${identity.value}"
              )
            )
          else if value.valueKind != ValueKind.Real then
            Left(
              SemanticError(
                "NODAL-ANALOG-032-010",
                "a potential/flow contribution must have a real-valued expression"
              )
            )
          else if value.dimension != target.dimension then
            Left(
              SemanticError(
                "NODAL-ANALOG-032-011",
                s"contribution dimension ${value.dimension} does not match target ${target.dimension}"
              )
            )
          else
            validateGuard(metadata).map { _ =>
              val record = ContributionRecord(identity, target, value, metadata)
              contributionRecords(identity) = record
              record
            }
        case Some(RegionKind.Equation) | Some(RegionKind.InitialEquation) |
            Some(RegionKind.Procedural) =>
          Left(
            SemanticError(
              "NODAL-ANALOG-032-012",
              "additive contributions are legal only in a contribution region"
            )
          )
        case None =>
          Left(
            SemanticError(
              "NODAL-ANALOG-032-013",
              "contribution requires a contribution region"
            )
          )

    def snapshot: Snapshot =
      // Audited empty copies/group/map/sorts plus dispatch and Snapshot construction.
      ConstructionKernel.chargePublicationWork(15L)
      val orderedEquations = equationRecords.values.toVector.sortBy(_.identity.value)
      val buckets = contributionRecords.values.toVector
        .groupBy(_.target)
        .toVector
        .sortBy { case (target, _) =>
          (target.identity, target.kind.toString, target.dimension, target.orientation)
        }
        .map { case (target, records) =>
          ContributionBucket(target, records.sortBy(_.identity.value))
        }
      Snapshot(
        orderedEquations,
        buckets,
        componentRecords.values.toVector.sortBy(_.identity),
        stateRecords.values.toVector.sortBy(_.identity),
        unsupported = unsupportedRecords.toVector.sorted
      )

    private def validateAndStoreEquation(
        identity: EquationIdentity,
        initialOnly: Boolean,
        left: Expression,
        right: Expression,
        metadata: Metadata
    ): Either[Diagnostic, EquationRecord] =
      if equationRecords.contains(identity) then
        Left(
          SemanticError(
            "NODAL-ANALOG-032-004",
            s"duplicate equation identity: ${identity.value}"
          )
        )
      else if initialOnly && metadata.analyses != initializationAnalyses then
        Left(
          SemanticError(
            "NODAL-ANALOG-133-009",
            "initial equations are applicable only to initialization analysis"
          )
        )
      else if left.valueKind != ValueKind.Real || right.valueKind != ValueKind.Real then
        Left(
          SemanticError(
            "NODAL-ANALOG-032-005",
            "equation operands must be real-valued expressions"
          )
        )
      else if left.dimension != right.dimension then
        Left(
          SemanticError(
            "NODAL-ANALOG-032-006",
            s"equation dimensions differ: ${left.dimension} versus ${right.dimension}"
          )
        )
      else
        validateGuard(metadata).map { _ =>
          val record = EquationRecord(
            identity,
            initialOnly,
            metadata,
            ResidualIntent(left, right)
          )
          equationRecords(identity) = record
          record
        }

    private def validateGuard(metadata: Metadata): Either[Diagnostic, Unit] =
      metadata.guard match
        case Some(guard) if guard.valueKind != ValueKind.Boolean =>
          Left(
            SemanticError(
              "NODAL-ANALOG-032-007",
              "an equation or contribution guard must be Boolean"
            )
          )
        case Some(guard) if guard.dimension != "1" =>
          Left(
            SemanticError(
              "NODAL-ANALOG-032-008",
              "an equation or contribution guard must be dimensionless"
            )
          )
        case _ => Right(())
