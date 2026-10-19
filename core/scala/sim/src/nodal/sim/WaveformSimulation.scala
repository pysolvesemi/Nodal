package nodal.sim

/** Completed comparison evidence is released only after the entire simulator output validates. */
final case class StreamingComparisonResult(
    evidence: Option[SimulationEvidence],
    error: Option[SimulationError],
    sampleCount: Int,
    comparison: Option[WaveformComparisonResult]
):
  def succeeded: Boolean = error.isEmpty && comparison.nonEmpty
  def passed: Boolean = succeeded && comparison.exists(_.failureCount == 0)

private[sim] object WaveformSimulation:
  def compare[A, D](
      bench: SimulationBench,
      tools: SimulatorTools,
      probe: Probe[D],
      reference: Waveform[A, D],
      tolerance: Tolerance[D],
      component: ComplexComponent,
      alignment: WaveformAlignment
  ): StreamingComparisonResult =
    val column = bench.probes.indexWhere(_ eq probe) + 1
    if column == 0 then
      rejected("probe is absent from this bench")
    else if reference.axis.unit != SimulationSession.axisUnit(bench) then
      rejected("reference axis does not match the requested analysis")
    else if reference.unit.symbol != probe.unit then
      rejected("reference signal unit does not match the requested probe")
    else if SimulationExecution.admit(bench.lowered, tools).isLeft then
      rejected(SimulationExecution.admit(bench.lowered, tools).left.toOption.get.message)
    else
      val reducer = WaveformComparison.reducer(reference, tolerance, component, alignment)
      val result = SimulationExecution.fold(bench, tools)(_ => Right(reducer))((state, _, row) =>
        state.addSi(row.head.real, row(column).real, row(column).imaginary)
          .left.map(error => s"${error.code}: ${error.message}")
          .map(_ => state)
      )
      val evidence = Some(result.evidence)
      if result.error.nonEmpty then
        StreamingComparisonResult(
          evidence,
          result.error,
          0,
          None
        )
      else
        result.aggregate.toRight(SimulationError("SIM-WAVEFORM", "missing completed comparison"))
          .flatMap(_.finish()) match
          case Left(error) => StreamingComparisonResult(evidence, Some(error), 0, None)
          case Right(comparison) =>
            StreamingComparisonResult(evidence, None, result.pointCount, Some(comparison))

  private def rejected(message: String): StreamingComparisonResult =
    StreamingComparisonResult(None, Some(SimulationError("SIM-WAVEFORM", message)), 0, None)
