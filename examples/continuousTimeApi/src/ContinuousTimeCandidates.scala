package examples.continuoustime

import external.continuoustime.*
import nodal.*

final class ContinuousTimeCandidates extends Module:
  val positive: Terminal[Electrical.type] = terminal(Electrical, "positive")
  val negative: Terminal[Electrical.type] = terminal(Electrical, "negative")
  val path: Branch[Electrical.type] = branch(positive, negative, "device_path")

  val resistance: Param[Real] = param(10.0.kOhm)
  val sectionCount: Param[Integer] = param(2.integer)
  val sections: StructuralParameter[Integer] = structuralParameter(
    sectionCount,
    StructuralEnvelope(minimum = 1, maximum = 8),
    Set(StructuralEffect.ComponentCount, StructuralEffect.EquationCount)
  )

  generate(sections.expression): _ =>
    CandidateSmoke.consume("structural-component-slot")

  val transientActive: Expr[Bool] = AnalysisContext.active(AnalysisKind.Transient)

  physicalComponent(
    PhysicalComponentContract(
      name = "ContinuousTimeCandidates",
      completeness = ComponentCompleteness.Concrete,
      topology = TopologyOwnership.Complete,
      localBalance = LocalBalancePolicy.Explicit
    )
  ):
    localBalance(
      BalanceId("candidate-terminal-balance"),
      positive.senseView.flow,
      negative.senseView.flow
    )

    equations:
      equation(
        path.potential,
        resistance * path.flow,
        EquationOptions(
          id = Some(EquationId("candidate-ohm-law")),
          guard = Some(transientActive),
          analyses = AnalysisApplicability.only(
            AnalysisKind.Dc,
            AnalysisKind.OperatingPoint,
            AnalysisKind.Transient
          ),
          continuity = ContinuityClass.C1
        )
      )

    contributions:
      path.flow <+ path.potential / resistance
      contribute(
        path.potential,
        0.0.V,
        ContributionOptions(id = Some(ContributionId("candidate-reference-drive")))
      )

  val charge: AnalogState = analogState(
    name = "charge",
    dimension = PhysicalDimension.Charge,
    initialization = StateInitialization.InitialEquation
  )

  initialEquations:
    initialEquation(
      charge.read,
      0.0.real,
      EquationOptions(id = Some(EquationId("initial-charge")))
    )

  val scratch: Variable[Real] = variable(Real, 0.0.real)
  analogProcedure:
    scratch := path.potential

  val thresholdEvent: Event = crossing(
    path.potential - 0.1.V,
    EventTolerance(value = 1.0e-6.V, time = 1.0.ns),
    edge = Edge.Rising,
    name = "positive-threshold"
  )

  on(thresholdEvent):
    reinitialize(charge, 0.0.real)

  discontinuity(
    DiscontinuityContract(
      kind = DiscontinuityKind.EventGuarded,
      continuity = ContinuityClass.Discontinuous,
      event = Some(thresholdEvent)
    )
  ):
    CandidateSmoke.consume("event-guarded-equation-mode")

  val simulationTime: Expr[Real] = AnalysisContext.time
  val analysisFrequency: Expr[Real] = AnalysisContext.frequency
  val temperature: Expr[Real] = EnvironmentContext.temperature
  val nominalTemperature: Expr[Real] = EnvironmentContext.nominalTemperature
  val supplyCondition: Expr[Real] =
    EnvironmentContext.operatingCondition("supply", PhysicalDimension.Voltage)
  val slowCorner: Expr[Bool] = EnvironmentContext.corner("slow")
  val sweep: Expr[Real] =
    EnvironmentContext.sweepCoordinate("bias", PhysicalDimension.Voltage)
  val localSeed: Expr[Integer] = EnvironmentContext.randomSeed("local-mismatch")

  val thermalNoise: Expr[Real] = whiteNoise(
    NoiseId("thermal-noise"),
    1.0e-18.real,
    NoiseOptions(correlation = NoiseCorrelation.Independent)
  )
  val correlatedNoise: Expr[Real] = flickerNoise(
    NoiseId("flicker-noise"),
    coefficient = 1.0e-12.real,
    exponent = 1.0,
    options = NoiseOptions(correlation = NoiseCorrelation.Group("device-pair"))
  )
  val tabulatedNoise: Expr[Real] = tableNoise(
    NoiseId("table-noise"),
    Seq(
      NoisePoint(1.0.real, 1.0e-18.real),
      NoisePoint(1.0e6.real, 1.0e-20.real)
    )
  )

  modelValidity(
    ModelValidityEnvelope(
      id = "candidate-validity",
      accuracy = AccuracyClass.Behavioral,
      constraints = Seq(
        ValidityConstraint.ParameterRange(resistance, 1.0.Ohm, 1.0e9.Ohm),
        ValidityConstraint.OperatingRange(
          "branch-voltage",
          path.potential,
          (-100.0).V,
          100.0.V
        ),
        ValidityConstraint.TemperatureRange(200.0.real, 450.0.real),
        ValidityConstraint.SupportedAnalyses(
          AnalysisApplicability.only(
            AnalysisKind.Dc,
            AnalysisKind.OperatingPoint,
            AnalysisKind.Transient,
            AnalysisKind.Ac,
            AnalysisKind.Noise
          )
        ),
        ValidityConstraint.CrossParameter("positive-resistance", resistance > 0.0.Ohm),
        ValidityConstraint.TopologyAssumption("two connected electrical terminals"),
        ValidityConstraint.LoadingAssumption("finite external impedance")
      ),
      onViolation = ViolationPolicy.Error
    )
  )

  solverHints(
    SolverHint.Nominal("branch-voltage", 1.0.V),
    SolverHint.AbsoluteTolerance(1.0e-9.V),
    SolverHint.RelativeTolerance(1.0e-6),
    SolverHint.MaximumStep(1.0.ns),
    SolverHint.Convergence("voltage-limiting", 0.1.V)
  )

  val libraryResistor: Instance[Resistor] = instance(new Resistor(2.0.kOhm))
  val libraryCapacitor: Instance[Capacitor] = instance(new Capacitor(2.0.pF))
  libraryResistor(_.negative).connectView.connectTo(libraryCapacitor(_.positive).connectView)

  CandidateSmoke.consume(
    charge.derivative,
    simulationTime,
    analysisFrequency,
    temperature,
    nominalTemperature,
    supplyCondition,
    slowCorner,
    sweep,
    localSeed,
    thermalNoise,
    correlatedNoise,
    tabulatedNoise,
    namedEvent("manual-event")
  )

object CandidateSmoke:
  def consume(values: Any*): Unit = values.foreach(_ => ())
