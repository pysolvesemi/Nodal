package nodal

/** Public continuous-time semantic surface frozen by Increment 133.
  *
  * These declarations are compile-time contracts only. Frontend construction, equation
  * normalization, residual formation, solving, and target lowering remain inert until their owning
  * implementation increments.
  */
enum AnalysisKind:
  case Initialization, Dc, OperatingPoint, Transient, Ac, Noise

final case class AnalysisApplicability private (values: Set[AnalysisKind])

object AnalysisApplicability:
  val All: AnalysisApplicability = AnalysisApplicability(AnalysisKind.values.toSet)

  def only(first: AnalysisKind, rest: AnalysisKind*): AnalysisApplicability =
    AnalysisApplicability((first +: rest).toSet)

enum ContinuityClass:
  case Unspecified, Discontinuous, C0, C1, C2

final case class EquationId(value: String)

final case class ContributionId(value: String)

final case class BalanceId(value: String)

final case class EquationOptions(
    id: Option[EquationId] = None,
    guard: Option[Expr[Bool]] = None,
    analyses: AnalysisApplicability = AnalysisApplicability.All,
    continuity: ContinuityClass = ContinuityClass.Unspecified
)

final case class InitialEquationOptions(
    id: Option[EquationId] = None,
    guard: Option[Expr[Bool]] = None,
    continuity: ContinuityClass = ContinuityClass.Unspecified
)

final case class ContributionOptions(
    id: Option[ContributionId] = None,
    guard: Option[Expr[Bool]] = None,
    analyses: AnalysisApplicability = AnalysisApplicability.All,
    continuity: ContinuityClass = ContinuityClass.Unspecified
)

def equations(body: => Unit): Unit =
  CandidateRuntime.statement("candidate-equations")
  body

def equation(
    left: Expr[Real],
    right: Expr[Real],
    options: EquationOptions = EquationOptions()
): Unit = CandidateRuntime.statement("candidate-equation", left, right, options)

extension (left: Expr[Real])
  infix def ===(right: Expr[Real]): Unit = equation(left, right)

def initialEquations(body: => Unit): Unit =
  CandidateRuntime.statement("candidate-initial-equations")
  body

def initialEquation(
    left: Expr[Real],
    right: Expr[Real],
    options: InitialEquationOptions = InitialEquationOptions()
): Unit = CandidateRuntime.statement("candidate-initial-equation", left, right, options)

def contributions(body: => Unit): Unit =
  CandidateRuntime.statement("candidate-contributions")
  body

def contribution(
    target: Expr[Real],
    value: Expr[Real],
    options: ContributionOptions = ContributionOptions()
): Unit = CandidateRuntime.statement("candidate-contribution", target, value, options)

def analogProcedure(body: => Unit): Unit =
  CandidateRuntime.statement("candidate-analog-procedure")
  body

enum ComponentCompleteness:
  case Partial, Concrete

enum TopologyOwnership:
  case Extensible, Complete

enum LocalBalancePolicy:
  case DeferredToConcrete, Required, Explicit

final case class PhysicalComponentContract(
    name: String,
    completeness: ComponentCompleteness,
    topology: TopologyOwnership,
    localBalance: LocalBalancePolicy,
    replaceable: Boolean = false
)

def physicalComponent(contract: PhysicalComponentContract)(body: => Unit): Unit =
  CandidateRuntime.statement("candidate-physical-component", contract)
  body

def localBalance(id: BalanceId, terminalFlows: Expr[Real]*): Unit =
  CandidateRuntime.statement("candidate-local-balance", id, terminalFlows)

/** Named or implicit conservative branch used by equations and contributions. */
final class Branch[D <: Discipline] private[nodal] (
    val positive: Terminal[D],
    val negative: Terminal[D],
    val name: Option[String]
):
  def potential: Expr[Real] =
    CandidateRuntime.analogExpr("candidate-branch-potential", this)

  def flow: Expr[Real] =
    CandidateRuntime.analogExpr("candidate-branch-flow", this)

  def contributePotential(
      value: Expr[Real],
      options: ContributionOptions = ContributionOptions()
  ): Unit = contribution(potential, value, options)

  def contributeFlow(
      value: Expr[Real],
      options: ContributionOptions = ContributionOptions()
  ): Unit = contribution(flow, value, options)

