package nodal.sim

import nodal.internal.integrations.*

/** One explicit dispatch owner for collected runs, streaming consumers and sweep admission. */
private[sim] object SimulationExecution:
  final case class Collected(
      evidence: SimulationEvidence,
      error: Option[SimulationError],
      plot: Option[AnalogRawPlot]
  )
  final case class Folded[A](
      evidence: SimulationEvidence,
      error: Option[SimulationError],
      pointCount: Int,
      aggregate: Option[A]
  )

  def admit(bench: AnalogCircuitBench, tools: SimulatorTools)
      : Either[SimulationError, AnalogGeneratedBench] =
    tools.backend match
      case SimulatorBackend.Ngspice =>
        if tools.runtimeLibraryDirectories.nonEmpty then
          Left(SimulationError(
            "SIM-CAPABILITY",
            "ngspice does not accept explicit runtime library directories"
          ))
        else NgspiceBench.generate(bench).left.map(SimulationError("SIM-CAPABILITY", _))
      case SimulatorBackend.Vacask =>
        VacaskBench.generate(bench).left.map(SimulationError("SIM-CAPABILITY", _))

  def run(bench: SimulationBench, tools: SimulatorTools): Collected =
    admit(bench.lowered, tools) match
      case Left(error) => Collected(rejectedEvidence(bench, error), Some(error), None)
      case Right(_) =>
        tools.backend match
          case SimulatorBackend.Ngspice =>
            val result = NgspiceSimulator.simulate(NgspiceCircuitSimulateRequest(
              tools.executable,
              tools.evidenceRoot,
              tools.profile,
              bench.model.compiled,
              bench.lowered,
              runTimeout = tools.timeout
            ))
            Collected(
              SimulationSession.evidence(result),
              Option.when(result.outcome != NgspiceOutcome.Simulated)(
                SimulationError(result.diagnosticCode, result.message)
              ),
              result.plot
            )
          case SimulatorBackend.Vacask =>
            val result = VacaskSimulator.simulate(VacaskCircuitSimulateRequest(
              tools.executable,
              tools.evidenceRoot,
              tools.profile,
              bench.model.compiled,
              bench.lowered,
              runTimeout = tools.timeout,
              runtimeLibraryDirectories = tools.runtimeLibraryDirectories
            ))
            Collected(
              evidence(result),
              Option.when(result.outcome != VacaskOutcome.Simulated)(
                SimulationError(result.diagnosticCode, result.message)
              ),
              result.plot
            )
  def fold[A](bench: SimulationBench, tools: SimulatorTools)(
      initialize: AnalogRawSchema => Either[String, A]
  )(consume: (A, Int, Vector[AnalogComplex]) => Either[String, A]): Folded[A] =
    admit(bench.lowered, tools) match
      case Left(error) => Folded(rejectedEvidence(bench, error), Some(error), 0, None)
      case Right(_) =>
        tools.backend match
          case SimulatorBackend.Ngspice =>
            val result = NgspiceSimulator.fold(NgspiceCircuitSimulateRequest(
              tools.executable,
              tools.evidenceRoot,
              tools.profile,
              bench.model.compiled,
              bench.lowered,
              runTimeout = tools.timeout
            ))(initialize)(consume)
            Folded(
              SimulationSession.evidence(result.receipt),
              Option.when(result.receipt.outcome != NgspiceOutcome.Simulated)(
                SimulationError(result.receipt.diagnosticCode, result.receipt.message)
              ),
              result.pointCount,
              result.aggregate
            )
          case SimulatorBackend.Vacask =>
            val result = VacaskSimulator.fold(VacaskCircuitSimulateRequest(
              tools.executable,
              tools.evidenceRoot,
              tools.profile,
              bench.model.compiled,
              bench.lowered,
              runTimeout = tools.timeout,
              runtimeLibraryDirectories = tools.runtimeLibraryDirectories
            ))(initialize)(consume)
            Folded(
              evidence(result.receipt),
              Option.when(result.receipt.outcome != VacaskOutcome.Simulated)(
                SimulationError(result.receipt.diagnosticCode, result.receipt.message)
              ),
              result.pointCount,
              result.aggregate
            )
  private def evidence(result: VacaskSimulationResult): SimulationEvidence =
    SimulationEvidence(
      result.evidenceDirectory,
      result.outcome.toString,
      result.message,
      result.identity.requestSha256,
      result.identity.modelSha256,
      result.identity.artifactSha256,
      result.identity.executableSha256,
      result.identity.version,
      result.simulation.map(value =>
        CommandEvidence(
          value.command,
          value.standardOutput,
          value.standardError,
          value.exitCode,
          value.failure.map(_.toString)
        )
      ),
      result.raw.map(_.sha256),
      result.raw.map(_.path)
    )

  private def rejectedEvidence(bench: SimulationBench, error: SimulationError): SimulationEvidence =
    SimulationEvidence(
      None,
      "UnsupportedCapability",
      error.message,
      None,
      bench.model.compiled.identity.modelSha256,
      bench.model.compiled.artifact.map(_.sha256),
      None,
      None,
      None,
      None,
      None
    )
