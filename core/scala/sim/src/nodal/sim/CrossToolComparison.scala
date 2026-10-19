package nodal.sim

/** Outcomes distinguish completed numerical disagreement from failure to compare. */
enum CrossToolOutcome:
  case Compared, Disagreement, UnsupportedCapability, InvalidRequest, ToolFailure,
    IncompatibleResults

final case class CrossToolComparisonResult(
    outcome: CrossToolOutcome,
    referenceEvidence: Option[SimulationEvidence],
    candidateEvidence: Option[SimulationEvidence],
    error: Option[SimulationError],
    comparison: Option[WaveformComparisonResult]
):
  def succeeded: Boolean = comparison.nonEmpty && error.isEmpty
  def passed: Boolean = outcome == CrossToolOutcome.Compared

private[sim] object CrossToolComparison:
  private final class State[A, D](val reducer: WaveformComparisonReducer[A, D]):
    var first = Option.empty[Double]
    var last = Option.empty[Double]

  def run[A, D](
      bench: SimulationBench,
      referenceTools: SimulatorTools,
      candidateTools: SimulatorTools,
      axis: WaveformAxis[A],
      probe: Probe[D],
      tolerance: Tolerance[D],
      component: ComplexComponent,
      alignment: WaveformAlignment
  ): CrossToolComparisonResult =
    val column = bench.probes.indexWhere(_ eq probe) + 1
    val admission =
      for
        _ <- Either.cond(
          column > 0 && axis.unit == SimulationSession.axisUnit(bench),
          (),
          SimulationError("SIM-CROSS-REQUEST", "foreign probe or incompatible physical axis")
        )
        _ <- SimulationExecution.admit(bench.lowered, referenceTools)
        _ <- SimulationExecution.admit(bench.lowered, candidateTools)
      yield ()
    admission match
      case Left(error) => failure(error, None, None)
      case Right(_) =>
        val reference = SimulationSession.run(bench, referenceTools)
        reference.waveform(axis, probe) match
          case Left(error) =>
            failure(reference.error.getOrElse(error), Some(reference.evidence), None)
          case Right(waveform) =>
            val reducer = WaveformComparison.reducer(waveform, tolerance, component, alignment)
            val candidate = SimulationExecution.fold(bench, candidateTools)(_ =>
              Right(new State(reducer))
            )((state, _, row) =>
              if state.first.isEmpty then state.first = Some(row.head.real)
              state.last = Some(row.head.real)
              state.reducer.addSi(row.head.real, row(column).real, row(column).imaginary)
                .left.map(error => s"${error.code}: ${error.message}").map(_ => state)
            )
            val ref = Some(reference.evidence)
            val cand = Some(candidate.evidence)
            candidate.error match
              case Some(error) => failure(error, ref, cand)
              case None =>
                val completed =
                  for
                    state <- candidate.aggregate.toRight(SimulationError(
                      "SIM-CROSS-RESULT",
                      "missing completed candidate aggregate"
                    ))
                    _ <- Either.cond(
                      reference.evidence.modelSha256 == candidate.evidence.modelSha256 &&
                        reference.evidence.artifactSha256.nonEmpty &&
                        reference.evidence.artifactSha256 == candidate.evidence.artifactSha256,
                      (),
                      SimulationError(
                        "SIM-CROSS-ARTIFACT",
                        "tools did not retain the identical model/artifact"
                      )
                    )
                    _ <- Either.cond(
                      state.first.contains(waveform.cell(0)._1) &&
                        state.last.contains(waveform.cell(waveform.sampleCount - 1)._1),
                      (),
                      SimulationError(
                        "SIM-CROSS-DOMAIN",
                        "candidate does not cover the complete reference domain"
                      )
                    )
                    comparison <- state.reducer.finish()
                  yield comparison
                completed match
                  case Left(error) => failure(error, ref, cand)
                  case Right(comparison) => CrossToolComparisonResult(
                      if comparison.failureCount == 0 then CrossToolOutcome.Compared
                      else CrossToolOutcome.Disagreement,
                      ref,
                      cand,
                      None,
                      Some(comparison)
                    )

  private def failure(
      error: SimulationError,
      reference: Option[SimulationEvidence],
      candidate: Option[SimulationEvidence]
  ): CrossToolComparisonResult =
    val outcome =
      if error.code == "SIM-CAPABILITY" ||
        Set("NODAL-NGSPICE-003", "NODAL-VACASK-003", "NODAL-VACASK-017").contains(error.code)
      then CrossToolOutcome.UnsupportedCapability
      else if Set(
          "SIM-CROSS-REQUEST",
          "SIM-CROSS-ARTIFACT",
          "NODAL-NGSPICE-001",
          "NODAL-NGSPICE-005",
          "NODAL-VACASK-001",
          "NODAL-VACASK-005"
        ).contains(error.code)
      then CrossToolOutcome.InvalidRequest
      else if error.code.startsWith("SIM-CROSS-") || error.code == "SIM-WAVEFORM" ||
        Set("NODAL-NGSPICE-016", "NODAL-VACASK-016").contains(error.code)
      then CrossToolOutcome.IncompatibleResults
      else CrossToolOutcome.ToolFailure
    CrossToolComparisonResult(outcome, reference, candidate, Some(error), None)