def branch[D <: Discipline](
    positive: Terminal[D],
    negative: Terminal[D]
): Branch[D] = new Branch(positive, negative, None)

def branch[D <: Discipline](
    positive: Terminal[D],
    negative: Terminal[D],
    name: String
): Branch[D] = new Branch(positive, negative, Some(name))

enum StructuralEffect:
  case Topology, ComponentCount, EquationCount, Shape, Rank

final case class StructuralEnvelope(minimum: Int, maximum: Int)

trait StructuralKind[A <: Data]

object StructuralKind:
  given integerStructuralKind: StructuralKind[Integer] with {}
  given booleanStructuralKind: StructuralKind[Bool] with {}

final class StructuralParameter[A <: Data] private[nodal] (
    val parameter: Param[A],
    val envelope: StructuralEnvelope,
    val effects: Set[StructuralEffect]
):
  def expression: Expr[A] = parameter

def structuralParameter[A <: Data](
    parameter: Param[A],
    envelope: StructuralEnvelope,
    effects: Set[StructuralEffect]
)(using StructuralKind[A]): StructuralParameter[A] =
  CandidateRuntime.statement("candidate-structural-parameter", parameter, envelope, effects)
  new StructuralParameter(parameter, envelope, effects)

final case class PhysicalDimension(name: String)

object PhysicalDimension:
  val Dimensionless: PhysicalDimension = PhysicalDimension("dimensionless")
  val Voltage: PhysicalDimension = PhysicalDimension("voltage")
  val Current: PhysicalDimension = PhysicalDimension("current")
  val Charge: PhysicalDimension = PhysicalDimension("charge")
  val Temperature: PhysicalDimension = PhysicalDimension("temperature")
  val Time: PhysicalDimension = PhysicalDimension("time")
  val Frequency: PhysicalDimension = PhysicalDimension("frequency")
  val Power: PhysicalDimension = PhysicalDimension("power")

sealed trait StateInitialization

object StateInitialization:
  final case class Fixed(value: Expr[Real]) extends StateInitialization
  final case class Guess(value: Expr[Real]) extends StateInitialization
  case object InitialEquation extends StateInitialization
  case object SteadyState extends StateInitialization
  case object OperatingPoint extends StateInitialization
  case object SolverSelected extends StateInitialization

final class AnalogState private[nodal] (
    val name: String,
    val dimension: PhysicalDimension,
    val initialization: StateInitialization
):
  def read: Expr[Real] = CandidateRuntime.analogExpr("candidate-analog-state", this)

  def derivative: Expr[Real] = ddt(read)

def analogState(
    name: String,
    dimension: PhysicalDimension,
    initialization: StateInitialization
): AnalogState =
  CandidateRuntime.statement("candidate-analog-state-declaration", name, dimension, initialization)
  new AnalogState(name, dimension, initialization)

def reinitialize(state: AnalogState, value: Expr[Real]): Unit =
  CandidateRuntime.statement("candidate-state-reinitialize", state, value)

final case class EventTolerance(value: Expr[Real], time: Expr[Real])

def namedEvent(name: String): Event = CandidateRuntime.event("candidate-named-event", name)

def crossing(
    value: Expr[Real],
    tolerance: EventTolerance,
    edge: Edge = Edge.Either,
    name: String = ""
): Event = CandidateRuntime.event("candidate-crossing", value, tolerance, edge, name)

enum DiscontinuityKind:
  case Exact, EventGuarded, Transitioned

final case class DiscontinuityContract(
    kind: DiscontinuityKind,
    continuity: ContinuityClass,
    event: Option[Event] = None
)

def discontinuity(contract: DiscontinuityContract)(body: => Unit): Unit =
  CandidateRuntime.statement("candidate-discontinuity", contract)
  body

object AnalysisContext:
  def active(kind: AnalysisKind): Expr[Bool] =
    CandidateRuntime.expr("candidate-analysis-active", kind)

  def time: Expr[Real] = CandidateRuntime.analogExpr("candidate-analysis-time")

  def frequency: Expr[Real] = CandidateRuntime.analogExpr("candidate-analysis-frequency")

