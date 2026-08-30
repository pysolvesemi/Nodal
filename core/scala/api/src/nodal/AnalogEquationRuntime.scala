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
      valueKind: ValueKind = ValueKind.Real
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
      source: SourceSpan
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

  final case class Snapshot(
      equations: Vector[EquationRecord],
      contributions: Vector[ContributionBucket]
  )

  sealed trait Diagnostic:
    def code: String
    def message: String

  final case class SemanticError(code: String, message: String) extends Diagnostic

  final class Recorder:
    private var activeRegion: Option[RegionKind] = None
    private val equationRecords = mutable.LinkedHashMap.empty[EquationIdentity, EquationRecord]
    private val contributionRecords =
      mutable.LinkedHashMap.empty[ContributionIdentity, ContributionRecord]

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
      val orderedEquations = equationRecords.values.toVector.sortBy(_.identity.value)
      val buckets = contributionRecords.values.toVector
        .groupBy(_.target)
        .toVector
        .sortBy { case (target, _) =>
          (target.identity, target.kind.toString, target.orientation)
        }
        .map { case (target, records) =>
          ContributionBucket(target, records.sortBy(_.identity.value))
        }
      Snapshot(orderedEquations, buckets)

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
