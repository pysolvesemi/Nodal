package nodal.internal.integrations

import java.io.InputStream

private[nodal] type NgspiceAnalysisKind = AnalogAnalysisKind
private[nodal] val NgspiceAnalysisKind = AnalogAnalysisKind

private[nodal] type NgspiceRawVariable = AnalogRawVariable
private[nodal] val NgspiceRawVariable = AnalogRawVariable

private[nodal] type NgspiceComplex = AnalogComplex
private[nodal] val NgspiceComplex = AnalogComplex

private[nodal] type NgspiceRawSchema = AnalogRawSchema
private[nodal] val NgspiceRawSchema = AnalogRawSchema

private[nodal] type NgspiceRawPlot = AnalogRawPlot
private[nodal] val NgspiceRawPlot = AnalogRawPlot

/** Strict entry point for the pinned Ngspice47 ASCII writer. */
private[nodal] object NgspiceRaw:
  def parse(
      bytes: Vector[Byte],
      expectedKind: AnalogAnalysisKind,
      maxPoints: Int = 100000,
      maxVariables: Int = 64
  ): Either[String, AnalogRawPlot] =
    SpiceAsciiRaw.parse(SpiceRawDialect.Ngspice47, bytes, expectedKind, maxPoints, maxVariables)

  def fold[A](
      input: InputStream,
      expectedKind: AnalogAnalysisKind,
      maxPoints: Int = 100000,
      maxVariables: Int = 64,
      asciiOnly: Boolean = false
  )(
      initialize: AnalogRawSchema => Either[String, A]
  )(
      consume: (A, Int, Vector[AnalogComplex]) => Either[String, A]
  ): Either[String, A] =
    SpiceAsciiRaw.fold(
      SpiceRawDialect.Ngspice47,
      input,
      expectedKind,
      maxPoints,
      maxVariables,
      asciiOnly
    )(initialize)(consume)