object EnvironmentContext:
  def temperature: Expr[Real] =
    CandidateRuntime.analogExpr("candidate-environment-temperature")

  def nominalTemperature: Expr[Real] =
    CandidateRuntime.analogExpr("candidate-environment-nominal-temperature")

  def operatingCondition(name: String, dimension: PhysicalDimension): Expr[Real] =
    CandidateRuntime.analogExpr("candidate-operating-condition", name, dimension)

  def corner(name: String): Expr[Bool] =
    CandidateRuntime.expr("candidate-environment-corner", name)

  def sweepCoordinate(name: String, dimension: PhysicalDimension): Expr[Real] =
    CandidateRuntime.analogExpr("candidate-sweep-coordinate", name, dimension)

  def randomSeed(scope: String): Expr[Integer] =
    CandidateRuntime.expr("candidate-environment-random-seed", scope)

final case class NoiseId(value: String)

sealed trait NoiseCorrelation

object NoiseCorrelation:
  case object Independent extends NoiseCorrelation
  final case class Group(id: String) extends NoiseCorrelation

final case class NoiseOptions(
    correlation: NoiseCorrelation = NoiseCorrelation.Independent,
    analyses: AnalysisApplicability = AnalysisApplicability.only(AnalysisKind.Noise)
)

final case class NoisePoint(frequency: Expr[Real], spectralDensity: Expr[Real])

def whiteNoise(
    id: NoiseId,
    spectralDensity: Expr[Real],
    options: NoiseOptions = NoiseOptions()
): Expr[Real] =
  CandidateRuntime.analogExpr("candidate-white-noise", id, spectralDensity, options)

def flickerNoise(
    id: NoiseId,
    coefficient: Expr[Real],
    exponent: Double,
    options: NoiseOptions = NoiseOptions()
): Expr[Real] =
  CandidateRuntime.analogExpr("candidate-flicker-noise", id, coefficient, exponent, options)

def tableNoise(
    id: NoiseId,
    points: Seq[NoisePoint],
    options: NoiseOptions = NoiseOptions()
): Expr[Real] = CandidateRuntime.analogExpr("candidate-table-noise", id, points, options)

enum ViolationPolicy:
  case Error, Warning, VerificationOnly

enum AccuracyClass:
  case Exact, CompactModel, Behavioral, Approximate

sealed trait ValidityConstraint

object ValidityConstraint:
  final case class ParameterRange[A <: Data](
      parameter: Param[A],
      minimum: Expr[A],
      maximum: Expr[A]
  ) extends ValidityConstraint

  final case class OperatingRange(
      name: String,
      value: Expr[Real],
      minimum: Expr[Real],
      maximum: Expr[Real]
  ) extends ValidityConstraint

  final case class TemperatureRange(minimum: Expr[Real], maximum: Expr[Real])
      extends ValidityConstraint

  final case class SupportedAnalyses(analyses: AnalysisApplicability)
      extends ValidityConstraint

  final case class CrossParameter(name: String, condition: Expr[Bool])
      extends ValidityConstraint

  final case class TopologyAssumption(description: String) extends ValidityConstraint

  final case class LoadingAssumption(description: String) extends ValidityConstraint

final case class ModelValidityEnvelope(
    id: String,
    accuracy: AccuracyClass,
    constraints: Seq[ValidityConstraint],
    onViolation: ViolationPolicy
)

def modelValidity(envelope: ModelValidityEnvelope): Unit =
  CandidateRuntime.statement("candidate-model-validity", envelope)

sealed trait SolverHint

object SolverHint:
  final case class Nominal(name: String, value: Expr[Real]) extends SolverHint
  final case class AbsoluteTolerance(value: Expr[Real]) extends SolverHint
  final case class RelativeTolerance(value: Double) extends SolverHint
  final case class MaximumStep(value: Expr[Real]) extends SolverHint
  final case class Convergence(name: String, value: Expr[Real]) extends SolverHint

def solverHints(hints: SolverHint*): Unit =
  CandidateRuntime.statement("candidate-solver-hints", hints)
