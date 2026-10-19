package nodal.internal.integrations

/** Validated data values shared by adapters; raw parsing and grid rules remain consumer-owned. */
private[nodal] enum AnalogAnalysisKind:
  case Dc, Ac, Transient

private[nodal] final case class AnalogRawVariable(index: Int, name: String, kind: String)
private[nodal] final case class AnalogComplex(real: Double, imaginary: Double)
private[nodal] final case class AnalogRawSchema(
    title: String,
    plotName: String,
    isComplex: Boolean,
    variables: Vector[AnalogRawVariable],
    pointCount: Int
)
private[nodal] final case class AnalogRawPlot(
    title: String,
    plotName: String,
    isComplex: Boolean,
    variables: Vector[AnalogRawVariable],
    rows: Vector[Vector[AnalogComplex]]
)

/** requestedPoints is the nominal grid size, not an adaptive transient output guarantee. */
private[nodal] final case class AnalogGeneratedBench(
    deck: String,
    kind: AnalogAnalysisKind,
    requestedPoints: Int,
    expectedVariables: Vector[(String, String)],
    axis: AnalogAxis
)
